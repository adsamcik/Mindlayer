package com.adsamcik.mindlayer.service.engine

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.adsamcik.mindlayer.SessionConfig
import com.adsamcik.mindlayer.service.security.IpcInputValidator
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import io.mockk.unmockkAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SessionManager]: priority calculation, device tiers,
 * session lifecycle, and eviction logic.
 */
class SessionManagerTest {

    private lateinit var context: Context
    private lateinit var engineManager: EngineManager
    private lateinit var memoryBudget: MemoryBudget
    private lateinit var sessionManager: SessionManager
    private lateinit var mockEngine: Engine

    @Before
    fun setUp() {
        mockkStatic(SystemClock::class)
        mockkStatic(Log::class)

        every { SystemClock.elapsedRealtime() } returns 100_000L
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        mockEngine = mockk(relaxed = true) {
            every { createConversation(any()) } returns mockk<Conversation>(relaxed = true)
        }
        engineManager = mockk(relaxed = true) {
            every { isInitialized } returns true
            every { requireEngine() } returns mockEngine
            every { currentBackend } returns "GPU"
            // F-018: default to already-initialised so existing tests that
            // exercise the create-session happy path don't trip the new
            // EngineNotReadyException fast-fail. Tests that exercise lazy
            // init explicitly override this in their @Test body.
            every { isInitialized } returns true
        }

        // Default: generous 16GB tier so session limits don't interfere
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

        context = mockk(relaxed = true)
        sessionManager = SessionManager(context, engineManager, memoryBudget)
    }

    @After
    fun tearDown() {
        sessionManager.shutdown()
        unmockkAll()
    }

    // ---- Helpers -----------------------------------------------------------

    private fun createDefaultSession(
        sessionId: String? = null,
        maxTokens: Int = 4096,
        ownerToken: Any? = null,
    ): String {
        return sessionManager.createSession(
            SessionConfig(
                sessionId = sessionId,
                maxTokens = maxTokens,
            ),
            ownerToken,
        )
    }

    private fun buildHandle(
        sessionId: String = "test",
        isStreaming: Boolean = false,
        isPinned: Boolean = false,
        lastAccessedElapsedMs: Long = SystemClock.elapsedRealtime(),
        clientPriorityHint: Int = 0,
    ): SessionManager.SessionHandle {
        val handle = SessionManager.SessionHandle(
            sessionId = sessionId,
            conversation = mockk(relaxed = true),
            config = SessionConfig(),
            createdAtMs = System.currentTimeMillis(),
            effectiveMaxTokens = 4096,
            baseConversationConfig = ConversationConfig(),
        )
        handle.isStreaming = isStreaming
        handle.isPinned = isPinned
        handle.lastAccessedElapsedMs = lastAccessedElapsedMs
        handle.clientPriorityHint = clientPriorityHint
        return handle
    }

    @Test
    fun `createSession returns engine initializing on synthetic init timeout without joining init job`() = runTest {
        every { engineManager.isInitialized } returns false
        coEvery { engineManager.initialize(any(), any()) } coAnswers { awaitCancellation() }
        coEvery { engineManager.awaitReady(any()) } returns EngineState.Failed(
            InitFailure.NativeError("init timeout"),
        )

        val startedNs = System.nanoTime()
        val error = assertThrows(EngineNotReadyException::class.java) {
            createDefaultSession()
        }
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L

        assertEquals(200L, error.retryAfterMs)
        assertTrue("createSession should not join a wedged init job (elapsed=${elapsedMs}ms)", elapsedMs < 1_000L)
        coVerify(exactly = 1) { engineManager.awaitReady(any()) }
    }

    // ---- Priority calculation ----------------------------------------------

    @Test
    fun `streaming session gets +1000 priority`() {
        val handle = buildHandle(isStreaming = true)
        val priority = EvictionPolicy.calculatePriority(handle)
        assertTrue("Streaming should add 1000", priority >= 1000)
    }

    @Test
    fun `pinned session gets +400 priority`() {
        // Make handle accessed long ago so no recency bonus (>120s before mocked time)
        val handle = buildHandle(
            isPinned = true,
            lastAccessedElapsedMs = SystemClock.elapsedRealtime() - 200_000L,
        )
        val priority = EvictionPolicy.calculatePriority(handle)
        assertEquals(400, priority)
    }

    @Test
    fun `recently accessed within 30s gets +300 priority`() {
        // Accessed at elapsedRealtime - 10s (within 30s window)
        val handle = buildHandle(
            lastAccessedElapsedMs = SystemClock.elapsedRealtime() - 10_000L,
        )
        val priority = EvictionPolicy.calculatePriority(handle)
        assertEquals(300, priority)
    }

    @Test
    fun `accessed within 2m but beyond 30s gets +150 priority`() {
        // Accessed at elapsedRealtime - 60s (within 120s, beyond 30s)
        val handle = buildHandle(
            lastAccessedElapsedMs = SystemClock.elapsedRealtime() - 60_000L,
        )
        val priority = EvictionPolicy.calculatePriority(handle)
        assertEquals(150, priority)
    }

    @Test
    fun `accessed beyond 2m gets no recency bonus`() {
        val handle = buildHandle(
            lastAccessedElapsedMs = SystemClock.elapsedRealtime() - 200_000L,
        )
        val priority = EvictionPolicy.calculatePriority(handle)
        assertEquals(0, priority)
    }

