package com.adsamcik.mindlayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EmbeddingParcelableTest {
    @Test
    fun `request toString redacts text`() {
        val req = EmbeddingRequest(text = "secret text", tag = "t")
        val rendered = req.toString()
        assertFalse(rendered.contains("secret"))
        assertFalse(rendered.contains("text,"))
        assertEquals(true, rendered.contains("<redacted:11>"))
    }

    @Test
    fun `result equality ignores vector contents`() {
        val a = EmbeddingResult(tag = "t", vector = floatArrayOf(1f, 2f), dim = 2, modelId = "m", tokenCount = 3, truncated = false, backend = "CPU", durationMs = 4)
        val b = a.copy(vector = floatArrayOf(9f, 8f))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a.toString().contains("1.0"))
        assertFalse(a.toString().contains("2.0"))
    }
}
