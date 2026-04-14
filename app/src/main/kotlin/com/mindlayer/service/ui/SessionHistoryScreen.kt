package com.mindlayer.service.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class SessionHistoryUiState(
    val sessions: List<SessionHistoryItem> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
)

data class SessionHistoryItem(
    val sessionId: String,
    val displayId: String,
    val backend: String?,
    val createdLabel: String,
    val lastActiveLabel: String,
    val inferenceCount: Int,
    val totalTokens: Int,
)

private val BackendGpu = Color(0xFF42A5F5)
private val BackendCpu = Color(0xFF9E9E9E)
private val BackendNpu = Color(0xFF66BB6A)

private fun backendColor(backend: String?): Color = when (backend?.uppercase()) {
    "GPU" -> BackendGpu
    "CPU" -> BackendCpu
    "NPU" -> BackendNpu
    else -> BackendCpu
}

@Composable
fun SessionHistoryScreen(
    state: SessionHistoryUiState,
    onSessionClick: (String) -> Unit = {},
    onBack: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
                Column(modifier = Modifier.padding(start = 4.dp)) {
                    Text(
                        text = "Session history",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Recent diagnostics sessions stored in the local log database.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            when {
                state.isLoading -> {
                    HistoryStatusPane(
                        title = "Loading session history",
                        message = "Reading the most recent session summaries from the diagnostics log.",
                        showProgress = true,
                    )
                }

                state.errorMessage != null -> {
                    HistoryStatusPane(
                        title = "Couldn't load session history",
                        message = state.errorMessage,
                        actionLabel = "Retry",
                        onAction = onRetry,
                    )
                }

                state.sessions.isEmpty() -> {
                    HistoryStatusPane(
                        title = "No sessions recorded yet",
                        message = "Run a dashboard test inference or wait for a client request to create session log entries.",
                    )
                }

                else -> {
                    Text(
                        text = "Showing ${formatWholeNumber(state.sessions.size)} recent sessions. Tap a card to inspect the full event timeline.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.sessions, key = { it.sessionId }) { item ->
                            SessionCard(
                                item = item,
                                onClick = { onSessionClick(item.sessionId) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionCard(item: SessionHistoryItem, onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.displayId,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                item.backend?.let { backend ->
                    Spacer(modifier = Modifier.width(8.dp))
                    BackendBadge(backend = backend)
                }
            }

            if (item.displayId != item.sessionId) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.sessionId,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            HistoryLabelValue(label = "Created", value = item.createdLabel)
            HistoryLabelValue(label = "Last active", value = item.lastActiveLabel)

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "${formatWholeNumber(item.inferenceCount)} requests • ${formatWholeNumber(item.totalTokens)} tokens",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun HistoryLabelValue(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun BackendBadge(backend: String) {
    val color = backendColor(backend)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = backend.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun HistoryStatusPane(
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

@Preview(showBackground = true)
@Composable
private fun SessionHistoryScreenPreview() {
    MaterialTheme {
        SessionHistoryScreen(
            state = SessionHistoryUiState(
                isLoading = false,
                sessions = listOf(
                    SessionHistoryItem(
                        sessionId = "abc123def456ghi789",
                        displayId = "abc123de…ghi789",
                        backend = "GPU",
                        createdLabel = "Jun 15, 2025 09:30",
                        lastActiveLabel = "2h ago",
                        inferenceCount = 42,
                        totalTokens = 8_192,
                    ),
                    SessionHistoryItem(
                        sessionId = "xyz987wvu654tsr321",
                        displayId = "xyz987wv…tsr321",
                        backend = "CPU",
                        createdLabel = "Jun 14, 2025 14:12",
                        lastActiveLabel = "1d ago",
                        inferenceCount = 7,
                        totalTokens = 1_024,
                    ),
                ),
            ),
        )
    }
}

@Preview(showBackground = true, name = "Error")
@Composable
private fun SessionHistoryErrorPreview() {
    MaterialTheme {
        SessionHistoryScreen(
            state = SessionHistoryUiState(
                isLoading = false,
                errorMessage = "The diagnostics log database couldn't be queried.",
            ),
        )
    }
}