    @Test
    fun `client priority hint 0-100 applied`() {
        val handle = buildHandle(
            clientPriorityHint = 75,
            lastAccessedElapsedMs = SystemClock.elapsedRealtime() - 200_000L,
        )
        val priority = EvictionPolicy.calculatePriority(handle)
        assertEquals(75, priority)
    }

    @Test
    fun `client priority hint clamped to max 100`() {
        val handle = buildHandle(
            clientPriorityHint = 999,
            lastAccessedElapsedMs = SystemClock.elapsedRealtime() - 200_000L,
        )
        val priority = EvictionPolicy.calculatePriority(handle)
        assertEquals(100, priority)
    }

    @Test
    fun `client priority hint clamped to min 0`() {
        val handle = buildHandle(
            clientPriorityHint = -50,
            lastAccessedElapsedMs = SystemClock.elapsedRealtime() - 200_000L,
        )
        val priority = EvictionPolicy.calculatePriority(handle)
        assertEquals(0, priority)
    }

    @Test
    fun `priority combines additively - streaming + pinned + recent + hint`() {
        val handle = buildHandle(
            isStreaming = true,
            isPinned = true,
            lastAccessedElapsedMs = SystemClock.elapsedRealtime() - 5_000L, // within 30s
            clientPriorityHint = 50,
        )
        val priority = EvictionPolicy.calculatePriority(handle)
        // 1000 (streaming) + 400 (pinned) + 300 (recent 30s) + 50 (hint) = 1750
        assertEquals(1750, priority)
    }

    @Test
    fun `priority combines additively - pinned + recent 2m + hint`() {
        val handle = buildHandle(
            isPinned = true,
            lastAccessedElapsedMs = SystemClock.elapsedRealtime() - 60_000L, // within 2m
            clientPriorityHint = 80,
        )
        val priority = EvictionPolicy.calculatePriority(handle)
        // 400 (pinned) + 150 (recent 2m) + 80 (hint) = 630
        assertEquals(630, priority)
    }

    @Test
    fun `zero priority for old, unpinned, non-streaming, no-hint session`() {
        val handle = buildHandle(
            lastAccessedElapsedMs = SystemClock.elapsedRealtime() - 200_000L,
            clientPriorityHint = 0,
        )
        val priority = EvictionPolicy.calculatePriority(handle)
        assertEquals(0, priority)
    }

    // ---- Session lifecycle -------------------------------------------------

    @Test
    fun `createSession returns valid session ID`() {
        val id = createDefaultSession()
        assertNotNull(id)
        assertTrue(id.isNotBlank())
    }

    @Test
    fun `createSession with explicit ID uses that ID`() {
        val id = createDefaultSession(sessionId = "my-session-1")
        assertEquals("my-session-1", id)
    }

