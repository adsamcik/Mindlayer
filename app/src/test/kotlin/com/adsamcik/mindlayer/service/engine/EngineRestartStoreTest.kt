package com.adsamcik.mindlayer.service.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Unit tests for [EngineRestartStore].
 *
 * Coverage:
 *  - Empty / missing file → peek/consume return null
 *  - record persists JSON with the documented shape
 *  - consume returns + clears in one step
 *  - record + record same target → attemptCount increments
 *  - record + record different target → attemptCount resets to 1
 *  - consume returns null + clears when attemptCount >= MAX_RESTART_ATTEMPTS
 *  - Malformed JSON → peek returns null
 *  - Schema-version mismatch → peek returns null
 *  - null targetBackend round-trips cleanly
 *  - clear() removes a recorded intent
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EngineRestartStoreTest {

    @get:Rule val testName = TestName()

    private lateinit var baseDir: File
    private val clockNowMs = AtomicLong(1_780_300_000_000L)
    private val clock: () -> Long = { clockNowMs.get() }

    @Before fun setUp() {
        baseDir = File(
            System.getProperty("java.io.tmpdir"),
            "engine-restart-store-test-${testName.methodName.sanitizedForPath()}",
        ).apply {
            deleteRecursively()
            mkdirs()
        }
    }

    private fun newStore() = EngineRestartStore(baseDir = baseDir, clock = clock)

    private fun String.sanitizedForPath(): String =
        replace(Regex("[^A-Za-z0-9_-]"), "_")

    @Test
    fun `peek returns null when no intent is recorded`() {
        val store = newStore()
        assertNull(store.peek())
    }

    @Test
    fun `consume returns null when no intent is recorded`() {
        val store = newStore()
        assertNull(store.consume())
    }

    @Test
    fun `record then peek returns the same intent`() {
        val store = newStore()
        val written = store.record(reason = "thermal_switch", targetBackend = "GPU", maxTokens = 4096)
        assertNotNull(written)
        val peeked = store.peek()
        assertNotNull(peeked)
        assertEquals("thermal_switch", peeked!!.reason)
        assertEquals("GPU", peeked.targetBackend)
        assertEquals(4096, peeked.maxTokens)
        assertEquals(1, peeked.attemptCount)
        assertEquals(EngineRestartStore.CURRENT_SCHEMA_VERSION, peeked.schemaVersion)
    }

    @Test
    fun `record persists with documented JSON shape`() {
        val store = newStore()
        store.record(reason = "memory_pressure", targetBackend = "CPU", maxTokens = 2048)
        val stateFile = File(baseDir, EngineRestartStore.STATE_FILE_NAME)
        assertTrue("State file should exist after record()", stateFile.exists())
        val raw = stateFile.readText()
        // schemaVersion MUST be the first key per the forward-compat contract.
        assertTrue("schemaVersion must be first key", raw.trimStart().startsWith("{\"schemaVersion\""))
        assertTrue("reason field present", raw.contains("\"reason\":\"memory_pressure\""))
        assertTrue("targetBackend field present", raw.contains("\"targetBackend\":\"CPU\""))
        assertTrue("maxTokens field present", raw.contains("\"maxTokens\":2048"))
        assertTrue("attemptCount field present", raw.contains("\"attemptCount\":1"))
    }

    @Test
    fun `consume returns intent and clears the file`() {
        val store = newStore()
        store.record(reason = "thermal_switch", targetBackend = "GPU", maxTokens = 4096)
        val consumed = store.consume()
        assertNotNull(consumed)
        assertEquals("thermal_switch", consumed!!.reason)
        assertEquals("GPU", consumed.targetBackend)
        // Subsequent peek/consume returns null (already cleared).
        assertNull(store.peek())
        assertNull(store.consume())
    }

    @Test
    fun `record twice with same target increments attemptCount`() {
        val store = newStore()
        store.record(reason = "thermal_switch", targetBackend = "GPU", maxTokens = 4096)
        val second = store.record(reason = "thermal_switch", targetBackend = "GPU", maxTokens = 4096)
        assertNotNull(second)
        assertEquals(2, second!!.attemptCount)
    }

    @Test
    fun `record with different target resets attemptCount to 1`() {
        val store = newStore()
        store.record(reason = "thermal_switch", targetBackend = "GPU", maxTokens = 4096)
        store.record(reason = "thermal_switch", targetBackend = "GPU", maxTokens = 4096)
        val switched = store.record(reason = "thermal_switch", targetBackend = "CPU", maxTokens = 4096)
        assertNotNull(switched)
        assertEquals(1, switched!!.attemptCount)
    }

    @Test
    fun `peek returns null when attemptCount reaches MAX_RESTART_ATTEMPTS`() {
        val store = newStore()
        // Three records bumps attemptCount to 3 (== MAX_RESTART_ATTEMPTS).
        store.record(reason = "thermal_switch", targetBackend = "GPU", maxTokens = 4096)
        store.record(reason = "thermal_switch", targetBackend = "GPU", maxTokens = 4096)
        store.record(reason = "thermal_switch", targetBackend = "GPU", maxTokens = 4096)
        assertNull("peek must return null at the attempt cap", store.peek())
    }

    @Test
    fun `consume returns null and clears file when attemptCount reaches cap`() {
        val store = newStore()
        store.record(reason = "thermal_switch", targetBackend = "GPU", maxTokens = 4096)
        store.record(reason = "thermal_switch", targetBackend = "GPU", maxTokens = 4096)
        store.record(reason = "thermal_switch", targetBackend = "GPU", maxTokens = 4096)
        assertNull("consume must return null at the cap", store.consume())
        // Loop guard: the file should now be cleared so a fresh record starts at attempt=1.
        val nextAttempt = store.record(reason = "thermal_switch", targetBackend = "GPU", maxTokens = 4096)
        assertEquals(
            "consume() at cap must clear so the next record starts fresh",
            1,
            nextAttempt!!.attemptCount,
        )
    }

    @Test
    fun `beginAttempt bumps and persists the count without clearing, then trips the cap (R-7)`() {
        // R-7: simulate the crash loop. record() seeds intent{count=1}.
        // Each beginAttempt() models a boot that bumps the count BEFORE
        // initialize() and would clear ONLY on success. If init keeps
        // SIGSEGV-ing (no clear), the persisted count must accumulate and
        // eventually trip the cap — unlike consume() which deleted the
        // intent up front and reset the loop guard to 1 on every crash.
        val store = newStore()
        store.record(reason = "thermal_switch", targetBackend = "GPU", maxTokens = 4096)

        val a1 = store.beginAttempt()
        assertNotNull(a1)
        assertEquals(2, a1!!.attemptCount)
        // Not cleared: a re-read still sees the bumped intent (crash before clear).
        assertEquals(2, store.peek()?.attemptCount)

        val a2 = store.beginAttempt()
        assertNotNull(a2)
        assertEquals(3, a2!!.attemptCount)

        // Cap reached: the next attempt gives up (default chain) and clears.
        assertNull("crash-loop guard must trip at the cap", store.beginAttempt())
        assertNull("file cleared after giving up", store.peek())
    }

    @Test
    fun `beginAttempt clears on a successful attempt path (R-7)`() {
        // The production success path: beginAttempt() bumps, then the
        // coordinator calls clear() after a good init. Confirm clear()
        // removes the intent so a later unrelated failure starts fresh.
        val store = newStore()
        store.record(reason = "thermal_switch", targetBackend = "GPU", maxTokens = 4096)
        assertEquals(2, store.beginAttempt()!!.attemptCount)
        store.clear()
        assertNull(store.peek())
        // A fresh record starts the count back at 1.
        assertEquals(1, store.record(reason = "thermal_switch", targetBackend = "GPU", maxTokens = 4096)!!.attemptCount)
    }

    @Test
    fun `beginAttempt returns null when no intent is recorded`() {
        assertNull(newStore().beginAttempt())
    }

    @Test
    fun `malformed JSON yields null peek`() {
        val store = newStore()
        File(baseDir, EngineRestartStore.STATE_FILE_NAME).writeText("{not valid json")
        assertNull(store.peek())
    }

    @Test
    fun `schema version mismatch yields null peek`() {
        val store = newStore()
        File(baseDir, EngineRestartStore.STATE_FILE_NAME).writeText(
            """{"schemaVersion":99,"reason":"x","targetBackend":"GPU","maxTokens":4096,"recordedAtMs":1,"attemptCount":1}""",
        )
        assertNull("forward-compat: unknown schema version must yield null", store.peek())
    }

    @Test
    fun `null targetBackend round-trips`() {
        val store = newStore()
        store.record(reason = "fallback_default_chain", targetBackend = null, maxTokens = 4096)
        val peeked = store.peek()
        assertNotNull(peeked)
        assertNull("targetBackend null → null after round-trip", peeked!!.targetBackend)
    }

    @Test
    fun `clear removes a recorded intent`() {
        val store = newStore()
        store.record(reason = "thermal_switch", targetBackend = "GPU", maxTokens = 4096)
        store.clear()
        assertNull(store.peek())
    }

    @Test
    fun `cross-instance read sees freshly written intent`() {
        // Simulate the cross-process scenario: writer process records,
        // separate reader process (different EngineRestartStore instance,
        // same baseDir) peeks — this is the dashboard-reading-:ml-state path.
        val writer = newStore()
        writer.record(reason = "memory_pressure", targetBackend = "CPU", maxTokens = 1024)
        val reader = newStore()
        val peeked = reader.peek()
        assertNotNull(peeked)
        assertEquals("memory_pressure", peeked!!.reason)
        assertEquals("CPU", peeked.targetBackend)
        assertEquals(1024, peeked.maxTokens)
    }
}
