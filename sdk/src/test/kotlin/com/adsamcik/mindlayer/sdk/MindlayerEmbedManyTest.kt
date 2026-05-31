package com.adsamcik.mindlayer.sdk

import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.adsamcik.mindlayer.EmbeddingBatchTransfer
import com.adsamcik.mindlayer.EmbeddingItemMetadata
import com.adsamcik.mindlayer.EmbeddingRequest
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the v0.7 → polish facade contract:
 *
 *  - `embedOne` and `embedMany` exist on the public surface.
 *  - The legacy `embed(text)`, `embedBatch`, `embedBatchLarge` are marked
 *    `@Deprecated(level=WARNING)` with `ReplaceWith` pointing at the new
 *    facades.
 *  - `embedMany` picks `Inline` for tiny batches and `SharedMemory` once
 *    the batch crosses the inline cap or the reply-byte budget.
 *  - `embedMany` falls back to `Inline` on API ≤ 26 (no `SharedMemory`),
 *    upgrading to `DeferredFallback` only when the batch is too big to
 *    fit inline at all.
 *
 * Anything more exotic (real-binder SHM smoke test, deferred-fallback
 * full E2E) is covered in MindlayerEmbedTest.
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

    // -- facade existence --------------------------------------------------

    @Test fun `embedOne is declared on Mindlayer`() {
        val embedOne = Mindlayer::class.java.declaredMethods.firstOrNull { it.name == "embedOne" }
        assertNotNull("embedOne must exist on the public Mindlayer surface", embedOne)
    }

    @Test fun `embedMany is declared on Mindlayer in both list and string overloads`() {
        val embedManyOverloads = Mindlayer::class.java.declaredMethods.filter { it.name == "embedMany" }
        assertEquals(
            "Expected exactly two embedMany overloads (List<EmbeddingConfig> + List<String>)",
            2,
            embedManyOverloads.size,
        )
    }

    // -- deprecation annotations ------------------------------------------

    @Test fun `embed(text) is Deprecated with ReplaceWith embedOne`() {
        // embed(text, Continuation) — the suspend overload taking a String first
        val method = Mindlayer::class.java.declaredMethods.first {
            it.name == "embed" &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == String::class.java
        }
        val deprecated = method.getAnnotation(Deprecated::class.java)
        assertNotNull("embed(text) must carry @Deprecated", deprecated)
        assertTrue(
            "ReplaceWith must point at embedOne",
            deprecated!!.replaceWith.expression.contains("embedOne"),
        )
    }

    @Test fun `embedBatch is Deprecated with ReplaceWith embedMany`() {
        val method = Mindlayer::class.java.declaredMethods.first { it.name == "embedBatch" }
        val deprecated = method.getAnnotation(Deprecated::class.java)
        assertNotNull("embedBatch must carry @Deprecated", deprecated)
        assertTrue(
            "ReplaceWith must point at embedMany",
            deprecated!!.replaceWith.expression.contains("embedMany"),
        )
    }

    @Test fun `embedBatchLarge is Deprecated with ReplaceWith embedMany`() {
        val method = Mindlayer::class.java.declaredMethods.first { it.name == "embedBatchLarge" }
        val deprecated = method.getAnnotation(Deprecated::class.java)
        assertNotNull("embedBatchLarge must carry @Deprecated", deprecated)
        assertTrue(
            "ReplaceWith must point at embedMany",
            deprecated!!.replaceWith.expression.contains("embedMany"),
        )
    }

    // -- embedOne behavior -------------------------------------------------

    @Test fun `embedOne returns FloatArray and forwards task plus modelId`() = runTest {
        val reqSlot = slot<EmbeddingRequest>()
        every { mockService.embed(capture(reqSlot)) } returns result(floatArrayOf(0.5f, 0.5f), dim = 2)

        val vector = mindlayer.embedOne(
            "hello",
            task = EmbeddingTask.RetrievalQuery,
            modelId = "embedding-gemma-300m-v1",
            outputDim = 256,
            tag = "row-7",
        )

        assertArrayEquals(floatArrayOf(0.5f, 0.5f), vector, 0f)
        assertEquals("hello", reqSlot.captured.text)
        assertEquals(com.adsamcik.mindlayer.EmbeddingTask.RETRIEVAL_QUERY, reqSlot.captured.taskType)
        assertEquals("embedding-gemma-300m-v1", reqSlot.captured.modelId)
        assertEquals(256, reqSlot.captured.outputDim)
        assertEquals("row-7", reqSlot.captured.tag)
    }

    // -- embedMany transport selection -------------------------------------

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

    // -- embedMany end-to-end routing --------------------------------------

    @Test fun `embedMany routes small batches through inline embedBatch and returns EmbeddingBatch`() = runTest {
        every { mockService.embedBatch(any()) } returns com.adsamcik.mindlayer.EmbeddingBatchResult(
            results = listOf(result(floatArrayOf(1f, 2f), dim = 2)),
            totalDurationMs = 17L,
            backend = "NPU",
        )

        val batch = mindlayer.embedMany(listOf(EmbeddingConfig("hello")))

        assertEquals(EmbeddingTransport.Inline, batch.transport)
        assertEquals(17L, batch.totalDurationMs)
        assertEquals("NPU", batch.backend)
        assertEquals(1, batch.results.size)
        assertArrayEquals(floatArrayOf(1f, 2f), batch.vectors.single(), 0f)
        verify(exactly = 1) { mockService.embedBatch(any()) }
        verify(exactly = 0) { mockService.embedBatchShm(any()) }
    }

    @Test fun `embedMany routes large batches through SharedMemory embedBatchShm`() = runTest {
        every { mockService.embedBatchShm(any()) } returns transfer(
            vectors = List(70) { floatArrayOf(it.toFloat(), it.toFloat()) },
            tags = List(70) { "t$it" },
        )

        val batch = mindlayer.embedMany(List(70) { EmbeddingConfig("x$it") })

        assertEquals(EmbeddingTransport.SharedMemory, batch.transport)
        assertEquals(70, batch.size)
        assertEquals("NPU", batch.backend)
        verify(exactly = 1) { mockService.embedBatchShm(any()) }
        verify(exactly = 0) { mockService.embedBatch(any()) }
    }

    @Test fun `embedMany string overload constructs configs with supplied task and modelId`() = runTest {
        val reqSlot = slot<List<EmbeddingRequest>>()
        every { mockService.embedBatch(capture(reqSlot)) } returns com.adsamcik.mindlayer.EmbeddingBatchResult(
            results = listOf(
                result(floatArrayOf(1f, 0f), dim = 2),
                result(floatArrayOf(0f, 1f), dim = 2),
            ),
            totalDurationMs = 1L,
            backend = "NPU",
        )

        mindlayer.embedMany(
            texts = listOf("hello", "world"),
            task = EmbeddingTask.RetrievalQuery,
            modelId = "embedding-gemma-300m-v1",
        )

        assertEquals(2, reqSlot.captured.size)
        assertEquals(com.adsamcik.mindlayer.EmbeddingTask.RETRIEVAL_QUERY, reqSlot.captured[0].taskType)
        assertEquals("embedding-gemma-300m-v1", reqSlot.captured[0].modelId)
        assertEquals("hello", reqSlot.captured[0].text)
        assertEquals("world", reqSlot.captured[1].text)
    }

    @Test fun `embedMany delegates to existing capability-gating and throws NOT_SUPPORTED on old services`() = runTest {
        every { mockService.capabilities } returns embeddingCaps.copy(supportedFeatures = emptySet())
        try {
            mindlayer.embedMany(listOf(EmbeddingConfig("hi")))
            org.junit.Assert.fail("expected MindlayerException")
        } catch (e: MindlayerException) {
            assertEquals(com.adsamcik.mindlayer.shared.MindlayerErrorCode.NOT_SUPPORTED, e.code)
        }
    }

    @Test fun `embedMany rejects empty items eagerly without binder hop`() = runTest {
        try {
            mindlayer.embedMany(emptyList<EmbeddingConfig>())
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        verify(exactly = 0) { mockService.embedBatch(any()) }
        verify(exactly = 0) { mockService.embedBatchShm(any()) }
    }

    @Test fun `EmbeddingBatch implements List delegation for ergonomic iteration`() = runTest {
        every { mockService.embedBatch(any()) } returns com.adsamcik.mindlayer.EmbeddingBatchResult(
            results = listOf(
                result(floatArrayOf(1f, 2f), dim = 2),
                result(floatArrayOf(3f, 4f), dim = 2),
            ),
            totalDurationMs = 5L,
            backend = "NPU",
        )

        val batch = mindlayer.embedMany(listOf(EmbeddingConfig("a"), EmbeddingConfig("b")))

        assertEquals(2, batch.size)
        assertEquals(2, batch.results.size)
        assertSame(batch[0], batch.results[0])
        val collected = batch.map { it.dim }
        assertEquals(listOf(2, 2), collected)
    }

    // -- test helpers ------------------------------------------------------

    private fun result(vector: FloatArray, dim: Int = vector.size): EmbeddingResult = EmbeddingResult(
        tag = null,
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
 * API-26 facet: [Mindlayer.embedMany] must NOT route through SharedMemory
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

    @Test fun `embedMany never calls embedBatchShm on API 26`() = runTest {
        every { mockService.embedBatch(any()) } returns com.adsamcik.mindlayer.EmbeddingBatchResult(
            results = listOf(result(floatArrayOf(1f, 2f), dim = 2)),
            totalDurationMs = 1L,
            backend = "CPU",
        )

        val batch = mindlayer.embedMany(listOf(EmbeddingConfig("hi")))

        assertEquals(EmbeddingTransport.Inline, batch.transport)
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
