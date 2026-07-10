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
import com.adsamcik.mindlayer.service.logging.safeLabelWithDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * F-071: thrown by [EngineManager.initialize] when the device's available
 * RAM is below the model size + working-buffer headroom. Carries the
 * concrete numbers so a diagnostic UI (or the dashboard's logs) can show
 * the user how short the device is.
 *
 * **Why a typed exception, not [IllegalStateException]:** the previous
 * implementation logged a warning and proceeded into native model load,
 * which on 4–6 GB devices SIGABRT'd inside LiteRT-LM and bricked the
 * service for retry-loop callers. We need a *terminal*, *typed* failure
 * so [SessionManager] can refuse further session creation immediately
 * (rather than waiting for the engine_initializing retry budget) and
 * [ServiceBinder] can map it to [com.adsamcik.mindlayer.shared.MindlayerErrorCode.LOW_MEMORY]
 * for a stable wire signal.
 *
 * Do not collapse this back into [IllegalStateException]. The downstream
 * `when (e) { is LowMemoryException -> … }` discriminator pins the
 * "no retry, surface to user" contract.
 */
class LowMemoryException(
    val availMb: Long,
    val requiredMb: Long,
) : IllegalStateException(
    "Insufficient memory: availMb=$availMb requiredMb=$requiredMb",
)

/**
 * S-11: thrown when the on-disk model file's identity changes between the
 * pre-load SHA-256 verification and the completion of native init — i.e. a
 * verify-then-load TOCTOU swap was detected across the load window. Extends
 * [SecurityException] so [EngineManager]'s `classifyInitFailure` maps it to
 * [InitFailure.IntegrityMismatch], and the message keeps the existing
 * "Model integrity check failed" substring the dashboard/tests key on.
 *
 * Distinct private type (not a bare `SecurityException`) so the per-backend
 * fallback loop can re-throw it immediately instead of mistaking an unrelated
 * native `SecurityException` for an integrity violation.
 */
private class ModelIntegrityViolation :
    SecurityException("Model integrity check failed: file changed during load")

sealed class EngineState {
    object Idle : EngineState()
    object Initializing : EngineState()
    object Ready : EngineState()
    data class Failed(val cause: InitFailure) : EngineState()
}

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

    /**
     * Factory for the persistent restart-intent store. Lazy via [restartStore]
     * so tests that never exercise [shutdownAndRestart] / [consumePendingRestartIntent]
     * don't pay the construction cost (and don't NPE on mock contexts that
     * don't stub `applicationContext.filesDir`). Tests that exercise the
     * restart paths should set this BEFORE first use, e.g. to a
     * `FakeEngineRestartStore` backed by a tmpdir.
     */
    @VisibleForTesting
    internal var restartStoreFactory: () -> EngineRestartStore = { EngineRestartStore(context) }
    private val restartStore: EngineRestartStore by lazy { restartStoreFactory() }

    /**
     * Test seam for [shutdownAndRestart]. Production: kills our own
     * process; the service auto-restarts on the next bind. Tests override
     * to a no-op or recording lambda so [shutdownAndRestart] can return
     * and assertions can fire.
     *
     * `android.os.Process.killProcess(myPid())` is the documented restart
     * hammer for services that need a fresh native heap.
     */
    @VisibleForTesting
    internal var processKiller: () -> Unit = {
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    companion object {
        private const val TAG = "EngineManager"

        /** Hint filename used for Play AI Pack extraction fallback. */
        const val DEFAULT_MODEL_FILENAME = "gemma-4-E2B-it.litertlm"

        /**
         * H-E2: default cap on [awaitReady]. A wedged init (driver hang,
         * stuck native load) used to pin every queued binder thread
         * forever. Each waiter now bails after this window with a typed
         * [InitFailure.NativeError] so the SDK can surface a deterministic
         * `engine_load_failed` instead of hanging. The init job itself is
         * **not** cancelled — it keeps running so a slow-but-eventual
         * success still rearms the engine for later callers.
         */
        const val DEFAULT_AWAIT_READY_TIMEOUT_MS: Long = 30_000L

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

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    @Volatile
    private var engine: Engine? = null

    @Volatile
    var currentBackend: String = "NONE"
        private set

    /**
     * The `maxNumTokens` ceiling the currently-loaded [engine] was actually
     * initialized with. Compared against later callers' requests in the
     * [initializeLocked] fast path so a caller silently getting a SMALLER
     * ceiling than it asked for is at least visible in logs (see the
     * `ServiceBinder.prewarm` KV-cache-ceiling mismatch root-caused in
     * StarlitCoffee's `.incomplete.md`).
     */
    @Volatile
    private var currentMaxTokens: Int? = null

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

    /**
     * F-077: the most recent [InitFailure] observed inside [initialize], or
     * `null` if no init has failed (or the engine has been shut down). See
     * the [InitFailure] KDoc for full population semantics — in short, this
     * field is reset at the start of every fresh init run, OVERWRITTEN on
     * each per-backend failure, intentionally retained across a
     * fallback-success so the dashboard can render "running on CPU because
     * GPU failed", and cleared on [shutdown].
     */
    @Volatile
    var lastInitFailure: InitFailure? = null
        private set

    /**
     * F-077: backward-compat shim over [lastInitFailure]. Returns the
     * GPU-specific safeLabel iff the most recent failure was a
     * [InitFailure.BackendUnavailable] for the GPU backend; otherwise `null`.
     *
     * The shim is kept (rather than removed) because:
     *  - The Room log query [com.adsamcik.mindlayer.service.logging.LogDao.latestGpuFallbackMessage]
     *    feeds the dashboard's existing `gpuFailureReason` field independently
     *    via the persisted `engine_fallback` event; tests across modules still
     *    inspect the property name.
     *  - `EngineManagerTest`'s leak-prevention suite (`shutdown clears
     *    lastGpuFailureReason`) reads this property, and the F-070 tests
     *    rely on the same call path. Keeping the shim avoids re-touching
     *    those tests on an unrelated structural change.
     *
     * New code should observe [lastInitFailure] directly.
     */
    val lastGpuFailureReason: String?
        get() = (lastInitFailure as? InitFailure.BackendUnavailable)
            ?.takeIf { it.backend == "GPU" }
            ?.safeLabel

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

    /**
     * F-077 / F-002 test seam: SHA-256 manifest source.
     *
     * Production reads from [com.adsamcik.mindlayer.service.BuildConfig.MODEL_SHA256]
     * (a compile-time constant inlined at every call site, so direct
     * reflection on the constant cannot retarget verifier calls). This
     * field lets tests force a non-empty manifest WITHOUT depending on
     * a custom build variant — the IntegrityMismatch path can be
     * exercised by setting `mgr.expectedModelSha256 = "wrong-hash"`
     * before [initialize].
     */
    @VisibleForTesting
    internal var expectedModelSha256: String =
        com.adsamcik.mindlayer.service.BuildConfig.MODEL_SHA256

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
        initializeLocked(preferredBackend, maxTokens)
    }

    private suspend fun initializeLocked(
        preferredBackend: String? = null,
        maxTokens: Int = 4096,
    ): Engine {
        _state.value = EngineState.Initializing
        try {
        val target = try {
            withContext(Dispatchers.IO) { selectedModel }
        } catch (e: IllegalStateException) {
            // F-077: `selectedModel` is `by lazy { ... ?: throw noModelFoundException() }`.
            // The only IllegalStateException reachable here is the no-model-file path
            // — categorise it before re-throwing so the dashboard renders the
            // ModelMissing remediation rather than a generic stale state.
            recordInitFailure(InitFailure.ModelMissing)
            throw e
        }

        // Fast-path: the selected device model is already loaded.
        engine?.let { eng ->
            MindlayerLog.i(TAG, "Engine already initialized with model=${target.id}, backend=$currentBackend")
            // F-{prewarm-ceiling}: the requested maxTokens is silently
            // discarded here — the engine keeps running at whatever
            // ceiling it was FIRST initialized with. Surface it loudly if
            // a caller ever asks for more than that, so a future caller
            // hitting the same class of bug that root-caused the
            // multi-turn SIGSEGV (see .incomplete.md) shows up in logs
            // instead of silently corrupting state under KV-cache pressure.
            val loadedCeiling = currentMaxTokens
            if (loadedCeiling != null && maxTokens > loadedCeiling) {
                MindlayerLog.w(
                    TAG,
                    "initialize() requested maxTokens=$maxTokens but the already-loaded engine " +
                        "was initialized with maxTokens=$loadedCeiling; the smaller ceiling stays " +
                        "in effect for this engine's lifetime (re-init required to change it).",
                )
            }
            _state.value = EngineState.Ready
            return eng
        }

        // F-077: a fresh init attempt supersedes the previous run's outcome.
        // Reset only AFTER the cache fast-path so a cached-engine return
        // continues to surface "running on CPU because GPU failed" state
        // captured by the prior run. A real new attempt past this point
        // populates the field via [recordInitFailure] at every failure site.
        lastInitFailure = null

        val path = target.path
        // F-{cache-contamination}: wipe the per-attempt LiteRT-LM cache
        // directory before init. The Phase-0 spike (commit ad1e199) found
        // that a previously failed init can leave a partially-written
        // compiled-model cache in this directory; subsequent inits then
        // fail with `NOT_FOUND: TF_LITE_PREFILL_DECODE not found in the
        // model`, which surfaces as an utterly misleading "model is
        // corrupted" error even though the model file is intact. The cost
        // of always wiping is one re-compile on cold start; the win is a
        // crashed init never silently bricks the next attempt.
        val cacheDir = context.cacheDir.resolve("litert_cache").also { dir ->
            if (dir.exists()) {
                runCatching { dir.deleteRecursively() }
                    .onFailure { t ->
                        MindlayerLog.w(
                            TAG,
                            "Failed to wipe litert_cache before init: ${t.safeLabel()}",
                            throwable = null,
                        )
                    }
            }
            dir.mkdirs()
        }

        // F-002: verify the on-disk model SHA-256 matches the build-time
        // manifest before handing it to native init. APK signing protects
        // delivery via the AI Pack, but the extracted file in `filesDir`
        // is mutable (root, OEM agents) and never re-verified on later
        // loads. When `BuildConfig.MODEL_SHA256` is empty (the dev/CI
        // default) we emit a loud warning instead of failing — once the
        // build pipeline pins the manifest, mismatches must hard-fail.
        // S-11: bookend the verify+load window with a cheap file-identity
        // check so a model-file swap BETWEEN the SHA-256 hash and the native
        // loader's open() (a verify-then-load TOCTOU) is detected and fails
        // closed. Capture identity BEFORE hashing so the hash itself falls
        // inside the guarded interval. Only meaningful when a release manifest
        // is pinned; the dev/CI empty-manifest path skips both the hash and
        // this bookend.
        val integrityEnforced = expectedModelSha256.isNotEmpty()
        val preLoadModelIdentity: ModelFileIdentity? =
            if (integrityEnforced) captureModelFileIdentity(File(path)) else null
        try {
            verifyModelIntegrity(File(path))
        } catch (e: SecurityException) {
            // F-077: dashboard remediation is "Model file corrupted —
            // reinstall." Distinct from a missing file (ModelMissing) so
            // the user knows the file exists but failed verification.
            recordInitFailure(InitFailure.IntegrityMismatch)
            throw e
        }

        // Pre-flight memory check: model needs sufficient RAM at peak.
        // F-071: this check hard-fails with a typed [LowMemoryException]
        // so SessionManager can stop the SDK retry loop. Previously this
        // logged a warning and proceeded, which SIGABRT'd inside native
        // model load on 4–6 GB devices. The debug-only
        // [BuildConfig.ALLOW_LOW_MEM] override (gated behind
        // `-Pmindlayer.allowLowMem=true`) restores the warn-and-proceed
        // behaviour for developer machines under transient memory
        // pressure during local debugging — release builds always
        // hard-fail.
        //
        // F-079 (revised): the previous heuristic computed
        // `requiredBytes = modelSize + 256 MB` and required
        // `availMem >= requiredBytes` on devices < 6 GB total RAM. For
        // Gemma 4 E2B (2.4 GB model file) that meant any sub-6 GB
        // device had to have >= 2.6 GB available — effectively a
        // permanent refusal on 4 GB-class phones, even though the
        // file is mmap'd and only ~175 MiB of pages stay resident
        // (empirically measured — see docs/engine/MEMORY_TIERS_EMPIRICS.md).
        //
        // The replacement gate uses an empirically-derived peak
        // residency estimate (not the on-disk file size) plus the
        // 1 GB availMem runway for the init burst. This admits 4 GB
        // and even 3 GB-class devices where the kernel's reclaimable
        // cache can satisfy the mmap'd weights during load.
        //
        // Headroom values reflect the analysis writeup:
        //   * `engineResidencyMb = 700` — model resident pages (~175 MiB
        //     measured) + activation peak (~256 MiB estimated) + LiteRT-LM
        //     compile-cache + sampler/decode buffers, rounded up for safety.
        //   * `systemReserveMb = 1024` — kernel + always-on apps + system_server.
        //   * `foregroundAppBudgetMb = 700` — the user's interactive app.
        //   * `minAvailFloorMb = 1024` — preserved from the original gate;
        //     this is the init burst runway (LiteRT-LM allocates significant
        //     scratch during JIT/compile before mmap settles).
        val modelSizeBytes = target.sizeBytes
        val activityManager = context.getSystemService(android.app.ActivityManager::class.java)
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val availMb = memInfo.availMem / 1024 / 1024
        val totalMb = memInfo.totalMem / 1024 / 1024

        val engineResidencyMb = 700L
        val systemReserveMb = 1024L
        val foregroundAppBudgetMb = 700L
        val minAvailFloorMb = 1024L
        val totalFloorMb = engineResidencyMb + systemReserveMb + foregroundAppBudgetMb

        val gateOk = totalMb >= totalFloorMb && availMb >= minAvailFloorMb
        MindlayerLog.i(TAG, "Loading model '${target.id}' (${target.displayName})")
        MindlayerLog.i(
            TAG,
            "Memory check: total=${totalMb}MB (floor=${totalFloorMb}MB), " +
                "available=${availMb}MB (floor=${minAvailFloorMb}MB), " +
                "model_on_disk=${modelSizeBytes / 1024 / 1024}MB, " +
                "gate=${if (gateOk) "open" else "refuse"}",
        )
        if (!gateOk) {
            if (com.adsamcik.mindlayer.service.BuildConfig.ALLOW_LOW_MEM) {
                MindlayerLog.w(TAG, "ALLOW_LOW_MEM override active; proceeding with " +
                    "${availMb}MB available, ${minAvailFloorMb}MB available-floor, " +
                    "${totalMb}MB total, ${totalFloorMb}MB total-floor (debug build only)")
            } else {
                MindlayerLog.w(TAG, "Refusing engine init: ${availMb}MB available, " +
                    "${totalMb}MB total (need >=${minAvailFloorMb}MB available + " +
                    ">=${totalFloorMb}MB total) for model '${target.id}'")
                // F-077: categorise BEFORE throwing so observers (dashboard,
                // logs) see the typed signal even on this terminal path.
                recordInitFailure(InitFailure.LowMemory)
                throw LowMemoryException(availMb = availMb, requiredMb = totalFloorMb)
            }
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
                    // F-{vision}: when the model advertises multimodal vision
                    // (Gemma 4 family), reuse the chosen text backend for the
                    // vision encoder. Setting `visionBackend = null` would
                    // skip vision-executor init entirely, and the native
                    // engine SIGSEGVs on the first image content part (see
                    // LiteRT-LM #1874). CPU on emulator is the validated
                    // path; GPU is exercised on real-device CI lanes.
                    visionBackend = if (target.supportsVision) backend else null,
                    maxNumTokens = maxTokens,
                    // F-{vision}: LiteRT-LM defaults `max_num_images` to 0
                    // even when the vision executor is loaded — the result
                    // is `INVALID_ARGUMENT: Provided more images than
                    // expected in the prompt` on Android x86_64 + .litertlm
                    // models whose metadata header doesn't ship the field
                    // (see LiteRT-LM #1686 comments). Forcing a positive
                    // explicit value bypasses the auto-derivation bug.
                    maxNumImages = target.maxImagesPerTurn.takeIf { it > 0 },
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

                // S-11: best-effort TOCTOU detection. If the on-disk model
                // file's identity (size / mtime / inode) changed across the
                // hash+load window, the bytes the native loader mmap'd may not
                // be the bytes we verified — fail closed. This is a detection
                // bookend, NOT a cryptographic guarantee that the exact loaded
                // bytes were hashed: a privileged attacker could swap-and-
                // restore within the window. A full fd-load fix is impossible
                // while EngineConfig only accepts a path string. On Android
                // the inode-bearing fileKey catches rename/replace swaps that
                // preserve size and mtime.
                if (preLoadModelIdentity != null &&
                    !modelFileIdentityUnchanged(
                        preLoadModelIdentity,
                        captureModelFileIdentity(File(path)),
                    )
                ) {
                    runCatching { eng.close() }
                    MindlayerLog.e(
                        TAG,
                        "Model file identity changed during native load " +
                            "(TOCTOU); refusing to use engine.",
                    )
                    throw ModelIntegrityViolation()
                }

                val elapsed = (System.nanoTime() - startNs) / 1_000_000_000f
                val durationMs = ((System.nanoTime() - startNs) / 1_000_000)
                MindlayerLog.i(TAG, "Engine initialized: model=${target.id}, backend=$name, time=${elapsed}s (tried ${backends.map { backendName(it) }})")

                // F-077: do NOT clear lastInitFailure on successful init via
                // fallback. The dashboard relies on "GPU failed -> running on
                // CPU" being visible (matches the previous lastGpuFailureReason
                // semantics where the GPU label persisted across CPU-fallback
                // success). The reset at the top of initialize() guarantees
                // a fresh first-attempt success leaves the field null.
                engine = eng
                currentBackend = name
                currentModel = target
                currentMaxTokens = maxTokens
                initTimeSeconds = elapsed
                isInitialized = true
                logRepository?.logEngineInit(name, durationMs, path)
                _state.value = EngineState.Ready
                return eng

            } catch (e: ModelIntegrityViolation) {
                // S-11: a load-window model swap is a security failure, not a
                // backend problem — do NOT fall through to the next backend.
                // Record IntegrityMismatch here so it can't be masked by an
                // earlier BackendUnavailable from a prior backend in the chain
                // (the outer catch keeps the first-recorded failure).
                recordInitFailure(InitFailure.IntegrityMismatch)
                throw e
            } catch (t: Throwable) {
                // F-006: never persist or surface raw native exception text.
                // LiteRT-LM tokenizer/template exceptions can inline prompt
                // fragments; safeLabel() returns class names only. The
                // `safeDetailLogcat` variant (allowlist-gated) surfaces the
                // native LiteRT/LiteRT-LM JNI status string for logcat only
                // — never persisted, never crosses the AIDL boundary. The
                // `safeDetail` value persisted via [InitFailure] and the
                // log DB stays class-name-only.
                val safeDetail = t.safeLabel()
                val safeDetailLogcat = t.safeLabelWithDetail()
                MindlayerLog.w(TAG, "Backend $name failed: $safeDetailLogcat")
                // F-077: every per-backend failure now categorises into a
                // typed [InitFailure.BackendUnavailable] — replaces the
                // GPU-only `lastGpuFailureReason` string. The dashboard
                // renders "X backend failed (label) — running on CPU"
                // when the next backend in the chain succeeds.
                recordInitFailure(InitFailure.BackendUnavailable(name, safeDetail))
                lastError = t
                logRepository?.log(com.adsamcik.mindlayer.service.logging.LogEntry(
                    timestampMs = System.currentTimeMillis(),
                    category = com.adsamcik.mindlayer.service.logging.LogCategory.ENGINE,
                    event = com.adsamcik.mindlayer.service.logging.LogEvent.ENGINE_FALLBACK.key,
                    backend = name,
                    errorMessage = safeDetail,
                ))
            }
        }

        val triedBackends = backends.joinToString(",") { backendName(it) }
        throw IllegalStateException(
            "All backends failed for model '${target.id}' at $path " +
                "(tried=[$triedBackends], available RAM: ${availMb}MB, model: ${modelSizeBytes / 1024 / 1024}MB). " +
                "Try closing other apps to free memory.", lastError
        )
        } catch (t: Throwable) {
            val existingFailure = lastInitFailure
            val failure = existingFailure ?: classifyInitFailure(t)
            if (existingFailure == null) recordInitFailure(failure)
            _state.value = EngineState.Failed(failure)
            throw t
        }
    }

    suspend fun awaitReady(): EngineState = awaitReady(DEFAULT_AWAIT_READY_TIMEOUT_MS)

    /**
     * H-E2: suspend until the engine settles into [EngineState.Ready] or
     * [EngineState.Failed], or until [timeoutMs] elapses. On timeout the
     * waiter receives a typed [EngineState.Failed] carrying
     * [InitFailure.NativeError]; the in-flight init job is left running
     * so a slow-but-eventual success still rearms the engine. Callers
     * MUST treat the returned state as authoritative for THIS request
     * only and re-call [awaitReady] for any later use.
     */
    suspend fun awaitReady(timeoutMs: Long): EngineState {
        val current = _state.value
        if (current is EngineState.Ready || current is EngineState.Failed) return current
        return try {
            withTimeout(timeoutMs) {
                state.first { it is EngineState.Ready || it is EngineState.Failed }
            }
        } catch (_: TimeoutCancellationException) {
            MindlayerLog.w(
                TAG,
                "awaitReady() timed out after ${timeoutMs}ms while engine state=${_state.value}",
            )
            EngineState.Failed(InitFailure.NativeError("init timeout"))
        }
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
        val expected = expectedModelSha256
        if (expected.isEmpty()) {
            if (!com.adsamcik.mindlayer.service.BuildConfig.DEBUG) {
                MindlayerLog.e(TAG, "Release build has empty BuildConfig.MODEL_SHA256; refusing to load model.")
                throw SecurityException("Model integrity check failed: missing release SHA-256")
            }
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

    /**
     * S-11: lightweight identity of the on-disk model file used by the
     * verify+load TOCTOU bookend. On Android/Linux [fileKey] is a non-null
     * `UnixFileKey` encoding (st_dev, st_ino), so a rename/replace swap that
     * preserves size and mtime is still detected via the inode change. On a
     * Windows dev host [fileKey] may be null; the bookend then degrades to
     * (size, mtime), which is adequate for the unit tests (they mutate size).
     */
    private data class ModelFileIdentity(
        val sizeBytes: Long,
        val lastModifiedMs: Long,
        val fileKey: Any?,
    )

    private fun captureModelFileIdentity(file: File): ModelFileIdentity? = runCatching {
        val attrs = java.nio.file.Files.readAttributes(
            file.toPath(),
            java.nio.file.attribute.BasicFileAttributes::class.java,
        )
        ModelFileIdentity(
            sizeBytes = attrs.size(),
            lastModifiedMs = attrs.lastModifiedTime().toMillis(),
            fileKey = attrs.fileKey(),
        )
    }.getOrNull()

    /**
     * True only when [after] is non-null and matches [before] on size, mtime,
     * and (when both are present) the inode-bearing [ModelFileIdentity.fileKey].
     * A null [after] (file vanished mid-load) is treated as changed so the
     * caller fails closed.
     */
    private fun modelFileIdentityUnchanged(
        before: ModelFileIdentity,
        after: ModelFileIdentity?,
    ): Boolean {
        if (after == null) return false
        if (before.sizeBytes != after.sizeBytes) return false
        if (before.lastModifiedMs != after.lastModifiedMs) return false
        val a = before.fileKey
        val b = after.fileKey
        if (a != null && b != null && a != b) return false
        return true
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
     * M-E1: atomically check [predicate] under the engine mutex and only
     * tear the engine down if it still holds. Used by
     * [com.adsamcik.mindlayer.service.MindlayerMlService.onTrimMemory]
     * to close the TOCTOU between "no active streams" check and the
     * native unload: while the mutex is held, no concurrent
     * [initialize] / [switchBackend] / [shutdown] can interleave, so a
     * fresh request that races our pressure-driven unload either:
     *  - lost the race and now finds the engine Idle (triggering a fresh
     *    rearm via [SessionManager.ensureInitStarted]), or
     *  - won the race and we observe its in-flight work via [predicate]
     *    (returning `false` and skipping the unload).
     *
     * Returns `true` if the engine was actually torn down.
     */
    suspend fun shutdownIfIdle(predicate: () -> Boolean): Boolean = mutex.withLock {
        if (!predicate()) {
            MindlayerLog.i(TAG, "shutdownIfIdle: predicate false, skipping native unload")
            return@withLock false
        }
        shutdownInternal()
        true
    }

    /**
     * Restart the `:ml` service process to obtain a fresh native engine
     * state, instead of doing an in-process `shutdown() + initialize()`.
     *
     * # Why this exists
     *
     * LiteRT-LM bug [#2028](https://github.com/google-ai-edge/LiteRT-LM/issues/2028)
     * — Gemma 4 E2B on CPU running inside an `android:process=":ml"`
     * isolated service SIGSEGVs in `liblitertlm_jni.so` on the SECOND
     * call to `Engine.close()` + new `Engine()` in the same process. The
     * native runtime's dispatch-delegate registry retains pointers into
     * freed memory across the close/recreate boundary; the next init
     * reads garbage out of `magic_number_utils.cc` and crashes.
     *
     * Until the upstream fix lands we sidestep the in-process recreate
     * path entirely: record the desired post-restart state to
     * [restartStore], drain in-flight work, then call [processKiller]
     * (production: `Process.killProcess(myPid())`). Android auto-restarts
     * the service the next time a client binds; the fresh process reads
     * the intent and starts engine warmup against [targetBackend].
     *
     * # Behaviour
     *
     * - **Persistence first**: the intent is written under the engine
     *   mutex and before any draining, so a client whose bind races our
     *   kill will at worst trigger a fresh restart against the same
     *   intent (idempotent — [EngineRestartStore] dedupes against the
     *   same target).
     * - **No in-process shutdown**: callers that previously did
     *   `engineManager.shutdown()` + `engineManager.initialize(target)`
     *   should now call this method instead. The native engine is
     *   never `close()`d in the doomed process — we just exit.
     * - **One-way**: after [processKiller] returns we will not. Code
     *   placed after this call inside the same coroutine will not run.
     *
     * # Loop prevention
     *
     * [EngineRestartStore.consume] returns `null` once the same target
     * backend has been requested [EngineRestartStore.MAX_RESTART_ATTEMPTS]
     * times in a row without a `clear()` (i.e. without a successful
     * post-restart init). The post-restart service then falls back to
     * the default backend chain.
     *
     * @param reason short opaque label (e.g. `"thermal_switch"`,
     *   `"memory_pressure"`, `"manual"`). Persisted verbatim — must not
     *   contain prompt text or user content.
     * @param targetBackend backend the post-restart init should prefer;
     *   `null` uses the default chain (GPU → CPU).
     * @param maxTokens KV-cache budget to apply on the post-restart
     *   init.
     */
    suspend fun shutdownAndRestart(
        reason: String,
        targetBackend: String? = null,
        maxTokens: Int = 4096,
    ): Unit = mutex.withLock {
        MindlayerLog.w(
            TAG,
            "Engine process-restart requested: reason=$reason, " +
                "currentBackend=$currentBackend, targetBackend=${targetBackend ?: "<default>"}",
        )
        // Persist the intent first. If the persistent write fails the
        // post-restart engine falls back to its default chain — safe and
        // correct, just not what the caller asked for. Persistence
        // failures are not fatal here; we still proceed to restart.
        val intent = restartStore.record(
            reason = reason,
            targetBackend = targetBackend,
            maxTokens = maxTokens,
        )
        if (intent == null) {
            MindlayerLog.w(
                TAG,
                "Engine restart intent persistence failed; post-restart will use default backend chain",
            )
        }
        logRepository?.logEngineRestart(
            reason = reason,
            targetBackend = targetBackend,
            currentBackend = currentBackend,
            attemptCount = intent?.attemptCount ?: 0,
        )
        // Drain native state best-effort BEFORE killing the process: this
        // is intentionally NOT a `close()` call (that's the path #2028
        // explodes on). We simply flip the in-memory state so any
        // late-arriving caller on the same coroutine sees Idle and
        // exits early rather than racing the kill.
        _state.value = EngineState.Idle
        // Hand-off to Android. The service is bindAutoCreate'd so the
        // next client bind brings us back; the new process consumes the
        // restart intent in its onCreate path.
        processKiller()
        // Unreachable in production; defensive in tests where
        // processKiller is a no-op.
    }

    /**
     * Returns and clears the persisted restart intent recorded by an
     * earlier process via [shutdownAndRestart]. Callers (typically
     * [com.adsamcik.mindlayer.service.MindlayerMlService] during
     * `onCreate`) should call this exactly once on service startup and
     * pass the returned [EngineRestartStore.RestartIntent.targetBackend]
     * to [initialize] as the preferred backend.
     *
     * Returns `null` when no intent is recorded or the loop-prevention
     * cap ([EngineRestartStore.MAX_RESTART_ATTEMPTS]) has been hit; the
     * caller should then init with `preferredBackend = null` (default
     * chain).
     */
    fun consumePendingRestartIntent(): EngineRestartStore.RestartIntent? =
        restartStore.consume()

    /**
     * R-7: begin a post-restart init attempt — bumps + persists the attempt
     * count and returns the intent without clearing it. Pair with
     * [clearPendingRestartIntent] AFTER a successful init so a crashing
     * init (LiteRT-LM #2028 SIGSEGV) leaves the bumped count behind and the
     * crash-loop guard actually trips at the cap.
     */
    fun beginPendingRestartAttempt(): EngineRestartStore.RestartIntent? =
        restartStore.beginAttempt()

    /**
     * Clear any pending restart intent without consuming it. Called
     * after a successful post-restart [initialize] so a subsequent
     * unrelated init failure doesn't fall back to "attempt 2 of the
     * same target." Idempotent.
     */
    fun clearPendingRestartIntent() = restartStore.clear()

    /**
     * Switch to a different backend (e.g. thermal-fallback from GPU → CPU).
     *
     * **DO NOT use this in production code paths** — the in-process
     * `shutdown() + initialize()` it performs is the exact sequence that
     * triggers LiteRT-LM #2028 SIGSEGV on the second iteration. New code
     * should call [shutdownAndRestart] with the desired `targetBackend`
     * instead, which gets a fresh native process via Android service
     * auto-restart.
     *
     * Retained only for the test seam (`switchBackend` is the entry
     * point exercised by `EngineManagerTest.backendChainFallbackTest`,
     * which mocks the LiteRT-LM Engine and so does not trip #2028).
     */
    @VisibleForTesting
    @Deprecated(
        message = "In-process backend switch is unsafe per LiteRT-LM #2028. " +
            "Use shutdownAndRestart(reason, targetBackend) instead — production " +
            "callers (MindlayerMlService) have already migrated.",
        replaceWith = ReplaceWith("shutdownAndRestart(reason, newBackend, maxTokens)"),
    )
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

    private fun classifyInitFailure(t: Throwable): InitFailure = when (t) {
        is LowMemoryException -> InitFailure.LowMemory
        is SecurityException -> InitFailure.IntegrityMismatch
        is IllegalStateException -> if (t.message?.contains("No .litertlm model files", ignoreCase = true) == true) {
            InitFailure.ModelMissing
        } else {
            InitFailure.NativeError(t.safeLabel())
        }
        else -> InitFailure.NativeError(t.safeLabel())
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
            MindlayerLog.i(TAG, "Shutting down engine (backend=$backend, model=${currentModel?.id})")
            withContext(Dispatchers.IO) {
                try {
                    eng.close()
                } catch (t: Throwable) {
                    MindlayerLog.e(TAG, "Error closing engine: ${t.safeLabel()}")
                }
            }
            engine = null
            _state.value = EngineState.Idle
            currentBackend = "NONE"
            currentModel = null
            currentMaxTokens = null
            isInitialized = false
            initTimeSeconds = 0f
            // F-077: clear typed failure state on explicit shutdown.
            // The shim `lastGpuFailureReason` resolves through this field
            // and so is implicitly cleared in lock-step (preserves the
            // pre-F-077 behaviour the leak-prevention tests rely on).
            lastInitFailure = null
            logRepository?.logEngineShutdown(backend)
            MindlayerLog.i(TAG, "Engine shutdown complete")
        }
    }

    /**
     * F-077: record a typed init failure and persist a categorised log
     * event. Centralised so every population site goes through the same
     * `lastInitFailure = …` + `LogRepository.logInitFailureCategorized`
     * pair — keeps the in-memory state and the persisted dashboard signal
     * in sync, and gives the safeLabel-only F-006 invariant a single
     * audit point.
     */
    private fun recordInitFailure(failure: InitFailure) {
        lastInitFailure = failure
        logRepository?.logInitFailureCategorized(failure)
    }

    /**
     * Build an ordered list of [Backend]s to try.
     *
     * * If [preferred] is specified, that backend comes first, followed by
     *   lower tiers as fallback.
     * * If `null`, the default chain is GPU → CPU.
     */
    private fun resolveBackendChain(preferred: String?): List<Backend> {
        val decision = LiteRtAcceleratorResolver.resolveBackend(
            requested = preferred,
            featureName = "chat",
            nativeLibraryDir = context.applicationInfo.nativeLibraryDir,
        )
        logRepository?.logBackendDecision(
            featureName = "chat",
            backend = decision.backend,
            reason = decision.reason,
            attempted = decision.attempted,
        )
        return when (decision.backend) {
            LiteRtAcceleratorResolver.BACKEND_NPU -> listOf(
                Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir),
                Backend.GPU(),
                Backend.CPU(),
            )
            LiteRtAcceleratorResolver.BACKEND_GPU -> listOf(Backend.GPU(), Backend.CPU())
            LiteRtAcceleratorResolver.BACKEND_CPU -> listOf(Backend.CPU())
            else -> listOf(Backend.GPU(), Backend.CPU())
        }
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
     * Legacy test-visible heuristic mirrored by [LiteRtAcceleratorResolver].
     * Production backend selection goes through the shared resolver so chat,
     * embeddings, and OCR expose the same downgrade-decision surface.
     */
    private fun isNpuLikelySupported(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false

        @Suppress("InlinedApi")
        val soc = Build.SOC_MODEL.orEmpty().lowercase()
        if (soc.isEmpty()) return false

        val socKnown = soc in QUALCOMM_NPU_SOCS || soc in MEDIATEK_NPU_SOCS
        if (!socKnown) return false

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
