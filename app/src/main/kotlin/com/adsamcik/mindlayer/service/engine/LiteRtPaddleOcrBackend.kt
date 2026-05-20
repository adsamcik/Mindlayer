package com.adsamcik.mindlayer.service.engine

import android.app.ActivityManager
import android.content.Context
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.logging.safeLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
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
) : PaddleOcrBackend {

    constructor(
        context: Context,
        memoryHeadroomBytes: Long = MEMORY_HEADROOM_BYTES,
        availableMemoryProvider: () -> Long = defaultAvailableMemoryProvider(context),
    ) : this(
        context = context,
        memoryHeadroomBytes = memoryHeadroomBytes,
        availableMemoryProvider = availableMemoryProvider,
        runnerFactory = { bundle, acceleratorLabel ->
            RealPaddleOcrLiteRtRunner.create(bundle, acceleratorLabel)
        },
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
        val loaded = try {
            withContext(Dispatchers.IO) {
                verifyBundleFilesExist(bundle)
                val loadedDictionary = loadDictionary(bundle.dictionaryPath)
                val newRunner = runnerFactory(bundle, selectedBackend)
                pendingRunner = newRunner
                LoadedPipeline(
                    runner = newRunner,
                    dictionary = loadedDictionary,
                )
            }
        } catch (t: Throwable) {
            pendingRunner?.runCatching { close() }
            MindlayerLog.w(TAG, "PaddleOCR backend init failed: ${t.safeLabel()}", throwable = null)
            throw t
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
        MindlayerLog.i(
            TAG,
            "PaddleOCR backend ready: id=${bundle.id}, backend=$selectedBackend, " +
                "size=${bundle.totalSizeBytes}B, hasCls=${bundle.hasOrientationClassifier}",
        )
    }

    override suspend fun recognise(
        yPlane: ByteArray,
        width: Int,
        height: Int,
        config: OcrEngineConfig,
    ): OcrEngineOutput = mutex.withLock {
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

    override suspend fun shutdown(): Unit = mutex.withLock {
        if (loadedBundle == null) return
        try {
            withContext(Dispatchers.IO) {
                runner?.runCatching { close() }
            }
        } catch (t: Throwable) {
            MindlayerLog.w(TAG, "PaddleOCR shutdown error: ${t.safeLabel()}", throwable = null)
        } finally {
            runner = null
            dictionary = emptyList()
            loadedBundle = null
            backendLabel = "NONE"
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
    private fun resolveBackend(preferred: String?): String = when (preferred?.uppercase()) {
        "GPU", "CPU", "NPU" -> preferred.uppercase()
        null -> "CPU"
        else -> "CPU"
    }

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
        val chars = File(path).readLines(Charsets.UTF_8)
            .filter { it.isNotEmpty() }
        require(chars.isNotEmpty()) { "PaddleOCR dictionary is empty: $path" }
        return chars
    }

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
        val visited = BooleanArray(output.size)
        val queue = IntArray(output.size)
        val candidates = mutableListOf<DetectionCandidate>()
        val minArea = max(MIN_DETECTION_AREA_PIXELS, (side * side) / MIN_DETECTION_AREA_DIVISOR)
        val minSide = max(1, side / MIN_DETECTION_SIDE_DIVISOR)

        for (y in 0 until side) {
            for (x in 0 until side) {
                val start = y * side + x
                if (visited[start]) continue
                val startScore = activation(output[start])
                if (startScore < DETECTION_PIXEL_THRESHOLD) {
                    visited[start] = true
                    continue
                }

                var head = 0
                var tail = 0
                queue[tail++] = start
                visited[start] = true
                var left = x
                var right = x
                var top = y
                var bottom = y
                var area = 0
                var scoreSum = 0.0

                while (head < tail) {
                    val index = queue[head++]
                    val px = index % side
                    val py = index / side
                    val score = activation(output[index])
                    area++
                    scoreSum += score.toDouble()
                    left = min(left, px)
                    right = max(right, px)
                    top = min(top, py)
                    bottom = max(bottom, py)

                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = px + dx
                            val ny = py + dy
                            if (nx !in 0 until side || ny !in 0 until side) continue
                            val ni = ny * side + nx
                            if (visited[ni]) continue
                            if (activation(output[ni]) >= DETECTION_PIXEL_THRESHOLD) {
                                visited[ni] = true
                                queue[tail++] = ni
                            }
                        }
                    }
                }

                val boxWidth = right - left + 1
                val boxHeight = bottom - top + 1
                val avgScore = (scoreSum / area.toDouble()).toFloat()
                if (area >= minArea &&
                    boxWidth >= minSide &&
                    boxHeight >= minSide &&
                    avgScore >= DETECTION_BOX_THRESHOLD
                ) {
                    candidates += DetectionCandidate.fromMapBox(
                        left = left,
                        top = top,
                        rightExclusive = right + 1,
                        bottomExclusive = bottom + 1,
                        mapSide = side,
                        score = avgScore,
                    )
                }
            }
        }

        val strongest = candidates.sortedByDescending { it.score }
            .let { if (maxLines > 0) it.take(maxLines) else it }
        return strongest.sortedWith(
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
        for (oy in 0 until outputHeight) {
            val sy = ((oy.toLong() * height) / outputHeight).toInt().coerceIn(0, height - 1)
            for (ox in 0 until outputWidth) {
                val sx = ((ox.toLong() * width) / outputWidth).toInt().coerceIn(0, width - 1)
                writeRgb(out, (oy * outputWidth + ox) * CHANNELS, yAt(yPlane, width, sx, sy), normalization)
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
        val cropLeft = floor(box.left * width).toInt().coerceIn(0, width - 1)
        val cropTop = floor(box.top * height).toInt().coerceIn(0, height - 1)
        val cropRight = ceil(box.right * width).toInt().coerceIn(cropLeft + 1, width)
        val cropBottom = ceil(box.bottom * height).toInt().coerceIn(cropTop + 1, height)
        val cropWidth = cropRight - cropLeft
        val cropHeight = cropBottom - cropTop
        val out = FloatArray(outputWidth * outputHeight * CHANNELS)

        for (oy in 0 until outputHeight) {
            val localY = ((oy.toLong() * cropHeight) / outputHeight).toInt().coerceIn(0, cropHeight - 1)
            for (ox in 0 until outputWidth) {
                val localX = ((ox.toLong() * cropWidth) / outputWidth).toInt().coerceIn(0, cropWidth - 1)
                val sx = cropLeft + if (rotate180) cropWidth - 1 - localX else localX
                val sy = cropTop + if (rotate180) cropHeight - 1 - localY else localY
                writeRgb(out, (oy * outputWidth + ox) * CHANNELS, yAt(yPlane, width, sx, sy), normalization)
            }
        }
        return out
    }

    private fun writeRgb(out: FloatArray, offset: Int, gray: Int, normalization: Normalization) {
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

    private fun activation(value: Float): Float = when {
        !value.isFinite() -> 0f
        value in 0f..1f -> value
        else -> (1.0 / (1.0 + exp(-value.toDouble()))).toFloat()
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
    ) {
        val boundingBox: FloatArray = floatArrayOf(left, top, right, top, right, bottom, left, bottom)

        companion object {
            fun fromMapBox(
                left: Int,
                top: Int,
                rightExclusive: Int,
                bottomExclusive: Int,
                mapSide: Int,
                score: Float,
            ): DetectionCandidate = DetectionCandidate(
                left = (left.toFloat() / mapSide).coerceIn(0f, 1f),
                top = (top.toFloat() / mapSide).coerceIn(0f, 1f),
                right = (rightExclusive.toFloat() / mapSide).coerceIn(0f, 1f),
                bottom = (bottomExclusive.toFloat() / mapSide).coerceIn(0f, 1f),
                score = score,
            )
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
        private const val DETECTION_PIXEL_THRESHOLD = 0.3f
        private const val DETECTION_BOX_THRESHOLD = 0.6f
        private const val MIN_DETECTION_AREA_PIXELS = 4
        private const val MIN_DETECTION_AREA_DIVISOR = 20_000
        private const val MIN_DETECTION_SIDE_DIVISOR = 160
        private const val CTC_BLANK_INDEX = 0
        private const val HIGH_CONFIDENCE_THRESHOLD = 0.85f
        private const val MEDIUM_CONFIDENCE_THRESHOLD = 0.55f
        private const val PROBABILITY_SUM_MIN = 0.98
        private const val PROBABILITY_SUM_MAX = 1.02
        private const val NANOS_PER_MS = 1_000_000L

        private val IMAGE_NET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val IMAGE_NET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

        // Matches the production bundle size plus native working buffers.
        private const val MEMORY_HEADROOM_BYTES = 64L * BYTES_PER_MB

        private fun defaultAvailableMemoryProvider(context: Context): () -> Long = {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am == null) {
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
        )
    }
}
