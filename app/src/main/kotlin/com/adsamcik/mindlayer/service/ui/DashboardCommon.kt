package com.adsamcik.mindlayer.service.ui

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adsamcik.mindlayer.service.R
import com.adsamcik.mindlayer.service.ui.theme.MindlayerColors
import com.adsamcik.mindlayer.service.ui.theme.MindlayerType

// ── Category color constants ─────────────────────────────────────────────────

internal val CategoryInference = Color(0xFF1565C0)
internal val CategoryThermal = Color(0xFFE65100)
internal val CategorySession = Color(0xFF2E7D32)
internal val CategoryMemory = Color(0xFF7B1FA2)
internal val CategoryError = Color(0xFFC62828)
internal val CategoryDefault = Color(0xFF616161)

internal const val HeaderSeparatorAspectRatio = 443f / 1181f

// ── Color helpers ────────────────────────────────────────────────────────────

@Composable
internal fun thermalColor(band: String): Color = MindlayerColors.Thermal.color(band)

@Composable
internal fun pressureColor(pressure: String): Color = MindlayerColors.Pressure.color(pressure)

internal fun categoryColor(category: String): Color = when (category.uppercase()) {
    "INFERENCE" -> CategoryInference
    "THERMAL" -> CategoryThermal
    "SESSION" -> CategorySession
    "MEMORY" -> CategoryMemory
    "ERROR" -> CategoryError
    else -> CategoryDefault
}

internal fun backendColor(backend: String): Color = when (backend.uppercase()) {
    "GPU" -> CategoryInference
    "CPU" -> CategoryMemory
    "NPU" -> CategorySession
    "NONE" -> CategoryError
    else -> CategoryDefault
}

@Composable
internal fun healthColor(level: DashboardHealthLevel): Color = when (level) {
    DashboardHealthLevel.CONNECTING -> MaterialTheme.colorScheme.secondary
    DashboardHealthLevel.IDLE -> MaterialTheme.colorScheme.secondary
    DashboardHealthLevel.HEALTHY -> MaterialTheme.colorScheme.primary
    DashboardHealthLevel.DEGRADED -> MindlayerColors.Warning.color
    DashboardHealthLevel.ERROR -> MaterialTheme.colorScheme.error
}

@Composable
internal fun toneColor(tone: DashboardMessageTone): Color = when (tone) {
    DashboardMessageTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    DashboardMessageTone.INFO -> MaterialTheme.colorScheme.secondary
    DashboardMessageTone.SUCCESS -> MaterialTheme.colorScheme.primary
    DashboardMessageTone.WARNING -> MindlayerColors.Warning.color
    DashboardMessageTone.ERROR -> MaterialTheme.colorScheme.error
}

@Composable
internal fun connectionColor(connectionState: DashboardConnectionState): Color = when (connectionState) {
    DashboardConnectionState.CONNECTING -> MaterialTheme.colorScheme.secondary
    DashboardConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
    DashboardConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.error
}

@Composable
internal fun freshnessColor(freshness: DashboardFreshness): Color = when (freshness) {
    DashboardFreshness.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    DashboardFreshness.FRESH -> MaterialTheme.colorScheme.primary
    DashboardFreshness.STALE -> MindlayerColors.Warning.color
}

// ── Formatters ───────────────────────────────────────────────────────────────

