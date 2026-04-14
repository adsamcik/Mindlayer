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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
// UI state
// ---------------------------------------------------------------------------

data class SessionDetailUiState(
    val sessionId: String = "",
    val displayId: String = "",
    val backend: String? = null,
    val durationLabel: String = "",
    val inferenceCount: Int = 0,
    val totalTokens: Int = 0,
    val avgTokensPerSec: String = "",
    val events: List<SessionEventItem> = emptyList(),
    val isLoading: Boolean = true,
)

data class SessionEventItem(
    val timestampLabel: String,
    val category: String,
    val event: String,
    val detail: String,
)

// ---------------------------------------------------------------------------
// Color helper
// ---------------------------------------------------------------------------

private fun categoryColor(category: String): Color = when (category.uppercase()) {
    "INFERENCE" -> Color(0xFF2196F3)
    "THERMAL"   -> Color(0xFFFF9800)
    "SESSION"   -> Color(0xFF4CAF50)
    "MEMORY"    -> Color(0xFF9C27B0)
    "ENGINE"    -> Color(0xFF00BCD4)
    "ERROR"     -> Color(0xFFF44336)
    else        -> Color.Gray
}

// ---------------------------------------------------------------------------
// Root composable
// ---------------------------------------------------------------------------

@Composable
fun SessionDetailScreen(
    state: SessionDetailUiState,
    onBack: () -> Unit = {},
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ---- Top bar ----
            HeaderBar(displayId = state.displayId, onBack = onBack)

            when {
                state.isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                state.events.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No events found",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item { SummaryCard(state) }
                        items(state.events) { event ->
                            EventRow(event)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Header bar
// ---------------------------------------------------------------------------

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
                text = "Session Detail",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
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

// ---------------------------------------------------------------------------
// Summary card
// ---------------------------------------------------------------------------

@Composable
private fun SummaryCard(state: SessionDetailUiState) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Summary",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))

            // Full session ID – selectable so users can copy it
            SelectionContainer {
                Text(
                    text = state.sessionId,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            Spacer(Modifier.height(8.dp))

            LabelValue("Backend", state.backend ?: "—")
            LabelValue("Duration", state.durationLabel.ifBlank { "—" })
            LabelValue("Inferences", "${state.inferenceCount}")
            LabelValue("Total tokens", "${state.totalTokens}")
            LabelValue("Avg tok/s", state.avgTokensPerSec.ifBlank { "—" })
        }
    }
}

// ---------------------------------------------------------------------------
// Event row
// ---------------------------------------------------------------------------

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
            if (event.detail.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = event.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Shared building blocks
// ---------------------------------------------------------------------------

@Composable
private fun LabelValue(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
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

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

private val PreviewEvents = listOf(
    SessionEventItem("14:32:01.123", "SESSION", "Session created", "GPU"),
    SessionEventItem("14:32:05.456", "INFERENCE", "Request start", ""),
    SessionEventItem("14:32:12.789", "INFERENCE", "Request complete", "7333ms • 512 tokens • 69.8 tok/s"),
    SessionEventItem("14:32:13.001", "THERMAL", "Band change", "WARM"),
    SessionEventItem("14:32:20.100", "INFERENCE", "Request start", ""),
    SessionEventItem("14:32:28.500", "INFERENCE", "Request complete", "8400ms • 620 tokens • 73.8 tok/s"),
    SessionEventItem("14:33:01.000", "ERROR", "Request error", "OOM in prefill stage"),
    SessionEventItem("14:33:02.200", "MEMORY", "Pressure change", "4200MB free"),
)

private val PreviewState = SessionDetailUiState(
    sessionId = "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    displayId = "a1b2c3d4-e5f…",
    backend = "GPU",
    durationLabel = "1m 1s",
    inferenceCount = 2,
    totalTokens = 1132,
    avgTokensPerSec = "71.8",
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
        SessionDetailScreen(state = SessionDetailUiState())
    }
}

@Preview(showBackground = true, widthDp = 400, heightDp = 400, name = "Empty")
@Composable
private fun SessionDetailEmptyPreview() {
    MaterialTheme {
        SessionDetailScreen(state = SessionDetailUiState(isLoading = false))
    }
}
