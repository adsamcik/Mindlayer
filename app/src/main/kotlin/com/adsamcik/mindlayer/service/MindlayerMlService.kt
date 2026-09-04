package com.adsamcik.mindlayer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.annotation.RequiresApi
import androidx.core.app.ServiceCompat
import com.adsamcik.mindlayer.service.engine.DeferredDatabase
import com.adsamcik.mindlayer.service.engine.DeferredStore
import com.adsamcik.mindlayer.service.engine.EngineManager
import com.adsamcik.mindlayer.service.engine.EmbeddingCoordinator
import com.adsamcik.mindlayer.service.engine.EmbeddingEngine
import com.adsamcik.mindlayer.service.engine.ForegroundTracker
import com.adsamcik.mindlayer.service.engine.InferenceOrchestrator
import com.adsamcik.mindlayer.service.engine.MemoryBudget
import com.adsamcik.mindlayer.service.engine.MemoryPressure
import com.adsamcik.mindlayer.service.engine.OcrSessionManager
import com.adsamcik.mindlayer.service.engine.PaddleOcrEngine
import com.adsamcik.mindlayer.service.engine.PrewarmContextPolicy
import com.adsamcik.mindlayer.service.engine.SessionManager
import com.adsamcik.mindlayer.service.engine.ThermalBand
import com.adsamcik.mindlayer.service.engine.ThermalMonitor
import com.adsamcik.mindlayer.service.health.MlHealthRecorder
import com.adsamcik.mindlayer.service.logging.DiagnosticExporter
import com.adsamcik.mindlayer.service.logging.LogCategory
import com.adsamcik.mindlayer.service.logging.LogDatabase
import com.adsamcik.mindlayer.service.logging.LogEntry
import com.adsamcik.mindlayer.service.logging.LogEvent
import com.adsamcik.mindlayer.service.logging.LogRepository
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.logging.safeLabel
import com.adsamcik.mindlayer.service.modeldelivery.DefaultLiveModelRuntimeController
import com.adsamcik.mindlayer.service.modeldelivery.LiveModelRuntimeController
import com.adsamcik.mindlayer.service.modeldelivery.LiveRuntimeReleaseResult
import com.adsamcik.mindlayer.service.modeldelivery.ModelFamily
import com.adsamcik.mindlayer.service.modeldelivery.ModelRuntimeControlRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import com.adsamcik.mindlayer.service.ipc.SharedMemoryPool
import com.adsamcik.mindlayer.service.security.AllowlistStore
import com.adsamcik.mindlayer.service.security.debugAutoAcceptAllEnabled
import com.adsamcik.mindlayer.service.engine.mock.mockEnginesOrNull
import com.adsamcik.mindlayer.service.security.EvictionRegistry

class MindlayerMlService : Service() {

    companion object {
        private const val TAG = "MindlayerMlService"
        private const val NOTIFICATION_CHANNEL_ID = "mindlayer_inference"
        private const val NOTIFICATION_ID = 1
        private const val LOG_CLEANUP_INTERVAL_MS = 24L * 60 * 60 * 1000
        private const val OCR_SESSION_SWEEP_INTERVAL_MS = 60_000L
        private const val EMERGENCY_DRAIN_TIMEOUT_MS = 2_000L
        private const val MODEL_RELEASE_DRAIN_TIMEOUT_MS = 5_000L

        /** Hidden-client grace before closing the independently reloadable engines. */
        const val AUXILIARY_IDLE_RELEASE_DELAY_MS = 30_000L

        /**
         * R-3: how long a thermal-driven GPU→CPU downshift may be deferred
         * (because inference is in flight) before it is FORCED under a
         * CRITICAL thermal band. Pre-fix the switch only applied when
         * activeInferenceCount hit 0, so sustained overlapping inferences
         * could starve it and keep the SoC decoding on an overheating GPU.
         * 20 s balances "let a short inference finish" against "don't cook
         * the device".
         */
        private const val THERMAL_FORCE_SWITCH_DEADLINE_MS = 20_000L

        /** R-3: bounded wait for cancelled jobs to unwind before a forced restart. */
        private const val FORCED_SWITCH_DRAIN_TIMEOUT_MS = 3_000L

        /**
         * Context growth is not urgent enough to tear down an active decode.
         * Give existing work a full minute to finish, then abort the resize
         * and let the requesting SDK retry instead of killing mid-inference.
         */
        private const val CONTEXT_RESIZE_DRAIN_TIMEOUT_MS = 60_000L

        const val STATE_IDLE = "idle"
        const val STATE_LOADING = "loading"
        const val STATE_READY = "ready"
        const val STATE_INFERRING = "inferring"

        /**
         * F-043: STOP intent action posted by the foreground notification.
         * Triggers a graceful shutdown of all active inferences and exits
         * the foreground state.
         */
        const val ACTION_STOP = "com.adsamcik.mindlayer.service.ACTION_STOP"
    }

    lateinit var engineManager: EngineManager
        private set
    lateinit var sessionManager: SessionManager
        private set
    lateinit var orchestrator: InferenceOrchestrator
        private set
    lateinit var memoryBudget: MemoryBudget
        private set
    lateinit var thermalMonitor: ThermalMonitor
        private set
    lateinit var mlHealthRecorder: MlHealthRecorder
        private set
    lateinit var deferredStore: DeferredStore
        private set
    lateinit var embeddingEngine: EmbeddingEngine
        private set
    lateinit var embeddingCoordinator: EmbeddingCoordinator
        private set
    /**
     * Phase 3 #1: service-owned PaddleOCR engine. Instantiated lazily in
     * [onCreate]; eager async init kicked off so [PaddleOcrEngine.state]
     * transitions to [com.adsamcik.mindlayer.service.engine.PaddleOcrEngineState.Ready]
     * (or `Failed`) without waiting for the first `pushOcrFrame` call.
     * That lets [com.adsamcik.mindlayer.service.engine.OcrSessionManager.isEngineReady]
     * flip the [com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_OCR_SESSION]
     * capability flag during the SDK's initial handshake.
     */
    lateinit var paddleOcrEngine: PaddleOcrEngine
        private set
    lateinit var ocrSessionManager: OcrSessionManager
        private set
    private lateinit var sharedMemoryPool: SharedMemoryPool
    private lateinit var binder: ServiceBinder
    private lateinit var logRepository: LogRepository
    private lateinit var modelRuntimeController: LiveModelRuntimeController

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val applyPressureMutex = Mutex()

    @Volatile
    var serviceState: String = STATE_IDLE
        private set

    @Volatile
    private var pendingBackend: String? = null

    /**
     * R-3: wall-clock (elapsedRealtime) at which the current [pendingBackend]
     * switch first started being deferred because inference was in flight.
     * `0L` = not currently deferring. Guarded by [stateLock]. Used to force
     * an overdue thermal downshift under a CRITICAL band instead of starving
     * it indefinitely under sustained load.
     */
    private var pendingBackendSince: Long = 0L

