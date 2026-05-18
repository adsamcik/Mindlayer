package com.adsamcik.mindlayer.service.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.logging.safeLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Phase 1 PR C2 LiteRT-based PaddleOCR backend **scaffold**.
 *
 * This class owns the lifecycle seam for the base
 * ``com.google.ai.edge.litert`` runtime. Like [LiteRtEmbeddingBackend]
 * in its first commit, this scaffold deliberately ships **without**
 * the inner PP-OCRv5 mobile pipeline (det → cls → rec → assemble)
 * wired to native interpreters — those run only on a device with the
 * full Paddle→ONNX→TFLite conversion pipeline output present, which
 * the CI conversion workflow (`.github/workflows/build-paddleocr-models.yml`,
 * PR B) produces but does not yet upload.
 *
 * # What this PR ships
 *
 *  - The interface contract ([PaddleOcrBackend])
 *  - Lifecycle (initialize / shutdown) with the same threading +
 *    idempotence rules as [LiteRtEmbeddingBackend]
 *  - Memory headroom enforcement at init time
 *  - Backend resolution chain (GPU → CPU) honouring caller preference
 *  - `recognise` implementation that **fails closed** with a precise
 *    [IllegalStateException] message until PR C2.5 wires the native
 *    interpreters. Higher layers (PR C3 + tests) substitute a fake
 *    backend.
 *
 * # What is deferred
 *
 *  - ``LiteRT Interpreter`` instances for det / rec / cls ``.tflite``
 *    files. The bundle's paths are stored; interpreter creation runs
 *    on the IO dispatcher with the appropriate delegate (GPU when
 *    available; CPU fallback otherwise).
 *  - Y-plane preprocessing pipeline (resize, normalize) — matches
 *    PaddleOCR PP-OCRv5 mobile input shape (det = 640x640 RGB-normalized
 *    float32; rec = 48x320 RGB; cls = 48x192 RGB).
 *  - Detection-output decoding (PSE / DB heads, polygon contour
 *    extraction).
 *  - Recognition CTC decoding using the bundle's character dictionary.
 *  - Verify-on-device GPU/NPU coexistence with the LiteRT-LM (Gemma)
 *    runtime — same coexistence story as [LiteRtEmbeddingBackend].
 *
 * Each deferred piece is marked with a ``TODO(verifyOnDevice)`` so a
 * future PR can grep for them and pick them up.
 */
