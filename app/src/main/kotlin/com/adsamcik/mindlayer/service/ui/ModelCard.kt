package com.adsamcik.mindlayer.service.ui

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adsamcik.mindlayer.service.R
import com.adsamcik.mindlayer.service.modeldelivery.ModelDeliveryIssue
import com.adsamcik.mindlayer.service.modeldelivery.ModelDeliveryState
import java.text.NumberFormat

internal data class ModelsTonePalette(
    val container: Color,
    val content: Color,
)

@Composable
internal fun modelsTonePalette(tone: DashboardMessageTone): ModelsTonePalette {
    val colors = MaterialTheme.colorScheme
    return when (tone) {
        DashboardMessageTone.NEUTRAL ->
            ModelsTonePalette(colors.surfaceVariant, colors.onSurfaceVariant)
        DashboardMessageTone.INFO ->
            ModelsTonePalette(colors.secondaryContainer, colors.onSecondaryContainer)
        DashboardMessageTone.SUCCESS ->
            ModelsTonePalette(colors.primaryContainer, colors.onPrimaryContainer)
        DashboardMessageTone.WARNING ->
            ModelsTonePalette(colors.tertiaryContainer, colors.onTertiaryContainer)
        DashboardMessageTone.ERROR ->
            ModelsTonePalette(colors.errorContainer, colors.onErrorContainer)
    }
}

