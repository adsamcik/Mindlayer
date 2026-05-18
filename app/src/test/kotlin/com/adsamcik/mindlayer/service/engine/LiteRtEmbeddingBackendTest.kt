package com.adsamcik.mindlayer.service.engine

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

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

    @Test
    fun `initialize records state and repeated initialize is idempotent`() = runTest {
        val backend = LiteRtEmbeddingBackend(context, memoryHeadroomBytes = 0L)

        backend.initialize(model, preferredBackend = "CPU")
        backend.initialize(model, preferredBackend = "CPU")

        assertTrue(backend.isInitialized)
        assertEquals(model, backend.currentModel)
        assertEquals("CPU", backend.activeBackend)
    }

    @Test
    fun `shutdown is idempotent and clears state`() = runTest {
        val backend = LiteRtEmbeddingBackend(context, memoryHeadroomBytes = 0L)
        backend.initialize(model, preferredBackend = "CPU")

        backend.shutdown()
        backend.shutdown()

        assertFalse(backend.isInitialized)
        assertEquals(null, backend.currentModel)
        assertEquals("NONE", backend.activeBackend)
    }

    @Test
    fun `tokenizer stub returns empty token array`() {
        val backend = LiteRtEmbeddingBackend(context, memoryHeadroomBytes = 0L)

        assertEquals(0, backend.tokenize("hello", maxTokens = 16).size)
    }

    @Test
    fun `gpu intent is honoured as best-effort label`() = runTest {
        val backend = LiteRtEmbeddingBackend(
            context,
            memoryHeadroomBytes = 0L,
            availableMemoryProvider = { Long.MAX_VALUE },
        )

        backend.initialize(model, preferredBackend = "GPU")

        // Best-effort intent: the runtime label reflects what the caller
        // asked for. Actual delegate fallback at TODO(verifyOnDevice) time
        // will overwrite this if GPU init fails on the device.
        assertEquals("GPU", backend.activeBackend)
    }

    @Test
    fun `embed without initialize throws IllegalStateException`() = runTest {
        val backend = LiteRtEmbeddingBackend(context, memoryHeadroomBytes = 0L)

        val ex = runCatching { backend.embed(intArrayOf(1, 2), outputDim = 768, normalize = true) }
            .exceptionOrNull()
        assertTrue(ex is IllegalStateException)
        assertTrue(ex!!.message.orEmpty().contains("not initialised"))
    }

    @Test
    fun `embed after initialize fails closed with verify-on-device guidance`() = runTest {
        val backend = LiteRtEmbeddingBackend(context, memoryHeadroomBytes = 0L)
        backend.initialize(model, preferredBackend = "CPU")

        val ex = runCatching { backend.embed(intArrayOf(1, 2), outputDim = 768, normalize = true) }
            .exceptionOrNull()
        assertTrue(ex is IllegalStateException)
        // The fail-closed contract: precise message that points the next
        // engineer at the documented gap. Symmetry with
        // LiteRtPaddleOcrBackend.recognise().
        val msg = ex!!.message.orEmpty()
        assertTrue(msg.contains("LiteRT embed()"))
        assertTrue(msg.contains("verifyOnDevice"))
    }

    @Test
    fun `embed with empty tokens returns zero vector and does not throw`() = runTest {
        val backend = LiteRtEmbeddingBackend(context, memoryHeadroomBytes = 0L)
        backend.initialize(model, preferredBackend = "CPU")

        val vec = backend.embed(IntArray(0), outputDim = 128, normalize = true)
        assertEquals(128, vec.size)
        assertTrue(vec.all { it == 0f })
    }

    @Test
    fun `injectable available memory provider drives LowMemoryException`() = runTest {
        val backend = LiteRtEmbeddingBackend(
            context,
            memoryHeadroomBytes = 256L * 1024 * 1024,
            availableMemoryProvider = { 0L },
        )

        val ex = runCatching {
            backend.initialize(
                model.copy(sizeBytes = 100L * 1024 * 1024),
                preferredBackend = "CPU",
            )
        }.exceptionOrNull()
        assertTrue(ex is LowMemoryException)
    }

    @Ignore("Requires real device — see Phase A README")
    @Test
    fun `embedding path requires real LiteRT model validation`() = runTest {
        val backend = LiteRtEmbeddingBackend(context, memoryHeadroomBytes = 0L)
        backend.initialize(model, preferredBackend = "CPU")
        backend.embed(intArrayOf(1, 2), outputDim = 768, normalize = true)
    }
}


