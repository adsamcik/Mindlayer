package com.mindlayer.service.ui

data class DashboardUiState(
    // Engine
    val isEngineLoaded: Boolean = false,
    val backend: String = "NONE",
    val initTimeSeconds: Float = 0f,
    val uptimeMs: Long = 0,
    val modelPath: String = "",
    // Thermal
    val thermalBand: String = "COOL",
    val recommendedBackend: String = "GPU",
    val burstSeconds: Int = 12,
    val restSeconds: Int = 0,
    val chunkTokens: Int = 128,
    val headroom: Float? = null,
    // Memory
    val memoryPressure: String = "NORMAL",
    val availableRamMb: Long = 0,
    val totalRamMb: Long = 0,
    val maxSessions: Int = 0,
    // Sessions
    val activeSessions: List<SessionUiItem> = emptyList(),
    // Logs
    val recentLogs: List<LogUiItem> = emptyList(),
    // Test
    val testStatus: String = "",
    val testOutput: String = "",
    val isTestRunning: Boolean = false,
)

data class SessionUiItem(
    val sessionId: String,
    val backend: String,
    val tokenCount: Int,
    val maxTokens: Int,
    val isStreaming: Boolean,
    val lastAccessedLabel: String,
)

data class LogUiItem(
    val timestampLabel: String,
    val category: String,
    val event: String,
    val detail: String,
)
