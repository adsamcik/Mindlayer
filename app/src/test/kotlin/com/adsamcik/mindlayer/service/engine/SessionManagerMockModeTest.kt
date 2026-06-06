package com.adsamcik.mindlayer.service.engine

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.adsamcik.mindlayer.SessionConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Verifies the DEBUG-only "CI mock engines" session path: with
 * `mockMode = true`, [SessionManager.createSession] must build a normal cold
 * session (`conversation == null`) WITHOUT auto-initializing or requiring the
 * native engine, and destroy must stay null-safe.
 */
class SessionManagerMockModeTest {

    private lateinit var context: Context
    private lateinit var engineManager: EngineManager
    private lateinit var memoryBudget: MemoryBudget

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

        // Engine is NOT initialized and requireEngine()/awaitReady throw — mock
        // mode must never reach them.
        engineManager = mockk(relaxed = true) {
            every { isInitialized } returns false
            every { currentBackend } returns "NONE"
            every { requireEngine() } throws IllegalStateException("engine must not be required in mock mode")
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
        context = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `createSession in mock mode builds a cold session without the engine`() {
        val sm = SessionManager(context, engineManager, memoryBudget, mockMode = true)

        val id = sm.createSession(SessionConfig(maxTokens = 2048))

        val handle = sm.getSession(id)
        assertNotNull("session must exist", handle)
        assertNull("mock session must stay cold (conversation == null)", handle!!.conversation)
        verify(exactly = 0) { engineManager.requireEngine() }

        // Destroy must be null-safe with a null conversation.
        sm.destroySession(id)
        assertNull("session must be removed after destroy", sm.getSession(id))
    }

    @Test(expected = IllegalStateException::class)
    fun `createSession without mock mode still requires the engine`() {
        // Control: with mockMode off and the engine already 'initialized' but
        // requireEngine() throwing, the guard must surface — proving the skip
        // in mock mode is load-bearing, not incidental.
        val em = mockk<EngineManager>(relaxed = true) {
            every { isInitialized } returns true
            every { currentBackend } returns "GPU"
            every { requireEngine() } throws IllegalStateException("engine required")
        }
        val sm = SessionManager(context, em, memoryBudget, mockMode = false)
        sm.createSession(SessionConfig(maxTokens = 2048))
    }
}