class LiteRtPaddleOcrBackend(
    private val context: Context,
    private val memoryHeadroomBytes: Long = MEMORY_HEADROOM_BYTES,
    private val availableMemoryProvider: () -> Long = {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am == null) {
            Long.MAX_VALUE
        } else {
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.availMem
        }
    },
) : PaddleOcrBackend {

    private val mutex = Mutex()

    @Volatile
    private var loadedBundle: PaddleOcrModelInfo? = null

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
        loadedBundle?.let { current ->
            if (current.id == bundle.id &&
                current.detectionPath == bundle.detectionPath
            ) {
                return
            }
        }

        checkMemoryHeadroom(bundle)
        val selectedBackend = resolveBackend(preferredBackend)

        try {
            withContext(Dispatchers.IO) {
                verifyBundleFilesExist(bundle)
                // TODO(verifyOnDevice): create LiteRT Interpreters from the
                // bundle's det/rec/cls .tflite files, attach the selected
                // delegate (GPU when selectedBackend == "GPU" and the device
                // supports it, CPU otherwise), and warm them with a synthetic
                // 640x640 / 48x320 / 48x192 input. Kept out of PR C2 execution
                // because the paddleocr_model AI Pack does not yet ship
                // actual .tflite payloads in tree (intentional — see
                // .gitignore + .github/workflows/build-paddleocr-models.yml).
            }
            backendLabel = selectedBackend
            loadedBundle = bundle
            MindlayerLog.i(
                TAG,
                "PaddleOCR backend ready: id=${bundle.id}, backend=$selectedBackend, " +
                    "size=${bundle.totalSizeBytes}B, hasCls=${bundle.hasOrientationClassifier}",
            )
        } catch (t: Throwable) {
            backendLabel = "NONE"
            loadedBundle = null
            throw t
        }
    }

    override suspend fun recognise(
        yPlane: ByteArray,
        width: Int,
        height: Int,
        config: OcrEngineConfig,
    ): OcrEngineOutput = mutex.withLock {
        check(loadedBundle != null) {
            "PaddleOCR backend not initialised; call initialize() first."
        }
        // TODO(verifyOnDevice): the full PP-OCRv5 mobile pipeline:
        //   1. Resize Y-plane -> 640x640, normalize to [0..1] mean/std,
        //      RGB-pack via grayscale broadcast (PaddleOCR's det head
        //      expects 3-channel input even on grayscale captures).
        //   2. Run det interpreter -> probability map -> contour
        //      extraction -> minAreaRect polygons.
        //   3. (Optional) For each polygon: warpPerspective to 48xK
        //      patch, run cls interpreter, rotate 180 if upside-down.
        //   4. For each patch: resize to 48x320 keeping aspect ratio,
        //      pad if shorter, run rec interpreter -> per-timestep
        //      softmax -> CTC-greedy-decode using dictionary.
        //   5. Assemble OcrTextLine list in detection order.
        //
        // Until that lands, fail closed with a precise message so
        // OcrSessionManager (PR C3) can route through a fake backend
        // for unit tests and the binder layer surfaces NOT_SUPPORTED
        // on production until the conversion pipeline output is
        // bundled.
        throw IllegalStateException(
            "PaddleOCR recognise() pipeline not yet wired — Phase 1 PR C2 ships the " +
                "scaffold only. The det/cls/rec interpreter wiring lands in a follow-up " +
                "after the paddleocr_model conversion artifacts are uploaded via the CI " +
                "pipeline in .github/workflows/build-paddleocr-models.yml.",
        )
    }

    override suspend fun shutdown(): Unit = mutex.withLock {
        if (loadedBundle == null) return
        try {
            withContext(Dispatchers.IO) {
                // TODO(verifyOnDevice): close the three LiteRT Interpreter
                // instances + delegate handles. The contract is idempotent
                // so calling shutdown on an unloaded backend is a no-op
                // (handled by the early return above).
            }
        } catch (t: Throwable) {
            MindlayerLog.w(TAG, "PaddleOCR shutdown error: ${t.safeLabel()}", throwable = null)
        } finally {
            loadedBundle = null
            backendLabel = "NONE"
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun checkMemoryHeadroom(bundle: PaddleOcrModelInfo) {
        val available = availableMemoryProvider()
        val required = bundle.totalSizeBytes + memoryHeadroomBytes
        if (available < required) {
            throw LowMemoryException(
                availMb = available / (1024L * 1024L),
                requiredMb = required / (1024L * 1024L),
            )
        }
    }

    private fun availableMemoryBytes(): Long = availableMemoryProvider()

    private fun resolveBackend(preferred: String?): String = when (preferred?.uppercase()) {
        // Caller asked for a specific backend; honor it if known. The
        // actual delegate selection at TODO(verifyOnDevice) time will
        // fall back to CPU if GPU/NPU init fails — the label here is
        // best-effort intent, the actual runtime label is overwritten
        // post-delegate-load if that lands.
        "GPU", "CPU", "NPU" -> preferred.uppercase()
        null -> "GPU" // default — same as LiteRtEmbeddingBackend
        else -> "CPU" // unknown label falls back conservatively
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

    private companion object {
        private const val TAG = "LiteRtPaddleOcrBackend"

        // Matches LiteRtEmbeddingBackend's headroom budget. PP-OCRv5
        // mobile bundles are ~30 MiB total (det 4.4 MiB + rec 11 MiB +
        // cls 1.4 MiB + dict 32 KiB) so the engine plus working buffers
        // fit comfortably under a 64 MiB headroom.
        private const val MEMORY_HEADROOM_BYTES = 64L * 1024L * 1024L
    }
}
