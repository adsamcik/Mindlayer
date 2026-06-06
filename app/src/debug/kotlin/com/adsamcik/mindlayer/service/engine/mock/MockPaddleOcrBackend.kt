package com.adsamcik.mindlayer.service.engine.mock

import com.adsamcik.mindlayer.service.engine.OcrEngineConfig
import com.adsamcik.mindlayer.service.engine.OcrEngineOutput
import com.adsamcik.mindlayer.service.engine.OcrFieldFusion
import com.adsamcik.mindlayer.service.engine.OcrTextLine
import com.adsamcik.mindlayer.service.engine.PaddleOcrBackend
import com.adsamcik.mindlayer.service.engine.PaddleOcrModelInfo

/**
 * DEBUG-only mock [PaddleOcrBackend] for the "CI mock engines" mode.
 *
 * Reports `isInitialized = true` and a fixed [BUNDLE] so
 * [com.adsamcik.mindlayer.service.engine.PaddleOcrEngine.initializeLocked]
 * takes its `isInitialized` fast-path and reaches `Ready` **without** scanning
 * for the on-disk PP-OCRv5 bundle. That flips `FEATURE_OCR_SESSION` /
 * `FEATURE_OCR_IMAGE_ONESHOT` in `getCapabilities`, letting a consumer app's
 * CI exercise the OCR session + one-shot wire paths on a model-less runner.
 *
 * [recognise] returns a small, deterministic set of plausible lines, every one
 * prefixed with `[mock]` so consumers can assert they are talking to the mock
 * (and never mistake mock output for a real recognition result). It honours
 * [OcrEngineConfig.maxLines] and [OcrEngineConfig.emitBoundingBoxes] so the
 * capability-gated bounding-box path is exercised too.
 */
internal class MockPaddleOcrBackend : PaddleOcrBackend {

    override val activeBackend: String = "MOCK"
    override val isInitialized: Boolean = true
    override val currentBundle: PaddleOcrModelInfo = BUNDLE

    override suspend fun initialize(bundle: PaddleOcrModelInfo, preferredBackend: String?) {
        // No-op: the mock is always "loaded".
    }

    override suspend fun recognise(
        yPlane: ByteArray,
        width: Int,
        height: Int,
        config: OcrEngineConfig,
    ): OcrEngineOutput {
        val emitBoxes = config.emitBoundingBoxes
        val lines = MOCK_LINES.mapIndexed { index, text ->
            OcrTextLine(
                text = text,
                confidence = if (index == 0) OcrFieldFusion.Confidence.HIGH else OcrFieldFusion.Confidence.MEDIUM,
                boundingBox = if (emitBoxes) stackedBox(index, MOCK_LINES.size) else null,
                orientationDegrees = 0,
            )
        }
        val capped = if (config.maxLines in 1 until lines.size) lines.take(config.maxLines) else lines
        return OcrEngineOutput(
            lines = capped,
            backend = activeBackend,
            detDurationMs = 1L,
            recDurationMs = 1L,
            clsDurationMs = 0L,
            totalDurationMs = 2L,
        )
    }

    override suspend fun shutdown() {
        // No-op.
    }

    /**
     * A normalised quad stacked vertically so each mock line occupies its own
     * horizontal band of the frame. Values are clamped to 0..1.
     */
    private fun stackedBox(index: Int, total: Int): FloatArray {
        val top = (index.toFloat() / total).coerceIn(0f, 1f)
        val bottom = ((index + 1).toFloat() / total).coerceIn(0f, 1f)
        val left = 0.05f
        val right = 0.95f
        return floatArrayOf(left, top, right, top, right, bottom, left, bottom)
    }

    companion object {
        private val MOCK_LINES = listOf(
            "[mock] MINDLAYER CI",
            "[mock] INVOICE #2024-001",
            "[mock] TOTAL 12.34",
        )

        /**
         * Fixed fake bundle the mock advertises. All paths are sentinels —
         * the mock never reads them.
         */
        val BUNDLE: PaddleOcrModelInfo = PaddleOcrModelInfo(
            id = "mock-ppocrv5-mobile",
            displayName = "Mock PP-OCRv5 (CI)",
            detectionPath = "/dev/null/mock-det.tflite",
            recognitionPath = "/dev/null/mock-rec.tflite",
            classifierPath = "/dev/null/mock-cls.tflite",
            dictionaryPath = "/dev/null/mock-dict.txt",
            totalSizeBytes = 0L,
            detSha256 = null,
            recSha256 = null,
            clsSha256 = null,
            dictSha256 = null,
        )
    }
}
