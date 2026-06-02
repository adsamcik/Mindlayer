package com.adsamcik.mindlayer.service.engine

import android.os.SystemClock
import com.adsamcik.mindlayer.SessionConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [EvictionPolicy] — the pure-function priority calculator
 * and victim selectors extracted from [SessionManager].
 *
 * The priority *arithmetic* itself is already covered by
 * [SessionManagerTest] (those tests now call [EvictionPolicy.calculatePriority]
 * directly after the extraction). This file focuses on the selector methods
 * that are newly introduced as part of the decomposition.
 */
class EvictionPolicyTest {

    @Before
    fun setUp() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 1_000_000L
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ---- selectLowestPriorityVictim ----------------------------------------

    @Test
    fun `selectLowestPriorityVictim returns null on empty input`() {
        assertNull(EvictionPolicy.selectLowestPriorityVictim(emptyList()))
    }

    @Test
    fun `selectLowestPriorityVictim picks lowest priority`() {
        val high = buildHandle("high", clientPriorityHint = 100, lastAccessedElapsedMs = 999_000L) // recent → +300
        val low = buildHandle("low", clientPriorityHint = 0, lastAccessedElapsedMs = 0L)            // old → 0
        val winner = EvictionPolicy.selectLowestPriorityVictim(listOf(high, low))
        assertSame(low, winner)
    }

    @Test
    fun `selectLowestPriorityVictim excludes streaming by default`() {
        val streamingButLow = buildHandle("s", isStreaming = true, clientPriorityHint = 0)
        val idleHigher = buildHandle("i", clientPriorityHint = 50, lastAccessedElapsedMs = 0L)
        val winner = EvictionPolicy.selectLowestPriorityVictim(listOf(streamingButLow, idleHigher))
        // Streaming is excluded → idleHigher is the only candidate
        assertSame(idleHigher, winner)
    }

    @Test
    fun `selectLowestPriorityVictim returns null when only streaming candidates remain`() {
        val a = buildHandle("a", isStreaming = true)
        val b = buildHandle("b", isStreaming = true)
        assertNull(EvictionPolicy.selectLowestPriorityVictim(listOf(a, b)))
    }

    @Test
    fun `selectLowestPriorityVictim with excludePinned skips pinned even when lowest priority`() {
        val pinnedLow = buildHandle("p", isPinned = true, lastAccessedElapsedMs = 0L)
        val unpinnedHigher = buildHandle("u", clientPriorityHint = 80, lastAccessedElapsedMs = 0L)
        val winner = EvictionPolicy.selectLowestPriorityVictim(
            handles = listOf(pinnedLow, unpinnedHigher),
            excludePinned = true,
        )
        assertSame(unpinnedHigher, winner)
    }

    @Test
    fun `selectLowestPriorityVictim with ownerUid filter scopes the search`() {
        val ownerA1 = buildHandle("a1", ownerUid = 1000, clientPriorityHint = 80, lastAccessedElapsedMs = 0L)
        val ownerA2 = buildHandle("a2", ownerUid = 1000, clientPriorityHint = 10, lastAccessedElapsedMs = 0L)
        val ownerB1 = buildHandle("b1", ownerUid = 2000, clientPriorityHint = 0, lastAccessedElapsedMs = 0L)
        val winner = EvictionPolicy.selectLowestPriorityVictim(
            handles = listOf(ownerA1, ownerA2, ownerB1),
            ownerUid = 1000,
        )
        // ownerB1 has lowest absolute priority but is filtered out by uid
        assertSame(ownerA2, winner)
    }

    // ---- selectPressureEvictionVictims -------------------------------------

    @Test
    fun `selectPressureEvictionVictims returns empty when fewer than 2 candidates`() {
        assertTrue(EvictionPolicy.selectPressureEvictionVictims(emptyList()).isEmpty())
        assertTrue(EvictionPolicy.selectPressureEvictionVictims(listOf(buildHandle("a"))).isEmpty())
    }

    @Test
    fun `selectPressureEvictionVictims keeps single highest-priority survivor`() {
        val low = buildHandle("low", lastAccessedElapsedMs = 0L)
        val medium = buildHandle("medium", clientPriorityHint = 30, lastAccessedElapsedMs = 0L)
        val high = buildHandle("high", clientPriorityHint = 80, lastAccessedElapsedMs = 999_000L)
        val toEvict = EvictionPolicy.selectPressureEvictionVictims(listOf(low, medium, high))
        assertEquals(2, toEvict.size)
        assertTrue(toEvict.contains(low))
        assertTrue(toEvict.contains(medium))
        assertTrue("highest priority must survive", !toEvict.contains(high))
    }

    @Test
    fun `selectPressureEvictionVictims excludes streaming and pinned`() {
        val streaming = buildHandle("s", isStreaming = true)
        val pinned = buildHandle("p", isPinned = true)
        val a = buildHandle("a", clientPriorityHint = 10)
        val b = buildHandle("b", clientPriorityHint = 50)
        val toEvict = EvictionPolicy.selectPressureEvictionVictims(listOf(streaming, pinned, a, b))
        // Only a and b are candidates → b survives (higher priority), a evicted
        assertEquals(1, toEvict.size)
        assertSame(a, toEvict.first())
    }

    @Test
    fun `selectPressureEvictionVictims returns empty when only one non-streaming candidate`() {
        val streaming1 = buildHandle("s1", isStreaming = true)
        val streaming2 = buildHandle("s2", isStreaming = true)
        val idle = buildHandle("i")
        assertTrue(EvictionPolicy.selectPressureEvictionVictims(listOf(streaming1, streaming2, idle)).isEmpty())
    }

    // ---- helpers -----------------------------------------------------------

    private fun buildHandle(
        sessionId: String,
        isStreaming: Boolean = false,
        isPinned: Boolean = false,
        lastAccessedElapsedMs: Long = SystemClock.elapsedRealtime(),
        clientPriorityHint: Int = 0,
        ownerUid: Int? = null,
    ): SessionManager.SessionHandle {
        val handle = SessionManager.SessionHandle(
            sessionId = sessionId,
            conversation = mockk(relaxed = true),
            config = SessionConfig(),
            createdAtMs = 1_000L,
            effectiveMaxTokens = 4096,
            ownerUid = ownerUid,
        )
        handle.isStreaming = isStreaming
        handle.isPinned = isPinned
        handle.lastAccessedElapsedMs = lastAccessedElapsedMs
        handle.clientPriorityHint = clientPriorityHint
        return handle
    }
}
