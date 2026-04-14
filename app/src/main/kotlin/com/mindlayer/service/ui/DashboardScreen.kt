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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val WarningAmber = Color(0xFFB26A00)

private val ThermalCool = Color(0xFF2E7D32)
private val ThermalWarm = Color(0xFFB26A00)
private val ThermalHot = Color(0xFFE65100)
private val ThermalCritical = Color(0xFFC62828)

private val PressureNormal = Color(0xFF2E7D32)
private val PressureWarning = Color(0xFFB26A00)
private val PressureCritical = Color(0xFFE65100)
private val PressureEmergency = Color(0xFFC62828)

private val CategoryInference = Color(0xFF1565C0)
private val CategoryThermal = Color(0xFFE65100)
private val CategorySession = Color(0xFF2E7D32)
private val CategoryMemory = Color(0xFF7B1FA2)
private val CategoryError = Color(0xFFC62828)
private val CategoryDefault = Color(0xFF616161)

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

private fun backendColor(backend: String): Color = when (backend.uppercase()) {
    "GPU" -> CategoryInference
    "CPU" -> CategoryMemory
    "NPU" -> CategorySession
    "NONE" -> CategoryError
    else -> CategoryDefault
}

@Composable
private fun healthColor(level: DashboardHealthLevel): Color = when (level) {
    DashboardHealthLevel.CONNECTING -> MaterialTheme.colorScheme.secondary
    DashboardHealthLevel.HEALTHY -> MaterialTheme.colorScheme.primary
    DashboardHealthLevel.DEGRADED -> WarningAmber
    DashboardHealthLevel.ERROR -> MaterialTheme.colorScheme.error
}

@Composable
private fun toneColor(tone: DashboardMessageTone): Color = when (tone) {
    DashboardMessageTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    DashboardMessageTone.INFO -> MaterialTheme.colorScheme.secondary
    DashboardMessageTone.SUCCESS -> MaterialTheme.colorScheme.primary
    DashboardMessageTone.WARNING -> WarningAmber
    DashboardMessageTone.ERROR -> MaterialTheme.colorScheme.error
}

@Composable
private fun connectionColor(connectionState: DashboardConnectionState): Color = when (connectionState) {
    DashboardConnectionState.CONNECTING -> MaterialTheme.colorScheme.secondary
    DashboardConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
    DashboardConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.error
}

@Composable
private fun freshnessColor(freshness: DashboardFreshness): Color = when (freshness) {
    DashboardFreshness.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    DashboardFreshness.FRESH -> MaterialTheme.colorScheme.primary
    DashboardFreshness.STALE -> WarningAmber
}

