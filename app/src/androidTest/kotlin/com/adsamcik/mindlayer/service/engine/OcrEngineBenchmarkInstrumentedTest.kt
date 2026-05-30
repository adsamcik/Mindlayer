package com.adsamcik.mindlayer.service.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/**
 * On-device OCR engine benchmark: PaddleOCR PP-OCRv5 mobile vs Tesseract 4
 * (eng-only and Latin script). Compares latency + extracted text quantity
 * across a fixture dataset of 13 coffee-bag photos.
 *
 * # Why this lives in androidTest
 *
 * Tesseract is NOT a production runtime in Mindlayer — it is pulled via
 * `androidTestImplementation` only (see `app/build.gradle.kts` and
 * `settings.gradle.kts`). This file therefore violates the production
 * privacy invariant ("recognized text never persists outside RAM") on
 * purpose: the whole point of the benchmark is to compare what each
 * engine actually outputs. The recognized text is written to the app's
 * `externalFilesDir` so it can be pulled with `adb pull` for offline
 * analysis. None of this code ships in `:app` or `:sdk` release artifacts.
 *
 * # Fixture provenance
 *
 *  - 13 JPEG/JPG photos under `androidTest/assets/ocr-bench/coffee-bags/`
 *    are the developer-supplied "Coffee bags" dataset; languages on the
 *    bags include English, Czech, Spanish, German, and Norwegian.
 *  - `androidTest/assets/ocr-bench/tessdata/{eng,Latin}.traineddata` are
 *    Apache-2.0 LSTM weights from `tessdata_fast` (commit-pinned at
 *    benchmark authoring time).
 *  - PaddleOCR PP-OCRv5 mobile `.tflite` artifacts must be sideloaded to
 *    `/data/local/tmp/` before running (see `docs/DEV_MODELS.md`). The
 *    test skips with [assumeTrue] when the registry can't find them, so
 *    the bench is opt-in even on a fully provisioned emulator.
 *
 * # Run
 *
 * ```powershell
 * adb push G:\Github\paddle-models\paddleocr-* /data/local/tmp/
 * .\gradlew.bat :app:connectedDebugAndroidTest `
 *   "-Pandroid.testInstrumentationRunnerArguments.class=com.adsamcik.mindlayer.service.engine.OcrEngineBenchmarkInstrumentedTest"
 * adb pull /sdcard/Android/data/com.adsamcik.mindlayer.service/files/ocr-bench/ ./results
 * ```
 *
 * # Output
 *
 *  - `results.csv` — one row per (image, engine, iteration) with
 *    `imageName,engine,iter,lineCount,charCount,totalMs,detMs,recMs`.
 *  - `text/<image>.<engine>.txt` — the recognized text from the first
 *    timed iteration (for quality inspection / diff).
 *  - `summary.txt` — engine medians (p50/p95) over 3 timed iterations.
 *
 * # Methodology notes (read before drawing conclusions)
 *
 *  - x86_64 emulator inference time is **not representative** of real
 *    device CPU time. Treat performance numbers as ordering hints only;
 *    quality numbers (lines / chars extracted) transfer cleanly.
 *  - Both engines see the exact same decoded pixel buffer per image.
 *    Paddle gets the BT.601 Y-plane (its required input). Tesseract gets
 *    the original color bitmap and does its own greyscale conversion —
 *    this is the configuration each engine ships with.
 *  - 1 warmup + 3 timed iterations per (image, engine). The warmup
 *    absorbs JIT / first-call native init costs; only iterations 1..3
 *    are in the CSV.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 27)
class OcrEngineBenchmarkInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** App-under-test context — owns the LiteRT classloader + filesDir. */
    private val targetContext: Context get() = instrumentation.targetContext

    /** androidTest APK context — owns the fixture assets. */
    private val testContext: Context get() = instrumentation.context

    @Test(timeout = TEST_TIMEOUT_MS)
    fun bench_paddle_vs_tesseract_on_coffee_bags() = runBlocking {
        val outputDir = File(targetContext.getExternalFilesDir(null), "ocr-bench").apply {
            deleteRecursively()
            mkdirs()
        }
        val textDir = File(outputDir, "text").apply { mkdirs() }
        val csvFile = File(outputDir, "results.csv").apply {
            writeText("image,engine,iter,lineCount,charCount,totalMs,detMs,recMs\n")
        }
        val summaryFile = File(outputDir, "summary.txt")

        val bundle = discoverPaddleBundleOrSkip()
        val paddle = LiteRtPaddleOcrBackend(targetContext, memoryHeadroomBytes = 0L).also {
            it.initialize(bundle, "CPU")
            Log.i(TAG, "Paddle initialised, backend=${it.activeBackend}, bundle=${bundle.displayName}")
        }

        val tessParent = setupTessdata()
        val tessEng = initTesseract(tessParent, "eng")
        val tessLatin = initTesseract(tessParent, "Latin")

        val images = testContext.assets.list("$BENCH_ASSET_ROOT/coffee-bags")
            ?.sorted()
            .orEmpty()
        assumeTrue("No fixture images found under assets/$BENCH_ASSET_ROOT/coffee-bags", images.isNotEmpty())

        val timings = mutableMapOf<String, MutableList<Double>>()

        try {
            for (imageName in images) {
                Log.i(TAG, "▶ $imageName")
                val bitmap = decodeAsset("$BENCH_ASSET_ROOT/coffee-bags/$imageName")
                if (bitmap == null) {
                    Log.w(TAG, "  decode failed, skipping")
                    continue
                }
                try {
                    val yPlane = bitmapToYPlane(bitmap)
                    val w = bitmap.width
                    val h = bitmap.height
                    Log.i(TAG, "  dims=${w}x$h yBytes=${yPlane.size}")

                    // Warmup (untimed).
                    paddle.recognise(yPlane, w, h, OcrEngineConfig(maxLines = MAX_LINES))
                    runTesseract(tessEng, bitmap)
                    runTesseract(tessLatin, bitmap)

                    for (iter in 1..ITERATIONS) {
                        runPaddleIteration(paddle, imageName, yPlane, w, h, iter, csvFile, textDir, timings)
                        runTesseractIteration(tessEng, "tess-eng", imageName, bitmap, iter, csvFile, textDir, timings)
                        runTesseractIteration(tessLatin, "tess-latin", imageName, bitmap, iter, csvFile, textDir, timings)
                    }
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }
        } finally {
            paddle.shutdown()
            tessEng.recycle()
            tessLatin.recycle()
            writeSummary(summaryFile, images.size, timings)
            Log.i(TAG, "═══ Bench complete ═══")
            Log.i(TAG, "Output: ${outputDir.absolutePath}")
            Log.i(TAG, "Pull:   adb pull ${outputDir.absolutePath} ./bench-results")
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun discoverPaddleBundleOrSkip(): PaddleOcrModelInfo {
        val bundles = PaddleOcrModelRegistry.discoverBundles(targetContext)
        val bundle = PaddleOcrModelRegistry.getDefaultBundle(bundles)
        assumeTrue(
            "No PaddleOCR bundle discovered. Sideload PP-OCRv5 mobile .tflite + dict.txt " +
                "to /data/local/tmp/ (see docs/DEV_MODELS.md) before running this bench.",
            bundle != null,
        )
        return bundle!!
    }

    private fun setupTessdata(): File {
        val tessParent = File(targetContext.filesDir, "ocr-bench-tess").apply {
            deleteRecursively()
            mkdirs()
        }
        val tessdataDir = File(tessParent, "tessdata").apply { mkdirs() }
        for (name in TESSDATA_FILES) {
            val asset = "$BENCH_ASSET_ROOT/tessdata/$name"
            val target = File(tessdataDir, name)
            testContext.assets.open(asset).use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            Log.i(TAG, "Extracted $asset → ${target.absolutePath} (${target.length()} bytes)")
        }
        return tessParent
    }

    private fun initTesseract(tessParent: File, language: String): TessBaseAPI {
        val api = TessBaseAPI()
        val ok = api.init(tessParent.absolutePath, language)
        assertNotNull("TessBaseAPI($language) init returned null", ok)
        Log.i(TAG, "Tesseract($language) initialised at ${tessParent.absolutePath}")
        return api
    }

    private fun decodeAsset(path: String): Bitmap? {
        val bytes = testContext.assets.open(path).use { it.readBytes() }
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private suspend fun runPaddleIteration(
        paddle: LiteRtPaddleOcrBackend,
        imageName: String,
        yPlane: ByteArray,
        w: Int,
        h: Int,
        iter: Int,
        csvFile: File,
        textDir: File,
        timings: MutableMap<String, MutableList<Double>>,
    ) {
        val start = System.nanoTime()
        val result = paddle.recognise(yPlane, w, h, OcrEngineConfig(maxLines = MAX_LINES))
        val totalMs = (System.nanoTime() - start) / 1_000_000.0
        val text = result.lines.joinToString("\n") { it.text }
        appendCsv(csvFile, imageName, "paddle", iter, result.lines.size, text.length, totalMs,
            result.detDurationMs.toDouble(), result.recDurationMs.toDouble())
        recordTiming(timings, "paddle", totalMs)
        if (iter == 1) writeText(textDir, imageName, "paddle", text)
        Log.i(TAG, "  paddle  iter=$iter lines=${result.lines.size} chars=${text.length} ms=${"%.1f".format(totalMs)}")
    }

    private fun runTesseractIteration(
        api: TessBaseAPI,
        label: String,
        imageName: String,
        bitmap: Bitmap,
        iter: Int,
        csvFile: File,
        textDir: File,
        timings: MutableMap<String, MutableList<Double>>,
    ) {
        val start = System.nanoTime()
        val text = runTesseract(api, bitmap)
        val totalMs = (System.nanoTime() - start) / 1_000_000.0
        val lineCount = text.split('\n').count { it.isNotBlank() }
        appendCsv(csvFile, imageName, label, iter, lineCount, text.length, totalMs, null, null)
        recordTiming(timings, label, totalMs)
        if (iter == 1) writeText(textDir, imageName, label, text)
        Log.i(TAG, "  $label iter=$iter lines=$lineCount chars=${text.length} ms=${"%.1f".format(totalMs)}")
    }

    private fun runTesseract(api: TessBaseAPI, bitmap: Bitmap): String {
        api.setImage(bitmap)
        val text = api.utF8Text ?: ""
        api.clear()
        return text
    }

    private fun appendCsv(
        csvFile: File,
        image: String,
        engine: String,
        iter: Int,
        lines: Int,
        chars: Int,
        totalMs: Double,
        detMs: Double?,
        recMs: Double?,
    ) {
        val det = detMs?.let { "%.1f".format(Locale.ROOT, it) } ?: ""
        val rec = recMs?.let { "%.1f".format(Locale.ROOT, it) } ?: ""
        val total = "%.1f".format(Locale.ROOT, totalMs)
        csvFile.appendText("$image,$engine,$iter,$lines,$chars,$total,$det,$rec\n")
    }

    private fun writeText(textDir: File, imageName: String, engine: String, text: String) {
        File(textDir, "$imageName.$engine.txt").writeText(text)
    }

    private fun recordTiming(timings: MutableMap<String, MutableList<Double>>, engine: String, ms: Double) {
        timings.getOrPut(engine) { mutableListOf() } += ms
    }

    private fun writeSummary(file: File, imageCount: Int, timings: Map<String, List<Double>>) {
        val sb = StringBuilder()
        sb.appendLine("OCR Engine Benchmark Summary")
        sb.appendLine("Images: $imageCount  Iterations per engine per image: $ITERATIONS (after 1 warmup)")
        sb.appendLine("Device: ${android.os.Build.MODEL} (${android.os.Build.HARDWARE}) API ${android.os.Build.VERSION.SDK_INT}")
        sb.appendLine()
        sb.appendLine("engine          n      p50_ms     p95_ms     mean_ms    min_ms     max_ms")
        sb.appendLine("─".repeat(72))
        for ((engine, samples) in timings.toSortedMap()) {
            val sorted = samples.sorted()
            val p50 = sorted[sorted.size / 2]
            val p95 = sorted[((sorted.size - 1) * 95 / 100).coerceAtLeast(0)]
            val mean = samples.average()
            val min = sorted.first()
            val max = sorted.last()
            sb.append(engine.padEnd(15))
            sb.append("%-6d".format(samples.size))
            sb.append("%-11.1f".format(Locale.ROOT, p50))
            sb.append("%-11.1f".format(Locale.ROOT, p95))
            sb.append("%-11.1f".format(Locale.ROOT, mean))
            sb.append("%-11.1f".format(Locale.ROOT, min))
            sb.append("%-11.1f".format(Locale.ROOT, max))
            sb.appendLine()
        }
        file.writeText(sb.toString())
        Log.i(TAG, "\n${sb}")
    }

    /**
     * BT.601 luminance conversion — matches [MediaPartYPlaneExtractor.bitmapToYPlane]
     * (this is the exact input transform PaddleOCR receives in production).
     */
    private fun bitmapToYPlane(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val y = ByteArray(width * height)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            val luma = (77 * r + 150 * g + 29 * b + 128) ushr 8
            y[i] = luma.toByte()
        }
        return y
    }

    private companion object {
        private const val TAG = "OcrBench"
        private const val BENCH_ASSET_ROOT = "ocr-bench"
        private const val MAX_LINES = 128
        private const val ITERATIONS = 3
        private const val TEST_TIMEOUT_MS = 30L * 60L * 1000L // 30 min cap
        private val TESSDATA_FILES = listOf("eng.traineddata", "Latin.traineddata")
    }
}
