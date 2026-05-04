package com.adsamcik.mindlayer.service.engine

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression coverage for [MemoryBudget.onTrimMemory] across every
 * `ComponentCallbacks2.TRIM_MEMORY_*` level Android can deliver.
 *
 * Why this exists: the previous implementation handled only two named
 * levels (`RUNNING_CRITICAL`, `RUNNING_LOW`) and let everything else
 * fall through a `>=` cascade. The cascade had two real defects:
 *
 *  - `TRIM_MEMORY_RUNNING_MODERATE` (5) was below both thresholds and
 *    received no pre-emptive escalation at all.
 *  - `UI_HIDDEN` (20), `BACKGROUND` (40), `MODERATE` (60), and
 *    `COMPLETE` (80) all collapsed to EMERGENCY because their values
 *    are >= `RUNNING_CRITICAL` (15). UI_HIDDEN and BACKGROUND are mild
 *    informational hints; treating them as full EMERGENCY caused
 *    unnecessarily aggressive token-cap clamping.
 *
 * These tests pin the explicit per-level mapping so a future refactor
 * cannot silently regress to the old binary cascade.
 *
 * Implementation note: tests run real `MemoryBudget` (no mocks) with a
 * NORMAL-baseline `availMem` so escalations from the system hint are
 * the only source of pressure changes — that isolates the mapping
 * under test from the underlying `availMem`-driven `computePressure`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@Suppress("DEPRECATION") // ComponentCallbacks2.TRIM_MEMORY_RUNNING_* are deprecated for apps, still delivered to services
class MemoryBudgetTrimMemoryTest {

    private lateinit var context: Context
    private lateinit var am: ActivityManager
    private lateinit var memoryBudget: MemoryBudget

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        mockkObject(MindlayerLog)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { MindlayerLog.d(any(), any(), any(), any()) } returns Unit
        every { MindlayerLog.i(any(), any(), any(), any()) } returns Unit
        every { MindlayerLog.w(any(), any(), any(), any(), any()) } returns Unit
        every { MindlayerLog.e(any(), any(), any(), any(), any()) } returns Unit

        am = mockk(relaxed = true)
        every { am.getMemoryInfo(any()) } answers {
            val info = firstArg<ActivityManager.MemoryInfo>()
            info.availMem = 4_000L * 1024 * 1024 // 4 GB free → computePressure returns NORMAL
            info.totalMem = 16L * 1024 * 1024 * 1024 // 16 GB → top device tier
            info.lowMemory = false
        }
        context = mockk(relaxed = true)
        every { context.getSystemService(Context.ACTIVITY_SERVICE) } returns am

        memoryBudget = MemoryBudget(context, TestScope())
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ---- Foreground pressure series (RUNNING_*) ----------------------------

    @Test
    fun `RUNNING_MODERATE escalates pressure to at least WARNING`() {
        memoryBudget.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
        // Previous code never named this level — it silently fell through
        // both `>=` thresholds and produced NO escalation. Pin the new
        // pre-emptive WARNING handling.
        assertEquals(MemoryPressure.WARNING, memoryBudget.pressure.value)
    }

    @Test
    fun `RUNNING_LOW escalates pressure to at least CRITICAL`() {
        memoryBudget.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertEquals(MemoryPressure.CRITICAL, memoryBudget.pressure.value)
    }

    @Test
    fun `RUNNING_CRITICAL escalates pressure to EMERGENCY`() {
        memoryBudget.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertEquals(MemoryPressure.EMERGENCY, memoryBudget.pressure.value)
    }

    // ---- Background LRU series (downgraded from old EMERGENCY cascade) -----

    @Test
    fun `UI_HIDDEN escalates only to WARNING (not EMERGENCY)`() {
        memoryBudget.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        // Critical regression guard: the old `>=` cascade made this
        // EMERGENCY (because UI_HIDDEN=20 >= RUNNING_CRITICAL=15) which
        // forced full token-cap clamping for what is really just "your
        // UI is no longer visible". For the :ml service (no UI) this
        // signal is purely informational.
        assertEquals(MemoryPressure.WARNING, memoryBudget.pressure.value)
    }

