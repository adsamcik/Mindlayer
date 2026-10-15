package com.adsamcik.mindlayer.sdk

import android.os.ParcelFileDescriptor
import android.util.Log
import app.cash.turbine.test
import com.adsamcik.mindlayer.CancelResult
import com.adsamcik.mindlayer.DeferredHandle
import com.adsamcik.mindlayer.DeferredResult
import com.adsamcik.mindlayer.EmbeddingBatchTransfer
import com.adsamcik.mindlayer.EmbeddingItemMetadata
import com.adsamcik.mindlayer.EmbeddingRequest as AidlEmbeddingRequest
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Covers the canonical `embed { }` builder path (single-shot) and the
 * deferred-batch + completion / cancel side surfaces that the canonical
 * facade composes on top of. Transport-selection routing for `embed { items(...) }`
 * lives in [MindlayerEmbedManyApi33Test] / [MindlayerEmbedManyApi26Test].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MindlayerEmbedTest {
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
        every { mockConnection.getService() } returns mockService
        mindlayer = buildMindlayer(mockConnection)
    }

    @After fun tearDown() = unmockkAll()

    @Test fun `embed text calls service embed with retrieval document and normalize true`() = runTest {
        val reqSlot = slot<AidlEmbeddingRequest>()
        every { mockService.embed(capture(reqSlot)) } returns result(floatArrayOf(1f, 0f), dim = 2)

        val handle = mindlayer.embed { text("hello") }
        val vector = (handle as EmbeddingHandle.Single).awaitVector()

        assertArrayEquals(floatArrayOf(1f, 0f), vector, 0f)
        assertEquals("hello", reqSlot.captured.text)
        assertEquals(com.adsamcik.mindlayer.EmbeddingTask.RETRIEVAL_DOCUMENT, reqSlot.captured.taskType)
        assertTrue(reqSlot.captured.normalize)
    }

    @Test fun `old service without embeddings capability throws NOT_SUPPORTED`() = runTest {
        every { mockService.capabilities } returns embeddingCaps.copy(supportedFeatures = emptySet())
        try {
            mindlayer.embed { text("hello") }
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

    @Test fun `canonical deferred single preserves configuration and metadata without inline computation`() = runTest {
        val submitted = slot<List<AidlEmbeddingRequest>>()
        every { mockService.embedBatchDeferred(capture(submitted)) } returns DeferredHandle(requestId = "emb-canonical", expiresAtMs = 123L)
        every { mockService.fetchEmbeddingBatchResult("emb-canonical") } answers {
            VectorBlobHandle(status = DeferredResult.READY, transfer = transfer(listOf(floatArrayOf(1f, 2f)), listOf("tag")))
        }
        val handle = mindlayer.embed {
            text("hello", tag = "tag", task = EmbeddingTask.RetrievalQuery, outputDim = 128, normalize = false)
            deferred()
        } as EmbeddingHandle.Deferred
        assertEquals("emb-canonical", handle.requestId)
        assertEquals(com.adsamcik.mindlayer.EmbeddingTask.RETRIEVAL_QUERY, submitted.captured.single().taskType)
        assertEquals(128, submitted.captured.single().outputDim)
        assertEquals(false, submitted.captured.single().normalize)
        val results = handle.awaitReady()
        assertEquals("tag", results.single().tag)
        assertEquals(5, results.single().tokenCount)
        assertEquals("NPU", results.single().backend)
        assertArrayEquals(floatArrayOf(1f, 2f), results.single().vector.data, 0f)
        handle.awaitReady()
        verify(exactly = 1) { mockService.fetchEmbeddingBatchResult("emb-canonical") }
        verify(exactly = 0) { mockService.embed(any()) }
        verify(exactly = 0) { mockService.acknowledgeEmbeddingBatchResult(any()) }
        handle.acknowledge()
        verify(exactly = 1) { mockService.acknowledgeEmbeddingBatchResult("emb-canonical") }
        handle.close()
    }

    @Test fun `deadline computes through cancellable deferred transport and preserves single result shape`() = runTest {
        every { mockService.embedBatchDeferred(any()) } returns DeferredHandle(requestId = "emb-bounded", expiresAtMs = 123L)
        every { mockService.fetchEmbeddingBatchResult("emb-bounded") } answers {
            VectorBlobHandle(status = DeferredResult.READY, transfer = transfer(listOf(floatArrayOf(3f, 4f))))
        }
        val handle = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            mindlayer.embed { text("hello"); deadline(10.seconds) }
        }
        assertArrayEquals(floatArrayOf(3f, 4f), (handle as EmbeddingHandle.Single).awaitVector(), 0f)
        verify(exactly = 1) { mockService.acknowledgeEmbeddingBatchResult("emb-bounded") }
        verify(exactly = 0) { mockService.embed(any()) }
    }

    @Test fun `canonical deferred batch preserves item ordering and correlation`() = runTest {
        every { mockService.embedBatchDeferred(any()) } returns DeferredHandle(requestId = "emb-batch", expiresAtMs = 123L)
        every { mockService.fetchEmbeddingBatchResult("emb-batch") } answers {
            VectorBlobHandle(
                status = DeferredResult.READY,
                transfer = transfer(listOf(floatArrayOf(1f, 2f), floatArrayOf(3f, 4f)), listOf("first", "second")),
            )
        }
        val handle = mindlayer.embed {
            items(listOf(EmbeddingItem("one", "first"), EmbeddingItem("two", "second")))
            deferred()
        } as EmbeddingHandle.Deferred
        val results = handle.awaitReady()
        assertEquals(listOf("first", "second"), results.map { it.tag })
        assertArrayEquals(floatArrayOf(3f, 4f), results[1].vector.data, 0f)
        handle.acknowledge()
        handle.close()
    }

    @Test fun `cancellation during submission disposes the returned durable request id`() = runTest {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val cleaned = kotlinx.coroutines.CompletableDeferred<Unit>()
        every { mockService.embedBatchDeferred(any()) } answers {
            entered.complete(Unit)
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            DeferredHandle(requestId = "emb-cancel-submit", expiresAtMs = 123L)
        }
        every { mockService.acknowledgeEmbeddingBatchResult("emb-cancel-submit") } answers { cleaned.complete(Unit); Unit }
        val submitting = launch(kotlinx.coroutines.Dispatchers.Default) { mindlayer.embed { text("hello"); deferred() } }
        try {
            entered.await()
            submitting.cancel()
        } finally {
            release.countDown()
        }
        submitting.join()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(5.seconds) { cleaned.await() }
        }
        verify(exactly = 1) { mockService.cancelEmbeddingBatch("emb-cancel-submit") }
        verify(exactly = 1) { mockService.acknowledgeEmbeddingBatchResult("emb-cancel-submit") }
    }

    @Test fun `expired deadline does not submit or compute and invalid durations are rejected`() = runTest {
        val failure = runCatching { mindlayer.embed { text("hello"); deadline(1.nanoseconds) } }.exceptionOrNull()
        assertTrue(failure is kotlinx.coroutines.TimeoutCancellationException)
        for (invalid in listOf(Duration.ZERO, -1.seconds)) {
            assertTrue(runCatching { mindlayer.embed { text("hello"); deadline(invalid) } }.exceptionOrNull() is IllegalArgumentException)
        }
        verify(exactly = 0) { mockService.embedBatchDeferred(any()) }
        verify(exactly = 0) { mockService.embed(any()) }
    }

    @Test fun `deadline also bounds acknowledgement when the connection stalls after fetching`() = runTest {
        val fetched = java.util.concurrent.atomic.AtomicBoolean(false)
        val cancelled = kotlinx.coroutines.CompletableDeferred<Unit>()
        every { mockService.embedBatchDeferred(any()) } returns DeferredHandle(requestId = "emb-ack", expiresAtMs = 123L)
        every { mockService.fetchEmbeddingBatchResult("emb-ack") } answers {
            fetched.set(true)
            VectorBlobHandle(status = DeferredResult.READY, transfer = transfer(listOf(floatArrayOf(1f, 2f))))
        }
        coEvery { mockConnection.awaitConnected() } coAnswers {
            if (fetched.get()) kotlinx.coroutines.awaitCancellation() else mockService
        }
        every { mockService.cancelEmbeddingBatch("emb-ack") } answers {
            cancelled.complete(Unit)
            CancelResult(outcome = CancelResult.CANCELLED)
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val failure = runCatching { mindlayer.embed { text("hello"); deadline(500.milliseconds) } }.exceptionOrNull()
            assertTrue(failure is kotlinx.coroutines.TimeoutCancellationException)
            assertTrue(fetched.get())
            kotlinx.coroutines.withTimeout(5.seconds) { cancelled.await() }
        }
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
            mindlayer.embed { text("hello") }
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

    private fun getCallback(mindlayer: MindlayerImpl): IClientCallback {
        val getter = MindlayerImpl::class.java.getDeclaredMethod("getEvictionCallback")
        getter.isAccessible = true
        return getter.invoke(mindlayer) as IClientCallback
    }

    private fun buildMindlayer(conn: ConnectionManager): MindlayerImpl {
        val ctor = MindlayerImpl::class.java.getDeclaredConstructor(ConnectionManager::class.java, HistoryStore::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(conn, null)
    }
}
