package com.adsamcik.mindlayer.ocrdriver

import com.adsamcik.mindlayer.HealthCheck
import com.adsamcik.mindlayer.ServiceStatus
import com.adsamcik.mindlayer.sdk.ConnectionState

/**
 * Top-level UI state for the Mindlayer driver. One slice per tab plus
 * a [globalError] channel for cross-tab failures. Every slice is a
 * plain [data] class so Compose diffing is cheap.
 */
data class DriverUiState(
    val connection: ConnectionSlice = ConnectionSlice(),
    val inference: InferenceSlice = InferenceSlice(),
    val embeddings: EmbeddingsSlice = EmbeddingsSlice(),
    val ocr: OcrSlice = OcrSlice(),
    val diagnostics: DiagnosticsSlice = DiagnosticsSlice(),
    val validation: ValidationSlice = ValidationSlice(),
    val globalError: String? = null,
)

data class ConnectionSlice(
    val state: ConnectionState = ConnectionState.DISCONNECTED,
    val apiVersion: Int = 0,
    val allFeatures: List<String> = emptyList(),
    val maxRequestsPerMinute: Int = 0,
    val maxConcurrentSessions: Int = 0,
    val lastProbedAtMs: Long = 0L,
    val error: String? = null,
)

data class InferenceSlice(
    val inProgress: Boolean = false,
    val response: String = "",
    val durationMs: Long = 0L,
    val error: String? = null,
)

data class EmbeddingsSlice(
    val advertised: Boolean = false,
    val embeddingDims: List<Int> = emptyList(),
    val embeddingModelIds: List<String> = emptyList(),
    val inProgress: Boolean = false,
    val lastDim: Int = 0,
    val lastL2Norm: Float = 0f,
    val lastFirstFew: String = "",
    val lastDurationMs: Long = 0L,
    val error: String? = null,
)

data class OcrSlice(
    val asyncAdvertised: Boolean = false,
    val sessionAdvertised: Boolean = false,
    val inProgress: Boolean = false,
    val lastFixture: String = "",
    val lastLineCount: Int = 0,
    val lastWithBbox: Int = 0,
    val lastOcrMs: Long = 0L,
    val lastLlmMs: Long = 0L,
    val lastFields: Int = 0,
    val lastPreview: String = "",
    val lastTotalMs: Long = 0L,
    val error: String? = null,
)

data class DiagnosticsSlice(
    val inProgress: Boolean = false,
    val status: ServiceStatus? = null,
    val ping: HealthCheck? = null,
    val sessionCount: Int = 0,
    val sessionPreview: List<String> = emptyList(),
    val typedDiagJson: String? = null,
    val error: String? = null,
)

data class ValidationSlice(
    val inProgress: Boolean = false,
    val scenarios: List<ValidationScenarioResult> = emptyList(),
    val reportPath: String? = null,
    val error: String? = null,
)