    @Test
    fun `BACKGROUND escalates only to WARNING (not EMERGENCY)`() {
        memoryBudget.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        // Same regression guard as above: BACKGROUND just means "you
        // moved to the LRU list", not "system is out of RAM".
        assertEquals(MemoryPressure.WARNING, memoryBudget.pressure.value)
    }

    @Test
    fun `MODERATE escalates to CRITICAL`() {
        memoryBudget.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_MODERATE)
        // Was EMERGENCY in the old cascade; downgraded to CRITICAL — the
        // system needs help but we are not yet about to be killed.
        assertEquals(MemoryPressure.CRITICAL, memoryBudget.pressure.value)
    }

    @Test
    fun `COMPLETE escalates to EMERGENCY`() {
        memoryBudget.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        // Unchanged from the old cascade behaviour — the system is about
        // to kill us, this is genuinely the EMERGENCY tier.
        assertEquals(MemoryPressure.EMERGENCY, memoryBudget.pressure.value)
    }

    // ---- Defensive cases ---------------------------------------------------

    @Test
    fun `unknown trim level does not escalate or crash`() {
        // Future Android could add a new TRIM_MEMORY_* constant; we must
        // not auto-escalate (could over-react to a cosmetic hint), and
        // MUST NOT throw. evaluate() still runs; with NORMAL availMem
        // the computed pressure stays NORMAL.
        memoryBudget.onTrimMemory(level = 9_999)
        assertEquals(MemoryPressure.NORMAL, memoryBudget.pressure.value)
    }

    @Test
    fun `onTrimMemory does not downgrade an already-EMERGENCY pressure with a milder hint`() {
        // First, drive pressure into EMERGENCY via an explicit hint.
        memoryBudget.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertEquals(MemoryPressure.EMERGENCY, memoryBudget.pressure.value)

        // A subsequent gentler hint must not downgrade — `escalateToAtLeast`
        // is upward-only by design. The next poll cycle handles natural
        // de-escalation as memory frees.
        memoryBudget.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertEquals(MemoryPressure.EMERGENCY, memoryBudget.pressure.value)

        memoryBudget.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        assertEquals(MemoryPressure.EMERGENCY, memoryBudget.pressure.value)
    }

    @Test
    fun `evaluate ordering - escalation is not silently overwritten by the immediate snapshot`() {
        // With availMem=4 GB the snapshot evaluation alone returns NORMAL,
        // so the previous (escalate-then-evaluate) implementation would
        // immediately downgrade a BACKGROUND hint back to NORMAL. The fix
        // routes snapshot refresh through `currentSnapshot()` which does
        // NOT touch `_pressure`, leaving `escalateToAtLeast(WARNING)` as
        // the sole writer for the duration of this call.
        memoryBudget.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        assertTrue(
            "Pressure must be at least WARNING after a BACKGROUND hint, " +
                "even when availMem indicates NORMAL — the system hint wins. " +
                "Got: ${memoryBudget.pressure.value}",
            memoryBudget.pressure.value.ordinal >= MemoryPressure.WARNING.ordinal,
        )
    }

    @Test
    fun `onTrimMemory refreshes snapshot for the dashboard`() {
        // The dashboard reads `snapshot.value` to render current memory state;
        // onTrimMemory must update it even if no escalation target applies.
        // (The pre-fix code ran evaluate() unconditionally and refreshed the
        // snapshot as a side effect; the new code uses currentSnapshot() to
        // refresh without touching pressure.)
        assertNull("Snapshot starts null before any sample", memoryBudget.snapshot.value)

        memoryBudget.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)

        val snap = memoryBudget.snapshot.value
        assertNotNull("Snapshot must be refreshed after onTrimMemory", snap)
        assertEquals(4_000L, snap!!.availableMb)
    }
}
