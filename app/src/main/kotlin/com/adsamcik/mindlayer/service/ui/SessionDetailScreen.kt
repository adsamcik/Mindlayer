package com.adsamcik.mindlayer.service.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.adsamcik.mindlayer.service.ui.theme.MindlayerTheme
import com.adsamcik.mindlayer.service.ui.theme.MindlayerType

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

@Composable
private fun categoryColor(category: String): Color = when (category.uppercase()) {
    "INFERENCE" -> MaterialTheme.colorScheme.primary
    "THERMAL"   -> MaterialTheme.colorScheme.tertiary
    "SESSION"   -> MaterialTheme.colorScheme.secondary
    "MEMORY"    -> MaterialTheme.colorScheme.inversePrimary
    "ENGINE"    -> MaterialTheme.colorScheme.secondary
    "ERROR"     -> MaterialTheme.colorScheme.error
    else        -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun categoryIcon(category: String): ImageVector = when (category.uppercase()) {
    "INFERENCE" -> Icons.Filled.PlayArrow
    "THERMAL"   -> Icons.Filled.Warning
    "ENGINE"    -> Icons.Filled.Settings
    "ERROR"     -> Icons.Filled.Warning
    else        -> Icons.Filled.Info
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    state: SessionDetailUiState,
    onBack: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            MediumTopAppBar(
                title = {
                    Column {
                        Text("Session Detail")
                        if (state.displayId.isNotBlank()) {
                            Text(
                                text = state.displayId,
                                style = MindlayerType.Mono.BodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.mediumTopAppBarColors(
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
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
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = "Session load error",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(40.dp),
                            )
                        },
                        actionLabel = state.sessionId.takeIf { it.isNotBlank() }?.let { "Retry" },
                        onAction = onRetry,
                    )
                }

                state.events.isEmpty() -> {
                    DetailStatusPane(
                        title = "No events recorded",
                        message = state.emptyMessage
                            ?: "No retained log entries were found for this session.",
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.Info,
                                contentDescription = "No session events",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(40.dp),
                            )
                        },
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
                                text = "${formatWholeNumber(state.eventCount)} log entries · newest first",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        items(state.events) { event ->
                            if (event.category.equals("INFERENCE", ignoreCase = true) &&
                                (event.event.equals("User message", ignoreCase = true) ||
                                 event.event.equals("Model response", ignoreCase = true))) {
                                MessageEventRow(event)
                            } else {
                                EventRow(event)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(state: SessionDetailUiState) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.DateRange,
                    contentDescription = "Session summary",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "Session summary",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
                Text(
                    text = state.sessionId,
                    style = MindlayerType.Mono.BodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
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
private fun MessageEventRow(event: SessionEventItem) {
    val isUser = event.event.lowercase().contains("user")
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val label = if (isUser) "User" else "Model"
    val alignment = if (isUser) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = event.timestampLabel,
                style = MindlayerType.Mono.LabelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
            )
        }
        Spacer(Modifier.height(4.dp))
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = bubbleColor,
            modifier = Modifier.fillMaxWidth(0.9f),
        ) {
            SelectionContainer {
                Text(
                    text = event.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun EventRow(event: SessionEventItem) {
    val catColor = categoryColor(event.category)
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = event.timestampLabel,
                    style = MindlayerType.Mono.LabelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CategoryChip(category = event.category, color = catColor)
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
                        style = MindlayerType.Mono.BodySmall,
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
private fun CategoryChip(category: String, color: Color) {
    Row(
        modifier = Modifier
            .background(
                color = color.copy(alpha = 0.12f),
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = categoryIcon(category),
            contentDescription = "$category category",
            tint = color,
            modifier = Modifier.size(11.dp),
        )
        Text(
            text = category.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold,
        )
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
private fun DetailStatusPane(
    title: String,
    message: String,
    showProgress: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
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
            if (showProgress) CircularProgressIndicator()
            icon?.invoke()
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
    SessionEventItem(
        timestampLabel = "14:32:28.400",
        category = "INFERENCE",
        event = "Model response",
        requestIdLabel = "req-9f2a…81c4",
        detail = "I'm Gemma, a large language model created by Google DeepMind. I'm designed to be helpful, harmless, and honest. I can assist with a wide variety of tasks including answering questions, writing, analysis, and more.",
    ),
    SessionEventItem(
        timestampLabel = "14:32:00.100",
        category = "INFERENCE",
        event = "User message",
        requestIdLabel = "req-9f2a…81c4",
        detail = "Hello! What are you? Can you tell me about yourself?",
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
    MindlayerTheme {
        SessionDetailScreen(state = PreviewState)
    }
}

@Preview(showBackground = true, widthDp = 400, heightDp = 400, name = "Loading")
@Composable
private fun SessionDetailLoadingPreview() {
    MindlayerTheme {
        SessionDetailScreen(state = SessionDetailUiState(displayId = "abc123de…ghi789"))
    }
}

@Preview(showBackground = true, widthDp = 400, heightDp = 400, name = "Error")
@Composable
private fun SessionDetailErrorPreview() {
    MindlayerTheme {
        SessionDetailScreen(
            state = SessionDetailUiState(
                isLoading = false,
                errorMessage = "The session timeline couldn't be read from the diagnostics log.",
            ),
        )
    }
}
