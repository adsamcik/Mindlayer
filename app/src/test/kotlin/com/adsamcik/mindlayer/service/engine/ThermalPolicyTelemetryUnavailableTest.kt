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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F-073: pins the conservative-policy contract for telemetry-blind hardware.
 *
 * Phase 1 (`91afbb5`) wired the `telemetryAvailable: Boolean` signal on
 * [ThermalSample]. Phase 2 (this test) locks the policy decision: when the
 * signal is `false`, [ThermalMonitor] must emit a [ThermalPolicy] whose
 * [ThermalPolicy.confidence] is [ThermalConfidence.INFERRED] and whose
 * `burstSeconds` / `restSeconds` / `chunkTokens` come from the conservative
 * variant of the table documented in `docs/THERMAL_POLICY_ON_UNAVAILABLE.md`.
 *
 * Drives API levels via the `sdkInt: () -> Int` injection seam introduced
 * in `91afbb5`, NOT via static-field reflection on `Build.VERSION.SDK_INT`
 * — the audit established that pattern as unreliable because the Kotlin
 * compiler inlines static-final reads at the call site.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ThermalPolicyTelemetryUnavailableTest {

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

    // -------------------------------------------------------------------
    // Initial-state contract
    // -------------------------------------------------------------------

    @Test
    fun `initial policy is OBSERVED before any sample is taken`() {
        // Before processSample runs, the StateFlow holds the initial
        // policyForBand(COOL) value with default confidence=OBSERVED.
        // This pins the backward-compatible default — existing callers
        // that read `currentPolicy.value` immediately after construction
        // get the legacy values.
        val monitor = freshMonitor()
        val initial = monitor.currentPolicy.value

        assertEquals(ThermalBand.COOL, initial.band)
        assertEquals(ThermalConfidence.OBSERVED, initial.confidence)
        assertEquals("GPU", initial.recommendedBackend)
        assertEquals(12, initial.burstSeconds)
        assertEquals(0, initial.restSeconds)
        assertEquals(128, initial.chunkTokens)
    }

    // -------------------------------------------------------------------
    // API 26 / 28 — telemetry-blind path emits INFERRED policy
    // -------------------------------------------------------------------

    @Test
    fun `API 26 sample produces INFERRED policy with conservative duty-cycle`() {
        fakeSdkInt = Build.VERSION_CODES.O
        val monitor = freshMonitor()

        invokeProcessSample(monitor)
        val policy = monitor.currentPolicy.value

        assertEquals(
            "Telemetry-blind devices stay COOL band; the conservative pacing " +
                "is encoded in the policy fields, not the band ordinal.",
            ThermalBand.COOL, policy.band,
        )
        assertEquals(
            "API 26 has neither getCurrentThermalStatus nor getThermalHeadroom; " +
                "policy must mark itself INFERRED",
            ThermalConfidence.INFERRED, policy.confidence,
        )
        assertEquals("GPU", policy.recommendedBackend)
        assertEquals(
            "Conservative variant halves the COOL/OBSERVED 12s burst",
            6, policy.burstSeconds,
        )
        assertEquals(
            "Conservative variant introduces a non-zero rest",
            4, policy.restSeconds,
        )
        assertEquals(
            "Conservative variant halves chunkTokens to limit burst heat",
            64, policy.chunkTokens,
        )
    }

    @Test
    fun `API 28 sample produces INFERRED policy identical to API 26`() {
        // Both API 26 and API 28 lack getCurrentThermalStatus + headroom;
        // the policy table treats them identically.
        fakeSdkInt = Build.VERSION_CODES.P
        val monitor = freshMonitor()

        invokeProcessSample(monitor)
        val policy = monitor.currentPolicy.value

        assertEquals(ThermalConfidence.INFERRED, policy.confidence)
        assertEquals(6, policy.burstSeconds)
        assertEquals(4, policy.restSeconds)
        assertEquals(64, policy.chunkTokens)
    }

    // -------------------------------------------------------------------
    // API 29+ — telemetry available path emits OBSERVED policy
    // -------------------------------------------------------------------

    @Test
    fun `API 29 sample with status produces OBSERVED policy with legacy values`() {
        fakeSdkInt = Build.VERSION_CODES.Q
        every { powerManager.currentThermalStatus } returns PowerManager.THERMAL_STATUS_NONE
        val monitor = freshMonitor()

        invokeProcessSample(monitor)
        val policy = monitor.currentPolicy.value

        assertEquals(ThermalBand.COOL, policy.band)
        assertEquals(
            "API 29 has currentThermalStatus, so telemetry is available; " +
                "policy must remain OBSERVED with the legacy fast values",
            ThermalConfidence.OBSERVED, policy.confidence,
        )
        // Legacy COOL/OBSERVED values — must NOT regress on API 29+.
        assertEquals(12, policy.burstSeconds)
        assertEquals(0, policy.restSeconds)
        assertEquals(128, policy.chunkTokens)
    }

    @Test
    fun `API 30 sample with headroom produces OBSERVED policy with legacy values`() {
        fakeSdkInt = Build.VERSION_CODES.R
        every { powerManager.currentThermalStatus } returns PowerManager.THERMAL_STATUS_NONE
        every { powerManager.getThermalHeadroom(0) } returns 0.4f
        every { powerManager.getThermalHeadroom(10) } returns 0.4f
        val monitor = freshMonitor()

        invokeProcessSample(monitor)
        val policy = monitor.currentPolicy.value

        assertEquals(ThermalBand.COOL, policy.band)
        assertEquals(ThermalConfidence.OBSERVED, policy.confidence)
        assertEquals(128, policy.chunkTokens)
    }

    // -------------------------------------------------------------------
    // Transition contract — flip from INFERRED to OBSERVED updates policy
    // -------------------------------------------------------------------

    @Test
    fun `policy refreshes when telemetryAvailable flips even if band is unchanged`() {
        // Initial: API 26 (telemetry-blind) → INFERRED.
        fakeSdkInt = Build.VERSION_CODES.O
        val monitor = freshMonitor()
        invokeProcessSample(monitor)
        val inferred = monitor.currentPolicy.value
        assertEquals(ThermalConfidence.INFERRED, inferred.confidence)

        // Now imagine the device upgrades its OS / driver mid-session.
        // We re-sample with API 30 + telemetry returning a healthy
        // reading — the band stays COOL but confidence must flip to
        // OBSERVED and the policy values must refresh.
        fakeSdkInt = Build.VERSION_CODES.R
        every { powerManager.currentThermalStatus } returns PowerManager.THERMAL_STATUS_NONE
        every { powerManager.getThermalHeadroom(0) } returns 0.3f
        every { powerManager.getThermalHeadroom(10) } returns 0.3f

        invokeProcessSample(monitor)
        val observed = monitor.currentPolicy.value

        assertEquals(
            "Same band; but the policy fields must have refreshed because " +
                "confidence flipped — not stayed pinned to the prior INFERRED " +
                "duty-cycle values.",
            ThermalBand.COOL, observed.band,
        )
        assertEquals(ThermalConfidence.OBSERVED, observed.confidence)
        assertEquals(12, observed.burstSeconds)
        assertEquals(0, observed.restSeconds)
        assertNotEquals(inferred, observed)
    }

    @Test
    fun `policy refreshes when telemetryAvailable disappears`() {
        // Initial: API 30 with telemetry → OBSERVED.
        fakeSdkInt = Build.VERSION_CODES.R
        every { powerManager.currentThermalStatus } returns PowerManager.THERMAL_STATUS_NONE
        every { powerManager.getThermalHeadroom(0) } returns 0.4f
        every { powerManager.getThermalHeadroom(10) } returns 0.4f
        val monitor = freshMonitor()
        invokeProcessSample(monitor)
        assertEquals(ThermalConfidence.OBSERVED, monitor.currentPolicy.value.confidence)

        // Telemetry disappears (e.g. the API starts returning NaN/Infinity
        // and getCurrentThermalStatus is no longer wired by the stub).
        // Drive that by switching to an SDK that lacks both APIs.
        fakeSdkInt = Build.VERSION_CODES.O
        invokeProcessSample(monitor)

        val policy = monitor.currentPolicy.value
        assertEquals(ThermalConfidence.INFERRED, policy.confidence)
        assertEquals(6, policy.burstSeconds)
    }

    // -------------------------------------------------------------------
    // policyForBand contract
    // -------------------------------------------------------------------

    @Test
    fun `policyForBand returns conservative variant for every band when INFERRED`() {
        // Defensive coverage of the INFERRED rows that are not normally
        // reachable at runtime (telemetry-blind devices stay in COOL).
        // If a future code path forces a band elevation under inferred
        // confidence, the conservative table must still produce a valid
        // ThermalPolicy and not, say, return the OBSERVED row by accident.
        val monitor = freshMonitor()

        val coolInferred = monitor.policyForBand(ThermalBand.COOL, ThermalConfidence.INFERRED)
        assertNotNull(coolInferred)
        assertEquals(ThermalConfidence.INFERRED, coolInferred.confidence)
        assertEquals(6, coolInferred.burstSeconds)
        assertEquals(4, coolInferred.restSeconds)
        assertEquals(64, coolInferred.chunkTokens)

        val warmInferred = monitor.policyForBand(ThermalBand.WARM, ThermalConfidence.INFERRED)
        assertEquals(ThermalConfidence.INFERRED, warmInferred.confidence)
        assertEquals(4, warmInferred.burstSeconds)
        assertEquals(5, warmInferred.restSeconds)
        assertEquals(32, warmInferred.chunkTokens)
        assertEquals("GPU", warmInferred.recommendedBackend)

        val hotInferred = monitor.policyForBand(ThermalBand.HOT, ThermalConfidence.INFERRED)
        assertEquals(ThermalConfidence.INFERRED, hotInferred.confidence)
        assertEquals("CPU", hotInferred.recommendedBackend)
        assertEquals(3, hotInferred.burstSeconds)
        assertEquals(6, hotInferred.restSeconds)
        assertEquals(16, hotInferred.chunkTokens)

        val critInferred = monitor.policyForBand(ThermalBand.CRITICAL, ThermalConfidence.INFERRED)
        assertEquals(ThermalConfidence.INFERRED, critInferred.confidence)
        assertEquals("CPU", critInferred.recommendedBackend)
        assertEquals(0, critInferred.burstSeconds)
        assertEquals(10, critInferred.restSeconds)
        assertEquals(16, critInferred.chunkTokens)
    }

    @Test
    fun `policyForBand returns legacy values for every band when OBSERVED`() {
        // Pin the OBSERVED column byte-for-byte against the values
        // exercised by the existing 35 ThermalBandTest cases. If this
        // test fails, you have changed shipping production behaviour
        // for API 29+ devices — that's a regression by definition.
        val monitor = freshMonitor()

        val cool = monitor.policyForBand(ThermalBand.COOL, ThermalConfidence.OBSERVED)
        assertEquals(ThermalConfidence.OBSERVED, cool.confidence)
        assertEquals("GPU", cool.recommendedBackend)
        assertEquals(12, cool.burstSeconds)
        assertEquals(0, cool.restSeconds)
        assertEquals(128, cool.chunkTokens)

        val warm = monitor.policyForBand(ThermalBand.WARM, ThermalConfidence.OBSERVED)
        assertEquals("GPU", warm.recommendedBackend)
        assertEquals(8, warm.burstSeconds)
        assertEquals(3, warm.restSeconds)
        assertEquals(64, warm.chunkTokens)

        val hot = monitor.policyForBand(ThermalBand.HOT, ThermalConfidence.OBSERVED)
        assertEquals("CPU", hot.recommendedBackend)
        assertEquals(4, hot.burstSeconds)
        assertEquals(5, hot.restSeconds)
        assertEquals(32, hot.chunkTokens)

        val crit = monitor.policyForBand(ThermalBand.CRITICAL, ThermalConfidence.OBSERVED)
        assertEquals("CPU", crit.recommendedBackend)
        assertEquals(0, crit.burstSeconds)
        assertEquals(8, crit.restSeconds)
        assertEquals(16, crit.chunkTokens)
    }

    // -------------------------------------------------------------------
    // Wire / dashboard sentinel contract
    // -------------------------------------------------------------------

    @Test
    fun `INFERRED policy carries explicit confidence flag for ServiceBinder mapping`() {
        // ServiceBinder maps confidence==INFERRED to the
        // "UNAVAILABLE" sentinel string in ServiceStatus.thermalBand —
        // pin that the source of truth is the confidence enum, not a
        // band-name match. This keeps ServiceBinder's branch logic
        // testable in isolation from the thermal-state machine.
        fakeSdkInt = Build.VERSION_CODES.O
        val monitor = freshMonitor()
        invokeProcessSample(monitor)

        val policy = monitor.currentPolicy.value
        assertTrue(
            "Wire-side mapping branches on confidence; we should not need " +
                "to inspect the band name to detect telemetry-blind state",
            policy.confidence == ThermalConfidence.INFERRED,
        )
    }

    /** Reflectively invoke private [ThermalMonitor.processSample]. */
    private fun invokeProcessSample(monitor: ThermalMonitor) {
        val method = ThermalMonitor::class.java.getDeclaredMethod("processSample")
        method.isAccessible = true
        method.invoke(monitor)
    }
}
