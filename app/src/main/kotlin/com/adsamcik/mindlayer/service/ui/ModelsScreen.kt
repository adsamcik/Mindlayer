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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.adsamcik.mindlayer.service.R

@Composable
fun ModelsScreen(state: DashboardUiState) {
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
            summaries.forEachIndexed { index, summary ->
                item(key = summary.role) {
                    CardEnterAnimation(index + 2) {
                        RoleModelCard(summary)
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
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
private fun RoleModelCard(summary: RoleModelSummary) {
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
