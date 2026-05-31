package com.adsamcik.mindlayer.sdk

import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import kotlinx.coroutines.CancellationException

/**
 * Observer instrumentation chokepoint (Spike-E §3 / §5 · §8.5).
 *
 * Wraps a single public [Mindlayer] call so that — when an observer is wired —
 * exactly one [MindlayerObserver.onCallStart] / [MindlayerObserver.onCallEnd]
 * pair brackets the call, whether it returns, throws a typed
 * [MindlayerException], or is cancelled.
 *
 * Design notes:
 * - **No `inline`.** `inline` + `suspend` + a nullable observer is a thicket
 *   (the `return`-from-inline interacts badly with the structured-concurrency
 *   cancellation path); a plain `suspend` function is correct and readable.
 * - **Zero overhead when unset.** The `observer ?: return block()` fast-path
 *   means a client that never passes an observer pays nothing beyond one null
 *   check and one extra (already-suspended) frame — proven by
 *   `ObserverInstrumentationTest`.
 * - **Cancellation still reports.** [CancellationException] is reported as a
 *   [CallOutcome.Failure] *before* being re-thrown, so traces are not silently
 *   truncated when a collecting coroutine is cancelled, then propagation
 *   continues so structured concurrency is preserved.
 *
 * @param summarise produces the optional [CallOutcome.Success.resultSummary].
 *   It MUST NOT echo prompt text, bytes, or model output — only sizes / shapes
 *   / identifiers — to honour the SDK's no-content-in-telemetry rule.
 */
internal suspend fun <R> instrumentCall(
    observer: MindlayerObserver?,
    method: String,
    params: Map<String, String>,
    summarise: (R) -> String?,
    block: suspend () -> R,
): R {
    val obs = observer ?: return block()
    val span = obs.onCallStart(method, params)
    return try {
        val result = block()
        obs.onCallEnd(span, CallOutcome.Success(summarise(result)))
        result
    } catch (e: CancellationException) {
        obs.onCallEnd(
            span,
            CallOutcome.Failure(
                MindlayerException(
                    message = "Call '$method' was cancelled",
                    code = MindlayerErrorCode.UNKNOWN,
                    codeName = "CANCELLED",
                ),
            ),
        )
        throw e
    } catch (e: MindlayerException) {
        obs.onCallEnd(span, CallOutcome.Failure(e))
        throw e
    }
}

/**
 * Redaction helpers for observer [params]. Telemetry must never carry prompt
 * text, raw bytes, or model output — only lengths, sizes, flags, and short
 * identifier prefixes. These keep that rule in one auditable place.
 */
internal object CallParams {

    /** Length of a string, or `"0"` when null. Never the string itself. */
    fun len(value: String?): String = (value?.length ?: 0).toString()

    /** Presence flag for an optional input. */
    fun has(value: Any?): String = (value != null).toString()

    /** First 8 chars of an identifier, enough to correlate without leaking it. */
    fun idPrefix(id: String?): String = id?.take(8) ?: ""
}
