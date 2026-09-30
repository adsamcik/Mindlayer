package com.adsamcik.mindlayer.service.ui

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsamcik.mindlayer.service.R
import com.adsamcik.mindlayer.service.logging.LogRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(
    state: DashboardUiState,
    onNavigateToTroubleshoot: () -> Unit = {},
    onNavigateToModels: () -> Unit = {},
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
            LazyColumn(
                contentPadding = MindlayerScreenDefaults.ContentPadding,
                verticalArrangement = Arrangement.spacedBy(MindlayerScreenDefaults.ItemSpacing),
            ) {
                item { CardEnterAnimation(0) { DashboardHero() } }
                item { CardEnterAnimation(1) { StatusOverviewCard(state) } }
                item {
                    DashboardShortcut(
                        title = stringResource(if (state.needsModelSetup()) R.string.nav_models else R.string.nav_tests),
                        icon = Icons.Filled.Build,
                        contentDescription = stringResource(if (state.needsModelSetup()) R.string.nav_models else R.string.nav_tests),
                        onClick = if (state.needsModelSetup()) onNavigateToModels else onNavigateToTroubleshoot,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (state.activeSessions.isNotEmpty()) {
                    item { CardEnterAnimation(3) { ActiveSessionsCard(state) } }
                }
                item {
                    CardEnterAnimation(6) {
                        AllowedAppsCard(
                            logRepository = logRepository,
                            onRevokeAidl = onRevokeApp,
                        )
                    }
                }
                item { CardEnterAnimation(7) { RuntimeDetailsCard(state) } }
                item { Spacer(Modifier.height(MindlayerScreenDefaults.BottomSpacerHeight)) }
            }
        }
    }
}

// ── Hero ─────────────────────────────────────────────────────────────────────

@Composable
private fun DashboardHero() {
    DashboardWordmark(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    )
}

@Composable
private fun DashboardWordmark(modifier: Modifier = Modifier) {
    val headlineStyle = MaterialTheme.typography.headlineMedium.copy(
        letterSpacing = 0.sp,
    )
    val separatorHeight = with(LocalDensity.current) { headlineStyle.fontSize.toDp() }
    val wordmarkLabel = stringResource(R.string.dashboard_app_title)
    val mindText = stringResource(R.string.dashboard_brand_mind)
    val layerText = stringResource(R.string.dashboard_brand_layer)

    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = wordmarkLabel
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = mindText,
            style = headlineStyle,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
        Image(
            painter = painterResource(R.drawable.ic_mindlayer_header_separator),
            contentDescription = null,
            modifier = Modifier
                .height(separatorHeight)
                .width(separatorHeight * HeaderSeparatorAspectRatio),
        )
        Text(
            text = layerText,
            style = headlineStyle,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
    }
}

// ── At-a-glance status ───────────────────────────────────────────────────────

