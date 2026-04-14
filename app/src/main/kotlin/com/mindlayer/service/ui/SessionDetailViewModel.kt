package com.mindlayer.service.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mindlayer.service.logging.LogDatabase
import com.mindlayer.service.logging.LogEntry
import com.mindlayer.service.logging.LogEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionDetailViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = LogDatabase.getInstance(application).logDao()
    private val _uiState = MutableStateFlow(SessionDetailUiState())
    val uiState: StateFlow<SessionDetailUiState> = _uiState.asStateFlow()

    fun loadSession(sessionId: String) {
        val displayId = formatSessionIdForDisplay(sessionId)
        _uiState.value = SessionDetailUiState(
            sessionId = sessionId,
            displayId = displayId,
            isLoading = true,
        )
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val entries = dao.getBySession(sessionId)
                if (entries.isEmpty()) {
                    _uiState.value = SessionDetailUiState(
                        sessionId = sessionId,
                        displayId = displayId,
                        isLoading = false,
                        emptyMessage = "No retained log entries were found for this session. The session may be stale, evicted, or removed by log cleanup.",
                    )
                    return@launch
                }

                val newestFirstEntries = entries.sortedByDescending { it.timestampMs }
                val firstEventMs = entries.minOf { it.timestampMs }
                val lastEventMs = entries.maxOf { it.timestampMs }
                val completedInferences = entries.filter { it.event == LogEvent.REQUEST_COMPLETE }
                val averageTokensPerSecond = completedInferences
                    .mapNotNull { it.tokensPerSec }
                    .takeIf { it.isNotEmpty() }
                    ?.average()
                    ?.let(::formatAverageTokensPerSecond)
                    .orEmpty()

                _uiState.value = SessionDetailUiState(
                    sessionId = sessionId,
                    displayId = displayId,
                    backend = newestFirstEntries.firstNotNullOfOrNull {
                        it.backend?.takeIf { backend -> backend.isNotBlank() }
                    }?.uppercase(Locale.US),
                    startedLabel = formatSessionTimestamp(firstEventMs),
                    lastEventLabel = formatSessionTimestamp(lastEventMs),
                    durationLabel = formatSessionDuration(lastEventMs - firstEventMs),
                    inferenceCount = completedInferences.size,
                    totalTokens = completedInferences.sumOf { it.tokensGenerated ?: 0 },
                    avgTokensPerSec = averageTokensPerSecond,
                    eventCount = entries.size,
                    events = newestFirstEntries.map { entry ->
                        SessionEventItem(
                            timestampLabel = formatEventTimestamp(entry.timestampMs),
                            category = entry.category,
                            event = formatEventName(entry.event),
                            requestIdLabel = entry.requestId
                                ?.takeIf { it.isNotBlank() }
                                ?.let(::formatSessionIdForDisplay)
                                .orEmpty(),
                            detail = buildEventDetail(entry),
                        )
                    },
                    isLoading = false,
                )
            } catch (exception: Exception) {
                val message = exception.message
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        "The session timeline couldn't be read from the diagnostics log: $it"
                    }
                    ?: "The session timeline couldn't be read from the diagnostics log. Retry or inspect Logcat/Room state."
                _uiState.value = SessionDetailUiState(
                    sessionId = sessionId,
                    displayId = displayId,
                    isLoading = false,
                    errorMessage = message,
                )
            }
        }
    }
}

internal fun formatEventTimestamp(ms: Long): String {
    val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    return formatter.format(Date(ms))
}

internal fun formatSessionDuration(ms: Long): String {
    val safeMs = ms.coerceAtLeast(0L)
    return when {
        safeMs < 1_000L -> "${formatWholeNumber(safeMs)}ms"
        safeMs < 60_000L -> String.format(Locale.US, "%.1fs", safeMs / 1000.0)
        safeMs < 3_600_000L -> "${safeMs / 60_000L}m ${(safeMs % 60_000L) / 1_000L}s"
        else -> "${safeMs / 3_600_000L}h ${(safeMs % 3_600_000L) / 60_000L}m"
    }
}

internal fun formatAverageTokensPerSecond(tokensPerSecond: Double): String =
    String.format(Locale.US, "%.1f", tokensPerSecond)

internal fun formatEventName(rawEvent: String): String = rawEvent
    .split('_')
    .filter { it.isNotBlank() }
    .joinToString(" ") { word ->
        word.lowercase(Locale.US).replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase(Locale.US) else char.toString()
        }
    }

internal fun buildEventDetail(entry: LogEntry): String {
    val parts = buildList {
        entry.durationMs?.let { add("${formatWholeNumber(it)}ms") }
        entry.tokensGenerated?.let { add("${formatWholeNumber(it)} tokens") }
        entry.tokensPerSec?.let { add("${formatAverageTokensPerSecond(it.toDouble())} tok/s") }
        entry.prefillTokensPerSec?.let {
            add("prefill ${formatAverageTokensPerSecond(it.toDouble())} tok/s")
        }
        entry.thermalBand?.takeIf { it.isNotBlank() }?.let(::add)
        entry.backend?.takeIf { it.isNotBlank() }?.let { add(it.uppercase(Locale.US)) }
        entry.errorMessage?.takeIf { it.isNotBlank() }?.let(::add)
        entry.memoryAvailableMb?.let { add("${formatWholeNumber(it)}MB free") }
        entry.memoryUsedMb?.let { add("${formatWholeNumber(it)}MB used") }
    }
    return when {
        parts.isNotEmpty() && !entry.extraJson.isNullOrBlank() -> {
            (parts + entry.extraJson).joinToString(" • ")
        }
        parts.isNotEmpty() -> parts.joinToString(" • ")
        !entry.extraJson.isNullOrBlank() -> entry.extraJson
        else -> ""
    }
}
