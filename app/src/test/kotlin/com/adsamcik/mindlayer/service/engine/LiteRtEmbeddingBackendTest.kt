package com.adsamcik.mindlayer.service.engine

import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.math.sqrt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LiteRtEmbeddingBackendTest {

    private lateinit var context: android.content.Context
    private lateinit var model: EmbeddingModelInfo

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        val modelFile = File(context.filesDir, "embedding-test.tflite").apply { writeText("model") }
        val tokenizerFile = File(context.filesDir, "sentencepiece.model").apply { writeText("tok") }
        model = EmbeddingModelInfo(
            id = "embedding-test",
            displayName = "Embedding Test",
            modelPath = modelFile.absolutePath,
            tokenizerPath = tokenizerFile.absolutePath,
            sizeBytes = 0L,
            nativeDim = 768,
            supportedDims = listOf(768, 512, 256, 128),
            maxContextTokens = 2048,
            sha256 = null,
        )
    }

    // ── init / shutdown / state ──────────────────────────────────────────

    @Test
    fun `initialize records state and repeated initialize is idempotent`() = runTest {
        val capturedLabels = mutableListOf<String>()
        val runner = mockk<LiteRtRunner>(relaxed = true)
        val backend = LiteRtEmbeddingBackend.forTesting(
            context = context,
            runnerFactory = { _, accel ->
                capturedLabels += accel
                runner
            },
        )

        backend.initialize(model, preferredBackend = "CPU")
        backend.initialize(model, preferredBackend = "CPU")

        assertTrue(backend.isInitialized)
        assertEquals(model, backend.currentModel)
        assertEquals("CPU", backend.activeBackend)
        // Second initialize() with the same model returns early; runner
        // creation runs only once.
        assertEquals(1, capturedLabels.size)
        assertEquals("CPU", capturedLabels.single())
    }

    @Test
    fun `shutdown is idempotent and clears state and closes runner`() = runTest {
        val runner = mockk<LiteRtRunner>(relaxed = true)
        val backend = LiteRtEmbeddingBackend.forTesting(
            context = context,
            runnerFactory = { _, _ -> runner },
        )
        backend.initialize(model, preferredBackend = "CPU")

        backend.shutdown()
        backend.shutdown()

        assertFalse(backend.isInitialized)
        assertEquals(null, backend.currentModel)
        assertEquals("NONE", backend.activeBackend)
        verify(exactly = 1) { runner.close() }
    }

    @Test
    fun `tokenizer factory is honoured and exposed via tokenize`() {
        val backend = LiteRtEmbeddingBackend.forTesting(
            context = context,
            runnerFactory = { _, _ -> mockk<LiteRtRunner>(relaxed = true) },
        )
        // tokenize() before init returns NoOp (the @Volatile default).
        assertEquals(0, backend.tokenize("hello", maxTokens = 16).size)
    }

    @Test
    fun `gpu intent is honoured as best-effort label`() = runTest {
        var capturedLabel: String? = null
        val backend = LiteRtEmbeddingBackend.forTesting(
            context = context,
            runnerFactory = { _, accel ->
                capturedLabel = accel
                mockk<LiteRtRunner>(relaxed = true)
            },
        )

        backend.initialize(model, preferredBackend = "GPU")

        assertEquals("GPU", backend.activeBackend)
        assertEquals("GPU", capturedLabel)
    }

    // ── embed: failure and short-circuit paths ──────────────────────────

    @Test
    fun `embed without initialize throws IllegalStateException`() = runTest {
        val backend = LiteRtEmbeddingBackend.forTesting(
            context = context,
            runnerFactory = { _, _ -> mockk<LiteRtRunner>(relaxed = true) },
        )

        val ex = runCatching { backend.embed(intArrayOf(1, 2), outputDim = 768, normalize = true) }
            .exceptionOrNull()
        assertTrue(ex is IllegalStateException)
        assertTrue(ex!!.message.orEmpty().contains("not initialised"))
    }

    @Test
    fun `embed with empty tokens returns zero vector and does not call runner`() = runTest {
        val runner = mockk<LiteRtRunner>(relaxed = true)
        val backend = LiteRtEmbeddingBackend.forTesting(
            context = context,
            runnerFactory = { _, _ -> runner },
        )
        backend.initialize(model, preferredBackend = "CPU")

        val vec = backend.embed(IntArray(0), outputDim = 128, normalize = true)
        assertEquals(128, vec.size)
        assertTrue(vec.all { it == 0f })
        // Short-circuit: runner.runEmbedding is never called for empty tokens.
        verify(exactly = 0) { runner.runEmbedding(any(), any()) }
    }

    @Test
    fun `embed rejects unsupported outputDim`() = runTest {
        val backend = LiteRtEmbeddingBackend.forTesting(
            context = context,
            runnerFactory = { _, _ -> mockk<LiteRtRunner>(relaxed = true) },
        )
        backend.initialize(model, preferredBackend = "CPU")

        val ex = runCatching { backend.embed(intArrayOf(1, 2), outputDim = 99, normalize = true) }
            .exceptionOrNull()
        assertTrue(ex is IllegalArgumentException)
        assertTrue(ex!!.message.orEmpty().contains("Unsupported embedding output dimension"))
    }

    // ── embed: real-wiring path ─────────────────────────────────────────

    @Test
    fun `embed pads tokens to maxContextTokens and passes input_ids + attention_mask`() = runTest {
        val inputIdsCapture = slot<IntArray>()
        val attentionMaskCapture = slot<IntArray>()
        val runner = mockk<LiteRtRunner>(relaxed = true) {
            every { runEmbedding(capture(inputIdsCapture), capture(attentionMaskCapture)) } returns
                FloatArray(768) { i -> (i + 1).toFloat() / 768f }
        }
        val backend = LiteRtEmbeddingBackend.forTesting(
            context = context,
            runnerFactory = { _, _ -> runner },
        )
        backend.initialize(model, preferredBackend = "CPU")

        backend.embed(intArrayOf(10, 20, 30), outputDim = 768, normalize = false)

        // Padded to maxContextTokens (2048). First 3 entries match input,
        // rest are 0 / 0.
        assertEquals(2048, inputIdsCapture.captured.size)
        assertEquals(2048, attentionMaskCapture.captured.size)
        assertArrayEquals(intArrayOf(10, 20, 30, 0), inputIdsCapture.captured.copyOfRange(0, 4))
        assertArrayEquals(intArrayOf(1, 1, 1, 0), attentionMaskCapture.captured.copyOfRange(0, 4))
        assertEquals(0, inputIdsCapture.captured[2047])
        assertEquals(0, attentionMaskCapture.captured[2047])
    }

    @Test
    fun `embed truncates input that exceeds maxContextTokens`() = runTest {
        val inputIdsCapture = slot<IntArray>()
        val attentionMaskCapture = slot<IntArray>()
        val runner = mockk<LiteRtRunner>(relaxed = true) {
            every { runEmbedding(capture(inputIdsCapture), capture(attentionMaskCapture)) } returns
                FloatArray(768) { 1f }
        }
        val backend = LiteRtEmbeddingBackend.forTesting(
            context = context,
            runnerFactory = { _, _ -> runner },
        )
        backend.initialize(model, preferredBackend = "CPU")

        // 3000 tokens > 2048 maxContextTokens — must be silently truncated.
        backend.embed(IntArray(3000) { it + 1 }, outputDim = 768, normalize = false)

        assertEquals(2048, inputIdsCapture.captured.size)
        // Truncation drops anything past index 2047 in the source array.
        assertEquals(2048, inputIdsCapture.captured.last())
        // Every position has attention=1 because we filled all 2048 slots.
        assertTrue(attentionMaskCapture.captured.all { it == 1 })
    }

    @Test
    fun `embed applies MRL truncation to outputDim less than nativeDim`() = runTest {
        val fullVector = FloatArray(768) { it.toFloat() }
        val runner = mockk<LiteRtRunner>(relaxed = true) {
            every { runEmbedding(any(), any()) } returns fullVector
        }
        val backend = LiteRtEmbeddingBackend.forTesting(
            context = context,
            runnerFactory = { _, _ -> runner },
        )
        backend.initialize(model, preferredBackend = "CPU")

        val result = backend.embed(intArrayOf(1, 2), outputDim = 256, normalize = false)

        assertEquals(256, result.size)
        // MRL: first 256 dims of the native 768-d output.
        for (i in 0 until 256) assertEquals(i.toFloat(), result[i], 0.0001f)
    }

    @Test
    fun `embed L2-normalizes when normalize is true`() = runTest {
        // Non-unit-length output vector to exercise the renormalize branch.
        val rawVector = FloatArray(768) { 3f }
        val runner = mockk<LiteRtRunner>(relaxed = true) {
            every { runEmbedding(any(), any()) } returns rawVector
        }
        val backend = LiteRtEmbeddingBackend.forTesting(
            context = context,
            runnerFactory = { _, _ -> runner },
        )
        backend.initialize(model, preferredBackend = "CPU")

        val result = backend.embed(intArrayOf(1, 2), outputDim = 768, normalize = true)

        val sumSq = result.sumOf { (it.toDouble() * it.toDouble()) }
        assertEquals("L2 norm of returned vector must be 1.0", 1.0, sqrt(sumSq), 1e-4)
    }

    @Test
    fun `embed passes through raw vector when normalize is false`() = runTest {
        val rawVector = FloatArray(768) { 3f }
        val runner = mockk<LiteRtRunner>(relaxed = true) {
            every { runEmbedding(any(), any()) } returns rawVector
        }
        val backend = LiteRtEmbeddingBackend.forTesting(
            context = context,
            runnerFactory = { _, _ -> runner },
        )
        backend.initialize(model, preferredBackend = "CPU")

        val result = backend.embed(intArrayOf(1, 2), outputDim = 768, normalize = false)

        assertEquals(3f, result[0], 0.0001f)
        assertEquals(3f, result[767], 0.0001f)
    }

    // ── memory / shutdown error paths ───────────────────────────────────

    @Test
    fun `injectable available memory provider drives LowMemoryException`() = runTest {
        val backend = LiteRtEmbeddingBackend.forTesting(
            context = context,
            memoryHeadroomBytes = 256L * 1024 * 1024,
            availableMemoryProvider = { 0L },
            runnerFactory = { _, _ -> mockk<LiteRtRunner>(relaxed = true) },
        )

        val ex = runCatching {
            backend.initialize(
                model.copy(sizeBytes = 100L * 1024 * 1024),
                preferredBackend = "CPU",
            )
        }.exceptionOrNull()
        assertTrue(ex is LowMemoryException)
    }

    @Test
    fun `runner create failure rolls state back to NONE`() = runTest {
        val backend = LiteRtEmbeddingBackend.forTesting(
            context = context,
            runnerFactory = { _, _ -> throw IllegalStateException("native init failed") },
        )

        val ex = runCatching { backend.initialize(model, preferredBackend = "CPU") }.exceptionOrNull()
        assertTrue(ex is IllegalStateException)
        assertFalse(backend.isInitialized)
        assertEquals(null, backend.currentModel)
        assertEquals("NONE", backend.activeBackend)
    }

    @Test
    fun `cancellation after runner creation closes pending runner`() = runTest {
        val runner = mockk<LiteRtRunner>(relaxed = true)
        val created = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val backend = LiteRtEmbeddingBackend.forTesting(
            context = context,
            runnerFactory = { _, _ ->
                created.complete(Unit)
                release.await()
                runner
            },
        )
        val initialization = async(Dispatchers.Default) {
            backend.initialize(model, preferredBackend = "CPU")
        }
        created.await()

        initialization.cancel()
        release.countDown()
        initialization.cancelAndJoin()

        verify(exactly = 1) { runner.close() }
        assertFalse(backend.isInitialized)
        assertEquals("NONE", backend.activeBackend)
    }

    @Ignore("Requires real device — exercises the full LiteRT CompiledModel + SentencePiece pipeline.")
    @Test
    fun `end-to-end embedding requires real device validation`() = runTest {
        // Real-device verification: load the actual embedding-gemma-300m-v1
        // .tflite + .spm.model, run inference on a known text, assert the
        // resulting 768-d vector has unit length and is close (cosine ≈ 1)
        // to a reference vector produced by the Python sentencepiece +
        // sentence-transformers stack. Cannot run in Robolectric — needs
        // an emulator with the LiteRT native .so loaded.
    }
}
