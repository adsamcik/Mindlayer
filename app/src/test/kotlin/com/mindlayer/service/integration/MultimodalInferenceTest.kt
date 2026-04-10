package com.mindlayer.service.integration

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.Message
import com.mindlayer.AudioTransfer
import com.mindlayer.ImageTransfer
import com.mindlayer.RequestMeta
import com.mindlayer.SessionConfig
import com.mindlayer.service.MindlayerMlService
import com.mindlayer.service.engine.DeviceTier
import com.mindlayer.service.engine.EngineManager
import com.mindlayer.service.engine.InferenceOrchestrator
import com.mindlayer.service.engine.MemoryBudget
import com.mindlayer.service.engine.MemoryPressure
import com.mindlayer.service.engine.MemorySnapshot
import com.mindlayer.service.engine.SessionManager
import com.mindlayer.service.ipc.SharedMemoryPool
import com.mindlayer.service.ipc.StagedMedia
import com.mindlayer.service.ipc.TokenStreamWriter
import com.mindlayer.service.testutil.TestPipeHelper
import com.mindlayer.shared.StreamEvent
import com.mindlayer.shared.StreamHeader
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * Integration tests for multimodal inference paths through [InferenceOrchestrator].
 *
 * Verifies that image/audio [Content] parts are correctly constructed when
 * [ImageTransfer] and [AudioTransfer] objects are provided, that
 * [SharedMemoryPool] staging and cleanup are called in all paths (success,
 * error, cancellation), and that the pipe streaming contract holds for
 * multimodal inputs.
 *
 * Uses the same test infrastructure as [InferencePipelineTest]: real
 * [InferenceOrchestrator] and [SessionManager], mocked LiteRT-LM layer,
 * piped streams via writerFactory.
 *
 * ⚠️ Gemma 4 multimodal is blocked (issue #1874), but these tests verify
 * the ARCHITECTURE is correct — that the right Content types are constructed
 * and the staging/cleanup lifecycle is sound.
 */
class MultimodalInferenceTest {

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

    private val outputStreamQueue = ConcurrentLinkedQueue<OutputStream>()

    // -- Captured Contents for verification ----------------------------------

    private lateinit var capturedContents: MutableList<Contents>

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

        // LiteRT-LM mocks — capture the Contents argument
        capturedContents = mutableListOf()
        mockConversation = mockk(relaxed = true)
        every { mockConversation.sendMessageAsync(any<Contents>()) } answers {
            val contents = firstArg<Contents>()
            capturedContents.add(contents)
            messageFlow("response")
        }

        mockEngine = mockk(relaxed = true) {
            every { createConversation(any()) } returns mockConversation
        }
        engineManager = mockk(relaxed = true) {
            every { requireEngine() } returns mockEngine
            every { currentBackend } returns "GPU"
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
            every { stageImage(any()) } answers {
                val transfer = firstArg<ImageTransfer>()
                StagedMedia(
                    requestId = transfer.requestId,
                    filePath = "/staged/image_${transfer.requestId}.jpg",
                    mimeType = "image/jpeg",
                    cleanup = {},
                )
            }
            every { stageAudio(any()) } answers {
                val transfer = firstArg<AudioTransfer>()
                StagedMedia(
                    requestId = transfer.requestId,
                    filePath = "/staged/audio_${transfer.requestId}.wav",
                    mimeType = "audio/wav",
                    cleanup = {},
                )
            }
        }

        service = mockk(relaxed = true) {
            every { enterForeground() } returns Unit
            every { exitForeground() } returns Unit
        }

        context = mockk(relaxed = true)
        sessionManager = SessionManager(context, engineManager, memoryBudget)

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

    private fun textMessage(text: String?): Message = mockk {
        val textParts: List<Content> = if (text.isNullOrEmpty()) {
            emptyList()
        } else {
            listOf(mockk<Content.Text> { every { this@mockk.text } returns text })
        }
        every { contents } returns mockk {
            every { contents } returns textParts
        }
        every { toolCalls } returns emptyList()
    }

    private fun messageFlow(vararg texts: String): Flow<Message> = flow {
        for (t in texts) emit(textMessage(t))
    }

    private fun createPipe(): Pair<PipedInputStream, ParcelFileDescriptor> {
        val pipedIn = PipedInputStream(64 * 1024)
        val pipedOut = PipedOutputStream(pipedIn)
        outputStreamQueue.add(pipedOut)
        val pfd = mockk<ParcelFileDescriptor>(relaxed = true)
        return pipedIn to pfd
    }

    private fun readFrames(input: InputStream): List<String> =
        TestPipeHelper.readFrames(input)

    private fun parseFrames(rawFrames: List<String>): List<TestPipeHelper.ParsedEvent> =
        TestPipeHelper.parseFrames(rawFrames)

    private fun createSession(sessionId: String? = null): String =
        orchestrator.createSession(SessionConfig(sessionId = sessionId))

    private fun mockImageTransfer(requestId: String): ImageTransfer = mockk(relaxed = true) {
        every { this@mockk.requestId } returns requestId
        every { width } returns 640
        every { height } returns 480
        every { pixelFormat } returns 1 // RGBA_8888
        every { rowStride } returns 640 * 4
        every { payloadBytes } returns 640 * 480 * 4
        every { isSharedMemory } returns false
        every { mimeType } returns "image/jpeg"
    }

    private fun mockAudioTransfer(requestId: String): AudioTransfer = mockk(relaxed = true) {
        every { this@mockk.requestId } returns requestId
        every { mimeType } returns "audio/wav"
        every { isSharedMemory } returns false
        every { durationMs } returns 3000L
    }

    /**
     * Run inference and collect frames synchronously. Returns parsed events.
     * Supports optional image and audio transfers.
     */
    private fun inferAndCollect(
        sessionId: String,
        text: String?,
        requestId: String = "req-1",
        image: ImageTransfer? = null,
        audio: AudioTransfer? = null,
    ): List<TestPipeHelper.ParsedEvent> {
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
        orchestrator.infer(meta, image = image, audio = audio, pipeWriteEnd = pfd)

        assertTrue("Pipe should close within 30s", latch.await(30, TimeUnit.SECONDS))
        return parseFrames(frames)
    }

    /**
     * Extract the Content parts from the most recently captured [Contents].
     */
    private fun lastCapturedParts(): List<Content> {
        assertTrue("sendMessageAsync should have been called", capturedContents.isNotEmpty())
        return capturedContents.last().contents
    }

    // ========================================================================
    // Test 1: Text only — no media staging
    // ========================================================================

    @Test
    fun textOnly_noMediaStaging() {
        val sessionId = createSession()
        val events = inferAndCollect(sessionId, "Hello world")

        // Verify SharedMemoryPool was NOT called for staging
        verify(exactly = 0) { sharedMemoryPool.stageImage(any()) }
        verify(exactly = 0) { sharedMemoryPool.stageAudio(any()) }

        // Verify the Content parts contain only Text
        val parts = lastCapturedParts()
        assertEquals("Should have 1 content part", 1, parts.size)
        assertTrue("Part should be Content.Text", parts[0] is Content.Text)

        // Pipe should complete normally
        assertEquals("done", events.last().kind)
        assertEquals("stop", events.last().finishReason)
    }

    // ========================================================================
    // Test 2: Image only — staged and included
    // ========================================================================

    @Test
    fun imageOnly_stagedAndIncluded() {
        val requestId = "req-img"
        val sessionId = createSession()
        val image = mockImageTransfer(requestId)

        val events = inferAndCollect(sessionId, null, requestId = requestId, image = image)

        // Verify stageImage was called with the transfer
        verify(exactly = 1) { sharedMemoryPool.stageImage(image) }
        verify(exactly = 0) { sharedMemoryPool.stageAudio(any()) }

        // Verify Content parts: ImageFile only (no text was provided)
        val parts = lastCapturedParts()
        assertEquals("Should have 1 content part", 1, parts.size)
        assertTrue("Part should be Content.ImageFile", parts[0] is Content.ImageFile)

        // Pipe completes normally
        assertEquals("done", events.last().kind)
    }

    // ========================================================================
    // Test 3: Text + image — both included
    // ========================================================================

    @Test
    fun textPlusImage_bothIncluded() {
        val requestId = "req-txt-img"
        val sessionId = createSession()
        val image = mockImageTransfer(requestId)

        val events = inferAndCollect(
            sessionId, "Describe this image", requestId = requestId, image = image,
        )

        verify(exactly = 1) { sharedMemoryPool.stageImage(image) }

        // Verify Content parts: Text then ImageFile (order matches orchestrator)
        val parts = lastCapturedParts()
        assertEquals("Should have 2 content parts", 2, parts.size)
        assertTrue("First part should be Content.Text", parts[0] is Content.Text)
        assertTrue("Second part should be Content.ImageFile", parts[1] is Content.ImageFile)

        assertEquals("Describe this image", (parts[0] as Content.Text).text)

        assertEquals("done", events.last().kind)
    }

    // ========================================================================
    // Test 4: Audio only — staged and included
    // ========================================================================

    @Test
    fun audioOnly_stagedAndIncluded() {
        val requestId = "req-aud"
        val sessionId = createSession()
        val audio = mockAudioTransfer(requestId)

        val events = inferAndCollect(sessionId, null, requestId = requestId, audio = audio)

        verify(exactly = 0) { sharedMemoryPool.stageImage(any()) }
        verify(exactly = 1) { sharedMemoryPool.stageAudio(audio) }

        val parts = lastCapturedParts()
        assertEquals("Should have 1 content part", 1, parts.size)
        assertTrue("Part should be Content.AudioFile", parts[0] is Content.AudioFile)

        assertEquals("done", events.last().kind)
    }

    // ========================================================================
    // Test 5: Text + audio — both included
    // ========================================================================

    @Test
    fun textPlusAudio_bothIncluded() {
        val requestId = "req-txt-aud"
        val sessionId = createSession()
        val audio = mockAudioTransfer(requestId)

        val events = inferAndCollect(
            sessionId, "Transcribe this", requestId = requestId, audio = audio,
        )

        verify(exactly = 1) { sharedMemoryPool.stageAudio(audio) }

        val parts = lastCapturedParts()
        assertEquals("Should have 2 content parts", 2, parts.size)
        assertTrue("First part should be Content.Text", parts[0] is Content.Text)
        assertTrue("Second part should be Content.AudioFile", parts[1] is Content.AudioFile)

        assertEquals("Transcribe this", (parts[0] as Content.Text).text)

        assertEquals("done", events.last().kind)
    }

    // ========================================================================
    // Test 6: Text + image + audio — all included
    // ========================================================================

    @Test
    fun textPlusImagePlusAudio_allIncluded() {
        val requestId = "req-all"
        val sessionId = createSession()
        val image = mockImageTransfer(requestId)
        val audio = mockAudioTransfer(requestId)

        val events = inferAndCollect(
            sessionId, "Describe what you see and hear",
            requestId = requestId, image = image, audio = audio,
        )

        verify(exactly = 1) { sharedMemoryPool.stageImage(image) }
        verify(exactly = 1) { sharedMemoryPool.stageAudio(audio) }

        // Verify Content parts order: Text, ImageFile, AudioFile
        val parts = lastCapturedParts()
        assertEquals("Should have 3 content parts", 3, parts.size)
        assertTrue("First part should be Content.Text", parts[0] is Content.Text)
        assertTrue("Second part should be Content.ImageFile", parts[1] is Content.ImageFile)
        assertTrue("Third part should be Content.AudioFile", parts[2] is Content.AudioFile)

        assertEquals("Describe what you see and hear", (parts[0] as Content.Text).text)

        assertEquals("done", events.last().kind)
    }

    // ========================================================================
    // Test 7: Image staging fails gracefully
    // ========================================================================

    @Test
    fun imageStaging_failsGracefully() {
        every { sharedMemoryPool.stageImage(any()) } throws
            RuntimeException("Failed to read shared memory")

        val requestId = "req-img-fail"
        val sessionId = createSession()
        val image = mockImageTransfer(requestId)

        val events = inferAndCollect(
            sessionId, "Describe this", requestId = requestId, image = image,
        )

        // Should produce an error event mentioning staging failure
        val errorEvent = events.find { it.kind == "error" }
        assertNotNull("Should contain an error event", errorEvent)
        assertTrue(
            "Error should mention staging failure",
            errorEvent!!.errorMessage!!.contains("media_staging_failed"),
        )

        // Cleanup should still be called
        Thread.sleep(200)
        verify(atLeast = 1) { sharedMemoryPool.cleanup(requestId) }

        // sendMessageAsync should NOT have been called (staging failed before inference)
        assertTrue(
            "sendMessageAsync should not be called on staging failure",
            capturedContents.isEmpty(),
        )
    }

    // ========================================================================
    // Test 8: Audio staging fails gracefully
    // ========================================================================

    @Test
    fun audioStaging_failsGracefully() {
        every { sharedMemoryPool.stageAudio(any()) } throws
            IOException("PFD read error")

        val requestId = "req-aud-fail"
        val sessionId = createSession()
        val audio = mockAudioTransfer(requestId)

        val events = inferAndCollect(
            sessionId, "Transcribe", requestId = requestId, audio = audio,
        )

        val errorEvent = events.find { it.kind == "error" }
        assertNotNull("Should contain an error event", errorEvent)
        assertTrue(
            "Error should mention staging failure",
            errorEvent!!.errorMessage!!.contains("media_staging_failed"),
        )

        Thread.sleep(200)

        verify(atLeast = 1) { sharedMemoryPool.cleanup(requestId) }
        assertTrue(
            "sendMessageAsync should not be called on staging failure",
            capturedContents.isEmpty(),
        )
    }

    // ========================================================================
    // Test 9: Media cleanup called after successful inference
    // ========================================================================

    @Test
    fun mediaCleanup_calledAfterSuccess() {
        val requestId = "req-cleanup-ok"
        val sessionId = createSession()
        val image = mockImageTransfer(requestId)
        val audio = mockAudioTransfer(requestId)

        val events = inferAndCollect(
            sessionId, "Hello", requestId = requestId, image = image, audio = audio,
        )

        assertEquals("done", events.last().kind)
        assertEquals("stop", events.last().finishReason)

        // cleanup(requestId) is called in the coroutine finally block —
        // give it a moment to execute after the pipe closes
        Thread.sleep(200)
        Thread.sleep(200)
        verify(atLeast = 1) { sharedMemoryPool.cleanup(requestId) }
    }

    // ========================================================================
    // Test 10: Media cleanup called after inference error
    // ========================================================================

    @Test
    fun mediaCleanup_calledAfterError() {
        val requestId = "req-cleanup-err"

        // stageImage succeeds but inference throws
        every { mockConversation.sendMessageAsync(any<Contents>()) } answers {
            capturedContents.add(firstArg())
            throw RuntimeException("GPU out of memory")
        }

        val sessionId = createSession()
        val image = mockImageTransfer(requestId)

        val events = inferAndCollect(
            sessionId, "Hello", requestId = requestId, image = image,
        )

        // Should get an error event
        val errorEvent = events.find { it.kind == "error" }
        assertNotNull("Should contain an error event", errorEvent)

        // cleanup must still be called even though inference failed
        Thread.sleep(200)
        verify(atLeast = 1) { sharedMemoryPool.cleanup(requestId) }
    }

    // ========================================================================
    // Test 11: Media cleanup called after cancellation
    // ========================================================================

    @Test
    fun mediaCleanup_calledAfterCancel() {
        val requestId = "req-cleanup-cancel"

        every { mockConversation.sendMessageAsync(any<Contents>()) } answers {
            capturedContents.add(firstArg())
            flow {
                emit(textMessage("chunk1"))
                delay(500)
                emit(textMessage("chunk2"))
                delay(500)
                emit(textMessage("chunk3"))
            }
        }
        every { mockConversation.cancelProcess() } returns Unit

        val sessionId = createSession()
        val image = mockImageTransfer(requestId)
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
            textContent = "Describe this",
        )
        orchestrator.infer(meta, image = image, audio = null, pipeWriteEnd = pfd)

        // Let first chunk emit, then cancel
        Thread.sleep(200)
        orchestrator.cancelInference(requestId)

        assertTrue("Pipe should close within 30s", latch.await(30, TimeUnit.SECONDS))

        // cleanup is called in the coroutine finally block — wait for it
        Thread.sleep(500)
        Thread.sleep(200)
        verify(atLeast = 1) { sharedMemoryPool.cleanup(requestId) }
    }

    // ========================================================================
    // Test 12: ImageTransfer source PFD is passed to stageImage
    // ========================================================================

    @Test
    fun imageTransfer_passedToStageImage() {
        val requestId = "req-pfd"
        val mockPfd = mockk<ParcelFileDescriptor>(relaxed = true)
        val image = mockk<ImageTransfer>(relaxed = true) {
            every { this@mockk.requestId } returns requestId
            every { source } returns mockPfd
            every { width } returns 100
            every { height } returns 100
            every { mimeType } returns "image/png"
        }

        val imageSlot = slot<ImageTransfer>()
        every { sharedMemoryPool.stageImage(capture(imageSlot)) } returns StagedMedia(
            requestId = requestId,
            filePath = "/staged/test.png",
            mimeType = "image/png",
            cleanup = {},
        )

        val sessionId = createSession()
        inferAndCollect(sessionId, "Check image", requestId = requestId, image = image)

        // Verify the exact ImageTransfer object was passed through
        assertTrue("ImageTransfer should have been captured", imageSlot.isCaptured)
        assertEquals(
            "Captured transfer should be the same object",
            image, imageSlot.captured,
        )
    }

    // ========================================================================
    // Test 13: Multimodal streaming still works — token deltas arrive
    // ========================================================================

    @Test
    fun multimodal_streamingTokens() {
        val requestId = "req-stream"

        every { mockConversation.sendMessageAsync(any<Contents>()) } answers {
            capturedContents.add(firstArg())
            messageFlow("The image", " shows a", " cat")
        }

        val sessionId = createSession()
        val image = mockImageTransfer(requestId)

        val events = inferAndCollect(
            sessionId, "What is this?", requestId = requestId, image = image,
        )

        // Verify header + 3 deltas + done
        assertEquals("Should have 5 events", 5, events.size)
        assertEquals("header", events[0].kind)
        assertEquals("The image", events[1].text)
        assertEquals(" shows a", events[2].text)
        assertEquals(" cat", events[3].text)
        assertEquals("done", events[4].kind)

        // Verify event contract (monotonic seq, single terminal)
        TestPipeHelper.assertEventContract(events)
    }

    // ========================================================================
    // Test 14: Image staging fails but audio is never attempted
    // ========================================================================

    @Test
    fun imageStagingFails_audioNotAttempted() {
        every { sharedMemoryPool.stageImage(any()) } throws
            RuntimeException("Corrupt shared memory")

        val requestId = "req-img-fail-first"
        val sessionId = createSession()
        val image = mockImageTransfer(requestId)
        val audio = mockAudioTransfer(requestId)

        inferAndCollect(
            sessionId, "Describe", requestId = requestId, image = image, audio = audio,
        )

        // Image staging was attempted
        verify(exactly = 1) { sharedMemoryPool.stageImage(any()) }
        // Audio staging should NOT have been attempted (image failed first in the
        // orchestrator's sequential staging order)
        verify(exactly = 0) { sharedMemoryPool.stageAudio(any()) }
        // Cleanup should still be called
        Thread.sleep(200)
        verify(atLeast = 1) { sharedMemoryPool.cleanup(requestId) }
    }

    // ========================================================================
    // Test 15: Audio staging fails after image succeeds
    // ========================================================================

    @Test
    fun audioStagingFails_afterImageSucceeds() {
        every { sharedMemoryPool.stageAudio(any()) } throws
            RuntimeException("Audio PFD closed")

        val requestId = "req-aud-fail-second"
        val sessionId = createSession()
        val image = mockImageTransfer(requestId)
        val audio = mockAudioTransfer(requestId)

        val events = inferAndCollect(
            sessionId, "Process both", requestId = requestId, image = image, audio = audio,
        )

        // Image staging succeeded
        verify(exactly = 1) { sharedMemoryPool.stageImage(any()) }
        // Audio staging was attempted and failed
        verify(exactly = 1) { sharedMemoryPool.stageAudio(any()) }

        val errorEvent = events.find { it.kind == "error" }
        assertNotNull("Should contain an error event", errorEvent)
        assertTrue(
            "Error should mention staging failure",
            errorEvent!!.errorMessage!!.contains("media_staging_failed"),
        )

        // Cleanup still called
        Thread.sleep(200)
        verify(atLeast = 1) { sharedMemoryPool.cleanup(requestId) }
    }

    // ========================================================================
    // Test 16: Text-only sends no image/audio to SharedMemoryPool
    // ========================================================================

    @Test
    fun textOnly_sharedMemoryPoolUntouched() {
        val sessionId = createSession()
        inferAndCollect(sessionId, "Just text, no media")

        verify(exactly = 0) { sharedMemoryPool.stageImage(any()) }
        verify(exactly = 0) { sharedMemoryPool.stageAudio(any()) }
        // cleanup IS still called in the finally block (with requestId)
        Thread.sleep(200)
        verify(atLeast = 1) { sharedMemoryPool.cleanup(any()) }
    }

    // ========================================================================
    // Test 17: Multimodal event contract — monotonic seq, single terminal
    // ========================================================================

    @Test
    fun multimodal_eventContract() {
        every { mockConversation.sendMessageAsync(any<Contents>()) } answers {
            capturedContents.add(firstArg())
            messageFlow("alpha", "beta", "gamma")
        }

        val requestId = "req-contract"
        val sessionId = createSession()
        val image = mockImageTransfer(requestId)
        val audio = mockAudioTransfer(requestId)

        val events = inferAndCollect(
            sessionId, "Full multimodal", requestId = requestId, image = image, audio = audio,
        )

        // Full event contract: monotonic seq, single terminal, nothing after terminal
        TestPipeHelper.assertEventContract(events)

        // Verify the terminal is "done" with "stop"
        val terminal = events.last()
        assertEquals("done", terminal.kind)
        assertEquals("stop", terminal.finishReason)
    }
}
