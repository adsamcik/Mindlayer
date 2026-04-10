package com.mindlayer.service.logging

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
            requestId?.let { append("req=$it ") }
            sessionId?.let { append("sess=$it") }
            append("]")
        }.replace("  ", " ").replace(" ]", "]")
        return "$ctx $message"
    }
}
