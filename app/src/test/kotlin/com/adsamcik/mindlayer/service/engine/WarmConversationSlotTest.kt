package com.adsamcik.mindlayer.service.engine

import android.os.SystemClock
import android.util.Log
import com.adsamcik.mindlayer.SessionConfig
import com.adsamcik.mindlayer.service.engine.util.FakeEngine
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Unit tests for [WarmConversationSlot] using the lease-based API.
 *
 * Covers the rubber-duck minimum-viable lifecycle scenarios:
 *
 *  1. Fresh slot has no warm session.
 *  2. First lease materialises the Conversation via the factory.
 *  3. Re-entering the same session reuses its Conversation (no factory call).
 *  4. Cross-session swap closes prior + creates fresh under the new lease.
 *  5. Swap blocked when prior session is mid-stream → EngineBusyException.
 *  6. Block exception releases the slot mutex (subsequent lease succeeds).
 *  7. `tryEvictIdle` no-ops on empty slot.
 *  8. `tryEvictIdle` no-ops when current warm ≠ requested id.
 *  9. `tryEvictIdle` closes and clears when current warm matches and idle.
 * 10. `tryEvictIdle` returns false when slot mutex is busy (lease in flight).
 * 11. `tryEvictIdle` clears stale marker when handle removed from sessions.
 * 12. `releaseMarker` matching warm id clears it.
 * 13. `releaseMarker` mismatching is a no-op.
 * 14. `shutdown` is best-effort and doesn't throw on empty slot.
 * 15. `EngineBusyException` message includes both ids + retry hint.
 *
 * The fake [FakeEngine] enforces the same FAILED_PRECONDITION the real
 * engine throws, so tests exercise the slot against a faithful
 * native-behaviour analogue.
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

    private fun newHandle(
        sessionId: String,
        initialConversation: Conversation? = null,
    ): SessionManager.SessionHandle = SessionManager.SessionHandle(
        sessionId = sessionId,
        conversation = initialConversation,
        config = SessionConfig(sessionId = sessionId),
        createdAtMs = 0L,
        effectiveMaxTokens = 4096,
        baseConversationConfig = ConversationConfig(),
    )

    @Test
    fun `fresh slot has no warm session`() {
        val slot = WarmConversationSlot()
        assertNull(slot.currentWarmSessionId)
    }

    @Test
    fun `lease materialises conversation when handle is cold`() = runBlocking {
        val fake = FakeEngine.create()
        val handle = newHandle("session-a")
        val slot = WarmConversationSlot()
        val sessions = ConcurrentHashMap<String, SessionManager.SessionHandle>()
        sessions["session-a"] = handle

        var factoryCalls = 0
        val seenConv = slot.lease(
            handle = handle,
            sessions = sessions,
            createConversation = {
                factoryCalls++
                fake.engine.createConversation(ConversationConfig())
            },
        ) { conv -> conv }

        assertEquals("factory should run once for cold handle", 1, factoryCalls)
        assertEquals(1, fake.openCount)
        assertEquals(1, fake.activeCount)
        assertNotNull("handle should now own the conversation", handle.conversation)
        assertSame(seenConv, handle.conversation)
        assertEquals("session-a", slot.currentWarmSessionId)
    }

    @Test
    fun `re-entering same warm session reuses conversation without factory call`() = runBlocking {
        val fake = FakeEngine.create()
        val handle = newHandle("session-a")
        val slot = WarmConversationSlot()
        val sessions = ConcurrentHashMap<String, SessionManager.SessionHandle>()
        sessions["session-a"] = handle

        var factoryCalls = 0
        val factory: suspend () -> Conversation = {
            factoryCalls++
            fake.engine.createConversation(ConversationConfig())
        }

        val first = slot.lease(handle, sessions, factory) { conv -> conv }
        val second = slot.lease(handle, sessions, factory) { conv -> conv }

        assertEquals("factory should not be re-run for warm session", 1, factoryCalls)
        assertSame("same Conversation across leases", first, second)
        assertEquals(1, fake.openCount)
        assertEquals(0, fake.closeCount)
    }

    @Test
    fun `cross-session swap closes prior and creates fresh under new lease`() = runBlocking {
        val fake = FakeEngine.create()
        val handleA = newHandle("session-a")
        val handleB = newHandle("session-b")
        val slot = WarmConversationSlot()
        val sessions = ConcurrentHashMap<String, SessionManager.SessionHandle>()
        sessions["session-a"] = handleA
        sessions["session-b"] = handleB

        slot.lease(handleA, sessions, { fake.engine.createConversation(ConversationConfig()) }) { /* enter A */ }
        assertEquals("A warm after first lease", "session-a", slot.currentWarmSessionId)
        assertNotNull(handleA.conversation)

        slot.lease(handleB, sessions, { fake.engine.createConversation(ConversationConfig()) }) { /* enter B */ }

        assertEquals("B warm after swap", "session-b", slot.currentWarmSessionId)
        assertNull("A's conversation should be released", handleA.conversation)
        assertNotNull("B should own a conversation", handleB.conversation)
        assertEquals("prior Conversation closed exactly once", 1, fake.closeCount)
        assertEquals("two Conversations created total", 2, fake.openCount)
        assertEquals("only B's Conversation is active now", 1, fake.activeCount)
    }

    @Test
    fun `swap blocked when prior session is mid-stream throws EngineBusy`() = runBlocking {
        val fake = FakeEngine.create()
        val handleA = newHandle("session-a")
        val handleB = newHandle("session-b")
        val slot = WarmConversationSlot()
        val sessions = ConcurrentHashMap<String, SessionManager.SessionHandle>()
        sessions["session-a"] = handleA
        sessions["session-b"] = handleB

        slot.lease(handleA, sessions, { fake.engine.createConversation(ConversationConfig()) }) { /* warm A */ }

        // Simulate an in-flight stream on A by holding its per-handle mutex
        handleA.mutex.lock()
        try {
            val ex = try {
                slot.lease(
                    handle = handleB,
                    sessions = sessions,
                    createConversation = { fake.engine.createConversation(ConversationConfig()) },
                ) { fail("block should not run when prior is busy"); error("unreachable") }
                null
            } catch (e: EngineBusyException) { e }
            assertNotNull("EngineBusyException expected", ex)
            assertEquals("session-a", ex!!.busySessionId)
            assertEquals("session-b", ex.requestedSessionId)
            assertEquals(WarmConversationSlot.ENGINE_BUSY_RETRY_MS, ex.retryAfterMs)

            // Atomic eviction guarantee: nothing changed.
            assertEquals("session-a", slot.currentWarmSessionId)
            assertNotNull(handleA.conversation)
            assertNull(handleB.conversation)
            assertEquals(0, fake.closeCount)
        } finally {
            handleA.mutex.unlock()
        }
    }

    @Test
    fun `block exception releases slot mutex so subsequent lease succeeds`() = runBlocking {
        val fake = FakeEngine.create()
        val handle = newHandle("session-a")
        val slot = WarmConversationSlot()
        val sessions = ConcurrentHashMap<String, SessionManager.SessionHandle>()
        sessions["session-a"] = handle

        val thrown = try {
            slot.lease(handle, sessions, { fake.engine.createConversation(ConversationConfig()) }) {
                throw RuntimeException("boom")
            }
            null
        } catch (e: RuntimeException) { e }
        assertEquals("boom", thrown?.message)

        // Slot mutex should have unwound; the warm marker stays (since A's
        // Conversation was successfully created and not explicitly closed).
        assertEquals("session-a", slot.currentWarmSessionId)

        // A follow-up lease on the same handle must NOT deadlock.
        var entered = false
        slot.lease(
            handle = handle,
            sessions = sessions,
            createConversation = {
                fail("factory should not re-run for warm session")
                error("unreachable")
            },
        ) { entered = true }
        assertTrue("follow-up lease entered", entered)
    }

    @Test
    fun `tryEvictIdle on empty slot is a no-op`() {
        val slot = WarmConversationSlot()
        val sessions = ConcurrentHashMap<String, SessionManager.SessionHandle>()
        assertTrue("empty slot reports success", slot.tryEvictIdle("anything", sessions))
        assertNull(slot.currentWarmSessionId)
    }

    @Test
    fun `tryEvictIdle when current warm differs is a no-op success`() = runBlocking {
        val fake = FakeEngine.create()
        val handle = newHandle("session-a")
        val slot = WarmConversationSlot()
        val sessions = ConcurrentHashMap<String, SessionManager.SessionHandle>()
        sessions["session-a"] = handle
        slot.lease(handle, sessions, { fake.engine.createConversation(ConversationConfig()) }) { }

        assertTrue("mismatching id returns success without action", slot.tryEvictIdle("session-other", sessions))
        assertEquals("session-a", slot.currentWarmSessionId)
        assertNotNull(handle.conversation)
        assertEquals(0, fake.closeCount)
    }

    @Test
    fun `tryEvictIdle closes warm conversation when idle`() = runBlocking {
        val fake = FakeEngine.create()
        val handle = newHandle("session-a")
        val slot = WarmConversationSlot()
        val sessions = ConcurrentHashMap<String, SessionManager.SessionHandle>()
        sessions["session-a"] = handle
        slot.lease(handle, sessions, { fake.engine.createConversation(ConversationConfig()) }) { }

        assertTrue(slot.tryEvictIdle("session-a", sessions))

        assertNull("warm marker cleared", slot.currentWarmSessionId)
        assertNull("handle's conversation cleared", handle.conversation)
        assertEquals(1, fake.closeCount)
        assertEquals(0, fake.activeCount)
    }

    @Test
    fun `tryEvictIdle returns false when per-handle mutex is held`() = runBlocking {
        val fake = FakeEngine.create()
        val handle = newHandle("session-a")
        val slot = WarmConversationSlot()
        val sessions = ConcurrentHashMap<String, SessionManager.SessionHandle>()
        sessions["session-a"] = handle
        slot.lease(handle, sessions, { fake.engine.createConversation(ConversationConfig()) }) { }

        handle.mutex.lock()
        try {
            assertFalse(
                "tryEvictIdle should fail when per-handle mutex is locked",
                slot.tryEvictIdle("session-a", sessions),
            )
            // State unchanged
            assertEquals("session-a", slot.currentWarmSessionId)
            assertNotNull(handle.conversation)
            assertEquals(0, fake.closeCount)
        } finally {
            handle.mutex.unlock()
        }
    }

    @Test
    fun `tryEvictIdle clears stale marker when handle removed from sessions`() = runBlocking {
        val fake = FakeEngine.create()
        val handle = newHandle("session-ghost")
        val slot = WarmConversationSlot()
        val sessions = ConcurrentHashMap<String, SessionManager.SessionHandle>()
        sessions["session-ghost"] = handle
        slot.lease(handle, sessions, { fake.engine.createConversation(ConversationConfig()) }) { }
        sessions.remove("session-ghost")

        assertTrue(slot.tryEvictIdle("session-ghost", sessions))
        assertNull(slot.currentWarmSessionId)
    }

    @Test
    fun `releaseMarker clears matching warm id`() = runBlocking {
        val fake = FakeEngine.create()
        val handle = newHandle("session-a")
        val slot = WarmConversationSlot()
        val sessions = ConcurrentHashMap<String, SessionManager.SessionHandle>()
        sessions["session-a"] = handle
        slot.lease(handle, sessions, { fake.engine.createConversation(ConversationConfig()) }) { }
        assertEquals("session-a", slot.currentWarmSessionId)

        slot.releaseMarker("session-a")
        assertNull(slot.currentWarmSessionId)
        // Conversation NOT closed — releaseMarker only clears the marker
        assertEquals(0, fake.closeCount)
    }

    @Test
    fun `releaseMarker for non-warm id is a no-op`() = runBlocking {
        val fake = FakeEngine.create()
        val handle = newHandle("session-a")
        val slot = WarmConversationSlot()
        val sessions = ConcurrentHashMap<String, SessionManager.SessionHandle>()
        sessions["session-a"] = handle
        slot.lease(handle, sessions, { fake.engine.createConversation(ConversationConfig()) }) { }

        slot.releaseMarker("session-other")
        assertEquals("session-a", slot.currentWarmSessionId)
    }

    @Test
    fun `shutdown on empty slot does not throw`() {
        val slot = WarmConversationSlot()
        val sessions = ConcurrentHashMap<String, SessionManager.SessionHandle>()
        slot.shutdown(sessions)
        assertNull(slot.currentWarmSessionId)
    }

    @Test
    fun `shutdown closes warm conversation when idle`() = runBlocking {
        val fake = FakeEngine.create()
        val handle = newHandle("session-a")
        val slot = WarmConversationSlot()
        val sessions = ConcurrentHashMap<String, SessionManager.SessionHandle>()
        sessions["session-a"] = handle
        slot.lease(handle, sessions, { fake.engine.createConversation(ConversationConfig()) }) { }

        slot.shutdown(sessions)
        assertEquals(1, fake.closeCount)
        assertNull(slot.currentWarmSessionId)
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
