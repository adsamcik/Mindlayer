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

    /** Engine init failed because no LiteRT-LM model file is installed. */
    const val MODEL_MISSING = 1003

    /** Engine init failed because model integrity verification failed. */
    const val INTEGRITY_MISMATCH = 1004

    /** Engine init failed because all configured backends were unavailable. */
    const val BACKEND_UNAVAILABLE = 1005

    /** Engine init failed inside the native runtime. */
    const val NATIVE_ERROR = 1006

    /**
     * The engine's single native session slot is held by another session
     * that is currently processing an inference. Caller should retry after
     * the `retryAfterMs` hint (typically ~500 ms — see
     * [com.adsamcik.mindlayer.service.engine.WarmConversationSlot.ENGINE_BUSY_RETRY_MS]).
     *
     * LiteRT-LM 0.12.0 enforces "one Conversation per Engine at a time" at
     * the JNI layer. When the client tries to `createSession(B)` while
     * session A is mid-stream, the service cannot safely close A's
     * Conversation underneath the active stream — the alternative would
     * corrupt the native KV cache.
     */
    const val ENGINE_BUSY = 1007

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

    /**
     * v0.8 OCR: session idle timeout fired (no `pushOcrFrame` for >
     * `idleTimeoutMs`, default 30 s). Session has been auto-closed.
     */
    const val OCR_IDLE_TIMEOUT = 2004

    /**
     * v0.8 OCR: session hit the hard wall-clock cap
     * ([OcrLimits.maxOcrSessionDurationMs], default 5 min) regardless of
     * activity. Session has been auto-closed.
     */
    const val OCR_MAX_DURATION = 2005

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

    /**
     * v0.8 OCR: `OcrSessionConfig.outputSchemaJson` / `optionsJson` /
     * `OcrFrameMeta.regionJson` malformed, exceeds
     * [OcrLimits.ocrSchemaJsonMaxLen], references an unknown
     * [OcrSessionConfig.mode], or non-monotonic [OcrFrameMeta.frameId].
     */
    const val OCR_SCHEMA_INVALID = 3007

    /**
     * v0.8 OCR: caller invoked `pushOcrFrame` (or `finalizeOcrSession`) on
     * a session that has already finalized. Caller must open a new session.
     */
    const val OCR_SESSION_FINALIZED = 3008

    // ---- 4xxx resource ------------------------------------------------------

    /** Device thermal state is critical; inference rejected. */
    const val THERMAL_CRITICAL = 4001

    /** Memory pressure exceeded the safe band; request rejected. */
    const val MEMORY_PRESSURE = 4002

    /**
     * Engine init refused: device available RAM is below the model size +
     * working-buffer headroom. Carries `availMb` and `requiredMb` in the
     * wire message (format: `"… availMb=N requiredMb=M …"`) so a diagnostic
     * UI can show the user how short the device is. Distinct from
     * [MEMORY_PRESSURE] (which fires for steady-state pressure on an
     * already-running session) and [ENGINE_LOAD_FAILED] (which is the
     * generic native-load failure).
     */
    const val LOW_MEMORY = 4003

    // ---- 5xxx quota / rate limiting ----------------------------------------

    /** Caller exceeded the per-UID concurrent inference cap. */
    const val CONCURRENT_LIMIT = 5001

    /** Caller exceeded the per-UID requests-per-minute cap (throws SecurityException too). */
    const val RATE_LIMITED = 5002

    /**
     * F-074: the `:ml` service has detected a crash-loop and is throttling
     * new binds for a cooldown window. The wire message body carries
     * `cooldown=<wallClockMs>` — the UTC ms timestamp at which the throttle
     * naturally expires. SDKs use this to back off the reconnect loop
     * instead of hot-spinning until the OOM-killer is provoked again.
     */
    const val SERVICE_THROTTLED = 5003

    /**
     * F-076: the SharedMemoryPool refused a media-staging slot because a
     * global cap (active PFD count or staged bytes) or the per-request
     * count cap would be exceeded. The wire message body carries
     * `retryAfterMs=N` so the SDK can backoff and retry — distinct from
     * the harder-failure caps (`CONCURRENT_LIMIT`, `RATE_LIMITED`) and
     * from the bind-side throttle (`SERVICE_THROTTLED`) because the
     * resource will free up on its own as in-flight requests drain.
     */
    const val TRANSIENT_RESOURCE_EXHAUSTED = 5004

    /** Caller/session quota exhausted; destroy an existing session before retrying. */
    const val SESSION_QUOTA_EXHAUSTED = 5005

    /** Connected service does not advertise the requested optional capability. */
    const val NOT_SUPPORTED = 5006

    /** Caller exceeded deferred-inference in-flight or pending-result quota. */
    const val DEFERRED_QUOTA_EXHAUSTED = 5007

    /** Deferred result expired before it was fetched. */
    const val DEFERRED_EXPIRED = 5008

    /** SDK-side bind is unsupported for this Android API level. */
    const val UNSUPPORTED_ANDROID_VERSION = 5009

    /**
     * Deferred inference produced (or would produce) a result exceeding the
     * per-result byte cap. Truncation is applied at commit time; this code is
     * surfaced on the [DeferredResult] metrics flag and reserved for any
     * future submit-time guard.
     */
    const val DEFERRED_RESULT_TOO_LARGE = 5010

    /** Caller exceeded embedding batch cap for inline/SHM/deferred transport. */
    const val EMBEDDING_BATCH_TOO_LARGE = 5011

    /** No embedding model is installed or usable. */
    const val EMBEDDING_MODEL_UNAVAILABLE = 5012

    /** Embedding input is empty or exceeds the per-call UTF-8 byte budget. */
    const val EMBEDDING_INPUT_TOO_LONG = 5013

    /** Embeddings are disabled for this caller or not capability-advertised. */
    const val EMBEDDING_DISABLED = 5014

    /**
     * v0.8 OCR: service-side intake queue saturated. Mirrors the
     * [OcrFrameAck.STATUS_DROPPED_BUSY] sync-ack status. Carries
     * `retryAfterMs=N` in the wire message body. Transient — resource
     * frees as in-flight inferences drain.
     */
    const val FRAME_DROPPED_BUSY = 5015

    /**
     * v0.8 OCR: service-side presort rejected the frame on quality grounds
     * (blur / glare / motion / duplicate / low-text-density). Mirrors
     * [OcrFrameAck.STATUS_REJECTED_QUALITY]. Caller should adjust capture
     * conditions and resubmit.
     */
    const val FRAME_REJECTED_QUALITY = 5016

    /** Connected service does not advertise the requested optional feature. */
    const val FEATURE_NOT_SUPPORTED = 5017

    /** SDK could not bind to the Mindlayer service process. */
    const val SERVICE_UNAVAILABLE = 5018

    /** SDK timed out waiting for the Mindlayer service connection. */
    const val CONNECT_TIMEOUT = 5019

    /** SDK observed a malformed or prematurely terminated pipe protocol. */
    const val PROTOCOL_VIOLATION = 5020

    // ---- 3xxx consent / input validation (continued) ----------------------

    /**
     * v0.10: caller-supplied input failed the third-party prompt-injection
     * scoring heuristics. Emitted only for THIRD_PARTY-tier callers; first-
     * party callers bypass the scoring. The wire message body carries
     * `score=<int>` and `threshold=<int>` for SDK telemetry; the
     * service log records the score but **never** the rejected content.
     */
    const val INPUT_REJECTED = 3009

    // ---- 6xxx auth / allowlist ---------------------------------------------

    /**
     * App approval was revoked; user must re-consent. Still emitted as the
     * **eviction / cancellation reason code** when `revokeApp` or a
     * permanent block tears down a caller's in-flight sessions
     * (`InferenceOrchestrator` / `SessionManager`). Distinct from
     * [CONSENT_REQUIRED] (returned by the auth gate to an un-consented
     * caller) and [CONSENT_DENIED] (returned to a user-denied caller).
     */
    const val ALLOWLIST_REVOKED = 6002

    /** Caller identity could not be resolved (shared-UID, unknown package). */
    const val IDENTITY_UNKNOWN = 6003

    /** Android denied binding to the service before the Mindlayer auth gate. */
    const val PERMISSION_DENIED = 6004

    /**
     * v0.10: caller is not approved in `entries.json` and must obtain
     * user consent via the consent-Intent flow. The SDK translates this
     * to `MindlayerConnectResult.ConsentRequired(intent)` where `intent`
     * is the PendingIntent returned by
     * [com.adsamcik.mindlayer.IMindlayerService.requestConsentChallenge].
     */
    const val CONSENT_REQUIRED = 6005

    /**
     * v0.10: user explicitly denied consent for this caller. The wire
     * message body carries either `until=<epochMs>` for a temporary
     * (24-hour) denial or `until=permanent` for a package-wide block.
     * The SDK translates this to
     * `MindlayerConnectResult.ConsentDenied(until)`.
     */
    const val CONSENT_DENIED = 6006

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
        ENGINE_INITIALIZING, ENGINE_LOAD_FAILED, MODEL_MISSING, INTEGRITY_MISMATCH,
        BACKEND_UNAVAILABLE, NATIVE_ERROR -> Category.ENGINE
        SESSION_NOT_FOUND_OR_NOT_OWNED, SESSION_EVICTED, SESSION_EXPIRED,
        OCR_IDLE_TIMEOUT, OCR_MAX_DURATION -> Category.SESSION
        INVALID_REQUEST, INVALID_SESSION_CONFIG, INVALID_TOOL_RESULT,
        DUPLICATE_REQUEST, NO_ACTIVE_REQUEST,
        INPUT_EXCEEDS_CONTEXT, INPUT_REJECTED,
        OCR_SCHEMA_INVALID, OCR_SESSION_FINALIZED -> Category.VALIDATION
        THERMAL_CRITICAL, MEMORY_PRESSURE, LOW_MEMORY,
        CONCURRENT_LIMIT, RATE_LIMITED, SERVICE_THROTTLED,
        TRANSIENT_RESOURCE_EXHAUSTED, SESSION_QUOTA_EXHAUSTED,
        NOT_SUPPORTED, DEFERRED_QUOTA_EXHAUSTED,
        DEFERRED_EXPIRED, UNSUPPORTED_ANDROID_VERSION,
        DEFERRED_RESULT_TOO_LARGE, EMBEDDING_BATCH_TOO_LARGE,
        EMBEDDING_MODEL_UNAVAILABLE, EMBEDDING_INPUT_TOO_LONG,
        EMBEDDING_DISABLED,
        FRAME_DROPPED_BUSY, FRAME_REJECTED_QUALITY,
        FEATURE_NOT_SUPPORTED, SERVICE_UNAVAILABLE, CONNECT_TIMEOUT,
        PROTOCOL_VIOLATION -> Category.RESOURCE
        ALLOWLIST_REVOKED,
        IDENTITY_UNKNOWN, PERMISSION_DENIED,
        CONSENT_REQUIRED, CONSENT_DENIED -> Category.AUTH
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
        MODEL_MISSING -> "MODEL_MISSING"
        INTEGRITY_MISMATCH -> "INTEGRITY_MISMATCH"
        BACKEND_UNAVAILABLE -> "BACKEND_UNAVAILABLE"
        NATIVE_ERROR -> "NATIVE_ERROR"
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
        LOW_MEMORY -> "LOW_MEMORY"
        CONCURRENT_LIMIT -> "CONCURRENT_LIMIT"
        RATE_LIMITED -> "RATE_LIMITED"
        SERVICE_THROTTLED -> "SERVICE_THROTTLED"
        TRANSIENT_RESOURCE_EXHAUSTED -> "TRANSIENT_RESOURCE_EXHAUSTED"
        SESSION_QUOTA_EXHAUSTED -> "SESSION_QUOTA_EXHAUSTED"
        NOT_SUPPORTED -> "NOT_SUPPORTED"
        DEFERRED_QUOTA_EXHAUSTED -> "DEFERRED_QUOTA_EXHAUSTED"
        DEFERRED_EXPIRED -> "DEFERRED_EXPIRED"
        DEFERRED_RESULT_TOO_LARGE -> "DEFERRED_RESULT_TOO_LARGE"
        EMBEDDING_BATCH_TOO_LARGE -> "EMBEDDING_BATCH_TOO_LARGE"
        EMBEDDING_MODEL_UNAVAILABLE -> "EMBEDDING_MODEL_UNAVAILABLE"
        EMBEDDING_INPUT_TOO_LONG -> "EMBEDDING_INPUT_TOO_LONG"
        EMBEDDING_DISABLED -> "EMBEDDING_DISABLED"
        OCR_IDLE_TIMEOUT -> "OCR_IDLE_TIMEOUT"
        OCR_MAX_DURATION -> "OCR_MAX_DURATION"
        OCR_SCHEMA_INVALID -> "OCR_SCHEMA_INVALID"
        OCR_SESSION_FINALIZED -> "OCR_SESSION_FINALIZED"
        FRAME_DROPPED_BUSY -> "FRAME_DROPPED_BUSY"
        FRAME_REJECTED_QUALITY -> "FRAME_REJECTED_QUALITY"
        FEATURE_NOT_SUPPORTED -> "FEATURE_NOT_SUPPORTED"
        SERVICE_UNAVAILABLE -> "SERVICE_UNAVAILABLE"
        CONNECT_TIMEOUT -> "CONNECT_TIMEOUT"
        PROTOCOL_VIOLATION -> "PROTOCOL_VIOLATION"
        UNSUPPORTED_ANDROID_VERSION -> "UNSUPPORTED_ANDROID_VERSION"
        INPUT_REJECTED -> "INPUT_REJECTED"
        ALLOWLIST_REVOKED -> "ALLOWLIST_REVOKED"
        IDENTITY_UNKNOWN -> "IDENTITY_UNKNOWN"
        PERMISSION_DENIED -> "PERMISSION_DENIED"
        CONSENT_REQUIRED -> "CONSENT_REQUIRED"
        CONSENT_DENIED -> "CONSENT_DENIED"
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


