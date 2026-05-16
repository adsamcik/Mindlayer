package com.adsamcik.mindlayer.service.engine

import com.adsamcik.mindlayer.EmbeddingTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingTaskTest {

    @Test
    fun `prefixFor maps all known task codes`() {
        assertEquals("task: search result | query: ", EmbeddingTask.prefixFor(EmbeddingTask.RETRIEVAL_QUERY))
        assertEquals("title: none | text: ", EmbeddingTask.prefixFor(EmbeddingTask.RETRIEVAL_DOCUMENT))
        assertEquals("task: classification | query: ", EmbeddingTask.prefixFor(EmbeddingTask.CLASSIFICATION))
        assertEquals("task: clustering | query: ", EmbeddingTask.prefixFor(EmbeddingTask.CLUSTERING))
        assertEquals("task: sentence similarity | query: ", EmbeddingTask.prefixFor(EmbeddingTask.SIMILARITY))
        assertEquals("task: code retrieval | query: ", EmbeddingTask.prefixFor(EmbeddingTask.CODE))
        assertEquals("task: question answering | query: ", EmbeddingTask.prefixFor(EmbeddingTask.QUESTION_ANSWERING))
        assertEquals("task: fact checking | query: ", EmbeddingTask.prefixFor(EmbeddingTask.FACT_VERIFICATION))
        assertEquals("", EmbeddingTask.prefixFor(99))
    }

    @Test
    fun `isValid accepts only append-only range`() {
        assertFalse(EmbeddingTask.isValid(-1))
        assertTrue(EmbeddingTask.isValid(0))
        assertTrue(EmbeddingTask.isValid(7))
        assertFalse(EmbeddingTask.isValid(8))
    }
}
