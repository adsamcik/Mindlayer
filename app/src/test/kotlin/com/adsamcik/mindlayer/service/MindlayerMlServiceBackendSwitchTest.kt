package com.adsamcik.mindlayer.service

import com.adsamcik.mindlayer.service.engine.EngineNotReadyException
import com.adsamcik.mindlayer.service.engine.ThermalBand
import com.adsamcik.mindlayer.service.logging.LogRepository
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the thermal/backend-switch starvation guard (R-3) and the
 * backend-switch restart quiescing race fix (R-6).
 *
 * The full `applyPendingBackendSwitch` flow ends in `Process.killProcess`,
 * so we test the two extracted, deterministic policy points:
 *  - `shouldForceOverdueThermalSwitch` (R-3): when a deferred downshift is
 *    forced.
 *  - `enterForeground` rejecting a new inference while quiescing (R-6).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MindlayerMlServiceBackendSwitchTest {

    @After
    fun tearDown() = unmockkAll()

    private fun newService(): MindlayerMlService {
        val service = Robolectric.buildService(MindlayerMlService::class.java).get()
        setField(service, "logRepository", mockk<LogRepository>(relaxed = true))
        mockkObject(MindlayerLog)
        every { MindlayerLog.i(any(), any(), any(), any()) } returns Unit
        every { MindlayerLog.w(any(), any(), any(), any(), any()) } returns Unit
        every { MindlayerLog.e(any(), any(), any(), any(), any()) } returns Unit
        return service
    }

    // ---- R-3: overdue-switch forcing policy --------------------------------

    @Test
    fun `does not force a non-critical deferred switch (R-3)`() {
        val s = newService()
        // HOT (not CRITICAL) deferred well past the deadline must NOT force —
        // we only pre-empt in-flight inference when the device is genuinely
        // too hot.
        assertFalse(
            s.shouldForceOverdueThermalSwitch(
                band = ThermalBand.HOT,
                pendingSince = 1_000L,
                now = 1_000L + 10 * 60_000L,
                deadlineMs = 20_000L,
            ),
        )
    }

    @Test
    fun `does not force before the deadline even under CRITICAL (R-3)`() {
        val s = newService()
        assertFalse(
            s.shouldForceOverdueThermalSwitch(
                band = ThermalBand.CRITICAL,
                pendingSince = 1_000L,
                now = 1_000L + 19_999L,
                deadlineMs = 20_000L,
            ),
        )
    }

    @Test
    fun `forces an overdue CRITICAL deferred switch (R-3)`() {
        val s = newService()
        assertTrue(
            s.shouldForceOverdueThermalSwitch(
                band = ThermalBand.CRITICAL,
                pendingSince = 1_000L,
                now = 1_000L + 20_000L,
                deadlineMs = 20_000L,
            ),
        )
    }

    @Test
    fun `never forces when no switch is pending (pendingSince == 0)`() {
        val s = newService()
        assertFalse(
            s.shouldForceOverdueThermalSwitch(
                band = ThermalBand.CRITICAL,
                pendingSince = 0L,
                now = 10 * 60_000L,
                deadlineMs = 20_000L,
            ),
        )
    }

    // ---- R-6: quiescing rejects new inferences -----------------------------

    @Test
    fun `enterForeground rejects a new inference while quiescing for restart (R-6)`() {
        val s = newService()
        s.setQuiescingForRestartForTest(true)

        // A new inference arriving after the backend-switch restart decision
        // must be rejected (retryable) so it can't race the process restart.
        assertThrows(EngineNotReadyException::class.java) {
            s.enterForeground()
        }
        // The rejected call must NOT have bumped the refcount.
        assertEquals(0, s.activeInferenceCount)
    }

    @Test
    fun `enterForeground proceeds normally when not quiescing (R-6 boundary)`() {
        val s = newService()
        s.setQuiescingForRestartForTest(false)
        // startForeground succeeds under Robolectric; the count increments.
        s.enterForeground()
        assertEquals(1, s.activeInferenceCount)
        s.exitForeground()
        assertEquals(0, s.activeInferenceCount)
    }

    private fun setField(target: Any, name: String, value: Any) {
        val field = MindlayerMlService::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }
}
