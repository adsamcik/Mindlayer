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
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression coverage for the on-demand rejection-recheck path
 * (see [ConnectionManager.REJECTION_RECHECK_COOLDOWN_MS]).
 *
 * Background: when [IMindlayerService.registerClient] throws a
 * SecurityException with the `App not authorized — user approval required`
 * wire code (MLERR:6001), the SDK lands in
 * [ConnectionState.REJECTED_NOT_APPROVED] and tears down the binding.
 *
 * The previous behaviour was strictly terminal: every subsequent
 * [ConnectionManager.awaitConnected] call would re-throw the same
 * `PERMISSION_DENIED` exception forever, even after the user approved the
 * caller in the Mindlayer dashboard. The only way to clear the cached
 * rejection was to force-stop the consumer app.
 *
 * The new behaviour: the FIRST awaitConnected after a rejection rebinds
 * once and re-asks the service. If the service now accepts (user has
 * approved), the binding lands in CONNECTED and the call returns the
 * binder. If the service still rejects, the call throws PERMISSION_DENIED.
 *
 * A 1-second cooldown ([ConnectionManager.REJECTION_RECHECK_COOLDOWN_MS])
 * prevents a hot retry loop in the consumer from weaponising the recheck
 * into a poll against the service-side per-UID rate limit.
 *
 * These tests pin the four most important behaviours:
 *  1. Approve-after-rejection: rebind succeeds → awaitConnected returns binder.
 *  2. Still-rejected: rebind also rejects → awaitConnected throws PERMISSION_DENIED.
 *  3. Cooldown: second awaitConnected within REJECTION_RECHECK_COOLDOWN_MS does
 *     NOT trigger another rebind (caller-poll defence).
 *  4. Cooldown reset: explicit connect() and successful registration both
 *     reset the cooldown so a future rejection gets a fresh recheck.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConnectionManagerRejectionRecheckTest {

    private lateinit var mgr: ConnectionManager
    private lateinit var mockContext: Context
    private lateinit var mockAppContext: Context

    private val connSlots = mutableListOf<ServiceConnection>()
    private val bindCallCount get() = connSlots.size

    /** Test clock so we can drive the cooldown without sleeping. */
    private var nowMs: Long = 1_000_000L

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        connSlots.clear()
        mockAppContext = mockk(relaxed = true) {
            every {
                bindService(any<Intent>(), any<ServiceConnection>(), any<Int>())
            } answers {
                // Capture each ServiceConnection so the test can drive
                // multiple bind cycles independently.
                connSlots.add(secondArg<ServiceConnection>())
                true
            }
            every { unbindService(any()) } just Runs
        }

        mockContext = mockk(relaxed = true) {
            every { applicationContext } returns mockAppContext
        }

        mgr = ConnectionManager()
        mgr.clockMillis = { nowMs }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun stubComponent() = ComponentName("com.adsamcik.mindlayer", "MlService")

    /**
     * Build a raw IBinder whose queryLocalInterface resolves to the supplied
     * stub. Mirrors the pattern in ConnectionManagerThrottleTest so the
     * SDK's `IMindlayerService.Stub.asInterface(binder).registerClient(...)`
     * call lands on our stub.
     */
    private fun stubBinder(stub: IMindlayerService): IBinder = mockk(relaxed = true) {
        every { linkToDeath(any(), any()) } just Runs
        every { unlinkToDeath(any(), any()) } returns true
        every { queryLocalInterface(IMindlayerService.DESCRIPTOR) } returns stub
    }

    private fun stubServiceThatRejects(): IMindlayerService = mockk(relaxed = true) {
        every { registerClient(any()) } throws SecurityException("not approved")
        every { asBinder() } returns mockk(relaxed = true)
    }

    private fun stubServiceThatAccepts(): IMindlayerService = mockk(relaxed = true) {
        every { registerClient(any()) } just Runs
        every { asBinder() } returns mockk(relaxed = true)
    }

    @Test
    fun `awaitConnected rebinds once after rejection and returns when user approves`() = runTest {
        // First bind: service rejects → state REJECTED_NOT_APPROVED.
        mgr.connect(mockContext)
        assertEquals("first bind launched", 1, bindCallCount)
        connSlots[0].onServiceConnected(stubComponent(), stubBinder(stubServiceThatRejects()))
        assertEquals(ConnectionState.REJECTED_NOT_APPROVED, mgr.state.value)

        // User approves between the rejection and the next API call.
        // We swap the bindService responder so the NEXT bind delivers a
        // stub that accepts. The cooldown floor has elapsed (nowMs ticks
        // forward), so awaitConnected should rebind.
        nowMs += ConnectionManager.REJECTION_RECHECK_COOLDOWN_MS + 1

        val deferred = async {
            mgr.awaitConnected(timeoutMs = 5_000L)
        }
        // The new bind call must have happened — that's the "rebind once
        // to re-check after rejection" behaviour.
        // (yield to let the awaitConnected coroutine reach the bind path)
        kotlinx.coroutines.yield()
        assertEquals(
            "awaitConnected must launch a fresh bind to recheck approval",
            2,
            bindCallCount,
        )
        // Deliver the now-accepting binder to the second ServiceConnection.
        connSlots[1].onServiceConnected(stubComponent(), stubBinder(stubServiceThatAccepts()))

        val service = deferred.await()
        assertNotNull("awaitConnected must return a binder after the user approves", service)
        assertEquals(ConnectionState.CONNECTED, mgr.state.value)
    }

    @Test
    fun `awaitConnected after rejection rebinds and throws if still not approved`() = runTest {
        // First bind: rejected.
        mgr.connect(mockContext)
        connSlots[0].onServiceConnected(stubComponent(), stubBinder(stubServiceThatRejects()))
        assertEquals(ConnectionState.REJECTED_NOT_APPROVED, mgr.state.value)

        nowMs += ConnectionManager.REJECTION_RECHECK_COOLDOWN_MS + 1

        val deferred = async {
            try {
                mgr.awaitConnected(timeoutMs = 5_000L)
                null
            } catch (e: MindlayerException) {
                e
            }
        }
        kotlinx.coroutines.yield()
        // Second bind happens — but the service still rejects.
        assertEquals("recheck rebind expected", 2, bindCallCount)
        connSlots[1].onServiceConnected(stubComponent(), stubBinder(stubServiceThatRejects()))

        val ex = deferred.await()
        assertNotNull("second rejection must surface as PERMISSION_DENIED", ex)
        assertEquals(MindlayerErrorCode.PERMISSION_DENIED, ex!!.code)
        assertEquals(ConnectionState.REJECTED_NOT_APPROVED, mgr.state.value)
    }

    @Test
    fun `awaitConnected does not rebind a second time inside the cooldown window`() = runTest {
        // Defence against a misbehaving consumer that calls awaitConnected
        // in a tight loop. We must rebind AT MOST once per
        // REJECTION_RECHECK_COOLDOWN_MS, otherwise the recheck becomes a
        // poll against the service-side rate limit.
        mgr.connect(mockContext)
        connSlots[0].onServiceConnected(stubComponent(), stubBinder(stubServiceThatRejects()))
        assertEquals(ConnectionState.REJECTED_NOT_APPROVED, mgr.state.value)

        nowMs += ConnectionManager.REJECTION_RECHECK_COOLDOWN_MS + 1

        // First awaitConnected: rebinds + still rejected → throws.
        val firstFail = async {
            try {
                mgr.awaitConnected(timeoutMs = 5_000L)
                null
            } catch (e: MindlayerException) { e }
        }
        kotlinx.coroutines.yield()
        assertEquals(2, bindCallCount)
        connSlots[1].onServiceConnected(stubComponent(), stubBinder(stubServiceThatRejects()))
        firstFail.await()

        // Second awaitConnected, fired immediately (no clock advance). The
        // cooldown floor must block another rebind — the call should throw
        // immediately without bumping bindCallCount.
        val secondFail = async {
            try {
                mgr.awaitConnected(timeoutMs = 5_000L)
                null
            } catch (e: MindlayerException) { e }
        }
        val ex = secondFail.await()
        assertNotNull(ex)
        assertEquals(MindlayerErrorCode.PERMISSION_DENIED, ex!!.code)
        assertEquals(
            "cooldown must suppress the second rebind",
            2,
            bindCallCount,
        )
    }

    @Test
    fun `awaitConnected rebinds again once the cooldown elapses`() = runTest {
        mgr.connect(mockContext)
        connSlots[0].onServiceConnected(stubComponent(), stubBinder(stubServiceThatRejects()))
        assertEquals(ConnectionState.REJECTED_NOT_APPROVED, mgr.state.value)

        nowMs += ConnectionManager.REJECTION_RECHECK_COOLDOWN_MS + 1

        // First recheck — rebinds, still rejected.
        val firstFail = async {
            try { mgr.awaitConnected(timeoutMs = 5_000L); null } catch (e: MindlayerException) { e }
        }
        kotlinx.coroutines.yield()
        assertEquals(2, bindCallCount)
        connSlots[1].onServiceConnected(stubComponent(), stubBinder(stubServiceThatRejects()))
        firstFail.await()

        // Advance past the cooldown — a fresh awaitConnected call MUST be
        // allowed to recheck (the user may have approved between the two
        // calls in the meantime).
        nowMs += ConnectionManager.REJECTION_RECHECK_COOLDOWN_MS + 1

        val secondTry = async {
            mgr.awaitConnected(timeoutMs = 5_000L)
        }
        kotlinx.coroutines.yield()
        assertEquals("post-cooldown awaitConnected must rebind", 3, bindCallCount)
        connSlots[2].onServiceConnected(stubComponent(), stubBinder(stubServiceThatAccepts()))
        val service = secondTry.await()
        assertNotNull(service)
        assertEquals(ConnectionState.CONNECTED, mgr.state.value)
    }

    @Test
    fun `successful registration resets the rejection-recheck cooldown for next rejection`() = runTest {
        // Setup: rejection #1 → recheck (cooldown timestamp set) → CONNECTED
        // (successful registration).
        mgr.connect(mockContext)
        connSlots[0].onServiceConnected(stubComponent(), stubBinder(stubServiceThatRejects()))
        nowMs += ConnectionManager.REJECTION_RECHECK_COOLDOWN_MS + 1
        val firstApproval = async { mgr.awaitConnected(timeoutMs = 5_000L) }
        kotlinx.coroutines.yield()
        connSlots[1].onServiceConnected(stubComponent(), stubBinder(stubServiceThatAccepts()))
        firstApproval.await()
        assertEquals(ConnectionState.CONNECTED, mgr.state.value)

        // Successful registration must drop the cooldown timestamp back to
        // 0L so a future rejection in this manager (e.g. user later
        // revokes approval and the next rebind gets rejected) gets a
        // fresh, immediate recheck window rather than inheriting a stale
        // floor that could block recovery.
        val cooldownField = ConnectionManager::class.java
            .getDeclaredField("lastRejectionRecheckAt")
            .apply { isAccessible = true }
        assertEquals(
            "successful registration must reset lastRejectionRecheckAt to 0L",
            0L,
            cooldownField.getLong(mgr),
        )
    }

    @Test
    fun `connect resets the rejection-recheck cooldown`() = runTest {
        // Rejection #1 → recheck → still rejected (cooldown timestamp now set).
        mgr.connect(mockContext)
        connSlots[0].onServiceConnected(stubComponent(), stubBinder(stubServiceThatRejects()))
        nowMs += ConnectionManager.REJECTION_RECHECK_COOLDOWN_MS + 1
        val firstFail = async {
            try { mgr.awaitConnected(timeoutMs = 5_000L); null } catch (e: MindlayerException) { e }
        }
        kotlinx.coroutines.yield()
        connSlots[1].onServiceConnected(stubComponent(), stubBinder(stubServiceThatRejects()))
        firstFail.await()

        val cooldownField = ConnectionManager::class.java
            .getDeclaredField("lastRejectionRecheckAt")
            .apply { isAccessible = true }
        assertNotEquals(
            "lastRejectionRecheckAt must be set after the first recheck fired",
            0L,
            cooldownField.getLong(mgr),
        )

        // Explicit connect() is the caller's "reset everything" signal — it
        // must drop the cooldown timestamp.
        mgr.connect(mockContext)
        assertEquals(
            "connect() must reset lastRejectionRecheckAt to 0L",
            0L,
            cooldownField.getLong(mgr),
        )
    }

    @Test
    fun `awaitConnected with no bound context surfaces immediate PERMISSION_DENIED on rejection`() = runTest {
        // Edge case: rejection happened, then disconnect dropped the
        // bound context. awaitConnected can't rebind (no context to bind
        // against) so it must surface the rejection immediately rather
        // than spinning waiting for a state change that will never come.
        mgr.connect(mockContext)
        connSlots[0].onServiceConnected(stubComponent(), stubBinder(stubServiceThatRejects()))
        assertEquals(ConnectionState.REJECTED_NOT_APPROVED, mgr.state.value)

        // Forcibly null the bound context to simulate the disconnect()
        // path or a defensive caller resetting state.
        val contextField = ConnectionManager::class.java
            .getDeclaredField("boundContext")
            .apply { isAccessible = true }
        contextField.set(mgr, null)

        nowMs += ConnectionManager.REJECTION_RECHECK_COOLDOWN_MS + 1
        val deferred = async {
            try {
                mgr.awaitConnected(timeoutMs = 5_000L)
                null
            } catch (e: MindlayerException) { e }
        }
        val ex = deferred.await()
        assertNotNull(ex)
        assertEquals(MindlayerErrorCode.PERMISSION_DENIED, ex!!.code)
        // No rebind was attempted (we still have just the initial bind).
        assertEquals(1, bindCallCount)
    }
}