private fun formatUptime(ms: Long): String {
    val totalSeconds = (ms / 1_000).coerceAtLeast(0)
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

private fun formatSampleTime(timestampMs: Long?, nowMs: Long, fallback: String): String =
    timestampMs?.let { formatRelativeTimestamp(it, nowMs) } ?: fallback

private fun modelDisplayName(modelId: String): String =
    modelId.substringAfterLast('/').substringAfterLast('\\')

private fun connectionLabel(connectionState: DashboardConnectionState): String = when (connectionState) {
    DashboardConnectionState.CONNECTING -> "CONNECTING"
    DashboardConnectionState.CONNECTED -> "CONNECTED"
    DashboardConnectionState.DISCONNECTED -> "DISCONNECTED"
}

private fun freshnessLabel(freshness: DashboardFreshness): String = when (freshness) {
    DashboardFreshness.UNKNOWN -> "UNKNOWN"
    DashboardFreshness.FRESH -> "FRESH"
    DashboardFreshness.STALE -> "STALE"
}

private fun healthHeadline(state: DashboardUiState, health: DashboardHealthLevel): String = when {
    state.connectionState == DashboardConnectionState.DISCONNECTED -> "Service disconnected"
    state.statusErrorMessage != null && state.connectionState == DashboardConnectionState.CONNECTED -> {
        "Status polling failed"
    }

    health == DashboardHealthLevel.CONNECTING -> "Connecting to service"
    !state.isEngineLoaded || state.backend.equals("NONE", ignoreCase = true) -> "Engine not ready"
    health == DashboardHealthLevel.DEGRADED -> "Service needs attention"
    else -> "Service ready"
}

private fun healthDetail(state: DashboardUiState, nowMs: Long): String = when {
    state.connectionState == DashboardConnectionState.DISCONNECTED -> {
        state.lastStatusUpdateMs?.let {
            "No live binder connection. Last good status sample ${formatRelativeTimestamp(it, nowMs)}."
        } ?: "No live binder connection and no successful status sample yet."
    }

    state.statusErrorMessage != null && state.connectionState == DashboardConnectionState.CONNECTED -> {
        "Binder is up, but live polling is failing. Check recent logs and reconnect the service if this persists."
    }

    state.statusFreshness(nowMs) == DashboardFreshness.STALE -> {
        state.lastStatusUpdateMs?.let {
            "Last successful status sample ${formatRelativeTimestamp(it, nowMs)}. Values on screen may be stale."
        } ?: "Waiting for the first successful runtime sample."
    }

    !state.isEngineLoaded || state.backend.equals("NONE", ignoreCase = true) -> {
        "Binder is reachable, but the latest runtime sample does not report a loaded model or active backend."
    }

    state.thermalBand.equals("CRITICAL", ignoreCase = true) -> {
        "Thermal guard reached CRITICAL. Expect throttling or backend changes at request boundaries."
    }

    state.thermalBand.equals("HOT", ignoreCase = true) -> {
        "Thermal band is HOT. Prefer shorter bursts or a cooler backend."
    }

    state.memoryPressure.equals("EMERGENCY", ignoreCase = true) ||
        state.memoryPressure.equals("CRITICAL", ignoreCase = true) -> {
        "Memory pressure is elevated. Session capacity and prefill reliability may degrade."
    }

    else -> "Binder, engine, and runtime samples are arriving normally."
}

private fun testBadgeLabel(state: DashboardUiState): String = when {
    state.isTestRunning -> "RUNNING"
    state.testStatus.isBlank() -> "IDLE"
    state.testStatusTone == DashboardMessageTone.SUCCESS -> "PASS"
    state.testStatusTone == DashboardMessageTone.WARNING -> "WARN"
    state.testStatusTone == DashboardMessageTone.ERROR -> "FAIL"
    else -> "READY"
}

@Composable
fun DashboardScreen(
    state: DashboardUiState,
    onTestInference: () -> Unit = {},
    onNavigateToHistory: () -> Unit = {},
    onNavigateToLogs: () -> Unit = {},
){
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { HeaderSection(state) }
            item { ServiceHealthCard(state) }
            item { ActiveSessionsCard(state) }
            item { SessionHistoryCard(onNavigateToHistory) }
            item { RecentLogsNavigationCard(onNavigateToLogs) }
            item { TestInferenceCard(state, onTestInference) }
            item { EngineStatusCard(state) }
            item { ThermalStatusCard(state) }
            item { MemoryCard(state) }
        }
    }
}

