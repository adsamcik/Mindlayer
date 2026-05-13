package com.adsamcik.mindlayer.sdk

import com.adsamcik.mindlayer.shared.MindlayerErrorCode

/**
 * Typed exception thrown by Mindlayer SDK suspend / one-shot convenience methods.
 *
 * # Wire encoding
 *
 * AIDL on `compileSdk 36` does not expose `android.os.ServiceSpecificException`
 * in the public stubs (Google made it system-API-only). Typed service errors
 * are therefore encoded as a [SecurityException] whose message carries a
 * stable wire prefix produced by
 * [MindlayerErrorCode.wireMessage] / decoded by
 * [MindlayerErrorCode.codeFromWireMessage]. The SDK's AIDL chokepoint
 * ([Mindlayer.withTypedErrors]) parses the prefix and constructs a
 * [MindlayerException]. Un-prefixed [SecurityException]s come from the auth
 * gate and propagate to callers unchanged so IDS / Play Protect doesn't lose
 * meaning.
 *
 * # Fields
 *
 * @param message Human-readable error description (prefix stripped).
 * @param code Wire-stable integer code from [MindlayerErrorCode], or
 *   [MindlayerErrorCode.UNKNOWN] (0) when no typed code is present.
 * @param codeName Symbolic name for the code, populated automatically when
 *   [code] is recognised. May also carry SDK-internal string codes that have
 *   no integer equivalent (e.g. `UNSUPPORTED_TOOL_CALL`, `PIPE_ERROR`).
 * @param requestId The inference request ID, if available.
 * @param sessionId The owning session ID, if available.
 * @param seq Stream sequence number when the error came via a pipe ERROR frame.
 * @param tsMs Service-side timestamp when the error came via a pipe ERROR frame.
 * @param cause Underlying cause (e.g. the original [SecurityException] that
 *   carried the wire prefix). **Never** wrap a service-side `Throwable.cause`
 *   that originates inside the engine — those stack traces can carry prompt
 *   or model output text per the security rules.
 */
