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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * F-075: regression coverage for the binder-death cleanup pathway.
 *
 * When a client app's binder dies, [com.adsamcik.mindlayer.service.ServiceBinder]
 * invokes [InferenceOrchestrator.closeAllOwnedBy] (or the uid variant). The
 * audit (rubber-duck pass) found that the cleanup wiring already exists at:
 *
 *  - [InferenceOrchestrator.closeAllOwnedBy] (line 151) — iterates active
 *    scopedKeys and calls [InferenceOrchestrator.cancelInference] for each.
 *  - [InferenceOrchestrator.closeAllOwnedByUid] (line 158) — uid variant,
 *    same wiring.
 *  - [InferenceOrchestrator.cancelInference] (line 240+) — calls
 *    `toolCallBridge.cancel(scopedKey)` (line 257), which removes the
 *    pending entry from [ToolCallBridge.pending].
 *
 * These tests pin that wiring: a binder-death cleanup MUST drain the bridge's
 * pending state for every dead-caller scopedKey, not just leak it. Without
 * this regression coverage, a future refactor that drops the
 * `cancelInference` call from `closeAllOwnedBy` (or the
 * `toolCallBridge.cancel` call from `cancelInference`) would compile and
 * pass every other test, while silently leaking pending tool-call state per
 * dead client. Across many client-death events the leak compounds — every
 * registered tool call holds a [kotlinx.coroutines.CompletableDeferred] and
 * a `MutableList` entry alive for the lifetime of the service process.
 *
 * Setup mirrors [InferenceOrchestratorBackpressureTest] /
 * [InferenceOrchestratorCancelTest]: real [SessionManager], mocked
 * [Engine] / [Conversation] / [MindlayerMlService] / [SharedMemoryPool],
 * with a writer factory that swallows token output.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class InferenceOrchestratorBinderDeathTest {

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

    /**
     * Test owner token that implements [SessionOwnerToken] so both
     * `closeAllOwnedBy(ownerToken)` (which compares by reference equality on
     * `it.ownerToken == ownerToken`) and `closeAllOwnedByUid(ownerUid)`
     * (which filters by `it.ownerUid == ownerUid` after
     * `SessionManager.ownerUidFor` reads `ownerToken.ownerUid`) resolve to
     * the same session.
     */
    private data class TestOwnerToken(override val ownerUid: Int) : SessionOwnerToken

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
     * Endless flow that emits a text-only token every 50 ms. Holds the
     * orchestrator's `runInference` coroutine inside the
     * `sendMessageAsync.collect { ... }` block so the request stays
     * "in-flight" (`SessionManager.findSessionByActiveRequest` returns the
     * handle) until something cancels it. The flow does NOT emit toolCalls;
     * we seed [ToolCallBridge.pending] explicitly via the orchestrator's
     * exposed `toolCallBridge` to focus the assertion on the cleanup
     * wiring without depending on tool-allowlist plumbing.
     */
    private fun endlessFlow(): Flow<Message> = flow {
        var i = 0
        while (true) {
            emit(textMessage("tok-$i"))
            i++
            kotlinx.coroutines.delay(50)
        }
    }

    /** Spin-wait until predicate is true or [timeoutMs] elapses. */
    private fun await(timeoutMs: Long, intervalMs: Long = 25, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(intervalMs)
        }
        return predicate()
    }

    /**
     * Reach into the private `pending` map on [ToolCallBridge] so the test
     * can observe whether a binder-death cleanup actually drained it.
     * Mirrors the reflection pattern used in
     * [InferenceOrchestratorCancelTest.jobFor] and EngineManagerTest.
     */
    private fun pendingScopedKeys(): Set<String> {
        val field = ToolCallBridge::class.java.getDeclaredField("pending")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(orchestrator.toolCallBridge) as ConcurrentHashMap<String, *>
        return map.keys.toSet()
    }

    /**
     * Start an endless inference for [scopedKey] and wait until
     * `runInference` has actually entered the per-session mutex (so
     * `handle.activeRequestId == scopedKey` and
     * [SessionManager.findSessionByActiveRequest] returns non-null). Only
     * then does `closeAllOwnedBy*` have an `activeRequestId` to map to a
     * `cancelInference(scopedKey)` call.
     */
    private fun startEndlessInference(scopedKey: String, sid: String) {
        every { mockConversation.sendMessageAsync(any<Contents>()) } returns endlessFlow()
        val pfd = mockk<ParcelFileDescriptor>(relaxed = true)
        val meta = RequestMeta(
            requestId = scopedKey.substringAfter(':'),
            sessionId = sid,
            textContent = "hello",
        )
        orchestrator.infer(scopedKey, meta, image = null, audio = null, pipeWriteEnd = pfd)
        val ready = await(5_000L) {
            sessionManager.findSessionByActiveRequest(scopedKey) != null
        }
        assertTrue("Inference must reach in-flight state within 5s", ready)
    }

    // ---- Tests --------------------------------------------------------------

    @Test
    fun `binder death tears down active inference and clears toolCallBridge`() {
        val ownerToken = TestOwnerToken(ownerUid = 100)
        val sid = sessionManager.createSession(SessionConfig(maxTokens = 2048), ownerToken)
        val scopedKey = "100:req-binder-death"
        startEndlessInference(scopedKey, sid)

        // Seed the bridge as if the model had just emitted a tool call:
        // `registerPendingToolCalls` is the same call site the orchestrator
        // uses internally (InferenceOrchestrator.kt line 604), so the
        // bridge state we plant is byte-identical to the real one.
        orchestrator.toolCallBridge.registerPendingToolCalls(
            scopedKey,
            listOf("get_weather" to "{\"city\":\"NYC\"}"),
        )
        assertTrue(
            "Pre-condition: bridge should have the seeded scopedKey",
            scopedKey in pendingScopedKeys(),
        )

        // Simulate binder death: ServiceBinder calls closeAllOwnedBy with
        // the dead caller's ownerToken.
        orchestrator.closeAllOwnedBy(ownerToken)

        // Wiring check 1 — the binder-death pathway must drive
        // `cancelProcess()` so native LiteRT-LM stops decoding (covered
        // for the explicit-cancel path by InferenceOrchestratorCancelTest;
        // re-asserted here so this regression test is self-contained).
        verify(timeout = 2_000L, atLeast = 1) { mockConversation.cancelProcess() }

        // Wiring check 2 — `toolCallBridge.cancel(scopedKey)` was called
        // and the pending entry is gone. This is the actual binder-death
        // tool-bridge regression: if a refactor drops this call,
        // pending tool-call state leaks per dead client forever.
        val drained = await(2_000L) { scopedKey !in pendingScopedKeys() }
        assertTrue(
            "closeAllOwnedBy MUST clear ToolCallBridge.pending for the dead caller's scopedKey",
            drained,
        )
        assertEquals(
            "ToolCallBridge.pending should be fully empty after binder-death cleanup",
            emptySet<String>(),
            pendingScopedKeys(),
        )
    }

    @Test
    fun `closeAllOwnedByUid path also clears toolCallBridge`() {
        val ownerToken = TestOwnerToken(ownerUid = 200)
        val sid = sessionManager.createSession(SessionConfig(maxTokens = 2048), ownerToken)
        val scopedKey = "200:req-binder-uid"
        startEndlessInference(scopedKey, sid)

        orchestrator.toolCallBridge.registerPendingToolCalls(
            scopedKey,
            listOf("lookup" to "{}"),
        )
        assertTrue(
            "Pre-condition: bridge should have the seeded scopedKey",
            scopedKey in pendingScopedKeys(),
        )

        // The uid variant runs when ServiceBinder did not retain a token
        // reference but still has the caller's UID (defense-in-depth path
        // in the binder-death linkage).
        orchestrator.closeAllOwnedByUid(200)

        verify(timeout = 2_000L, atLeast = 1) { mockConversation.cancelProcess() }
        val drained = await(2_000L) { scopedKey !in pendingScopedKeys() }
        assertTrue(
            "closeAllOwnedByUid MUST clear ToolCallBridge.pending for the dead caller's scopedKey",
            drained,
        )
        assertEquals(
            "ToolCallBridge.pending should be fully empty after binder-death cleanup",
            emptySet<String>(),
            pendingScopedKeys(),
        )
    }

    @Test
    fun `closeAllOwnedBy with no active requests is a clean no-op`() {
        val ownerToken = TestOwnerToken(ownerUid = 300)
        // Create a session but never call infer() — there is no
        // activeRequestId, so closeAllOwnedBy iterates an empty list and
        // never reaches cancelInference.
        sessionManager.createSession(SessionConfig(maxTokens = 2048), ownerToken)
        assertEquals(
            "Pre-condition: bridge starts empty when no inference has run",
            emptySet<String>(),
            pendingScopedKeys(),
        )

        // Must not throw, even with nothing in flight.
        orchestrator.closeAllOwnedBy(ownerToken)

        // Bridge stays empty (nothing was there to cancel).
        assertEquals(emptySet<String>(), pendingScopedKeys())
        // No active conversation means no native cancel was issued — the
        // `cancelInference` for-loop body did not execute.
        verify(exactly = 0) { mockConversation.cancelProcess() }
    }
}
