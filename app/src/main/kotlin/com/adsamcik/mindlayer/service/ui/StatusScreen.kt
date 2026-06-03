package com.adsamcik.mindlayer.service.ui

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsamcik.mindlayer.service.R
import com.adsamcik.mindlayer.service.logging.LogRepository
import com.adsamcik.mindlayer.service.ui.theme.MindlayerType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(
    state: DashboardUiState,
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
                    start = safeInsets.calculateLeftPadding(LayoutDirection.Ltr) + 16.dp,
                    end = safeInsets.calculateRightPadding(LayoutDirection.Ltr) + 16.dp,
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
                item {
                    CardEnterAnimation(5) {
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
    val serviceHealthLabel = stringResource(R.string.dashboard_a11y_service_health)

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
            DashboardWordmark(Modifier.weight(1f, fill = false))
            if (!showBanner) {
                Row(
                    modifier = Modifier.semantics(mergeDescendants = true) {
                        contentDescription = serviceHealthLabel
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
                    StatusDot(healthTint, description = stringResource(R.string.dashboard_a11y_service_health_state, health.name.lowercase()))
                }
            }
        }

        if (showBanner) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = healthTint.copy(alpha = if (darkTheme) 0.15f else 0.10f),
                border = BorderStroke(
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
                        contentDescription = stringResource(R.string.dashboard_a11y_service_health_alert),
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
                        description = stringResource(R.string.dashboard_a11y_service_health_state, health.name.lowercase()),
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
                        contentDescription = stringResource(R.string.dashboard_a11y_loaded_model),
                        modifier = Modifier.size(AssistChipDefaults.IconSize),
                    )
                },
            )
        }
    }
}