class MindlayerException @JvmOverloads constructor(
    message: String,
    val code: Int = MindlayerErrorCode.UNKNOWN,
    codeName: String? = null,
    val requestId: String? = null,
    val sessionId: String? = null,
    val seq: Long? = null,
    val tsMs: Long? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** Symbolic name; derived from [code] when not explicitly supplied. */
    val codeName: String? = codeName ?: MindlayerErrorCode.nameOf(code)

    /** Coarse category derived from [code]. Never caller-supplied. */
    val category: MindlayerErrorCode.Category = MindlayerErrorCode.categoryOf(code)

    /**
     * F-072: when [code] is [MindlayerErrorCode.INPUT_EXCEEDS_CONTEXT], the
     * service embeds `remaining=N` in the wire message body — the number
     * of KV-cache slots still available for user input given the session's
     * service-owned overhead. Returns `null` for any other code or if the
     * service did not include the marker (e.g. an old service binary).
     *
     * Callers can show the user how much input the session can still
     * accept and shorten the next prompt accordingly.
     */
    val remainingTokens: Int?
        get() {
            if (code != MindlayerErrorCode.INPUT_EXCEEDS_CONTEXT) return null
            val raw = message ?: return null
            // Match `remaining=<digits>` — non-anchored so future fields
            // appended to the message body don't break parsing.
            val match = REMAINING_TOKENS_PATTERN.find(raw) ?: return null
            return match.groupValues[1].toIntOrNull()
        }

    /**
     * F-074: when [code] is [MindlayerErrorCode.SERVICE_THROTTLED], the
     * service embeds `cooldown=<wallClockMs>` in the wire message body —
     * the UTC timestamp at which the throttle window naturally expires.
     * SDK reconnect loops use this to schedule a deferred bind instead
     * of hot-spinning while the `:ml` process is in a crash loop.
     *
     * Returns `null` for any other code, or if the service did not
     * include the marker (older service binary). In that case the SDK
     * should fall back to its standard exponential backoff.
     */
    val cooldownEndsAt: Long?
        get() {
            if (code != MindlayerErrorCode.SERVICE_THROTTLED) return null
            val raw = message ?: return null
            val match = COOLDOWN_ENDS_AT_PATTERN.find(raw) ?: return null
            return match.groupValues[1].toLongOrNull()
        }

    /**
     * F-076: when [code] is [MindlayerErrorCode.TRANSIENT_RESOURCE_EXHAUSTED],
     * the service embeds `retryAfterMs=N` in the wire message body — the
     * minimum delay the SDK should wait before retrying the staging-bound
     * request. Returns `null` for any other code or if the service did not
     * include the marker (e.g. an old service binary), in which case
     * callers should fall back to a generic exponential backoff.
     */
    val retryAfterMs: Long?
        get() {
            if (code != MindlayerErrorCode.TRANSIENT_RESOURCE_EXHAUSTED) return null
            val raw = message ?: return null
            val match = RETRY_AFTER_MS_PATTERN.find(raw) ?: return null
            return match.groupValues[1].toLongOrNull()
        }

    companion object {
        /**
         * F-072 wire-payload regex. Matches `remaining=<digits>` anywhere
         * in the SecurityException message body so newer services can
         * append additional fields (e.g. `cooldown=`) without breaking
         * older SDK parsers.
         */
        private val REMAINING_TOKENS_PATTERN: Regex = Regex("""remaining=(\d+)""")

        /**
         * F-074 wire-payload regex. Matches `cooldown=<digits>` anywhere
         * in the SecurityException message body. Pattern is independent
         * of [REMAINING_TOKENS_PATTERN] so a future code that emits both
         * markers does not accidentally collide.
         */
        private val COOLDOWN_ENDS_AT_PATTERN: Regex = Regex("""cooldown=(\d+)""")

        /**
         * F-076 wire-payload regex. Matches `retryAfterMs=<digits>`
         * anywhere in the SecurityException message body so newer
         * services can append additional fields without breaking older
         * SDK parsers.
         */
        private val RETRY_AFTER_MS_PATTERN: Regex = Regex("""retryAfterMs=(\d+)""")

        /**
         * Parse a wire-prefixed [SecurityException] message into a
         * [MindlayerException]. Returns `null` when the message does not carry
         * the [MindlayerErrorCode] wire prefix — callers should rethrow the
         * original so un-prefixed auth-gate exceptions reach user code
         * unchanged.
         */
        @JvmStatic
        fun fromAidlSecurityException(
            e: SecurityException,
            requestId: String? = null,
            sessionId: String? = null,
        ): MindlayerException? {
            val raw = e.message ?: return null
            val code = MindlayerErrorCode.codeFromWireMessage(raw) ?: return null
            val plainMessage = MindlayerErrorCode.messageFromWireMessage(raw) ?: ""
            return MindlayerException(
                message = plainMessage,
                code = code,
                requestId = requestId,
                sessionId = sessionId,
                cause = e,
            )
        }

        /**
         * Build a [MindlayerException] from a stream `ERROR` frame's payload.
         * Pipe-side codes are currently strings; this constructor is used by
         * one-shot collectors to carry pipe error information through the
         * SDK's typed exception surface.
         */
        @JvmStatic
        fun fromStreamError(
            message: String,
            codeName: String?,
            codeInt: Int? = null,
            seq: Long? = null,
            tsMs: Long? = null,
            requestId: String? = null,
            sessionId: String? = null,
        ): MindlayerException = MindlayerException(
            message = message,
            code = codeInt ?: MindlayerErrorCode.UNKNOWN,
            codeName = codeName ?: codeInt?.let { MindlayerErrorCode.nameOf(it) },
            requestId = requestId,
            sessionId = sessionId,
            seq = seq,
            tsMs = tsMs,
        )
    }
}
