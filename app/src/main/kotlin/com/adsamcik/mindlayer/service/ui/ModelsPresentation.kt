package com.adsamcik.mindlayer.service.ui

import com.adsamcik.mindlayer.service.modeldelivery.ModelDeliveryIssue
import com.adsamcik.mindlayer.service.modeldelivery.ModelDeliveryState

internal enum class ModelOverviewKind {
    NEEDS_ATTENTION,
    ACTIVITY_IN_PROGRESS,
    DOWNLOADED_COUNT,
    ALL_AVAILABLE,
}

internal data class ModelOverview(
    val kind: ModelOverviewKind,
    val affectedCount: Int,
    val downloadedCount: Int,
    val totalCount: Int,
)

internal enum class ModelProgressKind {
    NONE,
    INDETERMINATE,
    DETERMINATE,
}

internal data class ModelProgressPresentation(
    val kind: ModelProgressKind,
    val fraction: Float? = null,
)

internal enum class ModelPhasePresentation {
    CHECKING,
    DOWNLOAD_REQUIRED,
    PENDING,
    WAITING_FOR_WIFI,
    CONFIRMATION_REQUIRED,
    CONFIRMATION_UNAVAILABLE,
    DOWNLOADING,
    TRANSFERRING,
    PROVISIONING_CHAT,
    PROVISIONING,
    ACTIVATING,
    DOWNLOADED_IDLE,
    STARTING,
    VERIFICATION_RUNNING,
    READY,
    INSUFFICIENT_STORAGE,
    DOWNLOAD_FAILED,
    VERIFICATION_FAILED,
    ACTIVATION_FAILED,
    REMOVAL_INTERRUPTED,
    REMOVAL_FAILED,
    RUNTIME_INITIALIZATION_FAILED,
    RUNTIME_VERIFICATION_FAILED,
    ATTENTION_REQUIRED,
    QUIESCING,
    REMOVING,
    UNAVAILABLE,
}

internal enum class RuntimeSummaryCategory {
    LIVE_READY,
    LIVE_NOT_LOADED,
    LIVE_STARTING,
    LIVE_FAILED,
    LAST_KNOWN_READY,
    LAST_KNOWN_NOT_LOADED,
    LAST_KNOWN_FAILED,
    SERVICE_UNAVAILABLE,
    VERIFICATION_RUNNING,
    VERIFICATION_PASSED,
    VERIFICATION_FAILED,
    VERIFICATION_NOT_RUN,
}

internal data class ModelActionAvailability(
    val primary: ModelDeliveryAction,
    val secondary: ModelDeliveryAction? = null,
)

internal fun modelOverview(summaries: List<RoleModelSummary>): ModelOverview {
    val attentionCount = summaries.count {
        it.readiness == ModelReadiness.NEEDS_ATTENTION ||
            it.readiness == ModelReadiness.UNAVAILABLE
    }
    val inProgressCount = summaries.count {
        it.readiness in setOf(
            ModelReadiness.CHECKING,
            ModelReadiness.WAITING,
            ModelReadiness.DOWNLOADING,
            ModelReadiness.PREPARING,
            ModelReadiness.STARTING,
            ModelReadiness.REMOVING,
        )
    }
    val downloadedCount = summaries.count {
        it.deliveryState == ModelDeliveryState.Installed ||
            it.deliveryState == ModelDeliveryState.InstalledWithActivationError
    }
    val totalCount = summaries.size
    val kind = when {
        attentionCount > 0 -> ModelOverviewKind.NEEDS_ATTENTION
        inProgressCount > 0 -> ModelOverviewKind.ACTIVITY_IN_PROGRESS
        totalCount > 0 && downloadedCount == totalCount -> ModelOverviewKind.ALL_AVAILABLE
        else -> ModelOverviewKind.DOWNLOADED_COUNT
    }
    val affectedCount = when (kind) {
        ModelOverviewKind.NEEDS_ATTENTION -> attentionCount
        ModelOverviewKind.ACTIVITY_IN_PROGRESS -> inProgressCount
        ModelOverviewKind.DOWNLOADED_COUNT,
        ModelOverviewKind.ALL_AVAILABLE,
        -> downloadedCount
    }
    return ModelOverview(kind, affectedCount, downloadedCount, totalCount)
}

