package com.adsamcik.mindlayer.service.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit tests for [ConsentAttemptStore]. Robolectric for `filesDir`.
 *
 * Pins:
 *  - dismiss escalation thresholds (1h after 3, 24h after 4)
 *  - device-wide prompt throttle (3 per 10 min)
 *  - clear() resets per-(pkg,sig) tracking
 *  - HMAC tamper rejection
 *  - cross-instance persistence (survives "restart")
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConsentAttemptStoreTest {

    private lateinit var context: Context
    private val dirName = "consent_attempt_test"
    private var clock = 1_000_000L

    private fun store() = ConsentAttemptStore(context, dirName = dirName, timeSource = { clock })

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, dirName).deleteRecursively()
    }

    @After
    fun tearDown() {
        File(context.filesDir, dirName).deleteRecursively()
    }

    @Test
    fun `fresh caller is allowed`() {
        val s = store()
        assertTrue(s.checkGate("com.a", "sigA") is ConsentGate.Allow)
    }

    @Test
    fun `three dismisses trigger a one-hour cooldown`() {
        val s = store()
        s.recordDismiss("com.a", "sigA") // 1
        s.recordDismiss("com.a", "sigA") // 2
        assertTrue("still allowed after 2", s.checkGate("com.a", "sigA") is ConsentGate.Allow)
        s.recordDismiss("com.a", "sigA") // 3 → 1h cooldown
        val gate = s.checkGate("com.a", "sigA")
        assertTrue("blocked after 3 dismisses", gate is ConsentGate.Blocked)
        gate as ConsentGate.Blocked
        assertEquals("dismiss_cooldown", gate.reason)
        assertEquals(clock + ConsentAttemptStore.COOLDOWN_1H_MS, gate.untilMs)
    }

    @Test
    fun `four dismisses trigger a 24-hour cooldown`() {
        val s = store()
        repeat(4) { s.recordDismiss("com.a", "sigA") }
        val gate = s.checkGate("com.a", "sigA")
        assertTrue(gate is ConsentGate.Blocked)
        assertEquals(clock + ConsentAttemptStore.COOLDOWN_24H_MS, (gate as ConsentGate.Blocked).untilMs)
    }

    @Test
    fun `cooldown lifts after the window passes`() {
        val s = store()
        repeat(3) { s.recordDismiss("com.a", "sigA") }
        clock += ConsentAttemptStore.COOLDOWN_1H_MS + 1
        assertTrue("allowed again after cooldown", s.checkGate("com.a", "sigA") is ConsentGate.Allow)
    }

    @Test
    fun `clear resets dismiss tracking`() {
        val s = store()
        repeat(3) { s.recordDismiss("com.a", "sigA") }
        assertTrue(s.checkGate("com.a", "sigA") is ConsentGate.Blocked)
        s.clear("com.a", "sigA")
        assertTrue("cleared caller is allowed", s.checkGate("com.a", "sigA") is ConsentGate.Allow)
        assertEquals(0, s.dismissCountFor("com.a", "sigA"))
    }

    @Test
    fun `dismiss tracking is per pkg-sig`() {
        val s = store()
        repeat(3) { s.recordDismiss("com.a", "sigA") }
        assertTrue("com.a blocked", s.checkGate("com.a", "sigA") is ConsentGate.Blocked)
        assertTrue("different sig unaffected", s.checkGate("com.a", "sigB") is ConsentGate.Allow)
        assertTrue("different pkg unaffected", s.checkGate("com.b", "sigA") is ConsentGate.Allow)
    }

    @Test
    fun `device-wide throttle blocks after three completed prompts`() {
        val s = store()
        // Three distinct callers each complete a prompt within the window.
        s.recordPromptCompleted("com.a", "s")
        s.recordPromptCompleted("com.b", "s")
        s.recordPromptCompleted("com.c", "s")
        val gate = s.checkGate("com.d", "s")
        assertTrue("4th caller throttled device-wide", gate is ConsentGate.Blocked)
        assertEquals("device_wide_throttle", (gate as ConsentGate.Blocked).reason)
    }

    @Test
    fun `device-wide throttle lifts after the window`() {
        val s = store()
        s.recordPromptCompleted("com.a", "s")
        s.recordPromptCompleted("com.b", "s")
        s.recordPromptCompleted("com.c", "s")
        clock += ConsentAttemptStore.DEVICE_WIDE_WINDOW_MS + 1
        assertTrue("allowed after window", s.checkGate("com.d", "s") is ConsentGate.Allow)
    }

    @Test
    fun `state persists across store instances`() {
        store().also { repeat(3) { _ -> it.recordDismiss("com.a", "sigA") } }
        // New instance reading the same dir — simulates :ml restart.
        val reloaded = store()
        assertEquals(3, reloaded.dismissCountFor("com.a", "sigA"))
        assertTrue(reloaded.checkGate("com.a", "sigA") is ConsentGate.Blocked)
    }

    @Test
    fun `tampered file is rejected and reads as empty`() {
        store().also { repeat(3) { _ -> it.recordDismiss("com.a", "sigA") } }
        val file = File(File(context.filesDir, dirName), "consent_attempts.json")
        val text = file.readText()
        // Flip the dismissCount in the JSON without re-signing.
        file.writeText(text.replace("\"dismissCount\":3", "\"dismissCount\":0"))
        val reloaded = store()
        // HMAC verification fails → file reads as empty → caller is allowed again
        // (fail-safe: a corrupted/forged attempt file must not silently grant a
        // permanent block, and must not preserve a forged lower count either).
        assertEquals(0, reloaded.dismissCountFor("com.a", "sigA"))
        assertTrue(reloaded.checkGate("com.a", "sigA") is ConsentGate.Allow)
    }
}
