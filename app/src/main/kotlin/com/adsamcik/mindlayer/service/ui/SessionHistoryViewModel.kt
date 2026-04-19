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
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val SessionHistoryLimit = 100

class SessionHistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = LogDatabase.getInstance(application).logDao()
    private val _uiState = MutableStateFlow(SessionHistoryUiState())
    val uiState: StateFlow<SessionHistoryUiState> = _uiState.asStateFlow()

    fun loadSessions() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val nowMs = System.currentTimeMillis()
                val items = dao.listSessionSummaries(limit = SessionHistoryLimit).map { row ->
                    SessionHistoryItem(
                        sessionId = row.sessionId,
                        displayId = formatSessionIdForDisplay(row.sessionId),
                        backend = row.backend?.takeIf { it.isNotBlank() }?.uppercase(Locale.US),
                        createdLabel = formatSessionTimestamp(row.firstEventMs),
                        lastActiveLabel = formatRelativeTime(row.lastEventMs, nowMs),
                        inferenceCount = row.inferenceCount,
                        totalTokens = row.totalTokens,
                    )
                }
                _uiState.update {
                    it.copy(
                        sessions = items,
                        isLoading = false,
                        errorMessage = null,
                    )
                }
            } catch (exception: Exception) {
                val message = exception.message
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "The diagnostics log couldn't be queried: $it" }
                    ?: "The diagnostics log couldn't be queried. Retry or inspect Logcat/Room state."
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = message,
                    )
                }
            }
        }
    }
}

internal fun formatSessionIdForDisplay(
    sessionId: String,
    leadingChars: Int = 8,
    trailingChars: Int = 6,
): String {
    val trimmedId = sessionId.trim()
    return if (trimmedId.length <= leadingChars + trailingChars + 1) {
        trimmedId
    } else {
        "${trimmedId.take(leadingChars)}…${trimmedId.takeLast(trailingChars)}"
    }
}

internal fun formatSessionTimestamp(ms: Long): String {
    val formatter = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())
    return formatter.format(Date(ms))
}

internal fun formatRelativeTime(targetMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val diffMs = (nowMs - targetMs).coerceAtLeast(0L)
    return when {
        diffMs < 60_000L -> "just now"
        diffMs < 3_600_000L -> "${diffMs / 60_000L}m ago"
        diffMs < 86_400_000L -> "${diffMs / 3_600_000L}h ago"
        diffMs < 604_800_000L -> "${diffMs / 86_400_000L}d ago"
        else -> formatSessionTimestamp(targetMs)
    }
}

internal fun formatWholeNumber(value: Int): String = NumberFormat.getIntegerInstance().format(value)

internal fun formatWholeNumber(value: Long): String = NumberFormat.getIntegerInstance().format(value)
