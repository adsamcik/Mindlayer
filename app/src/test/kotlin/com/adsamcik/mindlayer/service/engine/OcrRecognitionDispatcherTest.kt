package com.adsamcik.mindlayer.service.engine

import com.adsamcik.mindlayer.OcrSessionConfig
import com.adsamcik.mindlayer.service.ipc.OcrTokenStreamWriter
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OcrRecognitionDispatcherTest {

    @Test fun `finalize registered empty session emits terminal result`() = runTest {
        val out = ByteArrayOutputStream()
        val writer = OcrTokenStreamWriter.forTesting(out)
        val dispatcher = OcrRecognitionDispatcher(
            engine = mockk(relaxed = true),
            barcodeDetector = null,
            llmExtractor = NoOpOcrLlmExtractor(),
        )

        try {
            dispatcher.registerSession(
                sessionId = "ocr-test",
                context = OcrExtractionContext(
                    mode = OcrSessionConfig.MODE_RECEIPT,
                    outputSchemaJson = """{"type":"object"}""",
                ),
            )
            dispatcher.finalize("ocr-test", writer)
            writer.close()

            val frames = readAllFrames(out.toByteArray())
            assertEquals(3, frames.size)
            assertTrue(frames[1].contains("ocr_result_finalized"))
            assertTrue(frames[1].contains(""""fullJson":"{}""""))
            assertTrue(frames[2].contains(""""type":"done""""))
        } finally {
            dispatcher.shutdown()
        }
    }

    private fun readAllFrames(bytes: ByteArray): List<String> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val frames = mutableListOf<String>()
        while (buffer.remaining() >= Int.SIZE_BYTES) {
            val len = buffer.int
            val payload = ByteArray(len)
            buffer.get(payload)
            frames += payload.toString(Charsets.UTF_8)
        }
        return frames
    }
}