internal fun formatUptime(ms: Long): String {
    val totalSeconds = (ms / 1_000).coerceAtLeast(0)
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

internal fun formatSampleTime(timestampMs: Long?, nowMs: Long, fallback: String): String =
    timestampMs?.let { formatRelativeTimestamp(it, nowMs) } ?: fallback

internal fun modelDisplayName(modelId: String): String =
    modelId.substringAfterLast('/').substringAfterLast('\\')

internal fun accessibilityBandLabel(value: String): String =
    value.lowercase().replaceFirstChar {
        if (it.isLowerCase()) it.titlecase() else it.toString()
    }

// ── Label resolvers ──────────────────────────────────────────────────────────

internal fun connectionLabel(connectionState: DashboardConnectionState): Int = when (connectionState) {
    DashboardConnectionState.CONNECTING -> R.string.dashboard_connection_connecting
    DashboardConnectionState.CONNECTED -> R.string.dashboard_connection_connected
    DashboardConnectionState.DISCONNECTED -> R.string.dashboard_connection_disconnected
}

internal fun freshnessLabel(freshness: DashboardFreshness): Int = when (freshness) {
    DashboardFreshness.UNKNOWN -> R.string.dashboard_freshness_unknown
    DashboardFreshness.FRESH -> R.string.dashboard_freshness_fresh
    DashboardFreshness.STALE -> R.string.dashboard_freshness_stale_label
}

// ── Health / test badge label resolvers ──────────────────────────────────────

@Composable
internal fun healthHeadline(state: DashboardUiState, health: DashboardHealthLevel): String = when {
    state.connectionState == DashboardConnectionState.DISCONNECTED ->
        stringResource(R.string.dashboard_health_service_disconnected)
    state.statusErrorMessage != null && state.connectionState == DashboardConnectionState.CONNECTED ->
        stringResource(R.string.dashboard_health_status_polling_failed)

    health == DashboardHealthLevel.CONNECTING ->
        stringResource(R.string.dashboard_health_connecting_to_service)
    health == DashboardHealthLevel.IDLE ->
        stringResource(R.string.dashboard_health_engine_idle)
    health == DashboardHealthLevel.DEGRADED ->
        stringResource(R.string.dashboard_health_service_needs_attention)
    else -> stringResource(R.string.dashboard_health_service_ready)
}

@Composable
internal fun healthDetail(state: DashboardUiState, nowMs: Long, health: DashboardHealthLevel): String = when {
    state.connectionState == DashboardConnectionState.DISCONNECTED -> {
        state.lastStatusUpdateMs?.let {
            stringResource(
                R.string.dashboard_health_detail_disconnected_with_sample,
                formatRelativeTimestamp(it, nowMs),
            )
        } ?: stringResource(R.string.dashboard_health_detail_disconnected_no_sample)
    }

    state.statusErrorMessage != null && state.connectionState == DashboardConnectionState.CONNECTED -> {
        stringResource(R.string.dashboard_health_detail_polling_failed)
    }

    state.statusFreshness(nowMs) == DashboardFreshness.STALE -> {
        state.lastStatusUpdateMs?.let {
            stringResource(
                R.string.dashboard_health_detail_stale_with_sample,
                formatRelativeTimestamp(it, nowMs),
            )
        } ?: stringResource(R.string.dashboard_health_detail_waiting_first_sample)
    }

    health == DashboardHealthLevel.IDLE -> {
        stringResource(R.string.dashboard_health_detail_engine_idle)
    }

    state.thermalBand.equals("CRITICAL", ignoreCase = true) -> {
        stringResource(R.string.dashboard_health_detail_thermal_critical)
    }

    state.thermalBand.equals("HOT", ignoreCase = true) -> {
        stringResource(R.string.dashboard_health_detail_thermal_hot)
    }

    state.memoryPressure.equals("EMERGENCY", ignoreCase = true) ||
        state.memoryPressure.equals("CRITICAL", ignoreCase = true) -> {
        stringResource(R.string.dashboard_health_detail_memory_elevated)
    }

    else -> stringResource(R.string.dashboard_health_detail_ok)
}

@Composable
internal fun testBadgeLabel(state: DashboardUiState): String = when {
    state.isTestRunning -> stringResource(R.string.dashboard_test_badge_running)
    state.testStatus.isBlank() -> stringResource(R.string.dashboard_test_badge_idle)
    state.testStatusTone == DashboardMessageTone.SUCCESS -> stringResource(R.string.dashboard_test_badge_pass)
    state.testStatusTone == DashboardMessageTone.WARNING -> stringResource(R.string.dashboard_test_badge_warn)
    state.testStatusTone == DashboardMessageTone.ERROR -> stringResource(R.string.dashboard_test_badge_fail)
    else -> stringResource(R.string.dashboard_test_badge_ready)
}

@Composable
internal fun engineTestBadgeLabel(test: EngineTestState): String = when {
    test.isRunning -> stringResource(R.string.dashboard_test_badge_running)
    test.status.isBlank() -> stringResource(R.string.dashboard_test_badge_idle)
    test.tone == DashboardMessageTone.SUCCESS -> stringResource(R.string.dashboard_test_badge_pass)
    test.tone == DashboardMessageTone.WARNING -> stringResource(R.string.dashboard_test_badge_warn)
    test.tone == DashboardMessageTone.ERROR -> stringResource(R.string.dashboard_test_badge_fail)
    else -> stringResource(R.string.dashboard_test_badge_ready)
}

// ── describeInitFailure (kept internal for tests) ────────────────────────────

/**
 * F-077: render an [com.adsamcik.mindlayer.service.engine.InitFailure] as
 * a `(tone, message)` pair for the dashboard's StatusSection callout.
 *
 * Visible (`internal`) for testing; the table is the contract that pins
 * which variant maps to which user-facing copy.
 */
internal fun describeInitFailure(
    failure: com.adsamcik.mindlayer.service.engine.InitFailure,
    currentBackend: String,
): Pair<DashboardMessageTone, String> = when (failure) {
    com.adsamcik.mindlayer.service.engine.InitFailure.LowMemory -> {
        DashboardMessageTone.ERROR to
            "Engine init refused: insufficient memory. Free up memory and retry."
    }
    com.adsamcik.mindlayer.service.engine.InitFailure.ModelMissing -> {
        DashboardMessageTone.ERROR to
            "Model file missing — download it from the Models tab."
    }
    com.adsamcik.mindlayer.service.engine.InitFailure.IntegrityMismatch -> {
        DashboardMessageTone.ERROR to
            "Model file corrupted — reinstall."
    }
    is com.adsamcik.mindlayer.service.engine.InitFailure.BackendUnavailable -> {
        val recovered = currentBackend.isNotBlank() &&
            !currentBackend.equals("NONE", ignoreCase = true) &&
            !currentBackend.equals(failure.backend, ignoreCase = true)
        if (recovered) {
            DashboardMessageTone.WARNING to
                "${failure.backend} backend failed (${failure.safeLabel}) — running on $currentBackend."
        } else {
            DashboardMessageTone.ERROR to
                "${failure.backend} backend failed (${failure.safeLabel})."
        }
    }
    is com.adsamcik.mindlayer.service.engine.InitFailure.NativeError -> {
        DashboardMessageTone.ERROR to
            "Native runtime error (${failure.safeLabel})."
    }
}

// ── Composable atoms ─────────────────────────────────────────────────────────

@Composable
internal fun Badge(text: String, color: Color) {
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

@Composable
internal fun StatusDot(
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
internal fun DiagnosticCallout(message: String, tone: DashboardMessageTone) {
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
internal fun DashboardCard(
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
                        contentDescription = stringResource(R.string.dashboard_a11y_card_section, title),
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
internal fun LabelValue(label: String, value: String) {
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
            text = value.ifBlank { stringResource(R.string.dashboard_value_dash) },
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
internal fun EngineOutputBox(output: String, hasOutput: Boolean, darkTheme: Boolean) {
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
                text = stringResource(R.string.dashboard_output),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (hasOutput) output else stringResource(R.string.dashboard_test_waiting_for_output),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (hasOutput) {
                            Modifier
                                .heightIn(min = 48.dp, max = 200.dp)
                                .verticalScroll(rememberScrollState())
                        } else {
                            Modifier
                        },
                    ),
                style = MindlayerType.Mono.BodySmall,
            )
        }
    }
}

@Composable
internal fun CardEnterAnimation(
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
