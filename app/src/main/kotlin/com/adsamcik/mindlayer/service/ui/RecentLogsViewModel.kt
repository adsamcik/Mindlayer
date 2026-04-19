package com.adsamcik.mindlayer.service.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.mindlayer.service.logging.LogDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RecentLogsViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = LogDatabase.getInstance(application).logDao()
    private val _uiState = MutableStateFlow(RecentLogsUiState())
    val uiState: StateFlow<RecentLogsUiState> = _uiState.asStateFlow()

    fun loadLogs() {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val entries = dao.getRecent(200)
                val items = entries.map { entry ->
                    LogUiItem(
                        timestampLabel = formatRelativeTimestamp(entry.timestampMs),
                        category = entry.category,
                        event = entry.event.replace('_', ' ')
                            .replaceFirstChar { it.uppercase() },
                        detail = buildLogDetail(entry),
                    )
                }
                _uiState.update { it.copy(logs = items, isLoading = false) }
            } catch (_: Exception) {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    private fun buildLogDetail(entry: com.adsamcik.mindlayer.service.logging.LogEntry): String {
        val parts = mutableListOf<String>()
        entry.sessionId?.let { parts += "session=${it.take(8)}" }
        entry.durationMs?.let { parts += "${it}ms" }
        entry.tokensGenerated?.let { parts += "$it tokens" }
        entry.tokensPerSec?.let { parts += "%.1f tok/s".format(it) }
        entry.thermalBand?.let { parts += "band=$it" }
        entry.backend?.let { parts += it }
        entry.errorMessage?.let { parts += it }
        entry.memoryAvailableMb?.let { parts += "${it}MB free" }
        if (parts.isEmpty() && !entry.extraJson.isNullOrBlank()) {
            return entry.extraJson.take(200)
        }
        return parts.joinToString(" • ")
    }
}
