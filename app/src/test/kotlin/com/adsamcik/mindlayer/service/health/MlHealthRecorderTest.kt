package com.adsamcik.mindlayer.service.health

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * F-074: unit tests for the crash-loop watchdog.
 *
 * Covers the three acceptance criteria spelled out in the F-074 issue:
 *  1. Three abnormal deaths within the rolling window → throttle on bind.
 *  2. Three deaths followed by a long uptime → counter resets, throttle clears.
 *  3. Missed-death detection on next boot when the previous run was killed
 *     externally (no clean-shutdown marker, no uncaught-exception bump).
 *
 * Plus persistence + cooldown timestamp regressions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MlHealthRecorderTest {

    private lateinit var dir: File
    private var virtualClockMs: Long = 1_000_000L

    private val clock: () -> Long = { virtualClockMs }

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("ml_health_test_").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun newRecorder(): MlHealthRecorder = MlHealthRecorder(dir, clock)

    private fun advance(ms: Long) {
        virtualClockMs += ms
    }

    // ---- Acceptance 1 -----------------------------------------------------

    @Test
    fun `three abnormal deaths within 60s trip the throttle on the next bind`() {
        val recorder = newRecorder()
        // Initial boot — no prior state.
        recorder.recordHealthyBoot()

        // Simulate three rapid uncaught-exception cycles.
        repeat(MlHealthRecorder.DEATH_COUNT_THRESHOLD) {
            advance(5_000L)
            recorder.recordAbnormalDeath()
        }

        // Fourth boot a few seconds later — within the rolling window.
        advance(2_000L)
        val nextBoot = newRecorder()
        nextBoot.recordHealthyBoot()
        assertTrue(
            "Watchdog should engage after $MlHealthRecorder.DEATH_COUNT_THRESHOLD deaths inside the rolling window",
            nextBoot.shouldThrottleBinds(),
        )
        // cooldownEndsAt is lastDeathAt + THROTTLE_WINDOW_MS — verify the
        // value moves forward as expected so the SDK can use it for backoff.
        val expected = recorderLastDeath(nextBoot) + MlHealthRecorder.THROTTLE_WINDOW_MS
        assertEquals(expected, nextBoot.cooldownEndsAt())
    }

    @Test
    fun `two abnormal deaths do not trip the throttle`() {
        val recorder = newRecorder()
        recorder.recordHealthyBoot()
        advance(1_000L)
        recorder.recordAbnormalDeath()
        advance(1_000L)
        recorder.recordAbnormalDeath()
        assertFalse(recorder.shouldThrottleBinds())
    }

    // ---- Acceptance 2 -----------------------------------------------------

    @Test
    fun `counter resets after HEALTHY_UPTIME_DECAY_MS without a death`() {
        val recorder = newRecorder()
        recorder.recordHealthyBoot()

        // Three deaths trip the throttle.
        repeat(MlHealthRecorder.DEATH_COUNT_THRESHOLD) {
            advance(2_000L)
            recorder.recordAbnormalDeath()
        }
        assertTrue(recorder.shouldThrottleBinds())

        // Sleep past the decay threshold without any further deaths.
        advance(MlHealthRecorder.HEALTHY_UPTIME_DECAY_MS + 1_000L)

        // The rolling window already cleared the throttle (60 s < decay):
        assertFalse(
            "Rolling 60s window should have cleared the throttle by now",
            recorder.shouldThrottleBinds(),
        )

        // A fresh boot should also reset the persisted counter so the
        // *next* abnormal death starts from one, not four.
        val nextBoot = newRecorder()
        nextBoot.recordHealthyBoot()
        val snapshot = nextBoot.peek()
        assertEquals(
            "decay reset should zero the persisted death count",
            0,
            snapshot.deathCount,
        )
    }

    // ---- Acceptance 3 -----------------------------------------------------

    @Test
    fun `simulated kernel kill bumps deathCount on next boot`() {
        val recorder = newRecorder()
        recorder.recordHealthyBoot()
        val baseSnapshot = recorder.peek()
        assertEquals(0, baseSnapshot.deathCount)

        // The previous run is killed externally (OOM, SIGKILL): no
        // recordCleanShutdown, no recordAbnormalDeath. Just time passes
        // and a new instance boots. We simulate it by constructing a
        // fresh recorder and running recordHealthyBoot — the missed-death
        // detection inside should pick up that lastBootAt > both
        // lastCleanShutdownAt and lastDeathAt.
        advance(10_000L)

        val nextBoot = newRecorder()
        nextBoot.recordHealthyBoot()
        assertEquals(
            "missed-death detection should bump the count by 1",
            1,
            nextBoot.peek().deathCount,
        )
    }

    @Test
    fun `clean shutdown skips missed-death bump on next boot`() {
        val recorder = newRecorder()
        recorder.recordHealthyBoot()

        // Clean teardown ran.
        advance(5_000L)
        recorder.recordCleanShutdown()

        advance(5_000L)
        val nextBoot = newRecorder()
        nextBoot.recordHealthyBoot()
        assertEquals(
            "deathCount must not be bumped after a clean shutdown",
            0,
            nextBoot.peek().deathCount,
        )
        assertFalse(nextBoot.shouldThrottleBinds())
    }

    @Test
    fun `uncaught-exception bump is not double-counted by missed-death detection`() {
        val recorder = newRecorder()
        recorder.recordHealthyBoot()

        // Uncaught handler fires.
        advance(3_000L)
        recorder.recordAbnormalDeath()
        assertEquals(1, recorder.peek().deathCount)

        // Next boot — UEH already incremented the counter; the missed-
        // death heuristic must not bump it again.
        advance(2_000L)
        val nextBoot = newRecorder()
        nextBoot.recordHealthyBoot()
        assertEquals(
            "missed-death must not double-count when UEH already fired",
            1,
            nextBoot.peek().deathCount,
        )
    }

    // ---- Persistence + cooldown ------------------------------------------

    @Test
    fun `state persists across recorder instances on the same directory`() {
        val first = newRecorder()
        first.recordHealthyBoot()
        repeat(2) {
            advance(1_000L)
            first.recordAbnormalDeath()
        }
        val firstSnap = first.peek()
        assertEquals(2, firstSnap.deathCount)

        val second = newRecorder()
        val secondSnap = second.peek()
        assertEquals(firstSnap.deathCount, secondSnap.deathCount)
        assertEquals(firstSnap.lastDeathAt, secondSnap.lastDeathAt)
    }

    @Test
    fun `cooldownEndsAt is zero when no death has been recorded`() {
        val recorder = newRecorder()
        recorder.recordHealthyBoot()
        assertEquals(0L, recorder.cooldownEndsAt())
        assertFalse(recorder.shouldThrottleBinds())
    }

    @Test
    fun `shouldThrottleBinds returns false once rolling window expires`() {
        val recorder = newRecorder()
        recorder.recordHealthyBoot()
        repeat(MlHealthRecorder.DEATH_COUNT_THRESHOLD) {
            advance(1_000L)
            recorder.recordAbnormalDeath()
        }
        assertTrue(recorder.shouldThrottleBinds())

        // Advance past the rolling window without resetting the counter.
        advance(MlHealthRecorder.THROTTLE_WINDOW_MS + 100L)
        assertFalse(recorder.shouldThrottleBinds())
        // Counter is still high — it only resets on a fresh boot after
        // HEALTHY_UPTIME_DECAY_MS. That's intentional: a service that has
        // crashed three times deserves another boot's worth of attention
        // even if the last crash was a minute ago.
        assertEquals(MlHealthRecorder.DEATH_COUNT_THRESHOLD, recorder.peek().deathCount)
    }

    @Test
    fun `peek reads the latest persisted state`() {
        val recorder = newRecorder()
        val empty = recorder.peek()
        assertEquals(0L, empty.lastBootAt)
        assertEquals(0, empty.deathCount)

        recorder.recordHealthyBoot()
        val booted = recorder.peek()
        assertNotEquals(0L, booted.lastBootAt)
    }

    private fun recorderLastDeath(r: MlHealthRecorder): Long = r.peek().lastDeathAt
}
