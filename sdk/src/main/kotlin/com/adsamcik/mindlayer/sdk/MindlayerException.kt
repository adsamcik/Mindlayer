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

    companion object {
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
            seq: Long? = null,
            tsMs: Long? = null,
            requestId: String? = null,
            sessionId: String? = null,
        ): MindlayerException = MindlayerException(
            message = message,
            code = MindlayerErrorCode.UNKNOWN,
            codeName = codeName,
            requestId = requestId,
            sessionId = sessionId,
            seq = seq,
            tsMs = tsMs,
        )
    }
}
