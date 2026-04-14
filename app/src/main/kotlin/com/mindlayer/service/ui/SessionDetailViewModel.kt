package com.mindlayer.service.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mindlayer.service.logging.LogDatabase
import com.mindlayer.service.logging.LogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SessionDetailViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = LogDatabase.getInstance(application).logDao()
    private val _uiState = MutableStateFlow(SessionDetailUiState())
    val uiState: StateFlow<SessionDetailUiState> = _uiState.asStateFlow()

    fun loadSession(sessionId: String) {
        _uiState.update {
            it.copy(
                sessionId = sessionId,
                displayId = sessionId.take(12) + if (sessionId.length > 12) "…" else "",
                isLoading = true,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val entries = dao.getBySession(sessionId)
                if (entries.isEmpty()) {
                    _uiState.update { it.copy(isLoading = false) }
                    return@launch
                }

                val backend = entries.firstNotNullOfOrNull { it.backend }
                val first = entries.minOf { it.timestampMs }
                val last = entries.maxOf { it.timestampMs }
                val durationMs = last - first
                val completedInferences = entries.filter { it.event == "request_complete" }
                val totalTokens = completedInferences.sumOf { it.tokensGenerated ?: 0 }
                val avgTps = completedInferences.mapNotNull { it.tokensPerSec }.let {
                    if (it.isNotEmpty()) "%.1f".format(it.average()) else "—"
                }

                val eventItems = entries.sortedBy { it.timestampMs }.map { entry ->
                    SessionEventItem(
                        timestampLabel = formatTimestamp(entry.timestampMs),
                        category = entry.category,
                        event = entry.event
                            .replace('_', ' ')
                            .replaceFirstChar { it.uppercase() },
                        detail = buildDetail(entry),
                    )
                }

                _uiState.update {
                    it.copy(
                        backend = backend,
                        durationLabel = formatDuration(durationMs),
                        inferenceCount = completedInferences.size,
                        totalTokens = totalTokens,
                        avgTokensPerSec = avgTps,
                        events = eventItems,
                        isLoading = false,
                    )
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    private fun formatTimestamp(ms: Long): String {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(ms))
    }

    private fun formatDuration(ms: Long): String = when {
        ms < 1_000     -> "${ms}ms"
        ms < 60_000    -> "%.1fs".format(ms / 1000.0)
        ms < 3_600_000 -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
        else           -> "${ms / 3_600_000}h ${(ms % 3_600_000) / 60_000}m"
    }

    private fun buildDetail(entry: LogEntry): String {
        val parts = mutableListOf<String>()
        entry.durationMs?.let { parts += "${it}ms" }
        entry.tokensGenerated?.let { parts += "$it tokens" }
        entry.tokensPerSec?.let { parts += "%.1f tok/s".format(it) }
        entry.thermalBand?.let { parts += it }
        entry.backend?.let { parts += it }
        entry.errorMessage?.let { parts += it }
        entry.memoryAvailableMb?.let { parts += "${it}MB free" }
        return parts.joinToString(" • ")
    }
}
