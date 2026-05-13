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
    // F-056: audit trail for approve / deny / revoke / cert-rotation
    // events. Surfaced in dashboard logs for the user to inspect.
    const val SECURITY = "SECURITY"
}

object LogEvent {
    // Inference
    const val REQUEST_START = "request_start"
    const val REQUEST_COMPLETE = "request_complete"
    const val REQUEST_CANCEL = "request_cancel"
    const val REQUEST_ERROR = "request_error"
    const val TOOL_CALL = "tool_call"
    const val TOOL_RESULT = "tool_result"
    const val TOOL_CALL_EXIT = "tool_call_exit"
    const val TOOL_CALL_TIMEOUT = "tool_call_timeout"
    const val STREAM_FRAME_TOO_LARGE = "stream_frame_too_large"
    const val STREAM_BACKPRESSURE = "stream_backpressure"
    // F-036: model fabricated an unknown tool name OR emitted oversized
    // arguments; the orchestrator dropped/truncated the call. Logged
    // under LogCategory.SECURITY so the dashboard surfaces it.
    const val TOOL_CALL_REJECTED = "tool_call_rejected"
    const val USER_MESSAGE = "user_message"
    const val MODEL_RESPONSE = "model_response"
    // Thermal
    const val BAND_CHANGE = "band_change"
    const val BACKEND_SWITCH = "backend_switch"
    // Session
    const val SESSION_CREATED = "session_created"
    const val SESSION_DESTROYED = "session_destroyed"
    const val SESSION_EVICTED = "session_evicted"
    const val SESSION_QUOTA_EXCEEDED = "session_quota_exceeded"
    // Memory
    const val PRESSURE_CHANGE = "pressure_change"
    const val EVICTION_TRIGGERED = "eviction_triggered"
    // Engine
    const val ENGINE_INIT = "engine_init"
    const val ENGINE_SHUTDOWN = "engine_shutdown"
    const val ENGINE_FALLBACK = "engine_fallback"
    const val FGS_PROMOTED = "fgs_promoted"
    const val FGS_DEMOTED = "fgs_demoted"
    // F-077: typed init-failure category. The variant name lands in
    // `extraJson` under "failureCategory"; the optional safeLabel goes
    // in `errorMessage` (already F-006-clean — class-name-only). The
    // dashboard reads the latest row to render variant-specific
    // remediation copy.
    const val INIT_FAILURE_CATEGORIZED = "init_failure_categorized"
    // Error
    const val GENERAL_ERROR = "general_error"
    // Security
    const val SECURITY_DECISION = "security_decision"
    const val RATE_LIMIT_REJECT = "rate_limit_reject"
    const val ALLOWLIST_PENDING_RECORDED = "allowlist_pending_recorded"
    const val BINDER_DEATH_CLIENT = "binder_death_client"
    const val BINDER_DEATH_SELF = "binder_death_self"
    const val CRASH_LOOP_THROTTLE = "crash_loop_throttle"
}
