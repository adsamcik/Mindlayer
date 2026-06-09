package com.adsamcik.mindlayer.sdk

/**
 * Optional observability hook for the Mindlayer SDK.
 *
 * `MindlayerObserver` is an OpenTelemetry-compatible start/end pair invoked
 * around every public [Mindlayer] method that dispatches to the AIDL service.
 * The SDK treats [Span] as an opaque token — it never reads it, only echoes
 * it back to [onCallEnd] — so an implementation may stash an OTel span id,
 * a trace handle, or any correlation key inside it.
 *
 * Wiring of the observer into the call path lands in C2; in C1 this type is
 * part of the public surface only.
 */
interface MindlayerObserver {
    /** Called before any public Mindlayer method dispatches to AIDL. */
    fun onCallStart(method: String, params: Map<String, String>): Span

    /** Called after the method returns, whether it succeeded or threw. */
    fun onCallEnd(span: Span, outcome: CallOutcome)
}

/**
 * Opaque correlation token handed from [MindlayerObserver.onCallStart] back to
 * [MindlayerObserver.onCallEnd]. The SDK never inspects its contents.
 */
@ConsistentCopyVisibility
data class Span internal constructor(val id: String, val startNanos: Long)

/** Terminal outcome of an observed Mindlayer call. */
sealed interface CallOutcome {
    /** The call returned normally. [resultSummary] is an optional short label. */
    data class Success(val resultSummary: String?) : CallOutcome

    /** The call threw. [error] carries the typed failure. */
    data class Failure(val error: MindlayerException) : CallOutcome
}
