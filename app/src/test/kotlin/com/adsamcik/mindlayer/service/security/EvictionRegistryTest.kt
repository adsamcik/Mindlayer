package com.adsamcik.mindlayer.service.security

import com.adsamcik.mindlayer.IClientCallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [EvictionRegistry] — the per-UID push-callback registry
 * that fires `IClientCallback.onSessionEvicted` from
 * [com.adsamcik.mindlayer.service.engine.SessionManager] involuntary
 * retirement paths.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EvictionRegistryTest {

    /** Counts callbacks per (sessionId, reasonCode). */
    private class CountingCallback : IClientCallback.Stub() {
        val invocations = AtomicInteger(0)
        var lastSessionId: String? = null
        var lastReasonCode: Int = -1
        override fun onSessionEvicted(sessionId: String?, reasonCode: Int) {
            invocations.incrementAndGet()
            lastSessionId = sessionId
            lastReasonCode = reasonCode
        }
    }

    @Test
    fun `register then notify dispatches once`() {
        val reg = EvictionRegistry()
        val cb = CountingCallback()
        assertTrue("first register should succeed", reg.register(uid = 10001, cb))

        reg.notifyEviction(uid = 10001, sessionId = "sess-A", reasonCode = 2002)

        assertEquals(1, cb.invocations.get())
        assertEquals("sess-A", cb.lastSessionId)
        assertEquals(2002, cb.lastReasonCode)
    }

    @Test
    fun `register is idempotent per binder`() {
        val reg = EvictionRegistry()
        val cb = CountingCallback()
        assertTrue(reg.register(uid = 10001, cb))
        assertFalse("duplicate should be rejected", reg.register(uid = 10001, cb))
        assertEquals(1, reg.size)

        reg.notifyEviction(10001, "sess-A", 2002)
        assertEquals("not duplicated despite double-register", 1, cb.invocations.get())
    }

    @Test
    fun `notifyEviction targets only the specified uid`() {
        val reg = EvictionRegistry()
        val cbA = CountingCallback()
        val cbB = CountingCallback()
        reg.register(uid = 10001, cbA)
        reg.register(uid = 10002, cbB)

        reg.notifyEviction(uid = 10001, sessionId = "sess-A", reasonCode = 2002)

        assertEquals("uid-A callback fired", 1, cbA.invocations.get())
        assertEquals("uid-B callback NOT fired (different uid)", 0, cbB.invocations.get())
    }

    @Test
    fun `unknown uid notify is silent no-op`() {
        val reg = EvictionRegistry()
        // Should not throw, should not blow up under load.
        reg.notifyEviction(uid = 99999, sessionId = "sess-A", reasonCode = 4002)
        assertEquals(0, reg.size)
    }

    @Test
    fun `unregister removes callback`() {
        val reg = EvictionRegistry()
        val cb = CountingCallback()
        reg.register(10001, cb)
        assertTrue("should report removal", reg.unregister(10001, cb))

        reg.notifyEviction(10001, "sess-A", 2002)
        assertEquals("no dispatches after unregister", 0, cb.invocations.get())
        assertEquals(0, reg.size)
    }

    @Test
    fun `unregister of unknown callback returns false`() {
        val reg = EvictionRegistry()
        val cb = CountingCallback()
        assertFalse(reg.unregister(uid = 10001, cb))
    }

    @Test
    fun `multiple distinct callbacks under same uid all receive notice`() {
        val reg = EvictionRegistry()
        val cb1 = CountingCallback()
        val cb2 = CountingCallback()
        val cb3 = CountingCallback()
        reg.register(10001, cb1)
        reg.register(10001, cb2)
        reg.register(10001, cb3)

        reg.notifyEviction(10001, "sess-A", 2003)

        assertEquals(1, cb1.invocations.get())
        assertEquals(1, cb2.invocations.get())
        assertEquals(1, cb3.invocations.get())
        assertEquals(2003, cb1.lastReasonCode)
    }

    @Test
    fun `per-uid cap blocks excess registrations`() {
        val reg = EvictionRegistry()
        val accepted = (0 until EvictionRegistry.MAX_CALLBACKS_PER_UID).count { idx ->
            reg.register(10001, CountingCallback())
        }
        assertEquals(EvictionRegistry.MAX_CALLBACKS_PER_UID, accepted)

        // One past the cap is rejected.
        val extra = CountingCallback()
        assertFalse(reg.register(10001, extra))

        reg.notifyEviction(10001, "sess-A", 2002)
        assertEquals("rejected callback never fires", 0, extra.invocations.get())
    }

    @Test
    fun `clear removes all entries across all uids`() {
        val reg = EvictionRegistry()
        val cbA = CountingCallback()
        val cbB = CountingCallback()
        reg.register(10001, cbA)
        reg.register(10002, cbB)
        assertEquals(2, reg.size)

        reg.clear()
        assertEquals(0, reg.size)

        reg.notifyEviction(10001, "sess-A", 2002)
        reg.notifyEviction(10002, "sess-B", 2002)
        assertEquals(0, cbA.invocations.get())
        assertEquals(0, cbB.invocations.get())
    }
}
