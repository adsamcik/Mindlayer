package com.adsamcik.mindlayer.service.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.adsamcik.mindlayer.service.R
import com.adsamcik.mindlayer.service.engine.OcrAcceleratorFailureCache
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TestsScreen(
    state: DashboardUiState,
    onTestInference: () -> Unit = {},
    onTestEmbeddings: () -> Unit = {},
    onTestOcr: () -> Unit = {},
    onTestImageInference: () -> Unit = {},
    onTestSdkInferAsync: () -> Unit = {},
    onTestSdkInferRealtime: () -> Unit = {},
    onTestSdkGenerateWithImage: () -> Unit = {},
    onTestOcrLlmExtraction: () -> Unit = {},
    onClearOcrFailureCache: () -> Unit = {},
    onRunAllVerifications: () -> Unit = {},
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
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
            item {
                CardEnterAnimation(0) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.tests_title),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            text = stringResource(R.string.tests_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item { CardEnterAnimation(1) { WelcomeCard(state, onRunAllVerifications) } }
            item {
                CardEnterAnimation(2) {
                    TestInferenceCard(
                        state,
                        onTestInference,
                        onTestEmbeddings,
                        onTestOcr,
                        onTestImageInference,
                        onTestSdkInferAsync,
                        onTestSdkInferRealtime,
                        onTestSdkGenerateWithImage,
                        onTestOcrLlmExtraction,
                        onClearOcrFailureCache,
                    )
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

// ── Welcome / on-device AI summary ──────────────────────────────────────────

@Composable
private fun WelcomeCard(state: DashboardUiState, onRunAllVerifications: () -> Unit) {
    val tone = state.verifyAllSummaryTone()
    val isRunning = state.isAnyTestRunning
    val (pillLabel, pillTone) = welcomePill(state, tone, isRunning)
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.3f

    DashboardCard(title = stringResource(R.string.dashboard_welcome_title), icon = Icons.Filled.Info) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.dashboard_welcome_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = onRunAllVerifications,
                    enabled = state.canRunAllVerifications(),
                ) {
                    if (isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        if (isRunning) {
                            stringResource(R.string.dashboard_welcome_button_running)
                        } else {
                            stringResource(R.string.dashboard_welcome_button_run)
                        },
                    )
                }
                Badge(text = pillLabel, color = toneColor(pillTone))
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant
                            .copy(alpha = if (darkTheme) 0.35f else 0.25f),
                        shape = RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WelcomeEngineLine(
                    label = stringResource(R.string.dashboard_test_chat_label),
                    isRunning = state.isTestRunning,
                    completedAtMs = state.lastTestCompletedAtMs,
                    tone = if (state.lastTestCompletedAtMs != null) state.testStatusTone else null,
                )
                WelcomeEngineLine(
                    label = stringResource(R.string.dashboard_test_embeddings_label),
                    isRunning = state.embeddingTest.isRunning,
                    completedAtMs = state.embeddingTest.lastCompletedAtMs,
                    tone = if (state.embeddingTest.lastCompletedAtMs != null) state.embeddingTest.tone else null,
                )
                WelcomeEngineLine(
                    label = stringResource(R.string.dashboard_test_ocr_label),
                    isRunning = state.ocrTest.isRunning,
                    completedAtMs = state.ocrTest.lastCompletedAtMs,
                    tone = if (state.ocrTest.lastCompletedAtMs != null) state.ocrTest.tone else null,
                )
                WelcomeEngineLine(
                    label = stringResource(R.string.dashboard_test_image_inference_label),
                    isRunning = state.imageInferenceTest.isRunning,
                    completedAtMs = state.imageInferenceTest.lastCompletedAtMs,
                    tone = if (state.imageInferenceTest.lastCompletedAtMs != null) state.imageInferenceTest.tone else null,
                )
            }
        }
    }
}

