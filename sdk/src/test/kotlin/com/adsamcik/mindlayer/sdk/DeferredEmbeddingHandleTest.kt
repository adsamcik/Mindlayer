package com.adsamcik.mindlayer.sdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DeferredEmbeddingHandleTest {
    private val item = EmbeddingResultItem(
        tag = "document-1",
        vector = EmbeddingVector(floatArrayOf(0.5f, 0.25f)),
        dim = 2,
        modelId = "model",
        tokenCount = 3,
        truncated = false,
        backend = "CPU",
        durationMs = 7,
    )

    @Test fun `fetch is cached with metadata and acknowledgement is explicit and idempotent`() = runTest {
        var fetched = 0
        var acknowledged = 0
        var discarded = 0
        val handle = DeferredEmbeddingHandle(
            "request", this, 1.seconds,
            fetch = { fetched++; listOf(item) },
            acknowledgeResult = { acknowledged++ },
            discard = { discarded++ },
        )
        val first = handle.awaitReady()
        assertSame(item, first.single())
        assertSame(first, handle.awaitReady())
        assertEquals(EmbeddingEvent.Completed(first), handle.events.first())
        assertEquals(1, fetched)
        assertEquals(0, acknowledged)
        handle.acknowledge()
        handle.acknowledge()
        assertEquals(1, acknowledged)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(0, discarded)
        handle.close()
        handle.close()
        assertEquals(0, discarded)
    }

    @Test fun `deadline disposes a request even when nobody is waiting`() = runTest {
        var discarded = 0
        val handle = DeferredEmbeddingHandle(
            "request", this, 1.seconds,
            fetch = { error("Expired request must not fetch") },
            acknowledgeResult = {},
            discard = { discarded++ },
        )
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(1, discarded)
        assertTrue(runCatching { handle.awaitReady() }.exceptionOrNull() is TimeoutCancellationException)
        handle.close()
        assertEquals(1, discarded)
    }

    @Test fun `close interrupts a pending wait and cancels its fetch`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var fetchStopped = false
        val handle = DeferredEmbeddingHandle(
            "request", this, null,
            fetch = {
                entered.complete(Unit)
                try { release.await(); listOf(item) } finally { fetchStopped = true }
            },
            acknowledgeResult = {}, discard = {},
        )
        val waiting = async { runCatching { handle.awaitReady() } }
        entered.await()
        handle.close()
        assertTrue(waiting.await().exceptionOrNull() is IllegalStateException)
        assertTrue(fetchStopped)
    }

    @Test fun `cancelling a wait does not dispose durable work`() = runTest {
        val release = CompletableDeferred<Unit>()
        var discarded = 0
        val handle = DeferredEmbeddingHandle(
            "request", this, null,
            fetch = { release.await(); listOf(item) },
            acknowledgeResult = {}, discard = { discarded++ },
        )
        val waiting = async { handle.awaitReady() }
        runCurrent()
        waiting.cancelAndJoin()
        assertEquals(0, discarded)
        release.complete(Unit)
        assertEquals(listOf(item), handle.awaitReady())
        handle.close()
    }

    @Test fun `acknowledgement rejects unread results and can retry a failed round trip`() = runTest {
        var attempts = 0
        val handle = DeferredEmbeddingHandle(
            "request", this, null,
            fetch = { listOf(item) },
            acknowledgeResult = { if (++attempts == 1) error("Unavailable") },
            discard = {},
        )
        assertTrue(runCatching { handle.acknowledge() }.exceptionOrNull() is IllegalStateException)
        assertEquals(0, attempts)
        handle.awaitReady()
        assertTrue(runCatching { handle.acknowledge() }.isFailure)
        handle.acknowledge()
        handle.acknowledge()
        assertEquals(2, attempts)
        handle.close()
    }

    @Test fun `deadline interrupts a pending fetch`() = runTest {
        val entered = CompletableDeferred<Unit>()
        var stopped = false
        val handle = DeferredEmbeddingHandle(
            "request", this, 1.seconds,
            fetch = {
                entered.complete(Unit)
                try { CompletableDeferred<Unit>().await(); emptyList() } finally { stopped = true }
            },
            acknowledgeResult = {}, discard = {},
        )
        val waiting = async { runCatching { handle.awaitReady() } }
        entered.await()
        assertFalse(stopped)
        advanceTimeBy(1_001)
        runCurrent()
        assertTrue(waiting.await().exceptionOrNull() is TimeoutCancellationException)
        assertTrue(stopped)
    }
}
