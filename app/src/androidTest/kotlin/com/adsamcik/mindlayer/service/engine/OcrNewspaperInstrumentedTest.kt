package com.adsamcik.mindlayer.service.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end OCR smoke test that drives the production
 * [LiteRtPaddleOcrBackend] against an actual newspaper image.
 *
 * Why this exists: PaddleOCR PP-OCRv5 mobile's recognition head contains 5
 * `LayerNormalization` ops. onnx2tf 2.4.0 changed its default
 * `--tflite_backend` from `tf_converter` to `flatbuffer_direct`; the new
 * backend emits LayerNormalization as a TFLite **custom** op
 * (`ONNX_LAYERNORMALIZATION`) that LiteRT 2.1.5 cannot resolve. The fault
 * only manifests at `Interpreter.invoke()` — unit tests with fake runners
 * cannot catch it. This test loads the actual `.tflite` and exercises the
 * full det → cls → rec → CTC-decode pipeline so the regression is caught
 * immediately on a real device/emulator.
 *
 * Gated by [assumeTrue]: skips silently if the AVIF fixture is missing
 * from the app's external files dir, so it does not fail CI environments
 * that don't have the test asset.
 *
 * To run: push the AVIF + four `.tflite`/`.txt` model artifacts to
 * `/sdcard/Android/data/<applicationId>/files/`, then
 *   `adb shell am instrument -w -r -e class
 *      com.adsamcik.mindlayer.service.engine.OcrNewspaperInstrumentedTest
 *      <applicationId>.test/androidx.test.runner.AndroidJUnitRunner`.
 */
@RunWith(AndroidJUnit4::class)
class OcrNewspaperInstrumentedTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
    fun production_backend_reads_newspaper_avif() = runBlocking {
        val imageFile = File(context.getExternalFilesDir(null), NEWSPAPER_FILE_NAME)
        assumeTrue("Newspaper AVIF fixture missing at ${imageFile.absolutePath}", imageFile.isFile)

        val bundles = PaddleOcrModelRegistry.discoverBundles(context, requireIntegrity = false)
        val bundle = PaddleOcrModelRegistry.getDefaultBundle(bundles)
        assertNotNull("PaddleOCR bundle resolution returned null", bundle)

        val bitmap = decodeBitmap(imageFile)
        val backend = LiteRtPaddleOcrBackend(context, memoryHeadroomBytes = 0L)
        try {
            backend.initialize(checkNotNull(bundle), "CPU")
            val result = backend.recognise(
                yPlane = bitmap.toYPlane(),
                width = bitmap.width,
                height = bitmap.height,
                config = OcrEngineConfig(
                    emitBoundingBoxes = true,
                    maxLines = MAX_LINES,
                ),
            )

            println("OCR_NEWSPAPER_BACKEND=${result.backend}")
            println("OCR_NEWSPAPER_LINES=${result.lines.size}")
            println("OCR_NEWSPAPER_TIMING_MS det=${result.detDurationMs} rec=${result.recDurationMs} cls=${result.clsDurationMs} total=${result.totalDurationMs}")
            result.lines.take(MAX_PRINTED_LINES).forEachIndexed { index, line ->
                println("OCR_NEWSPAPER_LINE_${index + 1}=${line.text}")
            }
            assertTrue("Expected at least one OCR line from $NEWSPAPER_FILE_NAME", result.lines.isNotEmpty())
        } finally {
            backend.shutdown()
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun decodeBitmap(file: File): Bitmap {
        val source = ImageDecoder.createSource(file)
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        if (decoded.config == Bitmap.Config.ARGB_8888) return decoded
        val copy = decoded.copy(Bitmap.Config.ARGB_8888, false)
        decoded.recycle()
        return copy
    }

    private fun Bitmap.toYPlane(): ByteArray {
        val out = ByteArray(width * height)
        var offset = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val color = getPixel(x, y)
                val luma = (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114 + 500) / 1000
                out[offset++] = luma.coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    private companion object {
        private const val NEWSPAPER_FILE_NAME = "the_enduring_value_of_student_newspapers_6_800.avif"
        private const val MAX_LINES = 128
        private const val MAX_PRINTED_LINES = 40
    }
}
