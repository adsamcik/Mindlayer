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
import androidx.core.app.ServiceCompat
import com.adsamcik.mindlayer.service.engine.DeferredDatabase
import com.adsamcik.mindlayer.service.engine.DeferredStore
import com.adsamcik.mindlayer.service.engine.EngineManager
import com.adsamcik.mindlayer.service.engine.EmbeddingCoordinator
import com.adsamcik.mindlayer.service.engine.EmbeddingEngine
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
import com.adsamcik.mindlayer.service.ipc.SharedMemoryPool
import com.adsamcik.mindlayer.service.security.AllowlistEntry
import com.adsamcik.mindlayer.service.security.AllowlistStore
import com.adsamcik.mindlayer.service.security.CallerVerifier
import com.adsamcik.mindlayer.service.security.EvictionRegistry
import com.adsamcik.mindlayer.service.security.debugSeedIfApplicable

class MindlayerMlService : Service() {

    companion object {
        private const val TAG = "MindlayerMlService"
        private const val NOTIFICATION_CHANNEL_ID = "mindlayer_inference"
        private const val NOTIFICATION_ID = 1
        private const val LOG_CLEANUP_INTERVAL_MS = 24L * 60 * 60 * 1000

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

        /**
         * First-party clients that should be approved on a fresh install.
         *
         * Each entry pins (packageName, signingCertSha256). The hash format is
         * lowercase hex with no separators (matches `CallerVerifier.sha256Hex`).
         * For Play-distributed apps, use the Play app signing certificate
         * SHA-256, not the upload key (Play Console → App integrity → App
         * signing → "SHA-256 certificate fingerprint").
         *
         * `seedIfEmpty` verifies each entry against the currently installed APK
         * signature before insertion, so multiple entries per package (e.g.
         * Play hash + debug-keystore hash) are safe — only the matching one
         * is inserted on a given device.
         */
        internal val FIRST_PARTY_ALLOWLIST_SEEDS: List<AllowlistEntry> = listOf(
            AllowlistEntry(
                packageName = "com.adsamcik.starlitcoffee",
                signingCertSha256 = "5932936267cac21efd4bb7a25200bb9eaf58d890f566fadae4f0daa1a9bbae47",
                grantedAtMs = 0L,
                displayName = "Starlit Coffee",
            ),
            AllowlistEntry(
                packageName = "com.adsamcik.expenses",
                signingCertSha256 = "027fde453b4fc327e5c4d7ac7d3f54b1fc711503e5a155fd72f31ff3a9a2e9bc",
                grantedAtMs = 0L,
                displayName = "Ledgit",
            ),
        )
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
        mlHealthRecorder = MlHealthRecorder(this)
        try {
            mlHealthRecorder.recordHealthyBoot()
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
        val callerVerifier = CallerVerifier
        allowlistStore.seedIfEmpty(FIRST_PARTY_ALLOWLIST_SEEDS)
        debugSeedIfApplicable(this, allowlistStore, callerVerifier, logRepository)

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
        binder = ServiceBinder(this, engineManager, orchestrator, diagnosticExporter, thermalMonitor, memoryBudget, allowlistStore = allowlistStore, logRepository = logRepository, mlHealthRecorder = mlHealthRecorder, deferredStore = deferredStore, embeddingCoordinator = embeddingCoordinator, callbackRegistry = callbackRegistry, ocrSessionManager = ocrSessionManager, sharedMemoryPool = sharedMemoryPool)

        logRepository.log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.ENGINE,
            event = LogEvent.ENGINE_INIT,
            extraJson = """{"lifecycle":"onCreate"}""",
        ))

        memoryBudget.start()
        thermalMonitor.start()
        observeMemoryPressure()
        observeThermalPolicy()
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
            try {
                orchestrator.cancelAll()
            } catch (t: Throwable) {
                MindlayerLog.w(TAG, "orchestrator.cancelAll() raised: ${t.safeLabel()}")
            }
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
            event = LogEvent.PRESSURE_CHANGE,
            extraJson = """{"trimLevel":$level}""",
        ))

        // Forward to MemoryBudget for immediate re-evaluation and escalation
        memoryBudget.onTrimMemory(level)

        if (memoryBudget.pressure.value == MemoryPressure.EMERGENCY) {
            logRepository.log(LogEntry(
                timestampMs = System.currentTimeMillis(),
                category = LogCategory.MEMORY,
                event = LogEvent.PRESSURE_CHANGE,
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
        if (level < MemoryPressure.CRITICAL) {
            sessionManager.applyMemoryPressure(level)
            return
        }

        if (level == MemoryPressure.EMERGENCY) {
            if (::ocrSessionManager.isInitialized) {
                ocrSessionManager.drainForMemoryPressure()
            }
            unloadPaddleOcrForMemoryPressure()
            unloadEmbeddingForMemoryPressure()
            if (sessionManager.hasActiveStreaming()) {
                orchestrator.awaitAllJobs(timeoutMs = 5_000)
            }
            sessionManager.applyMemoryPressure(level)
            val unloaded = engineManager.shutdownIfIdle {
                !sessionManager.hasActiveStreaming()
            }
            if (unloaded) {
                sessionManager.invalidateIdleSessionsForBackendSwitch()
            }
            return
        }

        unloadEmbeddingForMemoryPressure()
        unloadPaddleOcrForMemoryPressure()
        sessionManager.applyMemoryPressure(level)
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
                // lock to finish — avoids closing a Conversation mid-inference.
                orchestrator.awaitAllJobs()
                // Lazy-invalidate idle sessions; they re-warm on next access after the backend changes.
                sessionManager.invalidateIdleSessionsForBackendSwitch()
                engineManager.switchBackend(target)
                logRepository.logBackendSwitch(fromBackend, engineManager.currentBackend, "complete")
                MindlayerLog.i(TAG, "Backend switch complete: now on ${engineManager.currentBackend}")
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
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        e is ForegroundServiceStartNotAllowedException
                    ) {
                        MindlayerLog.e(
                            TAG,
                            "Foreground service start not allowed: ${e.safeLabel()}",
                            throwable = null,
                        )
                    } else {
                        MindlayerLog.e(TAG, "Failed to enter foreground: ${e.safeLabel()}")
                    }
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






