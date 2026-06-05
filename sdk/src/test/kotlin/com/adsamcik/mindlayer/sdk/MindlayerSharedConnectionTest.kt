package com.adsamcik.mindlayer.sdk

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * Lifecycle coverage for the process-shared client accessor [Mindlayer.shared]
 * / [Mindlayer.disconnectShared]. Uses the internal `sharedConnectFactory`
 * seam so the singleton logic is exercised without a real Binder bind or
 * history DB — only the identity / rebuild / policy-guard behaviour matters
 * here; the bind itself is covered by ConnectionManager tests.
 */
class MindlayerSharedConnectionTest {

    private lateinit var context: Context

    @Before fun setUp() {
        context = mockk(relaxed = true)
        every { context.applicationContext } returns context
        // Start from a clean static singleton regardless of test order.
        Mindlayer.disconnectShared()
        Mindlayer.sharedConnectFactory = null
    }

    @After fun tearDown() {
        Mindlayer.disconnectShared()
        Mindlayer.sharedConnectFactory = null
    }

    private fun fakeClient(
        initial: ConnectionState = ConnectionState.CONNECTING,
    ): Pair<Mindlayer, MutableStateFlow<ConnectionState>> {
        val flow = MutableStateFlow(initial)
        val client = mockk<Mindlayer>(relaxed = true)
        every { client.connectionState } returns flow
        return client to flow
    }

    @Test fun `shared returns the same instance on repeated calls`() {
        val (client, _) = fakeClient()
        Mindlayer.sharedConnectFactory = { _, _ -> client }

        val a = Mindlayer.shared(context)
        val b = Mindlayer.shared(context)

        assertSame(a, b)
        assertSame(client, a)
    }

    @Test fun `disconnectShared tears down and the next shared builds a new instance`() {
        val (first, _) = fakeClient()
        val (second, _) = fakeClient()
        val clients = ArrayDeque(listOf(first, second))
        Mindlayer.sharedConnectFactory = { _, _ -> clients.removeFirst() }

        val a = Mindlayer.shared(context)
        Mindlayer.disconnectShared()
        val b = Mindlayer.shared(context)

        verify { first.disconnect() }
        assertNotSame(a, b)
        assertSame(second, b)
    }

    @Test fun `shared rebuilds when the previous instance was disconnected out from under it`() {
        val (first, firstState) = fakeClient()
        val (second, _) = fakeClient()
        val clients = ArrayDeque(listOf(first, second))
        Mindlayer.sharedConnectFactory = { _, _ -> clients.removeFirst() }

        val a = Mindlayer.shared(context)
        // Simulate a consumer calling disconnect() directly instead of
        // disconnectShared(): the instance lands in DISCONNECTED.
        firstState.value = ConnectionState.DISCONNECTED
        val b = Mindlayer.shared(context)

        assertSame(first, a)
        assertSame(second, b)
        assertNotSame(a, b)
    }

    @Test fun `shared does not rebuild on a non-DISCONNECTED terminal state`() {
        val (first, firstState) = fakeClient()
        Mindlayer.sharedConnectFactory = { _, _ -> first }
        val firstClient = Mindlayer.shared(context)

        // BIND_GAVE_UP is terminal but not DISCONNECTED — we must NOT thrash a
        // rebuild (the consumer can resume via awaitConnected()).
        firstState.value = ConnectionState.BIND_GAVE_UP
        Mindlayer.sharedConnectFactory = { _, _ -> error("shared() must not rebuild on BIND_GAVE_UP") }

        val again = Mindlayer.shared(context)

        assertSame(firstClient, again)
    }

    @Test fun `shared throws when called with a different history policy`() {
        val (client, _) = fakeClient()
        Mindlayer.sharedConnectFactory = { _, _ -> client }

        Mindlayer.shared(context, HistoryPolicy.METADATA_ONLY)

        assertThrows(IllegalStateException::class.java) {
            Mindlayer.shared(context, HistoryPolicy.FULL_CONTENT)
        }
    }

    @Test fun `shared with the same non-default policy returns the same instance`() {
        val (client, _) = fakeClient()
        Mindlayer.sharedConnectFactory = { _, _ -> client }

        val a = Mindlayer.shared(context, HistoryPolicy.FULL_CONTENT)
        val b = Mindlayer.shared(context, HistoryPolicy.FULL_CONTENT)

        assertSame(a, b)
    }
}
