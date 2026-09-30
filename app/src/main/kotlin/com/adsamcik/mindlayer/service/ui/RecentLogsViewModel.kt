package com.adsamcik.mindlayer.service.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.mindlayer.service.logging.LogDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RecentLogsViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = LogDatabase.getInstance(application).logDao()
    private val _uiState = MutableStateFlow(RecentLogsUiState())
    val uiState: StateFlow<RecentLogsUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null

    fun loadLogs() {
        loadJob?.cancel()
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        loadJob = viewModelScope.launch(Dispatchers.IO) {
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
                val evidence = entries.map { entry ->
                    LogEvidence(entry.timestampMs, entry.requestId, entry.sessionId)
                }
                _uiState.update { it.copy(logs = items, evidence = evidence, isLoading = false, errorMessage = null) }
            } catch (exception: Exception) {
                // A superseded refresh stays cancelled. Room can also report a closed
                // database as cancellation while our job remains active: show that failure.
                currentCoroutineContext().ensureActive()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = buildLoadFailureMessage(exception),
                    )
                }
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
        val safeExtra = formatSafeExtraJsonForUi(entry.event, entry.extraJson)
        if (safeExtra != null) parts += safeExtra
        return parts.joinToString(" • ")
    }

    private fun buildLoadFailureMessage(exception: Exception): String {
        val detail = exception.localizedMessage
            ?.takeIf { it.isNotBlank() }
            ?.let { " $it" }
            ?: ""
        return "The diagnostics log database couldn't be queried.$detail"
    }
}