internal fun modelReadinessTone(readiness: ModelReadiness): DashboardMessageTone = when (readiness) {
    ModelReadiness.CHECKING,
    ModelReadiness.DOWNLOAD_REQUIRED,
    ModelReadiness.DOWNLOADING,
    ModelReadiness.PREPARING,
    ModelReadiness.STARTING,
    ModelReadiness.REMOVING,
    -> DashboardMessageTone.INFO
    ModelReadiness.WAITING,
    ModelReadiness.UNAVAILABLE,
    -> DashboardMessageTone.WARNING
    ModelReadiness.DOWNLOADED_IDLE,
    ModelReadiness.READY,
    -> DashboardMessageTone.SUCCESS
    ModelReadiness.NEEDS_ATTENTION -> DashboardMessageTone.ERROR
}

internal fun modelProgressPresentation(summary: RoleModelSummary): ModelProgressPresentation {
    val delivery = summary.deliveryState
    if (delivery is ModelDeliveryState.Downloading) {
        return if (delivery.totalBytes > 0L) {
            ModelProgressPresentation(
                kind = ModelProgressKind.DETERMINATE,
                fraction = (delivery.downloadedBytes.toDouble() / delivery.totalBytes.toDouble())
                    .coerceIn(0.0, 1.0)
                    .toFloat(),
            )
        } else {
            ModelProgressPresentation(ModelProgressKind.INDETERMINATE)
        }
    }
    return if (
        delivery == ModelDeliveryState.Pending ||
        summary.readiness in setOf(
            ModelReadiness.CHECKING,
            ModelReadiness.PREPARING,
            ModelReadiness.STARTING,
            ModelReadiness.REMOVING,
        )
    ) {
        ModelProgressPresentation(ModelProgressKind.INDETERMINATE)
    } else {
        ModelProgressPresentation(ModelProgressKind.NONE)
    }
}

internal fun modelPhasePresentation(summary: RoleModelSummary): ModelPhasePresentation =
    when (val delivery = summary.deliveryState) {
        ModelDeliveryState.Checking -> ModelPhasePresentation.CHECKING
        ModelDeliveryState.NotInstalled -> ModelPhasePresentation.DOWNLOAD_REQUIRED
        ModelDeliveryState.Pending -> ModelPhasePresentation.PENDING
        is ModelDeliveryState.Downloading -> ModelPhasePresentation.DOWNLOADING
        ModelDeliveryState.Transferring -> ModelPhasePresentation.TRANSFERRING
        ModelDeliveryState.WaitingForWifi -> ModelPhasePresentation.WAITING_FOR_WIFI
        ModelDeliveryState.RequiresConfirmation -> ModelPhasePresentation.CONFIRMATION_REQUIRED
        ModelDeliveryState.Provisioning -> {
            if (summary.role == ModelRole.CHAT_AND_VISION) {
                ModelPhasePresentation.PROVISIONING_CHAT
            } else {
                ModelPhasePresentation.PROVISIONING
            }
        }
        ModelDeliveryState.Installed -> when (summary.readiness) {
            ModelReadiness.STARTING -> {
                if (summary.evidence == ModelRuntimeEvidence.DASHBOARD_VERIFICATION) {
                    ModelPhasePresentation.VERIFICATION_RUNNING
                } else {
                    ModelPhasePresentation.STARTING
                }
            }
            ModelReadiness.READY -> ModelPhasePresentation.READY
            ModelReadiness.NEEDS_ATTENTION -> when (summary.runtimeIssue) {
                is ModelRuntimeIssue.InitializationFailed ->
                    ModelPhasePresentation.RUNTIME_INITIALIZATION_FAILED
                ModelRuntimeIssue.VerificationFailed ->
                    ModelPhasePresentation.RUNTIME_VERIFICATION_FAILED
                null -> ModelPhasePresentation.ATTENTION_REQUIRED
            }
            else -> ModelPhasePresentation.DOWNLOADED_IDLE
        }
        ModelDeliveryState.Activating -> ModelPhasePresentation.ACTIVATING
        ModelDeliveryState.InstalledWithActivationError ->
            ModelPhasePresentation.ACTIVATION_FAILED
        ModelDeliveryState.Removing -> ModelPhasePresentation.REMOVING
        ModelDeliveryState.Quiescing -> ModelPhasePresentation.QUIESCING
        is ModelDeliveryState.RemovalFailed -> when (delivery.issue) {
            ModelDeliveryIssue.RemovalInterrupted -> ModelPhasePresentation.REMOVAL_INTERRUPTED
            else -> ModelPhasePresentation.REMOVAL_FAILED
        }
        is ModelDeliveryState.Failed -> when (delivery.issue) {
            is ModelDeliveryIssue.InsufficientStorage ->
                ModelPhasePresentation.INSUFFICIENT_STORAGE
            ModelDeliveryIssue.ConfirmationUnavailable ->
                ModelPhasePresentation.CONFIRMATION_UNAVAILABLE
            ModelDeliveryIssue.VerificationFailed ->
                ModelPhasePresentation.VERIFICATION_FAILED
            ModelDeliveryIssue.ActivationFailed ->
                ModelPhasePresentation.ACTIVATION_FAILED
            ModelDeliveryIssue.RemovalInterrupted ->
                ModelPhasePresentation.REMOVAL_INTERRUPTED
            ModelDeliveryIssue.RemovalFailed ->
                ModelPhasePresentation.REMOVAL_FAILED
            ModelDeliveryIssue.PlayDeliveryFailed ->
                ModelPhasePresentation.DOWNLOAD_FAILED
            ModelDeliveryIssue.RefreshFailed ->
                ModelPhasePresentation.ATTENTION_REQUIRED
        }
        ModelDeliveryState.Unsupported -> ModelPhasePresentation.UNAVAILABLE
    }