@Composable
private fun StatusOverviewCard(state: DashboardUiState) {
    val nowMs = System.currentTimeMillis()
    val health = state.serviceHealth(nowMs)
    val healthTint = healthColor(health)
    val containerColor = when (health) {
        DashboardHealthLevel.HEALTHY -> MaterialTheme.colorScheme.primaryContainer
        DashboardHealthLevel.IDLE -> MaterialTheme.colorScheme.secondaryContainer
        DashboardHealthLevel.CONNECTING -> MaterialTheme.colorScheme.surfaceContainerHigh
        DashboardHealthLevel.DEGRADED -> MaterialTheme.colorScheme.tertiaryContainer
        DashboardHealthLevel.ERROR -> MaterialTheme.colorScheme.errorContainer
    }
    val serviceHealthLabel = stringResource(R.string.dashboard_a11y_service_health)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = serviceHealthLabel
                stateDescription = accessibilityBandLabel(health.name)
            },
        shape = MaterialTheme.shapes.extraLarge,
        color = containerColor,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(MindlayerScreenDefaults.CardContentPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(healthTint.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = when (health) {
                            DashboardHealthLevel.HEALTHY,
                            DashboardHealthLevel.IDLE,
                            -> Icons.Filled.CheckCircle
                            DashboardHealthLevel.CONNECTING -> Icons.Filled.Info
                            DashboardHealthLevel.DEGRADED,
                            DashboardHealthLevel.ERROR,
                            -> Icons.Filled.Warning
                        },
                        contentDescription = null,
                        modifier = Modifier.size(26.dp),
                        tint = healthTint,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        text = healthHeadline(state, health),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (health != DashboardHealthLevel.HEALTHY) {
                        Text(
                            text = healthDetail(state, nowMs, health),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.56f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = healthTint,
                    )
                    Text(
                        text = pluralStringResource(
                            R.plurals.dashboard_apps_using_now,
                            state.activeSessions.size,
                            state.activeSessions.size,
                        ),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    state.lastStatusUpdateMs?.let { sampledAt ->
                        Text(
                            text = stringResource(
                                R.string.dashboard_updated_short,
                                formatRelativeTimestamp(sampledAt, nowMs),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (state.statusErrorMessage != null &&
                state.connectionState == DashboardConnectionState.CONNECTED
            ) {
                DiagnosticCallout(message = state.statusErrorMessage, tone = DashboardMessageTone.ERROR)
            }

            if (state.serviceThrottled) {
                val seconds = state.throttleCooldownSecondsRemaining
                val deathCount = state.recentDeathCount
                DiagnosticCallout(
                    message = if (seconds > 0) {
                        pluralStringResource(
                            R.plurals.dashboard_throttle_cooldown,
                            deathCount,
                            seconds,
                            deathCount,
                        )
                    } else {
                        pluralStringResource(
                            R.plurals.dashboard_throttle_retrying,
                            deathCount,
                            deathCount,
                        )
                    },
                    tone = DashboardMessageTone.ERROR,
                )
            }

            val initFailure = state.lastInitFailure
            if (initFailure != null) {
                val (tone, message) = describeInitFailure(initFailure, state.backend)
                DiagnosticCallout(message = message, tone = tone)
            } else if (
                state.gpuFailureReason != null &&
                state.backend.equals("CPU", ignoreCase = true)
            ) {
                DiagnosticCallout(
                    message = stringResource(R.string.dashboard_acceleration_fallback),
                    tone = DashboardMessageTone.WARNING,
                )
            }
        }
    }
}

@Composable
private fun RuntimeDetailsCard(state: DashboardUiState) {
    val nowMs = System.currentTimeMillis()
    var expanded by rememberSaveable { mutableStateOf(false) }
    val expansionState = stringResource(
        if (expanded) R.string.dashboard_details_expanded else R.string.dashboard_details_collapsed,
    )
    val acceleratorDecisions = state.acceleratorDecisions.ifEmpty {
        state.acceleratorDecision?.let(::listOf).orEmpty()
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .semantics(mergeDescendants = true) {
                        stateDescription = expansionState
                    }
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Build,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.dashboard_technical_details),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier
                        .size(22.dp)
                        .rotate(if (expanded) 90f else 0f),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    DeviceStatusRow(state)
                    if (state.modelId.isNotBlank()) {
                        LabelValue(
                            stringResource(R.string.dashboard_label_model),
                            modelDisplayName(state.modelId),
                        )
                    }
                    LabelValue(
                        stringResource(R.string.dashboard_label_backend),
                        state.backend.ifBlank { stringResource(R.string.dashboard_backend_none) },
                    )
                    LabelValue(
                        stringResource(R.string.dashboard_label_init_time),
                        if (state.initTimeSeconds > 0f) {
                            stringResource(R.string.dashboard_init_time_seconds, state.initTimeSeconds)
                        } else {
                            stringResource(R.string.dashboard_value_dash)
                        },
                    )
                    LabelValue(stringResource(R.string.dashboard_label_uptime), formatUptime(state.uptimeMs))
                    LabelValue(
                        stringResource(R.string.dashboard_label_sampled),
                        formatSampleTime(
                            state.lastStatusUpdateMs,
                            nowMs,
                            stringResource(R.string.dashboard_sampled_never),
                        ),
                    )
                    LabelValue(
                        stringResource(R.string.dashboard_label_session_limit),
                        state.maxSessions.toString(),
                    )

                    acceleratorDecisions.forEach { decision ->
                        val attempts = decision.attemptedSummary.takeIf { it.isNotBlank() }
                        DiagnosticCallout(
                            message = if (attempts != null) {
                                stringResource(
                                    R.string.dashboard_accelerator_decision_with_attempts,
                                    decision.featureName,
                                    decision.backend,
                                    decision.reason,
                                    attempts,
                                )
                            } else {
                                stringResource(
                                    R.string.dashboard_accelerator_decision,
                                    decision.featureName,
                                    decision.backend,
                                    decision.reason,
                                )
                            },
                            tone = DashboardMessageTone.INFO,
                        )
                    }
                }
            }
        }
    }
}

