package com.adsamcik.mindlayer.sdk

import android.os.DeadObjectException
import android.os.RemoteException
import android.util.Log
import com.adsamcik.mindlayer.EmbeddingResult
import com.adsamcik.mindlayer.IMindlayerService
import com.adsamcik.mindlayer.ServiceCapabilities
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the AIDL chokepoint's `:ml`-process-death handling
 * ([MindlayerImpl.withTypedErrors]):
 *
 *  - idempotent calls (e.g. [MindlayerImpl.embed]) transparently reconnect and
 *    retry once after a [DeadObjectException], then succeed;
 *  - persistent death surfaces a typed [MindlayerErrorCode.SERVICE_UNAVAILABLE]
 *    rather than leaking a raw [DeadObjectException];
 *  - non-idempotent calls (e.g. [MindlayerImpl.destroySession]) map a dead
 *    binder to the typed error **without** a retry;
 *  - a generic [RemoteException] maps to the typed error and is never retried.
 *
 * Mirrors the harness in `MindlayerEmbedTest`: a relaxed mock
 * [IMindlayerService] behind a relaxed mock [ConnectionManager] whose
 * `awaitConnected()` always re-hands the same service (the reconnect itself is
 * exercised by `ConnectionManager`'s own tests; here we assert the chokepoint's
 * retry/typed-mapping policy).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@Suppress("DEPRECATION") // exercises legacy embed(text) — replaced by embedOne(text)
class MindlayerServiceDeathRecoveryTest {

    private lateinit var mockService: IMindlayerService
    private lateinit var mockConnection: ConnectionManager
    private lateinit var mindlayer: MindlayerImpl

    private val embeddingCaps = ServiceCapabilities(
        apiVersion = 8,
        supportedFeatures = setOf(ServiceCapabilities.FEATURE_EMBEDDINGS),
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
        maxEmbeddingBatchInline = 64,
        maxEmbeddingBatchShm = 4096,
        maxEmbeddingBatchTotal = 4096,
        maxEmbeddingInputBytes = 512L * 1024,
        embeddingModelIds = listOf(EmbeddingModel.Default.id),
        embeddingDims = listOf(768, 512, 256, 128),
    )

    @Before fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        mockService = mockk(relaxed = true)
        every { mockService.capabilities } returns embeddingCaps
        mockConnection = mockk(relaxed = true)
        every { mockConnection.state } returns MutableStateFlow(ConnectionState.CONNECTED)
        io.mockk.coEvery { mockConnection.awaitConnected() } returns mockService
        mindlayer = buildMindlayer(mockConnection)
    }

    @After fun tearDown() = unmockkAll()

    @Test fun `embed retries once after service death then succeeds`() = runTest {
        // First binder hop dies; reconnect re-hands the same service and the
        // second hop succeeds.
        every { mockService.embed(any()) } throws DeadObjectException() andThen
            result(floatArrayOf(0.6f, 0.8f), dim = 2)

        val vector = mindlayer.embed("hello")

        assertArrayEquals(floatArrayOf(0.6f, 0.8f), vector, 0f)
        // Stale binder invalidated exactly once (one death observed).
        verify(exactly = 1) { mockConnection.reportBinderDeath(mockService) }
        // Initial attempt + one retry.
        verify(exactly = 2) { mockService.embed(any()) }
    }

    @Test fun `embed surfaces typed SERVICE_UNAVAILABLE when service stays dead`() = runTest {
        every { mockService.embed(any()) } throws DeadObjectException()

        try {
            mindlayer.embed("hello")
            fail("expected MindlayerException")
        } catch (e: MindlayerException) {
            assertEquals(MindlayerErrorCode.SERVICE_UNAVAILABLE, e.code)
            // Raw binder exception is attached as cause, never re-thrown raw.
            assertEquals(true, e.cause is DeadObjectException)
        }

        // Initial attempt + MAX_SERVICE_DEATH_RETRIES (1) retry, then give up.
        verify(exactly = 2) { mockService.embed(any()) }
    }

    @Test fun `non-idempotent call maps dead binder to typed error without retry`() = runTest {
        every { mockService.destroySession(any()) } throws DeadObjectException()

        try {
            mindlayer.destroySession("sess-1")
            fail("expected MindlayerException")
        } catch (e: MindlayerException) {
            assertEquals(MindlayerErrorCode.SERVICE_UNAVAILABLE, e.code)
        }

        // No retry for a non-idempotent op — invoked exactly once.
        verify(exactly = 1) { mockService.destroySession("sess-1") }
        verify(exactly = 1) { mockConnection.reportBinderDeath(mockService) }
    }

    @Test fun `generic RemoteException maps to typed error and is not retried`() = runTest {
        // A non-DeadObject RemoteException is treated as a transport failure
        // that may have applied — typed, but never auto-retried even on the
        // idempotent embed path.
        every { mockService.embed(any()) } throws RemoteException("transport boom")

        try {
            mindlayer.embed("hello")
            fail("expected MindlayerException")
        } catch (e: MindlayerException) {
            assertEquals(MindlayerErrorCode.SERVICE_UNAVAILABLE, e.code)
        }

        verify(exactly = 1) { mockService.embed(any()) }
    }

    private fun result(vector: FloatArray, dim: Int = vector.size): EmbeddingResult = EmbeddingResult(
        tag = null,
        vector = vector,
        dim = dim,
        modelId = EmbeddingModel.Default.id,
        tokenCount = 3,
        truncated = false,
        backend = "GPU",
        durationMs = 7L,
    )

    private fun buildMindlayer(conn: ConnectionManager): MindlayerImpl {
        val ctor = MindlayerImpl::class.java.getDeclaredConstructor(
            ConnectionManager::class.java,
            HistoryStore::class.java,
        )
        ctor.isAccessible = true
        return ctor.newInstance(conn, null)
    }
}
