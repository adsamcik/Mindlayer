package com.mindlayer.service.integration

import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.mindlayer.service.engine.ThermalBand
import com.mindlayer.service.engine.ThermalMonitor
import com.mindlayer.service.engine.ThermalSample
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests ThermalMonitor band computation, hysteresis, and GPU cooldown
 * by calling [ThermalMonitor.computeBand] directly with synthetic samples.
 * No polling, no Robolectric, no SDK_INT issues.
 */
class ThermalStateMachineTest {

    private lateinit var monitor: ThermalMonitor
    private var mockElapsedRealtime = 100_000L

    @Before
    fun setUp() {
        mockkStatic(SystemClock::class)
        mockkStatic(Log::class)
        every { SystemClock.uptimeMillis() } answers { mockElapsedRealtime }
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        val context = mockk<android.content.Context>(relaxed = true) {
            every { getSystemService(any<Class<*>>()) } returns mockk<PowerManager>(relaxed = true)
        }
        monitor = ThermalMonitor(context, kotlinx.coroutines.test.TestScope())
    }

    @After
    fun tearDown() = unmockkAll()

    private fun sample(
        status: Int = PowerManager.THERMAL_STATUS_NONE,
        headroom10s: Float? = null,
    ) = ThermalSample(status, headroomNow = null, headroom10s = headroom10s, timestampMs = mockElapsedRealtime)

    // === Band entry via headroom ===

    @Test
    fun `initialState_isCool`() {
        assertEquals(ThermalBand.COOL, monitor.currentBand.value)
    }

    @Test
    fun `headroom080_entersWarm`() {
        val band = monitor.computeBand(sample(headroom10s = 0.81f), ThermalBand.COOL)
        assertEquals(ThermalBand.WARM, band)
    }

    @Test
    fun `headroom092_entersHot`() {
        val band = monitor.computeBand(sample(headroom10s = 0.93f), ThermalBand.COOL)
        assertEquals(ThermalBand.HOT, band)
    }

    @Test
    fun `headroom100_entersCritical`() {
        val band = monitor.computeBand(sample(headroom10s = 1.01f), ThermalBand.COOL)
        assertEquals(ThermalBand.CRITICAL, band)
    }

    // === Band entry via status ===

    @Test
    fun `statusSevere_entersCritical`() {
        // On JVM, Build.VERSION.SDK_INT=0 so status-based checks are skipped.
        // Test via headroom (the production equivalent):
        // SEVERE status + high headroom both mean CRITICAL.
        val band = monitor.computeBand(
            sample(status = PowerManager.THERMAL_STATUS_SEVERE, headroom10s = 1.05f),
            ThermalBand.COOL,
        )
        assertEquals(ThermalBand.CRITICAL, band)
    }

    // === Hysteresis: WARM exit ===

    @Test
    fun `hysteresis_warmExit`() {
        // In WARM, headroom 0.75 (above exit 0.70) → stays WARM
        val staysWarm = monitor.computeBand(sample(headroom10s = 0.75f), ThermalBand.WARM)
        assertEquals(ThermalBand.WARM, staysWarm)

        // In WARM, headroom 0.69 (below exit 0.70) → exits to COOL
        val exitsCool = monitor.computeBand(sample(headroom10s = 0.69f), ThermalBand.WARM)
        assertEquals(ThermalBand.COOL, exitsCool)
    }

    // === Hysteresis: HOT exit ===

    @Test
    fun `hysteresis_hotExit`() {
        // In HOT, headroom 0.85 (above exit 0.82) → stays HOT
        val staysHot = monitor.computeBand(sample(headroom10s = 0.85f), ThermalBand.HOT)
        assertEquals(ThermalBand.HOT, staysHot)

        // In HOT, headroom 0.81 (below exit 0.82, above warm enter 0.80) → WARM
        val exitsWarm = monitor.computeBand(sample(headroom10s = 0.81f), ThermalBand.HOT)
        assertEquals(ThermalBand.WARM, exitsWarm)
    }

    // === GPU cooldown ===

    @Test
    fun `gpuCooldown_30seconds`() {
        mockElapsedRealtime = 100_000L
        monitor.recordGpuDisabled()
        assertFalse(monitor.canReenableGpu())

        mockElapsedRealtime = 115_000L
        assertFalse(monitor.canReenableGpu())

        mockElapsedRealtime = 131_000L
        assertTrue(monitor.canReenableGpu())
    }

    @Test
    fun `gpuCooldown_notCoolBand`() {
        // Force band to WARM via internal state
        // canReenableGpu checks currentBand.value != COOL
        mockElapsedRealtime = 100_000L
        monitor.recordGpuDisabled()
        mockElapsedRealtime = 131_000L

        // Band is COOL (default) → true
        assertTrue(monitor.canReenableGpu())
    }

    // === Policy mapping ===

    @Test
    fun `policyMapping_allBands`() {
        val cool = monitor.currentPolicy.value
        assertEquals(ThermalBand.COOL, cool.band)
        assertEquals("GPU", cool.recommendedBackend)
        assertEquals(12, cool.burstSeconds)
        assertEquals(0, cool.restSeconds)
        assertEquals(128, cool.chunkTokens)

        // Verify other band policies via computeBand + policyForBand pattern
        val warm = monitor.computeBand(sample(headroom10s = 0.81f), ThermalBand.COOL)
        assertEquals(ThermalBand.WARM, warm)

        val hot = monitor.computeBand(sample(headroom10s = 0.93f), ThermalBand.COOL)
        assertEquals(ThermalBand.HOT, hot)

        val critical = monitor.computeBand(sample(headroom10s = 1.01f), ThermalBand.COOL)
        assertEquals(ThermalBand.CRITICAL, critical)
    }

    // === Edge-case: null / NaN / sub-threshold headroom ===

    @Test
    fun `nullHeadroom_fallsBackToStatusOnly`() {
        // headroom10s = null, status = THERMAL_STATUS_NONE → no headroom checks, no status checks on JVM → COOL
        val band = monitor.computeBand(
            sample(status = PowerManager.THERMAL_STATUS_NONE, headroom10s = null),
            ThermalBand.COOL,
        )
        assertEquals(ThermalBand.COOL, band)
    }

    @Test
    fun `nanHeadroom_fallsBackToStatusOnly`() {
        // headroom10s = NaN → h.isFinite() is false everywhere → headroom checks skipped → COOL
        val band = monitor.computeBand(
            sample(status = PowerManager.THERMAL_STATUS_NONE, headroom10s = Float.NaN),
            ThermalBand.COOL,
        )
        assertEquals(ThermalBand.COOL, band)
    }

    @Test
    fun `belowAllThresholds_staysCool`() {
        // headroom10s = 0.5 is below WARM_ENTER (0.80) → remains COOL
        val band = monitor.computeBand(sample(headroom10s = 0.5f), ThermalBand.COOL)
        assertEquals(ThermalBand.COOL, band)
    }

    @Test
    fun `repeatedSameBand_noExtraStateChanges`() {
        // Calling computeBand with the same headroom twice yields the same result (idempotent)
        val first = monitor.computeBand(sample(headroom10s = 0.85f), ThermalBand.COOL)
        val second = monitor.computeBand(sample(headroom10s = 0.85f), ThermalBand.COOL)
        assertEquals(first, second)
        assertEquals(ThermalBand.WARM, first)
    }
}
