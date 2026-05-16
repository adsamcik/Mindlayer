package com.adsamcik.mindlayer.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest

class InMemoryVectorIndexTest {
    @Test fun `put remove clear size dimension basic round-trip`() {
        val index = InMemoryVectorIndex()
        assertEquals(0, index.size)
        assertEquals(-1, index.dimension)
        assertFalse(index.put("a", floatArrayOf(1f, 0f), "payload"))
        assertEquals(1, index.size)
        assertEquals(2, index.dimension)
        assertEquals(listOf("a"), index.ids())
        assertTrue(index.put("a", floatArrayOf(0f, 1f)))
        assertTrue(index.remove("a"))
        assertEquals(0, index.size)
        index.put("b", floatArrayOf(1f, 0f))
        index.clear()
        assertEquals(0, index.size)
        assertEquals(-1, index.dimension)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `put with mismatched dim throws`() {
        val index = InMemoryVectorIndex()
        index.put("a", floatArrayOf(1f, 0f))
        index.put("b", floatArrayOf(1f, 0f, 0f))
    }

    @Test fun `search returns top K sorted descending by score`() {
        val index = InMemoryVectorIndex()
        index.put("x", floatArrayOf(1f, 0f))
        index.put("y", floatArrayOf(0f, 1f))
        index.put("z", InMemoryVectorIndex.normalize(floatArrayOf(1f, 1f)))
        val hits = index.search(floatArrayOf(1f, 0f), 2)
        assertEquals(listOf("x", "z"), hits.map { it.id })
        assertTrue(hits[0].score >= hits[1].score)
    }

    @Test fun `search with k greater than size returns all entries`() {
        val index = InMemoryVectorIndex()
        index.put("a", floatArrayOf(1f))
        index.put("b", floatArrayOf(-1f))
        assertEquals(2, index.search(floatArrayOf(1f), 10).size)
    }

    @Test fun `search on empty index returns empty list`() {
        assertTrue(InMemoryVectorIndex().search(floatArrayOf(1f), 1).isEmpty())
    }

    @Test fun `normalize produces unit-length vector`() {
        val n = InMemoryVectorIndex.normalize(floatArrayOf(3f, 4f))
        val len = kotlin.math.sqrt(n.sumOf { (it * it).toDouble() })
        assertEquals(1.0, len, 1e-6)
    }

    @Test fun `normalize on zero vector returns zeros`() {
        val n = InMemoryVectorIndex.normalize(floatArrayOf(0f, 0f))
        assertEquals(0f, n[0], 0f)
        assertEquals(0f, n[1], 0f)
        assertFalse(n.any { it.isNaN() })
    }

    @Test fun `thread-safety concurrent puts and searches do not crash`() = runTest {
        val index = InMemoryVectorIndex()
        (0 until 1000).map { i ->
            async(Dispatchers.Default) {
                index.put("id-$i", floatArrayOf(1f, 0f), i)
                index.search(floatArrayOf(1f, 0f), 1)
            }
        }.awaitAll()
        assertEquals(1000, index.size)
        assertEquals(2, index.dimension)
    }

    @Test fun `numerical stability perturbation has high similarity`() {
        val base = InMemoryVectorIndex.normalize(FloatArray(768) { 1f })
        val perturbed = InMemoryVectorIndex.normalize(FloatArray(768) { if (it % 2 == 0) 1.001f else 0.999f })
        val index = InMemoryVectorIndex()
        index.put("p", perturbed)
        assertTrue(index.search(base, 1).single().score >= 0.99f)
    }

    @Test fun `payload is preserved across put and search`() {
        val payload = mapOf("doc" to "d1")
        val index = InMemoryVectorIndex()
        index.put("a", floatArrayOf(1f, 0f), payload)
        assertEquals(payload, index.search(floatArrayOf(1f, 0f), 1).single().payload)
    }
}
