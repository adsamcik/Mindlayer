package com.adsamcik.mindlayer.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingConfigTest {
    @Test fun `all embedding tasks round-trip code through aidl request`() {
        val cases = listOf(
            EmbeddingTask.RetrievalQuery to com.adsamcik.mindlayer.EmbeddingTask.RETRIEVAL_QUERY,
            EmbeddingTask.RetrievalDocument to com.adsamcik.mindlayer.EmbeddingTask.RETRIEVAL_DOCUMENT,
            EmbeddingTask.Classification to com.adsamcik.mindlayer.EmbeddingTask.CLASSIFICATION,
            EmbeddingTask.Clustering to com.adsamcik.mindlayer.EmbeddingTask.CLUSTERING,
            EmbeddingTask.Similarity to com.adsamcik.mindlayer.EmbeddingTask.SIMILARITY,
            EmbeddingTask.Code to com.adsamcik.mindlayer.EmbeddingTask.CODE,
            EmbeddingTask.QuestionAnswering to com.adsamcik.mindlayer.EmbeddingTask.QUESTION_ANSWERING,
            EmbeddingTask.FactVerification to com.adsamcik.mindlayer.EmbeddingTask.FACT_VERIFICATION,
        )
        for ((task, code) in cases) {
            assertEquals(code, EmbeddingConfig(text = "secret", task = task).toAidlRequest().taskType)
        }
    }

    @Test fun `default model resolves to EmbeddingGemma300m`() {
        assertSame(EmbeddingModel.EmbeddingGemma300m, EmbeddingModel.Default)
        assertEquals("embedding-gemma-300m-v1", EmbeddingModel.Default.id)
    }

    @Test fun `EmbeddingConfig toString redacts text`() {
        val text = "sensitive text"
        val rendered = EmbeddingConfig(text = text).toString()
        assertTrue(rendered.contains("text=<redacted:${text.length}>"))
        assertFalse(rendered.contains(text))
    }
}
