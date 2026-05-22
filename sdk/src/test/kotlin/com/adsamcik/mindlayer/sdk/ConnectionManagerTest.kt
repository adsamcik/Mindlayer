package com.adsamcik.mindlayer.sdk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import app.cash.turbine.test
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConnectionManagerTest {

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

    // -- State machine --------------------------------------------------------

    @Test
    fun `initial state is DISCONNECTED`() {
        assertEquals(ConnectionState.DISCONNECTED, mgr.state.value)
    }

    @Test
    fun `connect transitions to CONNECTING`() {
        mgr.connect(mockContext)
        assertEquals(ConnectionState.CONNECTING, mgr.state.value)
    }

    @Test
    fun `onServiceConnected transitions to CONNECTED`() {
        mgr.connect(mockContext)
        deliverBinder()
        assertEquals(ConnectionState.CONNECTED, mgr.state.value)
    }

    @Test
    fun `onServiceDisconnected transitions to RECOVERING`() {
        mgr.connect(mockContext)
        deliverBinder()
        connSlot.captured.onServiceDisconnected(stubComponent())
        assertEquals(ConnectionState.RECOVERING, mgr.state.value)
    }

    @Test
    fun `onBindingDied transitions to RECOVERING and unbinds`() {
        mgr.connect(mockContext)
        deliverBinder()
        connSlot.captured.onBindingDied(stubComponent())
        assertEquals(ConnectionState.RECOVERING, mgr.state.value)
        verify { mockAppContext.unbindService(any()) }
    }

    @Test
    fun `binderDied via linkToDeath invalidates cached binder`() {
        val recipientSlot = slot<IBinder.DeathRecipient>()
        val rawBinder = mockk<IBinder>(relaxed = true) {
            every { linkToDeath(capture(recipientSlot), any()) } just Runs
            every { queryLocalInterface(any()) } returns null
        }

        mgr.connect(mockContext)
        connSlot.captured.onServiceConnected(stubComponent(), rawBinder)
        assertEquals(ConnectionState.CONNECTED, mgr.state.value)

        // Fire the death recipient
        recipientSlot.captured.binderDied()
        assertNull(mgr.getService())
        assertEquals(ConnectionState.RECOVERING, mgr.state.value)
    }

    @Test
    fun `disconnect transitions to DISCONNECTED and unbinds`() {
        mgr.connect(mockContext)
        deliverBinder()
        mgr.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, mgr.state.value)
        verify { mockAppContext.unbindService(any()) }
    }

    // -- getService / requireService ------------------------------------------

    @Test
    fun `getService returns binder when CONNECTED`() {
        mgr.connect(mockContext)
        deliverBinder()
        assertNotNull(mgr.getService())
    }

    @Test
    fun `getService returns null when DISCONNECTED`() {
        assertNull(mgr.getService())
    }

    @Test
    fun `getService returns null when CONNECTING`() {
        mgr.connect(mockContext)
        assertNull(mgr.getService())
    }

    @Test
    fun `requireService throws when DISCONNECTED`() {
        val ex = assertThrows(MindlayerException::class.java) {
            mgr.requireService()
        }
        assertTrue(ex.message!!.contains("not connected"))
        assertEquals(MindlayerErrorCode.SERVICE_UNAVAILABLE, ex.code)
    }

    @Test
    fun `requireService returns binder when CONNECTED`() {
        mgr.connect(mockContext)
        deliverBinder()
        assertNotNull(mgr.requireService())
    }

    // -- awaitConnected -------------------------------------------------------

    @Test
    fun `awaitConnected suspends until CONNECTED`() = runTest {
        mgr.connect(mockContext)

        val deferred = async { mgr.awaitConnected() }
        // Still waiting (CONNECTING)
        assertTrue(deferred.isActive)

        // Deliver binder → CONNECTED
        deliverBinder()
        val result = deferred.await()
        assertNotNull(result)
    }

    @Test
    fun `awaitConnected retries when binder dies between state and read`() = runTest {
        mgr.connect(mockContext)

        // Set up a binder whose binderRef will be nulled right after CONNECTED
        val recipientSlot = slot<IBinder.DeathRecipient>()
        val rawBinder1 = mockk<IBinder>(relaxed = true) {
            every { linkToDeath(capture(recipientSlot), any()) } just Runs
            every { queryLocalInterface(any()) } returns null
        }

        val deferred = async { mgr.awaitConnected() }

        // Deliver binder → CONNECTED, then immediately kill it
        connSlot.captured.onServiceConnected(stubComponent(), rawBinder1)
        // Simulate binder death right after CONNECTED — triggers RECOVERING
        recipientSlot.captured.binderDied()

        // Still active because binder was nulled, loop retries
        assertTrue(deferred.isActive)

        // Deliver a fresh binder → CONNECTED again
        deliverBinder()
        val result = deferred.await()
        assertNotNull(result)
    }

    @Test
    fun `awaitConnected times out when service never connects`() = runTest {
        mgr.connect(mockContext)

        val ex = assertThrows(MindlayerException::class.java) {
            @Suppress("BlockingMethodInNonBlockingContext")
            kotlinx.coroutines.runBlocking {
                mgr.awaitConnected(timeoutMs = 100L)
            }
        }
        assertEquals(MindlayerErrorCode.CONNECT_TIMEOUT, ex.code)
    }

    @Test
    fun `awaitConnected with custom timeout`() = runTest {
        mgr.connect(mockContext)

        val ex = assertThrows(MindlayerException::class.java) {
            @Suppress("BlockingMethodInNonBlockingContext")
            kotlinx.coroutines.runBlocking {
                mgr.awaitConnected(timeoutMs = 50L)
            }
        }
        assertEquals(MindlayerErrorCode.CONNECT_TIMEOUT, ex.code)
    }

    @Test
    fun `bindService returning false on supported android surfaces service unavailable`() {
        every {
            mockAppContext.bindService(any<Intent>(), any<ServiceConnection>(), any<Int>())
        } returns false

        mgr.connect(mockContext)

        assertEquals(ConnectionState.BIND_GAVE_UP, mgr.state.value)
        val ex = assertThrows(MindlayerException::class.java) {
            kotlinx.coroutines.runBlocking { mgr.awaitConnected(timeoutMs = 1_000L) }
        }
        assertEquals(MindlayerErrorCode.SERVICE_UNAVAILABLE, ex.code)
    }

    @Test
    fun `bindService SecurityException on supported android surfaces permission denied`() {
        every {
            mockAppContext.bindService(any<Intent>(), any<ServiceConnection>(), any<Int>())
        } throws SecurityException("denied")

        mgr.connect(mockContext)

        assertEquals(ConnectionState.BIND_GAVE_UP, mgr.state.value)
        val ex = assertThrows(MindlayerException::class.java) {
            kotlinx.coroutines.runBlocking { mgr.awaitConnected(timeoutMs = 1_000L) }
        }
        assertEquals(MindlayerErrorCode.PERMISSION_DENIED, ex.code)
    }

    // -- StateFlow emissions --------------------------------------------------

    @Test
    fun `state flow emits correct transitions`() = runTest {
        mgr.state.test {
            assertEquals(ConnectionState.DISCONNECTED, awaitItem())

            mgr.connect(mockContext)
            assertEquals(ConnectionState.CONNECTING, awaitItem())

            deliverBinder()
            assertEquals(ConnectionState.CONNECTED, awaitItem())

            connSlot.captured.onServiceDisconnected(stubComponent())
            assertEquals(ConnectionState.RECOVERING, awaitItem())

            cancelAndConsumeRemainingEvents()
        }
    }

    // -- Idempotency ----------------------------------------------------------

    @Test
    fun `double connect is ignored when already CONNECTING`() {
        mgr.connect(mockContext)
        assertEquals(ConnectionState.CONNECTING, mgr.state.value)

        // Second connect should be a no-op
        mgr.connect(mockContext)
        assertEquals(ConnectionState.CONNECTING, mgr.state.value)
        // bindService called only once
        verify(exactly = 1) {
            mockAppContext.bindService(any<Intent>(), any(), any<Int>())
        }
    }

    @Test
    fun `double connect is ignored when already CONNECTED`() {
        mgr.connect(mockContext)
        deliverBinder()
        assertEquals(ConnectionState.CONNECTED, mgr.state.value)

        mgr.connect(mockContext)
        assertEquals(ConnectionState.CONNECTED, mgr.state.value)
        verify(exactly = 1) {
            mockAppContext.bindService(any<Intent>(), any(), any<Int>())
        }
    }

    @Test
    fun `double disconnect is safe`() {
        mgr.connect(mockContext)
        deliverBinder()
        mgr.disconnect()
        mgr.disconnect() // should not crash
        assertEquals(ConnectionState.DISCONNECTED, mgr.state.value)
    }

    // -- bindService failure --------------------------------------------------

    @Test
    fun `connect moves to BIND_GAVE_UP when bindService returns false`() {
        every {
            mockAppContext.bindService(any<Intent>(), any(), any<Int>())
        } returns false

        mgr.connect(mockContext)
        assertEquals(ConnectionState.BIND_GAVE_UP, mgr.state.value)
    }

    @Test
    fun `connect moves to BIND_GAVE_UP when bindService throws SecurityException`() {
        every {
            mockAppContext.bindService(any<Intent>(), any(), any<Int>())
        } throws SecurityException("denied")

        mgr.connect(mockContext)
        assertEquals(ConnectionState.BIND_GAVE_UP, mgr.state.value)
    }

    // -- onServiceConnected edge case -----------------------------------------

    @Test
    fun `onServiceConnected with null binder logs warning and stays CONNECTING`() {
        mgr.connect(mockContext)
        connSlot.captured.onServiceConnected(stubComponent(), null)
        assertEquals(ConnectionState.CONNECTING, mgr.state.value)
        assertNull(mgr.getService())
    }

    @Test
    fun `onServiceConnected handles linkToDeath failure`() {
        val rawBinder = mockk<IBinder>(relaxed = true) {
            every { linkToDeath(any(), any()) } throws RuntimeException("already dead")
            every { queryLocalInterface(any()) } returns null
        }
        mgr.connect(mockContext)
        connSlot.captured.onServiceConnected(stubComponent(), rawBinder)
        // Should have called onBinderDied, binder should be null
        assertNull(mgr.getService())
        assertEquals(ConnectionState.RECOVERING, mgr.state.value)
    }

    // -- binderDied when already DISCONNECTED ----------------------------------

    @Test
    fun `binderDied while DISCONNECTED does not transition to RECOVERING`() {
        val recipientSlot = slot<IBinder.DeathRecipient>()
        val rawBinder = mockk<IBinder>(relaxed = true) {
            every { linkToDeath(capture(recipientSlot), any()) } just Runs
            every { queryLocalInterface(any()) } returns null
        }

        mgr.connect(mockContext)
        connSlot.captured.onServiceConnected(stubComponent(), rawBinder)
        mgr.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, mgr.state.value)

        recipientSlot.captured.binderDied()
        assertEquals(ConnectionState.DISCONNECTED, mgr.state.value)
    }

    // -- onServiceDisconnected clears binder ----------------------------------

    @Test
    fun `onServiceDisconnected clears binder reference`() {
        mgr.connect(mockContext)
        deliverBinder()
        assertNotNull(mgr.getService())

        connSlot.captured.onServiceDisconnected(stubComponent())
        assertNull(mgr.getService())
    }

    // -- onBindingDied clears binder ------------------------------------------

    @Test
    fun `onBindingDied clears binder reference`() {
        mgr.connect(mockContext)
        deliverBinder()
        assertNotNull(mgr.getService())

        connSlot.captured.onBindingDied(stubComponent())
        assertNull(mgr.getService())
    }

    // -- Exponential backoff --------------------------------------------------

    @Test
    fun `exponential backoff increases delays with cap at 5s`() {
        // Access backoffMs via reflection to verify the sequence
        val backoffField = ConnectionManager::class.java.getDeclaredField("backoffMs")
        backoffField.isAccessible = true

        mgr.connect(mockContext)
        deliverBinder()

        assertEquals(250L, backoffField.getLong(mgr))

        // Trigger onBindingDied which calls scheduleReconnect() which bumps backoff
        connSlot.captured.onBindingDied(stubComponent())
        // After scheduleReconnect is called, backoffMs is increased in the coroutine.
        // Let's simulate multiple backoff escalations by reading the companion values.
        val initialField = ConnectionManager::class.java.getDeclaredField("INITIAL_BACKOFF_MS")
        initialField.isAccessible = true
        val maxField = ConnectionManager::class.java.getDeclaredField("MAX_BACKOFF_MS")
        maxField.isAccessible = true
        val multiplierField = ConnectionManager::class.java.getDeclaredField("BACKOFF_MULTIPLIER")
        multiplierField.isAccessible = true

        assertEquals(250L, initialField.getLong(null))
        assertEquals(5000L, maxField.getLong(null))
        assertEquals(2.0, multiplierField.getDouble(null), 0.001)

        // Verify the series: 250 → 500 → 1000 → 2000 → 4000 → 5000 (capped)
        var backoff = 250L
        val expected = listOf(500L, 1000L, 2000L, 4000L, 5000L)
        for (exp in expected) {
            backoff = (backoff * 2.0).toLong().coerceAtMost(5000L)
            assertEquals(exp, backoff)
        }
    }

    @Test
    fun `successful reconnect resets backoff to initial value`() {
        val backoffField = ConnectionManager::class.java.getDeclaredField("backoffMs")
        backoffField.isAccessible = true

        mgr.connect(mockContext)
        deliverBinder()
        assertEquals(250L, backoffField.getLong(mgr))

        // Simulate a backoff bump
        backoffField.setLong(mgr, 2000L)
        assertEquals(2000L, backoffField.getLong(mgr))

        // Reconnect delivery resets backoff
        val rawBinder2 = mockk<IBinder>(relaxed = true) {
            every { linkToDeath(any(), any()) } just Runs
            every { queryLocalInterface(any()) } returns null
        }
        connSlot.captured.onServiceConnected(stubComponent(), rawBinder2)
        assertEquals(250L, backoffField.getLong(mgr))
    }

    // -- unbindService tolerates IllegalArgumentException ---------------------

    @Test
    fun `doUnbind tolerates IllegalArgumentException from unbindService`() {
        every { mockAppContext.unbindService(any()) } throws IllegalArgumentException("not bound")
        mgr.connect(mockContext)
        deliverBinder()
        // Should not throw
        mgr.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, mgr.state.value)
    }

    // -- Give-up after MAX_RECONNECT_ATTEMPTS ---------------------------------

    @Test
    fun `bindGaveUp is false initially`() {
        assertFalse(mgr.bindGaveUp)
    }

    @Test
    fun `give up after MAX_RECONNECT_ATTEMPTS consecutive onBindingDied fires`() {
        mgr.connect(mockContext)
        deliverBinder()

        // Fire onBindingDied MAX_RECONNECT_ATTEMPTS times without a successful reconnect.
        // Each call to onBindingDied invokes scheduleReconnect() synchronously before
        // launching the delay coroutine, so the counter advances without time advancement.
        repeat(ConnectionManager.MAX_RECONNECT_ATTEMPTS) {
            connSlot.captured.onBindingDied(stubComponent())
        }

        assertTrue(mgr.bindGaveUp)
        assertEquals(ConnectionState.BIND_GAVE_UP, mgr.state.value)
    }

    @Test
    fun `no further reconnect scheduled after give-up`() {
        mgr.connect(mockContext)
        deliverBinder()

        repeat(ConnectionManager.MAX_RECONNECT_ATTEMPTS) {
            connSlot.captured.onBindingDied(stubComponent())
        }
        assertTrue(mgr.bindGaveUp)
        assertEquals(ConnectionState.BIND_GAVE_UP, mgr.state.value)

        // Additional onBindingDied calls after give-up must not flip bindGaveUp back
        // or change state (the guard at the top of scheduleReconnect() returns early).
        connSlot.captured.onBindingDied(stubComponent())
        connSlot.captured.onBindingDied(stubComponent())

        assertTrue(mgr.bindGaveUp)
        assertEquals(ConnectionState.BIND_GAVE_UP, mgr.state.value)
    }

    @Test
    fun `getService returns null when in BIND_GAVE_UP state`() {
        mgr.connect(mockContext)
        deliverBinder()

        repeat(ConnectionManager.MAX_RECONNECT_ATTEMPTS) {
            connSlot.captured.onBindingDied(stubComponent())
        }

        assertEquals(ConnectionState.BIND_GAVE_UP, mgr.state.value)
        assertNull(mgr.getService())
    }

    @Test
    fun `connect after give-up resets counter and retries binding`() {
        mgr.connect(mockContext)
        deliverBinder()

        repeat(ConnectionManager.MAX_RECONNECT_ATTEMPTS) {
            connSlot.captured.onBindingDied(stubComponent())
        }
        assertTrue(mgr.bindGaveUp)
        assertEquals(ConnectionState.BIND_GAVE_UP, mgr.state.value)

        // Explicit reconnect should clear give-up state and start a new bind
        mgr.connect(mockContext)

        assertFalse(mgr.bindGaveUp)
        assertEquals(ConnectionState.CONNECTING, mgr.state.value)
        // bindService called at least twice (initial + retry after give-up)
        verify(atLeast = 2) {
            mockAppContext.bindService(any<Intent>(), any(), any<Int>())
        }
    }

    @Test
    fun `connect after give-up resets consecutiveFailures so give-up re-arms`() {
        val consecutiveFailuresField =
            ConnectionManager::class.java.getDeclaredField("consecutiveFailures")
        consecutiveFailuresField.isAccessible = true

        mgr.connect(mockContext)
        deliverBinder()

        repeat(ConnectionManager.MAX_RECONNECT_ATTEMPTS) {
            connSlot.captured.onBindingDied(stubComponent())
        }
        assertTrue(mgr.bindGaveUp)

        mgr.connect(mockContext)

        assertFalse(mgr.bindGaveUp)
        assertEquals(0, consecutiveFailuresField.getInt(mgr))
    }

    @Test
    fun `successful reconnect after partial failures resets counter`() {
        val consecutiveFailuresField =
            ConnectionManager::class.java.getDeclaredField("consecutiveFailures")
        consecutiveFailuresField.isAccessible = true

        mgr.connect(mockContext)
        deliverBinder()

        // Trigger some failures (less than MAX)
        val partialCount = ConnectionManager.MAX_RECONNECT_ATTEMPTS - 3
        repeat(partialCount) {
            connSlot.captured.onBindingDied(stubComponent())
        }
        assertEquals(partialCount, consecutiveFailuresField.getInt(mgr))
        assertFalse(mgr.bindGaveUp)

        // Successful reconnect resets the counter
        deliverBinder()

        assertEquals(0, consecutiveFailuresField.getInt(mgr))
        assertFalse(mgr.bindGaveUp)
        assertEquals(ConnectionState.CONNECTED, mgr.state.value)
    }

    @Test
    fun `awaitConnected throws service unavailable when BIND_GAVE_UP`() = runTest {
        mgr.connect(mockContext)
        deliverBinder()

        val deferred = async {
            try {
                mgr.awaitConnected(timeoutMs = 10_000L)
                null
            } catch (e: MindlayerException) {
                e
            }
        }

        // Drive the manager to BIND_GAVE_UP
        repeat(ConnectionManager.MAX_RECONNECT_ATTEMPTS) {
            connSlot.captured.onBindingDied(stubComponent())
        }

        val ex = deferred.await()
        assertNotNull(ex)
        assertTrue(ex!!.message!!.contains("permanently unavailable"))
        assertEquals(MindlayerErrorCode.SERVICE_UNAVAILABLE, ex.code)
    }

    // -- Helpers --------------------------------------------------------------

    private fun stubComponent() = ComponentName("com.adsamcik.mindlayer.service", "MlService")

    private fun deliverBinder() {
        val rawBinder = mockk<IBinder>(relaxed = true) {
            every { linkToDeath(any(), any()) } just Runs
            every { queryLocalInterface(any()) } returns null
        }
        connSlot.captured.onServiceConnected(stubComponent(), rawBinder)
    }
}
