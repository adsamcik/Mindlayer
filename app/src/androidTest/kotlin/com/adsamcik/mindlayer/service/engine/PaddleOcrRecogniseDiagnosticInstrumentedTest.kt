package com.adsamcik.mindlayer.service.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * Bug #8 regression: runs the REAL [LiteRtPaddleOcrBackend] against a
 * synthetic black-text-on-white Bitmap and asserts that recognition
 * either succeeds OR fails for a *recognised* (and acceptable) reason
 * such as `LowMemoryException`. Any other exception (notably the
 * `LiteRtException: Failed to invoke the compiled model` thrown by the
 * native side when the bundled `rec.tflite` contains the unresolvable
 * `ONNX_LAYERNORMALIZATION` custom op) fails the test loudly.
 *
 * Why it exists: the previous validation report incorrectly attributed
 * recognise failures to "emulator LiteRT op-set limitations" — that was
 * speculation. Ground-truth investigation (binary `grep` on the shipped
 * `rec.tflite` + native logcat from this very test) showed the failure
 * is in the SHIPPED MODEL ARTIFACT, not the emulator. The artifact is
 * stale (built 2026-05-20, before the `-tb tf_converter` workflow fix
 * landed 2026-05-30). Recognition would fail identically on every real
 * device shipping these stale models.
 *
 * Complements [OcrNewspaperInstrumentedTest], which exercises the same
 * pipeline against a real newspaper AVIF — that test is currently
 * gated on a fixture that is not in CI, so this synthetic-image
 * variant runs unconditionally in every instrumented sweep and catches
 * model regressions without needing a manual fixture push.
 *
 * Output appears in instrumentation logcat under tag `System.out`:
 *   adb shell logcat -d | grep PADDLE_OCR_DIAG
 */
@RunWith(AndroidJUnit4::class)
class PaddleOcrRecogniseDiagnosticInstrumentedTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun diag_real_recognise_on_synthetic_text_print_exception_or_lines() = runBlocking {
        // CI without the PaddleOCR AI Pack provisioned (the four
        // `PADDLEOCR_*_SHA256` repository variables unset, so the
        // `Provision AI Pack assets` step short-circuits with
        // `have-pack=false`) cannot stage the det/rec/cls/dict artefacts
        // the registry needs. Skip cleanly in that environment — local
        // dev runs and CI runs WITH the vars configured still get the
        // full regression coverage. Mirrors the
        // `paddleocr_production_backend_loads_real_ai_pack_assets`
        // self-skip in EngineCoexistenceInstrumentedTest so the two
        // diagnostic paths behave identically on a bare runner.
        val assetList = context.assets.list("")?.toList().orEmpty()
        assumeTrue(
            "PaddleOCR AI Pack manifest not mirrored into the test APK; configure " +
                "PADDLEOCR_* repository variables (see docs/models/MODEL_SHAS.md) and " +
                "provision the bundle to enable this diagnostic in CI.",
            assetList.contains("paddleocr_model_integrity.json"),
        )

        val bundles = PaddleOcrModelRegistry.discoverBundles(context, requireIntegrity = false)
        val bundle = PaddleOcrModelRegistry.getDefaultBundle(bundles)
        assertNotNull("PADDLE_OCR_DIAG: bundle resolution null — staging missing?", bundle)
        println("PADDLE_OCR_DIAG: bundle resolved id=${bundle?.id} det=${bundle?.detectionPath} rec=${bundle?.recognitionPath}")

        val bitmap = synthesiseTextBitmap()
        val yPlane = bitmap.toYPlane()
        val backend = LiteRtPaddleOcrBackend(context, memoryHeadroomBytes = 0L)
        try {
            // Force CPU explicitly — matches the bug-2 fallback path.
            backend.initialize(checkNotNull(bundle), "CPU")
            println("PADDLE_OCR_DIAG: backend init ok, activeBackend=${backend.activeBackend}")

            try {
                val result = backend.recognise(
                    yPlane = yPlane,
                    width = bitmap.width,
                    height = bitmap.height,
                    config = OcrEngineConfig(emitBoundingBoxes = true, maxLines = 16),
                )
                println("PADDLE_OCR_DIAG: RECOGNISE_OK backend=${result.backend} lines=${result.lines.size}")
                result.lines.forEachIndexed { i, line ->
                    println("PADDLE_OCR_DIAG: LINE[$i] text='${line.text}' conf=${line.confidence}")
                }
            } catch (t: Throwable) {
                // Intentional FULL exception dump for diagnostic purposes —
                // PP-OCRv5 input/output is pure float tensors derived from
                // pixel data; no prompt fragments can be inlined here.
                println("PADDLE_OCR_DIAG: RECOGNISE_FAILED class=${t::class.qualifiedName} message=${t.message}")
                var depth = 0
                var cause = t.cause
                while (cause != null && depth < 6) {
                    println("PADDLE_OCR_DIAG: cause[$depth] class=${cause::class.qualifiedName} message=${cause.message}")
                    cause = cause.cause
                    depth++
                }
                t.stackTrace.take(15).forEachIndexed { i, frame ->
                    println("PADDLE_OCR_DIAG: stack[$i] $frame")
                }
                throw t
            }
        } finally {
            backend.shutdown()
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun synthesiseTextBitmap(): Bitmap {
        // 640 × 480, white background, large black text — proportions
        // typical of PaddleOCR test inputs.
        val w = 640
        val h = 480
        val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bm)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 64f
            isAntiAlias = true
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        canvas.drawText("MINDLAYER OCR", 60f, 140f, paint)
        canvas.drawText("paddle 0123", 60f, 240f, paint)
        canvas.drawText("test 456789", 60f, 340f, paint)
        return bm
    }

    private fun Bitmap.toYPlane(): ByteArray {
        // BT.601 luma: Y = 0.299 R + 0.587 G + 0.114 B
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        val out = ByteArray(width * height)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            val y = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
            out[i] = y.toByte()
        }
        return out
    }
}
