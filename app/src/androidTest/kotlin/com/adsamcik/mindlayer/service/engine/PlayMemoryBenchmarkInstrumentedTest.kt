package com.adsamcik.mindlayer.service.engine

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.adsamcik.mindlayer.ServiceCapabilities
import com.adsamcik.mindlayer.sdk.EmbeddingHandle
import com.adsamcik.mindlayer.sdk.InferenceBackend
import com.adsamcik.mindlayer.sdk.Mindlayer
import com.adsamcik.mindlayer.sdk.MindlayerSession
import com.adsamcik.mindlayer.sdk.OcrProfile
import com.adsamcik.mindlayer.service.ui.MainActivity
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/**
 * Real-service workload driver for `scripts/benchmark-play-memory.ps1`.
 *
 * Sampling stays on the host so the benchmark sees both the client and `:ml`
 * PIDs, including the process replacement required to grow an already-loaded
 * LiteRT-LM context. This driver labels and holds the real production phases:
 *
 *  1. cold bind and unloaded service;
 *  2. default-context prewarm and first text inference;
 *  3. text-plus-image inference;
 *  4. resident chat + EmbeddingGemma + PaddleOCR, idle and concurrently active;
 *  5. safe restart into the maximum supported context and its first inference;
 *  6. dashboard TOP, active-inference FGS, background, unbound, and cached tail.
 *
 * Every transition is appended to an internal event journal as well as written
 * to the atomic current-phase marker. The host records Android's UID state,
 * current top activity, foreground-service flag, and `oom_score_adj`; phase
 * names alone are never accepted as lifecycle proof.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class PlayMemoryBenchmarkInstrumentedTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val args
        get() = InstrumentationRegistry.getArguments()

    @Test
    fun representative_service_workload() = runBlocking<Unit> {
        val marker = PhaseMarker(context)
        val defaultContextTokens = intArg(
            "defaultContextTokens",
            DEFAULT_CONTEXT_TOKENS,
            MIN_CONTEXT_TOKENS..MAX_CONTEXT_TOKENS,
        )
        val maxContextTokens = intArg(
            "maxContextTokens",
            MAX_CONTEXT_TOKENS,
            defaultContextTokens..MAX_CONTEXT_TOKENS,
        )
        val backend = backendArg()
        val phaseHoldMs = intArg("phaseHoldMs", DEFAULT_PHASE_HOLD_MS, 1_500..30_000)
        val promptChars = intArg("promptChars", DEFAULT_PROMPT_CHARS, 256..50_000)
        val bitmapWidth = intArg("bitmapWidth", DEFAULT_BITMAP_WIDTH, 1..MAX_BITMAP_DIMENSION)
        val bitmapHeight = intArg("bitmapHeight", DEFAULT_BITMAP_HEIGHT, 1..MAX_BITMAP_DIMENSION)
        require(bitmapWidth.toLong() * bitmapHeight <= MAX_BITMAP_PIXELS) {
            "bitmap pixel count must be <= $MAX_BITMAP_PIXELS, got ${bitmapWidth.toLong() * bitmapHeight}"
        }

        val modelDirectory = requireModelBundle(marker)
        var mindlayer: Mindlayer? = null
        var defaultSession: MindlayerSession? = null
        var maxSession: MindlayerSession? = null
        var bitmap: Bitmap? = null
        try {
            marker.mark(
                phase = "cold_process_baseline",
                note = "default_tokens=$defaultContextTokens max_tokens=$maxContextTokens backend=${backend.name}",
            )
            hold(phaseHoldMs)

            marker.mark("service_connect_begin")
            val client = Mindlayer.connect(context)
            mindlayer = client
            withTimeout(CONNECTION_TIMEOUT_MS) {
                client.awaitConnected(CONNECTION_TIMEOUT_MS.milliseconds)
            }
            marker.mark(
                phase = "service_bound_unloaded",
                note = "models=${modelDirectory.absolutePath}",
            )
            hold(phaseHoldMs)

            marker.mark(
                phase = "default_prewarm_begin",
                note = "requested_tokens=$defaultContextTokens requested_backend=${backend.name}",
            )
            client.prewarmForContext(defaultContextTokens, backend)
            val defaultEngine = awaitEngineContext(client, defaultContextTokens)
            marker.mark(
                phase = "default_prewarm_ready",
                note = engineNote(defaultEngine.modelId, defaultEngine.maxTokens, defaultEngine.backend),
            )
            hold(phaseHoldMs)

            marker.mark("default_session_open_begin")
            val defaultConversation = client.openSession {
                systemPrompt = "You are a concise offline assistant. Follow the final instruction exactly."
                maxTokens = defaultContextTokens
            }
            defaultSession = defaultConversation
            marker.mark(
                phase = "default_context_idle",
                note = "session=${defaultConversation.id.take(12)} active_tokens=${defaultEngine.maxTokens}",
            )
            hold(phaseHoldMs)

            marker.mark("first_inference_begin", note = "prompt_chars=$promptChars")
            val firstResult = withTimeout(INFERENCE_TIMEOUT_MS) {
                defaultConversation.ask(representativePrompt(promptChars))
            }
            marker.mark("first_inference_complete", note = "result_chars=${firstResult.length}")
            hold(phaseHoldMs)

            val workloadBitmap = createRepresentativeBitmap(bitmapWidth, bitmapHeight)
            bitmap = workloadBitmap
            val bitmapBytes = workloadBitmap.allocationByteCount.toLong()
            marker.mark(
                phase = "multimodal_image_held",
                trackedBitmapBytes = bitmapBytes,
                note = "bitmap=${bitmapWidth}x$bitmapHeight config=ARGB_8888",
            )
            hold(phaseHoldMs)

            marker.mark(
                phase = "multimodal_inference_begin",
                trackedBitmapBytes = bitmapBytes,
                note = "text_plus_image",
            )
            val visionResult = withTimeout(INFERENCE_TIMEOUT_MS) {
                defaultConversation.describe(
                    prompt = "Read this coffee label and return only its brand and roast level.",
                    image = workloadBitmap,
                )
            }
            marker.mark(
                phase = "multimodal_inference_complete",
                trackedBitmapBytes = bitmapBytes,
                note = "result_chars=${visionResult.length}",
            )
            hold(phaseHoldMs)

            marker.mark("auxiliary_capabilities_wait")
            val capabilities = awaitAuxiliaryCapabilities(client)
            marker.mark(
                phase = "embedding_first_request_begin",
                trackedBitmapBytes = bitmapBytes,
                note = "embedding_models=${capabilities.embeddingModelIds.size}",
            )
            val embeddingVector = runEmbedding(client, "A medium-roast washed Ethiopian coffee with citrus notes.")
            marker.mark(
                phase = "embedding_first_request_complete",
                trackedBitmapBytes = bitmapBytes,
                note = "dimensions=${embeddingVector.size}",
            )
            hold(phaseHoldMs)

            marker.mark(
                phase = "ocr_first_request_begin",
                trackedBitmapBytes = bitmapBytes,
            )
            val ocrLines = runOcr(client, workloadBitmap)
            marker.mark(
                phase = "ocr_first_request_complete",
                trackedBitmapBytes = bitmapBytes,
                note = "lines=$ocrLines",
            )
            hold(phaseHoldMs)

            marker.mark(
                phase = "coexistence_idle",
                trackedBitmapBytes = bitmapBytes,
                note = "chat_session_open embedding_loaded ocr_loaded",
            )
            hold(phaseHoldMs)

            marker.mark(
                phase = "coexistence_active_begin",
                trackedBitmapBytes = bitmapBytes,
                note = "chat+embedding+ocr",
            )
            val coexistence = coroutineScope {
                val chat = async {
                    withTimeout(INFERENCE_TIMEOUT_MS) {
                        defaultConversation.ask(representativePrompt(promptChars.coerceAtLeast(8_000)))
                    }
                }
                val embedding = async {
                    runEmbedding(
                        client,
                        "Coffee recipe search document: 18 grams in, 42 grams out, medium roast.",
                    )
                }
                val ocr = async { runOcr(client, workloadBitmap) }
                Triple(chat.await().length, embedding.await().size, ocr.await())
            }
            marker.mark(
                phase = "coexistence_active_complete",
                trackedBitmapBytes = bitmapBytes,
                note = "chat_chars=${coexistence.first} embedding_dims=${coexistence.second} ocr_lines=${coexistence.third}",
            )
            hold(phaseHoldMs)

            workloadBitmap.recycle()
            bitmap = null
            defaultConversation.closeAsync()
            defaultSession = null
            marker.mark("default_context_released")
            hold(phaseHoldMs)

            marker.mark(
                phase = "max_context_resize_begin",
                note = "requested_tokens=$maxContextTokens prior_tokens=${defaultEngine.maxTokens}",
            )
            client.prewarmForContext(maxContextTokens, backend)
            val maxEngine = awaitEngineContext(client, maxContextTokens)
            marker.mark(
                phase = "max_context_prewarm_ready",
                note = engineNote(maxEngine.modelId, maxEngine.maxTokens, maxEngine.backend),
            )
            hold(phaseHoldMs)

            marker.mark("max_session_open_begin")
            val maxConversation = client.openSession {
                systemPrompt = "You are a concise offline assistant."
                maxTokens = maxContextTokens
            }
            maxSession = maxConversation
            marker.mark(
                phase = "max_context_idle",
                note = "session=${maxConversation.id.take(12)} active_tokens=${maxEngine.maxTokens}",
            )
            hold(phaseHoldMs)

            marker.mark("max_context_first_inference_begin", note = "prompt_chars=$promptChars")
            val maxResult = withTimeout(INFERENCE_TIMEOUT_MS) {
                maxConversation.ask(representativePrompt(promptChars))
            }
            marker.mark(
                phase = "max_context_first_inference_complete",
                note = "result_chars=${maxResult.length}",
            )
            hold(phaseHoldMs)

            launchDashboard()
            marker.mark("lifecycle_top", note = "dashboard_started")
            hold(phaseHoldMs)

            marker.mark(
                phase = "lifecycle_fgs_inference_begin",
                note = "long_prefill_chars=$LIFECYCLE_PROMPT_CHARS",
            )
            val lifecycleInference = async {
                withTimeout(INFERENCE_TIMEOUT_MS) {
                    maxConversation.ask(lifecyclePrompt())
                }
            }
            delay(FGS_SETTLE_MS)
            check(!lifecycleInference.isCompleted) {
                "Lifecycle inference completed before foreground-service state could be sampled"
            }
            marker.mark("lifecycle_fgs_top", note = "inference_active dashboard_top")
            launchHome()
            marker.mark("lifecycle_fgs_background", note = "inference_active dashboard_stopped")
            hold(phaseHoldMs)
            val lifecycleResult = lifecycleInference.await()
            marker.mark(
                phase = "lifecycle_background",
                note = "inference_complete result_chars=${lifecycleResult.length}",
            )
            hold(phaseHoldMs)

            maxConversation.closeAsync()
            maxSession = null
            client.disconnect()
            mindlayer = null
            marker.mark("lifecycle_background_unbound", note = "client_disconnected")
            hold(phaseHoldMs)

            marker.mark("instrumentation_complete")
            hold(MIN_COMPLETE_HOLD_MS)
        } catch (t: Throwable) {
            marker.mark(
                phase = "failed",
                trackedBitmapBytes = runCatching { bitmap?.allocationByteCount?.toLong() }.getOrNull() ?: 0L,
                note = "${t.javaClass.simpleName}:${t.message.orEmpty().take(180)}",
            )
            throw t
        } finally {
            runCatching { bitmap?.recycle() }
            runCatching { defaultSession?.closeAsync() }
            runCatching { maxSession?.closeAsync() }
            runCatching { mindlayer?.disconnect() }
        }
    }

    private fun requireModelBundle(marker: PhaseMarker): File {
        val directory = checkNotNull(context.getExternalFilesDir(null)) {
            "External files directory is unavailable"
        }
        val missing = REQUIRED_MODEL_FILES.filter { name ->
            File(directory, name).let { !it.isFile || it.length() == 0L }
        }
        if (missing.isNotEmpty()) {
            marker.mark(
                phase = "missing_models",
                note = "missing=${missing.joinToString(",")}",
            )
            assumeTrue(
                "Gemma, EmbeddingGemma, and PaddleOCR models must be present in ${directory.absolutePath}. " +
                    "Push them with tools/dev-models/push-models.ps1 -All. Missing: ${missing.joinToString()}",
                false,
            )
        }
        return directory
    }

    private suspend fun awaitEngineContext(mindlayer: Mindlayer, minimumTokens: Int) =
        withTimeout(ENGINE_TIMEOUT_MS) {
            while (true) {
                runCatching {
                    val status = mindlayer.getStatus()
                    val info = mindlayer.getEngineInfo()
                    if (status.isEngineLoaded && !status.engineWarming && info.maxTokens >= minimumTokens) {
                        return@withTimeout info
                    }
                }
                delay(ENGINE_POLL_MS)
            }
            error("unreachable")
        }

    private suspend fun awaitAuxiliaryCapabilities(mindlayer: Mindlayer): ServiceCapabilities =
        withTimeout(AUXILIARY_ENGINE_TIMEOUT_MS) {
            while (true) {
                val capabilities = mindlayer.getCapabilities(forceRefresh = true)
                if (
                    capabilities.supports(ServiceCapabilities.FEATURE_EMBEDDINGS) &&
                    capabilities.supports(ServiceCapabilities.FEATURE_OCR_IMAGE_ONESHOT)
                ) {
                    return@withTimeout capabilities
                }
                delay(AUXILIARY_POLL_MS)
            }
            error("unreachable")
        }

    private suspend fun runEmbedding(mindlayer: Mindlayer, text: String): FloatArray {
        val handle = withTimeout(INFERENCE_TIMEOUT_MS) {
            mindlayer.embed { text(text) }
        }
        check(handle is EmbeddingHandle.Single) { "Single embedding request returned ${handle.javaClass.name}" }
        return withTimeout(INFERENCE_TIMEOUT_MS) { handle.awaitVector() }
    }

    private suspend fun runOcr(mindlayer: Mindlayer, bitmap: Bitmap): Int {
        val handle = withTimeout(INFERENCE_TIMEOUT_MS) {
            mindlayer.ocr {
                profile(OcrProfile.GeneralDocument)
                image(bitmap)
                emitBoundingBoxes()
            }
        }
        return withTimeout(INFERENCE_TIMEOUT_MS) { handle.awaitResult() }.lines.size
    }

    private suspend fun hold(durationMs: Int) {
        delay(durationMs.toLong())
    }

    private fun intArg(name: String, default: Int, range: IntRange): Int {
        val value = args.getString(name)?.toIntOrNull() ?: default
        require(value in range) { "$name must be in ${range.first}..${range.last}, got $value" }
        return value
    }

    private fun backendArg(): InferenceBackend {
        val value = args.getString("backend")?.uppercase() ?: InferenceBackend.GPU.name
        return InferenceBackend.entries.firstOrNull { it.name == value }
            ?: throw IllegalArgumentException("backend must be CPU, GPU, or NPU; got $value")
    }

    private fun representativePrompt(minChars: Int): String {
        val paragraph =
            "Mindlayer is an offline Android inference service. A client sends a bounded request, " +
                "the service keeps model execution private on device, and the caller consumes a streamed result. "
        return buildString(minChars + 160) {
            append("Use the following context only to exercise prompt prefill. ")
            while (length < minChars) append(paragraph)
            append("\nFinal instruction: reply with exactly MEMORY_OK and no other text.")
        }
    }

    private fun lifecyclePrompt(): String {
        val paragraph =
            "On-device inference has explicit lifecycle states, bounded contexts, and observable memory accounting. "
        return buildString(LIFECYCLE_PROMPT_CHARS + 512) {
            while (length < LIFECYCLE_PROMPT_CHARS) append(paragraph)
            append("\nWrite 256 numbered one-sentence observations about responsible on-device inference.")
        }
    }

    private fun createRepresentativeBitmap(width: Int, height: Int): Bitmap {
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).apply {
                drawColor(Color.rgb(239, 228, 207))
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(51, 36, 24)
                    textSize = (width / 12f).coerceAtLeast(24f)
                }
                drawText("STARLIT COFFEE", width * 0.08f, height * 0.42f, paint)
                paint.textSize *= 0.72f
                drawText("MEDIUM ROAST", width * 0.16f, height * 0.58f, paint)
            }
        }
    }

    private fun launchDashboard() {
        context.startActivity(
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
        )
    }

    private fun launchHome() {
        context.startActivity(
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    private fun engineNote(modelId: String, maxTokens: Int, backend: String): String =
        "model=$modelId active_tokens=$maxTokens active_backend=$backend"

    private class PhaseMarker(context: Context) {
        private val sequence = AtomicInteger(0)
        private val directory = File(context.filesDir, MARKER_DIRECTORY).apply { mkdirs() }
        private val destination = File(directory, MARKER_FILENAME)
        private val journal = File(directory, JOURNAL_FILENAME)

        init {
            destination.delete()
            journal.delete()
        }

        @Synchronized
        fun mark(
            phase: String,
            trackedBitmapBytes: Long = 0L,
            note: String = "",
        ) {
            val safePhase = sanitize(phase)
            val safeNote = sanitize(note)
            val line = listOf(
                sequence.incrementAndGet().toString(),
                SystemClock.elapsedRealtime().toString(),
                safePhase,
                trackedBitmapBytes.coerceAtLeast(0L).toString(),
                safeNote,
            ).joinToString("\t")

            journal.appendText("$line\n")
            val temporary = File(directory, "$MARKER_FILENAME.tmp")
            temporary.writeText("$line\n")
            check(temporary.renameTo(destination) || runCatching {
                destination.writeText("$line\n")
                temporary.delete()
                true
            }.getOrDefault(false)) {
                "Unable to publish benchmark phase marker"
            }
            Log.i(TAG, "MLBENCH_PHASE $line")
        }

        private fun sanitize(value: String): String =
            value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')
    }

    private companion object {
        private const val TAG = "PlayMemoryBench"
        private const val MARKER_DIRECTORY = "play-memory-benchmark"
        private const val MARKER_FILENAME = "current-phase.tsv"
        private const val JOURNAL_FILENAME = "phase-events.tsv"

        private const val MIN_CONTEXT_TOKENS = 128
        private const val DEFAULT_CONTEXT_TOKENS = 8_192
        private const val MAX_CONTEXT_TOKENS = 32_768
        private const val DEFAULT_PROMPT_CHARS = 12_000
        private const val LIFECYCLE_PROMPT_CHARS = 40_000
        private const val DEFAULT_BITMAP_WIDTH = 2_048
        private const val DEFAULT_BITMAP_HEIGHT = 2_048
        private const val DEFAULT_PHASE_HOLD_MS = 4_000
        private const val MIN_COMPLETE_HOLD_MS = 2_000
        private const val FGS_SETTLE_MS = 750L
        private const val MAX_BITMAP_DIMENSION = 8_192
        private const val MAX_BITMAP_PIXELS = 64L * 1024L * 1024L

        private const val CONNECTION_TIMEOUT_MS = 60L * 1_000L
        private const val ENGINE_TIMEOUT_MS = 10L * 60L * 1_000L
        private const val AUXILIARY_ENGINE_TIMEOUT_MS = 3L * 60L * 1_000L
        private const val INFERENCE_TIMEOUT_MS = 10L * 60L * 1_000L
        private const val ENGINE_POLL_MS = 500L
        private const val AUXILIARY_POLL_MS = 1_000L

        private val REQUIRED_MODEL_FILES = listOf(
            "gemma-4-E2B-it.litertlm",
            "embedding-gemma-300m-v1.tflite",
            "embedding-gemma-300m-v1.spm.model",
            "paddleocr-ppocrv5-mobile-det.tflite",
            "paddleocr-ppocrv5-mobile-rec.tflite",
            "paddleocr-ppocrv5-mobile-cls.tflite",
            "paddleocr-ppocrv5-mobile-dict.txt",
        )
    }
}
