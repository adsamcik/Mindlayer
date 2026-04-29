package com.adsamcik.mindlayer.service.logging

import android.util.Log

/**
 * Structured logcat wrapper that prefixes all messages with correlation context.
 * 
 * Usage:
 *   MindlayerLog.d("InferenceOrchestrator", "Starting inference", requestId = "req-123", sessionId = "sess-456")
 *   → D/Mindlayer.InferenceOrchestrator: [req=req-123 sess=sess-456] Starting inference
 *
 * All tags are prefixed with "Mindlayer." for easy logcat filtering:
 *   adb logcat -s "Mindlayer.*:D"
 */
object MindlayerLog {
    private const val PREFIX = "Mindlayer"

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
            requestId?.let { append("req=${sanitizeLogField(it)} ") }
            sessionId?.let { append("sess=${sanitizeLogField(it)}") }
            append("]")
        }.replace("  ", " ").replace(" ]", "]")
        return "$ctx $message"
    }
}

/**
 * Make an attacker-controlled string safe to embed in a logcat line.
 * Strips control characters (newline, CR, ESC, etc.) and length-caps at 64
 * to prevent log injection / log spoofing via crafted requestId/sessionId
 * /toolName values arriving across the binder.
 *
 * Allowed characters: [A-Za-z0-9._:-/]; everything else becomes '_'.
 */
internal fun sanitizeLogField(value: String?): String {
    if (value == null) return "null"
    val limit = value.length.coerceAtMost(64)
    val sb = StringBuilder(limit)
    for (i in 0 until limit) {
        val c = value[i]
        if (c.isLetterOrDigit() || c in "._:-/") sb.append(c) else sb.append('_')
    }
    return sb.toString()
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
