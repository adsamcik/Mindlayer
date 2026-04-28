package com.adsamcik.mindlayer.service.logging

import androidx.room.*

@Entity(
    tableName = "usage_logs",
    indices = [
        Index("category"),
        Index("sessionId"),
        Index("timestampMs"),
        Index(value = ["requestId", "timestampMs"], name = "index_usage_logs_request_timestamp"),
        Index(value = ["sessionId", "timestampMs"], name = "index_usage_logs_session_timestamp"),
        Index(value = ["category", "event", "timestampMs"], name = "index_usage_logs_category_event_timestamp"),
        Index(value = ["backend", "event", "timestampMs"], name = "index_usage_logs_backend_event_timestamp"),
    ],
)
data class LogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val category: String,      // INFERENCE, THERMAL, SESSION, MEMORY, ENGINE, ERROR
    val event: String,         // e.g. "request_start", "request_complete", "band_change", "session_created"
    val sessionId: String? = null,
    val requestId: String? = null,
    val backend: String? = null,
    val durationMs: Long? = null,
    val tokensGenerated: Int? = null,
    val tokensPerSec: Float? = null,
    val prefillTokensPerSec: Float? = null,
    val thermalBand: String? = null,
    val memoryUsedMb: Long? = null,
    val memoryAvailableMb: Long? = null,
    val errorMessage: String? = null,
    val extraJson: String? = null,  // flexible JSON for additional data
)

object LogCategory {
    const val INFERENCE = "INFERENCE"
    const val THERMAL = "THERMAL"
    const val SESSION = "SESSION"
    const val MEMORY = "MEMORY"
    const val ENGINE = "ENGINE"
    const val ERROR = "ERROR"
}

object LogEvent {
    // Inference
    const val REQUEST_START = "request_start"
    const val REQUEST_COMPLETE = "request_complete"
    const val REQUEST_CANCEL = "request_cancel"
    const val REQUEST_ERROR = "request_error"
    const val TOOL_CALL = "tool_call"
    const val TOOL_RESULT = "tool_result"
    const val USER_MESSAGE = "user_message"
    const val MODEL_RESPONSE = "model_response"
    // Thermal
    const val BAND_CHANGE = "band_change"
    const val BACKEND_SWITCH = "backend_switch"
    // Session
    const val SESSION_CREATED = "session_created"
    const val SESSION_DESTROYED = "session_destroyed"
    const val SESSION_EVICTED = "session_evicted"
    // Memory
    const val PRESSURE_CHANGE = "pressure_change"
    const val EVICTION_TRIGGERED = "eviction_triggered"
    // Engine
    const val ENGINE_INIT = "engine_init"
    const val ENGINE_SHUTDOWN = "engine_shutdown"
    const val ENGINE_FALLBACK = "engine_fallback"
    // Error
    const val GENERAL_ERROR = "general_error"
}