    /**
     * R-6: set under [stateLock] when [applyPendingBackendSwitch] has decided
     * to restart the process for a backend switch. While true, [enterForeground]
     * rejects new inferences so none can race the restart between the
     * decision and the actual process kill. Never cleared on the happy path
     * (the process dies); reset only if the restart launch fails.
     */
    @Volatile
    private var quiescingForRestart = false

    /** Largest context requested while a context-resize restart is draining. */
    private var pendingContextResizeTokens: Int? = null

    /** Guarded by [stateLock]; distinguishes context resize from other restarts. */
    private var contextResizeInFlight = false

    /** Work such as async prewarm that must block idle teardown but is not inference/FGS. */
    private var idleProtectedWorkCount = 0

    /** True from the last framework bind until the final client unbinds. */
    private var clientsBound = false

    /** Set after the service atomically approves hidden SDK clients to unbind. */
    private var idleDisconnectGranted = false

    /** Prevents native requests from racing an auxiliary-engine shutdown. */
    private var auxiliaryReleaseInProgress = false

    /** Causes OCR to rearm when a visible client returns; embeddings rearm lazily. */
    private var auxiliaryReleasedForIdle = false

    private var auxiliaryIdleJob: Job? = null

    /** Test seam. Production exits the isolated `:ml` process without a warm restart intent. */
    internal var idleProcessKiller: () -> Unit = {
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /** Test seam; production uses [AUXILIARY_IDLE_RELEASE_DELAY_MS]. */
    internal var auxiliaryIdleReleaseDelayMs: Long = AUXILIARY_IDLE_RELEASE_DELAY_MS

    var activeInferenceCount = 0
        private set
    private val stateLock = Any()
    var createdAtMs: Long = 0L
        private set

    /**
     * F-074: hold a reference to the prior default uncaught-exception
     * handler so we can chain to it after recording the abnormal death.
     * Skipping the chain would suppress the framework's own crash
     * reporting (e.g. ActivityThread's process-dump trigger).
     */
    private var previousUncaughtHandler: Thread.UncaughtExceptionHandler? = null

    override fun onCreate() {
        super.onCreate()
        createdAtMs = android.os.SystemClock.elapsedRealtime()
        MindlayerLog.i(TAG, "Service created in process ${android.os.Process.myPid()}")
        createNotificationChannel()

        // F-074: install the crash-loop watchdog *before* anything else
        // touches the engine. recordHealthyBoot() runs the missed-death
        // detection (previous boot ran but neither cleanly destroyed nor
        // raised an uncaught exception — i.e. OOM-killer SIGKILL) and the
        // 5-minute decay reset. The uncaught handler chains to whatever
        // was installed before us so framework crash reporting still
        // fires.
        //
        // We pass the host APK's `lastUpdateTime` so the watchdog can
        // skip the missed-death bump when the previous run was killed
        // by `pm install -r` / `pm clear` rather than by the OOM-killer
        // — the watchdog exists to break OOM-loops, not to penalise
        // dev iteration.
        mlHealthRecorder = MlHealthRecorder(this)
        val packageLastUpdateMs = try {
            packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        } catch (t: Throwable) {
            // Pre-Android-9 quirks, Robolectric, or a torn install all
            // land here. 0L disables the exemption — i.e. fall back to
            // legacy behaviour rather than masking a real crash loop.
            MindlayerLog.w(TAG, "PackageInfo.lastUpdateTime unreadable: ${t.safeLabel()}")
            0L
        }
        try {
            mlHealthRecorder.recordHealthyBoot(packageLastUpdateMs)
        } catch (t: Throwable) {
            MindlayerLog.w(TAG, "MlHealthRecorder.recordHealthyBoot raised: ${t.safeLabel()}")
        }
        previousUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                mlHealthRecorder.recordAbnormalDeath()
            } catch (_: Throwable) {
                // Never block the death path on I/O failure.
            }
            previousUncaughtHandler?.uncaughtException(thread, error)
        }

        val logDb = LogDatabase.getInstance(this)
        logRepository = LogRepository(logDb.logDao())

        val allowlistStore = AllowlistStore(this, logRepository = logRepository)

        engineManager = EngineManager(this, logRepository)
        memoryBudget = MemoryBudget(this, serviceScope, logRepository)
        thermalMonitor = ThermalMonitor(this, serviceScope, logRepository)
        // DEBUG-only "CI mock engines" mode. Evaluated ONCE here; null in
        // release and in debug builds without the system property set, so the
        // real engines are wired unconditionally in the common case. When
        // non-null, OCR + embeddings + OCR→LLM extraction are served by
        // synthetic backends that need no on-disk models, and the interactive
        // LLM (chat/vision/audio) path streams synthetic [mock] tokens — letting
        // a consumer app's CI verify its Mindlayer integration end-to-end on a
        // model-less runner.
        val mockEngines = mockEnginesOrNull(this)
        sessionManager = SessionManager(this, engineManager, memoryBudget, logRepository, mockMode = mockEngines != null)
        sharedMemoryPool = SharedMemoryPool(cacheDir)
        sharedMemoryPool.cleanupAll()
        deferredStore = DeferredStore(DeferredDatabase.getInstance(this).deferredDao())
        embeddingEngine = if (mockEngines != null) {
            EmbeddingEngine(this, backendFactory = mockEngines.embeddingBackendFactory, logRepository = logRepository)
        } else {
            EmbeddingEngine(this, logRepository = logRepository)
        }
        // M-D5: synchronously fail any STILL_RUNNING rows left over from a
        // prior process before `binder` is exposed. `serviceScope.launch`
        // returned before the SQL UPDATE ran, so an `onBind` arriving in
        // the first ~tens of ms could see stale `STILL_RUNNING` rows. The
        // call is a single bulk UPDATE on a freshly-opened SQLCipher DB
        // (one SQL statement, small table), safely runnable on the main
        // thread per the existing pattern.
        runBlocking { deferredStore.failRunningOnInit() }
        orchestrator = InferenceOrchestrator(this, sessionManager, sharedMemoryPool, logRepository, llmMockGenerator = mockEngines?.llmMockGenerator)
        val callbackRegistry = EvictionRegistry()
        embeddingCoordinator = EmbeddingCoordinator(embeddingEngine, deferredStore, this, serviceScope, callbackRegistry, sharedMemoryPool, defaultModelOverride = mockEngines?.embeddingDefaultModel)

        // Sweep orphaned embedding blobs before any client can bind. Two
        // crash windows can leave files in `cacheDir/embedding-blobs/<uid>/`
        // that aren't reachable from any DeferredStore row: (a) a `.tmp-*`
        // file from an interrupted atomic-rename in
        // `EmbeddingCoordinator.writeBlobFile`, (b) a `<requestId>.bin` that
        // was atomically moved into place but whose
        // `completeEmbeddingBatch` call never ran because the process died.
        // Both cases otherwise leak disk until the 24h TTL prune fires.
        runBlocking { embeddingCoordinator.cleanupOrphanBlobsOnStartup() }

