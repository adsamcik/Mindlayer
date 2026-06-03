package com.adsamcik.mindlayer.service.engine

import android.os.SystemClock
import android.util.Log
import com.adsamcik.mindlayer.SessionConfig
import com.adsamcik.mindlayer.service.engine.util.FakeEngine
import com.google.ai.edge.litertlm.ConversationConfig
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Unit tests for [WarmConversationSlot]. Validates the building block
 * is correct even though it is not yet wired into
 * [SessionManager.createSession] — the wiring is deferred to a follow-up
 * PR that also rewrites the ~30 multi-session tests whose assumptions no
 * longer hold under the one-Conversation-per-Engine native invariant.
 *
 * The fake [com.adsamcik.mindlayer.service.engine.util.FakeEngine]
 * enforces the same FAILED_PRECONDITION the real engine throws, so tests
 * here exercise the slot against a faithful native-behaviour analogue.
 */
class WarmConversationSlotTest {

    @Before
    fun setUp() {
        mockkStatic(SystemClock::class)
        mockkStatic(Log::class)
        every { SystemClock.elapsedRealtime() } returns 0L
        every { Log.i(any(), any()) } returns 0
        every { Log.i(any(), any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `fresh slot has no warm session`() {
        val slot = WarmConversationSlot()
        assertNull(slot.currentWarmSessionId)
    }

    @Test
    fun `claim records the warm sessionId`() {
        val slot = WarmConversationSlot()
        slot.claim("session-a")
        assertEquals("session-a", slot.currentWarmSessionId)
    }

    @Test
    fun `release clears the warm sessionId`() {
        val slot = WarmConversationSlot()
        slot.claim("session-a")
        slot.release("session-a")
        assertNull(slot.currentWarmSessionId)
    }

    @Test
    fun `release for non-warm session is a no-op`() {
        val slot = WarmConversationSlot()
        slot.claim("session-a")
        slot.release("session-b")
        assertEquals("session-a", slot.currentWarmSessionId)
    }

    @Test
    fun `evictWarmFor with empty slot is a no-op`() {
        val slot = WarmConversationSlot()
        val sessions = ConcurrentHashMap<String, SessionManager.SessionHandle>()
        slot.evictWarmFor("session-new", sessions)
        assertNull(slot.currentWarmSessionId)
    }

    @Test
    fun `evictWarmFor with same-id slot is a no-op`() {
        val slot = WarmConversationSlot()
        slot.claim("session-a")
        val sessions = ConcurrentHashMap<String, SessionManager.SessionHandle>()
        slot.evictWarmFor("session-a", sessions)
        assertEquals("session-a", slot.currentWarmSessionId)
    }

    @Test
    fun `evictWarmFor clears stale slot when prior session not in map`() {
        // Slot tracks an ID that has since been removed from the sessions
        // map (orphaned by test teardown / crash recovery / unsynchronised
        // close path). Eviction should clear the marker without error.
        val slot = WarmConversationSlot()
        slot.claim("session-ghost")
        val sessions = ConcurrentHashMap<String, SessionManager.SessionHandle>()
        slot.evictWarmFor("session-new", sessions)
        assertNull(slot.currentWarmSessionId)
    }

    @Test
    fun `evictWarmFor closes prior conversation and removes from sessions map`() {
        val fake = FakeEngine.create()
        val firstConv = fake.engine.createConversation(ConversationConfig())
        val firstHandle = SessionManager.SessionHandle(
            sessionId = "session-a",
            conversation = firstConv,
            config = SessionConfig(sessionId = "session-a"),
            createdAtMs = 0L,
            effectiveMaxTokens = 4096,
        )
        val sessions = ConcurrentHashMap<String, SessionManager.SessionHandle>()
        sessions["session-a"] = firstHandle
        val slot = WarmConversationSlot()
        slot.claim("session-a")

        slot.evictWarmFor("session-b", sessions)

        assertEquals(1, fake.closeCount)
        assertEquals(0, fake.activeCount)
        assertNull("session-a must be removed from the map", sessions["session-a"])
        assertNull(slot.currentWarmSessionId)
        // The slot is now free for the new Conversation to be created
        // without triggering the native FAILED_PRECONDITION
        val secondConv = fake.engine.createConversation(ConversationConfig())
        assertEquals(2, fake.openCount)
        // Cleanup
        secondConv.close()
    }

    @Test
    fun `evictWarmFor throws EngineBusy when prior session is mid-stream`() {
        val fake = FakeEngine.create()
        val firstConv = fake.engine.createConversation(ConversationConfig())
        val firstHandle = SessionManager.SessionHandle(
            sessionId = "session-a",
            conversation = firstConv,
            config = SessionConfig(sessionId = "session-a"),
            createdAtMs = 0L,
            effectiveMaxTokens = 4096,
        )
        val sessions = ConcurrentHashMap<String, SessionManager.SessionHandle>()
        sessions["session-a"] = firstHandle
        val slot = WarmConversationSlot()
        slot.claim("session-a")

        // Simulate an in-flight stream by holding the per-handle mutex
        runBlocking {
            firstHandle.mutex.lock()
            try {
                val ex = assertThrows(EngineBusyException::class.java) {
                    slot.evictWarmFor("session-b", sessions)
                }
                assertEquals("session-a", ex.busySessionId)
                assertEquals("session-b", ex.requestedSessionId)
                assertEquals(WarmConversationSlot.ENGINE_BUSY_RETRY_MS, ex.retryAfterMs)
                // Prior session must still be in the map and the slot
                // unchanged — the eviction was atomic.
                assertEquals(firstHandle, sessions["session-a"])
                assertEquals("session-a", slot.currentWarmSessionId)
                assertEquals(0, fake.closeCount)
            } finally {
                firstHandle.mutex.unlock()
            }
        }
    }

    @Test
    fun `EngineBusyException message mentions both session ids and retry hint`() {
        val ex = EngineBusyException(
            busySessionId = "old",
            requestedSessionId = "new",
            retryAfterMs = 500L,
        )
        val msg = ex.message ?: ""
        assertTrue("message should mention busy id", msg.contains("old"))
        assertTrue("message should mention requested id", msg.contains("new"))
        assertTrue("message should mention retry-after", msg.contains("500"))
    }
}
