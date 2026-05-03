package com.adsamcik.mindlayer.service.engine

import android.content.Context
import android.os.Build
import androidx.annotation.VisibleForTesting
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.adsamcik.mindlayer.service.logging.LogRepository
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.logging.safeLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages the LiteRT-LM [Engine] lifecycle: initialization with automatic
 * backend fallback (NPU → GPU → CPU), single-model selection via [ModelRegistry],
 * and teardown.
 *
 * All public methods are coroutine-safe and serialize via [Mutex] so only one
 * init/shutdown can be in flight at a time.
 */
class EngineManager(
    private val context: Context,
    private val logRepository: com.adsamcik.mindlayer.service.logging.LogRepository? = null,
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

    /** Detail string from the most recent GPU init failure, or `null` if GPU never failed (or succeeded). */
    @Volatile
    var lastGpuFailureReason: String? = null
        private set

    /**
     * Factory used to construct a new [Engine] from an [EngineConfig].
     *
     * The default delegates to the LiteRT-LM [Engine] constructor. Tests
     * override this to inject a mock so that backend-fallback paths and
     * partial-init cleanup can be exercised without loading native code.
     *
     * Backed by a single field so the constructor signature stays stable
     * for the dashboard and `:ml` service entry points.
     */
    @VisibleForTesting
    internal var engineFactory: (EngineConfig) -> Engine = { Engine(it) }

    /** All model files detected on the device for internal selection purposes. */
    private val installedModels: List<ModelInfo> by lazy {
        ModelRegistry.discoverModels(context)
    }

    /** The single model Mindlayer selects and exposes on this device. */
    private val selectedModel: ModelInfo by lazy {
        ModelRegistry.getDefaultModel(installedModels) ?: throw noModelFoundException()
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
     * @return The initialized [Engine].
     */
    suspend fun initialize(
        preferredBackend: String? = null,
        maxTokens: Int = 4096,
    ): Engine = mutex.withLock {
        val target = selectedModel

        // Fast-path: the selected device model is already loaded.
        engine?.let { eng ->
            MindlayerLog.i(TAG, "Engine already initialized with model=${target.id}, backend=$currentBackend")
            return eng
        }

        val path = target.path
        val cacheDir = context.cacheDir.resolve("litert_cache").also { it.mkdirs() }

        // F-002: verify the on-disk model SHA-256 matches the build-time
        // manifest before handing it to native init. APK signing protects
        // delivery via the AI Pack, but the extracted file in `filesDir`
        // is mutable (root, OEM agents) and never re-verified on later
        // loads. When `BuildConfig.MODEL_SHA256` is empty (the dev/CI
        // default) we emit a loud warning instead of failing — once the
        // build pipeline pins the manifest, mismatches must hard-fail.
        verifyModelIntegrity(File(path))

        // Pre-flight memory check: model needs ~2.5GB + working buffers
        val modelSizeBytes = target.sizeBytes
        val activityManager = context.getSystemService(android.app.ActivityManager::class.java)
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val requiredBytes = modelSizeBytes + (512L * 1024 * 1024) // model + 512MB headroom
        MindlayerLog.i(TAG, "Loading model '${target.id}' (${target.displayName})")
        MindlayerLog.i(TAG, "Memory check: available=${memInfo.availMem / 1024 / 1024}MB, " +
            "model=${modelSizeBytes / 1024 / 1024}MB, required=${requiredBytes / 1024 / 1024}MB")
        if (memInfo.availMem < requiredBytes) {
            MindlayerLog.w(TAG, "Low memory: ${memInfo.availMem / 1024 / 1024}MB available, " +
                "need ${requiredBytes / 1024 / 1024}MB. Engine init may fail.")
        }

        val backends = resolveBackendChain(preferredBackend)
        var lastError: Throwable? = null

        for (backend in backends) {
            val name = backendName(backend)
            MindlayerLog.i(TAG, "Attempting init with backend=$name, model=${target.id}")

            try {
                val startNs = System.nanoTime()

                val config = EngineConfig(
                    modelPath = path,
                    backend = backend,
                    maxNumTokens = maxTokens,
                    cacheDir = cacheDir.absolutePath,
                )

                val eng = withContext(Dispatchers.IO) {
                    val instance = engineFactory(config)
                    try {
                        instance.initialize()
                        instance
                    } catch (t: Throwable) {
                        // F-070: the LiteRT-LM Engine constructor allocates native
                        // resources before `initialize()` is even called. If init
                        // throws (driver crash, OOM, malformed model), the
                        // partially-constructed engine MUST be closed or the
                        // backend-fallback loop leaks native heap on every retry.
                        // close() can itself throw on a half-initialised handle —
                        // best-effort, swallow secondaries so the original cause
                        // propagates to the outer catch unchanged.
                        try { instance.close() } catch (_: Throwable) { /* best-effort */ }
                        throw t
                    }
                }

                val elapsed = (System.nanoTime() - startNs) / 1_000_000_000f
                val durationMs = ((System.nanoTime() - startNs) / 1_000_000)
                MindlayerLog.i(TAG, "Engine initialized: model=${target.id}, backend=$name, time=${elapsed}s (tried ${backends.map { backendName(it) }})")

                if (name == "GPU") {
                    lastGpuFailureReason = null
                }
                engine = eng
                currentBackend = name
                currentModel = target
                initTimeSeconds = elapsed
                isInitialized = true
                logRepository?.logEngineInit(name, durationMs, path)
                return eng

            } catch (t: Throwable) {
                // F-006: never persist or surface raw native exception text.
                // LiteRT-LM tokenizer/template exceptions can inline prompt
                // fragments; safeLabel() returns class names only.
                val safeDetail = t.safeLabel()
                MindlayerLog.w(TAG, "Backend $name failed: $safeDetail")
                if (name == "GPU") {
                    lastGpuFailureReason = safeDetail
                }
                lastError = t
                logRepository?.log(com.adsamcik.mindlayer.service.logging.LogEntry(
                    timestampMs = System.currentTimeMillis(),
                    category = com.adsamcik.mindlayer.service.logging.LogCategory.ENGINE,
                    event = com.adsamcik.mindlayer.service.logging.LogEvent.ENGINE_FALLBACK,
                    backend = name,
                    errorMessage = safeDetail,
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

    /**
     * Verify the on-disk model file's SHA-256 matches the build-time
     * manifest in `BuildConfig.MODEL_SHA256`.
     *
     * Behaviour:
     *  - Manifest empty (default) → emit a warning and continue. This is
     *    the dev/CI mode before the build pipeline pins the hash.
     *  - Manifest non-empty + on-disk hash matches → silent pass.
     *  - Manifest non-empty + on-disk hash differs → throw
     *    [SecurityException]. Refusing to load is safer than booting a
     *    swapped backdoored model (see SECURITY_REVIEW F-002 / F-003).
     */
    private fun verifyModelIntegrity(file: File) {
        val expected = com.adsamcik.mindlayer.service.BuildConfig.MODEL_SHA256
        if (expected.isEmpty()) {
            MindlayerLog.w(
                TAG,
                "Model integrity manifest empty — loading without SHA-256 " +
                    "verification. CI must pin BuildConfig.MODEL_SHA256.",
            )
            return
        }
        val actual = sha256(file)
        if (!constantTimeEquals(actual, expected)) {
            MindlayerLog.e(
                TAG,
                "Model SHA-256 mismatch (expected=…${expected.takeLast(8)}, " +
                    "actual=…${actual.takeLast(8)}); refusing to load.",
            )
            throw SecurityException("Model integrity check failed")
        }
        MindlayerLog.i(TAG, "Model integrity verified (SHA-256 …${actual.takeLast(8)})")
    }

    private fun sha256(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
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
        MindlayerLog.i(TAG, "Switching backend: $currentBackend → $newBackend")
        shutdown()
        return initialize(
            preferredBackend = newBackend,
            maxTokens = maxTokens,
        )
    }

    // ---- Private helpers ---------------------------------------------------

    private fun noModelFoundException(): IllegalStateException =
        IllegalStateException(
            "No .litertlm model files found. Place a model in app filesDir, " +
                "externalFilesDir, cacheDir, /data/local/tmp, or deploy via Play AI pack."
        )

    /** Internal shutdown without acquiring the mutex (caller must hold it). */
    private suspend fun shutdownInternal() {
        engine?.let { eng ->
            val backend = currentBackend
            MindlayerLog.i(TAG, "Shutting down engine (backend=$backend, model=${currentModel?.id})")
            withContext(Dispatchers.IO) {
                try {
                    eng.close()
                } catch (t: Throwable) {
                    MindlayerLog.e(TAG, "Error closing engine: ${t.safeLabel()}")
                }
            }
            engine = null
            currentBackend = "NONE"
            currentModel = null
            isInitialized = false
            initTimeSeconds = 0f
            lastGpuFailureReason = null
            logRepository?.logEngineShutdown(backend)
            MindlayerLog.i(TAG, "Engine shutdown complete")
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
