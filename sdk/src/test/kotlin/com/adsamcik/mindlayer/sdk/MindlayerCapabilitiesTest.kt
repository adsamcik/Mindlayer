package com.adsamcik.mindlayer.sdk

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.mindlayer.IMindlayerService
import com.adsamcik.mindlayer.ServiceCapabilities
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for `v02-capabilities`: SDK probes [IMindlayerService.getCapabilities]
 * once after `awaitConnected`, caches the result, and falls back to
 * [ServiceCapabilities.v0Baseline] when the service stub doesn't implement
 * the method (binder reports `NoSuchMethodError` / `AbstractMethodError`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MindlayerCapabilitiesTest {

    private lateinit var mockService: IMindlayerService
    private lateinit var mockConnection: ConnectionManager
    private lateinit var mindlayer: Mindlayer

    private val sampleCaps = ServiceCapabilities(
        apiVersion = 1,
        supportedFeatures = setOf(
            ServiceCapabilities.FEATURE_TYPED_ERRORS,
            ServiceCapabilities.FEATURE_TOOL_RESULTS,
        ),
        pipeProtocol = "mindlayer.stream.v1",
        maxFrameBytes = 1_048_576,
        maxToolRounds = 25,
        maxToolArgsLen = 64 * 1024,
        maxRequestsPerMinute = 60,
        maxConcurrentInferences = 4,
        maxConcurrentSessions = 3,
        maxSessionExpirationMs = 90L * 24 * 60 * 60 * 1000,
        maxMediaPartsPerRequest = 2,
        maxTotalMediaBytesPerRequest = 200L * 1024 * 1024,
    )

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0

        mockService = mockk(relaxed = true)
        mockConnection = mockk(relaxed = true)
        every { mockConnection.state } returns MutableStateFlow(ConnectionState.CONNECTED)
        io.mockk.coEvery { mockConnection.awaitConnected() } returns mockService

        mindlayer = buildMindlayer(mockConnection, historyStore = null)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `getCapabilities returns service-supplied caps`() = runTest {
        every { mockService.capabilities } returns sampleCaps

        val caps = mindlayer.getCapabilities()

        assertSame("expected exact instance from the service", sampleCaps, caps)
        verify(exactly = 1) { mockService.capabilities }
    }

    @Test
    fun `getCapabilities is cached after first call`() = runTest {
        every { mockService.capabilities } returns sampleCaps

        mindlayer.getCapabilities()
        mindlayer.getCapabilities()
        mindlayer.getCapabilities()

        verify(exactly = 1) { mockService.capabilities }
    }

    @Test
    fun `getCapabilities falls back to v0Baseline on NoSuchMethodError`() = runTest {
        every { mockService.capabilities } throws NoSuchMethodError("getCapabilities")

        val caps = mindlayer.getCapabilities()

        assertEquals(0, caps.apiVersion)
        assertTrue(
            "v0Baseline should advertise pipe_stream_v1",
            caps.supports(ServiceCapabilities.FEATURE_PIPE_STREAM_V1),
        )
        assertNotEquals(
            "v0Baseline should NOT advertise typed_errors (that's a v0.2+ feature)",
            true,
            caps.supports(ServiceCapabilities.FEATURE_TYPED_ERRORS),
        )
    }

    @Test
    fun `getCapabilities falls back to v0Baseline on AbstractMethodError`() = runTest {
        every { mockService.capabilities } throws AbstractMethodError("getCapabilities")

        val caps = mindlayer.getCapabilities()

        assertEquals(0, caps.apiVersion)
    }

    @Test
    fun `fallback result is cached too`() = runTest {
        every { mockService.capabilities } throws NoSuchMethodError("getCapabilities")

        val first = mindlayer.getCapabilities()
        val second = mindlayer.getCapabilities()

        // Both should be the same v0 baseline; the AIDL is hit only once.
        assertEquals(first, second)
        verify(exactly = 1) { mockService.capabilities }
    }

    @Test
    fun `auth gate SecurityException propagates from getCapabilities`() = runTest {
        every { mockService.capabilities } throws SecurityException("Rate limit exceeded")

        var thrown: Throwable? = null
        try {
            mindlayer.getCapabilities()
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue(
            "auth-gate SecurityException should propagate, got $thrown",
            thrown is SecurityException,
        )
    }

    @Test
    fun `supports works for canonical feature flags`() {
        val caps = ServiceCapabilities.v0Baseline()
        assertTrue(caps.supports(ServiceCapabilities.FEATURE_PIPE_STREAM_V1))
        assertTrue(caps.supports(ServiceCapabilities.FEATURE_TOOL_RESULTS))
        assertEquals(false, caps.supports(ServiceCapabilities.FEATURE_MEDIA_LIST))
        assertEquals(false, caps.supports(ServiceCapabilities.FEATURE_TOKEN_BATCH))
    }

    // ---- Helpers --------------------------------------------------------

    private fun buildMindlayer(conn: ConnectionManager, historyStore: HistoryStore?): Mindlayer {
        val ctor = Mindlayer::class.java.getDeclaredConstructor(
            ConnectionManager::class.java,
            HistoryStore::class.java,
        )
        ctor.isAccessible = true
        return ctor.newInstance(conn, historyStore)
    }
}
