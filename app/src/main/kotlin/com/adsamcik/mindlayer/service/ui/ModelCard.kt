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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.mindlayer.service.R
import com.adsamcik.mindlayer.service.modeldelivery.ModelDeliveryIssue
import com.adsamcik.mindlayer.service.modeldelivery.ModelDeliveryState

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
) {
    val palette = modelsTonePalette(tone)
    Surface(
        color = palette.container,
        contentColor = palette.content,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = palette.content,
            fontWeight = FontWeight.Bold,
        )
    }
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

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier
                .animateContentSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.Filled.Build,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = roleTitle,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            ModelsBadge(
                text = readinessLabel(phase, summary.readiness),
                tone = readinessTone,
            )

            ReadinessSurface(
                summary = summary,
                roleTitle = roleTitle,
                onDownload = onDownload,
                onRemove = onRemove,
                onRetryActivation = onRetryActivation,
                onConfirmDownload = onConfirmDownload,
            )

            Text(
                text = summary.modelDisplayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(roleDescriptionRes(summary.role)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            RuntimeVerificationSurface(summary, roleTitle)

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            TextButton(
                onClick = { technicalExpanded = !technicalExpanded },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
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
            }
            if (technicalExpanded) {
                TechnicalDetails(summary)
            }
        }
    }
}

private data class ModelStatusCopy(
    val headline: String,
    val detail: String,
)

@Composable
private fun ReadinessSurface(
    summary: RoleModelSummary,
    roleTitle: String,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    onRetryActivation: () -> Unit,
    onConfirmDownload: () -> Unit,
) {
    val phase = modelPhasePresentation(summary)
    val copy = phaseCopy(phase, summary)
    val palette = modelsTonePalette(phaseTone(phase, summary.readiness))
    val progress = modelProgressPresentation(summary)
    val stateAnnouncement = stringResource(
        R.string.models_a11y_phase_status,
        roleTitle,
        copy.headline,
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = palette.container,
        contentColor = palette.content,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = copy.headline,
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = stateAnnouncement
                    liveRegion = LiveRegionMode.Polite
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = palette.content,
            )
            Text(
                text = copy.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.content,
            )
            when (progress.kind) {
                ModelProgressKind.DETERMINATE -> {
                    LinearProgressIndicator(
                        progress = { progress.fraction ?: 0f },
                        modifier = Modifier.fillMaxWidth(),
                        color = palette.content,
                        trackColor = palette.content.copy(alpha = 0.24f),
                    )
                    DownloadProgressLabel(summary.deliveryState, palette.content)
                }
                ModelProgressKind.INDETERMINATE -> {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = palette.content,
                        trackColor = palette.content.copy(alpha = 0.24f),
                    )
                    if (summary.deliveryState is ModelDeliveryState.Downloading) {
                        DownloadProgressLabel(summary.deliveryState, palette.content)
                    }
                }
                ModelProgressKind.NONE -> Unit
            }
            ModelActions(
                state = summary.deliveryState,
                onDownload = onDownload,
                onRemove = onRemove,
                onRetryActivation = onRetryActivation,
                onConfirmDownload = onConfirmDownload,
            )
        }
    }
}

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
private fun ModelActions(
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
    if (availability.secondary == ModelDeliveryAction.REMOVE) {
        OutlinedButton(
            onClick = onRemove,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.models_remove_action))
        }
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
private fun RuntimeVerificationSurface(
    summary: RoleModelSummary,
    roleTitle: String,
) {
    val category = runtimeSummaryCategory(summary)
    val tone = when (category) {
        RuntimeSummaryCategory.LIVE_READY,
        RuntimeSummaryCategory.VERIFICATION_PASSED,
        -> DashboardMessageTone.SUCCESS
        RuntimeSummaryCategory.LIVE_FAILED,
        RuntimeSummaryCategory.VERIFICATION_FAILED,
        -> DashboardMessageTone.ERROR
        RuntimeSummaryCategory.LAST_KNOWN_READY,
        RuntimeSummaryCategory.LAST_KNOWN_NOT_LOADED,
        RuntimeSummaryCategory.LAST_KNOWN_FAILED,
        RuntimeSummaryCategory.SERVICE_UNAVAILABLE,
        -> DashboardMessageTone.WARNING
        RuntimeSummaryCategory.LIVE_STARTING,
        RuntimeSummaryCategory.VERIFICATION_RUNNING,
        -> DashboardMessageTone.INFO
        RuntimeSummaryCategory.LIVE_NOT_LOADED,
        RuntimeSummaryCategory.VERIFICATION_NOT_RUN,
        -> DashboardMessageTone.NEUTRAL
    }
    val palette = modelsTonePalette(tone)
    val copy = runtimeCopy(category, summary)
    val stateAnnouncement = stringResource(
        R.string.models_a11y_runtime_status,
        roleTitle,
        copy.headline,
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = palette.container,
        contentColor = palette.content,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.models_runtime_section_title),
                style = MaterialTheme.typography.labelLarge,
                color = palette.content,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = copy.headline,
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = stateAnnouncement
                    liveRegion = LiveRegionMode.Polite
                },
                style = MaterialTheme.typography.titleSmall,
                color = palette.content,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = copy.detail,
                style = MaterialTheme.typography.bodySmall,
                color = palette.content,
            )
        }
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
private fun TechnicalDetails(summary: RoleModelSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TechnicalDetail(
            label = stringResource(R.string.models_source_label),
            value = stringResource(R.string.models_source_google_play),
        )
        TechnicalDetail(
            label = stringResource(R.string.models_pack_names_label),
            value = summary.deliveryPackNames.joinToString(separator = "\n"),
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
    }
}

@Composable
private fun TechnicalDetail(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
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

@Composable
private fun readinessLabel(
    phase: ModelPhasePresentation,
    readiness: ModelReadiness,
): String = if (phase == ModelPhasePresentation.VERIFICATION_RUNNING) {
    stringResource(R.string.models_readiness_verifying)
} else {
    stringResource(
        when (readiness) {
            ModelReadiness.CHECKING -> R.string.models_readiness_checking
            ModelReadiness.DOWNLOAD_REQUIRED -> R.string.models_readiness_download_required
            ModelReadiness.WAITING -> R.string.models_readiness_waiting
            ModelReadiness.DOWNLOADING -> R.string.models_readiness_downloading
            ModelReadiness.PREPARING -> R.string.models_readiness_preparing
            ModelReadiness.DOWNLOADED_IDLE -> R.string.models_readiness_downloaded
            ModelReadiness.STARTING -> R.string.models_readiness_starting
            ModelReadiness.READY -> R.string.models_readiness_ready
            ModelReadiness.NEEDS_ATTENTION -> R.string.models_readiness_needs_attention
            ModelReadiness.REMOVING -> R.string.models_readiness_removing
            ModelReadiness.UNAVAILABLE -> R.string.models_readiness_unavailable
        },
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
