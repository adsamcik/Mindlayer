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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
// Color helpers
// ---------------------------------------------------------------------------

private val ThermalCool = Color(0xFF4CAF50)
private val ThermalWarm = Color(0xFFFFC107)
private val ThermalHot = Color(0xFFFF9800)
private val ThermalCritical = Color(0xFFF44336)

private val PressureNormal = Color(0xFF4CAF50)
private val PressureWarning = Color(0xFFFFC107)
private val PressureCritical = Color(0xFFFF9800)
private val PressureEmergency = Color(0xFFF44336)

private val CategoryInference = Color(0xFF42A5F5)
private val CategoryThermal = Color(0xFFFF9800)
private val CategorySession = Color(0xFF66BB6A)
private val CategoryMemory = Color(0xFFAB47BC)
private val CategoryError = Color(0xFFF44336)
private val CategoryDefault = Color(0xFF9E9E9E)

private fun thermalColor(band: String): Color = when (band.uppercase()) {
    "COOL" -> ThermalCool
    "WARM" -> ThermalWarm
    "HOT" -> ThermalHot
    "CRITICAL" -> ThermalCritical
    else -> CategoryDefault
}

private fun pressureColor(pressure: String): Color = when (pressure.uppercase()) {
    "NORMAL" -> PressureNormal
    "WARNING" -> PressureWarning
    "CRITICAL" -> PressureCritical
    "EMERGENCY" -> PressureEmergency
    else -> CategoryDefault
}

private fun categoryColor(category: String): Color = when (category.uppercase()) {
    "INFERENCE" -> CategoryInference
    "THERMAL" -> CategoryThermal
    "SESSION" -> CategorySession
    "MEMORY" -> CategoryMemory
    "ERROR" -> CategoryError
    else -> CategoryDefault
}

// ---------------------------------------------------------------------------
// Formatting helpers
// ---------------------------------------------------------------------------

private fun formatUptime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

// ---------------------------------------------------------------------------
// Root composable
// ---------------------------------------------------------------------------

@Composable
fun DashboardScreen(state: DashboardUiState, onTestInference: () -> Unit = {}, onNavigateToHistory: () -> Unit = {}) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header
            item { HeaderSection(state) }
            // Engine
            item { EngineStatusCard(state) }
            // Thermal
            item { ThermalStatusCard(state) }
            // Memory
            item { MemoryCard(state) }
            // Sessions
            item { ActiveSessionsCard(state) }
            // Session History
            item {
                ElevatedCard(
                    onClick = onNavigateToHistory,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                text = "Session History",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "View past sessions and inference activity",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = "→",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            // Test inference
            item { TestInferenceCard(state, onTestInference) }
            // Logs
            item { RecentLogsCard(state) }
        }
    }
}

// ---------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------

@Composable
private fun HeaderSection(state: DashboardUiState) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Mindlayer",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        if (state.modelPath.isNotBlank()) {
            Text(
                text = state.modelPath.substringAfterLast('/'),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Engine status
// ---------------------------------------------------------------------------

@Composable
private fun EngineStatusCard(state: DashboardUiState) {
    DashboardCard(title = "Engine Status") {
        LabelValue("Model loaded", if (state.isEngineLoaded) "Yes" else "No")
        LabelValue("Backend", state.backend)
        LabelValue("Init time", "%.1fs".format(state.initTimeSeconds))
        LabelValue("Uptime", formatUptime(state.uptimeMs))
    }
}

// ---------------------------------------------------------------------------
// Thermal status
// ---------------------------------------------------------------------------

@Composable
private fun ThermalStatusCard(state: DashboardUiState) {
    DashboardCard(title = "Thermal Status") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(thermalColor(state.thermalBand))
            Spacer(Modifier.width(8.dp))
            Text(
                text = state.thermalBand.uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = thermalColor(state.thermalBand),
            )
        }
        Spacer(Modifier.height(8.dp))
        LabelValue("Recommended backend", state.recommendedBackend)
        LabelValue("Burst", "${state.burstSeconds}s")
        LabelValue("Rest", "${state.restSeconds}s")
        LabelValue("Chunk tokens", "${state.chunkTokens}")
        if (state.headroom != null) {
            LabelValue("Headroom", "%.2f".format(state.headroom))
        }
    }
}

// ---------------------------------------------------------------------------
// Memory
// ---------------------------------------------------------------------------

@Composable
private fun MemoryCard(state: DashboardUiState) {
    DashboardCard(title = "Memory") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(pressureColor(state.memoryPressure))
            Spacer(Modifier.width(8.dp))
            Text(
                text = state.memoryPressure.uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = pressureColor(state.memoryPressure),
            )
        }
        Spacer(Modifier.height(8.dp))

        val fraction = if (state.totalRamMb > 0) {
            state.availableRamMb.toFloat() / state.totalRamMb.toFloat()
        } else 0f

        LabelValue("RAM", "${state.availableRamMb} MB / ${state.totalRamMb} MB available")

        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = pressureColor(state.memoryPressure),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        LabelValue("Max sessions", "${state.maxSessions}")
    }
}