@Composable
private fun WelcomeEngineLine(
    label: String,
    isRunning: Boolean,
    completedAtMs: Long?,
    tone: DashboardMessageTone?,
) {
    val effectiveTone = when {
        isRunning -> DashboardMessageTone.INFO
        tone != null -> tone
        else -> DashboardMessageTone.NEUTRAL
    }
    val badgeLabel = when {
        isRunning -> stringResource(R.string.dashboard_test_badge_running)
        completedAtMs == null -> stringResource(R.string.dashboard_test_badge_idle)
        tone == DashboardMessageTone.SUCCESS -> stringResource(R.string.dashboard_test_badge_pass)
        tone == DashboardMessageTone.WARNING -> stringResource(R.string.dashboard_test_badge_warn)
        tone == DashboardMessageTone.ERROR -> stringResource(R.string.dashboard_test_badge_fail)
        else -> stringResource(R.string.dashboard_test_badge_idle)
    }
    val nowMs = System.currentTimeMillis()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            completedAtMs?.let { ts ->
                if (!isRunning) {
                    Text(
                        text = formatRelativeTimestamp(ts, nowMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Badge(text = badgeLabel, color = toneColor(effectiveTone))
        }
    }
}

@Composable
private fun welcomePill(
    state: DashboardUiState,
    tone: DashboardMessageTone,
    isRunning: Boolean,
): Pair<String, DashboardMessageTone> {
    val hasAnyCompleted = state.lastTestCompletedAtMs != null ||
        state.embeddingTest.lastCompletedAtMs != null ||
        state.ocrTest.lastCompletedAtMs != null ||
        state.imageInferenceTest.lastCompletedAtMs != null
    return when {
        isRunning -> stringResource(R.string.dashboard_welcome_state_running) to DashboardMessageTone.INFO
        !hasAnyCompleted -> stringResource(R.string.dashboard_welcome_state_idle) to DashboardMessageTone.NEUTRAL
        tone == DashboardMessageTone.SUCCESS ->
            stringResource(R.string.dashboard_welcome_state_pass) to DashboardMessageTone.SUCCESS
        tone == DashboardMessageTone.WARNING ->
            stringResource(R.string.dashboard_welcome_state_warn) to DashboardMessageTone.WARNING
        tone == DashboardMessageTone.ERROR ->
            stringResource(R.string.dashboard_welcome_state_fail) to DashboardMessageTone.ERROR
        else -> stringResource(R.string.dashboard_welcome_state_partial) to DashboardMessageTone.INFO
    }
}

// ── Engine verification ──────────────────────────────────────────────────────

@Composable
private fun TestInferenceCard(
    state: DashboardUiState,
    onTestInference: () -> Unit,
    onTestEmbeddings: () -> Unit,
    onTestOcr: () -> Unit,
    onTestImageInference: () -> Unit,
    onTestSdkInferAsync: () -> Unit,
    onTestSdkInferRealtime: () -> Unit,
    onTestSdkGenerateWithImage: () -> Unit,
    onTestOcrLlmExtraction: () -> Unit,
    onClearOcrFailureCache: () -> Unit,
) {
    DashboardCard(title = stringResource(R.string.dashboard_card_test_inference_title), icon = Icons.Filled.PlayArrow) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ChatEngineRow(state, onTestInference)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            EmbeddingEngineRow(state, onTestEmbeddings)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            OcrEngineRow(state, onTestOcr, onClearOcrFailureCache)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ImageInferenceEngineRow(state, onTestImageInference)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SdkInferAsyncEngineRow(state, onTestSdkInferAsync)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SdkInferRealtimeEngineRow(state, onTestSdkInferRealtime)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SdkGenerateWithImageEngineRow(state, onTestSdkGenerateWithImage)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            OcrLlmExtractionEngineRow(state, onTestOcrLlmExtraction)
        }
    }
}

