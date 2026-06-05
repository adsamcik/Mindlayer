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
            requestId?.let { append("req=${formatId(sanitizeLogField(it))} ") }
            sessionId?.let { append("sess=${formatId(sanitizeLogField(it))}") }
            append("]")
        }.replace("  ", " ").replace(" ]", "]")
        return "$ctx $message"
    }

    private fun formatId(id: String): String =
        if (truncateIdsInLogcat && id.length > 8) id.take(8) else id
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
 * Like [safeLabel] but appends the exception's `message` for a hand-picked
 * allowlist of exception classes whose messages are provably free of
 * user-controlled content (model load errors, op-set probe results,
 * tensor-shape mismatches, native compile failures). Everything outside
 * the allowlist falls through to plain [safeLabel].
 *
 * Use this only for the narrow set of file-system / kernel exception
 * classes whose messages identify device state (a model-file path, a
 * lock conflict, an `errno`) and provably **cannot** carry
 * caller-supplied strings, prompts, tool arguments, or model output.
 *
 * ## Security: why LiteRT/LiteRT-LM and generic IAE/ISE are NOT here
 *
 * The allowlist keys on exception *type*, not throw *site*. Native
 * LiteRT / LiteRT-LM exceptions (`com.google.ai.edge.litert*`) and
 * generic `IllegalArgumentException` / `IllegalStateException` can be
 * raised from tokenizer / template / vocab code with prompt fragments
 * or model output inlined in the message — see
 * `.github/instructions/engine.instructions.md` ("LiteRT-LM error
 * messages and stack traces can embed prompt text"). Appending their
 * messages to logcat, the persisted `LogRepository` row, or the wire
 * error would leak that content, violating the metadata-only logging
 * invariant. They therefore fall through to class-name-only
 * [safeLabel].
 *
 * Boundary-validator detail is surfaced separately and intentionally:
 * `ServiceBinder` translates an `IpcInputValidator` `IllegalArgumentException`
 * into a typed `INVALID_REQUEST` error using the validator's own
 * (content-free, field-name + numeric-bound) message — that path does
 * not depend on this helper.
 *
 * Add a class here ONLY after auditing every throw site in the source
 * to confirm the message cannot contain caller-supplied strings,
 * prompts, or model output — and add a paired test in `SafeLabelTest`.
 */
fun Throwable.safeLabelWithDetail(maxMessageChars: Int = 160): String {
    val base = safeLabel()
    val msg = message
    if (msg.isNullOrBlank()) return base
    val fqcn = this::class.java.name
    // Strictly file-system / kernel classes only. Do NOT add
    // `com.google.ai.edge.litert*`, `IllegalArgumentException`, or
    // `IllegalStateException` — their messages can embed prompt /
    // model content depending on the throw site (see KDoc).
    val allow = fqcn == "java.nio.channels.OverlappingFileLockException" ||
        fqcn == "java.io.FileNotFoundException" ||
        fqcn == "android.system.ErrnoException"
    if (!allow) return base
    val trimmed = msg.lineSequence().firstOrNull().orEmpty().take(maxMessageChars)
    return if (trimmed.isBlank()) base else "$base($trimmed)"
}

/**
 * Shorten an id for logcat so correlation is possible without dumping full
 * UUIDs. NOT auto-applied to existing callsites — intended for new log
 * lines where brevity matters. Callers that need exact correlation (tests,
 * traces, database entries) should keep the full id.
 */
fun String.loggable(): String = take(8) + "…"
