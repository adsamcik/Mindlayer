package com.adsamcik.mindlayer.service.engine

import com.adsamcik.mindlayer.OcrSessionConfig
import com.adsamcik.mindlayer.service.ipc.OcrTokenStreamWriter
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
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
            scope = this,
            barcodeDetector = null,
            llmExtractor = NoOpOcrLlmExtractor(),
        )

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
    }

    @Test fun `recognition job enters and exits foreground tracker on completion`() = runTest {
        var entered = 0
        var exited = 0
        val engine = mockk<PaddleOcrEngine>()
        val enteredSignal = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { engine.recognise(any(), any(), any(), any()) } coAnswers {
            release.await()
            OcrEngineOutput(emptyList(), backend = "CPU", detDurationMs = 0, recDurationMs = 0, clsDurationMs = 0, totalDurationMs = 0)
        }
        val dispatcher = OcrRecognitionDispatcher(
            engine = engine,
            scope = this,
            barcodeDetector = null,
            llmExtractor = NoOpOcrLlmExtractor(),
            foregroundTracker = object : ForegroundTracker {
                override fun enterForeground() {
                    entered++
                    enteredSignal.complete(Unit)
                }
                override fun exitForeground() { exited++ }
            },
        )

        val job = dispatcher.submit("ocr-test", 1L, ByteArray(4), 2, 2, OcrEngineConfig(), null)
        enteredSignal.await()
        assertEquals(1, entered)
        assertEquals(0, exited)
        release.complete(Unit)
        job.join()
        assertEquals(1, exited)
    }

    @Test fun `recognition job exits foreground tracker on cancellation`() = runTest {
        var entered = 0
        var exited = 0
        val engine = mockk<PaddleOcrEngine>()
        val enteredSignal = CompletableDeferred<Unit>()
        coEvery { engine.recognise(any(), any(), any(), any()) } coAnswers { awaitCancellation() }
        val dispatcher = OcrRecognitionDispatcher(
            engine = engine,
            scope = this,
            barcodeDetector = null,
            llmExtractor = NoOpOcrLlmExtractor(),
            foregroundTracker = object : ForegroundTracker {
                override fun enterForeground() {
                    entered++
                    enteredSignal.complete(Unit)
                }
                override fun exitForeground() { exited++ }
            },
        )

        val job = dispatcher.submit("ocr-test", 1L, ByteArray(4), 2, 2, OcrEngineConfig(), null)
        enteredSignal.await()
        assertEquals(1, entered)
        job.cancelAndJoin()
        assertEquals(1, exited)
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
