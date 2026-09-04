package com.adsamcik.mindlayer.service.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adsamcik.mindlayer.service.R
import com.adsamcik.mindlayer.service.modeldelivery.ModelDeliveryIssue

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
    val summaries = state.modelSummaries()
    val overview = modelOverview(summaries)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        LazyColumn(
            contentPadding = MindlayerScreenDefaults.ContentPadding,
            verticalArrangement = Arrangement.spacedBy(MindlayerScreenDefaults.ItemSpacing),
        ) {
            item {
                ModelsHeader(
                    overview = overview,
                    isRefreshing = state.modelDeliveryRefresh.isRefreshing,
                    lastSuccessfulRefreshAtMs =
                        state.modelDeliveryRefresh.lastSuccessfulRefreshAtMs,
                    onRefresh = onRefresh,
                )
            }
            if (state.modelDeliveryRefresh.issue == ModelDeliveryIssue.RefreshFailed) {
                item {
                    RefreshWarning(
                        enabled = !state.modelDeliveryRefresh.isRefreshing,
                        onRefresh = onRefresh,
                    )
                }
            }
            summaries.forEach { summary ->
                item(key = summary.role) {
                    RoleModelCard(
                        summary = summary,
                        onDownload = { onDownload(summary.role) },
                        onRemove = { pendingRemoval = summary },
                        onRetryActivation = { onRetryActivation(summary.role) },
                        onConfirmDownload = onConfirmDownload,
                    )
                }
            }
            item { Spacer(Modifier.height(MindlayerScreenDefaults.BottomSpacerHeight)) }
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
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.models_remove_action)) }
                },
                dismissButton = {
                    TextButton(
                        onClick = { pendingRemoval = null },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.models_cancel_action))
                    }
                },
            )
        }
    }
}

@Composable
private fun ModelsHeader(
    overview: ModelOverview,
    isRefreshing: Boolean,
    lastSuccessfulRefreshAtMs: Long?,
    onRefresh: () -> Unit,
) {
    val nowMs = System.currentTimeMillis()
    val refreshDescription = stringResource(
        if (isRefreshing) {
            R.string.models_refreshing_action
        } else {
            R.string.models_refresh_action
        },
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MindlayerPageHeader(
            title = stringResource(R.string.models_title),
            subtitle = stringResource(R.string.models_subtitle),
        ) {
            FilledTonalIconButton(
                onClick = onRefresh,
                enabled = !isRefreshing,
                modifier = Modifier.semantics {
                    contentDescription = refreshDescription
                },
            ) {
                if (isRefreshing) {
                    LoadingIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ModelOverviewPill(
                overview = overview,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = lastSuccessfulRefreshAtMs?.let { timestamp ->
                    stringResource(
                        R.string.models_delivery_checked,
                        DateUtils.getRelativeTimeSpanString(
                            timestamp,
                            nowMs,
                            DateUtils.SECOND_IN_MILLIS,
                            DateUtils.FORMAT_ABBREV_RELATIVE,
                        ),
                    )
                } ?: stringResource(R.string.models_delivery_not_checked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ModelOverviewPill(
    overview: ModelOverview,
    modifier: Modifier = Modifier,
) {
    val tone = when (overview.kind) {
        ModelOverviewKind.NEEDS_ATTENTION -> DashboardMessageTone.WARNING
        ModelOverviewKind.CHECKING,
        ModelOverviewKind.ACTIVITY_IN_PROGRESS -> DashboardMessageTone.INFO
        ModelOverviewKind.DOWNLOADED_COUNT -> DashboardMessageTone.NEUTRAL
        ModelOverviewKind.ALL_AVAILABLE -> DashboardMessageTone.SUCCESS
    }
    val palette = modelsTonePalette(tone)
    val headline = when (overview.kind) {
        ModelOverviewKind.NEEDS_ATTENTION -> pluralStringResource(
            R.plurals.models_overview_attention,
            overview.affectedCount,
            overview.affectedCount,
        )
        ModelOverviewKind.CHECKING -> pluralStringResource(
            R.plurals.models_overview_checking,
            overview.affectedCount,
            overview.affectedCount,
        )
        ModelOverviewKind.ACTIVITY_IN_PROGRESS -> pluralStringResource(
            R.plurals.models_overview_activity,
            overview.affectedCount,
            overview.affectedCount,
        )
        ModelOverviewKind.DOWNLOADED_COUNT -> pluralStringResource(
            R.plurals.models_overview_downloaded_count,
            overview.downloadedCount,
            overview.downloadedCount,
            overview.totalCount,
        )
        ModelOverviewKind.ALL_AVAILABLE -> pluralStringResource(
            R.plurals.models_overview_all_available,
            overview.totalCount,
            overview.totalCount,
        )
    }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = palette.container,
        contentColor = palette.content,
    ) {
        Text(
            text = headline,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            color = palette.content,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RefreshWarning(
    enabled: Boolean,
    onRefresh: () -> Unit,
) {
    val palette = modelsTonePalette(DashboardMessageTone.WARNING)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.large,
        color = palette.container,
        contentColor = palette.content,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = palette.content,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.models_refresh_warning_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.content,
                )
                Text(
                    text = stringResource(R.string.models_refresh_failed_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.content,
                )
            }
            TextButton(
                onClick = onRefresh,
                enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = palette.content,
                    disabledContentColor = palette.content.copy(alpha = 0.38f),
                ),
            ) {
                Text(stringResource(R.string.models_try_again_action))
            }
        }
    }
}
