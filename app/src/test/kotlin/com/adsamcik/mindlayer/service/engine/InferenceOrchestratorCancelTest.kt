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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Locks the **native cancellation invariant** documented in
 * `engine.instructions.md`:
 *
 *  > LiteRT-LM `Conversation.cancelProcess()` is **explicit** — Flow
 *  > cancellation alone does NOT stop native work.
 *
 * The orchestrator MUST therefore call `cancelProcess()` on every
 * cancellation path or native generation leaks (the engine keeps
 * decoding in the background, holding the KV cache and burning the
 * GPU/NPU). Without this regression coverage, a refactor that removes
 * any of the three call sites — explicit `cancelInference`, timeout
 * catch, or `CancellationException` catch — would compile, all other
 * tests would still pass, and inference would silently leak forever on
 * broken-pipe / client-death scenarios.
 *
 * These tests pin two of the three sites at the orchestrator level
 * (the timeout site is already covered by
 * `InferenceWallClockTimeoutTest`, and the broken-pipe path is covered
 * indirectly by `InferenceOrchestratorBackpressureTest`):
 *
 *  1. Explicit `cancelInference(scopedKey)` invokes
 *     `Conversation.cancelProcess()` exactly once for the active
 *     request, and also tears down `ToolCallBridge` + the running Job.
 *  2. Cancelling the orchestrator's underlying Job directly (simulating
 *     a parent-scope cancel without going through `cancelInference`)
 *     STILL drives `cancelProcess()` via the
 *     `runInference -> catch (CancellationException)` handler. The
 *     orchestrator must NOT rely on every cancellation flowing through
 *     the public API; the catch is the safety net.
 *  3. Calling `cancelInference` on a scopedKey with no active request
 *     is a clean no-op — does not crash, does not invoke
 *     `cancelProcess()` against a stale conversation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class InferenceOrchestratorCancelTest {

    private lateinit var context: Context
    private lateinit var engineManager: EngineManager
    private lateinit var memoryBudget: MemoryBudget
    private lateinit var sharedMemoryPool: SharedMemoryPool
    private lateinit var service: MindlayerMlService
    private lateinit var mockEngine: Engine
    private lateinit var mockConversation: Conversation
    private lateinit var sessionManager: SessionManager
    private lateinit var orchestrator: InferenceOrchestrator

    private val outputStreamQueue = ConcurrentLinkedQueue<java.io.OutputStream>()

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
                val out = outputStreamQueue.poll() ?: ByteArrayOutputStream()
                TokenStreamWriter.forTesting(out, writeTimeoutMs = 60_000L)
            },
        )
    }

    @After
    fun tearDown() {
        orchestrator.shutdown()
        unmockkAll()
    }

    // ---- Helpers ------------------------------------------------------------

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

    /**
     * Endless flow that emits a token every 50ms forever. Lets the test
     * synchronise on "inference is in flight" (sendMessageAsync was called
     * and SessionManager has registered the active request) before issuing
     * the cancel.
     */
    private fun endlessFlow(): Flow<Message> = flow {
        var i = 0
        while (true) {
            emit(textMessage("tok-$i"))
            i++
            kotlinx.coroutines.delay(50)
        }
    }

    /** Spin-wait until the predicate is true or timeoutMs elapses. */
    private fun await(timeoutMs: Long, intervalMs: Long = 25, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(intervalMs)
        }
        return predicate()
    }

    /**
     * Reach into `activeJobs` so a test can directly cancel the underlying
     * coroutine without going through the public `cancelInference` API.
     * This simulates parent-scope cancellation / broken-pipe driven cancel,
     * which must STILL invoke `cancelProcess()` via the
     * `CancellationException` catch in `runInference`.
     */
    @Suppress("UNCHECKED_CAST")
    private fun jobFor(scopedKey: String): Job? {
        val field = InferenceOrchestrator::class.java.getDeclaredField("activeJobs")
        field.isAccessible = true
        val map = field.get(orchestrator) as java.util.concurrent.ConcurrentHashMap<String, Job>
        return map[scopedKey]
    }

    private fun startEndlessInference(scopedKey: String, sid: String): RequestMeta {
        every { mockConversation.sendMessageAsync(any<Contents>()) } returns endlessFlow()
        val pfd = mockk<ParcelFileDescriptor>(relaxed = true)
        val meta = RequestMeta(
            requestId = scopedKey.substringAfter(':'),
            sessionId = sid,
            textContent = "hello",
        )
        orchestrator.infer(scopedKey, meta, image = null, audio = null, pipeWriteEnd = pfd)
        // Wait until the orchestrator has actually entered runInference and
        // SessionManager has registered the active request — only then is
        // there a Conversation for cancelInference to act on.
        val ready = await(5_000L) {
            sessionManager.findSessionByActiveRequest(scopedKey) != null
        }
        assertTrue("Inference must reach in-flight state within 5s for cancel test", ready)
        return meta
    }

    // ---- Tests --------------------------------------------------------------

    @Test
    fun `cancelInference invokes Conversation cancelProcess on the active session`() {
        val sid = sessionManager.createSession(SessionConfig(maxTokens = 2048))
        startEndlessInference("100:req-direct-cancel", sid)

        orchestrator.cancelInference("100:req-direct-cancel")

        // The whole point of this test: the public cancel API MUST drive
        // cancelProcess so native LiteRT-LM stops decoding.
        verify(timeout = 2_000L, exactly = 1) { mockConversation.cancelProcess() }

        // The active job must also be torn down — cancelInference is the
        // single source of truth for "this request is over".
        val ended = await(2_000L) { jobFor("100:req-direct-cancel")?.isActive != true }
        assertTrue("activeJobs entry must be cancelled / removed after cancelInference", ended)
    }

    @Test
    fun `cancelInference on inactive scopedKey is a clean no-op`() {
        // No session, no inference, nothing in flight.
        orchestrator.cancelInference("999:req-never-existed")

        // No conversation to cancel — the call must NOT touch the mock,
        // and must NOT crash.
        verify(exactly = 0) { mockConversation.cancelProcess() }
    }

    @Test
    fun `parent-scope coroutine cancel still drives cancelProcess via runInference catch`() {
        val sid = sessionManager.createSession(SessionConfig(maxTokens = 2048))
        startEndlessInference("100:req-job-cancel", sid)

        // Cancel the underlying coroutine Job directly, bypassing the
        // public cancelInference API entirely. This simulates a parent-
        // scope cancellation (e.g. broken pipe → writer raises
        // CancellationException to unwind the coroutine).
        val job = jobFor("100:req-job-cancel")
        assertNotNull("Active job must exist for the in-flight inference", job)
        job!!.cancel()

        // The architectural invariant: the runInference catch
        // (InferenceOrchestrator.kt:689) MUST observe CancellationException
        // and call cancelProcess(), because LiteRT-LM keeps decoding
        // natively otherwise. If a future refactor drops that catch or
        // removes cancelProcess from it, native generation leaks forever.
        verify(timeout = 2_000L, atLeast = 1) { mockConversation.cancelProcess() }
    }
}
