package com.adsamcik.mindlayer.service.integration

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.Message
import com.adsamcik.mindlayer.RequestMeta
import com.adsamcik.mindlayer.SessionConfig
import com.adsamcik.mindlayer.service.MindlayerMlService
import com.adsamcik.mindlayer.service.engine.DeviceTier
import com.adsamcik.mindlayer.service.engine.EngineManager
import com.adsamcik.mindlayer.service.engine.InferenceOrchestrator
import com.adsamcik.mindlayer.service.engine.MemoryBudget
import com.adsamcik.mindlayer.service.engine.MemoryPressure
import com.adsamcik.mindlayer.service.engine.MemorySnapshot
import com.adsamcik.mindlayer.service.engine.SessionManager
import com.adsamcik.mindlayer.service.ipc.SharedMemoryPool
import com.adsamcik.mindlayer.service.ipc.TokenStreamWriter
import com.adsamcik.mindlayer.shared.StreamEvent
import com.adsamcik.mindlayer.shared.StreamHeader
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Integration tests for the full inference pipeline: request → session →
 * streamed response over a pipe.
 *
 * Uses real [InferenceOrchestrator], real [SessionManager], and real
 * [com.adsamcik.mindlayer.service.ipc.TokenStreamWriter]. The LiteRT-LM
 * [Engine]/[Conversation]/[Message] layer is mocked so tests run on a
 * plain JVM without native libraries.
 *
 * The Android [ParcelFileDescriptor.AutoCloseOutputStream] constructor is
 * intercepted via [mockkConstructor] so it delegates writes to Java piped
 * streams instead of requiring real file descriptors.
 */
class InferencePipelineTest {

    // -- Shared constants & JSON parser --------------------------------------

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // -- Mocks ---------------------------------------------------------------

    private lateinit var context: Context
    private lateinit var engineManager: EngineManager
    private lateinit var memoryBudget: MemoryBudget
    private lateinit var sharedMemoryPool: SharedMemoryPool
    private lateinit var service: MindlayerMlService
    private lateinit var mockEngine: Engine
    private lateinit var mockConversation: Conversation

    // -- Real components under test ------------------------------------------

    private lateinit var sessionManager: SessionManager
    private lateinit var orchestrator: InferenceOrchestrator

    // -- Pipe wiring via writerFactory ---------------------------------------

    /**
     * Queue of [PipedOutputStream]s. Each [createPipe] enqueues one; the
     * custom [writerFactory] dequeues it and creates a [TokenStreamWriter]
     * backed by that stream instead of a real [ParcelFileDescriptor].
     */
    private val outputStreamQueue = ConcurrentLinkedQueue<OutputStream>()

    @Before
    fun setUp() {
        mockkStatic(SystemClock::class)
        mockkStatic(Log::class)

        every { SystemClock.elapsedRealtime() } returns 100_000L
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        // LiteRT-LM mocks
        mockConversation = mockk(relaxed = true)
        mockEngine = mockk(relaxed = true) {
            every { createConversation(any()) } returns mockConversation
        }
        engineManager = mockk(relaxed = true) {
            every { isInitialized } returns true
            every { requireEngine() } returns mockEngine
            every { currentBackend } returns "GPU"
            every { isInitialized } returns true
        }

        val generousTier = DeviceTier(
            maxSessions = 6,
            defaultMaxTokens = 16384,
            maxMaxTokens = 32768,
            deviceRamMb = 16 * 1024L,
        )
        memoryBudget = mockk(relaxed = true) {
            every { deviceTier } returns generousTier
            every { currentSnapshot() } returns MemorySnapshot(
                availableMb = 4000L,
                totalMb = 16 * 1024L,
                lowMemory = false,
                pressure = MemoryPressure.NORMAL,
                recommendedMaxTokens = 32768,
            )
        }

        sharedMemoryPool = mockk(relaxed = true) {
            every { cleanup(any()) } returns Unit
            every { cleanupAll() } returns Unit
        }

        service = mockk(relaxed = true) {
            every { enterForeground() } returns Unit
            every { exitForeground() } returns Unit
        }

        context = mockk(relaxed = true)
        sessionManager = SessionManager(context, engineManager, memoryBudget)

        // Inject a writerFactory that bypasses ParcelFileDescriptor
        outputStreamQueue.clear()
        orchestrator = InferenceOrchestrator(
            service, sessionManager, sharedMemoryPool,
            writerFactory = { _ ->
                val out = outputStreamQueue.poll()
                    ?: error("No output stream queued for TokenStreamWriter")
                TokenStreamWriter.forTesting(out)
            },
        )
    }

