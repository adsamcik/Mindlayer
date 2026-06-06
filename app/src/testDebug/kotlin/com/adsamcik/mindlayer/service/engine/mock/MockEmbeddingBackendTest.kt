package com.adsamcik.mindlayer.service.engine.mock

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Determinism + distinguishability contract for [MockEmbeddingBackend].
 *
 * Pure-JVM; the backend has no Android dependencies.
 */
class MockEmbeddingBackendTest {

    private val backend = MockEmbeddingBackend()

    @Test
    fun `reports initialized with a fixed model`() {
        assertTrue(backend.isInitialized)
        assertEquals("mock-embeddinggemma", backend.currentModel.id)
        assertEquals(768, backend.currentModel.nativeDim)
        assertTrue(768 in backend.currentModel.supportedDims)
    }

    @Test
    fun `same text yields identical vector`() = runBlocking {
        val a = backend.embed(backend.tokenize("receipt total 12.34", 2048), null, true)
        val b = backend.embed(backend.tokenize("receipt total 12.34", 2048), null, true)
        assertEquals(768, a.size)
        assertEquals(a.toList(), b.toList())
        assertEquals(1.0, cosine(a, b), 1e-6)
    }

    @Test
    fun `different text yields distinguishable vectors`() = runBlocking {
        val a = backend.embed(backend.tokenize("the quick brown fox", 2048), null, true)
        val b = backend.embed(backend.tokenize("entirely different content here", 2048), null, true)
        val cos = cosine(a, b)
        assertTrue("cosine $cos should be < 0.99 for distinct inputs", cos < 0.99)
    }

    @Test
    fun `normalized vector is unit length`() = runBlocking {
        val v = backend.embed(backend.tokenize("normalize me", 2048), null, true)
        val norm = sqrt(v.fold(0.0) { acc, x -> acc + x.toDouble() * x.toDouble() })
        assertEquals(1.0, norm, 1e-5)
    }

    @Test
    fun `outputDim selects matryoshka prefix`() = runBlocking {
        val tokens = backend.tokenize("dimension test", 2048)
        val full = backend.embed(tokens, null, false)
        val half = backend.embed(tokens, 256, false)
        assertEquals(768, full.size)
        assertEquals(256, half.size)
        // Smaller dim is the prefix of the native vector (pre-normalization).
        for (i in 0 until 256) {
            assertEquals(full[i], half[i], 1e-6f)
        }
    }

    @Test
    fun `tokenize is content sensitive and capped`() {
        assertNotEquals(backend.tokenize("abc", 2048).toList(), backend.tokenize("abd", 2048).toList())
        assertEquals(3, backend.tokenize("xyz", 2048).size)
        assertEquals(2, backend.tokenize("hello", 2).size)
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i].toDouble()
            na += a[i].toDouble() * a[i].toDouble()
            nb += b[i].toDouble() * b[i].toDouble()
        }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom == 0.0) 0.0 else dot / denom
    }
}