@Composable
private fun DashboardWordmark(modifier: Modifier = Modifier) {
    val headlineStyle = MaterialTheme.typography.headlineLarge.copy(
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
        health == DashboardHealthLevel.IDLE -> DashboardMessageTone.NEUTRAL
        else -> DashboardMessageTone.WARNING
    }

    DashboardCard(title = stringResource(R.string.dashboard_card_service_status_title), icon = Icons.Filled.Info) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val serviceHealthLabel = stringResource(R.string.dashboard_a11y_service_health)
            // Health headline row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        contentDescription = serviceHealthLabel
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
                        text = healthDetail(state, nowMs, health),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                StatusDot(
                    healthTint,
                    pulse = health == DashboardHealthLevel.DEGRADED || health == DashboardHealthLevel.ERROR,
                    description = stringResource(R.string.dashboard_a11y_service_health_state, health.name.lowercase()),
                )
            }

            // Key badges — only show what adds information; suppress redundant ones
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Badge(stringResource(connectionLabel(state.connectionState)), connectionColor(state.connectionState))
                Badge(
                    text = when {
                        state.isEngineLoaded -> stringResource(R.string.dashboard_engine_loaded)
                        health == DashboardHealthLevel.IDLE -> stringResource(R.string.dashboard_engine_idle_badge)
                        else -> stringResource(R.string.dashboard_engine_not_ready_badge)
                    },
                    color = toneColor(engineTone),
                )
                if (freshness == DashboardFreshness.STALE) {
                    Badge(stringResource(R.string.dashboard_freshness_stale_label), freshnessColor(freshness))
                }
            }

            if (state.statusErrorMessage != null &&
                state.connectionState == DashboardConnectionState.CONNECTED
            ) {
                DiagnosticCallout(message = state.statusErrorMessage, tone = DashboardMessageTone.ERROR)
            }

            // F-074: surface the crash-loop watchdog throttle banner.
            if (state.serviceThrottled) {
                val secs = state.throttleCooldownSecondsRemaining
                val deathCount = state.recentDeathCount
                val message = if (secs > 0) {
                    pluralStringResource(
                        R.plurals.dashboard_throttle_cooldown,
                        deathCount,
                        secs,
                        deathCount,
                    )
                } else {
                    pluralStringResource(
                        R.plurals.dashboard_throttle_retrying,
                        deathCount,
                        deathCount,
                    )
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
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    LabelValue(stringResource(R.string.dashboard_label_uptime), formatUptime(state.uptimeMs))
                    LabelValue(
                        stringResource(R.string.dashboard_label_sampled),
                        formatSampleTime(state.lastStatusUpdateMs, nowMs, stringResource(R.string.dashboard_sampled_never)),
                    )
                }
            }

            val acceleratorDecisions = state.acceleratorDecisions.ifEmpty {
                state.acceleratorDecision?.let(::listOf).orEmpty()
            }
            if (acceleratorDecisions.isNotEmpty()) {
                var showAdvanced by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAdvanced = !showAdvanced }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            if (showAdvanced) R.string.dashboard_advanced_hide
                            else R.string.dashboard_advanced_show,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                AnimatedVisibility(visible = showAdvanced) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

            // Calm-IDLE UX: when the engine simply has not loaded yet but
            // there is no failure, no throttle, no staleness, and the
            // service is connected, suppress the WARNING runtime-no-model
            // callout entirely. Only emit a single INFO hint *once* (when
            // no engine test has ever been run) so first-time users have
            // a breadcrumb to the Tests tab without permanently nagging
            // returning users.
            if (!state.isEngineLoaded || state.backend.equals("NONE", ignoreCase = true)) {
                val isCalmIdle = state.lastInitFailure == null &&
                    state.connectionState == DashboardConnectionState.CONNECTED &&
                    state.statusFreshness(nowMs) != DashboardFreshness.STALE &&
                    !state.serviceThrottled
                val hasAnyCompletedTest = state.lastTestCompletedAtMs != null ||
                    state.embeddingTest.lastCompletedAtMs != null ||
                    state.ocrTest.lastCompletedAtMs != null ||
                    state.imageInferenceTest.lastCompletedAtMs != null ||
                    state.sdkInferAsyncTest.lastCompletedAtMs != null ||
                    state.sdkInferRealtimeTest.lastCompletedAtMs != null ||
                    state.sdkGenerateWithImageTest.lastCompletedAtMs != null ||
                    state.ocrLlmExtractionTest.lastCompletedAtMs != null
                when {
                    isCalmIdle && !hasAnyCompletedTest -> {
                        DiagnosticCallout(
                            message = stringResource(R.string.status_idle_no_test_hint),
                            tone = DashboardMessageTone.INFO,
                        )
                    }
                    isCalmIdle -> Unit
                    else -> {
                        val isIdle = state.lastInitFailure == null
                        DiagnosticCallout(
                            message = stringResource(
                                if (isIdle) R.string.dashboard_callout_runtime_idle
                                else R.string.dashboard_callout_runtime_no_model,
                            ),
                            tone = if (isIdle) DashboardMessageTone.INFO else DashboardMessageTone.WARNING,
                        )
                    }
                }
            }

            // F-077: typed init-failure rendering. Each variant gets a
            // specific message + suggested remediation. When the typed
            // signal is absent (e.g. legacy `engine_fallback` rows from
            // before this build) fall through to the GPU-only string
            // shim so existing dashboards don't go silent during the
            // upgrade window.
            val initFailure = state.lastInitFailure
            if (initFailure != null) {
                val (tone, message) = describeInitFailure(initFailure, state.backend)
                DiagnosticCallout(message = message, tone = tone)
            } else {
                state.gpuFailureReason?.let { reason ->
                    if (state.backend.equals("CPU", ignoreCase = true)) {
                        Text(
                            text = stringResource(R.string.dashboard_gpu_init_failed, reason),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
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

    ElevatedCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = thermalStatusLabel
                    stateDescription = thermalCombined
                },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.dashboard_thermal),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusDot(tint, description = stringResource(R.string.dashboard_a11y_thermal_band_state, state.thermalBand.lowercase()))
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
                    text = stringResource(R.string.dashboard_headroom),
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
                // no headroom readout AND no current/10s thermal status.
                Text(
                    text = if (telemetryBlind) {
                        stringResource(R.string.dashboard_thermal_telemetry_unavailable)
                    } else {
                        stringResource(R.string.dashboard_thermal_headroom_not_reported)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
            if (telemetryBlind) {
                Text(
                    text = stringResource(R.string.dashboard_conservative_policy_active),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (isHot) {
                Text(
                    text = stringResource(R.string.dashboard_throttling_active),
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
    val memoryPressureLabel = stringResource(R.string.dashboard_a11y_memory_pressure)
    val memoryStateDescription = stringResource(
        R.string.dashboard_a11y_memory_pressure_available_mb,
        accessibilityBandLabel(state.memoryPressure),
        formatWholeNumber(state.availableRamMb),
    )

    ElevatedCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = memoryPressureLabel
                    stateDescription = memoryStateDescription
                },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.dashboard_memory),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.dashboard_max_sessions, state.maxSessions),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusDot(tint, description = stringResource(R.string.dashboard_a11y_memory_pressure_state, state.memoryPressure.lowercase()))
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
                    text = stringResource(R.string.dashboard_available_mb, formatWholeNumber(state.availableRamMb)),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.dashboard_used_mb, formatWholeNumber(usedMb), formatWholeNumber(state.totalRamMb)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isElevated) {
                Text(
                    text = stringResource(R.string.dashboard_low_headroom),
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
        title = stringResource(R.string.dashboard_card_active_sessions_title, state.activeSessions.size),
        icon = Icons.Filled.Person,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                state.connectionState == DashboardConnectionState.CONNECTING &&
                    state.activeSessions.isEmpty() -> {
                    DiagnosticCallout(
                        message = stringResource(R.string.dashboard_active_sessions_waiting),
                        tone = DashboardMessageTone.INFO,
                    )
                }

                state.connectionState == DashboardConnectionState.DISCONNECTED &&
                    state.activeSessions.isEmpty() -> {
                    DiagnosticCallout(
                        message = stringResource(R.string.dashboard_active_sessions_disconnected),
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
                            contentDescription = stringResource(R.string.dashboard_a11y_no_active_sessions),
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                        Text(
                            text = stringResource(R.string.dashboard_no_active_sessions),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = stringResource(R.string.dashboard_no_active_sessions_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = stringResource(R.string.dashboard_last_sampled, formatSampleTime(state.lastStatusUpdateMs, nowMs, stringResource(R.string.dashboard_sampled_never))),
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
                    Badge(text = stringResource(R.string.dashboard_live), color = CategoryInference)
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
                    text = stringResource(R.string.dashboard_token_count, session.tokenCount, session.maxTokens),
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
                        text = stringResource(R.string.dashboard_session_history),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                },
                supportingContent = {
                    Text(
                        text = stringResource(R.string.dashboard_session_history_body),
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Filled.DateRange,
                        contentDescription = stringResource(R.string.dashboard_a11y_session_history),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                trailingContent = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.dashboard_a11y_navigate_to_session_history),
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
                        text = stringResource(R.string.dashboard_recent_logs),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                },
                supportingContent = {
                    Text(
                        text = stringResource(R.string.dashboard_recent_logs_body),
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.List,
                        contentDescription = stringResource(R.string.dashboard_a11y_recent_logs),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                trailingContent = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.dashboard_a11y_navigate_to_recent_logs),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { onNavigateToLogs() },
            )
        }
    }
}
