package com.adsamcik.mindlayer.service.ui

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsamcik.mindlayer.service.logging.LogRepository
import com.adsamcik.mindlayer.service.ui.theme.MindlayerColors
import com.adsamcik.mindlayer.service.ui.theme.MindlayerTheme
import com.adsamcik.mindlayer.service.ui.theme.MindlayerType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val CategoryInference = Color(0xFF1565C0)
private val CategoryThermal = Color(0xFFE65100)
private val CategorySession = Color(0xFF2E7D32)
private val CategoryMemory = Color(0xFF7B1FA2)
private val CategoryError = Color(0xFFC62828)
private val CategoryDefault = Color(0xFF616161)

@Composable
private fun thermalColor(band: String): Color = MindlayerColors.Thermal.color(band)

@Composable
private fun pressureColor(pressure: String): Color = MindlayerColors.Pressure.color(pressure)

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
    DashboardHealthLevel.DEGRADED -> MindlayerColors.Warning.color
    DashboardHealthLevel.ERROR -> MaterialTheme.colorScheme.error
}

@Composable
private fun toneColor(tone: DashboardMessageTone): Color = when (tone) {
    DashboardMessageTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    DashboardMessageTone.INFO -> MaterialTheme.colorScheme.secondary
    DashboardMessageTone.SUCCESS -> MaterialTheme.colorScheme.primary
    DashboardMessageTone.WARNING -> MindlayerColors.Warning.color
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
    DashboardFreshness.STALE -> MindlayerColors.Warning.color
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

private fun accessibilityBandLabel(value: String): String =
    value.lowercase().replaceFirstChar {
        if (it.isLowerCase()) it.titlecase() else it.toString()
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    onTestInference: () -> Unit = {},
    onNavigateToHistory: () -> Unit = {},
    onNavigateToLogs: () -> Unit = {},
    logRepository: LogRepository? = null,
    /**
     * F-055: cross-process revoke hook. The activity wires this to
     * [DashboardViewModel.revokeApp] so a tap in the Allowed Apps card
     * goes through the `:ml` AIDL path and tears down owned sessions.
     */
    onRevokeApp: ((packageName: String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }
    val pullState = rememberPullToRefreshState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                scope.launch {
                    isRefreshing = true
                    // UI-only refresh: recreate activity to re-bind and re-poll
                    (context as? Activity)?.recreate()
                    delay(500)
                    isRefreshing = false
                }
            },
            state = pullState,
        ) {
            val safeInsets = WindowInsets.safeDrawing.asPaddingValues()
            LazyColumn(
                contentPadding = PaddingValues(
                    start = safeInsets.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr) + 16.dp,
                    end = safeInsets.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr) + 16.dp,
                    top = safeInsets.calculateTopPadding() + 12.dp,
                    bottom = safeInsets.calculateBottomPadding() + 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { CardEnterAnimation(0) { DashboardHero(state) } }
                item { CardEnterAnimation(1) { StatusSection(state) } }
                item { CardEnterAnimation(2) { ThermalMemoryRow(state) } }
                item { CardEnterAnimation(3) { ActiveSessionsCard(state) } }
                item { CardEnterAnimation(4) { ActivityNavigationCard(onNavigateToHistory, onNavigateToLogs) } }
                item { CardEnterAnimation(5) { TestInferenceCard(state, onTestInference) } }
                item {
                    CardEnterAnimation(6) {
                        AllowedAppsCard(
                            logRepository = logRepository,
                            onRevokeAidl = onRevokeApp,
                        )
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

// ── Hero ─────────────────────────────────────────────────────────────────────

@Composable
private fun DashboardHero(state: DashboardUiState) {
    val nowMs = System.currentTimeMillis()
    val health = state.serviceHealth(nowMs)
    val healthTint = healthColor(health)
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.3f
    val showBanner = health == DashboardHealthLevel.DEGRADED || health == DashboardHealthLevel.ERROR

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Mindlayer",
                style = MaterialTheme.typography.headlineLarge.copy(
                    letterSpacing = 1.2.sp,
                ),
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
            )
            if (!showBanner) {
                Row(
                    modifier = Modifier.semantics(mergeDescendants = true) {
                        contentDescription = "Service health"
                        stateDescription = accessibilityBandLabel(health.name)
                    },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = health.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = healthTint,
                        fontWeight = FontWeight.SemiBold,
                    )
                    StatusDot(healthTint, description = "Service health ${health.name.lowercase()}")
                }
            }
        }

        if (showBanner) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = healthTint.copy(alpha = if (darkTheme) 0.15f else 0.10f),
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    color = healthTint.copy(alpha = 0.25f),
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = "Service health alert",
                        modifier = Modifier.size(20.dp),
                        tint = healthTint,
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = health.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = healthTint,
                        )
                        Text(
                            text = healthHeadline(state, health),
                            style = MaterialTheme.typography.bodySmall,
                            color = healthTint,
                        )
                    }
                    StatusDot(
                        color = healthTint,
                        pulse = true,
                        description = "Service health ${health.name.lowercase()}",
                    )
                }
            }
        }

        if (state.modelId.isNotBlank()) {
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        text = modelDisplayName(state.modelId),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Build,
                        contentDescription = "Loaded model",
                        modifier = Modifier.size(AssistChipDefaults.IconSize),
                    )
                },
            )
        }
    }
}

