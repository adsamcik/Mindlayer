package com.adsamcik.mindlayer.service.engine

import android.content.Context
import android.os.Build
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric-hosted regression coverage for
 * [ThermalSample.telemetryAvailable] across Android API levels.
 *
 * The audit found that on Android 8 / 8.1 (API 26-28) the band always
 * computes COOL because neither [PowerManager.getCurrentThermalStatus]
 * (API 29+) nor [PowerManager.getThermalHeadroom] (API 30+) is
 * available — which masks actual thermal stress on ~15-20 % of the
 * install base. The fix adds an explicit `telemetryAvailable` flag so
 * consumers (dashboard, [RequestTrace], future thermal policy) can
 * distinguish "device reported COOL" from "device cannot report at all".
 *
 * This class is a separate Robolectric runner so the reflection-based
 * [setStaticField] override of `Build.VERSION.SDK_INT` actually takes
 * effect — under plain JUnit the field is the static-final default
 * (effectively inlined to 0) and writes are silently ignored.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThermalSampleTelemetryTest {

    private lateinit var context: Context
    private lateinit var powerManager: PowerManager

    /** Mutable so each test can pick the SDK level for ThermalMonitor to read. */
    private var fakeSdkInt: Int = 33

    private fun freshMonitor(): ThermalMonitor =
        ThermalMonitor(
            context = context,
            scope = TestScope(),
            sdkInt = { fakeSdkInt },
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

        powerManager = mockk(relaxed = true)
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
        fakeSdkInt = Build.VERSION_CODES.R
        every { powerManager.currentThermalStatus } returns PowerManager.THERMAL_STATUS_NONE
        every { powerManager.getThermalHeadroom(0) } returns 0.5f
        every { powerManager.getThermalHeadroom(10) } returns 0.5f

        val sample = invokeTakeSample(freshMonitor())

        assertTrue(
            "API 30+ exposes both status and headroom; telemetry must be marked available",
            sample.telemetryAvailable,
        )
        assertEquals(0.5f, sample.headroomNow!!, 0.001f)
    }

    @Test
    fun `takeSample on API 29 marks telemetry as available via status alone`() {
        fakeSdkInt = Build.VERSION_CODES.Q
        every { powerManager.currentThermalStatus } returns PowerManager.THERMAL_STATUS_NONE

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
        fakeSdkInt = Build.VERSION_CODES.P

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
        fakeSdkInt = Build.VERSION_CODES.O

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
        // filters those to null, but status from API 29+ still provides a
        // signal — so telemetryAvailable must remain true.
        fakeSdkInt = Build.VERSION_CODES.R
        every { powerManager.currentThermalStatus } returns PowerManager.THERMAL_STATUS_LIGHT
        every { powerManager.getThermalHeadroom(0) } returns Float.POSITIVE_INFINITY
        every { powerManager.getThermalHeadroom(10) } returns Float.POSITIVE_INFINITY

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
