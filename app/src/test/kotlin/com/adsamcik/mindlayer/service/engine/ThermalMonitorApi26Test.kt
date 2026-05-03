package com.adsamcik.mindlayer.service.engine

import android.content.Context
import android.os.Build
import android.os.PowerManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F-078: integrated-path Robolectric proof that
 * [ThermalMonitor.takeSample] correctly emits
 * `telemetryAvailable = false` on API 26 — *without* the test-only
 * `sdkInt: () -> Int` injection.
 *
 * [ThermalSampleTelemetryTest] already covers the SDK-level matrix via
 * the injection mechanism (so each test can pick its own API level
 * regardless of the host JVM). That's good for breadth, but it does
 * NOT prove that the production default lambda
 * `{ Build.VERSION.SDK_INT }` actually reads the runtime SDK level
 * correctly. This class closes that loop: under Robolectric
 * `@Config(sdk = [26])`, `Build.VERSION.SDK_INT == 26`, and the
 * monitor (constructed with all defaults) must compute
 * `telemetryAvailable = false` because neither
 * [PowerManager.getCurrentThermalStatus] (API 29+) nor
 * [PowerManager.getThermalHeadroom] (API 30+) is available.
 *
 * If this test ever fails it means either:
 * 1. The default `sdkInt` lambda has been replaced with something that
 *    no longer reads `Build.VERSION.SDK_INT`, or
 * 2. The `telemetryAvailable` derivation in [ThermalMonitor.takeSample]
 *    has regressed and is incorrectly reporting `true` on a
 *    telemetry-blind device.
 *
 * Either is a P1 regression — Android 8 / 8.1 devices would silently
 * be misread as thermally-healthy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ThermalMonitorApi26Test {

    @Test
    fun `Robolectric sanity check - SDK_INT is 26`() {
        // Belt-and-braces: if Robolectric ever stops honouring
        // `@Config(sdk=N)` for SDK_INT, this test class is silently
        // useless. Pin the precondition explicitly.
        assertEquals(26, Build.VERSION.SDK_INT)
    }

    @Test
    fun `production default sdkInt on API 26 emits telemetryAvailable=false`() {
        val powerManager = mockk<PowerManager>(relaxed = true)
        val context = mockk<Context>(relaxed = true) {
            every { getSystemService(PowerManager::class.java) } returns powerManager
        }

        // Construct WITHOUT the sdkInt injection — exercise the
        // production default `{ Build.VERSION.SDK_INT }`.
        val monitor = ThermalMonitor(
            context = context,
            scope = TestScope(),
        )

        val sample = invokeTakeSample(monitor)

        assertFalse(
            "API 26 (minSdk) has neither thermal status nor headroom — telemetry MUST be unavailable",
            sample.telemetryAvailable,
        )
        assertEquals(PowerManager.THERMAL_STATUS_NONE, sample.status)
        assertNull("Headroom is API 30+; null on API 26", sample.headroomNow)
        assertNull("Headroom is API 30+; null on API 26", sample.headroom10s)
        assertTrue("Sample must carry a non-zero monotonic timestamp", sample.timestampMs > 0L)
    }

    /** Reach into the private takeSample to read the resulting sample. */
    private fun invokeTakeSample(monitor: ThermalMonitor): ThermalSample {
        val method = ThermalMonitor::class.java.getDeclaredMethod("takeSample")
        method.isAccessible = true
        return method.invoke(monitor) as ThermalSample
    }
}
