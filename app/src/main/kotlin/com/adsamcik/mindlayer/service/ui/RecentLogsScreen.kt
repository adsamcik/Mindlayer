package com.adsamcik.mindlayer.service.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
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

@Composable
fun RecentLogsScreen(
    state: RecentLogsUiState,
    onBack: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            MindlayerSecondaryTopBar(
                title = stringResource(R.string.recent_logs_title),
                subtitle = when {
                    state.isLoading -> stringResource(R.string.recent_logs_subtitle_loading)
                    state.errorMessage != null -> stringResource(R.string.recent_logs_subtitle_load_failure)
                    else -> stringResource(R.string.recent_logs_subtitle_count, formatWholeNumber(state.logs.size))
                },
                onBack = onBack,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        when {
            state.isLoading -> {
                MindlayerStatusPane(
                    modifier = Modifier.padding(innerPadding),
                    title = stringResource(R.string.recent_logs_loading_title),
                    message = stringResource(R.string.recent_logs_loading_message),
                    showProgress = true,
                )
            }

            state.errorMessage != null -> {
                MindlayerStatusPane(
                    modifier = Modifier.padding(innerPadding),
                    title = stringResource(R.string.recent_logs_error_title),
                    message = state.errorMessage,
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = stringResource(R.string.recent_logs_a11y_load_error),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(40.dp),
                        )
                    },
                    actionLabel = stringResource(R.string.common_retry),
                    onAction = onRetry,
                )
            }

            state.logs.isEmpty() -> {
                MindlayerStatusPane(
                    modifier = Modifier.padding(innerPadding),
                    title = stringResource(R.string.recent_logs_empty_title),
                    message = stringResource(R.string.recent_logs_empty_message),
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = stringResource(R.string.recent_logs_a11y_empty),
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
                    contentPadding = MindlayerScreenDefaults.ContentPadding,
                    verticalArrangement = Arrangement.spacedBy(MindlayerScreenDefaults.ItemSpacing),
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
private fun LogEntryCard(log: LogUiItem) {
    val color = logCategoryColor(log.category)
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(MindlayerScreenDefaults.CardContentPadding),
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
