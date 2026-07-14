package com.adsamcik.mindlayer.service.engine

import android.app.ActivityManager
import android.content.Context
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.logging.LogRepository
import com.adsamcik.mindlayer.service.logging.safeLabel
import com.adsamcik.mindlayer.service.logging.safeLabelWithDetail
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
 * EmbeddingGemma remains tracked in `docs/architecture/LITERT_COEXISTENCE.md`.
 */
class LiteRtPaddleOcrBackend internal constructor(
    private val context: Context,
    private val memoryHeadroomBytes: Long = MEMORY_HEADROOM_BYTES,
    private val availableMemoryProvider: () -> Long = defaultAvailableMemoryProvider(context),
    private val runnerFactory: PaddleOcrLiteRtRunnerFactory,
    private val logRepository: LogRepository? = null,
    private val failureCache: OcrAcceleratorFailureCache = OcrAcceleratorFailureCache(context),
    private val clock: () -> Long = { System.currentTimeMillis() },
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
        failureCache = OcrAcceleratorFailureCache(context),
        clock = { System.currentTimeMillis() },
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
        val resolvedRaw = resolveBackend(preferredBackend)

        // Cooldown short-circuit: when the caller did not pin a specific
        // backend, the resolver picked a non-CPU backend, and the failure
        // cache shows a recent failure within the cooldown window, skip the
        // doomed accelerator init and go straight to CPU. Saves 1-2s of
        // guaranteed-doomed init + battery on every cold start AND every
        // memory-pressure unload+reload cycle on stable-failure devices.
        //
        // Explicit caller picks (`preferredBackend != null`) always win,
        // even for the cached backend — the caller is asking for a specific
        // accelerator and the cache MUST NOT second-guess that.
        val cooldownRecord = failureCache.snapshot()
        val cooldownSkip = preferredBackend == null &&
            resolvedRaw != "CPU" &&
            cooldownRecord != null &&
            cooldownRecord.isInCooldown(clock = clock, cooldownMs = failureCache.cooldownMs)
        val selectedBackend = if (cooldownSkip) {
            MindlayerLog.i(
                TAG,
                "PaddleOCR $resolvedRaw init skipped: prior failure within cooldown window " +
                    "(cachedBackend=${cooldownRecord.lastFailedBackend}, " +
                    "ageMs=${clock() - cooldownRecord.lastFailedAtMs}, " +
                    "failureCount=${cooldownRecord.failureCount})",
            )
            recordOcrCooldownSkipDecision(
                resolverPick = resolvedRaw,
                cooldownRecord = cooldownRecord,
            )
            "CPU"
        } else {
            resolvedRaw
        }

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

        // Memory headroom is checked once, before any init attempt. The
        // throw escapes via the outer suspend boundary, never enters the
        // fallback dance below.
        checkMemoryHeadroom(bundle)

        try {
            attemptInit(bundle, selectedBackend, initStartNs)
            // Successful init on a non-CPU backend clears the cache so a
            // transient failure is not sticky for the full cooldown window.
            // Skip the clear if we short-circuited to CPU via cooldown
            // (cooldownSkip=true) — the cache is still authoritative for
            // the resolver-picked backend that we never even tried.
            if (selectedBackend != "CPU" && !cooldownSkip && cooldownRecord != null) {
                failureCache.clear()
                MindlayerLog.i(
                    TAG,
                    "PaddleOCR $selectedBackend init recovered; cleared accelerator failure cache",
                )
            }
        } catch (t: Throwable) {
            // LowMemoryException stays terminal — callers (engine + UI) need
            // to see it. CPU is the last-resort backend, so a CPU-forced
            // failure is also terminal: no fallback dance, original behaviour.
            // LowMemory is also NEVER cached: memory-pressure failures are
            // transient and would otherwise poison a 24 h skip window over
            // a momentary RAM dip.
            if (t is LowMemoryException || selectedBackend == "CPU") {
                MindlayerLog.w(
                    TAG,
                    "PaddleOCR backend init failed: ${t.safeLabelWithDetail()}",
                    throwable = null,
                )
                throw t
            }
            MindlayerLog.w(
                TAG,
                "PaddleOCR $selectedBackend init failed (${t.safeLabelWithDetail()}), " +
                    "falling back to CPU",
                throwable = null,
            )
            // Record BEFORE the CPU fallback attempt. If CPU also throws,
            // the GPU failure is still persisted so the next cold-start can
            // skip it; the alternative (record after CPU success) loses the
            // GPU failure when the whole init path dies.
            failureCache.recordFailure(backend = selectedBackend, safeLabel = t.safeLabel())
            recordOcrFallbackDecision(originalBackend = selectedBackend)
            try {
                attemptInit(bundle, "CPU", initStartNs)
                MindlayerLog.w(
                    TAG,
                    "PaddleOCR CPU fallback succeeded (active=CPU)",
                    throwable = null,
                )
            } catch (cpuT: Throwable) {
                MindlayerLog.w(
                    TAG,
                    "PaddleOCR CPU last-resort init failed (${cpuT.safeLabelWithDetail()})",
                    throwable = null,
                )
                throw cpuT
            }
        }
    }

    /**
     * Performs a single init attempt with the given backend label. On success
     * publishes the new runner / dictionary / bundle / backend label and logs
     * "PaddleOCR backend ready". On failure closes any partially-created
     * runner and rethrows; the previous state is untouched so the caller can
     * decide whether to fall back to a different backend.
     */
    private suspend fun attemptInit(
        bundle: PaddleOcrModelInfo,
        backend: String,
        initStartNs: Long,
    ) {
        var pendingRunner: PaddleOcrLiteRtRunner? = null
        try {
            val loaded = withContext(Dispatchers.IO) {
                verifyBundleFilesExist(bundle)
                verifyRecognitionModelUsable(bundle.recognitionPath)
                val loadedDictionary = loadDictionary(bundle.dictionaryPath)
                val newRunner = runnerFactory(bundle, backend)
                pendingRunner = newRunner
                // Runtime smoke test on non-CPU backends: a single warm-up
                // det inference validates that the GPU/NPU runtime can
                // actually allocate input buffers AND produces finite
                // output. Compile-time success (e.g. "Replacing 322/322
                // node(s) with delegate") does NOT guarantee runtime
                // success: observed on Android emulator (swiftshader
                // LITERT_OPENGL backend) the float [1,640,640,3] det
                // input buffer allocation throws at createInputBuffers
                // because the host GLES driver refuses 3-channel float
                // texture imports of that size. Catching at warm-up
                // time lets the outer `initialize()` catch perform the
                // GPU→CPU fallback before any user-facing recognise().
                // Skipped on CPU since CPU XNNPACK has no equivalent
                // allocation pitfalls and the warm-up is a real ~100-
                // 1000ms compute cost we shouldn't pay twice.
                if (backend != "CPU") {
                    validateBackendNumerics(newRunner, backend)
                }
                LoadedPipeline(
                    runner = newRunner,
                    dictionary = loadedDictionary,
                )
            }
            val previousRunner = runner
            runner = loaded.runner
            dictionary = loaded.dictionary
            backendLabel = backend
            loadedBundle = bundle
            pendingRunner = null
            if (previousRunner !== loaded.runner) {
                previousRunner?.runCatching { close() }
            }
            val durationMs = elapsedMs(initStartNs)
            logRepository?.logOcrBackendReady(
                backend = backend,
                bundleId = bundle.id,
                durationMs = durationMs,
            )
            MindlayerLog.i(
                TAG,
                "PaddleOCR backend ready: id=${bundle.id}, backend=$backend, " +
                    "size=${bundle.totalSizeBytes}B, hasCls=${bundle.hasOrientationClassifier}",
            )
        } catch (t: Throwable) {
            pendingRunner?.runCatching { close() }
            throw t
        } finally {
            pendingRunner = null
        }
    }

    /**
     * Run a single warm-up det inference and reject the backend if input
     * buffer allocation or the inference itself fails, or if the output
     * contains NaN/Inf.
     *
     * Two real-device classes of failure this catches:
     *
     * 1. Runtime-buffer-allocation failure on GPU backends whose driver
     *    cannot honour the requested tensor layout. Observed on the
     *    Android emulator's swiftshader LITERT_OPENGL backend, where
     *    `createInputBuffers()` throws `LiteRtException("Failed to
     *    create input buffers")` for a `float [1,640,640,3]` det input
     *    even though compile reported 100% delegated nodes.
     *
     * 2. FP16 overflow / NaN in the SVTR attention softmax of the rec
     *    sub-model. The QKV-split surgery (`scripts/build-paddleocr-
     *    models/onnx_split_qkv.py`) makes rec GPU-compileable but the
     *    attention math still benefits from FP32 + infiniteFloatCapping
     *    (configured in [RealPaddleOcrLiteRtRunner]); this smoke test is
     *    the second line of defence if a future LiteRT bump regresses
     *    that path.
     *
     * Throws [BackendNumericsException] (which extends [IllegalStateException]
     * so the outer `initialize` catch performs the same GPU→CPU fallback
     * dance it already does for compile failures).
     */
    private fun validateBackendNumerics(
        runner: PaddleOcrLiteRtRunner,
        backend: String,
    ) {
        val warmupInput = FloatArray(DET_INPUT_WIDTH * DET_INPUT_HEIGHT * CHANNELS)
        val output = runner.runDetection(warmupInput)
        val firstBadIdx = output.indexOfFirst { !it.isFinite() }
        if (firstBadIdx >= 0) {
            throw BackendNumericsException(
                "PaddleOCR $backend det warm-up produced non-finite value " +
                    "at index $firstBadIdx (value=${output[firstBadIdx]}). " +
                    "Backend will fall back to CPU.",
            )
        }
    }

    /**
     * Records a follow-up [LiteRtAcceleratorResolver] decision reflecting the
     * runtime fallback. Dashboards surface `latestDecision("ocr")`, so this
     * keeps the "active backend" display in sync with the runner that ended
     * up loaded, while preserving the original-attempt chain for diagnostics.
     */
    private fun recordOcrFallbackDecision(originalBackend: String) {
        val original = LiteRtAcceleratorResolver.latestDecision("ocr")
        val chainedAttempts = (original?.attempted ?: emptyList()) +
            listOf(originalBackend to "init-failed", "CPU" to "selected")
        LiteRtAcceleratorResolver.recordDecision(
            featureName = "ocr",
            backend = "CPU",
            reason = "${originalBackend}_INIT_FAILED_FALLBACK_CPU",
            attempted = chainedAttempts,
        )
    }

    /**
     * Records a [LiteRtAcceleratorResolver] decision reflecting that the
     * resolver-picked accelerator was skipped because of a recent prior
     * failure (within [OcrAcceleratorFailureCache.cooldownMs]). The dashboard
     * sees backend=CPU + a "_COOLDOWN_SKIP" reason so the OCR status row can
     * distinguish a cooldown-skipped backend from a real CPU pick.
     */
    private fun recordOcrCooldownSkipDecision(
        resolverPick: String,
        cooldownRecord: OcrAcceleratorFailureCache.FailureRecord,
    ) {
        val original = LiteRtAcceleratorResolver.latestDecision("ocr")
        val chainedAttempts = (original?.attempted ?: emptyList()) +
            listOf(resolverPick to "cooldown-skip", "CPU" to "selected")
        LiteRtAcceleratorResolver.recordDecision(
            featureName = "ocr",
            backend = "CPU",
            reason = "${resolverPick}_COOLDOWN_SKIP_CPU_failureCount=${cooldownRecord.failureCount}",
            attempted = chainedAttempts,
        )
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
     * Resolves the LiteRT accelerator for the OCR pipeline. Mirrors chat:
     * `null` defaults to GPU; explicit `NPU` is honored when SoC + native libs
     * pass the probe and otherwise falls back to GPU; explicit `CPU`/`GPU` is
     * always honored. `RealPaddleOcrLiteRtRunner` creates three sequential
     * `CompiledModel` instances (det + rec + cls), so this site has the
     * highest exposure to LiteRT issue #5264 — see
     * `docs/architecture/LITERT_COEXISTENCE.md`.
     */
    private fun resolveBackend(preferred: String?): String =
        LiteRtAcceleratorResolver.resolveBackend(
            requested = preferred,
            featureName = "ocr",
            nativeLibraryDir = context.applicationInfo.nativeLibraryDir,
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

    /**
     * Fail-fast guard mirroring the
     * `scripts/build-paddleocr-models/convert.sh` build-time check: a
     * recognition model converted without LayerNorm decomposition leaves an
     * unresolved `ONNX_LAYERNORMALIZATION` custom op. The detection model (a
     * pure CNN) still loads, so the break otherwise surfaces only as a
     * per-frame `LiteRtException: Failed to invoke the compiled model` at
     * `recognise()` time — on *every* accelerator (GPU and CPU), because an
     * unresolved custom op has no kernel regardless of delegate. Reject such a
     * model at init with an actionable message instead of silently degrading
     * OCR to 0 recognised lines.
     *
     * Best-effort: a read error never blocks a load the LiteRT runtime would
     * otherwise attempt (it will surface its own error then).
     */
    private fun verifyRecognitionModelUsable(recognitionPath: String) {
        val broken = try {
            fileContainsAscii(File(recognitionPath), UNRESOLVED_LAYERNORM_MARKER)
        } catch (t: Throwable) {
            MindlayerLog.w(
                TAG,
                "PaddleOCR rec model scan failed (${t.safeLabel()}); proceeding to load",
                throwable = null,
            )
            false
        }
        if (broken) {
            throw IllegalStateException(
                "PaddleOCR recognition model is stale or mis-converted: it contains an " +
                    "unresolved $UNRESOLVED_LAYERNORM_MARKER_STR custom op that LiteRT cannot " +
                    "invoke on any accelerator. Rebuild the models with " +
                    "scripts/build-paddleocr-models (onnx2tf -tb tf_converter decomposes " +
                    "LayerNorm to native ops) and re-deploy / re-push them. See " +
                    "docs/ocr/PADDLEOCR_GPU_INVESTIGATION.md.",
            )
        }
    }

    /**
     * Streaming substring search so a ~16 MB recognition model is never loaded
     * into a single String/ByteArray. Carries `marker.size - 1` bytes across
     * chunk boundaries so a marker split across two reads is still found.
     */
    private fun fileContainsAscii(file: File, marker: ByteArray): Boolean {
        if (marker.isEmpty() || file.length() < marker.size) return false
        val overlap = marker.size - 1
        val chunk = 1 shl 16
        val buffer = ByteArray(chunk + overlap)
        file.inputStream().buffered().use { input ->
            var carried = 0
            while (true) {
                val read = input.read(buffer, carried, chunk)
                if (read < 0) break
                val available = carried + read
                if (indexOfBytes(buffer, available, marker) >= 0) return true
                carried = minOf(overlap, available)
                if (carried > 0) {
                    System.arraycopy(buffer, available - carried, buffer, 0, carried)
                }
            }
        }
        return false
    }

    private fun indexOfBytes(haystack: ByteArray, length: Int, needle: ByteArray): Int {
        if (needle.isEmpty() || length < needle.size) return -1
        val last = length - needle.size
        outer@ for (i in 0..last) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
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

        val totalDurationMs = elapsedMs(totalStart)
        MindlayerLog.i(
            TAG,
            "OCR recognise: backend=$activeBackend, lines=${lines.size}, " +
                "det=${detDurationMs}ms, rec=${recDurationMs}ms, cls=${clsDurationMs}ms, " +
                "total=${totalDurationMs}ms, candidates=${candidates.size}",
        )
        return OcrEngineOutput(
            lines = lines,
            backend = activeBackend,
            detDurationMs = detDurationMs,
            recDurationMs = recDurationMs,
            clsDurationMs = clsDurationMs,
            totalDurationMs = totalDurationMs,
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

    /**
     * Thrown by [validateBackendNumerics] when a non-CPU PaddleOCR backend
     * either fails the runtime smoke test (input-buffer alloc, run-call
     * exception) or returns non-finite values. Subclasses
     * [IllegalStateException] so the outer init catch performs the same
     * GPU→CPU fallback dance it already uses for compile-time failures.
     */
    class BackendNumericsException(message: String) : IllegalStateException(message)

    companion object {
        private const val TAG = "LiteRtPaddleOcrBackend"
        private const val BYTES_PER_MB = 1024L * 1024L

        /**
         * Custom-op name left behind when a PP-OCRv5 rec model is converted
         * without LayerNorm decomposition. LiteRT has no kernel for it, so the
         * model fails to invoke on every accelerator. Mirrors the guard in
         * `scripts/build-paddleocr-models/convert.sh`.
         */
        private const val UNRESOLVED_LAYERNORM_MARKER_STR = "ONNX_LAYERNORMALIZATION"
        private val UNRESOLVED_LAYERNORM_MARKER =
            UNRESOLVED_LAYERNORM_MARKER_STR.toByteArray(Charsets.US_ASCII)

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
            failureCache: OcrAcceleratorFailureCache = OcrAcceleratorFailureCache(context),
            clock: () -> Long = { System.currentTimeMillis() },
        ): LiteRtPaddleOcrBackend = LiteRtPaddleOcrBackend(
            context = context,
            memoryHeadroomBytes = memoryHeadroomBytes,
            availableMemoryProvider = availableMemoryProvider,
            runnerFactory = runnerFactory,
            logRepository = null,
            failureCache = failureCache,
            clock = clock,
        )
    }
}
