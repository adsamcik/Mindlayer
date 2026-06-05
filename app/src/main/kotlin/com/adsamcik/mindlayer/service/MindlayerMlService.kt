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
import com.adsamcik.mindlayer.service.engine.SessionManager
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import com.adsamcik.mindlayer.service.security.EvictionRegistry

class MindlayerMlService : Service() {

    companion object {
        private const val TAG = "MindlayerMlService"
        private const val NOTIFICATION_CHANNEL_ID = "mindlayer_inference"
        private const val NOTIFICATION_ID = 1
        private const val LOG_CLEANUP_INTERVAL_MS = 24L * 60 * 60 * 1000
        private const val OCR_SESSION_SWEEP_INTERVAL_MS = 60_000L
        private const val EMERGENCY_DRAIN_TIMEOUT_MS = 2_000L

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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val applyPressureMutex = Mutex()

    @Volatile
    var serviceState: String = STATE_IDLE
        private set

    @Volatile
    private var pendingBackend: String? = null

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
        sessionManager = SessionManager(this, engineManager, memoryBudget, logRepository)
        sharedMemoryPool = SharedMemoryPool(cacheDir)
        sharedMemoryPool.cleanupAll()
        deferredStore = DeferredStore(DeferredDatabase.getInstance(this).deferredDao())
        embeddingEngine = EmbeddingEngine(this, logRepository = logRepository)
        // M-D5: synchronously fail any STILL_RUNNING rows left over from a
        // prior process before `binder` is exposed. `serviceScope.launch`
        // returned before the SQL UPDATE ran, so an `onBind` arriving in
        // the first ~tens of ms could see stale `STILL_RUNNING` rows. The
        // call is a single bulk UPDATE on a freshly-opened SQLCipher DB
        // (one SQL statement, small table), safely runnable on the main
        // thread per the existing pattern.
        runBlocking { deferredStore.failRunningOnInit() }
        orchestrator = InferenceOrchestrator(this, sessionManager, sharedMemoryPool, logRepository)
        val callbackRegistry = EvictionRegistry()
        embeddingCoordinator = EmbeddingCoordinator(embeddingEngine, deferredStore, this, serviceScope, callbackRegistry, sharedMemoryPool)

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
        // EmbeddingGemma backend. See docs/LITERT_COEXISTENCE.md for the
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
        paddleOcrEngine = PaddleOcrEngine(this, logRepository = logRepository)
        val ocrLlmExtractor = com.adsamcik.mindlayer.service.engine
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
        // Eager async init — failure-tolerant. If the PaddleOCR bundle
        // isn't installed, the engine settles into `Failed(ModelMissing)`
        // and `isEngineReady()` stays false (FEATURE_OCR_SESSION stays
        // unadvertised). Lives on the IO dispatcher so it does NOT block
        // service startup.
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

        val diagnosticExporter = DiagnosticExporter(
            engineManager, thermalMonitor, memoryBudget, sessionManager, logDb.logDao()
        )
        binder = ServiceBinder(this, engineManager, orchestrator, diagnosticExporter, thermalMonitor, memoryBudget, allowlistStore = allowlistStore, consentChallengeStore = com.adsamcik.mindlayer.service.security.ConsentChallengeStore(this), consentAttemptStore = com.adsamcik.mindlayer.service.security.ConsentAttemptStore(this), logRepository = logRepository, mlHealthRecorder = mlHealthRecorder, deferredStore = deferredStore, embeddingCoordinator = embeddingCoordinator, callbackRegistry = callbackRegistry, ocrSessionManager = ocrSessionManager, sharedMemoryPool = sharedMemoryPool, paddleOcrEngine = paddleOcrEngine, ocrLlmExtractor = ocrLlmExtractor)

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
        MindlayerLog.i(TAG, "Client bound: ${intent?.`package`}")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        MindlayerLog.i(TAG, "Client unbound: ${intent?.`package`}")
        return true
    }

    override fun onRebind(intent: Intent?) {
        MindlayerLog.i(TAG, "Client rebound: ${intent?.`package`}")
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
                    pendingBackend = null
                    return@collect
                }

                MindlayerLog.i(TAG, "Thermal recommends $recommended, currently on $current")
                pendingBackend = recommended
                logRepository.logBackendSwitch(current, recommended, "queued")

                if (activeInferenceCount == 0) {
                    applyPendingBackendSwitch()
                }
                // Otherwise, will be applied in exitForeground()
            }
        }
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
    private fun applyPendingBackendSwitch() {
        val target: String
        synchronized(stateLock) {
            target = pendingBackend ?: return
            // Re-check idleness under the same lock that enterForeground uses.
            // If an inference just started, exitForeground() will retry.
            if (activeInferenceCount > 0) return
            val current = engineManager.currentBackend
            if (target == current) {
                pendingBackend = null
                return
            }
            if (target == "GPU" && !thermalMonitor.canReenableGpu()) {
                MindlayerLog.i(TAG, "GPU re-enable cooldown not elapsed, deferring")
                return
            }
            pendingBackend = null
        }

        val fromBackend = engineManager.currentBackend
        MindlayerLog.i(TAG, "Applying backend switch: ${engineManager.currentBackend} → $target")
        logRepository.logBackendSwitch(fromBackend, target, "started")

        serviceScope.launch {
            try {
                // Wait for any coroutine that slipped in before we took the
                // lock to finish — avoids killing the process mid-inference.
                orchestrator.awaitAllJobs()
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
                    reason = "thermal_switch",
                    targetBackend = target,
                )
            } catch (t: Throwable) {
                logRepository.logBackendSwitch(fromBackend, target, "failed")
                MindlayerLog.e(TAG, "Backend switch failed: ${t.safeLabel()}")
            }
        }
    }

    // --- Foreground state management ---

    /**
     * Promote to foreground service when active inference begins.
     * Called by the inference orchestrator.
     */
    fun enterForeground() {
        synchronized(stateLock) {
            activeInferenceCount++
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
        synchronized(stateLock) {
            activeInferenceCount = (activeInferenceCount - 1).coerceAtLeast(0)
            if (activeInferenceCount == 0) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                logRepository.logFgsDemoted(activeInferenceCount)
                MindlayerLog.i(TAG, "Exited foreground")
                // Apply pending backend switch when idle
                applyPendingBackendSwitch()
            }
        }
        updateNotification()
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






