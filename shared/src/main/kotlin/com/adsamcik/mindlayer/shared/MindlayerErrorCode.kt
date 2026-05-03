package com.adsamcik.mindlayer.shared

/**
 * Stable error codes carried over the wire via a prefixed Binder exception
 * message (AIDL throws) and as the `code` payload field of stream ERROR frames.
 *
 * # Stability contract
 *
 * - These integer values are **wire-stable**. Once allocated, never reuse, never
 *   change semantics. Append-only.
 * - Codes are grouped by leading digit:
 *   - 1xxx — engine lifecycle
 *   - 2xxx — session lifecycle
 *   - 3xxx — request / validation
 *   - 4xxx — resource (memory, thermal)
 *   - 5xxx — quota / rate limiting
 *   - 6xxx — auth / allowlist (typically thrown as [SecurityException]; codes are
 *     present here for symmetry with stream ERROR frames and any future typed
 *     surfaces)
 *
 * # Anti-enumeration constraint
 *
 * [SESSION_NOT_FOUND_OR_NOT_OWNED] is a **single code** for both
 * "session does not exist" and "session exists but is not owned by the caller".
 * Splitting these would leak cross-UID session existence (F-008 in the security
 * review). Do not introduce a separate `SESSION_NOT_OWNED` code under any
 * circumstances.
 *
 * # SDK-internal codes
 *
 * SDK code path errors that never cross the wire (e.g. `UNSUPPORTED_TOOL_CALL`)
 * use the string `codeName` field on [com.adsamcik.mindlayer.sdk.MindlayerException]
 * and leave [MindlayerException.code] as [UNKNOWN].
 */
object MindlayerErrorCode {
    private const val WIRE_PREFIX = "MLERR:"

    /** Reserved value meaning "no typed code provided". */
    const val UNKNOWN = 0

    // ---- 1xxx engine lifecycle ---------------------------------------------

    /** Engine is performing cold-start init (~5–10 s). Caller should retry with backoff. */
    const val ENGINE_INITIALIZING = 1001

    /** Engine load failed (model missing, integrity check failed, OOM during load). */
    const val ENGINE_LOAD_FAILED = 1002

    // ---- 2xxx session lifecycle --------------------------------------------

    /**
     * Session does not exist OR exists but is not owned by the caller.
     *
     * **Anti-enumeration**: these two cases must remain indistinguishable to
     * external callers. See class-level KDoc.
     */
    const val SESSION_NOT_FOUND_OR_NOT_OWNED = 2001

    /** Session was evicted (memory pressure, OOM kill, max-sessions cap). */
    const val SESSION_EVICTED = 2002

    /** Session expired past its configured `expirationMs`. */
    const val SESSION_EXPIRED = 2003

    // ---- 3xxx request / validation -----------------------------------------

    /** Generic invalid-request (bad sessionId/requestId/text content). */
    const val INVALID_REQUEST = 3001

    /** SessionConfig failed [com.adsamcik.mindlayer.shared] validation rules. */
    const val INVALID_SESSION_CONFIG = 3002

    /** ToolResult parcelable failed validation. */
    const val INVALID_TOOL_RESULT = 3003

    /** Caller submitted a duplicate `(uid, requestId)` for an in-flight request. */
    const val DUPLICATE_REQUEST = 3004

    /** No active inference matches the supplied requestId for this caller. */
    const val NO_ACTIVE_REQUEST = 3005

    /**
     * F-072: caller-supplied input plus service-owned prompt overhead
     * (system prompt + tool definitions + structured-output schema suffix)
     * exceeds the session's effective KV-cache ceiling.
     *
     * The wire message body carries `remainingTokens=N`, the number of
     * KV-cache slots still available for user input given the session's
     * current overhead reservation. SDKs surface this so callers can
     * truncate input or shorten history before retrying.
     */
    const val INPUT_EXCEEDS_CONTEXT = 3006

    // ---- 4xxx resource ------------------------------------------------------

    /** Device thermal state is critical; inference rejected. */
    const val THERMAL_CRITICAL = 4001

    /** Memory pressure exceeded the safe band; request rejected. */
    const val MEMORY_PRESSURE = 4002

    // ---- 5xxx quota / rate limiting ----------------------------------------

    /** Caller exceeded the per-UID concurrent inference cap. */
    const val CONCURRENT_LIMIT = 5001

    /** Caller exceeded the per-UID requests-per-minute cap (throws SecurityException too). */
    const val RATE_LIMITED = 5002

    // ---- 6xxx auth / allowlist ---------------------------------------------

    /** App not on the allowlist; user approval pending in the dashboard. */
    const val ALLOWLIST_PENDING = 6001

    /** App approval was revoked; user must re-approve. */
    const val ALLOWLIST_REVOKED = 6002

