package com.adsamcik.mindlayer.sdk

import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.adsamcik.mindlayer.EmbeddingBatchTransfer
import com.adsamcik.mindlayer.EmbeddingItemMetadata
import com.adsamcik.mindlayer.EmbeddingRequest as AidlEmbeddingRequest
import com.adsamcik.mindlayer.EmbeddingResult
import com.adsamcik.mindlayer.IMindlayerService
import com.adsamcik.mindlayer.ServiceCapabilities
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the canonical `embed { }` builder contract for batch shapes:
 *
 *  - `embed { text(...) }` forwards full per-item config (task, modelId,
 *    outputDim, tag) to the underlying AIDL request.
 *  - `embed { items(...) }` picks `Inline` for tiny batches and
 *    `SharedMemory` once the batch crosses the inline cap or the
 *    reply-byte budget.
 *  - `embed { items(...) }` falls back to `Inline` on API ≤ 26 (no
 *    `SharedMemory`), upgrading to `DeferredFallback` only when the
 *    batch is too big to fit inline at all.
 *
 * Transport selection itself is unit-tested via the internal
 * [MindlayerImpl.selectEmbeddingTransport] helper; the routing tests
 * here additionally pin the binder calls each transport actually makes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MindlayerEmbedManyApi33Test {
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
        coEvery { mockConnection.awaitConnected() } returns mockService
        mindlayer = buildMindlayer(mockConnection)
    }

    @After fun tearDown() = unmockkAll()

    // -- single-shot text(...) forwards every per-item knob ----------------

    @Test fun `embed text forwards task plus modelId plus outputDim plus tag`() = runTest {
        val reqSlot = slot<AidlEmbeddingRequest>()
        every { mockService.embed(capture(reqSlot)) } returns result(floatArrayOf(0.5f, 0.5f), dim = 2)

        val handle = mindlayer.embed {
            text(
                "hello",
                tag = "row-7",
                task = EmbeddingTask.RetrievalQuery,
                modelId = "embedding-gemma-300m-v1",
                outputDim = 256,
            )
        }
        val vector = (handle as EmbeddingHandle.Single).awaitVector()

        assertArrayEquals(floatArrayOf(0.5f, 0.5f), vector, 0f)
        assertEquals("hello", reqSlot.captured.text)
        assertEquals(com.adsamcik.mindlayer.EmbeddingTask.RETRIEVAL_QUERY, reqSlot.captured.taskType)
        assertEquals("embedding-gemma-300m-v1", reqSlot.captured.modelId)
        assertEquals(256, reqSlot.captured.outputDim)
        assertEquals("row-7", reqSlot.captured.tag)
    }

    // -- selectEmbeddingTransport routing ----------------------------------

    @Test fun `selectEmbeddingTransport picks Inline for tiny batches`() {
        val items = List(4) { EmbeddingConfig(text = "x$it") }
        val transport = mindlayer.selectEmbeddingTransport(embeddingCaps, items)
        assertEquals(EmbeddingTransport.Inline, transport)
    }

    @Test fun `selectEmbeddingTransport picks SharedMemory when batch exceeds inline cap`() {
        val items = List(65) { EmbeddingConfig(text = "x$it") }
        val transport = mindlayer.selectEmbeddingTransport(embeddingCaps, items)
        assertEquals(EmbeddingTransport.SharedMemory, transport)
    }

    @Test fun `selectEmbeddingTransport picks SharedMemory when estimated reply bytes exceeds inline budget`() {
        // 200 items × 768 dim × 4 bytes ≈ 614 KB → above the 512 KB inline budget.
        val items = List(200) { EmbeddingConfig(text = "x$it") }
        val capsWithHugeInline = embeddingCaps.copy(maxEmbeddingBatchInline = 4096)
        val transport = mindlayer.selectEmbeddingTransport(capsWithHugeInline, items)
        assertEquals(EmbeddingTransport.SharedMemory, transport)
    }

    @Test fun `selectEmbeddingTransport picks DeferredFallback when SHM cap is zero and batch too big for inline`() {
        val items = List(100) { EmbeddingConfig(text = "x$it") }
        val noShmCaps = embeddingCaps.copy(maxEmbeddingBatchShm = 0)
        val transport = mindlayer.selectEmbeddingTransport(noShmCaps, items)
        assertEquals(EmbeddingTransport.DeferredFallback, transport)
    }

    // -- embed { items(...) } end-to-end routing ---------------------------

    @Test fun `embed items routes small batches through inline embedBatch`() = runTest {
        every { mockService.embedBatch(any()) } returns com.adsamcik.mindlayer.EmbeddingBatchResult(
            results = listOf(result(floatArrayOf(1f, 2f), dim = 2)),
            totalDurationMs = 17L,
            backend = "NPU",
        )

        val handle = mindlayer.embed { items(listOf(EmbeddingItem("hello"))) }
        val results = (handle as EmbeddingHandle.Batch).awaitVectors()

        assertEquals(1, results.size)
        assertArrayEquals(floatArrayOf(1f, 2f), results.single().vector.data, 0f)
        verify(exactly = 1) { mockService.embedBatch(any()) }
        verify(exactly = 0) { mockService.embedBatchShm(any()) }
    }

    @Test fun `embed items routes large batches through SharedMemory embedBatchShm`() = runTest {
        every { mockService.embedBatchShm(any()) } returns transfer(
            vectors = List(70) { floatArrayOf(it.toFloat(), it.toFloat()) },
            tags = List(70) { "t$it" },
        )

        val handle = mindlayer.embed { items(List(70) { EmbeddingItem("x$it") }) }
        val results = (handle as EmbeddingHandle.Batch).awaitVectors()

        assertEquals(70, results.size)
        verify(exactly = 1) { mockService.embedBatchShm(any()) }
        verify(exactly = 0) { mockService.embedBatch(any()) }
    }

    @Test fun `embed items propagates per-item telemetry to EmbeddingResultItem`() = runTest {
        every { mockService.embedBatch(any()) } returns com.adsamcik.mindlayer.EmbeddingBatchResult(
            results = listOf(result(floatArrayOf(1f, 2f), dim = 2, tag = "row-1")),
            totalDurationMs = 17L,
            backend = "NPU",
        )

        val handle = mindlayer.embed { items(listOf(EmbeddingItem("hello", tag = "row-1"))) }
        val item = (handle as EmbeddingHandle.Batch).awaitVectors().single()

        assertEquals("row-1", item.tag)
        assertEquals(2, item.dim)
        assertEquals(EmbeddingModel.Default.id, item.modelId)
        assertEquals(3, item.tokenCount)
        assertEquals(false, item.truncated)
        assertEquals("NPU", item.backend)
        assertEquals(7L, item.durationMs)
    }

    @Test fun `embed items forwards per-item modelId and outputDim to the AIDL request`() = runTest {
        val reqSlot = slot<List<AidlEmbeddingRequest>>()
        every { mockService.embedBatch(capture(reqSlot)) } returns com.adsamcik.mindlayer.EmbeddingBatchResult(
            results = listOf(
                result(floatArrayOf(1f, 0f), dim = 2),
                result(floatArrayOf(0f, 1f), dim = 2),
            ),
            totalDurationMs = 1L,
            backend = "NPU",
        )

        mindlayer.embed {
            items(
                listOf(
                    EmbeddingItem(
                        text = "hello",
                        task = EmbeddingTask.RetrievalQuery,
                        modelId = "embedding-gemma-300m-v1",
                        outputDim = 256,
                    ),
                    EmbeddingItem(text = "world", task = EmbeddingTask.RetrievalQuery),
                ),
            )
        }

        assertEquals(2, reqSlot.captured.size)
        assertEquals(com.adsamcik.mindlayer.EmbeddingTask.RETRIEVAL_QUERY, reqSlot.captured[0].taskType)
        assertEquals("embedding-gemma-300m-v1", reqSlot.captured[0].modelId)
        assertEquals(256, reqSlot.captured[0].outputDim)
        assertEquals("hello", reqSlot.captured[0].text)
        assertEquals("world", reqSlot.captured[1].text)
    }

    @Test fun `embed items delegates to capability-gating and throws NOT_SUPPORTED on old services`() = runTest {
        every { mockService.capabilities } returns embeddingCaps.copy(supportedFeatures = emptySet())
        try {
            mindlayer.embed { items(listOf(EmbeddingItem("hi"))) }
            org.junit.Assert.fail("expected MindlayerException")
        } catch (e: MindlayerException) {
            assertEquals(com.adsamcik.mindlayer.shared.MindlayerErrorCode.NOT_SUPPORTED, e.code)
        }
    }

    @Test fun `embed items rejects empty items eagerly without binder hop`() = runTest {
        try {
            mindlayer.embed { items(emptyList()) }
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        verify(exactly = 0) { mockService.embedBatch(any()) }
        verify(exactly = 0) { mockService.embedBatchShm(any()) }
    }

    @Test fun `embed with neither text nor items throws INVALID_REQUEST`() = runTest {
        try {
            mindlayer.embed { }
            org.junit.Assert.fail("expected MindlayerException")
        } catch (e: MindlayerException) {
            assertEquals(com.adsamcik.mindlayer.shared.MindlayerErrorCode.INVALID_REQUEST, e.code)
        }
    }

    // -- test helpers ------------------------------------------------------

    private fun result(vector: FloatArray, dim: Int = vector.size, tag: String? = null): EmbeddingResult = EmbeddingResult(
        tag = tag,
        vector = vector,
        dim = dim,
        modelId = EmbeddingModel.Default.id,
        tokenCount = 3,
        truncated = false,
        backend = "NPU",
        durationMs = 7L,
    )

    private fun transfer(vectors: List<FloatArray>, tags: List<String?> = List(vectors.size) { null }): EmbeddingBatchTransfer {
        val count = vectors.size
        val dim = vectors.firstOrNull()?.size ?: 0
        val bytes = ByteBuffer.allocate(8 + count * dim * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.putInt(count)
        bytes.putInt(dim)
        vectors.forEach { v -> v.forEach { bytes.putFloat(it) } }
        val pipe = ParcelFileDescriptor.createPipe()
        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes.array()) }
        return EmbeddingBatchTransfer(
            pfd = pipe[0],
            count = count,
            dim = dim,
            modelId = EmbeddingModel.Default.id,
            perItemMetadata = tags.map { EmbeddingItemMetadata(tag = it, tokenCount = 5, truncated = false) },
            totalDurationMs = 11L,
            backend = "NPU",
        )
    }

    private fun buildMindlayer(conn: ConnectionManager): MindlayerImpl {
        val ctor = MindlayerImpl::class.java.getDeclaredConstructor(ConnectionManager::class.java, HistoryStore::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(conn, null)
    }
}

