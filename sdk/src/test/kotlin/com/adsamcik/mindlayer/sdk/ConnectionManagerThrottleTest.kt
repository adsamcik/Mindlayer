package com.adsamcik.mindlayer.sdk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.adsamcik.mindlayer.IMindlayerService
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F-074: SDK reconnect-loop integration tests for the crash-loop watchdog.
 *
 * When the service rejects [IMindlayerService.registerClient] with a
 * `SERVICE_THROTTLED`-coded SecurityException, the SDK must:
 *  - NOT transition to [ConnectionState.REJECTED_NOT_APPROVED] (that's
 *    a terminal state reserved for genuine allowlist denials).
 *  - Transition to [ConnectionState.RECOVERING] so callers know a retry
 *    is pending.
 *  - Schedule a deferred rebind instead of hot-spinning so the OOM-killer
 *    that caused the crash loop in the first place is not re-provoked.
 *
 * A plain (un-prefixed) SecurityException is still treated as the
 * legacy "not approved" rejection — that path is unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConnectionManagerThrottleTest {

    private lateinit var mgr: ConnectionManager
    private lateinit var mockContext: Context
    private lateinit var mockAppContext: Context

    private val connSlot = slot<ServiceConnection>()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        mockAppContext = mockk(relaxed = true) {
            every {
                bindService(any<Intent>(), capture(connSlot), any<Int>())
            } returns true
            every { unbindService(any()) } just Runs
        }

        mockContext = mockk(relaxed = true) {
            every { applicationContext } returns mockAppContext
        }

        mgr = ConnectionManager()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun stubComponent() = ComponentName("com.adsamcik.mindlayer.service", "MlService")

    /**
     * Build a raw [IBinder] whose `queryLocalInterface` resolves to the
     * supplied [stub] — that lets us hand a fully-stubbed
     * [IMindlayerService] back from `Stub.asInterface` and watch the
     * `registerClient` invocation throw a coded SecurityException.
     */
    private fun stubBinder(stub: IMindlayerService): IBinder = mockk(relaxed = true) {
        every { linkToDeath(any(), any()) } just Runs
        every { unlinkToDeath(any(), any()) } returns true
        every { queryLocalInterface(IMindlayerService.DESCRIPTOR) } returns stub
    }

    @Test
    fun `SERVICE_THROTTLED on registerClient transitions to RECOVERING not REJECTED`() {
        val cooldown = System.currentTimeMillis() + 30_000L
        val wire = MindlayerErrorCode.wireMessage(
            MindlayerErrorCode.SERVICE_THROTTLED,
            "service_throttled (cooldown=$cooldown)",
        )
        val stub = mockk<IMindlayerService>(relaxed = true) {
            every { registerClient(any()) } throws SecurityException(wire)
            every { asBinder() } returns mockk(relaxed = true)
        }

        mgr.connect(mockContext)
        connSlot.captured.onServiceConnected(stubComponent(), stubBinder(stub))

        // Must NOT be REJECTED_NOT_APPROVED — that is reserved for genuine
        // allowlist denials and is a terminal state.
        assertNotEquals(ConnectionState.REJECTED_NOT_APPROVED, mgr.state.value)
        // Must be RECOVERING with a pending rebind scheduled.
        assertEquals(ConnectionState.RECOVERING, mgr.state.value)
        // The binding is torn down so a fresh ServiceConnection is created
        // on the deferred reconnect.
        verify { mockAppContext.unbindService(any()) }
    }

    @Test
    fun `un-prefixed SecurityException still goes to REJECTED_NOT_APPROVED`() {
        // Forward-compat: the auth gate (allowlist denial, identity
        // unknown) still throws an un-prefixed SecurityException. That
        // path must keep terminating the connection — only the coded
        // SERVICE_THROTTLED path is special-cased.
        val stub = mockk<IMindlayerService>(relaxed = true) {
            every { registerClient(any()) } throws SecurityException("not approved")
            every { asBinder() } returns mockk(relaxed = true)
        }

        mgr.connect(mockContext)
        connSlot.captured.onServiceConnected(stubComponent(), stubBinder(stub))

        assertEquals(ConnectionState.REJECTED_NOT_APPROVED, mgr.state.value)
    }

    @Test
    fun `SERVICE_THROTTLED with no cooldown marker still transitions to RECOVERING`() {
        // Forward-compat: an older service binary that emits the typed
        // code without the cooldown payload should still trigger the
        // RECOVERING + deferred-reconnect path. The SDK falls back to
        // its MAX_BACKOFF cap for the wait.
        val wire = MindlayerErrorCode.wireMessage(
            MindlayerErrorCode.SERVICE_THROTTLED,
            "service_throttled",
        )
        val stub = mockk<IMindlayerService>(relaxed = true) {
            every { registerClient(any()) } throws SecurityException(wire)
            every { asBinder() } returns mockk(relaxed = true)
        }

        mgr.connect(mockContext)
        connSlot.captured.onServiceConnected(stubComponent(), stubBinder(stub))

        assertEquals(ConnectionState.RECOVERING, mgr.state.value)
        assertNotEquals(ConnectionState.REJECTED_NOT_APPROVED, mgr.state.value)
    }
}
