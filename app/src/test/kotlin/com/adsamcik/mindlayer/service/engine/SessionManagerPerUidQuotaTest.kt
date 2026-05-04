package com.adsamcik.mindlayer.service.engine

import android.content.Context
import android.util.Log
import com.adsamcik.mindlayer.SessionConfig
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * Pins SECURITY_REVIEW F-039 — per-UID session quotas + own-first eviction.
 *
 * A single ownerToken cannot occupy more than ⌈tier.maxSessions/2⌉ slots.
 * When the per-UID quota is exhausted, [SessionManager] tries to evict one
 * of the caller's own (non-streaming) sessions before failing closed.
 */
class SessionManagerPerUidQuotaTest {

    private lateinit var engineManager: EngineManager
    private lateinit var memoryBudget: MemoryBudget
    private lateinit var sessionManager: SessionManager
    private lateinit var mockEngine: Engine
    private lateinit var context: Context

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        mockkStatic(android.os.SystemClock::class)
        every { android.os.SystemClock.elapsedRealtime() } returns 100_000L
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        mockEngine = mockk(relaxed = true)
        every { mockEngine.createConversation(any()) } returns mockk<Conversation>(relaxed = true)
        engineManager = mockk(relaxed = true)
        every { engineManager.requireEngine() } returns mockEngine
        every { engineManager.currentBackend } returns "GPU"
        every { engineManager.isInitialized } returns true

        // 4-session tier ⇒ per-UID cap is ceil(4/2) = 2.
        val tier = DeviceTier(maxSessions = 4, defaultMaxTokens = 8192, maxMaxTokens = 32768, deviceRamMb = 12000L)
        memoryBudget = mockk(relaxed = true)
        every { memoryBudget.deviceTier } returns tier
        every { memoryBudget.currentSnapshot() } returns MemorySnapshot(
            availableMb = 8000L, totalMb = 12000L, lowMemory = false,
            pressure = MemoryPressure.NORMAL, recommendedMaxTokens = 32768,
        )

        context = mockk(relaxed = true)
        sessionManager = SessionManager(context, engineManager, memoryBudget)
    }

    @After
    fun tearDown() {
        sessionManager.shutdown()
        unmockkAll()
    }

    @Test
    fun `single uid cannot exceed half of tier cap`() {
        val uidA = 1001
        // 2 sessions for uidA — both succeed (at per-UID cap).
        sessionManager.createSession(SessionConfig(), uidA)
        sessionManager.createSession(SessionConfig(), uidA)
        assertEquals(2, sessionManager.listSessionsOwnedBy(uidA).size)

        // 3rd session for the same UID would exceed the per-UID cap.
        // The manager tries to evict one of uidA's own sessions first; for
        // simplicity it succeeds — but the OWNER-COUNT must stay at the cap,
        // not exceed it.
        sessionManager.createSession(SessionConfig(), uidA)
        assertEquals(
            "uidA owner count must remain at per-UID cap after eviction",
            2,
            sessionManager.listSessionsOwnedBy(uidA).size,
        )
    }

    @Test
    fun `per-uid quota does not block other UIDs`() {
        val uidA = 1001
        val uidB = 1002
        // uidA fills its quota.
        sessionManager.createSession(SessionConfig(), uidA)
        sessionManager.createSession(SessionConfig(), uidA)
        // uidB is unaffected — gets its own quota.
        sessionManager.createSession(SessionConfig(), uidB)
        sessionManager.createSession(SessionConfig(), uidB)

        assertEquals(2, sessionManager.listSessionsOwnedBy(uidA).size)
        assertEquals(2, sessionManager.listSessionsOwnedBy(uidB).size)
        assertEquals(4, sessionManager.listSessions().size)
    }

    @Test
    fun `null owner is unconstrained by per-UID quota`() {
        // Self-UID (dashboard) passes ownerToken = null and should be able
        // to fill the global tier cap.
        repeat(4) { sessionManager.createSession(SessionConfig(), null) }
        assertEquals(4, sessionManager.listSessions().size)
    }
}
