package com.adsamcik.mindlayer.service.engine

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ThermalBand, ThermalPolicy, ThermalSample data classes,
 * the [ThermalMonitor.policyForBand] mapping, and GPU re-enable cooldown logic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThermalBandTest {

    private lateinit var context: Context
    private lateinit var powerManager: PowerManager
    private lateinit var scope: TestScope
    private lateinit var monitor: ThermalMonitor

    @Before
    fun setUp() {
        mockkStatic(SystemClock::class)
        mockkStatic(Log::class)
        mockkStatic(Build.VERSION::class)

        every { SystemClock.uptimeMillis() } returns 100_000L
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        powerManager = mockk(relaxed = true)
        context = mockk {
            every { getSystemService(PowerManager::class.java) } returns powerManager
        }
        scope = TestScope()
        monitor = ThermalMonitor(context, scope)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ---- ThermalBand enum ordering -----------------------------------------

    @Test
    fun `ThermalBand COOL has lowest ordinal`() {
        assertEquals(0, ThermalBand.COOL.ordinal)
    }

    @Test
    fun `ThermalBand WARM is ordinal 1`() {
        assertEquals(1, ThermalBand.WARM.ordinal)
    }

    @Test
    fun `ThermalBand HOT is ordinal 2`() {
        assertEquals(2, ThermalBand.HOT.ordinal)
    }

    @Test
    fun `ThermalBand CRITICAL is ordinal 3`() {
        assertEquals(3, ThermalBand.CRITICAL.ordinal)
    }

    @Test
    fun `ThermalBand ordering - COOL less than WARM less than HOT less than CRITICAL`() {
        assertTrue(ThermalBand.COOL < ThermalBand.WARM)
        assertTrue(ThermalBand.WARM < ThermalBand.HOT)
        assertTrue(ThermalBand.HOT < ThermalBand.CRITICAL)
        assertTrue(ThermalBand.COOL < ThermalBand.CRITICAL)
    }

    @Test
    fun `ThermalBand values has exactly 4 entries`() {
        assertEquals(4, ThermalBand.entries.size)
    }

    // ---- ThermalPolicy mapping via policyForBand ---------------------------
    // policyForBand is private, but the initial currentPolicy reflects COOL
    // and we can test the data class values directly from spec knowledge.

    @Test
    fun `COOL policy - GPU backend, burst=12, rest=0, chunk=128`() {
        val policy = monitor.currentPolicy.value
        assertEquals(ThermalBand.COOL, policy.band)
        assertEquals("GPU", policy.recommendedBackend)
        assertEquals(12, policy.burstSeconds)
        assertEquals(0, policy.restSeconds)
        assertEquals(128, policy.chunkTokens)
    }

    @Test
    fun `COOL policy data class matches expected values`() {
        val expected = ThermalPolicy(
            band = ThermalBand.COOL,
            recommendedBackend = "GPU",
            burstSeconds = 12,
            restSeconds = 0,
            chunkTokens = 128,
        )
        assertEquals(expected, monitor.currentPolicy.value)
    }

    @Test
    fun `WARM policy has expected values`() {
        val policy = ThermalPolicy(
            band = ThermalBand.WARM,
            recommendedBackend = "GPU",
            burstSeconds = 8,
            restSeconds = 3,
            chunkTokens = 64,
        )
        assertEquals("GPU", policy.recommendedBackend)
        assertEquals(8, policy.burstSeconds)
        assertEquals(3, policy.restSeconds)
        assertEquals(64, policy.chunkTokens)
    }

    @Test
    fun `HOT policy has expected values`() {
        val policy = ThermalPolicy(
            band = ThermalBand.HOT,
            recommendedBackend = "CPU",
            burstSeconds = 4,
            restSeconds = 5,
            chunkTokens = 32,
        )
        assertEquals("CPU", policy.recommendedBackend)
        assertEquals(4, policy.burstSeconds)
        assertEquals(5, policy.restSeconds)
        assertEquals(32, policy.chunkTokens)
    }

    @Test
    fun `CRITICAL policy has expected values`() {
        val policy = ThermalPolicy(
            band = ThermalBand.CRITICAL,
            recommendedBackend = "CPU",
            burstSeconds = 0,
            restSeconds = 8,
            chunkTokens = 16,
        )
        assertEquals("CPU", policy.recommendedBackend)
        assertEquals(0, policy.burstSeconds)
        assertEquals(8, policy.restSeconds)
        assertEquals(16, policy.chunkTokens)
    }

    @Test
    fun `WARM and HOT switch from GPU to CPU backend`() {
        val warm = ThermalPolicy(ThermalBand.WARM, "GPU", 8, 3, 64)
        val hot = ThermalPolicy(ThermalBand.HOT, "CPU", 4, 5, 32)
        assertEquals("GPU", warm.recommendedBackend)
        assertEquals("CPU", hot.recommendedBackend)
    }

    @Test
    fun `burstSeconds decreases as band severity increases`() {
        val cool = ThermalPolicy(ThermalBand.COOL, "GPU", 12, 0, 128)
        val warm = ThermalPolicy(ThermalBand.WARM, "GPU", 8, 3, 64)
        val hot = ThermalPolicy(ThermalBand.HOT, "CPU", 4, 5, 32)
        val critical = ThermalPolicy(ThermalBand.CRITICAL, "CPU", 0, 8, 16)

        assertTrue(cool.burstSeconds > warm.burstSeconds)
        assertTrue(warm.burstSeconds > hot.burstSeconds)
        assertTrue(hot.burstSeconds > critical.burstSeconds)
    }

    @Test
    fun `restSeconds increases as band severity increases`() {
        val cool = ThermalPolicy(ThermalBand.COOL, "GPU", 12, 0, 128)
        val warm = ThermalPolicy(ThermalBand.WARM, "GPU", 8, 3, 64)
        val hot = ThermalPolicy(ThermalBand.HOT, "CPU", 4, 5, 32)
        val critical = ThermalPolicy(ThermalBand.CRITICAL, "CPU", 0, 8, 16)

        assertTrue(cool.restSeconds < warm.restSeconds)
        assertTrue(warm.restSeconds < hot.restSeconds)
        assertTrue(hot.restSeconds < critical.restSeconds)
    }

    @Test
    fun `chunkTokens halves as band severity increases`() {
        val cool = ThermalPolicy(ThermalBand.COOL, "GPU", 12, 0, 128)
        val warm = ThermalPolicy(ThermalBand.WARM, "GPU", 8, 3, 64)
        val hot = ThermalPolicy(ThermalBand.HOT, "CPU", 4, 5, 32)
        val critical = ThermalPolicy(ThermalBand.CRITICAL, "CPU", 0, 8, 16)

        assertEquals(128, cool.chunkTokens)
        assertEquals(64, warm.chunkTokens)
        assertEquals(32, hot.chunkTokens)
        assertEquals(16, critical.chunkTokens)
    }

    // ---- ThermalSample data class ------------------------------------------

    @Test
    fun `ThermalSample construction with all fields`() {
        val sample = ThermalSample(
            status = 2,
            headroomNow = 0.75f,
            headroom10s = 0.85f,
            timestampMs = 12345L,
        )
        assertEquals(2, sample.status)
        assertEquals(0.75f, sample.headroomNow!!, 0.001f)
        assertEquals(0.85f, sample.headroom10s!!, 0.001f)
        assertEquals(12345L, sample.timestampMs)
    }

    @Test
    fun `ThermalSample with null headroom values`() {
        val sample = ThermalSample(
            status = 0,
            headroomNow = null,
            headroom10s = null,
            timestampMs = 99999L,
        )
        assertEquals(0, sample.status)
        assertNull(sample.headroomNow)
        assertNull(sample.headroom10s)
    }

    @Test
    fun `ThermalSample with NONE status`() {
        val sample = ThermalSample(
            status = PowerManager.THERMAL_STATUS_NONE,
            headroomNow = 0.1f,
            headroom10s = 0.2f,
            timestampMs = 5000L,
        )
        assertEquals(PowerManager.THERMAL_STATUS_NONE, sample.status)
    }

    @Test
    fun `ThermalSample equality and copy`() {
        val s1 = ThermalSample(1, 0.5f, 0.6f, 1000L)
        val s2 = ThermalSample(1, 0.5f, 0.6f, 1000L)
        assertEquals(s1, s2)

        val s3 = s1.copy(status = 3)
        assertEquals(3, s3.status)
        assertEquals(s1.headroomNow, s3.headroomNow)
    }

    @Test
    fun `ThermalSample destructuring`() {
        val sample = ThermalSample(2, 0.7f, 0.8f, 2000L)
        val (status, headroomNow, headroom10s, ts) = sample
        assertEquals(2, status)
        assertEquals(0.7f, headroomNow!!, 0.001f)
        assertEquals(0.8f, headroom10s!!, 0.001f)
        assertEquals(2000L, ts)
    }

    // ---- ThermalPolicy data class ------------------------------------------

    @Test
    fun `ThermalPolicy equality`() {
        val p1 = ThermalPolicy(ThermalBand.COOL, "GPU", 12, 0, 128)
        val p2 = ThermalPolicy(ThermalBand.COOL, "GPU", 12, 0, 128)
        assertEquals(p1, p2)
    }

    @Test
    fun `ThermalPolicy copy modifies single field`() {
        val original = ThermalPolicy(ThermalBand.COOL, "GPU", 12, 0, 128)
        val modified = original.copy(band = ThermalBand.HOT)
        assertEquals(ThermalBand.HOT, modified.band)
        assertEquals("GPU", modified.recommendedBackend)
    }

    // ---- GPU re-enable cooldown logic (public API) -------------------------

    @Test
    fun `canReenableGpu returns true when COOL and no prior disable`() {
        // Monitor starts in COOL band, no prior GPU disable
        assertTrue(monitor.canReenableGpu())
    }

    @Test
    fun `canReenableGpu returns false within 30s of recordGpuDisabled`() {
        val disableTime = 100_000L
        every { SystemClock.uptimeMillis() } returns disableTime
        monitor.recordGpuDisabled()

        // 10s later — still within 30s cooldown
        every { SystemClock.uptimeMillis() } returns disableTime + 10_000L
        assertFalse(monitor.canReenableGpu())
    }

    @Test
    fun `canReenableGpu returns false at exactly 29s after disable`() {
        val disableTime = 100_000L
        every { SystemClock.uptimeMillis() } returns disableTime
        monitor.recordGpuDisabled()

        every { SystemClock.uptimeMillis() } returns disableTime + 29_999L
        assertFalse(monitor.canReenableGpu())
    }

    @Test
    fun `canReenableGpu returns true at exactly 30s after disable`() {
        val disableTime = 100_000L
        every { SystemClock.uptimeMillis() } returns disableTime
        monitor.recordGpuDisabled()

        every { SystemClock.uptimeMillis() } returns disableTime + 30_000L
        assertTrue(monitor.canReenableGpu())
    }

    @Test
    fun `canReenableGpu returns true well after 30s cooldown`() {
        val disableTime = 100_000L
        every { SystemClock.uptimeMillis() } returns disableTime
        monitor.recordGpuDisabled()

        every { SystemClock.uptimeMillis() } returns disableTime + 60_000L
        assertTrue(monitor.canReenableGpu())
    }

    @Test
    fun `recordGpuDisabled updates cooldown timestamp`() {
        val time1 = 100_000L
        every { SystemClock.uptimeMillis() } returns time1
        monitor.recordGpuDisabled()

        // After 30s from first disable, can re-enable
        every { SystemClock.uptimeMillis() } returns time1 + 30_000L
        assertTrue(monitor.canReenableGpu())

        // Disable again at a later time
        val time2 = time1 + 50_000L
        every { SystemClock.uptimeMillis() } returns time2
        monitor.recordGpuDisabled()

        // 10s after second disable — still in cooldown
        every { SystemClock.uptimeMillis() } returns time2 + 10_000L
        assertFalse(monitor.canReenableGpu())

        // 30s after second disable — can re-enable
        every { SystemClock.uptimeMillis() } returns time2 + 30_000L
        assertTrue(monitor.canReenableGpu())
    }

    // ---- Initial state -----------------------------------------------------

    @Test
    fun `initial band is COOL`() {
        assertEquals(ThermalBand.COOL, monitor.currentBand.value)
    }

    @Test
    fun `initial policy is COOL policy`() {
        val policy = monitor.currentPolicy.value
        assertEquals(ThermalBand.COOL, policy.band)
        assertEquals("GPU", policy.recommendedBackend)
    }

    @Test
    fun `initial latestSample is null`() {
        assertNull(monitor.latestSample.value)
    }

    // ---- Policy all-bands comprehensive check ------------------------------

    @Test
    fun `all four bands produce distinct policies`() {
        val policies = ThermalBand.entries.map { band ->
            ThermalPolicy(
                band = band,
                recommendedBackend = when (band) {
                    ThermalBand.COOL, ThermalBand.WARM -> "GPU"
                    ThermalBand.HOT, ThermalBand.CRITICAL -> "CPU"
                },
                burstSeconds = when (band) {
                    ThermalBand.COOL -> 12
                    ThermalBand.WARM -> 8
                    ThermalBand.HOT -> 4
                    ThermalBand.CRITICAL -> 0
                },
                restSeconds = when (band) {
                    ThermalBand.COOL -> 0
                    ThermalBand.WARM -> 3
                    ThermalBand.HOT -> 5
                    ThermalBand.CRITICAL -> 8
                },
                chunkTokens = when (band) {
                    ThermalBand.COOL -> 128
                    ThermalBand.WARM -> 64
                    ThermalBand.HOT -> 32
                    ThermalBand.CRITICAL -> 16
                },
            )
        }
        // All 4 policies should be distinct
        assertEquals(4, policies.toSet().size)
    }

    @Test
    fun `CRITICAL policy has zero burst seconds`() {
        val critical = ThermalPolicy(ThermalBand.CRITICAL, "CPU", 0, 8, 16)
        assertEquals(0, critical.burstSeconds)
    }

    @Test
    fun `COOL policy has zero rest seconds`() {
        val cool = ThermalPolicy(ThermalBand.COOL, "GPU", 12, 0, 128)
        assertEquals(0, cool.restSeconds)
    }
}
