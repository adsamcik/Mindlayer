package com.adsamcik.mindlayer.service.ui

import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
    val layoutDirection = LocalLayoutDirection.current
    val safeInsets = WindowInsets.safeDrawing.asPaddingValues()
    val summaries = state.modelSummaries()
    val overview = modelOverview(summaries)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        LazyColumn(
            contentPadding = PaddingValues(
                start = safeInsets.calculateStartPadding(layoutDirection) + 16.dp,
                end = safeInsets.calculateEndPadding(layoutDirection) + 16.dp,
                top = safeInsets.calculateTopPadding() + 12.dp,
                bottom = safeInsets.calculateBottomPadding() + 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ModelsHeader(
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
            item { ModelOverviewSurface(overview) }
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
            item { OfflineBanner() }
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
    isRefreshing: Boolean,
    lastSuccessfulRefreshAtMs: Long?,
    onRefresh: () -> Unit,
) {
    val nowMs = System.currentTimeMillis()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.models_title),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
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
        )
        Button(
            onClick = onRefresh,
            enabled = !isRefreshing,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            if (isRefreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.models_refreshing_action))
            } else {
                Text(stringResource(R.string.models_refresh_action))
            }
        }
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
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.models_refresh_warning_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = palette.content,
            )
            Text(
                text = stringResource(R.string.models_refresh_failed_detail),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.content,
            )
            OutlinedButton(
                onClick = onRefresh,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                border = BorderStroke(1.dp, palette.content),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.content),
            ) {
                Text(stringResource(R.string.models_try_again_action))
            }
        }
    }
}

@Composable
private fun ModelOverviewSurface(overview: ModelOverview) {
    val tone = when (overview.kind) {
        ModelOverviewKind.NEEDS_ATTENTION -> DashboardMessageTone.WARNING
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
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = palette.container,
        contentColor = palette.content,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.models_overview_title),
                style = MaterialTheme.typography.labelLarge,
                color = palette.content,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = headline,
                style = MaterialTheme.typography.titleLarge,
                color = palette.content,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun OfflineBanner() {
    val palette = modelsTonePalette(DashboardMessageTone.INFO)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = palette.container,
        contentColor = palette.content,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = palette.content,
            )
            Text(
                text = stringResource(R.string.models_offline_banner),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.content,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
