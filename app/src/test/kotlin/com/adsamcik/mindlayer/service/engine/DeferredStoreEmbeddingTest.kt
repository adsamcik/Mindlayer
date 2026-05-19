package com.adsamcik.mindlayer.service.engine

import android.os.Bundle
import com.adsamcik.mindlayer.DeferredResult
import com.adsamcik.mindlayer.EmbeddingItemMetadata
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeferredStoreEmbeddingTest {
    private lateinit var dao: FakeDeferredDao
    private var now = 1_000L
    private fun store(
        maxBytes: Long = 1_000L,
        maxEmbeddingBytes: Long = maxBytes,
        ttlMs: Long = 10_000L,
    ) = DeferredStore(
        dao = dao,
        clock = { now },
        ttlMs = ttlMs,
        maxResultBytesPerUid = maxBytes,
        maxEmbeddingResultBytesPerUid = maxEmbeddingBytes,
    )

    @Before fun setUp() {
        dao = FakeDeferredDao()
        now = 1_000L
    }

    @Test fun `create complete fetch round trip with metadata`() = runTest {
        val s = store()
        val blob = File.createTempFile("emb", ".bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val meta = listOf(EmbeddingItemMetadata("a", 12, false), EmbeddingItemMetadata(null, 7, true))
        s.createEmbeddingBatch(10, "req", 2)
        s.completeEmbeddingBatch("req", 10, blob.absolutePath, blob.length(), Bundle().apply { putInt("count", 2); putInt("dim", 1) }, meta)
        val out = s.fetchEmbeddingBatch(10, "req") as EmbeddingFetchOutcome.Ready
        assertEquals(blob.absolutePath, out.blobPath)
        assertEquals(meta, out.metadata)
        assertEquals(2, out.metrics?.getInt("count"))
    }

    @Test fun `blobBytes count against quota and oldest evicted`() = runTest {
        // Embedding blobs are sized against [maxEmbeddingResultBytesPerUid]
        // independently from chat. The test sets BOTH caps to 100 B to
        // pin the eviction boundary.
        val s = store(maxBytes = 100L, maxEmbeddingBytes = 100L)
        val old = File.createTempFile("old", ".bin").apply { writeBytes(ByteArray(80)) }
        val newer = File.createTempFile("new", ".bin").apply { writeBytes(ByteArray(40)) }
        s.createEmbeddingBatch(1, "old", 1)
        s.completeEmbeddingBatch("old", 1, old.absolutePath, 80, null)
        now += 1
        s.createEmbeddingBatch(1, "new", 1)
        s.completeEmbeddingBatch("new", 1, newer.absolutePath, 40, null)
        assertNull(dao.snapshot("old"))
        assertFalse(old.exists())
        assertTrue(newer.exists())
    }

    @Test fun `embedding quota independent from chat quota`() = runTest {
        // Pin the regression that motivated the per-kind quota split: at
        // chat-quota=1MiB and embedding-quota=16MiB, a 12MiB embedding
        // batch must NOT be evicted just because it exceeds the chat cap.
        val s = store(maxBytes = 1L * 1024 * 1024, maxEmbeddingBytes = 16L * 1024 * 1024)
        val big = File.createTempFile("big", ".bin").apply { writeBytes(ByteArray(12 * 1024)) }
        s.createEmbeddingBatch(1, "big", 1)
        s.completeEmbeddingBatch("big", 1, big.absolutePath, (12L * 1024 * 1024), null)
        // Row must survive: blob bytes (12 MiB) exceed the chat cap (1 MiB)
        // but stay well under the embedding cap (16 MiB).
        assertTrue(dao.snapshot("big") != null)
        assertTrue(big.exists())
    }

    @Test fun `cancel acknowledge and prune remove blob files`() = runTest {
        val s = store(ttlMs = 5L)
        val cancelBlob = File.createTempFile("cancel", ".bin").apply { writeText("x") }
        s.createEmbeddingBatch(1, "cancel", 1)
        s.completeEmbeddingBatch("cancel", 1, cancelBlob.absolutePath, 1, null)
        assertEquals(com.adsamcik.mindlayer.CancelResult.ALREADY_FINISHED, s.cancelEmbeddingBatch("cancel", 1))
        assertFalse(cancelBlob.exists())

        val ackBlob = File.createTempFile("ack", ".bin").apply { writeText("x") }
        s.createEmbeddingBatch(1, "ack", 1)
        s.completeEmbeddingBatch("ack", 1, ackBlob.absolutePath, 1, null)
        assertTrue(s.acknowledgeEmbeddingBatch(1, "ack"))
        assertFalse(ackBlob.exists())

        val pruneBlob = File.createTempFile("prune", ".bin").apply { writeText("x") }
        s.createEmbeddingBatch(1, "prune", 1)
        s.completeEmbeddingBatch("prune", 1, pruneBlob.absolutePath, 1, null)
        now += 10
        assertEquals(1, s.pruneExpired())
        assertFalse(pruneBlob.exists())
    }

    @Test fun `ttl applies to embedding rows`() = runTest {
        val s = store(ttlMs = 5L)
        s.createEmbeddingBatch(1, "ttl", 1)
        now += 10
        assertEquals(EmbeddingFetchOutcome.Expired, s.fetchEmbeddingBatch(1, "ttl"))
    }
}