    /** Caller identity could not be resolved (shared-UID, unknown package). */
    const val IDENTITY_UNKNOWN = 6003

    /** Internal service error; should not be observed in healthy operation. */
    const val INTERNAL = 9999

    /**
     * Coarse SDK-side category derived from a wire code. Callers can write
     * `when (e.category) { Category.SESSION -> ... }` without an exhaustive
     * `when (e.code)` and without coupling to specific code numbers.
     *
     * Adding a new category is a **breaking change** for SDK consumers using
     * exhaustive `when` over this enum — append carefully.
     */
    enum class Category {
        /** Pipe / IPC transport failures (connection lost, pipe closed). */
        TRANSPORT,

        /** Auth gate failures (allowlist, identity, rate limit at gate). */
        AUTH,

        /** Caller-supplied input was malformed or violates byte budgets. */
        VALIDATION,

        /** Engine lifecycle (initializing, load failed). */
        ENGINE,

        /** Session lifecycle (not found / evicted / expired). */
        SESSION,

        /** Resource exhaustion (memory, thermal, concurrent slots). */
        RESOURCE,

        /** Model produced an unsupported output shape (e.g. tool calls in one-shot mode). */
        MODEL,

        /** Unmapped or unknown code. */
        UNKNOWN,
    }

    /**
     * Map a wire code to its [Category]. Unknown codes return [Category.UNKNOWN].
     */
    fun categoryOf(code: Int): Category = when (code) {
        ENGINE_INITIALIZING, ENGINE_LOAD_FAILED -> Category.ENGINE
        SESSION_NOT_FOUND_OR_NOT_OWNED, SESSION_EVICTED, SESSION_EXPIRED -> Category.SESSION
        INVALID_REQUEST, INVALID_SESSION_CONFIG, INVALID_TOOL_RESULT,
        DUPLICATE_REQUEST, NO_ACTIVE_REQUEST,
        INPUT_EXCEEDS_CONTEXT -> Category.VALIDATION
        THERMAL_CRITICAL, MEMORY_PRESSURE, CONCURRENT_LIMIT, RATE_LIMITED -> Category.RESOURCE
        ALLOWLIST_PENDING, ALLOWLIST_REVOKED, IDENTITY_UNKNOWN -> Category.AUTH
        INTERNAL -> Category.UNKNOWN
        else -> Category.UNKNOWN
    }

    /**
     * Best-effort symbolic name for a wire code, suitable for logs and
     * [com.adsamcik.mindlayer.sdk.MindlayerException.codeName]. Returns
     * `null` for unmapped codes.
     */
    fun nameOf(code: Int): String? = when (code) {
        UNKNOWN -> null
        ENGINE_INITIALIZING -> "ENGINE_INITIALIZING"
        ENGINE_LOAD_FAILED -> "ENGINE_LOAD_FAILED"
        SESSION_NOT_FOUND_OR_NOT_OWNED -> "SESSION_NOT_FOUND_OR_NOT_OWNED"
        SESSION_EVICTED -> "SESSION_EVICTED"
        SESSION_EXPIRED -> "SESSION_EXPIRED"
        INVALID_REQUEST -> "INVALID_REQUEST"
        INVALID_SESSION_CONFIG -> "INVALID_SESSION_CONFIG"
        INVALID_TOOL_RESULT -> "INVALID_TOOL_RESULT"
        DUPLICATE_REQUEST -> "DUPLICATE_REQUEST"
        NO_ACTIVE_REQUEST -> "NO_ACTIVE_REQUEST"
        INPUT_EXCEEDS_CONTEXT -> "INPUT_EXCEEDS_CONTEXT"
        THERMAL_CRITICAL -> "THERMAL_CRITICAL"
        MEMORY_PRESSURE -> "MEMORY_PRESSURE"
        CONCURRENT_LIMIT -> "CONCURRENT_LIMIT"
        RATE_LIMITED -> "RATE_LIMITED"
        ALLOWLIST_PENDING -> "ALLOWLIST_PENDING"
        ALLOWLIST_REVOKED -> "ALLOWLIST_REVOKED"
        IDENTITY_UNKNOWN -> "IDENTITY_UNKNOWN"
        INTERNAL -> "INTERNAL"
        else -> null
    }

    fun wireMessage(code: Int, message: String): String =
        "$WIRE_PREFIX$code:$message"

    fun codeFromWireMessage(message: String?): Int? {
        if (message == null || !message.startsWith(WIRE_PREFIX)) return null
        return message
            .removePrefix(WIRE_PREFIX)
            .substringBefore(':')
            .toIntOrNull()
    }

    fun messageFromWireMessage(message: String?): String? {
        if (message == null || !message.startsWith(WIRE_PREFIX)) return message
        return message
            .removePrefix(WIRE_PREFIX)
            .substringAfter(':', missingDelimiterValue = "")
            .ifEmpty { null }
    }
}
