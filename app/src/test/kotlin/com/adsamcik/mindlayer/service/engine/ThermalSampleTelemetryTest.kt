package com.adsamcik.mindlayer.service.engine

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Plain-JUnit regression coverage for [ThermalSample.telemetryAvailable]
 * across the API matrix the production default supports.
 *
 * The audit found that on Android 8 / 8.1 (API 26-28) the band always
 * computes COOL because neither [PowerManager.getCurrentThermalStatus]
 * (API 29+) nor [PowerManager.getThermalHeadroom] (API 30+) is
 * available — which masks actual thermal stress on ~15-20 % of the
 * install base. The fix added an explicit `telemetryAvailable` flag so
 * consumers (dashboard, [RequestTrace], thermal policy) can distinguish
 * "device reported COOL" from "device cannot report at all".
 *
 * Drives API-level scenarios via the `readThermalStatus` /
 * `readThermalHeadroomRaw` injection seams on [ThermalMonitor]. Each
 * test substitutes a reader that simulates the platform behaviour at
 * the chosen API level — `null` from `readThermalStatus` for
 * API < 29, `Float.NaN` from `readThermalHeadroomRaw` for API < 30.
 *
 * The seams replaced an earlier `sdkInt: () -> Int` injection
 * because AGP 9 lint flagged the lambda-indirected `Build.VERSION.SDK_INT`
 * comparison as `NewApi` (the version comparison must read the SDK
 * field directly to satisfy lint's API-guard heuristic). Production
 * defaults retain direct `Build.VERSION.SDK_INT` guards inside the
 * reader implementations; an integrated proof that the production
 * defaults read `SDK_INT` correctly under Robolectric lives in
 * [ThermalMonitorApi26Test].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThermalSampleTelemetryTest {

    private lateinit var context: Context

    /** Status reader override, mutable per test. `null` = "API < 29". */
    private var statusReader: () -> Int? = { null }

    /** Headroom reader override, mutable per test. `Float.NaN` = "API < 30". */
    private var headroomReader: (Int) -> Float = { _ -> Float.NaN }

    private fun freshMonitor(): ThermalMonitor =
        ThermalMonitor(
            context = context,
            scope = TestScope(),
            readThermalStatus = { statusReader() },
            readThermalHeadroomRaw = { s -> headroomReader(s) },
        )

    @Before
    fun setUp() {
        mockkStatic(SystemClock::class)
        mockkStatic(Log::class)
        mockkObject(MindlayerLog)

        every { SystemClock.uptimeMillis() } returns 100_000L
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { MindlayerLog.d(any(), any(), any(), any()) } returns Unit
        every { MindlayerLog.i(any(), any(), any(), any()) } returns Unit
        every { MindlayerLog.w(any(), any(), any(), any(), any()) } returns Unit
        every { MindlayerLog.e(any(), any(), any(), any(), any()) } returns Unit

        val powerManager = mockk<PowerManager>(relaxed = true)
        context = mockk {
            every { getSystemService(PowerManager::class.java) } returns powerManager
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `takeSample on API 30+ marks telemetry as available`() {
        // API 30+: both status and headroom return concrete values.
        statusReader = { PowerManager.THERMAL_STATUS_NONE }
        headroomReader = { _ -> 0.5f }

        val sample = invokeTakeSample(freshMonitor())

        assertTrue(
            "API 30+ exposes both status and headroom; telemetry must be marked available",
            sample.telemetryAvailable,
        )
        assertEquals(0.5f, sample.headroomNow!!, 0.001f)
    }

    @Test
    fun `takeSample on API 29 marks telemetry as available via status alone`() {
        // API 29: status is concrete; headroom API does not exist (NaN).
        statusReader = { PowerManager.THERMAL_STATUS_NONE }
        headroomReader = { _ -> Float.NaN }

        val sample = invokeTakeSample(freshMonitor())

        assertTrue(
            "API 29 has currentThermalStatus even without headroom; telemetry is available",
            sample.telemetryAvailable,
        )
        // Headroom is null because getThermalHeadroom is API 30+.
        assertNull(sample.headroomNow)
        assertNull(sample.headroom10s)
    }

    @Test
    fun `takeSample on API 28 (Android 9) marks telemetry as unavailable`() {
        // API 28: neither API exists. Status reader returns null; headroom is NaN.
        statusReader = { null }
        headroomReader = { _ -> Float.NaN }

        val sample = invokeTakeSample(freshMonitor())

        assertFalse(
            "API 28 has neither thermal status nor headroom — telemetry must be marked unavailable so " +
                "consumers know the COOL band is a default, not an actual reading",
            sample.telemetryAvailable,
        )
        assertEquals(PowerManager.THERMAL_STATUS_NONE, sample.status)
        assertNull(sample.headroomNow)
        assertNull(sample.headroom10s)
    }

    @Test
    fun `takeSample on API 26 (minSdk Android 8) marks telemetry as unavailable`() {
        // API 26 (minSdk): same shape as API 28 — neither API exists.
        statusReader = { null }
        headroomReader = { _ -> Float.NaN }

        val sample = invokeTakeSample(freshMonitor())

        assertFalse(
            "API 26 (minSdk) has no thermal API at all — telemetry MUST be marked unavailable",
            sample.telemetryAvailable,
        )
        assertEquals(PowerManager.THERMAL_STATUS_NONE, sample.status)
    }

    @Test
    fun `takeSample on API 30+ with infinite headroom still marks telemetry available via status`() {
        // Devices occasionally return Float.POSITIVE_INFINITY from
        // getThermalHeadroom under transient conditions. The takeIf{isFinite}
        // filter inside takeSample collapses that to null, but status from
        // API 29+ still provides a signal — so telemetryAvailable must remain
        // true. We feed the raw infinity through the seam so the production
        // filter is exercised end-to-end.
        statusReader = { PowerManager.THERMAL_STATUS_LIGHT }
        headroomReader = { _ -> Float.POSITIVE_INFINITY }

        val sample = invokeTakeSample(freshMonitor())

        assertTrue(sample.telemetryAvailable)
        assertNull("Infinite headroom is filtered to null", sample.headroomNow)
        assertNull(sample.headroom10s)
        assertEquals(PowerManager.THERMAL_STATUS_LIGHT, sample.status)
    }

    /** Reach into the private takeSample to read the new field. */
    private fun invokeTakeSample(monitor: ThermalMonitor): ThermalSample {
        val method = ThermalMonitor::class.java.getDeclaredMethod("takeSample")
        method.isAccessible = true
        return method.invoke(monitor) as ThermalSample
    }
}
