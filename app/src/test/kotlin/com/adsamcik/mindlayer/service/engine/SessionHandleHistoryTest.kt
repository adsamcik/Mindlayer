package com.adsamcik.mindlayer.service.engine

import android.os.SystemClock
import com.adsamcik.mindlayer.HistoryTurn
import com.adsamcik.mindlayer.SessionConfig
import com.adsamcik.mindlayer.shared.Role
import com.google.ai.edge.litertlm.ConversationConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [SessionManager.SessionHandle.appendTurn] and its token-budget
 * trimming policy.
 */
class SessionHandleHistoryTest {

    @Before
    fun setUp() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 0L
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `recordedTurns is seeded from config initialHistory`() {
        val seed = listOf(
            HistoryTurn(Role.USER, "hi"),
            HistoryTurn(Role.MODEL, "hello"),
        )
        val handle = buildHandle(initialHistory = seed)
        val snapshot = handle.snapshotRecordedTurns()
        assertEquals(2, snapshot.size)
        assertEquals(seed, snapshot)
    }

    @Test
    fun `empty initialHistory yields empty buffer`() {
        val handle = buildHandle(initialHistory = null)
        assertEquals(0, handle.snapshotRecordedTurns().size)
    }

    @Test
    fun `appendTurn extends the buffer`() {
        val handle = buildHandle()
        handle.appendTurn(Role.USER, "first")
        handle.appendTurn(Role.MODEL, "first reply")
        handle.appendTurn(Role.USER, "second")
        val snapshot = handle.snapshotRecordedTurns()
        assertEquals(3, snapshot.size)
        assertEquals(Role.USER, snapshot[0].role)
        assertEquals("first", snapshot[0].text)
    }

    @Test
    fun `appendTurn drops oldest turns when total exceeds budget`() {
        // effectiveMaxTokens=2048 → budget = 1024 tokens = 4096 chars.
        // Each turn here is ~1000 chars (~250 tokens). After 5 turns we're
        // at ~1250 tokens, over the 1024 budget. The oldest turn should
        // get evicted.
        val handle = buildHandle(effectiveMaxTokens = 2048)
        val payload = "x".repeat(1000)
        for (i in 1..5) {
            handle.appendTurn(if (i % 2 == 0) Role.MODEL else Role.USER, "$i: $payload")
        }
        val snapshot = handle.snapshotRecordedTurns()
        assertTrue(
            "Should have dropped at least one turn (got ${snapshot.size}/5)",
            snapshot.size < 5,
        )
        // The most-recent turn must still be present
        assertTrue("most-recent text retained", snapshot.last().text.startsWith("5:"))
    }

    @Test
    fun `budget enforcement honours MIN_HISTORY_BUDGET_TOKENS floor`() {
        // effectiveMaxTokens=128 → 0.5x = 64 tokens (≈ 256 chars). The
        // MIN_HISTORY_BUDGET_TOKENS floor of 256 kicks in instead.
        val handle = buildHandle(effectiveMaxTokens = 128)
        val padding = "y".repeat(200)
        handle.appendTurn(Role.USER, padding)
        handle.appendTurn(Role.MODEL, padding)
        // Two turns ≈ 100 tokens — well under the 256-token floor
        assertEquals(2, handle.snapshotRecordedTurns().size)
    }

    @Test
    fun `appendTurn keeps at least one turn even if it exceeds budget`() {
        // A single turn larger than the entire budget shouldn't be dropped
        // — that would leave the buffer empty and lose the most recent
        // user content. The loop guard `recordedTurns.size > 1` prevents
        // that.
        val handle = buildHandle(effectiveMaxTokens = 2048)
        val giant = "z".repeat(20_000) // ~5000 tokens, way over budget
        handle.appendTurn(Role.USER, giant)
        val snapshot = handle.snapshotRecordedTurns()
        assertEquals(1, snapshot.size)
        assertEquals(giant.length, snapshot.first().text.length)
    }

    @Test
    fun `snapshotRecordedTurns returns immutable copy`() {
        val handle = buildHandle()
        handle.appendTurn(Role.USER, "a")
        val s1 = handle.snapshotRecordedTurns()
        handle.appendTurn(Role.MODEL, "b")
        val s2 = handle.snapshotRecordedTurns()
        // s1 should not have grown
        assertEquals(1, s1.size)
        assertEquals(2, s2.size)
    }

    // ---- helpers -----------------------------------------------------------

    private fun buildHandle(
        effectiveMaxTokens: Int = 4096,
        initialHistory: List<HistoryTurn>? = null,
    ): SessionManager.SessionHandle {
        val config = SessionConfig(
            sessionId = "h",
            initialHistory = initialHistory,
        )
        return SessionManager.SessionHandle(
            sessionId = "h",
            conversation = mockk(relaxed = true),
            config = config,
            createdAtMs = 0L,
            effectiveMaxTokens = effectiveMaxTokens,
            baseConversationConfig = ConversationConfig(),
        )
    }
}
