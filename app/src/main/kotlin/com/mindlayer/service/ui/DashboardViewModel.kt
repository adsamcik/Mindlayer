package com.mindlayer.service.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mindlayer.RequestMeta
import com.mindlayer.SessionConfig
import com.mindlayer.service.logging.LogDao
import com.mindlayer.service.logging.LogDatabase
import com.mindlayer.service.logging.LogEntry
import com.mindlayer.shared.StreamEvent
import com.mindlayer.shared.StreamHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.util.UUID

/**
 * Connects to [com.mindlayer.service.MindlayerMlService] via AIDL (cross-process :ml)
 * and reads the Room log database directly (file-based, accessible from main process).
 */
class DashboardViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var logDao: LogDao? = null
    private var service: com.mindlayer.IMindlayerService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = com.mindlayer.IMindlayerService.Stub.asInterface(binder)
            startPolling()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    fun bindService(context: Context) {
        if (bound) return
        logDao = LogDatabase.getInstance(context).logDao()

        val intent = Intent().apply {
            component = ComponentName(
                context.packageName,
                "com.mindlayer.service.MindlayerMlService"
            )
        }
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        bound = true
        startLogPolling()
    }

    fun unbindService(context: Context) {
        if (!bound) return
        context.unbindService(connection)
        bound = false
        service = null
    }

    private fun startPolling() {
        viewModelScope.launch {
            while (isActive && bound) {
                try {
                    val svc = service ?: continue
                    val status = svc.status
                    val engineInfo = svc.engineInfo
                    val sessions = svc.listSessions()

                    _uiState.update { current ->
                        current.copy(
                            isEngineLoaded = status.isEngineLoaded,
                            backend = status.backend,
                            uptimeMs = status.uptimeMs,
                            thermalBand = status.thermalBand,
                            recommendedBackend = status.recommendedBackend,
                            burstSeconds = status.burstSeconds,
                            restSeconds = status.restSeconds,
                            chunkTokens = status.chunkTokens,
                            headroom = status.headroom,
                            memoryPressure = status.memoryPressure,
                            availableRamMb = status.availableRamMb,
                            totalRamMb = status.totalRamMb,
                            maxSessions = status.maxSessions,
                            activeSessions = sessions?.map { s ->
                                SessionUiItem(
                                    sessionId = s.sessionId.take(8) + "…",
                                    backend = s.backend,
                                    tokenCount = s.currentTokenCount,
                                    maxTokens = s.maxTokens,
                                    isStreaming = s.isStreaming,
                                    lastAccessedLabel = formatRelativeTime(s.lastAccessedAtMs),
                                )
                            } ?: emptyList(),
                            initTimeSeconds = engineInfo?.initTimeSeconds ?: 0f,
                            modelPath = engineInfo?.modelPath ?: "",
                        )
                    }
                } catch (_: Exception) {
                }
                delay(2000)
            }
        }
    }

    private fun startLogPolling() {
        viewModelScope.launch {
            while (isActive) {
                try {
                    val dao = logDao ?: continue
                    val recent = dao.getRecent(20)
                    _uiState.update { current ->
                        current.copy(
                            recentLogs = recent.map { entry ->
                                LogUiItem(
                                    timestampLabel = formatRelativeTime(entry.timestampMs),
                                    category = entry.category,
                                    event = entry.event,
                                    detail = buildLogDetail(entry),
                                )
                            }
                        )
                    }
                } catch (_: Exception) {
                }
                delay(3000)
            }
        }
    }

    private fun buildLogDetail(entry: LogEntry): String = buildString {
        entry.sessionId?.let { append("session=${it.take(8)}… ") }
        entry.backend?.let { append("[$it] ") }
        entry.durationMs?.let { append("${it}ms ") }
        entry.tokensGenerated?.let { append("${it}tok ") }
        entry.tokensPerSec?.let { append("%.1ftps ".format(it)) }
        entry.thermalBand?.let { append("band=$it ") }
        entry.errorMessage?.let { append("err=$it") }
    }.trim()

    private fun formatRelativeTime(timestampMs: Long): String {
        val diff = System.currentTimeMillis() - timestampMs
        return when {
            diff < 1_000 -> "now"
            diff < 60_000 -> "${diff / 1_000}s ago"
            diff < 3_600_000 -> "${diff / 60_000}m ago"
            diff < 86_400_000 -> "${diff / 3_600_000}h ago"
            else -> "${diff / 86_400_000}d ago"
        }
    }

    // ---- Test inference --------------------------------------------------------

    private val testJson = Json { ignoreUnknownKeys = true }

    /**
     * Runs a full end-to-end inference test:
     * 1. Creates a session via AIDL
     * 2. Sends a text prompt via AIDL infer() with a pipe
     * 3. Reads streaming events from the pipe
     * 4. Reports results (or errors) in the UI
     */
    fun runTestInference(prompt: String = "Hello! What are you?") {
        if (_uiState.value.isTestRunning) return

        _uiState.update { it.copy(isTestRunning = true, testStatus = "Starting…", testOutput = "") }

        viewModelScope.launch {
            try {
                val svc = service ?: run {
                    _uiState.update { it.copy(isTestRunning = false, testStatus = "❌ Not connected to service") }
                    return@launch
                }

                // 1. Create session
                _uiState.update { it.copy(testStatus = "Creating session…") }
                val sessionId = svc.createSession(SessionConfig(
                    systemPrompt = "You are a helpful assistant. Be concise.",
                    maxTokens = 2048,
                    backend = "CPU",
                ))
                _uiState.update { it.copy(testStatus = "Session $sessionId created. Sending prompt…") }

                // 2. Create pipe
                val pipe = ParcelFileDescriptor.createReliablePipe()
                val readEnd = pipe[0]
                val writeEnd = pipe[1]

                val requestId = UUID.randomUUID().toString()
                val meta = RequestMeta(
                    requestId = requestId,
                    sessionId = sessionId,
                    textContent = prompt,
                )

                // 3. Start inference (async — service writes to pipe)
                svc.infer(meta, null, null, writeEnd)
                writeEnd.close() // close our copy of write end

                // 4. Read events from pipe
                _uiState.update { it.copy(testStatus = "Streaming response…") }
                val output = StringBuilder()
                var eventCount = 0
                var finishReason = ""

                withContext(Dispatchers.IO) {
                    val input = DataInputStream(BufferedInputStream(
                        ParcelFileDescriptor.AutoCloseInputStream(readEnd)
                    ))
                    try {
                        while (true) {
                            val len = try {
                                Integer.reverseBytes(input.readInt())
                            } catch (_: EOFException) { break }
                            if (len < 0 || len > 1_048_576) break

                            val bytes = ByteArray(len)
                            input.readFully(bytes)
                            val jsonStr = bytes.decodeToString()
                            eventCount++

                            // Try parsing as StreamEvent
                            try {
                                val event = testJson.decodeFromString<StreamEvent>(jsonStr)
                                when (event.type) {
                                    "token_delta" -> {
                                        val text = event.payload["text"]?.jsonPrimitive?.contentOrNull ?: ""
                                        output.append(text)
                                        _uiState.update { it.copy(testOutput = output.toString()) }
                                    }
                                    "error" -> {
                                        val msg = event.payload["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown"
                                        val code = event.payload["code"]?.jsonPrimitive?.contentOrNull ?: ""
                                        output.append("\n❌ ERROR [$code]: $msg")
                                        _uiState.update { it.copy(testOutput = output.toString()) }
                                    }
                                    "done" -> {
                                        finishReason = event.payload["finish_reason"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                                    }
                                }
                            } catch (_: Exception) {
                                // Try as header
                                try {
                                    testJson.decodeFromString<StreamHeader>(jsonStr)
                                } catch (_: Exception) { }
                            }
                        }
                    } catch (_: Exception) { }
                }

                // 5. Report results
                val status = if (output.contains("❌ ERROR")) {
                    "❌ Error after $eventCount events"
                } else if (output.isNotEmpty()) {
                    "✅ Complete! $eventCount events, finish=$finishReason"
                } else {
                    "⚠️ No output received ($eventCount events)"
                }

                _uiState.update { it.copy(isTestRunning = false, testStatus = status) }

                // Cleanup
                try { svc.destroySession(sessionId) } catch (_: Exception) { }

            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isTestRunning = false,
                    testStatus = "❌ ${e.javaClass.simpleName}: ${e.message}",
                    testOutput = e.stackTraceToString().take(500),
                ) }
            }
        }
    }
}
