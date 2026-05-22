package com.adsamcik.mindlayer.sdk

import android.os.ParcelFileDescriptor
import android.util.Log
import app.cash.turbine.test
import com.adsamcik.mindlayer.CancelResult
import com.adsamcik.mindlayer.DeferredHandle
import com.adsamcik.mindlayer.DeferredResult
import com.adsamcik.mindlayer.EmbeddingBatchTransfer
import com.adsamcik.mindlayer.EmbeddingItemMetadata
import com.adsamcik.mindlayer.EmbeddingRequest
import com.adsamcik.mindlayer.EmbeddingResult
import com.adsamcik.mindlayer.IClientCallback
import com.adsamcik.mindlayer.IMindlayerService
import com.adsamcik.mindlayer.ServiceCapabilities
import com.adsamcik.mindlayer.VectorBlobHandle
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MindlayerEmbedTest {
    private lateinit var mockService: IMindlayerService
    private lateinit var mockConnection: ConnectionManager
    private lateinit var mindlayer: Mindlayer

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

    @Test fun `embed text calls service embed with retrieval document and normalize true`() = runTest {
        val reqSlot = slot<EmbeddingRequest>()
        every { mockService.embed(capture(reqSlot)) } returns result(floatArrayOf(1f, 0f), dim = 2)

        val vector = mindlayer.embed("hello")

        assertArrayEquals(floatArrayOf(1f, 0f), vector, 0f)
        assertEquals("hello", reqSlot.captured.text)
        assertEquals(com.adsamcik.mindlayer.EmbeddingTask.RETRIEVAL_DOCUMENT, reqSlot.captured.taskType)
        assertTrue(reqSlot.captured.normalize)
    }

    @Test fun `embedBatch over inline limit throws before embedding binder hop`() = runTest {
        try {
            mindlayer.embedBatch(List(65) { EmbeddingConfig(text = "x$it") })
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        verify(exactly = 0) { mockService.embedBatch(any()) }
    }

    @Test fun `embedBatchLarge parses shared-memory blob into results`() = runTest {
        every { mockService.embedBatchShm(any()) } returns transfer(
            vectors = listOf(floatArrayOf(1f, 2f), floatArrayOf(3f, 4f)),
            tags = listOf("a", "b"),
        )

        val results = mindlayer.embedBatchLarge(listOf(EmbeddingConfig("a"), EmbeddingConfig("b")))

        assertEquals(2, results.size)
        assertEquals("a", results[0].tag)
        assertArrayEquals(floatArrayOf(1f, 2f), results[0].vector, 0f)
        assertEquals("b", results[1].tag)
        assertArrayEquals(floatArrayOf(3f, 4f), results[1].vector, 0f)
        assertEquals("NPU", results[0].backend)
    }

    @Test fun `embedBatchLarge falls back to inline when shared memory cap is zero`() = runTest {
        every { mockService.capabilities } returns embeddingCaps.copy(maxEmbeddingBatchShm = 0)
        every { mockService.embedBatch(any()) } returns com.adsamcik.mindlayer.EmbeddingBatchResult(
            results = listOf(result(floatArrayOf(5f, 6f), dim = 2)),
            totalDurationMs = 8L,
            backend = "NPU",
        )

        val results = mindlayer.embedBatchLarge(listOf(EmbeddingConfig("inline")))

        assertArrayEquals(floatArrayOf(5f, 6f), results.single().vector, 0f)
        verify(exactly = 0) { mockService.embedBatchShm(any()) }
        verify(exactly = 1) { mockService.embedBatch(any()) }
    }

    @Test fun `embedBatchLarge falls back to deferred when shared memory cap is zero and batch exceeds inline cap`() = runTest {
        every {
            mockService.capabilities
        } returns embeddingCaps.copy(maxEmbeddingBatchInline = 1, maxEmbeddingBatchShm = 0, maxEmbeddingBatchTotal = 2)
        every { mockService.embedBatchDeferred(any()) } returns DeferredHandle(requestId = "emb-fallback", expiresAtMs = 1_000L)
        every { mockService.fetchEmbeddingBatchResult("emb-fallback") } returns VectorBlobHandle(
            status = DeferredResult.READY,
            transfer = transfer(listOf(floatArrayOf(7f, 8f), floatArrayOf(9f, 10f))),
        )

        val results = mindlayer.embedBatchLarge(listOf(EmbeddingConfig("a"), EmbeddingConfig("b")))

        assertEquals(2, results.size)
        assertArrayEquals(floatArrayOf(7f, 8f), results[0].vector, 0f)
        verify(exactly = 0) { mockService.embedBatchShm(any()) }
        verify(exactly = 1) { mockService.embedBatchDeferred(any()) }
    }

    @Test fun `old service without embeddings capability throws NOT_SUPPORTED`() = runTest {
        every { mockService.capabilities } returns embeddingCaps.copy(supportedFeatures = emptySet())
        try {
            mindlayer.embed("hello")
            fail("expected MindlayerException")
        } catch (e: MindlayerException) {
            assertEquals(MindlayerErrorCode.NOT_SUPPORTED, e.code)
        }
    }

    @Test fun `embedBatchDeferred returns sdk handle`() = runTest {
        every { mockService.embedBatchDeferred(any()) } returns DeferredHandle(requestId = "emb-1", expiresAtMs = 123L)
        val handle = mindlayer.embedBatchDeferred(listOf(EmbeddingConfig("a")))
        assertEquals("emb-1", handle.requestId)
        assertEquals(123L, handle.expiresAtMs)
    }

    @Test fun `fetchEmbeddingBatch maps vector blob statuses`() = runTest {
        val handle = EmbeddingBatchHandle("rid", 0L)
        every { mockService.fetchEmbeddingBatchResult("rid") } returns VectorBlobHandle(status = DeferredResult.STILL_RUNNING)
        assertTrue(mindlayer.fetchEmbeddingBatch(handle) is EmbeddingBatchOutcome.StillRunning)

        every { mockService.fetchEmbeddingBatchResult("rid") } returns VectorBlobHandle(status = DeferredResult.FAILED, errorCodeInt = 42, errorCodeName = "boom")
        val failed = mindlayer.fetchEmbeddingBatch(handle) as EmbeddingBatchOutcome.Failed
        assertEquals(42, failed.errorCode)
        assertEquals("boom", failed.errorName)

        every { mockService.fetchEmbeddingBatchResult("rid") } returns VectorBlobHandle(status = DeferredResult.CANCELLED)
        assertTrue(mindlayer.fetchEmbeddingBatch(handle) is EmbeddingBatchOutcome.Cancelled)
        every { mockService.fetchEmbeddingBatchResult("rid") } returns VectorBlobHandle(status = DeferredResult.EXPIRED)
        assertTrue(mindlayer.fetchEmbeddingBatch(handle) is EmbeddingBatchOutcome.Expired)
        every { mockService.fetchEmbeddingBatchResult("rid") } returns VectorBlobHandle(status = DeferredResult.NOT_FOUND_OR_NOT_OWNED)
        assertTrue(mindlayer.fetchEmbeddingBatch(handle) is EmbeddingBatchOutcome.NotFound)

        every { mockService.fetchEmbeddingBatchResult("rid") } returns VectorBlobHandle(status = DeferredResult.READY, transfer = transfer(listOf(floatArrayOf(9f, 8f))))
        val ready = mindlayer.fetchEmbeddingBatch(handle) as EmbeddingBatchOutcome.Ready
        assertEquals(1, ready.results.size)
        assertArrayEquals(floatArrayOf(9f, 8f), ready.results.single().vector, 0f)
    }

    @Test fun `cancelEmbed and cancelEmbeddingBatch map cancel constants`() = runTest {
        every { mockService.cancelEmbed("e1") } returns CancelResult(outcome = CancelResult.CANCELLED)
        assertEquals(EmbeddingCancelResult.Cancelled, mindlayer.cancelEmbed("e1"))
        every { mockService.cancelEmbed("e1") } returns CancelResult(outcome = CancelResult.ALREADY_FINISHED)
        assertEquals(EmbeddingCancelResult.AlreadyFinished, mindlayer.cancelEmbed("e1"))
        every { mockService.cancelEmbeddingBatch("b1") } returns CancelResult(outcome = CancelResult.UNKNOWN)
        assertEquals(EmbeddingCancelResult.Unknown, mindlayer.cancelEmbeddingBatch(EmbeddingBatchHandle("b1", 0L)))
    }

    @Test fun `embedding batch completions emits callback request id`() = runTest {
        val callback = getCallback(mindlayer)
        mindlayer.embeddingBatchCompletions().test {
            callback.onEmbeddingBatchComplete("emb-done")
            assertEquals("emb-done", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `NoSuchMethodError at embedding binder stub becomes NOT_SUPPORTED`() = runTest {
        every { mockService.embed(any()) } throws NoSuchMethodError("embed")
        try {
            mindlayer.embed("hello")
            fail("expected MindlayerException")
        } catch (e: MindlayerException) {
            assertEquals(MindlayerErrorCode.NOT_SUPPORTED, e.code)
        }
    }

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

    private fun getCallback(mindlayer: Mindlayer): IClientCallback {
        val getter = Mindlayer::class.java.getDeclaredMethod("getEvictionCallback")
        getter.isAccessible = true
        return getter.invoke(mindlayer) as IClientCallback
    }

    private fun buildMindlayer(conn: ConnectionManager): Mindlayer {
        val ctor = Mindlayer::class.java.getDeclaredConstructor(ConnectionManager::class.java, HistoryStore::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(conn, null)
    }
}