@Composable
private fun HeaderSection(state: DashboardUiState) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Mindlayer",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "Single-screen operations console for service health, logs, and test inference.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.modelId.isNotBlank()) {
            Text(
                text = modelDisplayName(state.modelId),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ServiceHealthCard(state: DashboardUiState) {
    val nowMs = System.currentTimeMillis()
    val health = state.serviceHealth(nowMs)
    val healthTint = healthColor(health)
    val freshness = state.statusFreshness(nowMs)
    val modelTone = when {
        state.isEngineLoaded -> DashboardMessageTone.SUCCESS
        state.connectionState == DashboardConnectionState.DISCONNECTED -> DashboardMessageTone.ERROR
        else -> DashboardMessageTone.WARNING
    }

    DashboardCard(title = "Service Health") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = healthHeadline(state, health),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = healthTint,
                    )
                    Text(
                        text = healthDetail(state, nowMs),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusDot(healthTint)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Badge(connectionLabel(state.connectionState), connectionColor(state.connectionState))
                Badge(
                    text = if (state.isEngineLoaded) "MODEL LOADED" else "MODEL NOT READY",
                    color = toneColor(modelTone),
                )
                Badge("STATUS ${freshnessLabel(freshness)}", freshnessColor(freshness))
            }

            if (state.statusErrorMessage != null &&
                state.connectionState == DashboardConnectionState.CONNECTED
            ) {
                DiagnosticCallout(
                    message = state.statusErrorMessage,
                    tone = DashboardMessageTone.ERROR,
                )
            }

            LabelValue("Backend", state.backend.ifBlank { "NONE" })
            LabelValue(
                "Last status sample",
                formatSampleTime(state.lastStatusUpdateMs, nowMs, "No successful sample yet"),
            )
            LabelValue(
                "Last log refresh",
                formatSampleTime(
                    state.lastLogsUpdateMs,
                    nowMs,
                    if (state.isLogsLoading) "Loading…" else "No log refresh yet",
                ),
            )
        }
    }
}

@Composable
private fun EngineStatusCard(state: DashboardUiState) {
    val engineTone = when {
        state.isEngineLoaded -> DashboardMessageTone.SUCCESS
        state.connectionState == DashboardConnectionState.DISCONNECTED -> DashboardMessageTone.ERROR
        else -> DashboardMessageTone.WARNING
    }

    DashboardCard(title = "Engine Details") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Badge(
                    text = if (state.isEngineLoaded) "LOADED" else "NOT LOADED",
                    color = toneColor(engineTone),
                )
                Badge(text = state.backend.uppercase(), color = backendColor(state.backend))
            }

            if (!state.isEngineLoaded || state.backend.equals("NONE", ignoreCase = true)) {
                DiagnosticCallout(
                    message = "Latest runtime sample does not show a loaded model. Treat earlier test output as historical until a fresh healthy sample arrives.",
                    tone = DashboardMessageTone.WARNING,
                )
            }

            LabelValue(
                "Model",
                state.modelId.takeIf { it.isNotBlank() }?.let(::modelDisplayName) ?: "Not reported",
            )
            LabelValue(
                "Init time",
                if (state.initTimeSeconds > 0f) "%.1fs".format(state.initTimeSeconds) else "Not reported",
            )
            LabelValue("Uptime", formatUptime(state.uptimeMs))
        }
    }
}

@Composable
private fun ThermalStatusCard(state: DashboardUiState) {
    DashboardCard(title = "Thermal Guard") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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

            if (state.thermalBand.equals("HOT", ignoreCase = true) ||
                state.thermalBand.equals("CRITICAL", ignoreCase = true)
            ) {
                DiagnosticCallout(
                    message = "Thermal control is actively limiting sustained work. " +
                        "Expect reduced throughput until the device cools.",
                    tone = if (state.thermalBand.equals("CRITICAL", ignoreCase = true)) {
                        DashboardMessageTone.ERROR
                    } else {
                        DashboardMessageTone.WARNING
                    },
                )
            }

            LabelValue(
                "Headroom",
                state.headroom?.let { "%.2f".format(it) } ?: "Not reported",
            )
        }
    }
}

