package com.mindlayer.service.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mindlayer.service.logging.LogDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SessionHistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = LogDatabase.getInstance(application).logDao()
    private val _uiState = MutableStateFlow(SessionHistoryUiState())
    val uiState: StateFlow<SessionHistoryUiState> = _uiState.asStateFlow()

    init {
        loadSessions()
    }

    fun loadSessions() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val rows = dao.listSessionSummaries(limit = 100)
                val items = rows.map { row ->
                    SessionHistoryItem(
                        sessionId = row.sessionId,
                        displayId = if (row.sessionId.length > 12) row.sessionId.take(12) + "…" else row.sessionId,
                        backend = row.backend,
                        createdLabel = formatDateVm(row.firstEventMs),
                        lastActiveLabel = formatRelativeTimeVm(row.firstEventMs, row.lastEventMs),
                        inferenceCount = row.inferenceCount,
                        totalTokens = row.totalTokens,
                    )
                }
                _uiState.update { it.copy(sessions = items, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    private fun formatDateVm(ms: Long): String {
        val sdf = java.text.SimpleDateFormat("MMM d, yyyy HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(ms))
    }

    private fun formatRelativeTimeVm(firstMs: Long, lastMs: Long): String {
        val diff = System.currentTimeMillis() - lastMs
        return when {
            diff < 60_000 -> "just now"
            diff < 3_600_000 -> "${diff / 60_000}m ago"
            diff < 86_400_000 -> "${diff / 3_600_000}h ago"
            diff < 604_800_000 -> "${diff / 86_400_000}d ago"
            else -> formatDateVm(lastMs)
        }
    }
}