@Composable
private fun ChatEngineRow(state: DashboardUiState, onTestInference: () -> Unit) {
    val nowMs = System.currentTimeMillis()
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.3f
    val displayTone = when {
        state.isTestRunning -> DashboardMessageTone.INFO
        state.testStatus.isBlank() -> DashboardMessageTone.NEUTRAL
        else -> state.testStatusTone
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.dashboard_test_chat_label),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.dashboard_test_chat_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(
                onClick = onTestInference,
                enabled = state.canRunTestInference(nowMs),
            ) {
                if (state.isTestRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (state.isTestRunning) stringResource(R.string.dashboard_test_button_running) else stringResource(R.string.dashboard_test_button_run))
            }
            Column(horizontalAlignment = Alignment.End) {
                Badge(text = testBadgeLabel(state), color = toneColor(displayTone))
                state.lastTestCompletedAtMs?.let { ts ->
                    if (!state.isTestRunning) {
                        Text(
                            text = formatRelativeTimestamp(ts, nowMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }

        if (state.testStatus.isNotBlank()) {
            DiagnosticCallout(message = state.testStatus, tone = displayTone)
        } else {
            Text(
                text = stringResource(R.string.dashboard_test_verify),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(visible = state.shouldHighlightTestResult(nowMs)) {
            DiagnosticCallout(
                message = stringResource(R.string.dashboard_callout_engine_not_ready),
                tone = DashboardMessageTone.WARNING,
            )
        }

        val hasOutput = state.testOutput.isNotBlank()
        if (hasOutput || state.isTestRunning) {
            EngineOutputBox(
                output = state.testOutput,
                hasOutput = hasOutput,
                darkTheme = darkTheme,
            )
        }
    }
}

@Composable
private fun EmbeddingEngineRow(state: DashboardUiState, onTestEmbeddings: () -> Unit) {
    val nowMs = System.currentTimeMillis()
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.3f
    val test = state.embeddingTest
    val displayTone = when {
        test.isRunning -> DashboardMessageTone.INFO
        test.status.isBlank() -> DashboardMessageTone.NEUTRAL
        else -> test.tone
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.dashboard_test_embeddings_label),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.dashboard_test_embeddings_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(
                onClick = onTestEmbeddings,
                enabled = state.canRunEmbeddingTest(),
            ) {
                if (test.isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    if (test.isRunning) {
                        stringResource(R.string.dashboard_test_embedding_button_running)
                    } else {
                        stringResource(R.string.dashboard_test_embedding_button_run)
                    },
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Badge(text = engineTestBadgeLabel(test), color = toneColor(displayTone))
                test.lastCompletedAtMs?.let { ts ->
                    if (!test.isRunning) {
                        Text(
                            text = formatRelativeTimestamp(ts, nowMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }

        if (test.status.isNotBlank()) {
            DiagnosticCallout(message = test.status, tone = displayTone)
        }

        val hasOutput = test.output.isNotBlank()
        if (hasOutput || test.isRunning) {
            EngineOutputBox(
                output = test.output,
                hasOutput = hasOutput,
                darkTheme = darkTheme,
            )
        }
    }
}

@Composable
private fun OcrEngineRow(
    state: DashboardUiState,
    onTestOcr: () -> Unit,
    onClearOcrFailureCache: () -> Unit,
) {
    val nowMs = System.currentTimeMillis()
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.3f
    val test = state.ocrTest
    val displayTone = when {
        test.isRunning -> DashboardMessageTone.INFO
        test.status.isBlank() -> DashboardMessageTone.NEUTRAL
        else -> test.tone
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.dashboard_test_ocr_label),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.dashboard_test_ocr_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(
                onClick = onTestOcr,
                enabled = state.canRunOcrTest(),
            ) {
                if (test.isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    if (test.isRunning) {
                        stringResource(R.string.dashboard_test_ocr_button_running)
                    } else {
                        stringResource(R.string.dashboard_test_ocr_button_run)
                    },
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Badge(text = engineTestBadgeLabel(test), color = toneColor(displayTone))
                test.lastCompletedAtMs?.let { ts ->
                    if (!test.isRunning) {
                        Text(
                            text = formatRelativeTimestamp(ts, nowMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }

        OcrAcceleratorCooldownRow(
            failure = state.ocrFailureSnapshot,
            cooldownMs = state.ocrFailureCooldownMs,
            nowMs = nowMs,
            onRetryNow = onClearOcrFailureCache,
        )

        if (test.status.isNotBlank()) {
            DiagnosticCallout(message = test.status, tone = displayTone)
        }

        val hasOutput = test.output.isNotBlank()
        if (hasOutput || test.isRunning) {
            EngineOutputBox(
                output = test.output,
                hasOutput = hasOutput,
                darkTheme = darkTheme,
            )
        }
    }
}

@Composable
private fun ImageInferenceEngineRow(
    state: DashboardUiState,
    onTestImageInference: () -> Unit,
) {
    val nowMs = System.currentTimeMillis()
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.3f
    val test = state.imageInferenceTest
    val displayTone = when {
        test.isRunning -> DashboardMessageTone.INFO
        test.status.isBlank() -> DashboardMessageTone.NEUTRAL
        else -> test.tone
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.dashboard_test_image_inference_label),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.dashboard_test_image_inference_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(
                onClick = onTestImageInference,
                enabled = state.canRunImageInferenceTest(),
            ) {
                if (test.isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    if (test.isRunning) {
                        stringResource(R.string.dashboard_test_image_inference_button_running)
                    } else {
                        stringResource(R.string.dashboard_test_image_inference_button_run)
                    },
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Badge(text = engineTestBadgeLabel(test), color = toneColor(displayTone))
                test.lastCompletedAtMs?.let { ts ->
                    if (!test.isRunning) {
                        Text(
                            text = formatRelativeTimestamp(ts, nowMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }

        if (test.status.isNotBlank()) {
            DiagnosticCallout(message = test.status, tone = displayTone)
        }

        val hasOutput = test.output.isNotBlank()
        if (hasOutput || test.isRunning) {
            EngineOutputBox(
                output = test.output,
                hasOutput = hasOutput,
                darkTheme = darkTheme,
            )
        }
    }
}

@Composable
private fun SdkInferAsyncEngineRow(
    state: DashboardUiState,
    onTestSdkInferAsync: () -> Unit,
) {
    val nowMs = System.currentTimeMillis()
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.3f
    val test = state.sdkInferAsyncTest
    val displayTone = when {
        test.isRunning -> DashboardMessageTone.INFO
        test.status.isBlank() -> DashboardMessageTone.NEUTRAL
        else -> test.tone
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.dashboard_test_sdk_infer_async_label),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.dashboard_test_sdk_infer_async_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(
                onClick = onTestSdkInferAsync,
                enabled = state.canRunSdkInferAsyncTest(),
            ) {
                if (test.isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    if (test.isRunning) {
                        stringResource(R.string.dashboard_test_sdk_infer_async_button_running)
                    } else {
                        stringResource(R.string.dashboard_test_sdk_infer_async_button_run)
                    },
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Badge(text = engineTestBadgeLabel(test), color = toneColor(displayTone))
                test.lastCompletedAtMs?.let { ts ->
                    if (!test.isRunning) {
                        Text(
                            text = formatRelativeTimestamp(ts, nowMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }

        if (test.status.isNotBlank()) {
            DiagnosticCallout(message = test.status, tone = displayTone)
        }

        val hasOutput = test.output.isNotBlank()
        if (hasOutput || test.isRunning) {
            EngineOutputBox(
                output = test.output,
                hasOutput = hasOutput,
                darkTheme = darkTheme,
            )
        }
    }
}

@Composable
private fun SdkInferRealtimeEngineRow(
    state: DashboardUiState,
    onTestSdkInferRealtime: () -> Unit,
) {
    val nowMs = System.currentTimeMillis()
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.3f
    val test = state.sdkInferRealtimeTest
    val displayTone = when {
        test.isRunning -> DashboardMessageTone.INFO
        test.status.isBlank() -> DashboardMessageTone.NEUTRAL
        else -> test.tone
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.dashboard_test_sdk_infer_realtime_label),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.dashboard_test_sdk_infer_realtime_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(
                onClick = onTestSdkInferRealtime,
                enabled = state.canRunSdkInferRealtimeTest(),
            ) {
                if (test.isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    if (test.isRunning) {
                        stringResource(R.string.dashboard_test_sdk_infer_realtime_button_running)
                    } else {
                        stringResource(R.string.dashboard_test_sdk_infer_realtime_button_run)
                    },
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Badge(text = engineTestBadgeLabel(test), color = toneColor(displayTone))
                test.lastCompletedAtMs?.let { ts ->
                    if (!test.isRunning) {
                        Text(
                            text = formatRelativeTimestamp(ts, nowMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }

        if (test.status.isNotBlank()) {
            DiagnosticCallout(message = test.status, tone = displayTone)
        }

        val hasOutput = test.output.isNotBlank()
        if (hasOutput || test.isRunning) {
            EngineOutputBox(
                output = test.output,
                hasOutput = hasOutput,
                darkTheme = darkTheme,
            )
        }
    }
}

@Composable
private fun SdkGenerateWithImageEngineRow(
    state: DashboardUiState,
    onTestSdkGenerateWithImage: () -> Unit,
) {
    val nowMs = System.currentTimeMillis()
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.3f
    val test = state.sdkGenerateWithImageTest
    val displayTone = when {
        test.isRunning -> DashboardMessageTone.INFO
        test.status.isBlank() -> DashboardMessageTone.NEUTRAL
        else -> test.tone
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.dashboard_test_sdk_generate_with_image_label),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.dashboard_test_sdk_generate_with_image_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(
                onClick = onTestSdkGenerateWithImage,
                enabled = state.canRunSdkGenerateWithImageTest(),
            ) {
                if (test.isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    if (test.isRunning) {
                        stringResource(R.string.dashboard_test_sdk_generate_with_image_button_running)
                    } else {
                        stringResource(R.string.dashboard_test_sdk_generate_with_image_button_run)
                    },
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Badge(text = engineTestBadgeLabel(test), color = toneColor(displayTone))
                test.lastCompletedAtMs?.let { ts ->
                    if (!test.isRunning) {
                        Text(
                            text = formatRelativeTimestamp(ts, nowMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }

        if (test.status.isNotBlank()) {
            DiagnosticCallout(message = test.status, tone = displayTone)
        }

        val hasOutput = test.output.isNotBlank()
        if (hasOutput || test.isRunning) {
            EngineOutputBox(
                output = test.output,
                hasOutput = hasOutput,
                darkTheme = darkTheme,
            )
        }
    }
}

@Composable
private fun OcrLlmExtractionEngineRow(
    state: DashboardUiState,
    onTestOcrLlmExtraction: () -> Unit,
) {
    val nowMs = System.currentTimeMillis()
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.3f
    val test = state.ocrLlmExtractionTest
    val displayTone = when {
        test.isRunning -> DashboardMessageTone.INFO
        test.status.isBlank() -> DashboardMessageTone.NEUTRAL
        else -> test.tone
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.dashboard_test_ocr_llm_extraction_label),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.dashboard_test_ocr_llm_extraction_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(
                onClick = onTestOcrLlmExtraction,
                enabled = state.canRunOcrLlmExtractionTest(),
            ) {
                if (test.isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    if (test.isRunning) {
                        stringResource(R.string.dashboard_test_ocr_llm_extraction_button_running)
                    } else {
                        stringResource(R.string.dashboard_test_ocr_llm_extraction_button_run)
                    },
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Badge(text = engineTestBadgeLabel(test), color = toneColor(displayTone))
                test.lastCompletedAtMs?.let { ts ->
                    if (!test.isRunning) {
                        Text(
                            text = formatRelativeTimestamp(ts, nowMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }

        if (test.status.isNotBlank()) {
            DiagnosticCallout(message = test.status, tone = displayTone)
        }

        val hasOutput = test.output.isNotBlank()
        if (hasOutput || test.isRunning) {
            EngineOutputBox(
                output = test.output,
                hasOutput = hasOutput,
                darkTheme = darkTheme,
            )
        }
    }
}

@Composable
private fun OcrAcceleratorCooldownRow(
    failure: OcrAcceleratorFailureCache.FailureRecord?,
    cooldownMs: Long,
    nowMs: Long,
    onRetryNow: () -> Unit,
) {
    if (failure == null) return
    val cooldownEndsAtMs = failure.lastFailedAtMs + cooldownMs
    val inCooldown = nowMs in failure.lastFailedAtMs..cooldownEndsAtMs
    if (!inCooldown) return

    val tertiary = MaterialTheme.colorScheme.tertiary
    val timeFormatter = remember {
        DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT,
            Locale.getDefault(),
        )
    }
    val until = timeFormatter.format(Date(cooldownEndsAtMs))
    val backend = failure.lastFailedBackend
    val label = failure.lastFailedSafeLabel
    val count = failure.failureCount

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.dashboard_ocr_cooldown_message, backend, until),
                style = MaterialTheme.typography.bodySmall,
                color = tertiary,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(R.string.dashboard_ocr_cooldown_details, label, count),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRetryNow) {
            Text(stringResource(R.string.dashboard_ocr_cooldown_retry))
        }
    }
}