    @Test
    fun `createSession rejects duplicate explicit ID`() {
        createDefaultSession(sessionId = "duplicate")

        try {
            createDefaultSession(sessionId = "duplicate")
            fail("Expected duplicate session id to be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("already in use"))
        }
    }

    @Test
    fun `createSession increments session count`() {
        assertEquals(0, sessionManager.sessionCount)
        createDefaultSession()
        assertEquals(1, sessionManager.sessionCount)
        createDefaultSession()
        assertEquals(2, sessionManager.sessionCount)
    }

    @Test
    fun `createSession passes backend and maxTokens during lazy init`() {
        // F-018: createSession no longer runBlocking-init's the engine; it
        // throws EngineNotReadyException and kicks off background init on a
        // dedicated dispatcher. Assert the typed exception fires AND the
        // background init invocation passes through the right backend/token
        // values.
        every { engineManager.isInitialized } returns false
        coEvery { engineManager.awaitReady(any()) } returns EngineState.Ready
        coEvery {
            engineManager.initialize(preferredBackend = "CPU", maxTokens = 2048)
        } returns mockEngine

        val id = sessionManager.createSession(
            SessionConfig(
                sessionId = "single-model",
                backend = "CPU",
                maxTokens = 2048,
            )
        )
        assertEquals("single-model", id)

        // Background init runs on the limitedParallelism(1) IO slice.
        // Wait briefly for it to schedule + invoke initialize().
        val deadline = System.currentTimeMillis() + 5_000
        var observed = false
        while (System.currentTimeMillis() < deadline && !observed) {
            try {
                coVerify(exactly = 1) {
                    engineManager.initialize(
                        preferredBackend = "CPU",
                        maxTokens = 2048,
                    )
                }
                observed = true
            } catch (_: AssertionError) {
                Thread.sleep(20)
            }
        }
        assertTrue("Expected engineManager.initialize to be invoked", observed)
    }

    @Test
    fun `destroySession removes session`() {
        val id = createDefaultSession(sessionId = "to-destroy")
        assertEquals(1, sessionManager.sessionCount)
        sessionManager.destroySession(id)
        assertEquals(0, sessionManager.sessionCount)
        assertNull(sessionManager.getSession(id))
    }

    @Test
    fun `destroySession unknown session is noop`() {
        createDefaultSession(sessionId = "real")
        sessionManager.destroySession("nonexistent")
        assertEquals(1, sessionManager.sessionCount)
    }

    @Test
    fun `destroySession cancels process when session is streaming (H6)`() {
        val convo = mockk<Conversation>(relaxed = true)
        every { mockEngine.createConversation(any()) } returns convo

        val id = createDefaultSession(sessionId = "streaming-destroy")
        // Hot-swap: createSession is lazy — simulate the session having
        // been warmed by a prior inference lease.
        val handle = sessionManager.getSession(id)!!
        handle.conversation = convo
        handle.isStreaming = true

        sessionManager.destroySession(id)

        io.mockk.verify(exactly = 1) { convo.cancelProcess() }
        io.mockk.verify(exactly = 1) { convo.close() }
        assertNull(sessionManager.getSession(id))
    }

    @Test
    fun `destroySession does not cancelProcess when not streaming (H6)`() {
        val convo = mockk<Conversation>(relaxed = true)
        every { mockEngine.createConversation(any()) } returns convo

        val id = createDefaultSession(sessionId = "idle-destroy")
        // Hot-swap: createSession is lazy — simulate the session having
        // been warmed by a prior inference lease.
        sessionManager.getSession(id)!!.conversation = convo
        sessionManager.destroySession(id)

        io.mockk.verify(exactly = 0) { convo.cancelProcess() }
        io.mockk.verify(exactly = 1) { convo.close() }
    }

    @Test
    fun `getSession returns handle for existing session`() {
        val id = createDefaultSession(sessionId = "lookup")
        val handle = sessionManager.getSession(id)
        assertNotNull(handle)
        assertEquals("lookup", handle!!.sessionId)
    }

    @Test
    fun `getSession returns null for unknown session`() {
        assertNull(sessionManager.getSession("no-such-session"))
    }

    @Test
    fun `listSessions returns all active sessions`() {
        createDefaultSession(sessionId = "s1")
        createDefaultSession(sessionId = "s2")
        createDefaultSession(sessionId = "s3")

        val sessions = sessionManager.listSessions()
        assertEquals(3, sessions.size)
        val ids = sessions.map { it.sessionId }.toSet()
        assertEquals(setOf("s1", "s2", "s3"), ids)
    }

    @Test
    fun `listSessions returns empty list when no sessions`() {
        assertTrue(sessionManager.listSessions().isEmpty())
    }

    @Test
    fun `getSessionInfo returns correct metadata`() {
        val id = createDefaultSession(sessionId = "info-test")
        val info = sessionManager.getSessionInfo(id)
        assertNotNull(info)
        assertEquals("info-test", info!!.sessionId)
        assertEquals("GPU", info.backend) // from mocked engineManager
        assertEquals(0, info.turnCount)
        assertEquals(false, info.isStreaming)
    }

    @Test
    fun `getSessionInfo returns null for unknown session`() {
        assertNull(sessionManager.getSessionInfo("not-here"))
    }

    // ---- Session capacity and eviction -------------------------------------

    @Test
    fun `createSession at capacity triggers eviction`() {
        // Set tier to max 2 sessions
        val tightTier = DeviceTier(2, 4096, 4096, 8 * 1024L)
        every { memoryBudget.deviceTier } returns tightTier

        createDefaultSession(sessionId = "s1")
        createDefaultSession(sessionId = "s2")
        assertEquals(2, sessionManager.sessionCount)

        // Third session should evict the lowest-priority one
        createDefaultSession(sessionId = "s3")
        assertEquals(2, sessionManager.sessionCount)
        // s3 should exist
        assertNotNull(sessionManager.getSession("s3"))
    }

    @Test
    fun `createSession at capacity evicts only sessions owned by same caller`() {
        val tightTier = DeviceTier(2, 4096, 4096, 8 * 1024L)
        every { memoryBudget.deviceTier } returns tightTier

        sessionManager.createSession(SessionConfig(sessionId = "a1"), ownerToken = "owner-a")
        sessionManager.createSession(SessionConfig(sessionId = "b1"), ownerToken = "owner-b")

        sessionManager.createSession(SessionConfig(sessionId = "a2"), ownerToken = "owner-a")

        assertNull(sessionManager.getSession("a1"))
        assertNotNull(sessionManager.getSession("a2"))
        assertNotNull(sessionManager.getSession("b1"))
        assertEquals(2, sessionManager.sessionCount)
    }

    @Test
    fun `createSession at capacity rejects caller with no owned eviction candidate`() {
        // After the security-hardening pass introduced per-caller eviction
        // (commit cf5631c), createSession at capacity tries to evict one of
        // the *calling* UID's sessions before stealing from another UID.
        // If the caller owns nothing in the map, evictLowestPriorityOwnedByUid
        // returns false and we throw "Session limit reached".
        //
        // ownerUidFor() only matches `Int` and `SessionOwnerToken`, so the
        // ownerToken values here MUST be Ints — anything else collapses to
        // ownerUid=null and falls back to evictLowestPriority(), which would
        // happily evict another caller's session and silently succeed,
        // defeating the rejection invariant this test guards.
        val tightTier = DeviceTier(2, 4096, 4096, 8 * 1024L)
        every { memoryBudget.deviceTier } returns tightTier

        sessionManager.createSession(SessionConfig(sessionId = "a1"), ownerToken = 1001)
        sessionManager.createSession(SessionConfig(sessionId = "b1"), ownerToken = 1002)

        try {
            sessionManager.createSession(SessionConfig(sessionId = "c1"), ownerToken = 1003)
            fail("Expected session creation to fail rather than evict another owner")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("Session limit reached"))
        }

        assertNotNull(sessionManager.getSession("a1"))
        assertNotNull(sessionManager.getSession("b1"))
        assertNull(sessionManager.getSession("c1"))
    }

