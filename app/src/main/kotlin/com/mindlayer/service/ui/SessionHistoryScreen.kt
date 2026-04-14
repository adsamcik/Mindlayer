package com.mindlayer.service.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
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

// ---------------------------------------------------------------------------
// UI state
// ---------------------------------------------------------------------------

data class SessionHistoryUiState(
    val sessions: List<SessionHistoryItem> = emptyList(),
    val isLoading: Boolean = true,
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

// ---------------------------------------------------------------------------
// Color helpers
// ---------------------------------------------------------------------------

private val BackendGpu = Color(0xFF42A5F5)
private val BackendCpu = Color(0xFF9E9E9E)
private val BackendNpu = Color(0xFF66BB6A)

private fun backendColor(backend: String?): Color = when (backend?.uppercase()) {
    "GPU" -> BackendGpu
    "CPU" -> BackendCpu
    "NPU" -> BackendNpu
    else -> BackendCpu
}

// ---------------------------------------------------------------------------
// Date formatting helpers
// ---------------------------------------------------------------------------

private fun formatDate(ms: Long): String {
    val sdf = java.text.SimpleDateFormat("MMM d, yyyy HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(ms))
}

private fun formatRelativeTime(ms: Long): String {
    val diff = System.currentTimeMillis() - ms
    return when {
        diff < 60_000 -> "just now"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        diff < 604_800_000 -> "${diff / 86_400_000}d ago"
        else -> formatDate(ms)
    }
}

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

@Composable
fun SessionHistoryScreen(
    state: SessionHistoryUiState,
    onSessionClick: (String) -> Unit = {},
    onBack: () -> Unit = {},
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
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
                Text(
                    text = "Session History",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            when {
                state.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                state.sessions.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No sessions yet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.sessions, key = { it.sessionId }) { item ->
                            SessionCard(item = item, onClick = { onSessionClick(item.sessionId) })
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Session card
// ---------------------------------------------------------------------------

@Composable
private fun SessionCard(item: SessionHistoryItem, onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header row: session ID + backend badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.displayId,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                if (item.backend != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    BackendBadge(backend = item.backend)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Time info
            Text(
                text = "Created: ${item.createdLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Last active: ${item.lastActiveLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Stats row
            Text(
                text = "${item.inferenceCount} inferences • ${item.totalTokens} tokens",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Backend badge
// ---------------------------------------------------------------------------

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

// ---------------------------------------------------------------------------
// Preview
// ---------------------------------------------------------------------------

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
                        displayId = "abc123def456…",
                        backend = "GPU",
                        createdLabel = "Jun 15, 2025 09:30",
                        lastActiveLabel = "2h ago",
                        inferenceCount = 42,
                        totalTokens = 8_192,
                    ),
                    SessionHistoryItem(
                        sessionId = "xyz987wvu654tsr321",
                        displayId = "xyz987wvu654…",
                        backend = "CPU",
                        createdLabel = "Jun 14, 2025 14:12",
                        lastActiveLabel = "1d ago",
                        inferenceCount = 7,
                        totalTokens = 1_024,
                    ),
                    SessionHistoryItem(
                        sessionId = "npu000session001ab",
                        displayId = "npu000sessio…",
                        backend = "NPU",
                        createdLabel = "Jun 13, 2025 08:00",
                        lastActiveLabel = "2d ago",
                        inferenceCount = 120,
                        totalTokens = 32_768,
                    ),
                ),
            ),
        )
    }
}