@Composable
private fun MemoryCard(state: DashboardUiState) {
    val fraction = if (state.totalRamMb > 0) {
        state.availableRamMb.toFloat() / state.totalRamMb.toFloat()
    } else {
        0f
    }

    DashboardCard(title = "Memory Headroom") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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

            if (state.memoryPressure.equals("CRITICAL", ignoreCase = true) ||
                state.memoryPressure.equals("EMERGENCY", ignoreCase = true)
            ) {
                DiagnosticCallout(
                    message = "Low RAM headroom may block new sessions or large prefills.",
                    tone = if (state.memoryPressure.equals("EMERGENCY", ignoreCase = true)) {
                        DashboardMessageTone.ERROR
                    } else {
                        DashboardMessageTone.WARNING
                    },
                )
            }

            LabelValue(
                "Available RAM",
                if (state.totalRamMb > 0) {
                    "${state.availableRamMb} MB of ${state.totalRamMb} MB"
                } else {
                    "Awaiting memory sample"
                },
            )

            if (state.totalRamMb > 0) {
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = pressureColor(state.memoryPressure),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }

            LabelValue("Max sessions", "${state.maxSessions}")
        }
    }
}

@Composable
private fun ActiveSessionsCard(state: DashboardUiState) {
    val nowMs = System.currentTimeMillis()

    DashboardCard(title = "Active Sessions (${state.activeSessions.size})") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                state.connectionState == DashboardConnectionState.CONNECTING &&
                    state.activeSessions.isEmpty() -> {
                    DiagnosticCallout(
                        message = "Waiting for the first live session sample…",
                        tone = DashboardMessageTone.INFO,
                    )
                }

                state.connectionState == DashboardConnectionState.DISCONNECTED &&
                    state.activeSessions.isEmpty() -> {
                    DiagnosticCallout(
                        message = "Session list is unavailable while the service is disconnected.",
                        tone = DashboardMessageTone.WARNING,
                    )
                }

                state.activeSessions.isEmpty() -> {
                    Text(
                        text = "No active sessions in the latest status sample.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Last sampled ${formatSampleTime(state.lastStatusUpdateMs, nowMs, "never")}.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    state.activeSessions.forEachIndexed { index, session ->
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 2.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                        SessionRow(session)
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(session: SessionUiItem) {
    val tokenFraction = if (session.maxTokens > 0) {
        session.tokenCount.toFloat() / session.maxTokens.toFloat()
    } else {
        0f
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = session.sessionId,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Last active ${session.lastAccessedLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Badge(text = session.backend.uppercase(), color = backendColor(session.backend))
                Badge(
                    text = if (session.isStreaming) "STREAMING" else "IDLE",
                    color = if (session.isStreaming) CategoryInference else CategoryDefault,
                )
            }
        }

        Text(
            text = "${session.tokenCount}/${session.maxTokens} tokens",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { tokenFraction.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = backendColor(session.backend),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

@Composable
private fun SessionHistoryCard(onNavigateToHistory: () -> Unit) {
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

@Composable
private fun TestInferenceCard(state: DashboardUiState, onTestInference: () -> Unit) {
    val nowMs = System.currentTimeMillis()
    val displayTone = if (state.isTestRunning) DashboardMessageTone.INFO else state.testStatusTone

    DashboardCard(title = "Test Inference") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
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
                    Text(if (state.isTestRunning) "Running…" else "Run Test Prompt")
                }
                Badge(text = testBadgeLabel(state), color = toneColor(displayTone))
            }

            if (state.testStatus.isNotBlank()) {
                DiagnosticCallout(
                    message = state.testStatus,
                    tone = displayTone,
                )
            } else {
                Text(
                    text = "Run a prompt to verify AIDL control flow and pipe streaming end to end.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.lastTestCompletedAtMs != null && !state.isTestRunning) {
                Text(
                    text = "Last run ${formatRelativeTimestamp(state.lastTestCompletedAtMs, nowMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.shouldHighlightTestResult(nowMs)) {
                DiagnosticCallout(
                    message = "Current engine state is not ready. Treat the last successful test output as historical until you rerun after recovery.",
                    tone = DashboardMessageTone.WARNING,
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "Latest output",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = state.testOutput.ifBlank {
                            "No streaming output captured yet. Successful runs will show token deltas here."
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp, max = 260.dp)
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

@Composable
private fun RecentLogsNavigationCard(onNavigateToLogs: () -> Unit) {
    ElevatedCard(
        onClick = onNavigateToLogs,
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
                    text = "Recent Logs",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Browse diagnostics and event log entries",
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

@Composable
private fun DiagnosticCallout(message: String, tone: DashboardMessageTone) {
    val accent = toneColor(tone)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = accent.copy(alpha = 0.12f),
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = accent,
        )
    }
}

@Composable
private fun DashboardCard(
    title: String,
    content: @Composable () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
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
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value.ifBlank { "—" },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(
        modifier = Modifier
            .size(12.dp)
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

private val PreviewNow = System.currentTimeMillis()

private val PreviewState = DashboardUiState(
    connectionState = DashboardConnectionState.CONNECTED,
    isStatusLoading = false,
    isLogsLoading = false,
    lastStatusUpdateMs = PreviewNow - 2_000,
    lastLogsUpdateMs = PreviewNow - 4_000,
    isEngineLoaded = true,
    backend = "GPU",
    initTimeSeconds = 2.3f,
    uptimeMs = 3_723_000,
    modelId = "gemma-4-E2B-it",
    thermalBand = "WARM",
    headroom = 0.78f,
    memoryPressure = "NORMAL",
    availableRamMb = 4_200,
    totalRamMb = 7_800,
    maxSessions = 2,
    activeSessions = listOf(
        SessionUiItem(
            sessionId = "a1b2c3d4…",
            backend = "GPU",
            tokenCount = 1_024,
            maxTokens = 4_096,
            isStreaming = true,
            lastAccessedLabel = "2s ago",
        ),
        SessionUiItem(
            sessionId = "f0e1d2c3…",
            backend = "CPU",
            tokenCount = 256,
            maxTokens = 4_096,
            isStreaming = false,
            lastAccessedLabel = "45s ago",
        ),
    ),
    recentLogs = listOf(
        LogUiItem("2m ago", "INFERENCE", "Generation complete", "session=a1b2c3d4 • tokens=1024 • speed=121.9 tok/s"),
        LogUiItem("2m ago", "SESSION", "Session created", "session=a1b2c3d4 • backend=GPU"),
        LogUiItem("5m ago", "THERMAL", "Band changed", "band=WARM • backend=GPU"),
        LogUiItem("12m ago", "MEMORY", "Snapshot", "duration=120ms • tokens=0"),
        LogUiItem("15m ago", "ERROR", "Inference failed", "session=f0e1d2c3 • error=OOM in prefill"),
    ),
    testStatus = "Completed • 14 event(s) • finish=stop",
    testOutput = "Hello! I am Mindlayer running a local diagnostic prompt.\n\nThis output area now keeps the latest streaming transcript visible for debugging.",
    testStatusTone = DashboardMessageTone.SUCCESS,
    lastTestCompletedAtMs = PreviewNow - 15_000,
)

@Preview(showBackground = true, widthDp = 400, heightDp = 960)
@Composable
private fun DashboardScreenPreview() {
    MaterialTheme {
        DashboardScreen(state = PreviewState)
    }
}

@Preview(showBackground = true, widthDp = 400, heightDp = 700, name = "Disconnected")
@Composable
private fun DashboardScreenEmptyPreview() {
    MaterialTheme {
        DashboardScreen(
            state = DashboardUiState(
                connectionState = DashboardConnectionState.DISCONNECTED,
                isStatusLoading = false,
                isLogsLoading = false,
                lastStatusUpdateMs = PreviewNow - 75_000,
                statusErrorMessage = "Binder connection lost. Last good status sample 75s ago.",
                logsErrorMessage = "Log polling failed: database unavailable.",
                backend = "NONE",
                totalRamMb = 7_800,
                availableRamMb = 6_100,
                maxSessions = 2,
                testStatus = "Completed • 9 event(s) • finish=stop",
                testStatusTone = DashboardMessageTone.SUCCESS,
                lastTestCompletedAtMs = PreviewNow - 180_000,
                testOutput = "Previous response from a healthy run.",
            ),
        )
    }
}