        // Phase 2 #3: PaddleOCR engine + recognition dispatcher + OCR
        // session manager — all wired here so they share the same process
        // lifecycle as the LiteRT-LM Gemma engine and the LiteRT
        // EmbeddingGemma backend. See docs/architecture/LITERT_COEXISTENCE.md for the
        // 8-step validation checklist that must be run on a real device
        // when GPU/NPU delegates are flipped on; the
        // `EngineCoexistenceInstrumentedTest` runs an in-process
        // smoke version of that checklist on the CI emulator matrix.
        //
        // Phase 3 #1: promote `paddleOcrEngine` to a service-owned field
        // so `onDestroy` can `shutdown()` it and `observeMemoryPressure()`
        // can hook the `unloadForMemoryPressure()` path. Kick off eager
        // async `initialize()` so `state` transitions to Ready (or Failed
        // / ModelMissing) without waiting for the first `pushOcrFrame`
        // call — that lets `OcrSessionManager.isEngineReady()` flip the
        // FEATURE_OCR_SESSION capability flag during the SDK handshake.
        //
        // Phase 3 #4 (p3-gemma-extractor): production-mode structured
        // extractor. Per-frame opens a fresh LiteRT-LM Conversation off
        // the shared engine, sends the OcrEvidencePromptBuilder prompt,
        // parses the JSON response. Returns EMPTY when the engine is
        // not yet ready (so the dispatcher's per-line + per-barcode
        // fusion path keeps emitting events).
        paddleOcrEngine = if (mockEngines != null) {
            PaddleOcrEngine(this, logRepository = logRepository, backendFactory = mockEngines.paddleOcrBackendFactory)
        } else {
            PaddleOcrEngine(this, logRepository = logRepository)
        }
        val ocrLlmExtractor = mockEngines?.ocrLlmExtractor
            ?: com.adsamcik.mindlayer.service.engine
                .LiteRtLmGemmaOcrExtractorProduction.create(
                    engineProvider = { engineManager.getEngine() },
                )
        val ocrRecognitionDispatcher = com.adsamcik.mindlayer.service.engine.OcrRecognitionDispatcher(
            engine = paddleOcrEngine,
            llmExtractor = ocrLlmExtractor,
            foregroundTracker = object : ForegroundTracker {
                override fun enterForeground() = this@MindlayerMlService.enterForeground()
                override fun exitForeground() = this@MindlayerMlService.exitForeground()
            },
        )
        ocrSessionManager = OcrSessionManager(
            engine = paddleOcrEngine,
            recognitionDispatcher = ocrRecognitionDispatcher,
        )
        // Eager init — failure-tolerant. If the PaddleOCR bundle
        // isn't installed, the engine settles into `Failed(ModelMissing)`
        // and `isEngineReady()` stays false (FEATURE_OCR_SESSION stays
        // unadvertised). Lives on the IO dispatcher so it does NOT block
        // service startup.
        //
        // In mock mode the init is instead run **synchronously** before
        // `binder` is exposed: the mock backend's `isInitialized` fast-path
        // makes `initialize()` instant, and getCapabilities must observe
        // `PaddleOcrEngineState.Ready` on the very first SDK handshake for the
        // OCR capability flags to be advertised deterministically (no startup
        // race for the CI client).
        if (mockEngines != null) {
            runBlocking {
                runCatching { paddleOcrEngine.initialize() }
                    .onFailure { MindlayerLog.i(TAG, "Mock PaddleOCR init did not complete: ${it.safeLabel()}") }
            }
        } else {
            serviceScope.launch(Dispatchers.IO) {
                try {
                    paddleOcrEngine.initialize()
                } catch (t: Throwable) {
                    // Already logged + state==Failed inside PaddleOcrEngine.initialize().
                    // Swallow here — the capability flag stays off and the
                    // service keeps running for other features (LLM, embeddings).
                    MindlayerLog.i(
                        TAG,
                        "PaddleOCR eager init did not complete: ${t.safeLabel()}",
                    )
                }
            }
        }

        val diagnosticExporter = DiagnosticExporter(
            engineManager, thermalMonitor, memoryBudget, sessionManager, logDb.logDao()
        )
        binder = ServiceBinder(this, engineManager, orchestrator, diagnosticExporter, thermalMonitor, memoryBudget, allowlistStore = allowlistStore, consentChallengeStore = com.adsamcik.mindlayer.service.security.ConsentChallengeStore(this), consentAttemptStore = com.adsamcik.mindlayer.service.security.ConsentAttemptStore(this), logRepository = logRepository, mlHealthRecorder = mlHealthRecorder, deferredStore = deferredStore, embeddingCoordinator = embeddingCoordinator, callbackRegistry = callbackRegistry, ocrSessionManager = ocrSessionManager, sharedMemoryPool = sharedMemoryPool, paddleOcrEngine = paddleOcrEngine, ocrLlmExtractor = ocrLlmExtractor, autoAcceptGate = { debugAutoAcceptAllEnabled(this) }, mockEngineMode = mockEngines != null)
        modelRuntimeController = DefaultLiveModelRuntimeController(
            quiesceAction = ::quiesceModelRuntime,
            retryOcrActivation = { paddleOcrEngine.retryInitialize() },
            recordCleanShutdownBeforeProcessExit = { mlHealthRecorder.recordCleanShutdown() },
        )
        ModelRuntimeControlRegistry.install(modelRuntimeController)