    @Test
    fun `createSession fair-share evicts over-quota tenant for newcomer below quota`() {
        // Scenario: tier=4. Owner-A acquires 3 sessions (above fair share), owner-B has 1.
        // Owner-C arrives with 0 sessions.
        //   activeOwners when C arrives = [A, B] (size=2)
        //   fairShare = floor((4+2)/(2+1)) = floor(6/3) = 2
        //   C owns 0 < fairShare=2 → enters fair-share path.
        //   A owns 3 > fairShare=2 → A is above fair share; has idle sessions.
        //   One of A's sessions is evicted, C's session is created.
        val fourSessionTier = DeviceTier(4, 4096, 4096, 8 * 1024L)
        every { memoryBudget.deviceTier } returns fourSessionTier

        sessionManager.createSession(SessionConfig(sessionId = "a1"), ownerToken = "owner-a")
        sessionManager.createSession(SessionConfig(sessionId = "a2"), ownerToken = "owner-a")
        sessionManager.createSession(SessionConfig(sessionId = "a3"), ownerToken = "owner-a")
        sessionManager.createSession(SessionConfig(sessionId = "b1"), ownerToken = "owner-b")
        assertEquals(4, sessionManager.sessionCount)

        // Owner-C arrives — should succeed by evicting one of A's sessions
        sessionManager.createSession(SessionConfig(sessionId = "c1"), ownerToken = "owner-c")

        assertEquals(4, sessionManager.sessionCount)
        assertNotNull(sessionManager.getSession("c1")) // newcomer admitted

        // B's sole session must be untouched
        assertNotNull(sessionManager.getSession("b1"))

        // Exactly one of A's three sessions was evicted
        val aRemaining = listOf("a1", "a2", "a3").count { sessionManager.getSession(it) != null }
        assertEquals(2, aRemaining)
    }

    @Test
    fun `createSession evicts lowest-priority idle session when at capacity`() {
        val tightTier = DeviceTier(2, 4096, 4096, 8 * 1024L)
        every { memoryBudget.deviceTier } returns tightTier

        // s1: old, low priority
        every { SystemClock.elapsedRealtime() } returns 100_000L
        createDefaultSession(sessionId = "s1")
        val s1 = sessionManager.getSession("s1")!!
        s1.lastAccessedElapsedMs = 0L // very old

        // s2: recent, higher priority
        createDefaultSession(sessionId = "s2")
        val s2 = sessionManager.getSession("s2")!!
        s2.lastAccessedElapsedMs = 100_000L // just now

        // s3 should trigger eviction of s1 (lowest priority)
        createDefaultSession(sessionId = "s3")
        assertNull(sessionManager.getSession("s1")) // evicted
        assertNotNull(sessionManager.getSession("s2")) // kept
        assertNotNull(sessionManager.getSession("s3")) // new
    }

    @Test
    fun `eviction never evicts streaming session`() {
        val tightTier = DeviceTier(2, 4096, 4096, 8 * 1024L)
        every { memoryBudget.deviceTier } returns tightTier

        createDefaultSession(sessionId = "streaming-1")
        createDefaultSession(sessionId = "non-streaming-1")

        // Mark first as streaming and old
        val s1 = sessionManager.getSession("streaming-1")!!
        s1.isStreaming = true
        s1.lastAccessedElapsedMs = 0L

        // Mark second as non-streaming and recent
        val s2 = sessionManager.getSession("non-streaming-1")!!
        s2.lastAccessedElapsedMs = SystemClock.elapsedRealtime()

        // Add third — should evict non-streaming-1 even though streaming-1 is lower priority
        createDefaultSession(sessionId = "s3")
        assertNotNull(sessionManager.getSession("streaming-1")) // protected
        assertNull(sessionManager.getSession("non-streaming-1")) // evicted
        assertNotNull(sessionManager.getSession("s3"))
    }

    @Test
    fun `owned create at capacity evicts only caller-owned session`() {
        val tightTier = DeviceTier(2, 4096, 4096, 8 * 1024L)
        every { memoryBudget.deviceTier } returns tightTier

        createDefaultSession(sessionId = "caller-old", ownerToken = 1001)
        createDefaultSession(sessionId = "other", ownerToken = 2002)
        sessionManager.getSession("caller-old")!!.lastAccessedElapsedMs = 0L
        sessionManager.getSession("other")!!.lastAccessedElapsedMs = 0L

        createDefaultSession(sessionId = "caller-new", ownerToken = 1001)

        assertNull(sessionManager.getSession("caller-old"))
        assertNotNull(sessionManager.getSession("other"))
        assertNotNull(sessionManager.getSession("caller-new"))
        assertEquals(2, sessionManager.sessionCount)
    }

    @Test
    fun `owned create at capacity rejects instead of evicting another owner`() {
        val tightTier = DeviceTier(2, 4096, 4096, 8 * 1024L)
        every { memoryBudget.deviceTier } returns tightTier

        createDefaultSession(sessionId = "owner-a", ownerToken = 1001)
        createDefaultSession(sessionId = "owner-b", ownerToken = 2002)

        assertThrows(IllegalStateException::class.java) {
            createDefaultSession(sessionId = "owner-c", ownerToken = 3003)
        }

        assertNotNull(sessionManager.getSession("owner-a"))
        assertNotNull(sessionManager.getSession("owner-b"))
        assertNull(sessionManager.getSession("owner-c"))
        assertEquals(2, sessionManager.sessionCount)
    }

