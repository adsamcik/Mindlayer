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
    const val OCR = "OCR"
    const val EMBEDDING = "EMBEDDING"
    const val IPC = "IPC"
    const val AUTH = "AUTH"
    // F-056: audit trail for approve / deny / revoke / cert-rotation
    // events. Surfaced in dashboard logs for the user to inspect.
    const val SECURITY = "SECURITY"
}

enum class LogEvent(val key: String) {
    // Inference
    REQUEST_START("request_start"),
    REQUEST_COMPLETE("request_complete"),
    REQUEST_CANCEL("request_cancel"),
    REQUEST_ERROR("request_error"),
    TOOL_CALL("tool_call"),
    TOOL_CALL_EXIT("tool_call_exit"),
    TOOL_CALL_TIMEOUT("tool_call_timeout"),
    STREAM_FRAME_TOO_LARGE("stream_frame_too_large"),
    STREAM_BACKPRESSURE("stream_backpressure"),
    // F-036: model fabricated an unknown tool name OR emitted oversized
    // arguments; the orchestrator dropped/truncated the call. Logged
    // under LogCategory.SECURITY so the dashboard surfaces it.
    TOOL_CALL_REJECTED("tool_call_rejected"),
    USER_MESSAGE("user_message"),
    MODEL_RESPONSE("model_response"),
    DEFERRED_SUBMIT("deferred_submit"),
    DEFERRED_COMPLETE("deferred_complete"),
    DEFERRED_FETCH("deferred_fetch"),
    DEFERRED_CANCEL("deferred_cancel"),
    DEFERRED_EXPIRED("deferred_expired"),
    // Thermal
    BAND_CHANGE("band_change"),
    BACKEND_SWITCH("backend_switch"),
    // Session
    SESSION_CREATED("session_created"),
    SESSION_DESTROYED("session_destroyed"),
    SESSION_EVICTED("session_evicted"),
    SESSION_QUOTA_EXCEEDED("session_quota_exceeded"),
    // Memory
    PRESSURE_CHANGE("pressure_change"),
    EVICTION_TRIGGERED("eviction_triggered"),
    // Engine
    ENGINE_INIT("engine_init"),
    ENGINE_INIT_SUCCESS("engine_init_success"),
    ENGINE_SHUTDOWN("engine_shutdown"),
    OCR_BACKEND_READY("ocr_backend_ready"),
    OCR_BACKEND_SHUTDOWN("ocr_backend_shutdown"),
    OCR_FRAME_PROCESSED("ocr_frame_processed"),
    OCR_FRAME_REJECTED("ocr_frame_rejected"),
    OCR_SESSION_FINALIZED("ocr_session_finalized"),
    EMBEDDING_BACKEND_READY("embedding_backend_ready"),
    EMBEDDING_BACKEND_SHUTDOWN("embedding_backend_shutdown"),
    EMBEDDING_BATCH_COMPLETE("embedding_batch_complete"),
    ENGINE_FALLBACK("engine_fallback"),
    BACKEND_DECISION("backend_decision"),
    FGS_PROMOTED("fgs_promoted"),
    FGS_DEMOTED("fgs_demoted"),
    // F-077: typed init-failure category. The variant name lands in
    // `extraJson` under "failureCategory"; the optional safeLabel goes
    // in `errorMessage` (already F-006-clean — class-name-only). The
    // dashboard reads the latest row to render variant-specific
    // remediation copy.
    INIT_FAILURE_CATEGORIZED("init_failure_categorized"),
    // Error
    GENERAL_ERROR("general_error"),
    // Security
    SECURITY_DECISION("security_decision"),
    RATE_LIMIT_REJECT("rate_limit_reject"),
    ALLOWLIST_PENDING_RECORDED("allowlist_pending_recorded"),
    BINDER_DEATH_CLIENT("binder_death_client"),
    BINDER_DEATH_SELF("binder_death_self"),
    CRASH_LOOP_THROTTLE("crash_loop_throttle");

    override fun toString(): String = key

    companion object {
        private val byKey: Map<String, LogEvent> = entries.associateBy(LogEvent::key)

        fun fromKey(key: String): LogEvent? = byKey[key]
    }
}