        logRepository.log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.ENGINE,
            event = LogEvent.ENGINE_INIT.key,
            extraJson = """{"lifecycle":"onCreate"}""",
        ))

        memoryBudget.start()
        thermalMonitor.start()
        observeMemoryPressure()
        observeThermalPolicy()
        scheduleOcrSessionSweep()
        // F-022: schedule the documented 7-day log-retention cleanup.
        // Without it, logs accumulate forever which is undesirable on a
        // low-storage device. Implementation lives in `scheduleLogCleanup()`
        // below.
        scheduleLogCleanup()
    }

    override fun onBind(intent: Intent?): IBinder {
        synchronized(stateLock) {
            clientsBound = true
            idleDisconnectGranted = false
        }
        MindlayerLog.i(TAG, "Client bound: ${intent?.`package`}")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        synchronized(stateLock) { clientsBound = false }
        binder.markAllClientsInvisible()
        MindlayerLog.i(TAG, "Client unbound: ${intent?.`package`}")
        reevaluateIdleRelease()
        return true
    }

    override fun onRebind(intent: Intent?) {
        synchronized(stateLock) {
            clientsBound = true
            idleDisconnectGranted = false
        }
        MindlayerLog.i(TAG, "Client rebound: ${intent?.`package`}")
        reevaluateIdleRelease()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MindlayerLog.i(TAG, "onStartCommand, startId=$startId, action=${intent?.action}")
        // F-043: handle the STOP action posted by the foreground notification.
        // Tear down all in-flight inferences gracefully, drop the FGS, and stop
        // the service. Other commands fall through to the START_NOT_STICKY
        // path.
        if (intent?.action == ACTION_STOP) {
            MindlayerLog.i(TAG, "ACTION_STOP received; cancelling all inferences and stopping")
            cancelForegroundCountedSubsystems()
            try {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            } catch (_: Throwable) { /* best-effort */ }
            // Coroutine-scoped graceful shutdown: complete orchestrator
            // teardown asynchronously so we don't block the binder thread.
            serviceScope.launch {
                try {
                    orchestrator.shutdown()
                } finally {
                    stopSelf(startId)
                }
            }
            return START_NOT_STICKY
        }
        // START_NOT_STICKY: don't auto-restart; recovery is client-driven
        return START_NOT_STICKY
    }

    /**
     * R-22: defensive foreground-service timeout handler.
     *
     * Today the `specialUse` FGS type is exempt from the Android 15
     * (API 35) `dataSync`/`mediaProcessing` 6-hour cumulative timeout, so
     * the platform does not currently call this. But if a future platform
     * or Play policy revision extends time limits to `specialUse`, the OS
     * invokes `onTimeout(...)` and then crashes the service with
     * "did not stop within its timeout" if it is left unhandled. We demote
     * the FGS and cancel in-flight inference so the service degrades
     * cleanly instead of being force-killed.
     */
    @RequiresApi(34)
    override fun onTimeout(startId: Int) {
        handleFgsTimeout(startId, fgsType = null)
    }

    @RequiresApi(35)
    override fun onTimeout(startId: Int, fgsType: Int) {
        handleFgsTimeout(startId, fgsType)
    }

    private fun handleFgsTimeout(startId: Int, fgsType: Int?) {
        MindlayerLog.w(
            TAG,
            "FGS onTimeout(startId=$startId, fgsType=$fgsType) — cancelling inferences and demoting",
        )
        cancelForegroundCountedSubsystems()
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) { /* best-effort */ }
        synchronized(stateLock) { activeInferenceCount = 0 }
    }

    /**
     * R-9 / R-22: cancel EVERY foreground-counted subsystem (LLM
     * orchestrator, embedding coordinator, OCR sessions) so none keeps
     * running native work after the FGS notification is dropped on
     * ACTION_STOP or an FGS onTimeout. Each cancelled job releases its own
     * foreground refcount as it unwinds.
     */
    private fun cancelForegroundCountedSubsystems() {
        try {
            orchestrator.cancelAll()
        } catch (t: Throwable) {
            MindlayerLog.w(TAG, "orchestrator.cancelAll() raised: ${t.safeLabel()}")
        }
        if (::embeddingCoordinator.isInitialized) {
            try {
                embeddingCoordinator.cancelAll()
            } catch (t: Throwable) {
                MindlayerLog.w(TAG, "embeddingCoordinator.cancelAll() raised: ${t.safeLabel()}")
            }
        }
        if (::ocrSessionManager.isInitialized) {
            try {
                ocrSessionManager.cancelAllForMemoryPressure()
            } catch (t: Throwable) {
                MindlayerLog.w(TAG, "ocrSessionManager cancel raised: ${t.safeLabel()}")
            }
        }
    }

    override fun onDestroy() {
        MindlayerLog.i(TAG, "Service destroyed")
        synchronized(stateLock) {
            auxiliaryIdleJob?.cancel()
            auxiliaryIdleJob = null
        }
        if (::modelRuntimeController.isInitialized) {
            ModelRuntimeControlRegistry.clear(modelRuntimeController)
        }
        thermalMonitor.stop()
        memoryBudget.stop()
        // v0.4: tear down eviction-callback registrations (unlinks death recipients).
        // Must run before orchestrator.shutdown() — sessionManager.shutdown()
        // calls into destroySession (no-notice path), but a stray binder
        // transaction is still cheaper to skip with an empty registry.
        if (::binder.isInitialized) {
            binder.evictionRegistry.clear()
        }
        if (::embeddingEngine.isInitialized) {
            runBlocking { embeddingEngine.shutdown() }
        }
        if (::ocrSessionManager.isInitialized) {
            runBlocking { ocrSessionManager.shutdown() }
        }
        // Phase 3 #1: shut down the PaddleOCR engine so native delegate
        // resources are released alongside LiteRT-LM and EmbeddingGemma.
        // Best-effort — Throwables here are non-recoverable shutdown
        // failures and must NOT block the rest of the teardown chain.
        if (::paddleOcrEngine.isInitialized) {
            try {
                runBlocking { paddleOcrEngine.shutdown() }
            } catch (t: Throwable) {
                MindlayerLog.w(
                    TAG,
                    "PaddleOcrEngine.shutdown raised: ${t.safeLabel()}",
                    throwable = null,
                )
            }
        }
        orchestrator.shutdown()
        logRepository.shutdown()
        serviceScope.cancel()
        // F-074: stamp the clean-shutdown marker last so the next boot's
        // missed-death check (which compares lastBootAt to lastCleanShutdownAt)
        // sees a fresh timestamp. Failure here is best-effort — if the
        // file system is unhappy we'd rather not block the rest of the
        // teardown chain. Worst case the next boot bumps the death
        // count by one, which decays in 5 minutes anyway.
        if (::mlHealthRecorder.isInitialized) {
            try {
                mlHealthRecorder.recordCleanShutdown()
            } catch (t: Throwable) {
                MindlayerLog.w(TAG, "MlHealthRecorder.recordCleanShutdown raised: ${t.safeLabel()}")
            }
        }
        super.onDestroy()
    }

    private suspend fun quiesceModelRuntime(family: ModelFamily): LiveRuntimeReleaseResult =
        when (family) {
            ModelFamily.CHAT -> {
                orchestrator.cancelAll()
                check(orchestrator.awaitAllJobs(MODEL_RELEASE_DRAIN_TIMEOUT_MS)) {
                    "Chat runtime did not drain"
                }
                ocrSessionManager.drainForMemoryPressure()
                sessionManager.shutdown()
                LiveRuntimeReleaseResult.RequiresProcessExit(android.os.Process.myPid())
            }
            ModelFamily.EMBEDDINGS -> {
                embeddingCoordinator.cancelAll()
                check(embeddingCoordinator.awaitAllJobs(MODEL_RELEASE_DRAIN_TIMEOUT_MS)) {
                    "Embedding runtime did not drain"
                }
                embeddingEngine.shutdown()
                LiveRuntimeReleaseResult.Released
            }
            ModelFamily.OCR -> {
                ocrSessionManager.drainForMemoryPressure()
                paddleOcrEngine.shutdown()
                LiveRuntimeReleaseResult.Released
            }
        }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        MindlayerLog.w(TAG, "onTrimMemory level=$level")

        logRepository.log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.MEMORY,
            event = LogEvent.PRESSURE_CHANGE.key,
            extraJson = """{"trimLevel":$level}""",
        ))

        // Forward to MemoryBudget for immediate re-evaluation and escalation
        memoryBudget.onTrimMemory(level)

        if (memoryBudget.pressure.value == MemoryPressure.EMERGENCY) {
            logRepository.log(LogEntry(
                timestampMs = System.currentTimeMillis(),
                category = LogCategory.MEMORY,
                event = LogEvent.PRESSURE_CHANGE.key,
                extraJson = "{\"trimLevel\":$level,\"engineUnload\":\"ordered_emergency\"}",
            ))
            serviceScope.launch { applyMemoryPressure(MemoryPressure.EMERGENCY) }
        }
    }

    private fun scheduleLogCleanup() {
        serviceScope.launch(Dispatchers.IO) {
            // Immediate pass on startup to catch up after long offline period.
            try {
                logRepository.cleanup(retentionDays = 7)
            } catch (t: Throwable) {
                MindlayerLog.w(TAG, "Log cleanup (startup) failed", throwable = t)
            }
            while (isActive) {
                delay(LOG_CLEANUP_INTERVAL_MS)
                try {
                    logRepository.cleanup(retentionDays = 7)
                } catch (t: Throwable) {
                    MindlayerLog.w(TAG, "Log cleanup failed", throwable = t)
                }
            }
        }
    }

    internal suspend fun applyMemoryPressure(level: MemoryPressure) {
        if (level != MemoryPressure.EMERGENCY) {
            applyPressureMutex.withLock {
                applyMemoryPressureLocked(level)
            }
            return
        }
        if (!applyPressureMutex.tryLock()) {
            MindlayerLog.i(TAG, "Skipping duplicate EMERGENCY memory-pressure pass")
            return
        }
        try {
            applyMemoryPressureLocked(level)
        } finally {
            applyPressureMutex.unlock()
        }
    }

    private suspend fun applyMemoryPressureLocked(level: MemoryPressure) {
        if (level < MemoryPressure.CRITICAL) {
            sessionManager.applyMemoryPressure(level)
            return
        }

        if (level == MemoryPressure.EMERGENCY) {
            if (::ocrSessionManager.isInitialized) {
                val drained = withTimeoutOrNull(EMERGENCY_DRAIN_TIMEOUT_MS) {
                    ocrSessionManager.drainForMemoryPressure()
                    true
                } == true
                if (!drained) {
                    MindlayerLog.w(
                        TAG,
                        "OCR drain timed out after ${EMERGENCY_DRAIN_TIMEOUT_MS}ms; cancelling in-flight OCR",
                    )
                    ocrSessionManager.cancelAllForMemoryPressure()
                }
            }
            unloadPaddleOcrForMemoryPressure()
            unloadEmbeddingForMemoryPressure()
            // R-15: cancel in-flight streams FIRST, then await them, so the
            // restart actually frees the native heap at the moment of
            // greatest OOM danger. Pre-fix the stream-cancelling
            // applyMemoryPressure ran AFTER awaitAllJobs and BEFORE the
            // hasActiveStreaming() re-check, so the asynchronous cancellation
            // had not unwound when the predicate was evaluated — it still saw
            // live streams and SKIPPED the heap-freeing restart exactly when
            // it was needed most.
            sessionManager.applyMemoryPressure(level)
            // Now await the cancelled jobs so every client receives its
            // terminal "cancelled" frame (written under NonCancellable) and
            // native generation has actually stopped before we tear the
            // process down.
            orchestrator.awaitAllJobs(timeoutMs = EMERGENCY_DRAIN_TIMEOUT_MS)
            // Process-restart instead of in-process engine shutdown.
            // Same LiteRT-LM #2028 reason as the thermal-switch path:
            // shutdownIfIdle would null the Engine and the next
            // initialize() (kicked off by the next client bind) would
            // SIGSEGV in liblitertlm_jni.so. shutdownAndRestart()
            // persists a "default-chain" restart intent and kills our
            // process, freeing the entire native heap rather than just
            // the LiteRT engine allocations.
            //
            // Under EMERGENCY (near-OOM) we restart unconditionally — even
            // if a stream stubbornly refused to finish cancelling within the
            // deadline above. We already issued cancelProcess via
            // applyMemoryPressure, clients have their terminal frames, and
            // the alternative is a harsher OS low-memory kill with no
            // persisted restart intent and no clean re-init.
            sessionManager.invalidateIdleSessionsForBackendSwitch()
            engineManager.shutdownAndRestart(
                reason = "memory_pressure_emergency",
                targetBackend = null,
            )
            // Unreachable; process is gone.
            return
        }

        unloadEmbeddingForMemoryPressure()
        unloadPaddleOcrForMemoryPressure()
        sessionManager.applyMemoryPressure(level)
    }

    private fun scheduleOcrSessionSweep(intervalMs: Long = OCR_SESSION_SWEEP_INTERVAL_MS) {
        serviceScope.launch {
            while (isActive) {
                delay(intervalMs)
                if (::ocrSessionManager.isInitialized) {
                    ocrSessionManager.sweepIdleAndExpired()
                }
            }
        }
    }

    private suspend fun unloadEmbeddingForMemoryPressure() {
        if (::embeddingEngine.isInitialized) {
            embeddingEngine.unloadForMemoryPressure()
        }
    }

    private suspend fun unloadPaddleOcrForMemoryPressure() {
        if (::paddleOcrEngine.isInitialized) {
            try {
                paddleOcrEngine.unloadForMemoryPressure()
            } catch (t: Throwable) {
                MindlayerLog.w(
                    TAG,
                    "PaddleOcrEngine.unloadForMemoryPressure raised: ${t.safeLabel()}",
                    throwable = null,
                )
            }
        }
    }

    /**
     * Observe [MemoryBudget.pressure] and forward changes to [SessionManager].
     * Runs in [serviceScope] so it's cancelled on destroy.
     */
    private fun observeMemoryPressure() {
        serviceScope.launch {
            memoryBudget.pressure
                .collect { pressure ->
                    MindlayerLog.i(TAG, "Memory pressure changed: $pressure")
                    applyMemoryPressure(pressure)
                }
        }
    }

    /**
     * Observe [ThermalMonitor.currentPolicy] and schedule backend switches
     * when the recommended backend differs from the active one.
     *
     * Switches are deferred until [activeInferenceCount] reaches 0 to avoid
     * tearing down a [Conversation] mid-inference.  The pending target is
     * stored in [pendingBackend] and applied either here (immediate idle) or
     * in [exitForeground] (after last inference completes).
     */
    private fun observeThermalPolicy() {
        serviceScope.launch {
            thermalMonitor.currentPolicy.collect { policy ->
                if (!engineManager.isInitialized || engineManager.currentBackend == "NONE") return@collect

                val current = engineManager.currentBackend
                val recommended = policy.recommendedBackend

                if (recommended == current) {
                    synchronized(stateLock) {
                        pendingBackend = null
                        pendingBackendSince = 0L
                    }
                    return@collect
                }

                MindlayerLog.i(TAG, "Thermal recommends $recommended, currently on $current")

                val applyNow: Boolean
                val forceOverdue: Boolean
                synchronized(stateLock) {
                    val firstDefer = pendingBackend != recommended || pendingBackendSince == 0L
                    pendingBackend = recommended
                    if (activeInferenceCount == 0) {
                        applyNow = true
                        forceOverdue = false
                    } else {
                        // R-3: track when this deferral began and force the
                        // switch once it has been deferred past the deadline
                        // under a CRITICAL band, instead of starving it under
                        // sustained load.
                        if (firstDefer) pendingBackendSince = android.os.SystemClock.elapsedRealtime()
                        applyNow = false
                        forceOverdue = shouldForceOverdueThermalSwitch(
                            band = policy.band,
                            pendingSince = pendingBackendSince,
                            now = android.os.SystemClock.elapsedRealtime(),
                        )
                    }
                }
                logRepository.logBackendSwitch(current, recommended, if (applyNow) "started" else "queued")

                if (applyNow) {
                    applyPendingBackendSwitch()
                } else if (forceOverdue) {
                    MindlayerLog.w(
                        TAG,
                        "Thermal switch to $recommended overdue (>${THERMAL_FORCE_SWITCH_DEADLINE_MS}ms) " +
                            "under CRITICAL band — forcing, cancelling in-flight inference",
                    )
                    applyPendingBackendSwitch(force = true)
                }
                // Otherwise, applied in exitForeground() when inference finishes.
            }
        }
    }

    /**
     * R-3: decide whether an overdue, deferred thermal downshift must be
     * forced. Forced only when the device is in the CRITICAL band (genuinely
     * too hot) AND the switch has been deferred past [deadlineMs]. Extracted
     * as a pure function so the starvation-guard policy is unit-testable
     * without the live thermal flow.
     */
    @androidx.annotation.VisibleForTesting
    internal fun shouldForceOverdueThermalSwitch(
        band: ThermalBand,
        pendingSince: Long,
        now: Long,
        deadlineMs: Long = THERMAL_FORCE_SWITCH_DEADLINE_MS,
    ): Boolean = band == ThermalBand.CRITICAL &&
        pendingSince != 0L &&
        now - pendingSince >= deadlineMs

    /** R-6 test seam: drive the quiescing flag without standing up the restart. */
    @androidx.annotation.VisibleForTesting
    internal fun setQuiescingForRestartForTest(value: Boolean) {
        quiescingForRestart = value
    }

    /**
     * Switch [EngineManager] to [pendingBackend] if the service is idle.
     *
     * All sessions are destroyed first because their [Conversation] references
     * become invalid after engine teardown.  GPU re-enable is gated by
     * [ThermalMonitor.canReenableGpu] (30 s cooldown).
     *
     * The decision and the clearing of [pendingBackend] happen atomically
     * under [stateLock] so a binder thread racing to start inference cannot
     * observe an inconsistent state between the idleness check and the
     * destructive coroutine launch.
     */
    private fun applyPendingBackendSwitch(force: Boolean = false) {
        val target: String
        synchronized(stateLock) {
            // A context-growth restart is already draining. Keep the thermal
            // target queued; the fresh process will re-evaluate policy.
            if (quiescingForRestart) return
            target = pendingBackend ?: return
            // Re-check idleness under the same lock that enterForeground uses.
            // If an inference just started, exitForeground() will retry — UNLESS
            // we're forcing an overdue thermal downshift (R-3), in which case we
            // proceed and cancel the in-flight inference below.
            if (!force && activeInferenceCount > 0) return
            val current = engineManager.currentBackend
            if (target == current) {
                pendingBackend = null
                pendingBackendSince = 0L
                return
            }
            if (target == "GPU" && !thermalMonitor.canReenableGpu()) {
                MindlayerLog.i(TAG, "GPU re-enable cooldown not elapsed, deferring")
                return
            }
            pendingBackend = null
            pendingBackendSince = 0L
            // R-6: quiesce — from here new inferences are rejected in
            // enterForeground so none can race the restart between this lock
            // release and the awaitAllJobs/shutdownAndRestart below. Both this
            // and enterForeground take stateLock, so the check-then-set is
            // atomic against a concurrent start.
            quiescingForRestart = true
        }

        val fromBackend = engineManager.currentBackend
        MindlayerLog.i(TAG, "Applying backend switch: ${engineManager.currentBackend} → $target (force=$force)")
        logRepository.logBackendSwitch(fromBackend, target, "started")

        serviceScope.launch {
            try {
                if (force) {
                    // R-3: pre-empt the in-flight inferences we're overriding so
                    // the thermal downshift isn't starved. cancelAll() emits a
                    // terminal frame + cancelProcess() per request, so clients
                    // get a clean signal and can retry on the cooler backend.
                    orchestrator.cancelAll()
                    orchestrator.awaitAllJobs(timeoutMs = FORCED_SWITCH_DRAIN_TIMEOUT_MS)
                } else {
                    // Wait for any coroutine that slipped in before we took the
                    // lock to finish — avoids killing the process mid-inference.
                    orchestrator.awaitAllJobs()
                }
                // Lazy-invalidate idle sessions; they re-warm on next access
                // after the post-restart engine init.
                sessionManager.invalidateIdleSessionsForBackendSwitch()
                // Process-restart instead of in-process switchBackend: the
                // latter triggers LiteRT-LM #2028 SIGSEGV on the second
                // recreate of the LiteRT engine inside the `:ml` process.
                // shutdownAndRestart() persists the target backend to
                // EngineRestartStore, calls Process.killProcess(myPid()),
                // and Android auto-restarts the service on the next bind.
                // The new process consumes the intent in startEngineWarmup
                // and inits against `target`.
                //
                // NB: this call DOES NOT RETURN — it ends the process. The
                // "complete" log line below is intentionally not reached;
                // the post-restart engine init logs its own completion via
                // the existing initialize() instrumentation.
                engineManager.shutdownAndRestart(
                    reason = if (force) "thermal_switch_forced" else "thermal_switch",
                    targetBackend = target,
                )
                // Reaching here means the process killer was a no-op (unit
                // tests / a future alternative killer). Clear quiescing so the
                // service doesn't permanently reject inference after a restart
                // that didn't actually kill the process.
                synchronized(stateLock) { quiescingForRestart = false }
            } catch (t: Throwable) {
                // The restart didn't happen — clear quiescing so inference can
                // resume rather than being permanently rejected.
                synchronized(stateLock) { quiescingForRestart = false }
                logRepository.logBackendSwitch(fromBackend, target, "failed")
                MindlayerLog.e(TAG, "Backend switch failed: ${t.safeLabel()}")
            }
        }
    }

    /**
     * Recreate the LLM engine in a fresh `:ml` process with a larger KV cache.
     *
     * LiteRT-LM #2028 makes in-process Engine close/recreate unsafe, so this
     * follows the same persisted process-restart path as thermal switching.
     * Requests coalesce to the largest context while current inference drains.
     * New inference is rejected during the drain and retried by the SDK.
     */
    internal fun requestEngineContextResize(maxTokens: Int) {
        val shouldLaunch = synchronized(stateLock) {
            if (quiescingForRestart && !contextResizeInFlight) {
                // A thermal or memory restart is already committed. The SDK's
                // post-restart createSession retry will reassess capacity.
                return
            }
            pendingContextResizeTokens = maxOf(pendingContextResizeTokens ?: 0, maxTokens)
            if (contextResizeInFlight) {
                false
            } else {
                contextResizeInFlight = true
                quiescingForRestart = true
                true
            }
        }
        if (!shouldLaunch) return

        val targetBackend = engineManager.currentBackend.takeUnless { it == "NONE" }
        MindlayerLog.i(
            TAG,
            "Scheduling engine context resize to at least $maxTokens tokens " +
                "(backend=${targetBackend ?: "<default>"})",
        )
        serviceScope.launch {
            try {
                val drained = orchestrator.awaitAllJobs(CONTEXT_RESIZE_DRAIN_TIMEOUT_MS)
                if (!drained) {
                    MindlayerLog.w(
                        TAG,
                        "Context resize deferred: inference did not drain within " +
                            "${CONTEXT_RESIZE_DRAIN_TIMEOUT_MS}ms",
                    )
                    clearContextResizeState()
                    return@launch
                }

                val tier = memoryBudget.deviceTier
                val snapshot = memoryBudget.currentSnapshot()
                val requestedTokens = synchronized(stateLock) {
                    val requested = pendingContextResizeTokens ?: maxTokens
                    // Commit the coalesced target. A request racing after this
                    // point sees quiescing=true/contextResizeInFlight=false and
                    // retries after the imminent process restart instead of
                    // being silently merged too late for the persisted intent.
                    pendingContextResizeTokens = null
                    contextResizeInFlight = false
                    requested
                }
                val targetTokens = PrewarmContextPolicy.effectiveRequestedContextTokens(
                    requestedTokens = requestedTokens,
                    tier = tier,
                    snapshot = snapshot,
                )
                val loadedTokens = engineManager.maxTokens
                if (loadedTokens != null && targetTokens <= loadedTokens) {
                    MindlayerLog.i(
                        TAG,
                        "Context resize no longer needed after memory-policy refresh " +
                            "(loaded=$loadedTokens, effective=$targetTokens)",
                    )
                    clearContextResizeState()
                    return@launch
                }
                sessionManager.invalidateIdleSessionsForBackendSwitch()
                engineManager.shutdownAndRestart(
                    reason = "context_resize",
                    targetBackend = targetBackend,
                    maxTokens = targetTokens,
                )

                // Production never reaches this because shutdownAndRestart
                // kills the process. Keep tests/no-op process killers usable.
                clearContextResizeState()
            } catch (t: Throwable) {
                clearContextResizeState()
                MindlayerLog.e(TAG, "Engine context resize failed: ${t.safeLabel()}")
            }
        }
    }

    private fun clearContextResizeState() {
        synchronized(stateLock) {
            pendingContextResizeTokens = null
            contextResizeInFlight = false
            quiescingForRestart = false
        }
    }

    // --- Visibility-aware idle release -------------------------------------

    /** Called by [ServiceBinder] after a registered client's visibility changes. */
    internal fun onClientVisibilityChanged() {
        val hasVisibleClients = ::binder.isInitialized && binder.hasVisibleClients()
        synchronized(stateLock) {
            if (hasVisibleClients) {
                idleDisconnectGranted = false
                auxiliaryIdleJob?.cancel()
                auxiliaryIdleJob = null
            }
        }
        if (hasVisibleClients) {
            rearmOcrAfterIdleReleaseIfNeeded()
        } else {
            reevaluateIdleRelease()
        }
    }

    /**
     * Atomically grant a hidden SDK client permission to drop its long-lived
     * application-context binding. Once granted, new native work is rejected
     * briefly so it cannot race the final unbind/process exit. A visible client
     * cancels the grant before starting new work.
     */
    internal fun tryGrantIdleDisconnect(): Boolean = synchronized(stateLock) {
        val noVisibleClients = ::binder.isInitialized && !binder.hasVisibleClients()
        if (
            !noVisibleClients ||
            activeInferenceCount > 0 ||
            idleProtectedWorkCount > 0 ||
            auxiliaryReleaseInProgress ||
            quiescingForRestart ||
            contextResizeInFlight
        ) {
            return@synchronized false
        }
        idleDisconnectGranted = true
        true
    }

    /** Keep async engine initialization out of the idle teardown window. */
    internal fun beginIdleProtectedWork(): Boolean = synchronized(stateLock) {
        if (idleDisconnectGranted || auxiliaryReleaseInProgress || quiescingForRestart) {
            return@synchronized false
        }
        idleProtectedWorkCount++
        auxiliaryIdleJob?.cancel()
        auxiliaryIdleJob = null
        true
    }

    internal fun endIdleProtectedWork() {
        synchronized(stateLock) {
            idleProtectedWorkCount = (idleProtectedWorkCount - 1).coerceAtLeast(0)
        }
        reevaluateIdleRelease()
    }

    private fun reevaluateIdleRelease() {
        var terminateProcess = false
        synchronized(stateLock) {
            val idle = isIdleReleaseEligibleLocked()
            if (!idle) {
                auxiliaryIdleJob?.cancel()
                auxiliaryIdleJob = null
                return
            }
            if (!clientsBound) {
                auxiliaryIdleJob?.cancel()
                auxiliaryIdleJob = null
                idleDisconnectGranted = true
                terminateProcess = true
            } else if (
                auxiliaryIdleJob?.isActive != true &&
                !auxiliaryReleaseInProgress &&
                !auxiliaryReleasedForIdle
            ) {
                auxiliaryIdleJob = serviceScope.launch {
                    delay(auxiliaryIdleReleaseDelayMs)
                    releaseAuxiliaryEnginesAfterIdle()
                }
            }
        }
        if (terminateProcess) terminateIdleProcess()
    }

    private fun isIdleReleaseEligibleLocked(): Boolean =
        activeInferenceCount == 0 &&
            idleProtectedWorkCount == 0 &&
            !quiescingForRestart &&
            !contextResizeInFlight &&
            (::binder.isInitialized && !binder.hasVisibleClients())

    private suspend fun releaseAuxiliaryEnginesAfterIdle() {
        val claimed = synchronized(stateLock) {
            auxiliaryIdleJob = null
            if (!isIdleReleaseEligibleLocked() || !clientsBound || auxiliaryReleaseInProgress) {
                false
            } else {
                auxiliaryReleaseInProgress = true
                true
            }
        }
        if (!claimed) {
            reevaluateIdleRelease()
            return
        }

        MindlayerLog.i(TAG, "Idle timeout reached; releasing embedding and OCR engines")
        var releasedSuccessfully = false
        try {
            if (::embeddingCoordinator.isInitialized) {
                check(embeddingCoordinator.awaitAllJobs(MODEL_RELEASE_DRAIN_TIMEOUT_MS)) {
                    "Embedding runtime did not drain during idle release"
                }
            }
            if (::ocrSessionManager.isInitialized) {
                ocrSessionManager.drainForMemoryPressure()
            }
            if (::embeddingEngine.isInitialized) {
                embeddingEngine.shutdown()
            }
            if (::paddleOcrEngine.isInitialized) {
                paddleOcrEngine.shutdown()
            }
            releasedSuccessfully = true
            MindlayerLog.i(TAG, "Idle auxiliary-engine release complete")
        } catch (t: Throwable) {
            MindlayerLog.w(TAG, "Idle auxiliary-engine release failed: ${t.safeLabel()}")
        } finally {
            val shouldRearm = synchronized(stateLock) {
                auxiliaryReleaseInProgress = false
                // A partial/failed shutdown is not a stable released state.
                // Leave it eligible for another delayed attempt while hidden.
                auxiliaryReleasedForIdle = releasedSuccessfully
                ::binder.isInitialized && binder.hasVisibleClients()
            }
            if (shouldRearm) rearmOcrAfterIdleReleaseIfNeeded()
            reevaluateIdleRelease()
        }
    }

    private fun rearmOcrAfterIdleReleaseIfNeeded() {
        val shouldRearm = synchronized(stateLock) {
            if (!auxiliaryReleasedForIdle || auxiliaryReleaseInProgress) {
                false
            } else {
                auxiliaryReleasedForIdle = false
                true
            }
        }
        if (!shouldRearm || !::paddleOcrEngine.isInitialized) return
        serviceScope.launch(Dispatchers.IO) {
            runCatching { paddleOcrEngine.initialize() }
                .onFailure {
                    MindlayerLog.i(
                        TAG,
                        "PaddleOCR rearm after idle release did not complete: ${it.safeLabel()}",
                    )
                }
        }
    }

    private fun terminateIdleProcess() {
        val shouldTerminate = synchronized(stateLock) {
            !clientsBound && isIdleReleaseEligibleLocked()
        }
        if (!shouldTerminate) return
        MindlayerLog.i(TAG, "Final client unbound while idle; terminating isolated ML process")
        if (::mlHealthRecorder.isInitialized) {
            runCatching { mlHealthRecorder.recordCleanShutdown() }
                .onFailure { MindlayerLog.w(TAG, "Unable to record clean idle shutdown: ${it.safeLabel()}") }
        }
        idleProcessKiller()
    }

    // --- Foreground state management ---

    /**
     * Promote to foreground service when active inference begins.
     * Called by the inference orchestrator.
     */
    fun enterForeground() {
        synchronized(stateLock) {
            // R-6: refuse to start a new inference once a backend-switch
            // restart has been decided. This and applyPendingBackendSwitch
            // both hold stateLock, so the decision can't race a start: a call
            // arriving here either ran BEFORE the decision (and was counted,
            // so the switch deferred to it) or AFTER (and is rejected here).
            // The thrown error is the retryable ENGINE_RESTARTING the SDK
            // backs off on; the process restarts within seconds.
            if (quiescingForRestart || idleDisconnectGranted || auxiliaryReleaseInProgress) {
                throw com.adsamcik.mindlayer.service.engine.EngineNotReadyException(
                    retryAfterMs = 2_000L,
                )
            }
            activeInferenceCount++
            auxiliaryReleasedForIdle = false
            auxiliaryIdleJob?.cancel()
            auxiliaryIdleJob = null
            if (activeInferenceCount == 1) {
                val notification = buildNotification("Processing inference request...")
                val fgsType = if (Build.VERSION.SDK_INT >= 34) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                }
                try {
                    ServiceCompat.startForeground(
                        this, NOTIFICATION_ID, notification, fgsType
                    )
                    logRepository.logFgsPromoted(activeInferenceCount)
                    MindlayerLog.i(TAG, "Entered foreground")
                } catch (e: Exception) {
                    // R-16: promotion failed — do NOT let the (possibly
                    // multi-minute) inference run as an unprotected background
                    // service where the OS can freeze/kill it mid-stream and
                    // silently drop the stream. Roll back the refcount and
                    // propagate so the orchestrator aborts with a typed error
                    // the client can retry (promotion self-heals on the next
                    // attempt once the app is foregroundable again).
                    activeInferenceCount = (activeInferenceCount - 1).coerceAtLeast(0)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        e is ForegroundServiceStartNotAllowedException
                    ) {
                        MindlayerLog.e(
                            TAG,
                            "Foreground service start not allowed; aborting inference: ${e.safeLabel()}",
                            throwable = null,
                        )
                    } else {
                        MindlayerLog.e(TAG, "Failed to enter foreground; aborting inference: ${e.safeLabel()}")
                    }
                    throw e
                }
            }
        }
        updateNotification()
    }

    /**
     * Drop foreground when no active inferences remain.
     */
    fun exitForeground() {
        var becameIdle = false
        synchronized(stateLock) {
            activeInferenceCount = (activeInferenceCount - 1).coerceAtLeast(0)
            if (activeInferenceCount == 0) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                logRepository.logFgsDemoted(activeInferenceCount)
                MindlayerLog.i(TAG, "Exited foreground")
                // Apply pending backend switch when idle
                applyPendingBackendSwitch()
                becameIdle = true
            }
        }
        updateNotification()
        if (becameIdle) reevaluateIdleRelease()
    }

    fun updateServiceState(newState: String) {
        serviceState = newState
        if (activeInferenceCount > 0) {
            updateNotification()
        }
    }

    // --- Notification ---

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Mindlayer Inference",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when Mindlayer is processing AI inference requests"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, com.adsamcik.mindlayer.service.ui.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MindlayerMlService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Mindlayer AI")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPendingIntent
            )
            .build()
    }

    private fun updateNotification() {
        if (activeInferenceCount <= 0) return

        val text = when (serviceState) {
            STATE_LOADING -> "Loading model..."
            STATE_READY -> "Ready ($activeInferenceCount active)"
            STATE_INFERRING -> "Inferring ($activeInferenceCount active)"
            else -> "Idle"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}


