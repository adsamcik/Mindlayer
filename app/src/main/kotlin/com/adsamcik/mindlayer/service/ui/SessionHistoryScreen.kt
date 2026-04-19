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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.adsamcik.mindlayer.service.ui.theme.MindlayerTheme
import com.adsamcik.mindlayer.service.ui.theme.MindlayerType

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionHistoryScreen(
    state: SessionHistoryUiState,
    onSessionClick: (String) -> Unit = {},
    onBack: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            MediumTopAppBar(
                title = {
                    Column {
                        Text("Session History")
                        val subtitle = when {
                            state.isLoading -> "Loading…"
                            state.errorMessage != null -> "Error"
                            else -> "${state.sessions.size} sessions"
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                    HistoryStatusPane(
                        title = "Loading session history",
                        showProgress = true,
                    )
                }

                state.errorMessage != null -> {
                    HistoryStatusPane(
                        title = "Couldn't load session history",
                        message = state.errorMessage,
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = "Session history load error",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(40.dp),
                            )
                        },
                        actionLabel = "Retry",
                        onAction = onRetry,
                    )
                }

                state.sessions.isEmpty() -> {
                    HistoryStatusPane(
                        title = "No sessions recorded yet",
                        message = "Run a test inference or wait for a client request.",
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.Info,
                                contentDescription = "No session history",
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
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.displayId,
                    style = MindlayerType.Mono.LabelMedium.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
                    style = MindlayerType.Mono.BodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            HistoryLabelValue(icon = Icons.Filled.DateRange, label = "Created", value = item.createdLabel)
            HistoryLabelValue(icon = null, label = "Last active", value = item.lastActiveLabel)

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "${formatWholeNumber(item.inferenceCount)} requests · ${formatWholeNumber(item.totalTokens)} tokens",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun HistoryLabelValue(icon: ImageVector?, label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
        } else {
            Spacer(modifier = Modifier.width(18.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
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
    val (bgColor, textColor) = when (backend.uppercase()) {
        "GPU" -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        "NPU" -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        else  -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .background(bgColor, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = backend.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun HistoryStatusPane(
    title: String,
    message: String? = null,
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
            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            actionLabel?.let { label ->
                FilledTonalButton(onClick = onAction) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Retry",
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = label)
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SessionHistoryScreenPreview() {
    MindlayerTheme {
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
    MindlayerTheme {
        SessionHistoryScreen(
            state = SessionHistoryUiState(
                isLoading = false,
                errorMessage = "The diagnostics log database couldn't be queried.",
            ),
        )
    }
}
