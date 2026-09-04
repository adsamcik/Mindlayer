package com.adsamcik.mindlayer.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class InferenceRequestPriorityTest {

    @Test
    fun `priority defaults to normal`() {
        val request = InferenceRequest.Builder()

        assertEquals(0, request.priorityHint)
    }

    @Test
    fun `typed priorities map to stable wire hints`() {
        assertEquals(-5, InferenceRequest.Builder().apply {
            priority(InferencePriority.BACKGROUND)
        }.priorityHint)
        assertEquals(0, InferenceRequest.Builder().apply {
            priority(InferencePriority.NORMAL)
        }.priorityHint)
        assertEquals(5, InferenceRequest.Builder().apply {
            priority(InferencePriority.INTERACTIVE)
        }.priorityHint)
        assertEquals(10, InferenceRequest.Builder().apply {
            priority(InferencePriority.URGENT)
        }.priorityHint)
    }

    @Test
    fun `advanced priority accepts the full validated range`() {
        assertEquals(-10, InferenceRequest.Builder().apply { priority(-10) }.priorityHint)
        assertEquals(10, InferenceRequest.Builder().apply { priority(10) }.priorityHint)
    }

    @Test
    fun `advanced priority rejects values outside the wire contract`() {
        assertThrows(IllegalArgumentException::class.java) {
            InferenceRequest.Builder().priority(-11)
        }
        assertThrows(IllegalArgumentException::class.java) {
            InferenceRequest.Builder().priority(11)
        }
    }
}
