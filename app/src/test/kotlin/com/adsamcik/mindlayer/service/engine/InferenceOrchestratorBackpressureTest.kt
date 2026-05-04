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
import java.io.OutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * F-009: regression coverage for the orchestrator's backpressure behaviour.
 *
 * Construction: the orchestrator is wired with `Dispatchers.IO` (default).
 * The writer factory hands the orchestrator a [TokenStreamWriter] backed by
 * a [BlockingOutputStream] whose `write` parks indefinitely on a
 * [CountDownLatch.await]. The writer is constructed with a short
 * `writeTimeoutMs` so the watchdog fires deterministically.
 *
 * Expectations:
 *  - The orchestrator's `runInference` unwinds within ~writeTimeout × small
 *    factor — it does not pin a worker forever.
 *  - `Conversation.cancelProcess()` is invoked when the watchdog fires
 *    (the rule from `engine.instructions.md`).
 *  - Other UIDs / requests can still progress (no global pin).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class InferenceOrchestratorBackpressureTest {

    private lateinit var context: Context
    private lateinit var engineManager: EngineManager
    private lateinit var memoryBudget: MemoryBudget
    private lateinit var sharedMemoryPool: SharedMemoryPool
    private lateinit var service: MindlayerMlService
    private lateinit var mockEngine: Engine
    private lateinit var mockConversation: Conversation
    private lateinit var sessionManager: SessionManager
    private lateinit var orchestrator: InferenceOrchestrator

    private val outputStreamQueue = ConcurrentLinkedQueue<OutputStream>()

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
        outputStreamQueue.clear()
        orchestrator = InferenceOrchestrator(
            service, sessionManager, sharedMemoryPool,
            writerFactory = { _ ->
                val out = outputStreamQueue.poll()
                    ?: error("No output stream queued for TokenStreamWriter")
                // Short backpressure timeout so the watchdog fires fast.
                TokenStreamWriter.forTesting(out, writeTimeoutMs = 200L)
            },
        )
    }

    @After
    fun tearDown() {
        orchestrator.shutdown()
        unmockkAll()
    }

    /**
     * Output stream that parks every write on a latch — simulates a peer
     * that holds the read end open without draining. Once `release()` is
     * called the latch fires and writes complete normally; until then,
     * the orchestrator must surface a backpressure timeout instead of
     * pinning the worker.
     */
    private class BlockingOutputStream : OutputStream() {
        private val latch = CountDownLatch(1)
        @Volatile var closed: Boolean = false
            private set

        override fun write(b: Int) {
            if (closed) throw java.io.IOException("closed")
            latch.await()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw java.io.IOException("closed")
            latch.await()
        }

        override fun close() {
            closed = true
            latch.countDown()
        }
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

    /** Endless flow of token chunks that never completes on its own. */
    private fun endlessFlow(): Flow<Message> = flow {
        var i = 0
        while (true) {
            emit(textMessage("tok-$i"))
            i++
            // Yield often so the orchestrator sees backpressure quickly.
            kotlinx.coroutines.delay(5)
        }
    }

    @Test
    fun `wedged peer does not pin orchestrator forever`() {
        every { mockConversation.sendMessageAsync(any<Contents>()) } returns endlessFlow()

        val sid = sessionManager.createSession(SessionConfig(maxTokens = 2048))

        val output = BlockingOutputStream()
        outputStreamQueue.add(output)

        val pfd = mockk<ParcelFileDescriptor>(relaxed = true)
        val meta = RequestMeta(
            requestId = "req-wedged",
            sessionId = sid,
            textContent = "hello",
        )

        val start = System.currentTimeMillis()
        orchestrator.infer("100:req-wedged", meta, image = null, audio = null, pipeWriteEnd = pfd)

        // Wait for watchdog to unwind the inference.
        val deadline = start + 10_000L
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            if (output.closed) break
        }

        val elapsed = System.currentTimeMillis() - start
        assertTrue(
            "Watchdog should have closed the writer within 10s, elapsed=${elapsed}ms",
            output.closed,
        )
        assertTrue("Inference must not pin worker, elapsed=${elapsed}ms", elapsed < 10_000L)

        // engine.instructions.md: cancelProcess MUST be called when the
        // watchdog fires so native inference stops.
        verify(timeout = 2_000L, atLeast = 1) { mockConversation.cancelProcess() }

        // Other UIDs continue: posting a fresh inference must still
        // progress past the writer factory — i.e., the dispatcher pool
        // is not exhausted.
        every { mockConversation.sendMessageAsync(any<Contents>()) } returns flow<Message> {
            emit(textMessage("ok"))
        }
        val sid2 = sessionManager.createSession(SessionConfig(maxTokens = 2048))
        val output2 = java.io.ByteArrayOutputStream()
        outputStreamQueue.add(output2)
        val pfd2 = mockk<ParcelFileDescriptor>(relaxed = true)
        orchestrator.infer(
            "200:req-other",
            RequestMeta(requestId = "req-other", sessionId = sid2, textContent = "hi"),
            image = null, audio = null, pipeWriteEnd = pfd2,
        )

        // Give the second inference up to 5s to write at least one frame.
        val gateDeadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < gateDeadline && output2.size() == 0) {
            Thread.sleep(50)
        }
        assertTrue("Second UID's inference should make progress", output2.size() > 0)
    }
}
