package com.adsamcik.mindlayer.service.engine.mock

import com.adsamcik.mindlayer.service.engine.OcrEngineConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for [MockPaddleOcrBackend]: ready-without-models, `[mock]`-tagged
 * lines, and config (`maxLines`, `emitBoundingBoxes`) honoured.
 */
class MockPaddleOcrBackendTest {

    private val backend = MockPaddleOcrBackend()
    private val frame = ByteArray(4 * 4)

    @Test
    fun `reports initialized with a fixed bundle`() {
        assertTrue(backend.isInitialized)
        assertEquals("mock-ppocrv5-mobile", backend.currentBundle.id)
        assertEquals("MOCK", backend.activeBackend)
    }

    @Test
    fun `recognise returns mock-tagged lines`() = runBlocking {
        val out = backend.recognise(frame, 4, 4, OcrEngineConfig())
        assertTrue(out.lines.isNotEmpty())
        assertTrue(out.lines.all { it.text.startsWith("[mock]") })
        assertEquals("MOCK", out.backend)
    }

    @Test
    fun `maxLines caps the result`() = runBlocking {
        val out = backend.recognise(frame, 4, 4, OcrEngineConfig(maxLines = 1))
        assertEquals(1, out.lines.size)
    }

    @Test
    fun `bounding boxes emitted only when requested`() = runBlocking {
        val without = backend.recognise(frame, 4, 4, OcrEngineConfig(emitBoundingBoxes = false))
        assertTrue(without.lines.all { it.boundingBox == null })

        val with = backend.recognise(frame, 4, 4, OcrEngineConfig(emitBoundingBoxes = true))
        assertTrue(with.lines.all { it.boundingBox != null && it.boundingBox.size == 8 })
        // All box coordinates are normalised into 0..1.
        assertTrue(with.lines.all { line -> line.boundingBox!!.all { it in 0f..1f } })
    }

    @Test
    fun `recognise is deterministic`() = runBlocking {
        val a = backend.recognise(frame, 4, 4, OcrEngineConfig())
        val b = backend.recognise(frame, 4, 4, OcrEngineConfig())
        assertEquals(a.lines.map { it.text }, b.lines.map { it.text })
    }

    @Test
    fun `bundle advertises an orientation classifier`() {
        assertNotNull(backend.currentBundle.classifierPath)
        assertTrue(backend.currentBundle.hasOrientationClassifier)
        assertNull(backend.currentBundle.recSha256)
    }
}
