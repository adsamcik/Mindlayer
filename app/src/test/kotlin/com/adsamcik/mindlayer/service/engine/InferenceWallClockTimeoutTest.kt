package com.adsamcik.mindlayer.service.engine

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.adsamcik.mindlayer.RequestMeta
import com.adsamcik.mindlayer.SessionConfig
import com.adsamcik.mindlayer.service.MindlayerMlService
import com.adsamcik.mindlayer.service.ipc.SharedMemoryPool
import com.adsamcik.mindlayer.service.ipc.TokenStreamWriter
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.Message
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * F-061: when an inference exceeds the wall-clock cap, the orchestrator must:
 *  - call `Conversation.cancelProcess()` so native generation stops
 *  - emit a terminal `inference_timeout` error frame on the pipe
 *  - release the slot/concurrency accounting (writer is closed in finally)
 *
 * To keep the test fast we inject a tiny `maxInferenceMs`. The model is
 * stubbed with an endless flow so the only termination path is the timeout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class InferenceWallClockTimeoutTest {

    private lateinit var context: Context
    private lateinit var engineManager: EngineManager
    private lateinit var memoryBudget: MemoryBudget
    private lateinit var sharedMemoryPool: SharedMemoryPool
    private lateinit var service: MindlayerMlService
    private lateinit var mockEngine: Engine
    private lateinit var mockConversation: Conversation
    private lateinit var sessionManager: SessionManager
    private lateinit var orchestrator: InferenceOrchestrator

    private val outputStreams = ConcurrentLinkedQueue<ByteArrayOutputStream>()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 100_000L
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        mockConversation = mockk(relaxed = true)
        mockEngine = mockk(relaxed = true) {
            every { createConversation(any()) } returns mockConversation
        }
        engineManager = mockk(relaxed = true) {
            every { requireEngine() } returns mockEngine
            every { currentBackend } returns "GPU"
            every { isInitialized } returns true
        }

        val tier = DeviceTier(
            maxSessions = 6,
            defaultMaxTokens = 4096,
            maxMaxTokens = 8192,
            deviceRamMb = 16 * 1024L,
        )
        memoryBudget = mockk(relaxed = true) {
            every { deviceTier } returns tier
            every { currentSnapshot() } returns MemorySnapshot(
                availableMb = 4000L,
                totalMb = 16 * 1024L,
                lowMemory = false,
                pressure = MemoryPressure.NORMAL,
                recommendedMaxTokens = 8192,
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
        outputStreams.clear()
        orchestrator = InferenceOrchestrator(
            service, sessionManager, sharedMemoryPool,
            writerFactory = { _ ->
                val out = ByteArrayOutputStream()
                outputStreams.add(out)
                TokenStreamWriter.forTesting(out, writeTimeoutMs = 5_000L)
            },
            // F-061 test seam — fire the timeout in 500ms instead of 5min.
            maxInferenceMs = 500L,
        )
    }

    @After
    fun tearDown() {
        orchestrator.shutdown()
        unmockkAll()
    }

    private fun textMessage(text: String?): Message = mockk {
        val parts: List<Content> = if (text.isNullOrEmpty()) {
            emptyList()
        } else {
            listOf(mockk<Content.Text> inner@{ every { this@inner.text } returns text })
        }
        every { contents } returns mockk {
            every { contents } returns parts
        }
        every { toolCalls } returns emptyList()
    }

    /** Endless slow flow that only terminates if the orchestrator cancels. */
    private fun endlessFlow(): Flow<Message> = flow {
        var i = 0
        while (true) {
            emit(textMessage("tok-$i"))
            i++
            kotlinx.coroutines.delay(50)
        }
    }

    @Test
    fun `inference exceeding wall-clock cap closes with inference_timeout`() {
        every { mockConversation.sendMessageAsync(any<Contents>()) } returns endlessFlow()

        val sid = sessionManager.createSession(SessionConfig(maxTokens = 2048))
        val pfd = mockk<ParcelFileDescriptor>(relaxed = true)
        val meta = RequestMeta(
            requestId = "req-timeout",
            sessionId = sid,
            textContent = "hello",
        )

        val start = System.currentTimeMillis()
        orchestrator.infer("100:req-timeout", meta, image = null, audio = null, pipeWriteEnd = pfd)

        // Wait for the timeout path to wind up.
        val deadline = start + 10_000L
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            val out = outputStreams.peek()
            if (out != null && containsErrorReason(out.toByteArray(), "inference_timeout")) break
        }

        val elapsed = System.currentTimeMillis() - start
        assertTrue(
            "Timeout must fire within ~5s of injected cap (got ${elapsed}ms)",
            elapsed < 10_000L,
        )

        // Native cancellation MUST be invoked.
        verify(timeout = 2_000L, atLeast = 1) { mockConversation.cancelProcess() }

        val streamBytes = outputStreams.first().toByteArray()
        assertTrue(
            "Pipe must contain inference_timeout reason; bytes=${streamBytes.size}",
            containsErrorReason(streamBytes, "inference_timeout"),
        )
    }

    private fun containsErrorReason(bytes: ByteArray, needle: String): Boolean {
        // Frames are 4-byte LE length + UTF-8 JSON. We don't bother parsing —
        // the literal substring is unambiguous because the orchestrator
        // never emits unrelated user content in a unit test.
        return bytes.toString(Charsets.UTF_8).contains(needle)
    }
}