@Composable
internal fun ModelsBadge(
    text: String,
    tone: DashboardMessageTone,
    modifier: Modifier = Modifier,
    inProgress: Boolean = false,
) {
    val palette = modelsTonePalette(tone)
    Surface(
        modifier = modifier,
        color = palette.container,
        contentColor = palette.content,
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (inProgress) {
                LoadingIndicator(
                    modifier = Modifier.size(18.dp),
                    color = palette.content,
                )
            } else {
                Icon(
                    imageVector = statusIcon(tone),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = palette.content,
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = palette.content,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private fun statusIcon(tone: DashboardMessageTone): ImageVector = when (tone) {
    DashboardMessageTone.SUCCESS -> Icons.Filled.CheckCircle
    DashboardMessageTone.WARNING,
    DashboardMessageTone.ERROR,
    -> Icons.Filled.Warning
    DashboardMessageTone.NEUTRAL,
    DashboardMessageTone.INFO,
    -> Icons.Filled.Info
}

@Composable
internal fun RoleModelCard(
    summary: RoleModelSummary,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    onRetryActivation: () -> Unit,
    onConfirmDownload: () -> Unit,
) {
    var technicalExpanded by rememberSaveable(summary.role.name) { mutableStateOf(false) }
    val roleTitle = stringResource(roleTitleRes(summary.role))
    val phase = modelPhasePresentation(summary)
    val readinessTone = phaseTone(phase, summary.readiness)
    val statusCopy = phaseCopy(phase, summary)
    val progress = modelProgressPresentation(summary)
    val stateAnnouncement = stringResource(
        R.string.models_a11y_phase_status,
        roleTitle,
        statusCopy.headline,
    )

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier
                .animateContentSize()
                .padding(MindlayerScreenDefaults.CardContentPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoleIcon(summary.role)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = roleTitle,
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(roleDescriptionRes(summary.role)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            ModelsBadge(
                text = statusCopy.headline,
                tone = readinessTone,
                modifier = Modifier.semantics {
                    contentDescription = stateAnnouncement
                    liveRegion = LiveRegionMode.Polite
                },
                inProgress = progress.kind != ModelProgressKind.NONE,
            )

            when (progress.kind) {
                ModelProgressKind.DETERMINATE -> {
                    LinearWavyProgressIndicator(
                        progress = { progress.fraction ?: 0f },
                        modifier = Modifier.fillMaxWidth(),
                        color = readinessTone.progressColor(),
                    )
                    DownloadProgressLabel(
                        summary.deliveryState,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ModelProgressKind.INDETERMINATE -> {
                    LinearWavyProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = readinessTone.progressColor(),
                    )
                    if (summary.deliveryState is ModelDeliveryState.Downloading) {
                        DownloadProgressLabel(
                            summary.deliveryState,
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                ModelProgressKind.NONE -> Unit
            }

            ModelPrimaryAction(
                state = summary.deliveryState,
                onDownload = onDownload,
                onRemove = onRemove,
                onRetryActivation = onRetryActivation,
                onConfirmDownload = onConfirmDownload,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = summary.modelDisplayName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(
                    onClick = { technicalExpanded = !technicalExpanded },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        stringResource(
                            if (technicalExpanded) {
                                R.string.models_hide_technical_details
                            } else {
                                R.string.models_show_technical_details
                            },
                        ),
                    )
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(18.dp)
                            .rotate(if (technicalExpanded) 180f else 0f),
                    )
                }
            }
            if (technicalExpanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ModelDetails(
                    summary = summary,
                    statusCopy = statusCopy,
                    roleTitle = roleTitle,
                    onRemove = onRemove,
                )
            }
        }
    }
}

@Composable
private fun DashboardMessageTone.progressColor(): Color = when (this) {
    DashboardMessageTone.NEUTRAL -> MaterialTheme.colorScheme.primary
    DashboardMessageTone.INFO -> MaterialTheme.colorScheme.secondary
    DashboardMessageTone.SUCCESS -> MaterialTheme.colorScheme.primary
    DashboardMessageTone.WARNING -> MaterialTheme.colorScheme.tertiary
    DashboardMessageTone.ERROR -> MaterialTheme.colorScheme.error
}

@Composable
private fun RoleIcon(role: ModelRole) {
    val (icon, palette) = when (role) {
        ModelRole.CHAT_AND_VISION -> Icons.Filled.Face to
            modelsTonePalette(DashboardMessageTone.INFO)
        ModelRole.EMBEDDINGS -> Icons.Filled.Search to
            modelsTonePalette(DashboardMessageTone.SUCCESS)
        ModelRole.OCR -> Icons.AutoMirrored.Filled.List to
            modelsTonePalette(DashboardMessageTone.WARNING)
    }
    Surface(
        modifier = Modifier.size(44.dp),
        shape = MaterialTheme.shapes.large,
        color = palette.container,
        contentColor = palette.content,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.padding(11.dp),
            tint = palette.content,
        )
    }
}

private data class ModelStatusCopy(
    val headline: String,
    val detail: String,
)

@Composable
private fun DownloadProgressLabel(
    state: ModelDeliveryState,
    contentColor: Color,
) {
    val downloading = state as? ModelDeliveryState.Downloading ?: return
    val context = LocalContext.current
    val downloaded = Formatter.formatShortFileSize(
        context,
        downloading.downloadedBytes.coerceAtLeast(0L),
    )
    if (downloading.totalBytes > 0L) {
        val total = Formatter.formatShortFileSize(context, downloading.totalBytes)
        Text(
            text = stringResource(
                R.string.models_delivery_progress_known,
                downloaded,
                total,
                downloading.progressPercent,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = contentColor,
        )
    } else {
        Text(
            text = stringResource(R.string.models_delivery_progress_unknown, downloaded),
            style = MaterialTheme.typography.bodySmall,
            color = contentColor,
        )
    }
}

@Composable
private fun phaseCopy(
    phase: ModelPhasePresentation,
    summary: RoleModelSummary,
): ModelStatusCopy {
    val context = LocalContext.current
    return when (phase) {
        ModelPhasePresentation.CHECKING -> ModelStatusCopy(
            stringResource(R.string.models_status_checking_headline),
            stringResource(R.string.models_status_checking_detail),
        )
        ModelPhasePresentation.DOWNLOAD_REQUIRED -> ModelStatusCopy(
            stringResource(R.string.models_status_download_required_headline),
            stringResource(R.string.models_status_download_required_detail),
        )
        ModelPhasePresentation.PENDING -> ModelStatusCopy(
            stringResource(R.string.models_status_pending_headline),
            stringResource(R.string.models_status_pending_detail),
        )
        ModelPhasePresentation.WAITING_FOR_WIFI -> ModelStatusCopy(
            stringResource(R.string.models_status_waiting_wifi_headline),
            stringResource(R.string.models_status_waiting_wifi_detail),
        )
        ModelPhasePresentation.CONFIRMATION_REQUIRED -> ModelStatusCopy(
            stringResource(R.string.models_status_confirmation_headline),
            stringResource(R.string.models_status_confirmation_detail),
        )
        ModelPhasePresentation.CONFIRMATION_UNAVAILABLE -> ModelStatusCopy(
            stringResource(R.string.models_status_confirmation_unavailable_headline),
            stringResource(R.string.models_status_confirmation_unavailable_detail),
        )
        ModelPhasePresentation.DOWNLOADING -> ModelStatusCopy(
            stringResource(R.string.models_status_downloading_headline),
            stringResource(R.string.models_status_downloading_detail),
        )
        ModelPhasePresentation.TRANSFERRING -> ModelStatusCopy(
            stringResource(R.string.models_status_transferring_headline),
            stringResource(R.string.models_status_transferring_detail),
        )
        ModelPhasePresentation.PROVISIONING_CHAT -> ModelStatusCopy(
            stringResource(R.string.models_status_provisioning_headline),
            stringResource(R.string.models_status_provisioning_chat_detail),
        )
        ModelPhasePresentation.PROVISIONING -> ModelStatusCopy(
            stringResource(R.string.models_status_provisioning_headline),
            stringResource(R.string.models_status_provisioning_detail),
        )
        ModelPhasePresentation.ACTIVATING -> ModelStatusCopy(
            stringResource(R.string.models_status_activating_headline),
            stringResource(R.string.models_status_activating_detail),
        )
        ModelPhasePresentation.DOWNLOADED_IDLE -> ModelStatusCopy(
            stringResource(R.string.models_status_downloaded_idle_headline),
            stringResource(
                when (summary.role) {
                    ModelRole.CHAT_AND_VISION ->
                        R.string.models_status_downloaded_idle_chat_detail
                    ModelRole.EMBEDDINGS ->
                        R.string.models_status_downloaded_idle_embeddings_detail
                    ModelRole.OCR ->
                        R.string.models_status_downloaded_idle_ocr_detail
                },
            ),
        )
        ModelPhasePresentation.STARTING -> ModelStatusCopy(
            stringResource(R.string.models_status_starting_headline),
            stringResource(R.string.models_status_starting_chat_detail),
        )
        ModelPhasePresentation.VERIFICATION_RUNNING -> ModelStatusCopy(
            stringResource(R.string.models_status_verification_running_headline),
            stringResource(R.string.models_status_verification_running_detail),
        )
        ModelPhasePresentation.READY -> ModelStatusCopy(
            stringResource(R.string.models_status_ready_headline),
            stringResource(R.string.models_status_ready_detail),
        )
        ModelPhasePresentation.INSUFFICIENT_STORAGE -> {
            val issue = (summary.deliveryState as? ModelDeliveryState.Failed)?.issue
                as? ModelDeliveryIssue.InsufficientStorage
            val required = Formatter.formatShortFileSize(context, issue?.requiredBytes ?: 0L)
            val available = Formatter.formatShortFileSize(context, issue?.availableBytes ?: 0L)
            ModelStatusCopy(
                stringResource(R.string.models_status_storage_headline),
                stringResource(R.string.models_status_storage_detail, required, available),
            )
        }
        ModelPhasePresentation.DOWNLOAD_FAILED -> ModelStatusCopy(
            stringResource(R.string.models_status_download_failed_headline),
            stringResource(R.string.models_status_download_failed_detail),
        )
        ModelPhasePresentation.VERIFICATION_FAILED -> ModelStatusCopy(
            stringResource(R.string.models_status_verification_failed_headline),
            stringResource(R.string.models_status_verification_failed_detail),
        )
        ModelPhasePresentation.ACTIVATION_FAILED -> ModelStatusCopy(
            stringResource(R.string.models_status_activation_failed_headline),
            stringResource(R.string.models_status_activation_failed_detail),
        )
        ModelPhasePresentation.REMOVAL_INTERRUPTED -> ModelStatusCopy(
            stringResource(R.string.models_status_removal_interrupted_headline),
            stringResource(R.string.models_status_removal_interrupted_detail),
        )
        ModelPhasePresentation.REMOVAL_FAILED -> ModelStatusCopy(
            stringResource(R.string.models_status_removal_failed_headline),
            stringResource(R.string.models_status_removal_failed_detail),
        )
        ModelPhasePresentation.RUNTIME_INITIALIZATION_FAILED -> ModelStatusCopy(
            stringResource(R.string.models_status_runtime_failed_headline),
            stringResource(R.string.models_status_runtime_failed_detail),
        )
        ModelPhasePresentation.RUNTIME_VERIFICATION_FAILED -> ModelStatusCopy(
            stringResource(R.string.models_status_runtime_verification_failed_headline),
            stringResource(R.string.models_status_runtime_verification_failed_detail),
        )
        ModelPhasePresentation.ATTENTION_REQUIRED -> ModelStatusCopy(
            stringResource(R.string.models_status_attention_headline),
            stringResource(R.string.models_status_attention_detail),
        )
        ModelPhasePresentation.QUIESCING -> ModelStatusCopy(
            stringResource(R.string.models_status_quiescing_headline),
            stringResource(R.string.models_status_quiescing_detail),
        )
        ModelPhasePresentation.REMOVING -> ModelStatusCopy(
            stringResource(R.string.models_status_removing_headline),
            stringResource(R.string.models_status_removing_detail),
        )
        ModelPhasePresentation.UNAVAILABLE -> ModelStatusCopy(
            stringResource(R.string.models_status_unavailable_headline),
            stringResource(R.string.models_status_unavailable_detail),
        )
    }
}

@Composable
private fun ModelPrimaryAction(
    state: ModelDeliveryState,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    onRetryActivation: () -> Unit,
    onConfirmDownload: () -> Unit,
) {
    val availability = modelActionAvailability(state)
    when (availability.primary) {
        ModelDeliveryAction.DOWNLOAD -> PrimaryAction(
            label = stringResource(R.string.models_download_action),
            onClick = onDownload,
        )
        ModelDeliveryAction.RETRY_DOWNLOAD -> PrimaryAction(
            label = stringResource(R.string.models_try_download_again_action),
            onClick = onDownload,
        )
        ModelDeliveryAction.RETRY_REMOVE -> PrimaryAction(
            label = stringResource(
                if (
                    state is ModelDeliveryState.RemovalFailed &&
                    state.issue == ModelDeliveryIssue.RemovalInterrupted
                ) {
                    R.string.models_finish_remove_action
                } else {
                    R.string.models_retry_remove_action
                },
            ),
            onClick = onRemove,
        )
        ModelDeliveryAction.CONFIRM -> PrimaryAction(
            label = stringResource(
                if (state == ModelDeliveryState.RequiresConfirmation) {
                    R.string.models_review_confirmation_action
                } else {
                    R.string.models_retry_confirmation_action
                },
            ),
            onClick = onConfirmDownload,
        )
        ModelDeliveryAction.RETRY_ACTIVATION -> PrimaryAction(
            label = stringResource(R.string.models_retry_activation_action),
            onClick = onRetryActivation,
        )
        ModelDeliveryAction.REMOVE,
        ModelDeliveryAction.NONE,
        -> Unit
    }
}

@Composable
private fun PrimaryAction(
    label: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) {
        Text(label)
    }
}

@Composable
private fun runtimeCopy(
    category: RuntimeSummaryCategory,
    summary: RoleModelSummary,
): ModelStatusCopy {
    val completedAt = summary.lastVerificationAtMs?.let { formatTimestamp(it) }
    val lastRuntimeAt = summary.lastRuntimeStatusAtMs?.let { formatTimestamp(it) }
        ?: stringResource(R.string.models_time_unknown)
    return when (category) {
        RuntimeSummaryCategory.LIVE_READY -> ModelStatusCopy(
            stringResource(R.string.models_runtime_live_ready_headline),
            stringResource(R.string.models_runtime_live_ready_detail),
        )
        RuntimeSummaryCategory.LIVE_NOT_LOADED -> ModelStatusCopy(
            stringResource(R.string.models_runtime_live_idle_headline),
            stringResource(R.string.models_runtime_live_idle_detail),
        )
        RuntimeSummaryCategory.LIVE_STARTING -> ModelStatusCopy(
            stringResource(R.string.models_runtime_live_starting_headline),
            stringResource(R.string.models_runtime_live_starting_detail),
        )
        RuntimeSummaryCategory.LIVE_FAILED -> ModelStatusCopy(
            stringResource(R.string.models_runtime_live_failed_headline),
            stringResource(R.string.models_runtime_live_failed_detail),
        )
        RuntimeSummaryCategory.LAST_KNOWN_READY -> ModelStatusCopy(
            stringResource(R.string.models_runtime_last_known_ready_headline),
            stringResource(R.string.models_runtime_last_known_detail, lastRuntimeAt),
        )
        RuntimeSummaryCategory.LAST_KNOWN_NOT_LOADED -> ModelStatusCopy(
            stringResource(R.string.models_runtime_last_known_idle_headline),
            stringResource(R.string.models_runtime_last_known_detail, lastRuntimeAt),
        )
        RuntimeSummaryCategory.LAST_KNOWN_FAILED -> ModelStatusCopy(
            stringResource(R.string.models_runtime_last_known_failed_headline),
            stringResource(R.string.models_runtime_last_known_detail, lastRuntimeAt),
        )
        RuntimeSummaryCategory.SERVICE_UNAVAILABLE -> ModelStatusCopy(
            stringResource(R.string.models_runtime_unavailable_headline),
            stringResource(R.string.models_runtime_unavailable_detail),
        )
        RuntimeSummaryCategory.VERIFICATION_RUNNING -> ModelStatusCopy(
            stringResource(R.string.models_runtime_verification_running_headline),
            stringResource(R.string.models_runtime_verification_running_detail),
        )
        RuntimeSummaryCategory.VERIFICATION_PASSED -> ModelStatusCopy(
            stringResource(R.string.models_runtime_verification_passed_headline),
            stringResource(
                R.string.models_runtime_verification_passed_detail,
                completedAt ?: stringResource(R.string.models_time_unknown),
            ),
        )
        RuntimeSummaryCategory.VERIFICATION_FAILED -> ModelStatusCopy(
            stringResource(R.string.models_runtime_verification_failed_headline),
            stringResource(
                R.string.models_runtime_verification_failed_detail,
                completedAt ?: stringResource(R.string.models_time_unknown),
            ),
        )
        RuntimeSummaryCategory.VERIFICATION_NOT_RUN -> ModelStatusCopy(
            stringResource(R.string.models_runtime_verification_not_run_headline),
            stringResource(R.string.models_runtime_verification_not_run_detail),
        )
    }
}

@Composable
private fun ModelDetails(
    summary: RoleModelSummary,
    statusCopy: ModelStatusCopy,
    roleTitle: String,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val runtime = runtimeCopy(runtimeSummaryCategory(summary), summary)
    val runtimeAnnouncement = stringResource(
        R.string.models_a11y_runtime_status,
        roleTitle,
        runtime.headline,
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.models_status_details_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = statusCopy.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TechnicalDetail(
            label = stringResource(R.string.models_runtime_section_title),
            value = runtime.headline,
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = runtimeAnnouncement
            },
        )
        Text(
            text = runtime.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        summary.contextWindowTokens?.let { tokens ->
            TechnicalDetail(
                label = stringResource(R.string.models_context_window_label),
                value = stringResource(
                    R.string.models_context_window_value,
                    NumberFormat.getIntegerInstance().format(tokens),
                ),
            )
        }
        summary.modelSizeBytes?.takeIf { it > 0L }?.let { modelSize ->
            TechnicalDetail(
                label = stringResource(R.string.models_model_size_label),
                value = Formatter.formatShortFileSize(context, modelSize),
            )
        }
        TechnicalDetail(
            label = stringResource(R.string.models_setup_space_label),
            value = stringResource(
                R.string.models_setup_space_value,
                Formatter.formatShortFileSize(context, summary.minimumFreeBytes),
            ),
        )
        summary.backend?.let { backend ->
            TechnicalDetail(
                label = stringResource(R.string.models_backend_label),
                value = backend.uppercase(),
            )
        }
        summary.initTimeSeconds?.takeIf { it > 0f }?.let { initTimeSeconds ->
            TechnicalDetail(
                label = stringResource(R.string.models_init_time_label),
                value = stringResource(R.string.dashboard_init_time_seconds, initTimeSeconds),
            )
        }
        if (
            summary.evidence == ModelRuntimeEvidence.DASHBOARD_VERIFICATION ||
            summary.lastVerificationAtMs != null
        ) {
            TechnicalDetail(
                label = stringResource(R.string.models_last_verification_label),
                value = summary.lastVerificationAtMs?.let { formatTimestamp(it) }
                    ?: stringResource(R.string.models_verification_not_run),
            )
        }
        TechnicalDetail(
            label = stringResource(R.string.models_source_label),
            value = stringResource(R.string.models_source_google_play),
        )
        TechnicalDetail(
            label = stringResource(R.string.models_pack_names_label),
            value = summary.deliveryPackNames.joinToString(separator = "\n"),
        )
        if (modelActionAvailability(summary.deliveryState).secondary == ModelDeliveryAction.REMOVE) {
            OutlinedButton(
                onClick = onRemove,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.models_remove_action))
            }
        }
    }
}

@Composable
private fun TechnicalDetail(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.4f),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.6f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun formatTimestamp(timestampMs: Long): String {
    val context = LocalContext.current
    return DateUtils.formatDateTime(
        context,
        timestampMs,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME,
    )
}

internal fun roleTitleRes(role: ModelRole): Int = when (role) {
    ModelRole.CHAT_AND_VISION -> R.string.models_role_chat
    ModelRole.EMBEDDINGS -> R.string.models_role_embeddings
    ModelRole.OCR -> R.string.models_role_ocr
}

private fun roleDescriptionRes(role: ModelRole): Int = when (role) {
    ModelRole.CHAT_AND_VISION -> R.string.models_role_chat_description
    ModelRole.EMBEDDINGS -> R.string.models_role_embeddings_description
    ModelRole.OCR -> R.string.models_role_ocr_description
}