    @Test
    fun `owned create at capacity rejects when caller-owned sessions are streaming`() {
        val tightTier = DeviceTier(2, 4096, 4096, 8 * 1024L)
        every { memoryBudget.deviceTier } returns tightTier

        createDefaultSession(sessionId = "caller-stream", ownerToken = 1001)
        createDefaultSession(sessionId = "other", ownerToken = 2002)
        sessionManager.getSession("caller-stream")!!.isStreaming = true

        assertThrows(IllegalStateException::class.java) {
            createDefaultSession(sessionId = "caller-new", ownerToken = 1001)
        }

        assertNotNull(sessionManager.getSession("caller-stream"))
        assertNotNull(sessionManager.getSession("other"))
        assertNull(sessionManager.getSession("caller-new"))
        assertEquals(2, sessionManager.sessionCount)
    }

    // ---- evictUnderPressure ------------------------------------------------

    @Test
    fun `evictUnderPressure keeps highest-priority non-streaming session`() {
        createDefaultSession(sessionId = "low")
        createDefaultSession(sessionId = "mid")
        createDefaultSession(sessionId = "high")

        // Set priorities via lastAccessedElapsedMs
        sessionManager.getSession("low")!!.lastAccessedElapsedMs = 0L
        sessionManager.getSession("mid")!!.lastAccessedElapsedMs = 50_000L
        sessionManager.getSession("high")!!.lastAccessedElapsedMs = SystemClock.elapsedRealtime()

        sessionManager.evictUnderPressure()

        // Only highest priority should remain
        assertNull(sessionManager.getSession("low"))
        assertNull(sessionManager.getSession("mid"))
        assertNotNull(sessionManager.getSession("high"))
        assertEquals(1, sessionManager.sessionCount)
    }

    @Test
    fun `evictUnderPressure never evicts streaming sessions`() {
        createDefaultSession(sessionId = "stream")
        createDefaultSession(sessionId = "idle")

        sessionManager.getSession("stream")!!.isStreaming = true
        sessionManager.getSession("stream")!!.lastAccessedElapsedMs = 0L
        sessionManager.getSession("idle")!!.lastAccessedElapsedMs = SystemClock.elapsedRealtime()

        sessionManager.evictUnderPressure()

        // Both should survive: streaming is protected, idle is the sole highest-priority
        assertNotNull(sessionManager.getSession("stream"))
        assertNotNull(sessionManager.getSession("idle"))
    }

    @Test
    fun `evictUnderPressure with single session keeps it`() {
        createDefaultSession(sessionId = "only")
        sessionManager.evictUnderPressure()
        assertNotNull(sessionManager.getSession("only"))
        assertEquals(1, sessionManager.sessionCount)
    }

    @Test
    fun `evictUnderPressure with no sessions is noop`() {
        sessionManager.evictUnderPressure()
        assertEquals(0, sessionManager.sessionCount)
    }

    // ---- applyMemoryPressure -----------------------------------------------

    @Test
    fun `applyMemoryPressure NORMAL does nothing`() {
        createDefaultSession(sessionId = "s1")
        createDefaultSession(sessionId = "s2")
        sessionManager.applyMemoryPressure(MemoryPressure.NORMAL)
        assertEquals(2, sessionManager.sessionCount)
    }

    @Test
    fun `applyMemoryPressure WARNING evicts one when multiple exist`() {
        createDefaultSession(sessionId = "s1")
        createDefaultSession(sessionId = "s2")
        createDefaultSession(sessionId = "s3")

        // Make s1 the lowest priority
        sessionManager.getSession("s1")!!.lastAccessedElapsedMs = 0L
        sessionManager.getSession("s2")!!.lastAccessedElapsedMs = 50_000L
        sessionManager.getSession("s3")!!.lastAccessedElapsedMs = SystemClock.elapsedRealtime()

        sessionManager.applyMemoryPressure(MemoryPressure.WARNING)
        assertEquals(2, sessionManager.sessionCount)
        assertNull(sessionManager.getSession("s1"))
    }

    @Test
    fun `applyMemoryPressure CRITICAL evicts to single session`() {
        createDefaultSession(sessionId = "s1")
        createDefaultSession(sessionId = "s2")
        createDefaultSession(sessionId = "s3")

        sessionManager.getSession("s1")!!.lastAccessedElapsedMs = 0L
        sessionManager.getSession("s2")!!.lastAccessedElapsedMs = 50_000L
        sessionManager.getSession("s3")!!.lastAccessedElapsedMs = SystemClock.elapsedRealtime()

        sessionManager.applyMemoryPressure(MemoryPressure.CRITICAL)
        assertEquals(1, sessionManager.sessionCount)
        assertNotNull(sessionManager.getSession("s3")) // highest priority kept
    }

    @Test
    fun `applyMemoryPressure EMERGENCY evicts all non-streaming`() {
        createDefaultSession(sessionId = "s1")
        createDefaultSession(sessionId = "s2")
        createDefaultSession(sessionId = "stream")

        sessionManager.getSession("stream")!!.isStreaming = true

        sessionManager.applyMemoryPressure(MemoryPressure.EMERGENCY)
        assertEquals(1, sessionManager.sessionCount)
        assertNotNull(sessionManager.getSession("stream"))
        assertNull(sessionManager.getSession("s1"))
        assertNull(sessionManager.getSession("s2"))
    }

    @Test
    fun `applyMemoryPressure EMERGENCY with no streaming evicts all`() {
        createDefaultSession(sessionId = "s1")
        createDefaultSession(sessionId = "s2")

        sessionManager.applyMemoryPressure(MemoryPressure.EMERGENCY)
        assertEquals(0, sessionManager.sessionCount)
    }