// ── Status section (Service Health + Engine Details merged) ──────────────────

@Composable
private fun StatusSection(state: DashboardUiState) {
    val nowMs = System.currentTimeMillis()
    val health = state.serviceHealth(nowMs)
    val healthTint = healthColor(health)
    val freshness = state.statusFreshness(nowMs)
    val engineTone = when {
        state.isEngineLoaded -> DashboardMessageTone.SUCCESS
        state.connectionState == DashboardConnectionState.DISCONNECTED -> DashboardMessageTone.ERROR
        else -> DashboardMessageTone.WARNING
    }

    DashboardCard(title = "Service Status", icon = Icons.Filled.Info) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Health headline row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Service health"
                        stateDescription = accessibilityBandLabel(health.name)
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = healthHeadline(state, health),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = healthTint,
                    )
                    Text(
                        text = healthDetail(state, nowMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                StatusDot(
                    healthTint,
                    pulse = health == DashboardHealthLevel.DEGRADED || health == DashboardHealthLevel.ERROR,
                    description = "Service health ${health.name.lowercase()}",
                )
            }

            // Key badges — only show what adds information; suppress redundant ones
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Badge(connectionLabel(state.connectionState), connectionColor(state.connectionState))
                Badge(
                    text = if (state.isEngineLoaded) "LOADED" else "NOT READY",
                    color = toneColor(engineTone),
                )
                if (freshness == DashboardFreshness.STALE) {
                    Badge("STALE", freshnessColor(freshness))
                }
            }

            if (state.statusErrorMessage != null &&
                state.connectionState == DashboardConnectionState.CONNECTED
            ) {
                DiagnosticCallout(message = state.statusErrorMessage, tone = DashboardMessageTone.ERROR)
            }

            // F-074: surface the crash-loop watchdog throttle banner.
            // Distinct from statusErrorMessage so it shows alongside any
            // other failure context. Hidden while the recorder reports
            // healthy.
            if (state.serviceThrottled) {
                val secs = state.throttleCooldownSecondsRemaining
                val deathCount = state.recentDeathCount
                val message = if (secs > 0) {
                    "Service throttled — cooling down (${secs}s) " +
                        "after $deathCount recent crash${if (deathCount == 1) "" else "es"}."
                } else {
                    "Service throttled after $deathCount recent crash" +
                        "${if (deathCount == 1) "" else "es"} — retrying soon."
                }
                DiagnosticCallout(
                    message = message,
                    tone = DashboardMessageTone.ERROR,
                )
            }

            // Engine detail grid — 2 columns to save vertical space
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    LabelValue("Backend", state.backend.ifBlank { "NONE" })
                    LabelValue(
                        "Init time",
                        if (state.initTimeSeconds > 0f) "%.1fs".format(state.initTimeSeconds) else "—",
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    LabelValue("Uptime", formatUptime(state.uptimeMs))
                    LabelValue(
                        "Sampled",
                        formatSampleTime(state.lastStatusUpdateMs, nowMs, "never"),
                    )
                }
            }

            if (!state.isEngineLoaded || state.backend.equals("NONE", ignoreCase = true)) {
                DiagnosticCallout(
                    message = "Runtime sample does not show a loaded model. Earlier test output is historical.",
                    tone = DashboardMessageTone.WARNING,
                )
            }

            state.gpuFailureReason?.let { reason ->
                if (state.backend.equals("CPU", ignoreCase = true)) {
                    Text(
                        text = "⚠ GPU init failed: $reason",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

// ── Thermal + Memory 2-column row ─────────────────────────────────────────────

@Composable
private fun ThermalMemoryRow(state: DashboardUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ThermalMiniCard(state, modifier = Modifier.weight(1f).fillMaxHeight())
        MemoryMiniCard(state, modifier = Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun ThermalMiniCard(state: DashboardUiState, modifier: Modifier = Modifier) {
    val tint = thermalColor(state.thermalBand)
    val isHot = state.thermalBand.equals("HOT", ignoreCase = true) ||
        state.thermalBand.equals("CRITICAL", ignoreCase = true)
    val telemetryBlind = !state.thermalTelemetryAvailable
    val headroomDescription = state.headroom?.let { "Headroom ${"%.0f".format(it * 100)} percent" }
        ?: if (telemetryBlind) {
            "Telemetry unavailable on this device"
        } else {
            "Headroom not reported"
        }

    ElevatedCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = "Thermal status"
                    stateDescription = "${accessibilityBandLabel(state.thermalBand)}. $headroomDescription"
                },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Thermal",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusDot(tint, description = "Thermal band ${state.thermalBand.lowercase()}")
                Text(
                    text = state.thermalBand.uppercase(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = tint,
                )
            }
            state.headroom?.let {
                val headroomFraction = it.coerceIn(0f, 1f)
                Text(
                    text = "Headroom",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { headroomFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = tint,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Text(
                    text = "%.0f%%".format(it * 100),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = tint,
                )
            } ?: run {
                // F-073: telemetry-blind devices (Android 8 / 8.1) have
                // no headroom readout AND no current/10s thermal status,
                // so we surface the fact explicitly instead of letting
                // "Headroom not reported" silently misrepresent the
                // service as healthy when it is actually running on a
                // conservative duty-cycle policy.
                Text(
                    text = if (telemetryBlind) {
                        "Telemetry unavailable"
                    } else {
                        "Headroom not reported"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
            if (telemetryBlind) {
                Text(
                    text = "Conservative policy active",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (isHot) {
                Text(
                    text = "Throttling active",
                    style = MaterialTheme.typography.labelSmall,
                    color = tint,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun MemoryMiniCard(state: DashboardUiState, modifier: Modifier = Modifier) {
    val tint = pressureColor(state.memoryPressure)
    val usedMb = (state.totalRamMb - state.availableRamMb).coerceAtLeast(0)
    val usedFraction = if (state.totalRamMb > 0) {
        usedMb.toFloat() / state.totalRamMb.toFloat()
    } else {
        0f
    }
    val isElevated = state.memoryPressure.equals("CRITICAL", ignoreCase = true) ||
        state.memoryPressure.equals("EMERGENCY", ignoreCase = true)

    ElevatedCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = "Memory pressure"
                    stateDescription =
                        "${accessibilityBandLabel(state.memoryPressure)}. ${formatWholeNumber(state.availableRamMb)} megabytes available"
                },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Memory",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Max sessions: ${state.maxSessions}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusDot(tint, description = "Memory pressure ${state.memoryPressure.lowercase()}")
                Text(
                    text = state.memoryPressure.uppercase(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = tint,
                )
            }
            if (state.totalRamMb > 0) {
                val usedPct = (usedFraction * 100).toInt()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LinearProgressIndicator(
                        progress = { usedFraction.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = tint,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "$usedPct%",
                        style = MindlayerType.Mono.LabelSmall,
                        fontWeight = FontWeight.Bold,
                        color = tint,
                    )
                }
                Text(
                    text = "Available: ${formatWholeNumber(state.availableRamMb)} MB",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Used: ${formatWholeNumber(usedMb)} MB / ${formatWholeNumber(state.totalRamMb)} MB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isElevated) {
                Text(
                    text = "Low headroom",
                    style = MaterialTheme.typography.labelSmall,
                    color = tint,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

// ── Active Sessions ───────────────────────────────────────────────────────────

@Composable
private fun ActiveSessionsCard(state: DashboardUiState) {
    val nowMs = System.currentTimeMillis()

    DashboardCard(
        title = "Active Sessions (${state.activeSessions.size})",
        icon = Icons.Filled.Person,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                        message = "Session list unavailable while the service is disconnected.",
                        tone = DashboardMessageTone.WARNING,
                    )
                }

                state.activeSessions.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = "No active sessions",
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                        Text(
                            text = "No active sessions",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = "Sessions will appear here when client apps connect and start inference.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = "Last sampled ${formatSampleTime(state.lastStatusUpdateMs, nowMs, "never")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                else -> {
                    state.activeSessions.forEachIndexed { index, session ->
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
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
    val backendTint = backendColor(session.backend)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Backend avatar
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(backendTint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = session.backend.take(1).uppercase(),
                style = MindlayerType.Mono.LabelMedium,
                fontWeight = FontWeight.Bold,
                color = backendTint,
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = session.sessionId,
                    modifier = Modifier.weight(1f),
                    style = MindlayerType.Mono.LabelMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (session.isStreaming) {
                    Spacer(Modifier.width(8.dp))
                    Badge(text = "LIVE", color = CategoryInference)
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = session.lastAccessedLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "·",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${session.tokenCount}/${session.maxTokens} tok",
                    style = MindlayerType.Mono.LabelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LinearProgressIndicator(
                progress = { tokenFraction.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = backendTint,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}

// ── Activity navigation (Session History + Recent Logs consolidated) ──────────

@Composable
private fun ActivityNavigationCard(
    onNavigateToHistory: () -> Unit,
    onNavigateToLogs: () -> Unit,
) {
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.3f

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (darkTheme) 0.45f else 0.35f),
    ) {
        Column {
            ListItem(
                headlineContent = {
                    Text(
                        text = "Session History",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                },
                supportingContent = {
                    Text(
                        text = "Past sessions and inference activity",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Filled.DateRange,
                        contentDescription = "Session history",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                trailingContent = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Navigate to session history",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { onNavigateToHistory() },
            )
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
            ListItem(
                headlineContent = {
                    Text(
                        text = "Recent Logs",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                },
                supportingContent = {
                    Text(
                        text = "Diagnostics and event log entries",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.List,
                        contentDescription = "Recent logs",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                trailingContent = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Navigate to recent logs",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { onNavigateToLogs() },
            )
        }
    }
}

// ── Test Inference ────────────────────────────────────────────────────────────

@Composable
private fun TestInferenceCard(state: DashboardUiState, onTestInference: () -> Unit) {
    val nowMs = System.currentTimeMillis()
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.3f
    val displayTone = when {
        state.isTestRunning -> DashboardMessageTone.INFO
        state.testStatus.isBlank() -> DashboardMessageTone.NEUTRAL
        else -> state.testStatusTone
    }

    DashboardCard(title = "Test Inference", icon = Icons.Filled.PlayArrow) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = onTestInference,
                    enabled = !state.isTestRunning,
                ) {
                    if (state.isTestRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (state.isTestRunning) "Running…" else "Run Test")
                }
                Column(horizontalAlignment = Alignment.End) {
                    Badge(text = testBadgeLabel(state), color = toneColor(displayTone))
                    state.lastTestCompletedAtMs?.let { ts ->
                        if (!state.isTestRunning) {
                            Text(
                                text = formatRelativeTimestamp(ts, nowMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }

            if (state.testStatus.isNotBlank()) {
                DiagnosticCallout(message = state.testStatus, tone = displayTone)
            } else {
                Text(
                    text = "Verify AIDL control flow and pipe streaming end to end.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(visible = state.shouldHighlightTestResult(nowMs)) {
                DiagnosticCallout(
                    message = "Engine not ready — last test output is historical until you rerun after recovery.",
                    tone = DashboardMessageTone.WARNING,
                )
            }

            val hasOutput = state.testOutput.isNotBlank()
            if (hasOutput || state.isTestRunning) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (darkTheme) 0.45f else 0.35f),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "Output",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = if (hasOutput) state.testOutput else "Waiting for output…",
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (hasOutput) {
                                        Modifier.heightIn(min = 48.dp, max = 200.dp)
                                            .verticalScroll(rememberScrollState())
                                    } else {
                                        Modifier
                                    }
                                ),
                            style = MindlayerType.Mono.BodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CardEnterAnimation(
    index: Int,
    content: @Composable () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(durationMillis = 300, delayMillis = index * 60)) +
            slideInVertically(
                animationSpec = tween(durationMillis = 300, delayMillis = index * 60),
                initialOffsetY = { it / 5 },
            ),
    ) {
        content()
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
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelSmall,
            color = accent,
        )
    }
}

@Composable
private fun DashboardCard(
    title: String,
    icon: ImageVector? = null,
    content: @Composable () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = "$title section",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
            }
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
            style = MindlayerType.Mono.LabelMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatusDot(
    color: Color,
    pulse: Boolean = false,
    description: String? = null,
) {
    val alpha = if (pulse) {
        val infiniteTransition = rememberInfiniteTransition(label = "statusDotPulse")
        val animatedAlpha by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulseAlpha",
        )
        animatedAlpha
    } else {
        1f
    }
    Box(
        modifier = Modifier
            .size(12.dp)
            .alpha(alpha)
            .then(
                if (description != null) {
                    Modifier.semantics { contentDescription = description }
                } else {
                    Modifier
                },
            )
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text = text,
        modifier = Modifier
            .background(
                color = color.copy(alpha = 0.12f),
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.Bold,
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
    MindlayerTheme {
        DashboardScreen(state = PreviewState)
    }
}

@Preview(showBackground = true, widthDp = 400, heightDp = 700, name = "Disconnected")
@Composable
private fun DashboardScreenEmptyPreview() {
    MindlayerTheme {
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