/**
 * API-26 facet: `embed { items(...) }` must NOT route through SharedMemory
 * even when the service advertises a non-zero `maxEmbeddingBatchShm` cap.
 * `android.os.SharedMemory` is API 27+; routing there on API 26 would
 * blow up at the binder boundary.
 *
 * Why a separate class? Robolectric's `@Config(sdk = [...])` controls
 * `Build.VERSION.SDK_INT` for the whole class. The default polish suite
 * runs on SDK 33; the API-26 case lives here in isolation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.O])
class MindlayerEmbedManyApi26Test {
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
        // A real service on API 26 would advertise 0 here; we set 4096 on
        // purpose to verify the SDK-side defense-in-depth gate.
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
        coEvery { mockConnection.awaitConnected() } returns mockService
        mindlayer = buildMindlayer(mockConnection)
    }

    @After fun tearDown() = unmockkAll()

    @Test fun `selectEmbeddingTransport picks Inline for small batch on API 26`() {
        val items = List(8) { EmbeddingConfig(text = "x$it") }
        val transport = mindlayer.selectEmbeddingTransport(embeddingCaps, items)
        assertEquals(EmbeddingTransport.Inline, transport)
    }

    @Test fun `selectEmbeddingTransport upgrades to DeferredFallback (not SharedMemory) on API 26 when batch is too large for inline`() {
        val items = List(100) { EmbeddingConfig(text = "x$it") }
        val transport = mindlayer.selectEmbeddingTransport(embeddingCaps, items)
        assertEquals(
            "API 26 has no SharedMemory; SDK must route to DeferredFallback rather than SHM",
            EmbeddingTransport.DeferredFallback,
            transport,
        )
    }

    @Test fun `embed items never calls embedBatchShm on API 26`() = runTest {
        every { mockService.embedBatch(any()) } returns com.adsamcik.mindlayer.EmbeddingBatchResult(
            results = listOf(result(floatArrayOf(1f, 2f), dim = 2)),
            totalDurationMs = 1L,
            backend = "CPU",
        )

        val handle = mindlayer.embed { items(listOf(EmbeddingItem("hi"))) }
        val results = (handle as EmbeddingHandle.Batch).awaitVectors()

        assertEquals(1, results.size)
        verify(exactly = 0) { mockService.embedBatchShm(any()) }
    }

    private fun result(vector: FloatArray, dim: Int = vector.size): EmbeddingResult = EmbeddingResult(
        tag = null,
        vector = vector,
        dim = dim,
        modelId = EmbeddingModel.Default.id,
        tokenCount = 3,
        truncated = false,
        backend = "CPU",
        durationMs = 4L,
    )

    private fun buildMindlayer(conn: ConnectionManager): MindlayerImpl {
        val ctor = MindlayerImpl::class.java.getDeclaredConstructor(ConnectionManager::class.java, HistoryStore::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(conn, null)
    }
}
