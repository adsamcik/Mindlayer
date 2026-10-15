package com.adsamcik.mindlayer.sdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

/** Owns one durable request; serializes fetching because its result contains a file descriptor. */
internal class DeferredEmbeddingHandle(
    override val requestId: String,
    scope: CoroutineScope,
    remainingTimeout: Duration?,
    private val fetch: suspend () -> List<EmbeddingResultItem>,
    private val acknowledgeResult: suspend () -> Unit,
    private val discard: () -> Unit,
) : EmbeddingHandle.Deferred {
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val expired = AtomicBoolean(false)
    private val closeSignal = CompletableDeferred<Unit>()
    private var results: List<EmbeddingResultItem>? = null
    private val acknowledged = AtomicBoolean(false)
    private val expiryJob = remainingTimeout?.let { timeout ->
        scope.launch {
            delay(timeout)
            expired.set(true)
            close()
        }
    }

    override val events: Flow<EmbeddingEvent> = flow {
        emit(EmbeddingEvent.Completed(awaitReady()))
    }

    override suspend fun awaitReady(): List<EmbeddingResultItem> = mutex.withLock {
        requireOpen()
        results ?: coroutineScope {
            val pending = async { fetch() }
            try {
                select {
                    pending.onAwait { it }
                    closeSignal.onAwait {
                        requireOpen()
                        error("Closed embedding handle")
                    }
                }
            } finally {
                pending.cancel()
            }
        }.also {
            requireOpen()
            results = it
            expiryJob?.cancel()
        }
    }

    override suspend fun acknowledge() = mutex.withLock {
        check(results != null) { "Await the embedding result before acknowledging it" }
        if (!acknowledged.get()) {
            acknowledgeResult()
            acknowledged.set(true)
        }
    }

    /** Non-blocking and idempotent. Cleanup uses the cached binder, without waiting for reconnection. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        expiryJob?.cancel()
        closeSignal.complete(Unit)
        if (!acknowledged.get()) discard()
    }

    private suspend fun requireOpen() {
        // Keep coroutine timeout semantics, including when the caller waits after the deadline elapsed.
        if (expired.get()) withTimeout(Duration.ZERO) { error("Embedding deadline expired") }
        check(!closed.get()) { "Embedding handle is closed" }
    }
}
