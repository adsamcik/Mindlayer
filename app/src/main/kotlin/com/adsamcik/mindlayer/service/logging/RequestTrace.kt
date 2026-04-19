package com.adsamcik.mindlayer.service.logging

/**
 * Tracks timing breakpoints through a single inference request lifecycle.
 * 
 * Usage:
 *   val trace = RequestTrace(requestId, sessionId)
 *   trace.markPrefillStart()
 *   trace.markFirstToken()
 *   trace.markDecodeEnd(tokenCount = 42)
 *   trace.markPipeWriteComplete()
 *   val summary = trace.summary()
 *   // → "req=abc sess=xyz | prefill=120ms ttft=150ms decode=850ms(42tok,49.4tps) pipe=5ms total=1005ms"
 */
class RequestTrace(
    val requestId: String,
    val sessionId: String,
) {
    private val startNanos = System.nanoTime()
    private var prefillStartNanos: Long? = null
    private var firstTokenNanos: Long? = null
    private var decodeEndNanos: Long? = null
    private var pipeCompleteNanos: Long? = null
    private var tokenCount: Int = 0
    private var error: String? = null

    fun markPrefillStart() { prefillStartNanos = System.nanoTime() }
    fun markFirstToken() { firstTokenNanos = System.nanoTime() }
    fun markDecodeEnd(tokenCount: Int) { 
        decodeEndNanos = System.nanoTime()
        this.tokenCount = tokenCount
    }
    fun markPipeWriteComplete() { pipeCompleteNanos = System.nanoTime() }
    fun markError(message: String) { error = message }

    val totalDurationMs: Long get() = (System.nanoTime() - startNanos) / 1_000_000
    val timeToFirstTokenMs: Long? get() = firstTokenNanos?.let { (it - startNanos) / 1_000_000 }
    val decodeDurationMs: Long? get() {
        val start = firstTokenNanos ?: prefillStartNanos ?: return null
        val end = decodeEndNanos ?: return null
        return (end - start) / 1_000_000
    }
    val tokensPerSec: Float? get() {
        val ms = decodeDurationMs ?: return null
        return if (ms > 0) tokenCount * 1000f / ms else null
    }

    fun summary(): String = buildString {
        append("req=$requestId sess=$sessionId")
        timeToFirstTokenMs?.let { append(" ttft=${it}ms") }
        decodeDurationMs?.let { d ->
            append(" decode=${d}ms(${tokenCount}tok")
            tokensPerSec?.let { append(",%.1ftps".format(it)) }
            append(")")
        }
        append(" total=${totalDurationMs}ms")
        error?.let { append(" ERROR=$it") }
    }
}