// ---------------------------------------------------------------------------
// Active sessions
// ---------------------------------------------------------------------------

@Composable
private fun ActiveSessionsCard(state: DashboardUiState) {
    DashboardCard(title = "Active Sessions (${state.activeSessions.size})") {
        if (state.activeSessions.isEmpty()) {
            Text(
                text = "No active sessions",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.activeSessions.forEachIndexed { index, session ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                SessionRow(session)
            }
        }
    }
}

@Composable
private fun SessionRow(session: SessionUiItem) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = session.sessionId.take(12) + "…",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (session.isStreaming) {
                Badge(text = "STREAMING", color = CategoryInference)
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = session.backend,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${session.tokenCount}/${session.maxTokens} tok",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = session.lastAccessedLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Test inference
// ---------------------------------------------------------------------------

@Composable
private fun TestInferenceCard(state: DashboardUiState, onTestInference: () -> Unit) {
    DashboardCard(title = "🧪 Test Inference") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onTestInference,
                    enabled = !state.isTestRunning,
                ) {
                    if (state.isTestRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (state.isTestRunning) "Running…" else "Send Test Prompt")
                }
                if (state.testStatus.isNotEmpty()) {
                    Text(
                        text = state.testStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            state.testStatus.startsWith("✅") -> Color(0xFF4CAF50)
                            state.testStatus.startsWith("❌") -> Color(0xFFF44336)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            if (state.testOutput.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        text = state.testOutput,
                        modifier = Modifier
                            .padding(12.dp)
                            .height(120.dp)
                            .verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Recent logs
// ---------------------------------------------------------------------------

@Composable
private fun RecentLogsCard(state: DashboardUiState) {
    DashboardCard(title = "Recent Logs") {
        if (state.recentLogs.isEmpty()) {
            Text(
                text = "No recent events",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.recentLogs.forEachIndexed { index, log ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 6.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                LogRow(log)
            }
        }
    }
}

@Composable
private fun LogRow(log: LogUiItem) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = log.timestampLabel,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Badge(text = log.category.uppercase(), color = categoryColor(log.category))
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = log.event,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        if (log.detail.isNotBlank()) {
            Text(
                text = log.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Shared building blocks
// ---------------------------------------------------------------------------

@Composable
private fun DashboardCard(
    title: String,
    content: @Composable () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

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
private fun StatusDot(color: Color) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color),
    )
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

private val PreviewState = DashboardUiState(
    isEngineLoaded = true,
    backend = "GPU",
    initTimeSeconds = 2.3f,
    uptimeMs = 3_723_000,
    modelPath = "/data/local/tmp/gemma-3n-E4B-it-int4.task",
    thermalBand = "WARM",
    recommendedBackend = "GPU",
    burstSeconds = 8,
    restSeconds = 3,
    chunkTokens = 64,
    headroom = 0.78f,
    memoryPressure = "NORMAL",
    availableRamMb = 4_200,
    totalRamMb = 7_800,
    maxSessions = 2,
    activeSessions = listOf(
        SessionUiItem(
            sessionId = "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
            backend = "GPU",
            tokenCount = 1_024,
            maxTokens = 4_096,
            isStreaming = true,
            lastAccessedLabel = "2s ago",
        ),
        SessionUiItem(
            sessionId = "f0e1d2c3-b4a5-6789-0fed-cba987654321",
            backend = "GPU",
            tokenCount = 256,
            maxTokens = 4_096,
            isStreaming = false,
            lastAccessedLabel = "45s ago",
        ),
    ),
    recentLogs = listOf(
        LogUiItem("2m ago", "INFERENCE", "Generation complete", "1 024 tokens in 8.4s — 121.9 tok/s"),
        LogUiItem("2m ago", "SESSION", "Session created", "a1b2c3d4 — GPU, 4 096 max tokens"),
        LogUiItem("5m ago", "THERMAL", "Band changed", "COOL → WARM (headroom 0.81)"),
        LogUiItem("12m ago", "MEMORY", "Snapshot", "4 200 MB available — NORMAL"),
        LogUiItem("15m ago", "ERROR", "Inference failed", "Session f0e1d2c3 — OOM in prefill"),
    ),
)

@Preview(showBackground = true, widthDp = 400, heightDp = 900)
@Composable
private fun DashboardScreenPreview() {
    MaterialTheme {
        DashboardScreen(state = PreviewState)
    }
}

@Preview(showBackground = true, widthDp = 400, heightDp = 500, name = "Empty State")
@Composable
private fun DashboardScreenEmptyPreview() {
    MaterialTheme {
        DashboardScreen(
            state = DashboardUiState(
                backend = "NONE",
                modelPath = "",
                totalRamMb = 7_800,
                availableRamMb = 6_100,
                maxSessions = 2,
            ),
        )
    }
}