// ── Device status ─────────────────────────────────────────────────────────────

@Composable
private fun DeviceStatusRow(state: DashboardUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.dashboard_device_title),
            modifier = Modifier.padding(horizontal = 4.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ThermalMiniCard(state, modifier = Modifier.weight(1f))
            MemoryMiniCard(state, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ThermalMiniCard(state: DashboardUiState, modifier: Modifier = Modifier) {
    val tint = thermalColor(state.thermalBand)
    val telemetryBlind = !state.thermalTelemetryAvailable
    val headroomDescription = state.headroom?.let {
        stringResource(R.string.dashboard_a11y_headroom_percent, "%.0f".format(it * 100))
    }
        ?: if (telemetryBlind) {
            stringResource(R.string.dashboard_a11y_telemetry_unavailable_on_device)
        } else {
            stringResource(R.string.dashboard_a11y_headroom_not_reported)
        }
    val thermalStatusLabel = stringResource(R.string.dashboard_a11y_thermal_status)
    val thermalCombined = stringResource(
        R.string.dashboard_a11y_thermal_band_with_headroom,
        accessibilityBandLabel(state.thermalBand),
        headroomDescription,
    )
    val temperatureLabel = when (state.thermalBand.uppercase()) {
        "COOL" -> stringResource(R.string.dashboard_temperature_cool)
        "WARM" -> stringResource(R.string.dashboard_temperature_warm)
        "HOT" -> stringResource(R.string.dashboard_temperature_hot)
        "CRITICAL" -> stringResource(R.string.dashboard_temperature_critical)
        else -> stringResource(R.string.dashboard_metric_unavailable)
    }
    val supportingText = when {
        telemetryBlind -> stringResource(R.string.dashboard_temperature_not_reported)
        state.thermalBand.equals("HOT", ignoreCase = true) ->
            stringResource(R.string.dashboard_temperature_hot_support)
        state.thermalBand.equals("CRITICAL", ignoreCase = true) ->
            stringResource(R.string.dashboard_temperature_critical_support)
        else -> stringResource(R.string.dashboard_temperature_ok_support)
    }

    ElevatedCard(
        modifier = modifier,
        shape = RoundedCornerShape(
            topStart = 28.dp,
            topEnd = 12.dp,
            bottomEnd = 28.dp,
            bottomStart = 12.dp,
        ),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 124.dp)
                .padding(MindlayerScreenDefaults.CardContentPadding)
                .semantics(mergeDescendants = true) {
                    contentDescription = thermalStatusLabel
                    stateDescription = thermalCombined
                },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.dashboard_temperature),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusDot(tint, description = stringResource(R.string.dashboard_a11y_thermal_band_state, state.thermalBand.lowercase()))
                Text(
                    text = temperatureLabel,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = tint,
                )
            }
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MemoryMiniCard(state: DashboardUiState, modifier: Modifier = Modifier) {
    val tint = pressureColor(state.memoryPressure)
    val memoryPressureLabel = stringResource(R.string.dashboard_a11y_memory_pressure)
    val memoryStateDescription = stringResource(
        R.string.dashboard_a11y_memory_pressure_available_mb,
        accessibilityBandLabel(state.memoryPressure),
        formatWholeNumber(state.availableRamMb),
    )
    val memoryLabel = when (state.memoryPressure.uppercase()) {
        "NORMAL" -> stringResource(R.string.dashboard_memory_plenty)
        "WARNING" -> stringResource(R.string.dashboard_memory_getting_low)
        "CRITICAL" -> stringResource(R.string.dashboard_memory_low)
        "EMERGENCY" -> stringResource(R.string.dashboard_memory_very_low)
        else -> stringResource(R.string.dashboard_metric_unavailable)
    }
    val availableLabel = if (state.totalRamMb > 0) {
        stringResource(
            R.string.dashboard_memory_available_short,
            formatMemoryAmount(state.availableRamMb),
        )
    } else {
        stringResource(R.string.dashboard_memory_not_reported)
    }

    ElevatedCard(
        modifier = modifier,
        shape = RoundedCornerShape(
            topStart = 12.dp,
            topEnd = 28.dp,
            bottomEnd = 12.dp,
            bottomStart = 28.dp,
        ),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 124.dp)
                .padding(MindlayerScreenDefaults.CardContentPadding)
                .semantics(mergeDescendants = true) {
                    contentDescription = memoryPressureLabel
                    stateDescription = memoryStateDescription
                },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.dashboard_memory),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusDot(tint, description = stringResource(R.string.dashboard_a11y_memory_pressure_state, state.memoryPressure.lowercase()))
                Text(
                    text = memoryLabel,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = tint,
                )
            }
            Text(
                text = availableLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatMemoryAmount(megabytes: Long): String = when {
    megabytes >= 1_024L -> "%.1f GB".format(megabytes / 1_024f)
    megabytes > 0L -> "${formatWholeNumber(megabytes)} MB"
    else -> "0 MB"
}

// ── Active Sessions ───────────────────────────────────────────────────────────

@Composable
private fun ActiveSessionsCard(state: DashboardUiState) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(MindlayerScreenDefaults.CardContentPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.dashboard_active_now),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Badge(
                    text = state.activeSessions.size.toString(),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            state.activeSessions.forEachIndexed { index, session ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 2.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    )
                }
                SessionRow(session, index + 1)
            }
        }
    }
}

