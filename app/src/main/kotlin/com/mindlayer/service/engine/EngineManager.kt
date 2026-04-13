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
 * backend fallback (NPU → GPU → CPU), model file discovery, and teardown.
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

    val modelPath: String by lazy {
        findModelFile()
    }

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
     * @return The initialized [Engine].
     */
    suspend fun initialize(
        preferredBackend: String? = null,
        maxTokens: Int = 4096,
    ): Engine = mutex.withLock {
        // Fast-path: already initialised
        engine?.let {
            Log.i(TAG, "Engine already initialized with backend=$currentBackend")
            return it
        }

        val path = modelPath
        val cacheDir = context.cacheDir.resolve("litert_cache").also { it.mkdirs() }

        // Pre-flight memory check: model needs ~2.5GB + working buffers
        val modelSizeBytes = java.io.File(path).length()
        val activityManager = context.getSystemService(android.app.ActivityManager::class.java)
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val requiredBytes = modelSizeBytes + (512L * 1024 * 1024) // model + 512MB headroom
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
            Log.i(TAG, "Attempting init with backend=$name")

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
                Log.i(TAG, "Engine initialized: backend=$name, time=${elapsed}s")

                engine = eng
                currentBackend = name
                initTimeSeconds = elapsed
                isInitialized = true
                logRepository?.logEngineInit(name, durationMs, path)
                return eng

            } catch (t: Throwable) {
                Log.w(TAG, "Backend $name failed: ${t.message}", t)
                lastError = t
                // Log fallback if there are more backends to try
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
            "All backends failed for model at $path (available RAM: ${availMb}MB, " +
                "model: ${modelSizeBytes / 1024 / 1024}MB). " +
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
        engine?.let { eng ->
            val backend = currentBackend
            Log.i(TAG, "Shutting down engine (backend=$backend)")
            withContext(Dispatchers.IO) {
                try {
                    eng.close()
                } catch (t: Throwable) {
                    Log.e(TAG, "Error closing engine", t)
                }
            }
            engine = null
            currentBackend = "NONE"
            isInitialized = false
            initTimeSeconds = 0f
            logRepository?.logEngineShutdown(backend)
            Log.i(TAG, "Engine shutdown complete")
        }
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
        return initialize(preferredBackend = newBackend, maxTokens = maxTokens)
    }

    // ---- Private helpers ---------------------------------------------------

    /**
     * Locate the `.litertlm` model file in well-known directories.
     * Checked in order: filesDir → externalFilesDir → cacheDir.
     */
    private fun findModelFile(): String {
        val candidates = mutableListOf<File>()

        // 1. App-private storage (manual placement or first-launch download)
        candidates.add(File(context.filesDir, DEFAULT_MODEL_FILENAME))
        context.getExternalFilesDir(null)?.let {
            candidates.add(File(it, DEFAULT_MODEL_FILENAME))
        }
        candidates.add(File(context.cacheDir, DEFAULT_MODEL_FILENAME))

        // 2. Play for On-device AI pack (install-time delivery)
        //    Install-time AI pack assets are merged into the app's asset path.
        //    They can be accessed by extracting from AssetManager, or on some
        //    devices they're directly accessible in the APK's native lib path.
        //    For LiteRT-LM which needs a file path, we check if the asset exists
        //    and extract it to filesDir on first use.
        try {
            val aiPackAssets = context.assets.list("") ?: emptyArray()
            if (DEFAULT_MODEL_FILENAME in aiPackAssets) {
                val extracted = File(context.filesDir, DEFAULT_MODEL_FILENAME)
                if (!extracted.exists()) {
                    MindlayerLog.i(TAG, "Extracting model from AI pack to filesDir...")
                    context.assets.open(DEFAULT_MODEL_FILENAME).use { input ->
                        extracted.outputStream().use { output ->
                            input.copyTo(output, bufferSize = 8 * 1024 * 1024)
                        }
                    }
                    MindlayerLog.i(TAG, "Model extracted: ${extracted.length() / 1_048_576}MB")
                }
                return extracted.absolutePath
            }
        } catch (e: Exception) {
            MindlayerLog.w(TAG, "AI pack asset check failed: ${e.message}")
        }

        // 3. Debug/testing: /data/local/tmp (adb push target)
        candidates.add(File("/data/local/tmp", DEFAULT_MODEL_FILENAME))

        for (file in candidates) {
            if (file.exists()) return file.absolutePath
        }

        throw IllegalStateException(
            "Model file '$DEFAULT_MODEL_FILENAME' not found. " +
            "Place it in app filesDir, externalFilesDir, cacheDir, /data/local/tmp, " +
            "or deploy via Play for On-device AI pack."
        )
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
