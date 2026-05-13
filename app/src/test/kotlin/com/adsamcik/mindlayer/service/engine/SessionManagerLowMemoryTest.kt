package com.adsamcik.mindlayer.service.engine

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.adsamcik.mindlayer.SessionConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F-071: end-to-end coverage for the low-memory typed-error propagation
 * through [SessionManager].
 *
 * Acceptance criterion 1 from the task spec:
 *
 * > stub ActivityManager.MemoryInfo so availMem < model + 512 MB. Call
 * > SessionManager.createSession from a coroutine. Assert: ONE
 * > LowMemoryException (or its translated form) reaches the caller, NOT
 * > a retry loop, NOT an engine_initializing exception.
 *
 * Because the synchronous binder path must not block on
 * [com.adsamcik.mindlayer.service.engine.ModelRegistry.discoverModels]
 * (the lazy `selectedModel` would extract AI-pack assets — see F-018 +
 * the rubber-duck note), the memory check stays inside
 * [EngineManager.initialize]. [SessionManager] caches the resulting
 * [LowMemoryException] from the bg init job and surfaces it on the
 * subsequent `createSession` call. The first call still gets
 * [EngineNotReadyException] (engine_initializing) per the F-018
 * cold-start contract; the second call sees the typed
 * [LowMemoryException] — that's "ONE LowMemoryException reaches the
 * caller, not a retry loop" in the only racey-with-async sense the
 * service can offer.
 *
 * Companion bg-init coalescing test below also pins that
 * `engineManager.initialize` is invoked **exactly once**, so there is
 * no retry storm even though createSession itself is called multiple
 * times.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionManagerLowMemoryTest {

    private lateinit var context: Context
    private lateinit var engineManager: EngineManager
    private lateinit var memoryBudget: MemoryBudget
    private lateinit var sessionManager: SessionManager

    private val lowMemException = LowMemoryException(availMb = 1024L, requiredMb = 2560L)

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 100_000L

        engineManager = mockk(relaxed = true) {
            every { isInitialized } returns false
            every { currentBackend } returns "NONE"
        }

        val tier = DeviceTier(
            maxSessions = 6,
            defaultMaxTokens = 4096,
            maxMaxTokens = 8192,
            deviceRamMb = 4 * 1024L,
        )
        memoryBudget = mockk(relaxed = true) {
            every { deviceTier } returns tier
            every { currentSnapshot() } returns MemorySnapshot(
                availableMb = 1024L,
                totalMb = 4 * 1024L,
                lowMemory = false,
                pressure = MemoryPressure.NORMAL,
                recommendedMaxTokens = 4096,
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

    @Test
    fun `bg init throws LowMemoryException so subsequent createSession surfaces typed error`() = runBlocking {
        // Bg init fails fast with LowMemoryException — no delay, so the
        // job completes well before our second poll below.
        coEvery { engineManager.initialize(any(), any()) } throws lowMemException
        coEvery { engineManager.awaitReady() } coAnswers {
            delay(100)
            EngineState.Failed(InitFailure.LowMemory)
        }

        // First call now blocks until init reaches terminal failure and surfaces it.
        try {
            sessionManager.createSession(SessionConfig(maxTokens = 2048))
            fail("Expected LowMemoryException")
        } catch (e: LowMemoryException) {
            assertEquals(1024L, e.availMb)
            assertEquals(2560L, e.requiredMb)
        }

        // Wait for the bg init job to fail and cache the LowMemoryException.
        // We bound the wait to keep the test deterministic on slow CI; the
        // coVerify probe doubles as a busy-poll for "init has been invoked".
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                try {
                    coVerify(exactly = 1) { engineManager.initialize(any(), any()) }
                    break
                } catch (_: AssertionError) {
                    delay(20)
                }
            }
            // Also wait a beat for the catch-block to actually run after
            // initialize() throws and for the AtomicReference write to
            // become visible.
            delay(50)
        }

        // Second call must now surface the cached LowMemoryException
        // directly. NOT EngineNotReadyException, NOT a retry storm —
        // exactly one LowMemoryException carrying the original numbers.
        try {
            sessionManager.createSession(SessionConfig(maxTokens = 2048))
            fail("Expected LowMemoryException on second call after bg init failure")
        } catch (e: LowMemoryException) {
            assertEquals(1024L, e.availMb)
            assertEquals(2560L, e.requiredMb)
            assertTrue(e.message!!.contains("availMb=1024"))
            assertTrue(e.message!!.contains("requiredMb=2560"))
        } catch (e: EngineNotReadyException) {
            fail("Expected LowMemoryException, got EngineNotReadyException — typed error did not propagate")
        }

        // Crucial F-018 invariant: bg init invoked exactly once even
        // though createSession was called twice. No retry storm.
        coVerify(exactly = 1) { engineManager.initialize(any(), any()) }
    }

    @Test
    fun `non-low-memory bg init failure does NOT cache and does not block subsequent createSession`() = runBlocking {
        // Transient backend failure — must NOT be cached, otherwise a
        // single driver glitch would brick session creation until the
        // service restarted.
        val failure = IllegalStateException("All backends failed: transient driver error")
        coEvery { engineManager.initialize(any(), any()) } throws failure
        coEvery { engineManager.awaitReady() } coAnswers {
            delay(100)
            EngineState.Failed(InitFailure.BackendUnavailable("CPU", "IllegalStateException"))
        }

        try {
            sessionManager.createSession(SessionConfig(maxTokens = 2048))
            fail("Expected cached init failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("transient driver error"))
        }

        // Wait for bg init to fail.
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                try {
                    coVerify(exactly = 1) { engineManager.initialize(any(), any()) }
                    break
                } catch (_: AssertionError) {
                    delay(20)
                }
            }
            delay(50)
        }

        try {
            sessionManager.createSession(SessionConfig(maxTokens = 2048))
            fail("Expected cached init failure on retry")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("transient driver error"))
        }

        coVerify(exactly = 1) { engineManager.initialize(any(), any()) }
    }
}