@Composable
private fun SessionRow(session: SessionUiItem, ordinal: Int) {
    val backendTint = backendColor(session.backend)
    val sessionDescription = stringResource(
        R.string.dashboard_a11y_active_session,
        ordinal,
        session.backend,
        session.tokenCount,
        session.maxTokens,
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = sessionDescription
            },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(backendTint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = ordinal.toString(),
                style = MaterialTheme.typography.labelLarge,
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
                    text = stringResource(R.string.dashboard_session_number, ordinal),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (session.isStreaming) {
                    Spacer(Modifier.width(8.dp))
                    Badge(text = stringResource(R.string.dashboard_live), color = CategoryInference)
                }
            }
            Text(
                text = stringResource(R.string.dashboard_last_active_short, session.lastAccessedLabel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Activity navigation (Session History + Recent Logs consolidated) ──────────

@Composable
internal fun ActivityNavigationCard(
    onNavigateToHistory: () -> Unit,
    onNavigateToLogs: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.dashboard_more_title),
            modifier = Modifier.padding(horizontal = 4.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DashboardShortcut(
                title = stringResource(R.string.dashboard_session_history),
                icon = Icons.Filled.DateRange,
                contentDescription = stringResource(R.string.dashboard_a11y_navigate_to_session_history),
                onClick = onNavigateToHistory,
                modifier = Modifier.weight(1f),
            )
            DashboardShortcut(
                title = stringResource(R.string.dashboard_recent_logs),
                icon = Icons.AutoMirrored.Filled.List,
                contentDescription = stringResource(R.string.dashboard_a11y_navigate_to_recent_logs),
                onClick = onNavigateToLogs,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DashboardShortcut(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
            },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
