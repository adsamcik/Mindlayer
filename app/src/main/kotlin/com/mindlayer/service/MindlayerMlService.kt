package com.mindlayer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.mindlayer.service.engine.EngineManager
import com.mindlayer.service.engine.InferenceOrchestrator
import com.mindlayer.service.engine.MemoryBudget
import com.mindlayer.service.engine.MemoryPressure
import com.mindlayer.service.engine.SessionManager
import com.mindlayer.service.engine.ThermalMonitor
import com.mindlayer.service.logging.DiagnosticExporter
import com.mindlayer.service.logging.LogCategory
import com.mindlayer.service.logging.LogDatabase
import com.mindlayer.service.logging.LogEntry
import com.mindlayer.service.logging.LogEvent
import com.mindlayer.service.logging.LogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.mindlayer.service.ipc.SharedMemoryPool

class MindlayerMlService : Service() {

    companion object {
        private const val TAG = "MindlayerMlService"
        private const val NOTIFICATION_CHANNEL_ID = "mindlayer_inference"
        private const val NOTIFICATION_ID = 1

        const val STATE_IDLE = "idle"
        const val STATE_LOADING = "loading"
        const val STATE_READY = "ready"
        const val STATE_INFERRING = "inferring"
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
    private lateinit var sharedMemoryPool: SharedMemoryPool
    private lateinit var binder: ServiceBinder
    private lateinit var logRepository: LogRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    var serviceState: String = STATE_IDLE
        private set

    var activeInferenceCount = 0
        private set
    private val stateLock = Any()
    var createdAtMs: Long = 0L
        private set

    override fun onCreate() {
        super.onCreate()
        createdAtMs = android.os.SystemClock.elapsedRealtime()
        Log.i(TAG, "Service created in process ${android.os.Process.myPid()}")
        createNotificationChannel()

        val logDb = LogDatabase.getInstance(this)
        logRepository = LogRepository(logDb.logDao())

        engineManager = EngineManager(this, logRepository)
        memoryBudget = MemoryBudget(this, serviceScope, logRepository)
        thermalMonitor = ThermalMonitor(this, serviceScope, logRepository)
        sessionManager = SessionManager(this, engineManager, memoryBudget, logRepository)
        sharedMemoryPool = SharedMemoryPool(cacheDir)
        orchestrator = InferenceOrchestrator(this, sessionManager, sharedMemoryPool, logRepository)

        val diagnosticExporter = DiagnosticExporter(
            engineManager, thermalMonitor, memoryBudget, sessionManager, logDb.logDao()
        )
        binder = ServiceBinder(this, engineManager, orchestrator, diagnosticExporter, thermalMonitor, memoryBudget)

        logRepository.log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.ENGINE,
            event = LogEvent.ENGINE_INIT,
            extraJson = """{"lifecycle":"onCreate"}""",
        ))

        memoryBudget.start()
        thermalMonitor.start()
        observeMemoryPressure()
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "Client bound: ${intent?.`package`}")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "Client unbound: ${intent?.`package`}")
        return true
    }

    override fun onRebind(intent: Intent?) {
        Log.i(TAG, "Client rebound: ${intent?.`package`}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand, startId=$startId")
        // START_NOT_STICKY: don't auto-restart; recovery is client-driven
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")
        thermalMonitor.stop()
        memoryBudget.stop()
        orchestrator.shutdown()
        logRepository.shutdown()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.w(TAG, "onTrimMemory level=$level")

        logRepository.log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.MEMORY,
            event = LogEvent.PRESSURE_CHANGE,
            extraJson = """{"trimLevel":$level}""",
        ))

        // Forward to MemoryBudget for immediate re-evaluation and escalation
        memoryBudget.onTrimMemory(level)
    }

    /**
     * Observe [MemoryBudget.pressure] and forward changes to [SessionManager].
     * Runs in [serviceScope] so it's cancelled on destroy.
     */
    private fun observeMemoryPressure() {
        serviceScope.launch {
            memoryBudget.pressure
                .collect { pressure ->
                    Log.i(TAG, "Memory pressure changed: $pressure")
                    sessionManager.applyMemoryPressure(pressure)
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
                    Log.i(TAG, "Entered foreground")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to enter foreground", e)
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
                Log.i(TAG, "Exited foreground")
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
            Intent(this, com.mindlayer.service.ui.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MindlayerMlService::class.java).apply {
            action = "com.mindlayer.STOP"
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
