package com.adsamcik.mindlayer.service.logging

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.adsamcik.mindlayer.service.BuildConfig

/**
 * Structured logcat wrapper that prefixes all messages with correlation context.
 * 
 * Usage:
 *   MindlayerLog.d("InferenceOrchestrator", "Starting inference", requestId = "req-123", sessionId = "sess-456")
 *   → D/Mindlayer.InferenceOrchestrator: [req=req-123 sess=sess-456] Starting inference
 *
 * All tags are prefixed with "Mindlayer." for easy logcat filtering:
 *   adb logcat -s "Mindlayer.*:D"
 *
 * F-046: in non-debug builds, request- and session-IDs are truncated to the
 * first 8 chars in the formatted logcat line so that a logcat dump from a
 * production device does not surface full UUIDs that could be cross-correlated
 * with traffic from the SDK side. The persisted [LogEntry] columns continue
 * to carry the full IDs — DB-side correlation still works, only the logcat
 * line is shortened. The truncation is gated by [BuildConfig.DEBUG] so
 * developers building debug variants still see full IDs for cross-grep.
 */
object MindlayerLog {
    private const val PREFIX = "Mindlayer"

    /**
     * F-046 test seam: defaults to `!BuildConfig.DEBUG`. Tests flip this to
     * exercise both code paths without needing a separate release build.
     */
    @VisibleForTesting
    @JvmField
    var truncateIdsInLogcat: Boolean = !BuildConfig.DEBUG

    fun d(component: String, message: String, requestId: String? = null, sessionId: String? = null) {
        Log.d(tag(component), format(message, requestId, sessionId))
    }

    fun i(component: String, message: String, requestId: String? = null, sessionId: String? = null) {
        Log.i(tag(component), format(message, requestId, sessionId))
    }

    fun w(component: String, message: String, requestId: String? = null, sessionId: String? = null, throwable: Throwable? = null) {
        if (throwable != null) Log.w(tag(component), format(message, requestId, sessionId), throwable)
        else Log.w(tag(component), format(message, requestId, sessionId))
    }

    fun e(component: String, message: String, requestId: String? = null, sessionId: String? = null, throwable: Throwable? = null) {
        if (throwable != null) Log.e(tag(component), format(message, requestId, sessionId), throwable)
        else Log.e(tag(component), format(message, requestId, sessionId))
    }

    private fun tag(component: String) = "$PREFIX.$component"

    private fun format(message: String, requestId: String?, sessionId: String?): String {
        if (requestId == null && sessionId == null) return message
        val ctx = buildString {
            append("[")
            requestId?.let { append("req=${formatId(it)} ") }
            sessionId?.let { append("sess=${formatId(it)}") }
            append("]")
        }.replace("  ", " ").replace(" ]", "]")
        return "$ctx $message"
    }

    private fun formatId(id: String): String =
        if (truncateIdsInLogcat && id.length > 8) id.take(8) else id
}

/**
 * Returns a log-safe description of a throwable with no user content.
 *
 * Use this anywhere a native LiteRT-LM or inference-path exception could
 * embed prompt text, tool arguments, or model output in its message
 * (e.g., tokenizer errors, template errors, validation errors). Pass the
 * result as part of the log message and `throwable = null` so the stack
 * trace (which can also contain inlined strings) is not emitted.
 */
fun Throwable.safeLabel(): String =
    "${this::class.simpleName}${cause?.let { " -> ${it::class.simpleName}" } ?: ""}"

/**
 * Shorten an id for logcat so correlation is possible without dumping full
 * UUIDs. NOT auto-applied to existing callsites — intended for new log
 * lines where brevity matters. Callers that need exact correlation (tests,
 * traces, database entries) should keep the full id.
 */
fun String.loggable(): String = take(8) + "…"
