package com.adsamcik.mindlayer.service.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.mindlayer.service.engine.OcrAcceleratorFailureCache
import com.adsamcik.mindlayer.service.R
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
    onNavigateToHistory: () -> Unit = {},
    onNavigateToLogs: () -> Unit = {},
    onNavigateToDiagnostics: () -> Unit = {},
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        LazyColumn(
            contentPadding = MindlayerScreenDefaults.ContentPadding,
            verticalArrangement = Arrangement.spacedBy(MindlayerScreenDefaults.ItemSpacing),
        ) {
            item {
                CardEnterAnimation(0) {
                    MindlayerPageHeader(
                        title = stringResource(R.string.tests_title),
                    )
                }
            }
            item {
                Button(
                    onClick = onNavigateToDiagnostics,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.troubleshoot_report_action))
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
            item { ActivityNavigationCard(onNavigateToHistory, onNavigateToLogs) }
            item { Spacer(Modifier.height(MindlayerScreenDefaults.BottomSpacerHeight)) }
        }
    }
}

// ── Welcome / on-device AI summary ──────────────────────────────────────────

@Composable
private fun WelcomeCard(state: DashboardUiState, onRunAllVerifications: () -> Unit) {
    val tone = state.verifyAllSummaryTone()
    val isRunning = state.isAnyTestRunning
    val (pillLabel, pillTone) = welcomePill(state, tone, isRunning)
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
        }
    }
}

@Composable
private fun welcomePill(
    state: DashboardUiState,
    tone: DashboardMessageTone,
    isRunning: Boolean,
): Pair<String, DashboardMessageTone> {
    val completedCount = listOf(
        state.lastTestCompletedAtMs,
        state.embeddingTest.lastCompletedAtMs,
        state.ocrTest.lastCompletedAtMs,
        state.imageInferenceTest.lastCompletedAtMs,
        state.sdkInferAsyncTest.lastCompletedAtMs,
        state.sdkInferRealtimeTest.lastCompletedAtMs,
        state.sdkGenerateWithImageTest.lastCompletedAtMs,
        state.ocrLlmExtractionTest.lastCompletedAtMs,
    ).count { it != null }
    return when {
        isRunning -> stringResource(R.string.dashboard_welcome_state_running) to DashboardMessageTone.INFO
        completedCount == 0 -> stringResource(R.string.dashboard_welcome_state_idle) to DashboardMessageTone.NEUTRAL
        completedCount == 8 && tone == DashboardMessageTone.SUCCESS ->
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
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CheckDisclosure(
                title = stringResource(R.string.dashboard_test_chat_label),
                status = testBadgeLabel(state),
                tone = if (state.isTestRunning) DashboardMessageTone.INFO else state.testStatusTone,
            ) { ChatEngineRow(state, onTestInference) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            CheckDisclosure(
                title = stringResource(R.string.dashboard_test_embeddings_label),
                status = engineTestBadgeLabel(state.embeddingTest),
                tone = if (state.embeddingTest.isRunning) DashboardMessageTone.INFO else state.embeddingTest.tone,
            ) { EmbeddingEngineRow(state, onTestEmbeddings) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            CheckDisclosure(
                title = stringResource(R.string.dashboard_test_ocr_label),
                status = engineTestBadgeLabel(state.ocrTest),
                tone = if (state.ocrTest.isRunning) DashboardMessageTone.INFO else state.ocrTest.tone,
            ) { OcrEngineRow(state, onTestOcr, onClearOcrFailureCache) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            CheckDisclosure(
                title = stringResource(R.string.dashboard_test_image_inference_label),
                status = engineTestBadgeLabel(state.imageInferenceTest),
                tone = if (state.imageInferenceTest.isRunning) DashboardMessageTone.INFO else state.imageInferenceTest.tone,
            ) { ImageInferenceEngineRow(state, onTestImageInference) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            CheckDisclosure(
                title = stringResource(R.string.dashboard_test_sdk_infer_async_label),
                status = engineTestBadgeLabel(state.sdkInferAsyncTest),
                tone = if (state.sdkInferAsyncTest.isRunning) DashboardMessageTone.INFO else state.sdkInferAsyncTest.tone,
            ) { SdkInferAsyncEngineRow(state, onTestSdkInferAsync) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            CheckDisclosure(
                title = stringResource(R.string.dashboard_test_sdk_infer_realtime_label),
                status = engineTestBadgeLabel(state.sdkInferRealtimeTest),
                tone = if (state.sdkInferRealtimeTest.isRunning) DashboardMessageTone.INFO else state.sdkInferRealtimeTest.tone,
            ) { SdkInferRealtimeEngineRow(state, onTestSdkInferRealtime) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            CheckDisclosure(
                title = stringResource(R.string.dashboard_test_sdk_generate_with_image_label),
                status = engineTestBadgeLabel(state.sdkGenerateWithImageTest),
                tone = if (state.sdkGenerateWithImageTest.isRunning) DashboardMessageTone.INFO else state.sdkGenerateWithImageTest.tone,
            ) { SdkGenerateWithImageEngineRow(state, onTestSdkGenerateWithImage) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            CheckDisclosure(
                title = stringResource(R.string.dashboard_test_ocr_llm_extraction_label),
                status = engineTestBadgeLabel(state.ocrLlmExtractionTest),
                tone = if (state.ocrLlmExtractionTest.isRunning) DashboardMessageTone.INFO else state.ocrLlmExtractionTest.tone,
            ) { OcrLlmExtractionEngineRow(state, onTestOcrLlmExtraction) }
        }
    }
}

@Composable
private fun CheckDisclosure(
    title: String,
    status: String,
    tone: DashboardMessageTone,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    val expansionState = stringResource(
        if (expanded) R.string.dashboard_details_expanded else R.string.dashboard_details_collapsed,
    )
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = expansionState }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
            )
            if (!expanded) {
                Badge(text = status, color = toneColor(tone))
            }
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(20.dp).rotate(if (expanded) 180f else 0f),
            )
        }
        if (expanded) {
            Column(modifier = Modifier.padding(bottom = 12.dp)) { content() }
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
