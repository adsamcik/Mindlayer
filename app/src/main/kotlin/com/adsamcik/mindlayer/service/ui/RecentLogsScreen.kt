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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.adsamcik.mindlayer.service.ui.theme.MindlayerTheme
import com.adsamcik.mindlayer.service.ui.theme.MindlayerType

data class RecentLogsUiState(
    val logs: List<LogUiItem> = emptyList(),
    val isLoading: Boolean = true,
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
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Recent Logs") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            state.logs.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = "No log entries",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp),
                        )
                        Text(
                            text = "No log entries recorded yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Run a test inference or wait for a client request to generate log entries.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }
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
                        contentDescription = "${log.category} category",
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
