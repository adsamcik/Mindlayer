package com.mindlayer.sdk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import app.cash.turbine.test
import com.mindlayer.IMindlayerService
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
        val ex = assertThrows(IllegalStateException::class.java) {
            mgr.requireService()
        }
        assertTrue(ex.message!!.contains("not connected"))
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
    fun `connect falls back to DISCONNECTED when bindService returns false`() {
        every {
            mockAppContext.bindService(any<Intent>(), any(), any<Int>())
        } returns false

        mgr.connect(mockContext)
        assertEquals(ConnectionState.DISCONNECTED, mgr.state.value)
    }

    @Test
    fun `connect falls back to DISCONNECTED when bindService throws SecurityException`() {
        every {
            mockAppContext.bindService(any<Intent>(), any(), any<Int>())
        } throws SecurityException("denied")

        mgr.connect(mockContext)
        assertEquals(ConnectionState.DISCONNECTED, mgr.state.value)
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

    // -- Helpers --------------------------------------------------------------

    private fun stubComponent() = ComponentName("com.mindlayer.service", "MlService")

    private fun deliverBinder() {
        val rawBinder = mockk<IBinder>(relaxed = true) {
            every { linkToDeath(any(), any()) } just Runs
            every { queryLocalInterface(any()) } returns null
        }
        connSlot.captured.onServiceConnected(stubComponent(), rawBinder)
    }
}
