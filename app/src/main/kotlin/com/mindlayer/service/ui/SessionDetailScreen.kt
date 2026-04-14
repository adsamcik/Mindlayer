package com.mindlayer.service.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class SessionDetailUiState(
    val sessionId: String = "",
    val displayId: String = "",
    val backend: String? = null,
    val startedLabel: String = "",
    val lastEventLabel: String = "",
    val durationLabel: String = "",
    val inferenceCount: Int = 0,
    val totalTokens: Int = 0,
    val avgTokensPerSec: String = "",
    val eventCount: Int = 0,
    val events: List<SessionEventItem> = emptyList(),
    val isLoading: Boolean = true,
    val emptyMessage: String? = null,
    val errorMessage: String? = null,
)

data class SessionEventItem(
    val timestampLabel: String,
    val category: String,
    val event: String,
    val requestIdLabel: String = "",
    val detail: String,
)

private fun categoryColor(category: String): Color = when (category.uppercase()) {
    "INFERENCE" -> Color(0xFF2196F3)
    "THERMAL" -> Color(0xFFFF9800)
    "SESSION" -> Color(0xFF4CAF50)
    "MEMORY" -> Color(0xFF9C27B0)
    "ENGINE" -> Color(0xFF00BCD4)
    "ERROR" -> Color(0xFFF44336)
    else -> Color.Gray
}

@Composable
fun SessionDetailScreen(
    state: SessionDetailUiState,
    onBack: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            HeaderBar(displayId = state.displayId, onBack = onBack)

            when {
                state.isLoading -> {
                    DetailStatusPane(
                        title = "Loading session timeline",
                        message = if (state.displayId.isNotBlank()) {
                            "Fetching log entries for ${state.displayId}."
                        } else {
                            "Fetching log entries from the diagnostics database."
                        },
                        showProgress = true,
                    )
                }

                state.errorMessage != null -> {
                    DetailStatusPane(
                        title = "Couldn't load session",
                        message = state.errorMessage,
                        actionLabel = state.sessionId.takeIf { it.isNotBlank() }?.let { "Retry" },
                        onAction = onRetry,
                    )
                }

                state.events.isEmpty() -> {
                    DetailStatusPane(
                        title = "No events recorded",
                        message = state.emptyMessage
                            ?: "No retained log entries were found for this session.",
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item { SummaryCard(state) }
                        item {
                            Text(
                                text = "Showing ${formatWholeNumber(state.eventCount)} log entries, newest event first.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        items(state.events) { event ->
                            EventRow(event)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderBar(displayId: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
            )
        }
        Spacer(Modifier.width(4.dp))
        Column {
            Text(
                text = "Session detail",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Timeline for a single diagnostics session.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (displayId.isNotBlank()) {
                Text(
                    text = displayId,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(state: SessionDetailUiState) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Session summary",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))

            SelectionContainer {
                Text(
                    text = state.sessionId,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))
            SummaryLabelValue(label = "Started", value = state.startedLabel.ifBlank { "—" })
            SummaryLabelValue(label = "Last event", value = state.lastEventLabel.ifBlank { "—" })
            SummaryLabelValue(label = "Backend", value = state.backend ?: "—")
            SummaryLabelValue(label = "Duration", value = state.durationLabel.ifBlank { "—" })
            SummaryLabelValue(
                label = "Completed requests",
                value = formatWholeNumber(state.inferenceCount),
            )
            SummaryLabelValue(
                label = "Generated tokens",
                value = formatWholeNumber(state.totalTokens),
            )
            SummaryLabelValue(
                label = "Average tok/s",
                value = state.avgTokensPerSec.ifBlank { "—" },
            )
            SummaryLabelValue(
                label = "Log entries",
                value = formatWholeNumber(state.eventCount),
            )
        }
    }
}

@Composable
private fun EventRow(event: SessionEventItem) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = event.timestampLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Badge(text = event.category.uppercase(), color = categoryColor(event.category))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = event.event,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (event.requestIdLabel.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                SelectionContainer {
                    Text(
                        text = "Request ${event.requestIdLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (event.detail.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                SelectionContainer {
                    Text(
                        text = event.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryLabelValue(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        modifier = Modifier
            .background(
                color = color.copy(alpha = 0.15f),
                shape = RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun DetailStatusPane(
    title: String,
    message: String,
    showProgress: Boolean = false,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showProgress) {
                CircularProgressIndicator()
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            actionLabel?.let { label ->
                FilledTonalButton(onClick = onAction) {
                    Text(text = label)
                }
            }
        }
    }
}

private val PreviewEvents = listOf(
    SessionEventItem(
        timestampLabel = "14:33:02.200",
        category = "MEMORY",
        event = "Pressure change",
        detail = "4,200MB free",
    ),
    SessionEventItem(
        timestampLabel = "14:33:01.000",
        category = "ERROR",
        event = "Request error",
        requestIdLabel = "req-9f2a…81c4",
        detail = "OOM in prefill stage",
    ),
    SessionEventItem(
        timestampLabel = "14:32:28.500",
        category = "INFERENCE",
        event = "Request complete",
        requestIdLabel = "req-9f2a…81c4",
        detail = "8,400ms • 620 tokens • 73.8 tok/s",
    ),
)

private val PreviewState = SessionDetailUiState(
    sessionId = "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    displayId = "a1b2c3d4…567890",
    backend = "GPU",
    startedLabel = "Jun 15, 2025 14:32",
    lastEventLabel = "Jun 15, 2025 14:33",
    durationLabel = "1m 1s",
    inferenceCount = 2,
    totalTokens = 1_132,
    avgTokensPerSec = "71.8",
    eventCount = 8,
    events = PreviewEvents,
    isLoading = false,
)

@Preview(showBackground = true, widthDp = 400, heightDp = 900)
@Composable
private fun SessionDetailScreenPreview() {
    MaterialTheme {
        SessionDetailScreen(state = PreviewState)
    }
}

@Preview(showBackground = true, widthDp = 400, heightDp = 400, name = "Loading")
@Composable
private fun SessionDetailLoadingPreview() {
    MaterialTheme {
        SessionDetailScreen(state = SessionDetailUiState(displayId = "abc123de…ghi789"))
    }
}

@Preview(showBackground = true, widthDp = 400, heightDp = 400, name = "Error")
@Composable
private fun SessionDetailErrorPreview() {
    MaterialTheme {
        SessionDetailScreen(
            state = SessionDetailUiState(
                isLoading = false,
                errorMessage = "The session timeline couldn't be read from the diagnostics log.",
            ),
        )
    }
}