internal fun runtimeSummaryCategory(summary: RoleModelSummary): RuntimeSummaryCategory =
    when (summary.evidence) {
        ModelRuntimeEvidence.LIVE_SERVICE -> when (summary.state) {
            ModelLoadState.READY -> RuntimeSummaryCategory.LIVE_READY
            ModelLoadState.STARTING -> RuntimeSummaryCategory.LIVE_STARTING
            ModelLoadState.FAILED -> RuntimeSummaryCategory.LIVE_FAILED
            ModelLoadState.IDLE,
            ModelLoadState.NOT_VERIFIED,
            -> RuntimeSummaryCategory.LIVE_NOT_LOADED
        }
        ModelRuntimeEvidence.LAST_KNOWN_SERVICE -> when (summary.state) {
            ModelLoadState.READY -> RuntimeSummaryCategory.LAST_KNOWN_READY
            ModelLoadState.FAILED -> RuntimeSummaryCategory.LAST_KNOWN_FAILED
            ModelLoadState.IDLE,
            ModelLoadState.STARTING,
            ModelLoadState.NOT_VERIFIED,
            -> RuntimeSummaryCategory.LAST_KNOWN_NOT_LOADED
        }
        ModelRuntimeEvidence.SERVICE_STATUS_UNAVAILABLE ->
            RuntimeSummaryCategory.SERVICE_UNAVAILABLE
        ModelRuntimeEvidence.DASHBOARD_VERIFICATION -> when {
            summary.state == ModelLoadState.STARTING ->
                RuntimeSummaryCategory.VERIFICATION_RUNNING
            summary.lastVerificationPassed == true ->
                RuntimeSummaryCategory.VERIFICATION_PASSED
            summary.lastVerificationPassed == false ->
                RuntimeSummaryCategory.VERIFICATION_FAILED
            else -> RuntimeSummaryCategory.VERIFICATION_NOT_RUN
        }
    }

internal fun phaseTone(
    phase: ModelPhasePresentation,
    readiness: ModelReadiness,
): DashboardMessageTone = when (phase) {
    ModelPhasePresentation.INSUFFICIENT_STORAGE,
    ModelPhasePresentation.CONFIRMATION_UNAVAILABLE,
    ModelPhasePresentation.REMOVAL_INTERRUPTED,
    ModelPhasePresentation.UNAVAILABLE,
    -> DashboardMessageTone.WARNING
    ModelPhasePresentation.DOWNLOAD_FAILED,
    ModelPhasePresentation.VERIFICATION_FAILED,
    ModelPhasePresentation.ACTIVATION_FAILED,
    ModelPhasePresentation.REMOVAL_FAILED,
    ModelPhasePresentation.RUNTIME_INITIALIZATION_FAILED,
    ModelPhasePresentation.RUNTIME_VERIFICATION_FAILED,
    ModelPhasePresentation.ATTENTION_REQUIRED,
    -> DashboardMessageTone.ERROR
    else -> modelReadinessTone(readiness)
}

internal fun modelActionAvailability(state: ModelDeliveryState): ModelActionAvailability =
    when (val action = modelDeliveryAction(state)) {
        ModelDeliveryAction.REMOVE ->
            ModelActionAvailability(primary = ModelDeliveryAction.NONE, secondary = action)
        ModelDeliveryAction.RETRY_ACTIVATION ->
            ModelActionAvailability(primary = action, secondary = ModelDeliveryAction.REMOVE)
        else -> ModelActionAvailability(primary = action)
    }
