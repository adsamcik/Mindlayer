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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.adsamcik.mindlayer.service.R
import com.adsamcik.mindlayer.service.ui.theme.MindlayerTheme
import com.adsamcik.mindlayer.service.ui.theme.MindlayerType

data class RecentLogsUiState(
    val logs: List<LogUiItem> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
)

@Composable
private fun logCategoryColor(category: String): Color = when (category.uppercase()) {
    "INFERENCE" -> MaterialTheme.colorScheme.primary
    "THERMAL"   -> MaterialTheme.colorScheme.tertiary
    "SESSION"   -> MaterialTheme.colorScheme.secondary
    "MEMORY"    -> MaterialTheme.colorScheme.inversePrimary
    "ENGINE"    -> MaterialTheme.colorScheme.secondary
    "ERROR"     -> MaterialTheme.colorScheme.error
    else        -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun logCategoryIcon(category: String): ImageVector = when (category.uppercase()) {
    "INFERENCE" -> Icons.Filled.PlayArrow
    "THERMAL"   -> Icons.Filled.Warning
    "ENGINE"    -> Icons.Filled.Settings
    "ERROR"     -> Icons.Filled.Warning
    else        -> Icons.Filled.Info
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentLogsScreen(
    state: RecentLogsUiState,
    onBack: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            MediumTopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.recent_logs_title))
                        val subtitle = when {
                            state.isLoading -> "Loading diagnostics log…"
                            state.errorMessage != null -> "Load failure"
                            else -> "${formatWholeNumber(state.logs.size)} log entries"
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
        when {
            state.isLoading -> {
                RecentLogsStatusPane(
                    modifier = Modifier.padding(innerPadding),
                    title = "Loading system logs",
                    message = "Reading retained diagnostics entries from the local log database.",
                    showProgress = true,
                )
            }

            state.errorMessage != null -> {
                RecentLogsStatusPane(
                    modifier = Modifier.padding(innerPadding),
                    title = "Couldn't load system logs",
                    message = state.errorMessage,
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = "System logs load error",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(40.dp),
                        )
                    },
                    actionLabel = "Retry",
                    onAction = onRetry,
                )
            }

            state.logs.isEmpty() -> {
                RecentLogsStatusPane(
                    modifier = Modifier.padding(innerPadding),
                    title = "No log entries recorded yet",
                    message = "Run a test inference or wait for a client request to generate diagnostic log entries.",
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = "No log entries",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp),
                        )
                    },
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.logs) { log ->
                        LogEntryCard(log)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentLogsStatusPane(
    modifier: Modifier = Modifier,
    title: String,
    message: String,
    showProgress: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showProgress) LoadingIndicator()
            icon?.invoke()
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
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

@Composable
private fun LogEntryCard(log: LogUiItem) {
    val color = logCategoryColor(log.category)
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = log.timestampLabel,
                    style = MindlayerType.Mono.LabelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                        imageVector = logCategoryIcon(log.category),
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(11.dp),
                    )
                    Text(
                        text = log.category.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Text(
                text = log.event,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (log.detail.isNotBlank()) {
                Text(
                    text = log.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RecentLogsScreenPreview() {
    MindlayerTheme {
        RecentLogsScreen(
            state = RecentLogsUiState(
                isLoading = false,
                logs = listOf(
                    LogUiItem("2m ago", "INFERENCE", "Generation complete", "tokens=1024 • 121.9 tok/s"),
                    LogUiItem("2m ago", "SESSION", "Session created", "backend=GPU"),
                    LogUiItem("5m ago", "THERMAL", "Band changed", "band=WARM"),
                    LogUiItem("15m ago", "ERROR", "Inference failed", "OOM in prefill"),
                ),
            ),
        )
    }
}
