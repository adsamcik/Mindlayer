package com.mindlayer.service.engine

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.mindlayer.service.logging.LogRepository
import com.mindlayer.service.logging.MindlayerLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages the LiteRT-LM [Engine] lifecycle: initialization with automatic
 * backend fallback (NPU → GPU → CPU), single-model selection via
 * [ModelRegistry], and teardown.
 *
 * All public methods are coroutine-safe and serialize via [Mutex] so only one
 * init/shutdown can be in flight at a time.
 */
class EngineManager(
    private val context: Context,
    private val logRepository: com.mindlayer.service.logging.LogRepository? = null,
) {

    companion object {
        private const val TAG = "EngineManager"

        /** Hint filename used for Play AI Pack extraction fallback. */
        const val DEFAULT_MODEL_FILENAME = "gemma-4-E2B-it.litertlm"

        // Qualcomm SoCs with NPU support
        private val QUALCOMM_NPU_SOCS = setOf(
            "sm8450", "sm8475", "sm8550", "sm8650", "sm8750", "sm8850"
        )

        // MediaTek SoCs with NPU support
        private val MEDIATEK_NPU_SOCS = setOf(
            "mt6878", "mt6897", "mt6983", "mt6985", "mt6989", "mt6990", "mt6991"
        )
    }

    private val mutex = Mutex()

    @Volatile
    private var engine: Engine? = null

    @Volatile
    var currentBackend: String = "NONE"
        private set

    @Volatile
    var initTimeSeconds: Float = 0f
        private set

    @Volatile
    var isInitialized: Boolean = false
        private set

    /** The single model currently loaded in the engine, or `null` if not yet initialised. */
    @Volatile
    var currentModel: ModelInfo? = null
        private set

    /** All model files detected on the device for internal selection purposes. */
    private val installedModels: List<ModelInfo> by lazy {
        ModelRegistry.discoverModels(context)
    }

    /** The single model Mindlayer selects and exposes on this device. */
    private val selectedModel: ModelInfo by lazy {
        ModelRegistry.getDefaultModel(installedModels) ?: throw noModelFoundException()
    }

    /** Single public model exposed to clients (legacy APIs still return a one-item list). */
    val availableModels: List<ModelInfo> by lazy {
        listOf(selectedModel)
    }

    /** Convenience: path of the currently loaded (or selected) model. */
    val modelPath: String
        get() = currentModel?.path
            ?: selectedModel.path

    // ---- Public API --------------------------------------------------------

    /**
     * Initialize the engine with automatic backend fallback.
     * Must be called from a coroutine — heavy work runs on [Dispatchers.IO].
     *
     * Fallback chain: NPU → GPU → CPU  (NPU only when explicitly requested or
     * when the device has a known-supported SoC *and* the vendor libs are present).
     *
     * @param preferredBackend Force a specific backend ("NPU", "GPU", "CPU").
     *        When `null`, the default chain GPU → CPU is used.
     * @param maxTokens KV-cache budget (input + output tokens combined).
     * @param modelId Legacy compatibility parameter. Explicit model selection is
     *        ignored; Mindlayer always uses the device-selected model.
     * @return The initialized [Engine].
     */
    suspend fun initialize(
        preferredBackend: String? = null,
        maxTokens: Int = 4096,
        modelId: String? = null,
    ): Engine = mutex.withLock {
        val target = resolveTargetModel(modelId)

        // Fast-path: the selected device model is already loaded.
        engine?.let { eng ->
            Log.i(TAG, "Engine already initialized with model=${target.id}, backend=$currentBackend")
            return eng
        }

        val path = target.path
        val cacheDir = context.cacheDir.resolve("litert_cache").also { it.mkdirs() }

        // Pre-flight memory check: model needs ~2.5GB + working buffers
        val modelSizeBytes = target.sizeBytes
        val activityManager = context.getSystemService(android.app.ActivityManager::class.java)
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val requiredBytes = modelSizeBytes + (512L * 1024 * 1024) // model + 512MB headroom
        Log.i(TAG, "Loading model '${target.id}' (${target.displayName})")
        Log.i(TAG, "Memory check: available=${memInfo.availMem / 1024 / 1024}MB, " +
            "model=${modelSizeBytes / 1024 / 1024}MB, required=${requiredBytes / 1024 / 1024}MB")
        if (memInfo.availMem < requiredBytes) {
            Log.w(TAG, "Low memory: ${memInfo.availMem / 1024 / 1024}MB available, " +
                "need ${requiredBytes / 1024 / 1024}MB. Engine init may fail.")
        }

        val backends = resolveBackendChain(preferredBackend)
        var lastError: Throwable? = null

        for (backend in backends) {
            val name = backendName(backend)
            Log.i(TAG, "Attempting init with backend=$name, model=${target.id}")

            try {
                val startNs = System.nanoTime()

                val config = EngineConfig(
                    modelPath = path,
                    backend = backend,
                    maxNumTokens = maxTokens,
                    cacheDir = cacheDir.absolutePath,
                )

                val eng = withContext(Dispatchers.IO) {
                    Engine(config).also { it.initialize() }
                }

                val elapsed = (System.nanoTime() - startNs) / 1_000_000_000f
                val durationMs = ((System.nanoTime() - startNs) / 1_000_000)
                Log.i(TAG, "Engine initialized: model=${target.id}, backend=$name, time=${elapsed}s")

                engine = eng
                currentBackend = name
                currentModel = target
                initTimeSeconds = elapsed
                isInitialized = true
                logRepository?.logEngineInit(name, durationMs, path)
                return eng

            } catch (t: Throwable) {
                Log.w(TAG, "Backend $name failed: ${t.message}", t)
                lastError = t
                logRepository?.log(com.mindlayer.service.logging.LogEntry(
                    timestampMs = System.currentTimeMillis(),
                    category = com.mindlayer.service.logging.LogCategory.ENGINE,
                    event = com.mindlayer.service.logging.LogEvent.ENGINE_FALLBACK,
                    backend = name,
                    errorMessage = t.message,
                ))
            }
        }

        val availMb = memInfo.availMem / 1024 / 1024
        throw IllegalStateException(
            "All backends failed for model '${target.id}' at $path " +
                "(available RAM: ${availMb}MB, model: ${modelSizeBytes / 1024 / 1024}MB). " +
                "Try closing other apps to free memory.", lastError
        )
    }

    /** Returns the current [Engine] or throws if not yet initialized. */
    fun requireEngine(): Engine =
        engine ?: throw IllegalStateException("Engine not initialized — call initialize() first.")

    /** Returns the current [Engine], or `null`. */
    fun getEngine(): Engine? = engine

    /** Shut down the engine and release native resources. */
    suspend fun shutdown() = mutex.withLock {
        shutdownInternal()
    }

    /**
     * Switch to a different backend (e.g. thermal-fallback from GPU → CPU).
     * The current engine is shut down first.
     */
    suspend fun switchBackend(
        newBackend: String,
        maxTokens: Int = 4096,
    ): Engine {
        Log.i(TAG, "Switching backend: $currentBackend → $newBackend")
        shutdown()
        return initialize(
            preferredBackend = newBackend,
            maxTokens = maxTokens,
        )
    }

    // ---- Private helpers ---------------------------------------------------

    /** Resolve the target [ModelInfo] for the given [modelId]. */
    private fun resolveTargetModel(modelId: String?): ModelInfo {
        val target = selectedModel
        if (modelId != null && modelId != target.id) {
            Log.w(
                TAG,
                "Ignoring requested model '$modelId'; using single selected model '${target.id}'"
            )
        }
        return target
    }

    private fun noModelFoundException(): IllegalStateException =
        IllegalStateException(
            "No .litertlm model files found. Place a model in app filesDir, " +
                "externalFilesDir, cacheDir, /data/local/tmp, or deploy via Play AI pack."
        )

    /** Internal shutdown without acquiring the mutex (caller must hold it). */
    private suspend fun shutdownInternal() {
        engine?.let { eng ->
            val backend = currentBackend
            Log.i(TAG, "Shutting down engine (backend=$backend, model=${currentModel?.id})")
            withContext(Dispatchers.IO) {
                try {
                    eng.close()
                } catch (t: Throwable) {
                    Log.e(TAG, "Error closing engine", t)
                }
            }
            engine = null
            currentBackend = "NONE"
            currentModel = null
            isInitialized = false
            initTimeSeconds = 0f
            logRepository?.logEngineShutdown(backend)
            Log.i(TAG, "Engine shutdown complete")
        }
    }

    /**
     * Build an ordered list of [Backend]s to try.
     *
     * * If [preferred] is specified, that backend comes first, followed by
     *   lower tiers as fallback.
     * * If `null`, the default chain is GPU → CPU.
     */
    private fun resolveBackendChain(preferred: String?): List<Backend> {
        if (preferred != null) {
            return when (preferred.uppercase()) {
                "NPU" -> buildBackendChain(includeNpu = true)
                "GPU" -> listOf(Backend.GPU(), Backend.CPU())
                "CPU" -> listOf(Backend.CPU())
                else  -> buildBackendChain(includeNpu = false)
            }
        }
        // Default: GPU → CPU (NPU only when explicitly requested)
        return buildBackendChain(includeNpu = false)
    }

    private fun buildBackendChain(includeNpu: Boolean): List<Backend> {
        val chain = mutableListOf<Backend>()

        if (includeNpu && isNpuLikelySupported()) {
            chain += Backend.NPU(
                nativeLibraryDir = context.applicationInfo.nativeLibraryDir
            )
        }

        chain += Backend.GPU()
        chain += Backend.CPU()
        return chain
    }

    /**
     * Heuristic: NPU is *likely* supported when
     *  1. API ≥ 31 (required for `Build.SOC_MODEL`)
     *  2. `SOC_MODEL` is on the allowlist
     *  3. Vendor runtime native libs are bundled in the APK
     */
    private fun isNpuLikelySupported(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false

        @Suppress("InlinedApi")
        val soc = Build.SOC_MODEL.orEmpty().lowercase()
        if (soc.isEmpty()) return false

        val socKnown = soc in QUALCOMM_NPU_SOCS || soc in MEDIATEK_NPU_SOCS
        if (!socKnown) return false

        // Verify vendor runtime libs are actually present
        val libDir = File(context.applicationInfo.nativeLibraryDir ?: return false)
        val libs = libDir.list()?.toSet().orEmpty()
        val hasQualcomm = libs.any { it.startsWith("libQnn") }
        val hasMediaTek = libs.any {
            it.contains("mediatek", ignoreCase = true) ||
            it.contains("dispatch", ignoreCase = true)
        }
        return hasQualcomm || hasMediaTek
    }

    private fun backendName(backend: Backend): String = when (backend) {
        is Backend.CPU -> "CPU"
        is Backend.GPU -> "GPU"
        is Backend.NPU -> "NPU"
        else -> "UNKNOWN"
    }
}
