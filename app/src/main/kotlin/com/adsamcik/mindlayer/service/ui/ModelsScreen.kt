package com.adsamcik.mindlayer.service.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.adsamcik.mindlayer.service.R
import com.adsamcik.mindlayer.service.modeldelivery.ModelDeliveryState

@Composable
fun ModelsScreen(
    state: DashboardUiState,
    onDownload: (ModelRole) -> Unit,
    onRemove: (ModelRole) -> Unit,
    onRetryActivation: (ModelRole) -> Unit,
    onRefresh: () -> Unit,
    onConfirmDownload: () -> Unit,
) {
    var pendingRemoval: RoleModelSummary? by remember { mutableStateOf(null) }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        val safeInsets = WindowInsets.safeDrawing.asPaddingValues()
        val summaries = state.modelSummaries()

        LazyColumn(
            contentPadding = PaddingValues(
                start = safeInsets.calculateLeftPadding(LayoutDirection.Ltr) + 16.dp,
                end = safeInsets.calculateRightPadding(LayoutDirection.Ltr) + 16.dp,
                top = safeInsets.calculateTopPadding() + 12.dp,
                bottom = safeInsets.calculateBottomPadding() + 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                CardEnterAnimation(0) {
                    Text(
                        text = stringResource(R.string.models_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
            item {
                CardEnterAnimation(1) {
                    OfflineBanner()
                }
            }
            item {
                OutlinedButton(onClick = onRefresh) {
                    Text(stringResource(R.string.models_refresh_action))
                }
            }
            summaries.forEachIndexed { index, summary ->
                item(key = summary.role) {
                    CardEnterAnimation(index + 2) {
                        RoleModelCard(
                            summary = summary,
                            onDownload = { onDownload(summary.role) },
                            onRemove = { pendingRemoval = summary },
                            onRetryActivation = { onRetryActivation(summary.role) },
                            onConfirmDownload = onConfirmDownload,
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
        pendingRemoval?.let { summary ->
            AlertDialog(
                onDismissRequest = { pendingRemoval = null },
                title = { Text(stringResource(R.string.models_remove_confirm_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.models_remove_confirm_message,
                            stringResource(roleTitleRes(summary.role)),
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onRemove(summary.role)
                            pendingRemoval = null
                        },
                    ) { Text(stringResource(R.string.models_remove_action)) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingRemoval = null }) {
                        Text(stringResource(R.string.models_cancel_action))
                    }
                },
            )
        }
    }
}

@Composable
private fun OfflineBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = stringResource(R.string.models_offline_banner),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun RoleModelCard(
    summary: RoleModelSummary,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    onRetryActivation: () -> Unit,
    onConfirmDownload: () -> Unit,
) {
    val roleTitle = stringResource(roleTitleRes(summary.role))
    val roleDescription = stringResource(roleDescriptionRes(summary.role))
    val (badgeText, badgeTone) = stateBadge(summary.state)

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Build,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = roleTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Badge(text = badgeText, color = toneColor(badgeTone))
            }

            Text(
                text = roleDescription,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = summary.modelDisplayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            LabelValue(stringResource(R.string.models_pack_label), summary.packDescription)
            DeliveryStatus(
                state = summary.deliveryState,
                onDownload = onDownload,
                onRemove = onRemove,
                onRetryActivation = onRetryActivation,
                onConfirmDownload = onConfirmDownload,
            )

            summary.backend?.takeIf { it.isNotBlank() }?.let { backend ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.models_backend_label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Badge(text = backend.uppercase(), color = backendColor(backend))
                }

            }

            summary.initTimeSeconds?.takeIf { it > 0f }?.let { seconds ->
                LabelValue(
                    stringResource(R.string.models_init_time_label),
                    stringResource(R.string.dashboard_init_time_seconds, seconds),
                )
            }

            when (summary.state) {
                ModelLoadState.UNKNOWN -> {
                    Text(
                        text = stringResource(R.string.models_state_unknown_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                    )
                }
                ModelLoadState.FAILED -> {
                    summary.failureDetail?.let { detail ->
                        DiagnosticCallout(message = detail, tone = DashboardMessageTone.ERROR)
                    }
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun DeliveryStatus(
    state: ModelDeliveryState,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    onRetryActivation: () -> Unit,
    onConfirmDownload: () -> Unit,
) {
    val label = when (state) {
        ModelDeliveryState.Checking -> stringResource(R.string.models_delivery_checking)
        ModelDeliveryState.NotInstalled -> stringResource(R.string.models_delivery_not_installed)
        ModelDeliveryState.Pending -> stringResource(R.string.models_delivery_pending)
        is ModelDeliveryState.Downloading -> stringResource(R.string.models_delivery_downloading)
        ModelDeliveryState.Transferring -> stringResource(R.string.models_delivery_transferring)
        ModelDeliveryState.WaitingForWifi -> stringResource(R.string.models_delivery_waiting_wifi)
        ModelDeliveryState.RequiresConfirmation -> stringResource(R.string.models_delivery_confirmation)
        ModelDeliveryState.Provisioning -> stringResource(R.string.models_delivery_provisioning)
        ModelDeliveryState.Installed -> stringResource(R.string.models_delivery_installed)
        ModelDeliveryState.Activating -> stringResource(R.string.models_delivery_activating)
        ModelDeliveryState.InstalledWithActivationError ->
            stringResource(R.string.models_delivery_installed)
        ModelDeliveryState.Removing -> stringResource(R.string.models_delivery_removing)
        ModelDeliveryState.Quiescing -> stringResource(R.string.models_delivery_removing)
        is ModelDeliveryState.RemovalFailed -> stringResource(R.string.models_delivery_removal_failed)
        is ModelDeliveryState.Failed -> stringResource(R.string.models_delivery_failed)
        ModelDeliveryState.Unsupported -> stringResource(R.string.models_delivery_unsupported)
    }
    Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    if (state is ModelDeliveryState.Downloading) {
        LinearProgressIndicator(progress = { state.progressPercent / 100f }, modifier = Modifier.fillMaxWidth())
        Text(
            stringResource(
                R.string.models_delivery_progress,
                state.downloadedBytes / 1_000_000,
                state.totalBytes / 1_000_000,
                state.progressPercent,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (state == ModelDeliveryState.Activating) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    if (state is ModelDeliveryState.Failed) {
        DiagnosticCallout(message = state.message, tone = DashboardMessageTone.ERROR)
    }
    if (state is ModelDeliveryState.RemovalFailed) {
        DiagnosticCallout(message = state.message, tone = DashboardMessageTone.ERROR)
    }
    if (state == ModelDeliveryState.InstalledWithActivationError) {
        DiagnosticCallout(
            message = stringResource(R.string.models_activation_failed_message),
            tone = DashboardMessageTone.ERROR,
        )
    }
    when (state) {
        ModelDeliveryState.NotInstalled -> Button(onClick = onDownload) {
            Text(stringResource(R.string.models_download_action))
        }
        is ModelDeliveryState.Failed -> Button(onClick = onDownload) {
            Text(stringResource(R.string.models_retry_action))
        }
        is ModelDeliveryState.RemovalFailed -> OutlinedButton(onClick = onRemove) {
            Text(stringResource(R.string.models_retry_remove_action))
        }
        ModelDeliveryState.RequiresConfirmation -> Button(onClick = onConfirmDownload) {
            Text(stringResource(R.string.models_confirm_action))
        }
        ModelDeliveryState.Installed -> OutlinedButton(onClick = onRemove) {
            Text(stringResource(R.string.models_remove_action))
        }
        ModelDeliveryState.InstalledWithActivationError -> {
            Button(onClick = onRetryActivation) {
                Text(stringResource(R.string.models_retry_activation_action))
            }
            OutlinedButton(onClick = onRemove) {
                Text(stringResource(R.string.models_remove_action))
            }
        }
        else -> Unit
    }
    if (
        state == ModelDeliveryState.Installed ||
        state == ModelDeliveryState.Activating ||
        state == ModelDeliveryState.InstalledWithActivationError
    ) {
        Text(stringResource(R.string.models_loaded_separate_hint), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun stateBadge(state: ModelLoadState): Pair<String, DashboardMessageTone> = when (state) {
    ModelLoadState.LOADED ->
        stringResource(R.string.models_state_loaded) to DashboardMessageTone.SUCCESS
    ModelLoadState.IDLE ->
        stringResource(R.string.models_state_idle) to DashboardMessageTone.NEUTRAL
    ModelLoadState.UNKNOWN ->
        stringResource(R.string.models_state_unknown) to DashboardMessageTone.NEUTRAL
    ModelLoadState.FAILED ->
        stringResource(R.string.models_state_failed) to DashboardMessageTone.ERROR
}

private fun roleTitleRes(role: ModelRole): Int = when (role) {
    ModelRole.CHAT_AND_VISION -> R.string.models_role_chat
    ModelRole.EMBEDDINGS -> R.string.models_role_embeddings
    ModelRole.OCR -> R.string.models_role_ocr
}

private fun roleDescriptionRes(role: ModelRole): Int = when (role) {
    ModelRole.CHAT_AND_VISION -> R.string.models_role_chat_description
    ModelRole.EMBEDDINGS -> R.string.models_role_embeddings_description
    ModelRole.OCR -> R.string.models_role_ocr_description
}