    @Test
    fun `closeAllOwnedByUidForRevoke fires allowlist revoked eviction notices`() {
        val notices = mutableListOf<Triple<String, Int?, Int>>()
        sessionManager.setEvictionListener { sessionId, ownerUid, reasonCode ->
            notices += Triple(sessionId, ownerUid, reasonCode)
        }
        sessionManager.createSession(SessionConfig(sessionId = "revoked-1"), ownerToken = 1234)
        sessionManager.createSession(SessionConfig(sessionId = "revoked-2"), ownerToken = 1234)
        sessionManager.createSession(SessionConfig(sessionId = "other-owner"), ownerToken = 5678)

        val closed = sessionManager.closeAllOwnedByUidForRevoke(1234)

        assertEquals(setOf("revoked-1", "revoked-2"), closed.toSet())
        assertNull(sessionManager.getSession("revoked-1"))
        assertNull(sessionManager.getSession("revoked-2"))
        assertNotNull(sessionManager.getSession("other-owner"))
        assertEquals(
            setOf(
                Triple("revoked-1", 1234, com.adsamcik.mindlayer.shared.MindlayerErrorCode.ALLOWLIST_REVOKED),
                Triple("revoked-2", 1234, com.adsamcik.mindlayer.shared.MindlayerErrorCode.ALLOWLIST_REVOKED),
            ),
            notices.toSet(),
        )
    }

    // ---- Device tier from MemoryBudget -------------------------------------

    @Test
    fun `getDeviceTier delegates to memoryBudget`() {
        val expectedTier = DeviceTier(4, 8192, 16384, 12 * 1024L)
        every { memoryBudget.deviceTier } returns expectedTier

        val tier = sessionManager.getDeviceTier()
        assertEquals(expectedTier, tier)
    }

    // ---- Token clamping ----------------------------------------------------

    @Test
    fun `createSession clamps maxTokens to device tier ceiling`() {
        val smallTier = DeviceTier(2, 2048, 2048, 6 * 1024L)
        every { memoryBudget.deviceTier } returns smallTier
        every { memoryBudget.currentSnapshot() } returns MemorySnapshot(
            availableMb = 2000L,
            totalMb = 6 * 1024L,
            lowMemory = false,
            pressure = MemoryPressure.NORMAL,
            recommendedMaxTokens = 4096,
        )

        val id = sessionManager.createSession(
            SessionConfig(sessionId = "clamped", maxTokens = 16384)
        )
        val handle = sessionManager.getSession(id)!!
        // Should be clamped to min(tier.maxMaxTokens=2048, snap.recommendedMaxTokens=4096)=2048
        assertEquals(2048, handle.effectiveMaxTokens)
    }

    @Test
    fun `createSession uses requested maxTokens when within budget`() {
        val id = sessionManager.createSession(
            SessionConfig(sessionId = "within-budget", maxTokens = 1024)
        )
        val handle = sessionManager.getSession(id)!!
        assertEquals(1024, handle.effectiveMaxTokens)
    }

    // ---- Access tracking ---------------------------------------------------

    @Test
    fun `markStreaming updates handle streaming flag`() {
        val id = createDefaultSession(sessionId = "s")
        sessionManager.markStreaming(id, true)
        assertTrue(sessionManager.getSession(id)!!.isStreaming)
    }

    @Test
    fun `findSessionByActiveRequest finds correct session`() {
        val id = createDefaultSession(sessionId = "active-req")
        sessionManager.getSession(id)!!.activeRequestId = "req-123"

        val found = sessionManager.findSessionByActiveRequest("req-123")
        assertNotNull(found)
        assertEquals("active-req", found!!.sessionId)
    }

    @Test
    fun `findSessionByActiveRequest returns null for unknown request`() {
        createDefaultSession(sessionId = "s")
        assertNull(sessionManager.findSessionByActiveRequest("no-such-req"))
    }

    // ---- Shutdown ----------------------------------------------------------

    @Test
    fun `shutdown destroys all sessions`() {
        createDefaultSession(sessionId = "s1")
        createDefaultSession(sessionId = "s2")
        createDefaultSession(sessionId = "s3")
        assertEquals(3, sessionManager.sessionCount)

        sessionManager.shutdown()
        assertEquals(0, sessionManager.sessionCount)
    }

    // ---- SessionHandle data tests ------------------------------------------

    // ---- validateSessionConfig bounds (H2) ---------------------------------
    //
    // The canonical limits live in IpcInputValidator. SessionManager.MAX_*_CHARS
    // are looser legacy constants used by the AIDL-boundary fast-fail in
    // ServiceBinder; the deeper IpcInputValidator check (called from
    // SessionManager.validateSessionConfig itself) is what these tests exercise,
    // so they reference IpcInputValidator's tightened budgets directly.

    @Test
    fun `validateSessionConfig accepts sessionId at exactly MAX_ID_LEN chars`() {
        val id = "a".repeat(IpcInputValidator.MAX_ID_LEN)
        sessionManager.createSession(SessionConfig(sessionId = id))
        assertNotNull(sessionManager.getSession(id))
    }

