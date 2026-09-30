package com.adsamcik.mindlayer.service.ui

import dev.tracebox.Tracebox
import dev.tracebox.api.LogTemplate
import dev.tracebox.api.TraceboxLogger
import dev.tracebox.api.public
import com.adsamcik.mindlayer.service.modeldelivery.ModelDeliveryIssue
import com.adsamcik.mindlayer.service.modeldelivery.issueOrNull

/** Captures the context of a report without test output, prompts or error messages. */
internal object DashboardDiagnostics {
    fun record(state: DashboardUiState, logger: TraceboxLogger = Tracebox.log) {
        logger.info(
            statusTemplate,
            public(state.connectionState), public(state.lastStatusUpdateMs), public(state.lastLogsUpdateMs),
            public(state.isEngineLoaded), public(state.backend.takeIf { it in setOf("CPU", "GPU", "NPU", "NONE") }),
            public(state.availableRamMb), public(state.totalRamMb), public(state.engineMaxTokens),
            public(state.thermalTelemetryAvailable), public(state.serviceThrottled), public(state.recentDeathCount),
            public(state.statusErrorMessage != null), public(state.logsErrorMessage != null),
        )
        val checks = listOf(
            "chat" to EngineTestState(isRunning = state.isTestRunning, tone = state.testStatusTone, lastCompletedAtMs = state.lastTestCompletedAtMs),
            "embeddings" to state.embeddingTest,
            "ocr" to state.ocrTest,
            "image" to state.imageInferenceTest,
            "sdk_async" to state.sdkInferAsyncTest,
            "sdk_realtime" to state.sdkInferRealtimeTest,
            "sdk_image" to state.sdkGenerateWithImageTest,
            "ocr_extraction" to state.ocrLlmExtractionTest,
        )
        checks.forEach { (name, check) ->
            logger.info(checkTemplate, public(name), public(check.isRunning), public(check.tone), public(check.lastCompletedAtMs))
        }
        state.modelDelivery.forEach { (role, delivery) ->
            val issue = delivery.issueOrNull()
            val storage = issue as? ModelDeliveryIssue.InsufficientStorage
            logger.info(modelTemplate, public(role), public(delivery.javaClass.simpleName),
                public(issue?.javaClass?.simpleName), public(storage?.requiredBytes), public(storage?.availableBytes))
        }
        logger.info(refreshTemplate, public(state.modelDeliveryRefresh.isRefreshing),
            public(state.modelDeliveryRefresh.lastSuccessfulRefreshAtMs), public(state.modelDeliveryRefresh.issue?.javaClass?.simpleName))
    }

    private val statusTemplate = LogTemplate.of(
        "Mindlayer report connection={} status_at={} logs_at={} engine_loaded={} backend={} free_mb={} total_mb={} context_tokens={} thermal_available={} throttled={} recent_deaths={} status_error={} logs_error={}",
    )
    private val checkTemplate = LogTemplate.of("Mindlayer check name={} running={} result={} completed_at={}")
    private val modelTemplate = LogTemplate.of("Mindlayer model role={} delivery={} issue={} required_bytes={} available_bytes={}")
    private val refreshTemplate = LogTemplate.of("Mindlayer model refresh running={} last_success_at={} issue={}")
}