    @After
    fun tearDown() {
        orchestrator.shutdown()
        unmockkAll()
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Create a mock [Message] that has the given [text] and no tool calls.
     */
    private fun textMessage(text: String?): Message = mockk {
        val textParts: List<Content> = if (text.isNullOrEmpty()) {
            emptyList()
        } else {
            listOf(mockk<Content.Text> inner@{ every { this@inner.text } returns text })
        }
        every { contents } returns mockk {
            every { contents } returns textParts
        }
        every { toolCalls } returns emptyList()
    }

    /**
     * Build a [Flow] of [Message] chunks that emits the given texts.
     */
    private fun messageFlow(vararg texts: String): Flow<Message> = flow {
        for (t in texts) emit(textMessage(t))
    }

    /**
     * Create a pipe pair. Returns (readEnd, writePfd).
     *
     * Enqueues a [PipedOutputStream] into [outputStreamQueue] so the next
     * constructor-mocked [AutoCloseOutputStream] will pick it up.
     */
    private fun createPipe(): Pair<PipedInputStream, ParcelFileDescriptor> {
        val pipedIn = PipedInputStream(64 * 1024)
        val pipedOut = PipedOutputStream(pipedIn)
        outputStreamQueue.add(pipedOut)
        val pfd = mockk<ParcelFileDescriptor>(relaxed = true)
        return pipedIn to pfd
    }

    /**
     * Read length-prefixed JSON frames from [input] until EOF or IOException.
     * Frame format: 4-byte LE u32 length + UTF-8 payload.
     */
    private fun readFrames(input: InputStream): List<String> {
        val frames = mutableListOf<String>()
        val dis = DataInputStream(BufferedInputStream(input))
        try {
            while (true) {
                val len = try {
                    Integer.reverseBytes(dis.readInt())
                } catch (_: EOFException) {
                    break
                }
                if (len < 0 || len > 1_048_576) break
                val bytes = ByteArray(len)
                dis.readFully(bytes)
                frames.add(bytes.decodeToString())
            }
        } catch (_: IOException) {
            // pipe closed — normal termination
        }
        return frames
    }

    /** Typed event wrapper for assertion convenience. */
    private data class ParsedEvent(
        val kind: String, // "header", "token_delta", "done", "error", ...
        val text: String? = null,
        val finishReason: String? = null,
        val requestId: String? = null,
        val errorMessage: String? = null,
        val seq: Long? = null,
    )

    private fun parseFrames(rawFrames: List<String>): List<ParsedEvent> =
        rawFrames.map(::parseOneFrame)

    private fun parseOneFrame(raw: String): ParsedEvent {
        // Try header first (has "protocol" field, no "type")
        try {
            val header = json.decodeFromString<StreamHeader>(raw)
            if (header.protocol.isNotEmpty()) {
                return ParsedEvent(kind = "header", requestId = header.requestId)
            }
        } catch (_: Exception) { /* not a header */ }

        // Must be a StreamEvent
        val event = json.decodeFromString<StreamEvent>(raw)
        return when (event.type) {
            "token_delta" -> ParsedEvent(
                kind = "token_delta",
                text = event.payload["text"]?.jsonPrimitive?.contentOrNull,
                seq = event.seq,
            )
            "done" -> ParsedEvent(
                kind = "done",
                finishReason = event.payload["finish_reason"]?.jsonPrimitive?.contentOrNull,
                seq = event.seq,
            )
            "error" -> ParsedEvent(
                kind = "error",
                errorMessage = event.payload["message"]?.jsonPrimitive?.contentOrNull,
                seq = event.seq,
            )
            else -> ParsedEvent(kind = event.type, seq = event.seq)
        }
    }

    /** Create a session with default config via the orchestrator. */
    private fun createSession(sessionId: String? = null): String =
        orchestrator.createSession(SessionConfig(sessionId = sessionId))

    /**
     * Run inference and collect frames synchronously. Blocks until the pipe
     * closes. Returns the parsed events.
     */
    private fun inferAndCollect(
        sessionId: String,
        text: String,
        requestId: String = "req-1",
    ): List<ParsedEvent> {
        val (pipedIn, pfd) = createPipe()

        val latch = CountDownLatch(1)
        var frames: List<String> = emptyList()
        Thread {
            frames = readFrames(pipedIn)
            latch.countDown()
        }.apply { isDaemon = true; start() }

        val meta = RequestMeta(
            requestId = requestId,
            sessionId = sessionId,
            textContent = text,
        )
        orchestrator.infer("test:" + meta.requestId, meta, image = null, audio = null, pipeWriteEnd = pfd)

        assertTrue("Pipe should close within 30s", latch.await(30, TimeUnit.SECONDS))
        return parseFrames(frames)
    }

    // ========================================================================
    // Test 1: Full pipeline — 3 text chunks
    // ========================================================================

    @Test
    fun textInference_fullPipeline() {
        every { mockConversation.sendMessageAsync(any<Contents>()) } returns
            messageFlow("Hello", " world", "!")

        val sessionId = createSession()
        val events = inferAndCollect(sessionId, "Hello")

        // Expect: header + 3 token_deltas + metrics + done
        assertEquals("Should have 6 events", 6, events.size)

        assertEquals("header", events[0].kind)
        assertNotNull(events[0].requestId)

        assertEquals("token_delta", events[1].kind)
        assertEquals("Hello", events[1].text)
        assertEquals(1L, events[1].seq)

        assertEquals("token_delta", events[2].kind)
        assertEquals(" world", events[2].text)
        assertEquals(2L, events[2].seq)

        assertEquals("token_delta", events[3].kind)
        assertEquals("!", events[3].text)
        assertEquals(3L, events[3].seq)

        assertEquals("metrics", events[4].kind)
        assertEquals(4L, events[4].seq)
        assertEquals("done", events[5].kind)
        assertEquals("stop", events[5].finishReason)
        assertEquals(5L, events[5].seq)
    }

    // ========================================================================
    // Test 2: Empty response — no deltas
    // ========================================================================

    @Test
    fun textInference_emptyResponse() {
        every { mockConversation.sendMessageAsync(any<Contents>()) } returns
            flowOf(textMessage(""))

        val sessionId = createSession()
        val events = inferAndCollect(sessionId, "Hello")

        // Expect: header + metrics + done (no token_delta since text is empty)
        assertEquals("Should have 3 events", 3, events.size)
        assertEquals("header", events[0].kind)
        assertEquals("metrics", events[1].kind)
        assertEquals("done", events[2].kind)
        assertEquals("stop", events[2].finishReason)
    }

    // ========================================================================
    // Test 3: Cancel mid-stream
    // ========================================================================

    @Test
    fun textInference_cancelMidStream() {
        val requestId = "cancel-req"

        // Conversation emits chunks slowly so we can cancel mid-stream
        every { mockConversation.sendMessageAsync(any<Contents>()) } returns flow {
            emit(textMessage("chunk1"))
            delay(500)
            emit(textMessage("chunk2"))
            delay(500)
            emit(textMessage("chunk3"))
        }
        every { mockConversation.cancelProcess() } returns Unit

        val sessionId = createSession()
        val (pipedIn, pfd) = createPipe()

        val latch = CountDownLatch(1)
        var frames: List<String> = emptyList()
        Thread {
            frames = readFrames(pipedIn)
            latch.countDown()
        }.apply { isDaemon = true; start() }

        val meta = RequestMeta(
            requestId = requestId,
            sessionId = sessionId,
            textContent = "Hello",
        )
        orchestrator.infer("test:" + meta.requestId, meta, image = null, audio = null, pipeWriteEnd = pfd)

        // Wait for the first chunk, then cancel. Cancel uses the same
        // scoped key the infer() call was registered under.
        Thread.sleep(200)
        orchestrator.cancelInference("test:" + requestId)

        assertTrue("Pipe should close within 30s", latch.await(30, TimeUnit.SECONDS))

        val events = parseFrames(frames)
        // Should have at least a header; may have some deltas before
        // cancellation; should end with a "done" with "cancelled" reason
        assertTrue("Should have at least 1 event", events.isNotEmpty())
        val doneEvent = events.lastOrNull { it.kind == "done" }
        assertNotNull("Should have a done event", doneEvent)
        assertEquals("cancelled", doneEvent!!.finishReason)
    }

    // ========================================================================
    // Test 4: Session not found → error
    // ========================================================================

    @Test
    fun textInference_sessionNotFound() {
        val (pipedIn, pfd) = createPipe()

        val latch = CountDownLatch(1)
        var frames: List<String> = emptyList()
        Thread {
            frames = readFrames(pipedIn)
            latch.countDown()
        }.apply { isDaemon = true; start() }

        val meta = RequestMeta(
            requestId = "req-missing",
            sessionId = "nonexistent-session",
            textContent = "Hello",
        )
        orchestrator.infer("test:" + meta.requestId, meta, image = null, audio = null, pipeWriteEnd = pfd)

        assertTrue("Pipe should close within 30s", latch.await(30, TimeUnit.SECONDS))

        val events = parseFrames(frames)
        assertTrue("Should have at least 1 event", events.isNotEmpty())
        val errorEvent = events.find { it.kind == "error" }
        assertNotNull("Should contain an error event", errorEvent)
        assertTrue(
            "Error should mention unknown session",
            errorEvent!!.errorMessage!!.contains("Unknown session"),
        )
    }

    // ========================================================================
    // Test 5: Session lifecycle — create, list, destroy
    // ========================================================================

    @Test
    fun sessionLifecycle_createListDestroy() {
        val id1 = createSession("session-A")
        val id2 = createSession("session-B")

        assertEquals("session-A", id1)
        assertEquals("session-B", id2)

        // List should contain both
        val list1 = orchestrator.listSessions()
        assertEquals(2, list1.size)
        val ids1 = list1.map { it.sessionId }.toSet()
        assertTrue(ids1.contains("session-A"))
        assertTrue(ids1.contains("session-B"))

        // Both should be individually retrievable
        assertNotNull(orchestrator.getSessionInfo("session-A"))
        assertNotNull(orchestrator.getSessionInfo("session-B"))

        // Destroy one
        orchestrator.destroySession("session-A")

        // Only session-B remains
        val list2 = orchestrator.listSessions()
        assertEquals(1, list2.size)
        assertEquals("session-B", list2[0].sessionId)

        assertNull(orchestrator.getSessionInfo("session-A"))
        assertNotNull(orchestrator.getSessionInfo("session-B"))
    }

    // ========================================================================
    // Test 6: Foreground state management
    // ========================================================================

    @Test
    fun foregroundState_managedCorrectly() {
        every { mockConversation.sendMessageAsync(any<Contents>()) } returns
            messageFlow("response")

        val sessionId = createSession()
        val events = inferAndCollect(sessionId, "Hello")

        // Inference should complete successfully
        assertEquals("done", events.last().kind)

        // Verify service foreground lifecycle was called
        verify(atLeast = 1) { service.enterForeground() }
        verify(atLeast = 1) { service.exitForeground() }
    }

    // ========================================================================
    // Test 7: Concurrent inferences — different sessions
    // ========================================================================

    @Test
    fun concurrentInferences_differentSessions() = runTest {
        // Each session gets its own mock conversation with unique text
        val conversation1 = mockk<Conversation>(relaxed = true) {
            every { sendMessageAsync(any<Contents>()) } returns flow {
                emit(textMessage("A1"))
                delay(50)
                emit(textMessage("A2"))
            }
        }
        val conversation2 = mockk<Conversation>(relaxed = true) {
            every { sendMessageAsync(any<Contents>()) } returns flow {
                emit(textMessage("B1"))
                delay(50)
                emit(textMessage("B2"))
            }
        }

        // Return different conversations for sequential createConversation calls
        var callCount = 0
        every { mockEngine.createConversation(any()) } answers {
            callCount++
            if (callCount == 1) conversation1 else conversation2
        }

        val sessionA = createSession("sess-A")
        val sessionB = createSession("sess-B")

        // Create both pipes
        val (pipedInA, pfdA) = createPipe()
        val (pipedInB, pfdB) = createPipe()

        val latchA = CountDownLatch(1)
        val latchB = CountDownLatch(1)
        var framesA: List<String> = emptyList()
        var framesB: List<String> = emptyList()

        Thread {
            framesA = readFrames(pipedInA)
            latchA.countDown()
        }.apply { isDaemon = true; start() }

        Thread {
            framesB = readFrames(pipedInB)
            latchB.countDown()
        }.apply { isDaemon = true; start() }

        val metaA = RequestMeta(requestId = "req-A", sessionId = sessionA, textContent = "Hi A")
        val metaB = RequestMeta(requestId = "req-B", sessionId = sessionB, textContent = "Hi B")

        orchestrator.infer("test:" + metaA.requestId, metaA, null, null, pfdA)
        orchestrator.infer("test:" + metaB.requestId, metaB, null, null, pfdB)

        assertTrue("Pipe A should close within 30s", latchA.await(30, TimeUnit.SECONDS))
        assertTrue("Pipe B should close within 30s", latchB.await(30, TimeUnit.SECONDS))

        val eventsA = parseFrames(framesA)
        val eventsB = parseFrames(framesB)

        // Identify which pipe got which session's data by checking the header
        val headerA = eventsA.first { it.kind == "header" }
        val headerB = eventsB.first { it.kind == "header" }

        // Determine the expected token deltas for each pipe based on requestId
        val (expectedDeltasA, expectedDeltasB) = if (headerA.requestId == "req-A") {
            listOf("A1", "A2") to listOf("B1", "B2")
        } else {
            listOf("B1", "B2") to listOf("A1", "A2")
        }

        val deltasA = eventsA.filter { it.kind == "token_delta" }.map { it.text }
        val deltasB = eventsB.filter { it.kind == "token_delta" }.map { it.text }

        assertEquals(expectedDeltasA, deltasA)
        assertEquals(expectedDeltasB, deltasB)

        // Both pipes should complete with "done"
        assertTrue(eventsA.any { it.kind == "done" && it.finishReason == "stop" })
        assertTrue(eventsB.any { it.kind == "done" && it.finishReason == "stop" })

        // Verify foreground entered/exited for both inferences
        verify(exactly = 2) { service.enterForeground() }
        verify(exactly = 2) { service.exitForeground() }
    }

    // ========================================================================
    // Test 8: Engine throws → error event on pipe + cleanup
    // ========================================================================

    @Test
    fun textInference_engineThrows_producesError() {
        every { mockConversation.sendMessageAsync(any<Contents>()) } throws
            RuntimeException("GPU out of memory")

        val sessionId = createSession()
        val events = inferAndCollect(sessionId, "Hello", requestId = "req-throw")

        // Should produce an error event with a sanitised class-name label.
        // Exception messages are NOT forwarded because native LiteRT-LM errors
        // can embed prompt content in them.
        val errorEvent = events.find { it.kind == "error" }
        assertNotNull("Should contain an error event", errorEvent)
        assertTrue(
            "Error should carry the sanitised exception class name, not its message",
            errorEvent!!.errorMessage!!.contains("RuntimeException"),
        )
        assertFalse(
            "Error must NOT leak the raw exception message (could contain prompt content)",
            errorEvent.errorMessage.contains("GPU out of memory"),
        )

        // Active job should be cleaned up (invokeOnCompletion removes it)
        Thread.sleep(200) // allow invokeOnCompletion to fire
        val activeJobField = orchestrator.javaClass.getDeclaredField("activeJobs")
        activeJobField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val activeJobs = activeJobField.get(orchestrator) as java.util.concurrent.ConcurrentHashMap<String, *>
        assertNull("Active job should be cleaned up", activeJobs["req-throw"])
    }

    // ========================================================================
    // Test 9: Terminal event contract on normal inference
    // ========================================================================

    @Test
    fun textInference_terminalEventContract() {
        every { mockConversation.sendMessageAsync(any<Contents>()) } returns
            messageFlow("alpha", "beta", "gamma")

        val sessionId = createSession()
        val events = inferAndCollect(sessionId, "contract test")

        // Convert local ParsedEvents to TestPipeHelper.ParsedEvents for contract check
        val helperEvents = events.map { e ->
            com.adsamcik.mindlayer.service.testutil.TestPipeHelper.ParsedEvent(
                kind = e.kind,
                text = e.text,
                finishReason = e.finishReason,
                requestId = e.requestId,
                errorMessage = e.errorMessage,
                seq = e.seq,
            )
        }
        com.adsamcik.mindlayer.service.testutil.TestPipeHelper.assertEventContract(helperEvents)
    }

    // ========================================================================
    // Test 10: Destroy session during active inference
    // ========================================================================

    @Test
    fun destroySession_duringActiveInference() {
        val requestId = "req-destroy"

        every { mockConversation.sendMessageAsync(any<Contents>()) } returns flow {
            emit(textMessage("chunk1"))
            delay(1000)
            emit(textMessage("chunk2"))
            delay(1000)
            emit(textMessage("chunk3"))
        }
        every { mockConversation.cancelProcess() } returns Unit

        val sessionId = createSession()
        val (pipedIn, pfd) = createPipe()

        val latch = CountDownLatch(1)
        var frames: List<String> = emptyList()
        Thread {
            frames = readFrames(pipedIn)
            latch.countDown()
        }.apply { isDaemon = true; start() }

        val meta = RequestMeta(
            requestId = requestId,
            sessionId = sessionId,
            textContent = "Hello",
        )
        orchestrator.infer("test:" + meta.requestId, meta, image = null, audio = null, pipeWriteEnd = pfd)

        // Let first chunk emit, then destroy the session
        Thread.sleep(200)
        orchestrator.destroySession(sessionId)
        orchestrator.cancelInference("test:" + requestId)

        assertTrue("Pipe should close within 30s", latch.await(30, TimeUnit.SECONDS))

        val events = parseFrames(frames)
        assertTrue("Should have at least 1 event", events.isNotEmpty())
        // Should end with either a done (cancelled) or error — clean termination
        val terminal = events.last { it.kind == "done" || it.kind == "error" }
        assertTrue(
            "Terminal event should be done or error",
            terminal.kind == "done" || terminal.kind == "error",
        )
    }

    // ========================================================================
    // Test 11: Pipe closed early — inference aborts cleanly
    // ========================================================================

    @Test
    fun pipeClosedEarly_inferenceAbortsCleanly() {
        val requestId = "req-pipe-close"

        every { mockConversation.sendMessageAsync(any<Contents>()) } returns flow {
            emit(textMessage("chunk1"))
            delay(300)
            emit(textMessage("chunk2"))
            delay(300)
            emit(textMessage("chunk3"))
        }

        val sessionId = createSession()
        val pipedIn = PipedInputStream(64 * 1024)
        val pipedOut = PipedOutputStream(pipedIn)
        outputStreamQueue.add(pipedOut)
        val pfd = mockk<ParcelFileDescriptor>(relaxed = true)

        val meta = RequestMeta(
            requestId = requestId,
            sessionId = sessionId,
            textContent = "Hello",
        )
        orchestrator.infer("test:" + meta.requestId, meta, image = null, audio = null, pipeWriteEnd = pfd)

        // Close the read end early to simulate client disconnect
        Thread.sleep(150)
        pipedIn.close()

        // Wait for the job to finish — should not crash
        Thread.sleep(2000)

        // Active job should be cleaned up
        val activeJobField = orchestrator.javaClass.getDeclaredField("activeJobs")
        activeJobField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val activeJobs = activeJobField.get(orchestrator) as java.util.concurrent.ConcurrentHashMap<String, *>
        assertNull("Active job should be cleaned up after pipe close", activeJobs[requestId])

        // Foreground state should have been exited
        verify(atLeast = 1) { service.exitForeground() }
    }

    // ========================================================================
    // Test 12: Active job cleaned up after normal inference
    // ========================================================================

    @Test
    fun textInference_activeJobCleanedUp() {
        every { mockConversation.sendMessageAsync(any<Contents>()) } returns
            messageFlow("done-test")

        val sessionId = createSession()
        val requestId = "req-cleanup"
        val events = inferAndCollect(sessionId, "Hello", requestId = requestId)

        assertEquals("done", events.last().kind)

        // Allow invokeOnCompletion to fire
        Thread.sleep(200)

        val activeJobField = orchestrator.javaClass.getDeclaredField("activeJobs")
        activeJobField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val activeJobs = activeJobField.get(orchestrator) as java.util.concurrent.ConcurrentHashMap<String, *>
        assertNull("Request should be removed from activeJobs after completion", activeJobs[requestId])
    }
}
