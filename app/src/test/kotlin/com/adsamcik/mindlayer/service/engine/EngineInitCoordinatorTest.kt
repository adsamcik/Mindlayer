package com.adsamcik.mindlayer.service.engine

import android.os.Looper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Unit tests for [EngineInitCoordinator] — the F-018 / F-071 owner of the
 * background LiteRT-LM init job, its per-variant retry-after TTL failure cache,
 * and the EngineRestartStore intent honouring.
 *
 * The coordinator runs init on a dedicated dispatcher; the tests pin it to
 * [Dispatchers.Unconfined] so a `startInitIfNeeded` call runs the (mocked,
 * non-suspending) init body synchronously, making the failure-cache assertions
 * deterministic. Robolectric supplies a controllable `SystemClock.elapsedRealtime`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EngineInitCoordinatorTest {

    private lateinit var engineManager: EngineManager
    private lateinit var coordinator: EngineInitCoordinator

    @Before
    fun setUp() {
        engineManager = mockk(relaxed = true)
        every { engineManager.beginPendingRestartAttempt() } returns null
        every { engineManager.lastInitFailure } returns null
        coEvery { engineManager.initialize(any(), any()) } returns mockk(relaxed = true)
        coordinator = EngineInitCoordinator(engineManager, initDispatcher = Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        coordinator.shutdown()
    }

    private fun driveFailedInit(
        failure: InitFailure?,
        error: Throwable = RuntimeException("boom"),
    ) {
        every { engineManager.lastInitFailure } returns failure
        coEvery { engineManager.initialize(any(), any()) } throws error
        coordinator.startInitIfNeeded(preferredBackend = null, maxTokens = 100)
        coordinator.awaitCurrentInitJob()
    }

    @Test
    fun `no cached failure before any init`() {
        assertNull(coordinator.pollCachedFailure())
        assertNull(coordinator.peekCachedFailure())
    }

    @Test
    fun `isSyntheticInitTimeout only matches the timeout sentinel`() {
        assertTrue(coordinator.isSyntheticInitTimeout(InitFailure.NativeError("init timeout")))
        assertFalse(coordinator.isSyntheticInitTimeout(InitFailure.NativeError("driver crash")))
        assertFalse(coordinator.isSyntheticInitTimeout(InitFailure.LowMemory))
        assertFalse(coordinator.isSyntheticInitTimeout(InitFailure.ModelMissing))
    }

    @Test
    fun `failed init caches the thrown throwable`() {
        val thrown = IllegalStateException("native init failed")
        driveFailedInit(InitFailure.NativeError("native init failed"), error = thrown)

        val cached = coordinator.peekCachedFailure()
        assertNotNull(cached)
        assertSame("must surface the exact original throwable", thrown, cached!!.throwable)
    }

    @Test
    fun `low-memory failures get the short retry window`() {
        driveFailedInit(InitFailure.LowMemory)
        assertEquals(30_000L, coordinator.peekCachedFailure()!!.retryAfterMs)
    }

    @Test
    fun `backend-unavailable native and uncategorised failures get the long retry window`() {
        driveFailedInit(InitFailure.BackendUnavailable(backend = "GPU", safeLabel = "X"))
        assertEquals(60_000L, coordinator.peekCachedFailure()!!.retryAfterMs)

        coordinator.shutdown()
        coordinator = EngineInitCoordinator(engineManager, initDispatcher = Dispatchers.Unconfined)
        driveFailedInit(InitFailure.NativeError("crash"))
        assertEquals(60_000L, coordinator.peekCachedFailure()!!.retryAfterMs)

        coordinator.shutdown()
        coordinator = EngineInitCoordinator(engineManager, initDispatcher = Dispatchers.Unconfined)
        driveFailedInit(failure = null)
        assertEquals(60_000L, coordinator.peekCachedFailure()!!.retryAfterMs)
    }

    @Test
    fun `structural failures are cached as effectively permanent`() {
        driveFailedInit(InitFailure.ModelMissing)
        assertEquals(Long.MAX_VALUE, coordinator.peekCachedFailure()!!.retryAfterMs)

        coordinator.shutdown()
        coordinator = EngineInitCoordinator(engineManager, initDispatcher = Dispatchers.Unconfined)
        driveFailedInit(InitFailure.IntegrityMismatch)
        assertEquals(Long.MAX_VALUE, coordinator.peekCachedFailure()!!.retryAfterMs)
    }

    @Test
    fun `pollCachedFailure returns the cached failure within its TTL`() {
        driveFailedInit(InitFailure.LowMemory)
        assertNotNull(coordinator.pollCachedFailure())
    }

    @Test
    fun `pollCachedFailure clears the cache once the TTL elapses`() {
        driveFailedInit(InitFailure.LowMemory) // 30s window
        assertNotNull(coordinator.pollCachedFailure())

        // Advance the Robolectric foreground clock past the 30s retry window.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(30_001L))

        assertNull("TTL elapsed: poll must self-heal by clearing the cache", coordinator.pollCachedFailure())
    }

    @Test
    fun `successful init clears a previously cached failure`() {
        driveFailedInit(InitFailure.LowMemory)
        assertNotNull(coordinator.peekCachedFailure())

        // Now a healthy attempt succeeds.
        every { engineManager.lastInitFailure } returns null
        coEvery { engineManager.initialize(any(), any()) } returns mockk(relaxed = true)
        coordinator.startInitIfNeeded(preferredBackend = null, maxTokens = 100)
        coordinator.awaitCurrentInitJob()

        assertNull(coordinator.peekCachedFailure())
    }

    @Test
    fun `restart intent overrides caller backend and is cleared on success`() {
        every { engineManager.beginPendingRestartAttempt() } returns EngineRestartStore.RestartIntent(
            schemaVersion = 1,
            reason = "thermal",
            targetBackend = "GPU",
            maxTokens = 4096,
            recordedAtMs = 0L,
            attemptCount = 2,
        )
        coEvery { engineManager.initialize(any(), any()) } returns mockk(relaxed = true)

        coordinator.startInitIfNeeded(preferredBackend = "CPU", maxTokens = 100)
        coordinator.awaitCurrentInitJob()

        coVerify(exactly = 1) { engineManager.initialize("GPU", 4096) }
        verify(exactly = 1) { engineManager.clearPendingRestartIntent() }
    }

    @Test
    fun `restart intent with non-positive attempt count is ignored`() {
        every { engineManager.beginPendingRestartAttempt() } returns EngineRestartStore.RestartIntent(
            schemaVersion = 1,
            reason = "thermal",
            targetBackend = "GPU",
            maxTokens = 4096,
            recordedAtMs = 0L,
            attemptCount = 0,
        )
        coEvery { engineManager.initialize(any(), any()) } returns mockk(relaxed = true)

        coordinator.startInitIfNeeded(preferredBackend = "CPU", maxTokens = 100)
        coordinator.awaitCurrentInitJob()

        coVerify(exactly = 1) { engineManager.initialize("CPU", 100) }
        verify(exactly = 0) { engineManager.clearPendingRestartIntent() }
    }

    @Test
    fun `shutdown drops the cached failure`() {
        driveFailedInit(InitFailure.LowMemory)
        assertNotNull(coordinator.peekCachedFailure())

        coordinator.shutdown()
        assertNull(coordinator.peekCachedFailure())
    }
}