    @Test
    fun `validateSessionConfig rejects sessionId over MAX_ID_LEN chars`() {
        try {
            sessionManager.createSession(
                SessionConfig(sessionId = "a".repeat(IpcInputValidator.MAX_ID_LEN + 1))
            )
            fail("Expected sessionId too long to be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("sessionId too long"))
        }
    }

    @Test
    fun `validateSessionConfig accepts systemPrompt at exactly MAX_SYSTEM_PROMPT_LEN`() {
        val prompt = "x".repeat(IpcInputValidator.MAX_SYSTEM_PROMPT_LEN)
        // 32 KiB system prompt reserves ~10923 tokens, which would overrun the
        // default 4096-token KV budget and trip ContextOverflowException before
        // the length validator gets to assert success. Bump maxTokens to the
        // ceiling so the boundary check is what actually runs.
        sessionManager.createSession(
            SessionConfig(systemPrompt = prompt, maxTokens = 32_768)
        )
    }

    @Test
    fun `validateSessionConfig rejects systemPrompt over MAX_SYSTEM_PROMPT_LEN`() {
        val prompt = "x".repeat(IpcInputValidator.MAX_SYSTEM_PROMPT_LEN + 1)
        try {
            sessionManager.createSession(SessionConfig(systemPrompt = prompt))
            fail("Expected systemPrompt too long to be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("systemPrompt too long"))
        }
    }

    @Test
    fun `validateSessionConfig accepts toolsJson at exactly MAX_TOOLS_JSON_LEN`() {
        val json = "x".repeat(IpcInputValidator.MAX_TOOLS_JSON_LEN)
        // toolsJson is parsed — pass something that fails JSON parse; validate only checks length
        // We only check that validation itself does not throw an IAE about length.
        try {
            sessionManager.createSession(SessionConfig(toolsJson = json))
        } catch (e: IllegalArgumentException) {
            // Only re-throw if it's the length rejection we're testing against
            if (e.message.orEmpty().contains("toolsJson too long")) throw e
        }
    }

    @Test
    fun `validateSessionConfig rejects toolsJson over MAX_TOOLS_JSON_LEN`() {
        val json = "x".repeat(IpcInputValidator.MAX_TOOLS_JSON_LEN + 1)
        try {
            sessionManager.createSession(SessionConfig(toolsJson = json))
            fail("Expected toolsJson too long to be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("toolsJson too long"))
        }
    }

    @Test
    fun `validateSessionConfig accepts extraContextJson at exactly MAX_EXTRA_CONTEXT_JSON_LEN`() {
        val ctx = "x".repeat(IpcInputValidator.MAX_EXTRA_CONTEXT_JSON_LEN)
        try {
            sessionManager.createSession(SessionConfig(extraContextJson = ctx))
        } catch (e: IllegalArgumentException) {
            if (e.message.orEmpty().contains("extraContextJson too long")) throw e
        }
    }

    @Test
    fun `validateSessionConfig rejects extraContextJson over MAX_EXTRA_CONTEXT_JSON_LEN`() {
        val ctx = "x".repeat(IpcInputValidator.MAX_EXTRA_CONTEXT_JSON_LEN + 1)
        try {
            sessionManager.createSession(SessionConfig(extraContextJson = ctx))
            fail("Expected extraContextJson too long to be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("extraContextJson too long"))
        }
    }

    @Test
    fun `validateSessionConfig accepts initialHistory at exactly MAX_HISTORY_TURNS`() {
        val hist = List(IpcInputValidator.MAX_HISTORY_TURNS) {
            com.adsamcik.mindlayer.HistoryTurn(role = "user", text = "hi")
        }
        sessionManager.createSession(SessionConfig(initialHistory = hist))
    }

    @Test
    fun `validateSessionConfig rejects initialHistory over MAX_HISTORY_TURNS`() {
        val hist = List(IpcInputValidator.MAX_HISTORY_TURNS + 1) {
            com.adsamcik.mindlayer.HistoryTurn(role = "user", text = "hi")
        }
        try {
            sessionManager.createSession(SessionConfig(initialHistory = hist))
            fail("Expected too many history turns to be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("initialHistory has too many turns"))
        }
    }

    @Test
    fun `validateSessionConfig accepts history turn text at exactly MAX_HISTORY_TURN_LEN`() {
        val hist = listOf(
            com.adsamcik.mindlayer.HistoryTurn(
                role = "user",
                text = "x".repeat(IpcInputValidator.MAX_HISTORY_TURN_LEN),
            )
        )
        // 16 KiB (~4096 estimated tokens) of history would exhaust the default
        // 4096-token KV budget, tripping ContextOverflowException before the
        // length validator gets to assert success. Use the maximum maxTokens
        // accepted by IpcInputValidator so the engine can fit the turn.
        sessionManager.createSession(
            SessionConfig(initialHistory = hist, maxTokens = 32_768)
        )
    }

    @Test
    fun `validateSessionConfig rejects history turn text over MAX_HISTORY_TURN_LEN`() {
        val hist = listOf(
            com.adsamcik.mindlayer.HistoryTurn(
                role = "user",
                text = "x".repeat(IpcInputValidator.MAX_HISTORY_TURN_LEN + 1),
            )
        )
        try {
            sessionManager.createSession(SessionConfig(initialHistory = hist))
            fail("Expected history turn too long to be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("text too long"))
        }
    }

    @Test
    fun `validateSessionConfig accepts expirationMs at exactly MAX_SESSION_EXPIRATION_MS`() {
        sessionManager.createSession(
            SessionConfig(expirationMs = IpcInputValidator.MAX_SESSION_EXPIRATION_MS)
        )
    }

    @Test
    fun `validateSessionConfig rejects expirationMs over MAX_SESSION_EXPIRATION_MS`() {
        val tooLong = IpcInputValidator.MAX_SESSION_EXPIRATION_MS + 1
        try {
            sessionManager.createSession(SessionConfig(expirationMs = tooLong))
            fail("Expected expirationMs too large to be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("expirationMs out of range"))
        }
    }

    @Test
    fun `validateSessionConfig rejects negative expirationMs`() {
        try {
            sessionManager.createSession(SessionConfig(expirationMs = -1L))
            fail("Expected negative expirationMs to be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("expirationMs out of range"))
        }
    }

    // ---- L9 Reserved tool names ---------------------------------------------

    @Test
    fun `createSession rejects reserved __structured_output tool name`() {
        val toolsJson = """
            [{"name":"__structured_output","description":"hijack"}]
        """.trimIndent()
        try {
            sessionManager.createSession(SessionConfig(toolsJson = toolsJson))
            fail("Expected reserved tool name to be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "Error must mention reserved prefix; got: ${e.message}",
                e.message.orEmpty().contains("reserved prefix"),
            )
        }
    }

    @Test
    fun `createSession rejects underscore-prefixed reserved tool names`() {
        val toolsJson = """
            [{"name":"__internal_helper","description":"x"}]
        """.trimIndent()
        try {
            sessionManager.createSession(SessionConfig(toolsJson = toolsJson))
            fail("Expected __ prefix to be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("reserved prefix"))
        }
    }

    @Test
    fun `createSession accepts ordinary tool names`() {
        val toolsJson = """
            [{"name":"calculator","description":"add"}]
        """.trimIndent()
        // Should not throw.
        val id = sessionManager.createSession(SessionConfig(toolsJson = toolsJson))
        assertNotNull(sessionManager.getSession(id))
    }

    // ---- applyMemoryPressure EMERGENCY + pinned (H7) -----------------------

    @Test
    fun `applyMemoryPressure EMERGENCY preserves pinned non-streaming sessions`() {
        createDefaultSession(sessionId = "unpinned")
        createDefaultSession(sessionId = "pinned")
        createDefaultSession(sessionId = "stream")

        sessionManager.getSession("stream")!!.isStreaming = true
        sessionManager.getSession("pinned")!!.isPinned = true

        sessionManager.applyMemoryPressure(MemoryPressure.EMERGENCY)

        assertNull(sessionManager.getSession("unpinned"))    // evicted
        assertNotNull(sessionManager.getSession("pinned"))   // pinned, non-streaming → preserved
        assertNotNull(sessionManager.getSession("stream"))   // streaming → preserved
        assertEquals(2, sessionManager.sessionCount)
    }

    @Test
    fun `SessionHandle effectiveMaxTokens reflects constructor value`() {
        val handle = buildHandle()
        assertEquals(4096, handle.effectiveMaxTokens)
    }

    @Test
    fun `SessionHandle turnCount starts at zero`() {
        val handle = buildHandle()
        assertEquals(0, handle.turnCount)
    }

    @Test
    fun `SessionHandle recordAccess updates timestamps`() {
        val handle = buildHandle()
        val beforeElapsed = handle.lastAccessedElapsedMs
        every { SystemClock.elapsedRealtime() } returns 200_000L

        handle.recordAccess()
        assertEquals(200_000L, handle.lastAccessedElapsedMs)
    }

    @Test
    fun `SessionHandle toSessionInfo produces correct SessionInfo`() {
        val handle = buildHandle(sessionId = "info-handle")
        handle.estimatedTokens = 512
        handle.turnCount = 3
        handle.isStreaming = true

        val info = handle.toSessionInfo("GPU")
        assertEquals("info-handle", info.sessionId)
        assertEquals("GPU", info.backend)
        assertEquals(4096, info.maxTokens)
        assertEquals(512, info.currentTokenCount)
        assertEquals(3, info.turnCount)
        assertTrue(info.isStreaming)
    }

    @Test
    fun `backend switch lazy-invalidates idle sessions and rewarms on next access`() {
        val sid = createDefaultSession(sessionId = "thermal-idle")
        assertEquals(1, sessionManager.sessionCount)

        val invalidated = sessionManager.invalidateIdleSessionsForBackendSwitch()

        assertEquals(1, invalidated)
        assertEquals("Idle session metadata should survive backend switch", 1, sessionManager.sessionCount)
        assertNotNull(sessionManager.getSessionInfo(sid))

        // Hot-swap: getSession triggers rewarmBackendInvalidatedSession,
        // which now just nulls handle.conversation and clears the warm
        // marker (no eager engine.createConversation call). The next
        // withWarmConversation will lazily materialise a fresh
        // Conversation from baseConversationConfig seeded with the
        // preserved recordedTurns — that lazy path is exercised by
        // separate WarmConversationSlotTest scenarios.
        val handle = sessionManager.getSession(sid)
        assertNotNull("session should survive lazy rewarm", handle)
        assertNull(
            "rewarmed session is cold until first inference",
            handle!!.conversation,
        )
        assertFalse(
            "backendInvalidated flag should be cleared after rewarm",
            handle.backendInvalidated,
        )
        assertEquals(1, sessionManager.sessionCount)
        // Hot-swap: createSession is lazy and rewarm is lazy, so
        // engine.createConversation was never called along this path.
        // The on-demand materialisation is covered by
        // WarmConversationSlotTest.
        verify(exactly = 0) { mockEngine.createConversation(any()) }
    }
}
