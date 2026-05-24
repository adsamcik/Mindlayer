package com.adsamcik.mindlayer.service.engine

import android.app.ActivityManager
import android.content.Context
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.logging.LogRepository
import com.adsamcik.mindlayer.service.logging.safeLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * LiteRT-based PaddleOCR PP-OCRv5 mobile backend.
 *
 * Owns the full per-frame OCR path:
 *
 * 1. Resize and normalize the Y plane into the det model's fixed NHWC input.
 * 2. Decode the DB probability map into axis-aligned line candidates.
 * 3. Optionally run the text-line orientation classifier.
 * 4. Resize each crop into the recognizer's fixed NHWC input.
 * 5. CTC-greedy-decode the recognizer output with the bundled dictionary.
 *
 * The native LiteRT surface stays behind [PaddleOcrLiteRtRunner] for
 * Robolectric-friendly unit coverage. Real-device coexistence with Gemma and
 * EmbeddingGemma remains tracked in `docs/LITERT_COEXISTENCE.md`.
 */
class LiteRtPaddleOcrBackend internal constructor(
    context: Context,
    private val memoryHeadroomBytes: Long = MEMORY_HEADROOM_BYTES,
    private val availableMemoryProvider: () -> Long = defaultAvailableMemoryProvider(context),
    private val runnerFactory: PaddleOcrLiteRtRunnerFactory,
    private val logRepository: LogRepository? = null,
) : PaddleOcrBackend {

    constructor(
        context: Context,
        memoryHeadroomBytes: Long = MEMORY_HEADROOM_BYTES,
        availableMemoryProvider: () -> Long = defaultAvailableMemoryProvider(context),
        logRepository: LogRepository? = null,
    ) : this(
        context = context,
        memoryHeadroomBytes = memoryHeadroomBytes,
        availableMemoryProvider = availableMemoryProvider,
        runnerFactory = { bundle, acceleratorLabel ->
            RealPaddleOcrLiteRtRunner.create(bundle, acceleratorLabel)
        },
        logRepository = logRepository,
    )

    private val mutex = Mutex()

    @Volatile
    private var loadedBundle: PaddleOcrModelInfo? = null

    @Volatile
    private var runner: PaddleOcrLiteRtRunner? = null

    @Volatile
    private var dictionary: List<String> = emptyList()

    @Volatile
    private var backendLabel: String = "NONE"

    @Volatile
    private var shuttingDown: Boolean = false

    override val activeBackend: String
        get() = backendLabel

    override val isInitialized: Boolean
        get() = loadedBundle != null

    override val currentBundle: PaddleOcrModelInfo?
        get() = loadedBundle

    override suspend fun initialize(
        bundle: PaddleOcrModelInfo,
        preferredBackend: String?,
    ): Unit = mutex.withLock {
        val initStartNs = System.nanoTime()
        shuttingDown = false
        val selectedBackend = resolveBackend(preferredBackend)
        loadedBundle?.let { current ->
            if (current.id == bundle.id &&
                current.detectionPath == bundle.detectionPath &&
                current.recognitionPath == bundle.recognitionPath &&
                current.classifierPath == bundle.classifierPath &&
                current.dictionaryPath == bundle.dictionaryPath &&
                backendLabel == selectedBackend
            ) {
                return
            }
        }

        checkMemoryHeadroom(bundle)
        var pendingRunner: PaddleOcrLiteRtRunner? = null
        try {
            val loaded = withContext(Dispatchers.IO) {
                verifyBundleFilesExist(bundle)
                val loadedDictionary = loadDictionary(bundle.dictionaryPath)
                val newRunner = runnerFactory(bundle, selectedBackend)
                pendingRunner = newRunner
                LoadedPipeline(
                    runner = newRunner,
                    dictionary = loadedDictionary,
                )
            }
            val previousRunner = runner
            runner = loaded.runner
            dictionary = loaded.dictionary
            backendLabel = selectedBackend
            loadedBundle = bundle
            pendingRunner = null
            if (previousRunner !== loaded.runner) {
                previousRunner?.runCatching { close() }
            }
            val durationMs = elapsedMs(initStartNs)
            logRepository?.logOcrBackendReady(
                backend = selectedBackend,
                bundleId = bundle.id,
                durationMs = durationMs,
            )
            MindlayerLog.i(
                TAG,
                "PaddleOCR backend ready: id=${bundle.id}, backend=$selectedBackend, " +
                    "size=${bundle.totalSizeBytes}B, hasCls=${bundle.hasOrientationClassifier}",
            )
        } catch (t: Throwable) {
            pendingRunner?.runCatching { close() }
            MindlayerLog.w(TAG, "PaddleOCR backend init failed: ${t.safeLabel()}", throwable = null)
            throw t
        } finally {
            pendingRunner = null
        }
    }

    override suspend fun recognise(
        yPlane: ByteArray,
        width: Int,
        height: Int,
        config: OcrEngineConfig,
    ): OcrEngineOutput {
        if (shuttingDown) return emptyOutput()
        return mutex.withLock {
            if (shuttingDown) return@withLock emptyOutput()
        val bundle = loadedBundle ?: error(
            "PaddleOCR backend not initialised; call initialize() first.",
        )
        val activeRunner = runner ?: error("PaddleOCR backend runner missing after initialization.")
        val activeDictionary = dictionary.takeIf { it.isNotEmpty() }
            ?: error("PaddleOCR dictionary missing after initialization.")

        return@withLock withContext(Dispatchers.IO) {
            runPipeline(
                runner = activeRunner,
                bundle = bundle,
                dictionary = activeDictionary,
                yPlane = yPlane,
                width = width,
                height = height,
                config = config,
            )
        }
        }
    }

    override suspend fun shutdown(): Unit {
        shuttingDown = true
        val shutdownStartNs = System.nanoTime()
        mutex.withLock {
            val bundle = loadedBundle ?: return
            val selectedBackend = backendLabel
            try {
                withContext(Dispatchers.IO) {
                    runner?.runCatching { close() }
                }
                logRepository?.logOcrBackendShutdown(
                    backend = selectedBackend,
                    bundleId = bundle.id,
                    durationMs = elapsedMs(shutdownStartNs),
                )
            } catch (t: Throwable) {
                MindlayerLog.w(TAG, "PaddleOCR shutdown error: ${t.safeLabel()}", throwable = null)
            } finally {
                runner = null
                dictionary = emptyList()
                loadedBundle = null
                backendLabel = "NONE"
            }
        }
    }

    private fun checkMemoryHeadroom(bundle: PaddleOcrModelInfo) {
        val available = availableMemoryProvider()
        val required = bundle.totalSizeBytes + memoryHeadroomBytes
        if (available < required) {
            throw LowMemoryException(
                availMb = available / BYTES_PER_MB,
                requiredMb = required / BYTES_PER_MB,
            )
        }
    }

    /**
     * CPU is the production default until the LiteRT + LiteRT-LM coexistence
     * checklist is completed for GPU/NPU on target devices. Callers can still
     * explicitly request GPU/NPU for prototype validation.
     */
    private fun resolveBackend(preferred: String?): String =
        LiteRtAcceleratorResolver.resolveBackend(
            requested = preferred,
            featureName = "ocr",
        ).backend

    private fun verifyBundleFilesExist(bundle: PaddleOcrModelInfo) {
        val missing = mutableListOf<String>()
        if (!File(bundle.detectionPath).isFile) missing += "det"
        if (!File(bundle.recognitionPath).isFile) missing += "rec"
        if (!File(bundle.dictionaryPath).isFile) missing += "dict"
        bundle.classifierPath?.let {
            if (!File(it).isFile) missing += "cls"
        }
        if (missing.isNotEmpty()) {
            throw IllegalStateException(
                "PaddleOCR bundle files missing: ${missing.joinToString(",")}",
            )
        }
    }

    private fun loadDictionary(path: String): List<String> {
        val raw = File(path).readText(Charsets.UTF_8)
        require(!Regex("\r(?!\n)").containsMatchIn(raw)) {
            "PaddleOCR dictionary contains bare CR line separators: $path"
        }
        val chars = raw.reader().buffered().use { reader ->
            reader.lineSequence()
                .map { it.trimEnd(Char(13)) }
                .filter(String::isNotEmpty)
                .mapIndexed { index, value -> if (index == 0) value.removePrefix("\uFEFF") else value }
                .toList()
        }
        require(chars.size in 100..50_000) { "PaddleOCR dictionary size ${chars.size} outside 100..50000: $path" }
        return chars
    }

    private fun emptyOutput(): OcrEngineOutput = OcrEngineOutput(
        lines = emptyList(),
        backend = backendLabel,
        detDurationMs = 0L,
        recDurationMs = 0L,
        clsDurationMs = 0L,
        totalDurationMs = 0L,
    )

    private fun runPipeline(
        runner: PaddleOcrLiteRtRunner,
        bundle: PaddleOcrModelInfo,
        dictionary: List<String>,
        yPlane: ByteArray,
        width: Int,
        height: Int,
        config: OcrEngineConfig,
    ): OcrEngineOutput {
        val totalStart = System.nanoTime()
        val detStart = System.nanoTime()
        val detInput = resizeFrameToRgbFloat(
            yPlane = yPlane,
            width = width,
            height = height,
            outputWidth = DET_INPUT_WIDTH,
            outputHeight = DET_INPUT_HEIGHT,
            normalization = Normalization.IMAGE_NET,
        )
        val detOutput = runner.runDetection(detInput)
        val candidates = decodeDetectionCandidates(detOutput, config.maxLines)
        val detDurationMs = elapsedMs(detStart)

        var clsDurationMs = 0L
        var recDurationMs = 0L
        val lines = ArrayList<OcrTextLine>(candidates.size)
        val useOrientation = bundle.classifierPath != null && !config.orientationDisabled
        for (candidate in candidates) {
            val orientationDegrees = if (useOrientation) {
                val clsStart = System.nanoTime()
                val clsInput = resizeCropToRgbFloat(
                    yPlane = yPlane,
                    width = width,
                    height = height,
                    box = candidate,
                    outputWidth = CLS_INPUT_WIDTH,
                    outputHeight = CLS_INPUT_HEIGHT,
                    normalization = Normalization.IMAGE_NET,
                    rotate180 = false,
                )
                val orientation = decodeOrientation(runner.runOrientation(clsInput))
                clsDurationMs += elapsedMs(clsStart)
                orientation
            } else {
                0
            }

            val recStart = System.nanoTime()
            val recInput = resizeCropToRgbFloat(
                yPlane = yPlane,
                width = width,
                height = height,
                box = candidate,
                outputWidth = REC_INPUT_WIDTH,
                outputHeight = REC_INPUT_HEIGHT,
                normalization = Normalization.CENTERED,
                rotate180 = orientationDegrees == 180,
            )
            val decoded = decodeRecognition(runner.runRecognition(recInput), dictionary)
            recDurationMs += elapsedMs(recStart)
            if (decoded.text.isNotBlank()) {
                lines += OcrTextLine(
                    text = decoded.text,
                    confidence = confidenceFromScore(decoded.confidence * candidate.score),
                    boundingBox = if (config.emitBoundingBoxes) candidate.boundingBox.copyOf() else null,
                    orientationDegrees = orientationDegrees,
                )
            }
        }

        return OcrEngineOutput(
            lines = lines,
            backend = activeBackend,
            detDurationMs = detDurationMs,
            recDurationMs = recDurationMs,
            clsDurationMs = clsDurationMs,
            totalDurationMs = elapsedMs(totalStart),
        )
    }

    private fun decodeDetectionCandidates(output: FloatArray, maxLines: Int): List<DetectionCandidate> {
        val side = sqrt(output.size.toDouble()).roundToInt()
        require(side > 0 && side * side == output.size) {
            "Unexpected PaddleOCR detection output size: ${output.size}"
        }
        val config = DetectionConfig(
            maxCandidates = if (maxLines > 0) maxLines else DetectionConfig.DEFAULT_MAX_CANDIDATES,
        )
        return DbPostProcessor.decode(output, side, config)
            .map { DetectionCandidate.fromDetectedQuad(it, side) }
            .sortedWith(
                compareBy<DetectionCandidate> { it.top }
                    .thenBy { it.left },
            )
    }

    private fun resizeFrameToRgbFloat(
        yPlane: ByteArray,
        width: Int,
        height: Int,
        outputWidth: Int,
        outputHeight: Int,
        normalization: Normalization,
    ): FloatArray {
        val out = FloatArray(outputWidth * outputHeight * CHANNELS)
        val scaleX = width.toFloat() / outputWidth.toFloat()
        val scaleY = height.toFloat() / outputHeight.toFloat()
        for (oy in 0 until outputHeight) {
            val sy = (oy + 0.5f) * scaleY - 0.5f
            for (ox in 0 until outputWidth) {
                val sx = (ox + 0.5f) * scaleX - 0.5f
                writeRgb(
                    out = out,
                    offset = (oy * outputWidth + ox) * CHANNELS,
                    gray = sampleBilinear(yPlane, width, height, sx, sy),
                    normalization = normalization,
                )
            }
        }
        return out
    }

    private fun resizeCropToRgbFloat(
        yPlane: ByteArray,
        width: Int,
        height: Int,
        box: DetectionCandidate,
        outputWidth: Int,
        outputHeight: Int,
        normalization: Normalization,
        rotate180: Boolean,
    ): FloatArray {
        val cropLeft = (box.left * width).coerceIn(0f, (width - 1).toFloat())
        val cropTop = (box.top * height).coerceIn(0f, (height - 1).toFloat())
        val cropRight = (box.right * width).coerceIn(cropLeft + 1f, width.toFloat())
        val cropBottom = (box.bottom * height).coerceIn(cropTop + 1f, height.toFloat())
        val cropWidth = cropRight - cropLeft
        val cropHeight = cropBottom - cropTop
        val out = FloatArray(outputWidth * outputHeight * CHANNELS)

        val heightScale = outputHeight.toFloat() / cropHeight
        val widthAtModelHeight = cropWidth * heightScale
        val resizeScale = if (widthAtModelHeight <= outputWidth) {
            heightScale
        } else {
            outputWidth.toFloat() / cropWidth
        }
        val resizedWidth = min(outputWidth, max(1, (cropWidth * resizeScale).roundToInt()))
        val resizedHeight = min(outputHeight, max(1, (cropHeight * resizeScale).roundToInt()))
        val srcStepX = cropWidth / resizedWidth.toFloat()
        val srcStepY = cropHeight / resizedHeight.toFloat()

        for (oy in 0 until resizedHeight) {
            val localY = (oy + 0.5f) * srcStepY - 0.5f
            for (ox in 0 until resizedWidth) {
                val localX = (ox + 0.5f) * srcStepX - 0.5f
                val sourceLocalX = if (rotate180) cropWidth - 1f - localX else localX
                val sourceLocalY = if (rotate180) cropHeight - 1f - localY else localY
                writeRgb(
                    out = out,
                    offset = (oy * outputWidth + ox) * CHANNELS,
                    gray = sampleBilinear(
                        yPlane = yPlane,
                        width = width,
                        height = height,
                        x = cropLeft + sourceLocalX,
                        y = cropTop + sourceLocalY,
                    ),
                    normalization = normalization,
                )
            }
        }
        return out
    }

    private fun sampleBilinear(
        yPlane: ByteArray,
        width: Int,
        height: Int,
        x: Float,
        y: Float,
    ): Float {
        val clampedX = x.coerceIn(0f, (width - 1).toFloat())
        val clampedY = y.coerceIn(0f, (height - 1).toFloat())
        val x0 = clampedX.toInt()
        val y0 = clampedY.toInt()
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val dx = clampedX - x0
        val dy = clampedY - y0
        val top = yAt(yPlane, width, x0, y0) * (1f - dx) + yAt(yPlane, width, x1, y0) * dx
        val bottom = yAt(yPlane, width, x0, y1) * (1f - dx) + yAt(yPlane, width, x1, y1) * dx
        return top * (1f - dy) + bottom * dy
    }

    private fun writeRgb(out: FloatArray, offset: Int, gray: Float, normalization: Normalization) {
        when (normalization) {
            Normalization.CENTERED -> {
                val value = gray / 127.5f - 1.0f
                out[offset] = value
                out[offset + 1] = value
                out[offset + 2] = value
            }
            Normalization.IMAGE_NET -> {
                val scaled = gray / 255.0f
                out[offset] = (scaled - IMAGE_NET_MEAN[0]) / IMAGE_NET_STD[0]
                out[offset + 1] = (scaled - IMAGE_NET_MEAN[1]) / IMAGE_NET_STD[1]
                out[offset + 2] = (scaled - IMAGE_NET_MEAN[2]) / IMAGE_NET_STD[2]
            }
        }
    }

    private fun decodeOrientation(output: FloatArray?): Int {
        if (output == null || output.size < 2) return 0
        return if (output[1] > output[0]) 180 else 0
    }

    private fun decodeRecognition(output: FloatArray, dictionary: List<String>): DecodedText {
        val classCount = dictionary.size + 1
        require(output.size % classCount == 0) {
            "Recognition output size ${output.size} is not divisible by class count $classCount"
        }
        val timesteps = output.size / classCount
        val builder = StringBuilder()
        var previousIndex = -1
        var confidenceSum = 0.0
        var emitted = 0
        for (t in 0 until timesteps) {
            val offset = t * classCount
            val (index, probability) = bestClass(output, offset, classCount)
            if (index != CTC_BLANK_INDEX && index != previousIndex) {
                builder.append(dictionary[index - 1])
                confidenceSum += probability.toDouble()
                emitted++
            }
            previousIndex = index
        }
        return DecodedText(
            text = builder.toString(),
            confidence = if (emitted == 0) 0f else (confidenceSum / emitted).toFloat(),
        )
    }

    private fun bestClass(output: FloatArray, offset: Int, classCount: Int): ClassProbability {
        var bestIndex = 0
        var bestValue = Float.NEGATIVE_INFINITY
        var sum = 0.0
        var probabilities = true
        for (i in 0 until classCount) {
            val value = output[offset + i]
            if (!value.isFinite() || value < 0f || value > 1f) probabilities = false
            sum += value.toDouble()
            if (value > bestValue) {
                bestValue = value
                bestIndex = i
            }
        }
        if (probabilities && sum in PROBABILITY_SUM_MIN..PROBABILITY_SUM_MAX) {
            return ClassProbability(bestIndex, bestValue.coerceIn(0f, 1f))
        }

        var expSum = 0.0
        for (i in 0 until classCount) {
            expSum += exp((output[offset + i] - bestValue).toDouble())
        }
        val probability = if (expSum <= 0.0) 0f else (1.0 / expSum).toFloat()
        return ClassProbability(bestIndex, probability)
    }

    private fun confidenceFromScore(score: Float): OcrFieldFusion.Confidence = when {
        score >= HIGH_CONFIDENCE_THRESHOLD -> OcrFieldFusion.Confidence.HIGH
        score >= MEDIUM_CONFIDENCE_THRESHOLD -> OcrFieldFusion.Confidence.MEDIUM
        else -> OcrFieldFusion.Confidence.LOW
    }

    private fun elapsedMs(startNs: Long): Long =
        (System.nanoTime() - startNs) / NANOS_PER_MS

    private fun yAt(yPlane: ByteArray, width: Int, x: Int, y: Int): Int =
        yPlane[y * width + x].toInt() and 0xFF

    private data class LoadedPipeline(
        val runner: PaddleOcrLiteRtRunner,
        val dictionary: List<String>,
    )

    private data class DetectionCandidate(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val score: Float,
        val boundingBox: FloatArray = floatArrayOf(left, top, right, top, right, bottom, left, bottom),
    ) {
        companion object {
            fun fromDetectedQuad(quad: DetectedQuad, mapSide: Int): DetectionCandidate {
                val normalized = quad.polygon
                    .flatMap { point ->
                        listOf(
                            (point.x.toFloat() / mapSide.toFloat()).coerceIn(0f, 1f),
                            (point.y.toFloat() / mapSide.toFloat()).coerceIn(0f, 1f),
                        )
                    }
                    .toFloatArray()
                val xs = quad.polygon.map { it.x.toFloat() / mapSide.toFloat() }
                val ys = quad.polygon.map { it.y.toFloat() / mapSide.toFloat() }
                return DetectionCandidate(
                    left = xs.minOrNull()?.coerceIn(0f, 1f) ?: 0f,
                    top = ys.minOrNull()?.coerceIn(0f, 1f) ?: 0f,
                    right = xs.maxOrNull()?.coerceIn(0f, 1f) ?: 0f,
                    bottom = ys.maxOrNull()?.coerceIn(0f, 1f) ?: 0f,
                    score = quad.score,
                    boundingBox = normalized,
                )
            }
        }
    }

    private data class DecodedText(val text: String, val confidence: Float)
    private data class ClassProbability(val index: Int, val probability: Float)

    private enum class Normalization { IMAGE_NET, CENTERED }

    companion object {
        private const val TAG = "LiteRtPaddleOcrBackend"
        private const val BYTES_PER_MB = 1024L * 1024L
        private const val CHANNELS = 3
        private const val DET_INPUT_WIDTH = 640
        private const val DET_INPUT_HEIGHT = 640
        private const val REC_INPUT_WIDTH = 320
        private const val REC_INPUT_HEIGHT = 48
        private const val CLS_INPUT_WIDTH = 160
        private const val CLS_INPUT_HEIGHT = 80
        private const val CTC_BLANK_INDEX = 0
        private const val HIGH_CONFIDENCE_THRESHOLD = 0.85f
        private const val MEDIUM_CONFIDENCE_THRESHOLD = 0.55f
        private const val PROBABILITY_SUM_MIN = 0.98
        private const val PROBABILITY_SUM_MAX = 1.02
        private const val NANOS_PER_MS = 1_000_000L

        private val IMAGE_NET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val IMAGE_NET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

        /**
         * OCR headroom covers three concurrent base-LiteRT model handles plus
         * two FloatArray(640*640*3) preprocessing buffers, while leaving room
         * for the LiteRT-LM Gemma KV-cache budget shared in this process.
         */
        private const val MEMORY_HEADROOM_BYTES = 192L * BYTES_PER_MB

        private val missingActivityManagerWarned = AtomicBoolean(false)

        private fun defaultAvailableMemoryProvider(context: Context): () -> Long = {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am == null) {
                if (missingActivityManagerWarned.compareAndSet(false, true)) {
                    MindlayerLog.w(TAG, "ActivityManager unavailable; OCR memory headroom check disabled.")
                }
                Long.MAX_VALUE
            } else {
                val info = ActivityManager.MemoryInfo()
                am.getMemoryInfo(info)
                info.availMem
            }
        }

        internal fun forTesting(
            context: Context,
            memoryHeadroomBytes: Long = 0L,
            availableMemoryProvider: () -> Long = { Long.MAX_VALUE },
            runnerFactory: PaddleOcrLiteRtRunnerFactory,
        ): LiteRtPaddleOcrBackend = LiteRtPaddleOcrBackend(
            context = context,
            memoryHeadroomBytes = memoryHeadroomBytes,
            availableMemoryProvider = availableMemoryProvider,
            runnerFactory = runnerFactory,
            logRepository = null,
        )
    }
}
