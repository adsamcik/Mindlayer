package com.adsamcik.mindlayer.service.engine

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.adsamcik.mindlayer.SessionConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * F-018: regression coverage for the [SessionManager] init-queue contract.
 *
 *  - Concurrent first-callers must each return within < 50 ms with
 *    `EngineNotReadyException` (binder pool not pinned).
 *  - Underlying `engineManager.initialize` is invoked **exactly once** —
 *    the CAS coalescing in `ensureInitStarted` prevents fan-out.
 *  - Once init completes, subsequent callers proceed normally.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EngineInitQueueTest {

    private lateinit var context: Context
    private lateinit var engineManager: EngineManager
    private lateinit var memoryBudget: MemoryBudget
    private lateinit var mockEngine: Engine
    private lateinit var sessionManager: SessionManager
    private val initBarrier = Mutex()

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

        mockEngine = mockk(relaxed = true) {
            every { createConversation(any()) } returns mockk<Conversation>(relaxed = true)
        }

        engineManager = mockk(relaxed = true) {
            every { requireEngine() } returns mockEngine
            every { currentBackend } returns "GPU"
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

        sessionManager = SessionManager(context, engineManager, memoryBudget)
    }

    @After
    fun tearDown() {
        sessionManager.shutdown()
        unmockkAll()
    }

    @Test
    fun `concurrent first callers all coalesce to a single init`() = runBlocking {
        every { engineManager.isInitialized } returns false
        // Make initialize block long enough that all 8 callers see
        // !isInitialized before the slot finishes.
        coEvery {
            engineManager.initialize(any(), any())
        } coAnswers {
            // Ensure mutual-exclusion with a Mutex so we can verify only
            // a single coroutine ever runs the body.
            initBarrier.withLock { delay(200) }
            mockEngine
        }

        val attempts = 8
        val fastFails = AtomicInteger(0)
        val durations = LongArray(attempts)
        val tasks = (0 until attempts).map { i ->
            async(Dispatchers.IO) {
                val start = System.nanoTime()
                try {
                    sessionManager.createSession(SessionConfig(maxTokens = 2048))
                } catch (e: EngineNotReadyException) {
                    fastFails.incrementAndGet()
                }
                durations[i] = (System.nanoTime() - start) / 1_000_000L
            }
        }
        tasks.awaitAll()

        assertEquals("All callers must fast-fail", attempts, fastFails.get())
        // Allow generous slack (100 ms) for Robolectric scheduler overhead.
        for (d in durations) {
            assertTrue("createSession should fast-fail (<100ms), took ${d}ms", d < 100L)
        }

        // Exactly one underlying init invocation (CAS coalescing).
        coVerify(exactly = 1) {
            engineManager.initialize(preferredBackend = any(), maxTokens = any())
        }
    }

    @Test
    fun `subsequent call after init succeeds`() = runBlocking {
        every { engineManager.isInitialized } returns false
        coEvery { engineManager.initialize(any(), any()) } coAnswers {
            delay(100)
            mockEngine
        }

        // First call: fast-fails.
        var threw = false
        try {
            sessionManager.createSession(SessionConfig(maxTokens = 2048))
        } catch (e: EngineNotReadyException) {
            threw = true
        }
        assertTrue("First caller must fast-fail", threw)

        // Wait for background init to flip the flag.
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
        }

        // Flip the mock now that init "completed" so the second call
        // proceeds through the regular create-session path.
        every { engineManager.isInitialized } returns true

        val id = sessionManager.createSession(SessionConfig(maxTokens = 2048))
        assertTrue("Second caller should get a session id", id.isNotBlank())
    }
}
