package com.adsamcik.mindlayer.service.integration

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.adsamcik.mindlayer.SessionConfig
import com.adsamcik.mindlayer.service.engine.DeviceTier
import com.adsamcik.mindlayer.service.engine.EngineManager
import com.adsamcik.mindlayer.service.engine.MemoryBudget
import com.adsamcik.mindlayer.service.engine.MemoryPressure
import com.adsamcik.mindlayer.service.engine.MemorySnapshot
import com.adsamcik.mindlayer.service.engine.SessionManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Integration tests for the MemoryBudget → SessionManager eviction pipeline.
 *
 * Uses a real [SessionManager] with mocked [EngineManager] and [MemoryBudget]
 * to verify that pressure changes drive the correct eviction behaviour.
 */
class MemoryPressurePipelineTest {

    private lateinit var context: Context
    private lateinit var engineManager: EngineManager
    private lateinit var mockEngine: Engine
    private lateinit var memoryBudget: MemoryBudget
    private lateinit var sessionManager: SessionManager

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
        }

        context = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        if (::sessionManager.isInitialized) sessionManager.shutdown()
        unmockkAll()
    }

    // -- Helpers -------------------------------------------------------------

    /**
     * Build a mocked [MemoryBudget] that returns the given [tier] and
     * a NORMAL-pressure snapshot by default.
     */
    private fun buildBudget(
        tier: DeviceTier,
        pressure: MemoryPressure = MemoryPressure.NORMAL,
        recommendedMaxTokens: Int = tier.maxMaxTokens,
    ): MemoryBudget = mockk(relaxed = true) {
        every { deviceTier } returns tier
        every { currentSnapshot() } returns MemorySnapshot(
            availableMb = 4000L,
            totalMb = tier.deviceRamMb,
            lowMemory = false,
            pressure = pressure,
            recommendedMaxTokens = recommendedMaxTokens,
        )
    }

    private fun generousTier() = DeviceTier(
        maxSessions = 6,
        defaultMaxTokens = 16384,
        maxMaxTokens = 32768,
        deviceRamMb = 16 * 1024L,
    )

    private fun createSession(id: String, maxTokens: Int = 4096): String =
        sessionManager.createSession(SessionConfig(sessionId = id, maxTokens = maxTokens))

    private fun setClientPriority(id: String, priority: Int) {
        sessionManager.getSession(id)?.clientPriorityHint = priority
    }

    private fun makeOld(id: String) {
        sessionManager.getSession(id)?.lastAccessedElapsedMs = 0L
    }

    // ========================================================================
    //  Tests
    // ========================================================================

    @Test
    fun `normalPressure_allSessionsSurvive`() {
        memoryBudget = buildBudget(generousTier(), MemoryPressure.NORMAL)
        sessionManager = SessionManager(context, engineManager, memoryBudget)

        createSession("s1")
        createSession("s2")
        createSession("s3")

        sessionManager.applyMemoryPressure(MemoryPressure.NORMAL)

        assertEquals(3, sessionManager.sessionCount)
        assertNotNull(sessionManager.getSession("s1"))
        assertNotNull(sessionManager.getSession("s2"))
        assertNotNull(sessionManager.getSession("s3"))
    }

    @Test
    fun `warningPressure_lowestPriorityEvicted`() {
        memoryBudget = buildBudget(generousTier(), MemoryPressure.NORMAL)
        sessionManager = SessionManager(context, engineManager, memoryBudget)

        createSession("high")
        createSession("mid")
        createSession("low")

        // Remove recency bonus from all so only clientPriorityHint matters
        makeOld("high"); makeOld("mid"); makeOld("low")
        setClientPriority("high", 100)
        setClientPriority("mid", 50)
        setClientPriority("low", 10)

        sessionManager.applyMemoryPressure(MemoryPressure.WARNING)

        assertEquals(2, sessionManager.sessionCount)
        assertNotNull(sessionManager.getSession("high"))
        assertNotNull(sessionManager.getSession("mid"))
        assertEquals(null, sessionManager.getSession("low"))
    }

    @Test
    fun `criticalPressure_onlyOneSessionSurvives`() {
        memoryBudget = buildBudget(generousTier(), MemoryPressure.NORMAL)
        sessionManager = SessionManager(context, engineManager, memoryBudget)

        createSession("s1")
        createSession("s2")
        createSession("s3")

        // Make s3 highest priority
        makeOld("s1"); makeOld("s2"); makeOld("s3")
        setClientPriority("s1", 10)
        setClientPriority("s2", 20)
        setClientPriority("s3", 90)

        sessionManager.applyMemoryPressure(MemoryPressure.CRITICAL)

        // evictUnderPressure keeps exactly one non-streaming session (highest priority)
        assertEquals(1, sessionManager.sessionCount)
        assertNotNull(sessionManager.getSession("s3"))
    }

    @Test
    fun `emergencyPressure_allNonStreamingEvicted`() {
        memoryBudget = buildBudget(generousTier(), MemoryPressure.NORMAL)
        sessionManager = SessionManager(context, engineManager, memoryBudget)

        createSession("streaming")
        createSession("idle1")
        createSession("idle2")

        sessionManager.markStreaming("streaming", true)

        sessionManager.applyMemoryPressure(MemoryPressure.EMERGENCY)

        // Only the streaming session survives EMERGENCY
        assertEquals(1, sessionManager.sessionCount)
        assertNotNull(sessionManager.getSession("streaming"))
    }

    @Test
    fun `streamingSession_neverEvicted`() {
        memoryBudget = buildBudget(generousTier(), MemoryPressure.NORMAL)
        sessionManager = SessionManager(context, engineManager, memoryBudget)

        createSession("s-stream")
        createSession("s-idle")

        makeOld("s-stream"); makeOld("s-idle")
        setClientPriority("s-stream", 0)
        setClientPriority("s-idle", 100)
        sessionManager.markStreaming("s-stream", true)

        // CRITICAL evicts all but highest priority non-streaming
        sessionManager.applyMemoryPressure(MemoryPressure.CRITICAL)

        // Streaming session survives despite zero priority
        assertNotNull(sessionManager.getSession("s-stream"))
        // The idle session is the only non-streaming one, so it's kept as the "best"
        assertNotNull(sessionManager.getSession("s-idle"))
        assertEquals(2, sessionManager.sessionCount)
    }

    @Test
    fun `deviceTier_8gb_maxTwoSessions`() {
        val tier8gb = DeviceTier(
            maxSessions = 2,
            defaultMaxTokens = 4096,
            maxMaxTokens = 4096,
            deviceRamMb = 8 * 1024L,
        )
        memoryBudget = buildBudget(tier8gb, MemoryPressure.NORMAL)
        sessionManager = SessionManager(context, engineManager, memoryBudget)

        createSession("s1")
        makeOld("s1"); setClientPriority("s1", 10)

        createSession("s2")
        makeOld("s2"); setClientPriority("s2", 50)

        // At limit (2). Creating s3 should evict the lowest-priority session.
        createSession("s3")

        assertEquals(2, sessionManager.sessionCount)
        // s1 (priority 10) is evicted; s2 and s3 remain
        assertEquals(null, sessionManager.getSession("s1"))
        assertNotNull(sessionManager.getSession("s2"))
        assertNotNull(sessionManager.getSession("s3"))
    }

    @Test
    fun `deviceTier_16gb_maxSixSessions`() {
        val tier16gb = DeviceTier(
            maxSessions = 6,
            defaultMaxTokens = 16384,
            maxMaxTokens = 32768,
            deviceRamMb = 16 * 1024L,
        )
        memoryBudget = buildBudget(tier16gb, MemoryPressure.NORMAL)
        sessionManager = SessionManager(context, engineManager, memoryBudget)

        repeat(6) { i -> createSession("s$i") }

        assertEquals(6, sessionManager.sessionCount)
    }

    @Test
    fun `maxTokens_clampedByPressure`() {
        val tier = generousTier() // maxMaxTokens = 32768
        // WARNING pressure → recommendedMaxTokens = defaultMaxTokens = 16384
        memoryBudget = buildBudget(
            tier = tier,
            pressure = MemoryPressure.WARNING,
            recommendedMaxTokens = tier.defaultMaxTokens, // 16384
        )
        sessionManager = SessionManager(context, engineManager, memoryBudget)

        // Request 32768 tokens — should be clamped to 16384
        createSession("clamped", maxTokens = 32768)

        val handle = sessionManager.getSession("clamped")
        assertNotNull(handle)
        assertTrue(
            "effectiveMaxTokens (${handle!!.effectiveMaxTokens}) should be <= ${tier.defaultMaxTokens}",
            handle.effectiveMaxTokens <= tier.defaultMaxTokens,
        )
    }

    @Test
    fun `onTrimMemory_background_triggersEviction`() {
        memoryBudget = buildBudget(generousTier(), MemoryPressure.NORMAL)
        sessionManager = SessionManager(context, engineManager, memoryBudget)

        createSession("s1")
        createSession("s2")
        createSession("s3")

        makeOld("s1"); makeOld("s2"); makeOld("s3")
        setClientPriority("s1", 10)
        setClientPriority("s2", 50)
        setClientPriority("s3", 90)

        // Simulate what happens when onTrimMemory dispatches CRITICAL
        sessionManager.applyMemoryPressure(MemoryPressure.CRITICAL)

        assertEquals(1, sessionManager.sessionCount)
        assertNotNull(sessionManager.getSession("s3"))
    }

    // === Edge-case tests ===

    @Test
    fun `eviction_tieBreaking_isStable`() {
        memoryBudget = buildBudget(generousTier(), MemoryPressure.NORMAL)
        sessionManager = SessionManager(context, engineManager, memoryBudget)

        createSession("tied-a")
        createSession("tied-b")

        // Both sessions get identical priority and recency
        makeOld("tied-a"); makeOld("tied-b")
        setClientPriority("tied-a", 50)
        setClientPriority("tied-b", 50)

        // WARNING evicts lowest-priority — with a tie, one is picked deterministically
        sessionManager.applyMemoryPressure(MemoryPressure.WARNING)

        assertEquals(1, sessionManager.sessionCount)
    }

    @Test
    fun `pressureRelief_doesNotRestoreEvicted`() {
        memoryBudget = buildBudget(generousTier(), MemoryPressure.NORMAL)
        sessionManager = SessionManager(context, engineManager, memoryBudget)

        createSession("keep")
        createSession("evict-me")

        makeOld("keep"); makeOld("evict-me")
        setClientPriority("keep", 90)
        setClientPriority("evict-me", 10)

        // Evict under CRITICAL → only "keep" survives
        sessionManager.applyMemoryPressure(MemoryPressure.CRITICAL)
        assertEquals(1, sessionManager.sessionCount)
        assertNotNull(sessionManager.getSession("keep"))

        // Pressure returns to NORMAL — evicted session must NOT reappear
        sessionManager.applyMemoryPressure(MemoryPressure.NORMAL)
        assertEquals(1, sessionManager.sessionCount)
        assertEquals(null, sessionManager.getSession("evict-me"))
    }

    @Test
    fun `createSession_underEmergency_refused`() {
        val tier = generousTier()
        memoryBudget = buildBudget(tier, MemoryPressure.NORMAL)
        sessionManager = SessionManager(context, engineManager, memoryBudget)

        // First session created under NORMAL pressure
        createSession("survivor")

        // Switch the mock to return EMERGENCY pressure
        every { memoryBudget.currentSnapshot() } returns MemorySnapshot(
            availableMb = 200L,
            totalMb = tier.deviceRamMb,
            lowMemory = true,
            pressure = MemoryPressure.EMERGENCY,
            recommendedMaxTokens = 2048,
        )

        var threw = false
        try {
            createSession("doomed")
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue("New session should be refused under EMERGENCY with active sessions", threw)
        // The surviving session is untouched
        assertNotNull(sessionManager.getSession("survivor"))
    }
}
