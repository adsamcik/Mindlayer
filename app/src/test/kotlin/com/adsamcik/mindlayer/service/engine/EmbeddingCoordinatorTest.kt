package com.adsamcik.mindlayer.service.engine

import androidx.test.core.app.ApplicationProvider
import com.adsamcik.mindlayer.DeferredResult
import com.adsamcik.mindlayer.EmbeddingRequest
import com.adsamcik.mindlayer.EmbeddingTask
import com.adsamcik.mindlayer.service.ipc.SharedMemoryPool
import com.adsamcik.mindlayer.service.security.EvictionRegistry
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EmbeddingCoordinatorTest {
    private lateinit var context: android.content.Context
    private lateinit var engine: EmbeddingEngine
    private lateinit var store: DeferredStore
    private lateinit var callbacks: EvictionRegistry
    private lateinit var coordinator: EmbeddingCoordinator

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.cacheDir, "embedding-test.tflite").writeBytes(byteArrayOf(1))
        File(context.cacheDir, "sentencepiece.model").writeBytes(byteArrayOf(1))
        engine = mockk()
        coEvery { engine.embed(any(), any(), any(), any()) } answers {
            EmbeddingOutput(floatArrayOf(1f, 2f), 2, "embedding-test", 3, false, "CPU", 4)
        }
        store = DeferredStore(FakeDeferredDao(), clock = { System.currentTimeMillis() })
        callbacks = mockk(relaxed = true)
        coordinator = EmbeddingCoordinator(engine, store, context, CoroutineScope(SupervisorJob() + Dispatchers.Default), callbacks, SharedMemoryPool(context.cacheDir))
    }

    @Test fun `validation gauntlet emits typed embedding errors`() = runTest {
        assertCode(MindlayerErrorCode.EMBEDDING_INPUT_TOO_LONG) { coordinator.embed(1, EmbeddingRequest(text = ""), "r1") }
        assertCode(MindlayerErrorCode.EMBEDDING_BATCH_TOO_LARGE) { coordinator.embedBatch(1, List(65) { EmbeddingRequest(text = "x") }, "r2") }
        assertCode(MindlayerErrorCode.EMBEDDING_BATCH_TOO_LARGE) { coordinator.embedBatchShm(1, List(4097) { EmbeddingRequest(text = "x") }, "r3") }
        assertCode(MindlayerErrorCode.EMBEDDING_BATCH_TOO_LARGE) { coordinator.embedBatchDeferred(1, List(4097) { EmbeddingRequest(text = "x") }) }
        assertCode(MindlayerErrorCode.EMBEDDING_INPUT_TOO_LONG) { coordinator.embedBatch(1, listOf(EmbeddingRequest(text = "x".repeat(512 * 1024 + 1))), "r4") }
        assertCode(MindlayerErrorCode.INVALID_REQUEST) { coordinator.embed(1, EmbeddingRequest(text = "x", outputDim = 9), "r5") }
        assertCode(MindlayerErrorCode.INVALID_REQUEST) { coordinator.embed(1, EmbeddingRequest(text = "x", taskType = -1), "r6") }
    }

    @Test fun `happy path returns inline and batch results`() = runTest {
        val one = coordinator.embed(1, EmbeddingRequest(text = "hello", tag = "a"), "ok1")
        assertEquals("a", one.tag)
        assertEquals(2, one.dim)
        val batch = coordinator.embedBatch(1, listOf(EmbeddingRequest(text = "a"), EmbeddingRequest(text = "b")), "ok2")
        assertEquals(2, batch.results.size)
    }

    // The previously @Ignore'd Robolectric SHM layout test has been
    // removed. Robolectric cannot faithfully simulate
    // android.os.SharedMemory FD creation, mmap, protection,
    // parceling, or lifetime — keeping the test under @Ignore created
    // a coverage mirage. The real SHM layout coverage now lives in
    // app/src/androidTest/.../EmbeddingShmLayoutInstrumentedTest.kt,
    // which exercises the production EmbeddingShmLayout.writeLayout
    // helper against a real SharedMemory mapping on an emulator /
    // device. JVM-side layout invariants (offsets, endianness,
    // bounds) are pinned by app/src/test/.../EmbeddingShmLayoutTest.kt
    // which tests against a plain ByteBuffer — fast, deterministic,
    // and honest about what it covers.

    @Test fun `cancelEmbed unknown is safe and fetch cancelled row round trips`() = runTest {
        assertEquals(com.adsamcik.mindlayer.CancelResult.UNKNOWN, coordinator.cancelEmbed(1, "missing"))
        val handle = store.createEmbeddingBatch(1, "cancelled", 1)!!
        store.completeEmbeddingCancelled(handle.requestId, 1)
        assertEquals(DeferredResult.CANCELLED, coordinator.fetchEmbeddingBatchResult(1, "cancelled").status)
    }

    @Test fun `requestId regex rejects path-traversal sequences`() = runTest {
        // Even though [writeBlobFile] / [transferFromBlob] canonicalize the
        // path and clamp to cacheDir/embedding-blobs/<uid>/, the requestId
        // regex itself must forbid `..` substrings as defense in depth. A
        // future code path that builds a filename from requestId outside
        // those two helpers (logger, error message, audit trail) would
        // otherwise allow log injection or path traversal.
        for (bad in listOf("..", "...", "..foo", "foo..bar", "a..", "a-..-b")) {
            try {
                coordinator.cancelEmbeddingBatch(1, bad)
                error("expected SecurityException for requestId=$bad")
            } catch (e: SecurityException) {
                assertEquals(
                    MindlayerErrorCode.INVALID_REQUEST,
                    MindlayerErrorCode.codeFromWireMessage(e.message),
                )
            }
        }
    }

    @Test fun `requestId regex accepts single-dot and safe characters`() = runTest {
        // Single dots and hyphens are part of the canonical service-generated
        // ID format ("emb-<uid>-<uuid>"). Bare dots in the middle (not
        // doubled) must remain accepted so legacy IDs still validate.
        for (ok in listOf("emb-1-abc", "foo.bar", "a.b.c", "id_42", "x-y-z")) {
            // Either UNKNOWN (no such row) or ALREADY_FINISHED is acceptable —
            // both mean the requestId passed validation.
            val outcome = coordinator.cancelEmbeddingBatch(1, ok)
            assertTrue("requestId=$ok must pass validation (got $outcome)", outcome >= 0)
        }
    }

    @Test fun `deferred embedding blobs are encrypted on disk and decrypted for fetch`() = runTest {
        val dao = FakeDeferredDao()
        val localStore = DeferredStore(dao, clock = { System.currentTimeMillis() })
        val cipher = RecordingEmbeddingBlobCipher()
        val localCoordinator = coordinator(localStore, cipher)

        val handle = localCoordinator.embedBatchDeferred(7, listOf(EmbeddingRequest(text = "secret")))
        awaitReady(dao, handle.requestId)
        val row = dao.snapshot(handle.requestId)!!
        val diskBytes = File(row.blobPath!!).readBytes()
        assertTrue(diskBytes.decodeToString().startsWith("enc:"))

        val fetched = localCoordinator.fetchEmbeddingBatchResult(7, handle.requestId)
        fetched.transfer!!.pfd.close()
        val buffer = ByteBuffer.wrap(cipher.lastPlaintext!!).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, buffer.int)
        assertEquals(2, buffer.int)
        assertEquals(1.0f, buffer.float, 0.0f)
        assertEquals(2.0f, buffer.float, 0.0f)
    }

    @Test fun `uid cleanup preserves completed deferred embedding until acknowledgement`() = runTest {
        val dao = FakeDeferredDao()
        val localStore = DeferredStore(dao, clock = { System.currentTimeMillis() })
        val localCoordinator = coordinator(localStore)

        val handle = localCoordinator.embedBatchDeferred(7, listOf(EmbeddingRequest(text = "survive disconnect")))
        awaitReady(dao, handle.requestId)
        val blob = File(dao.snapshot(handle.requestId)!!.blobPath!!)
        assertTrue(blob.isFile)

        localCoordinator.cancelAllForUid(7)

        assertTrue("completed deferred blob must survive client cleanup", blob.isFile)
        val fetched = localCoordinator.fetchEmbeddingBatchResult(7, handle.requestId)
        assertEquals(DeferredResult.READY, fetched.status)
        fetched.transfer!!.pfd.close()
        assertTrue(localCoordinator.acknowledgeEmbeddingBatchResult(7, handle.requestId))
        assertFalse("acknowledgement must delete the deferred blob", blob.exists())
    }

    @Test fun `uid cleanup during ready handoff preserves deferred embedding`() = runTest {
        val delegate = FakeDeferredDao()
        val dao = BlockingQuotaDeferredDao(delegate)
        val localStore = DeferredStore(dao, clock = { System.currentTimeMillis() })
        val localCoordinator = coordinator(localStore)

        val handle = localCoordinator.embedBatchDeferred(7, listOf(EmbeddingRequest(text = "handoff race")))
        withContext(Dispatchers.Default) {
            withTimeout(5_000L) { dao.quotaCheckEntered.await() }
        }
        val blob = File(delegate.snapshot(handle.requestId)!!.blobPath!!)
        assertTrue("READY row must reference an existing blob before cleanup", blob.isFile)

        localCoordinator.cancelAllForUid(7)
        dao.releaseQuotaCheck.complete(Unit)
        awaitInactive(localCoordinator)

        assertTrue("completed blob must survive cancellation during ownership handoff", blob.isFile)
        val fetched = localCoordinator.fetchEmbeddingBatchResult(7, handle.requestId)
        assertEquals(DeferredResult.READY, fetched.status)
        fetched.transfer!!.pfd.close()
        assertTrue(localCoordinator.acknowledgeEmbeddingBatchResult(7, handle.requestId))
        assertFalse(blob.exists())
    }

    @Test fun `uid cleanup does not sweep temp files from a concurrent request`() = runTest {
        val uidDir = File(File(context.cacheDir, "embedding-blobs"), "7").also { it.mkdirs() }
        val concurrentTemp = File(uidDir, "new-request.tmp-race").also { it.writeBytes(byteArrayOf(1)) }

        coordinator.cancelAllForUid(7)

        assertTrue("UID cleanup must not delete another request's active temp file", concurrentTemp.isFile)
        concurrentTemp.delete()
    }

    @Test fun `uid cleanup prevents deferred submission from starting after disconnect`() = runTest {
        val delegate = FakeDeferredDao()
        val dao = BlockingCreateDeferredDao(delegate)
        val localCoordinator = coordinator(DeferredStore(dao, clock = { System.currentTimeMillis() }))

        val submission = async(Dispatchers.Default) {
            localCoordinator.embedBatchDeferred(7, listOf(EmbeddingRequest(text = "racing submission")))
        }
        withContext(Dispatchers.Default) {
            withTimeout(5_000L) { dao.createEntered.await() }
        }

        val cleanup = async(start = CoroutineStart.UNDISPATCHED) {
            localCoordinator.cancelAllForUid(7)
        }
        dao.releaseCreate.complete(Unit)
        val handle = submission.await()
        cleanup.await()
        awaitNotRunning(delegate, handle.requestId)
        awaitInactive(localCoordinator)

        assertEquals(DeferredResult.CANCELLED, delegate.snapshot(handle.requestId)?.statusCode)
        assertEquals(0, localCoordinator.activeEmbeddingBatchCount)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `uid cleanup cancels row when lazy job has not entered its body`() = runTest {
        val dao = FakeDeferredDao()
        val localStore = DeferredStore(dao, clock = { System.currentTimeMillis() })
        val queuedScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val localCoordinator = coordinator(localStore, scope = queuedScope)

        val handle =
            localCoordinator.embedBatchDeferred(7, listOf(EmbeddingRequest(text = "queued")))
        localCoordinator.cancelAllForUid(7)
        runCurrent()
        awaitInactive(localCoordinator)

        assertEquals(DeferredResult.CANCELLED, dao.snapshot(handle.requestId)?.statusCode)
        assertEquals(0, localCoordinator.activeEmbeddingBatchCount)
    }

    @Test fun `revocation purges completed deferred embedding`() = runTest {
        val dao = FakeDeferredDao()
        val localStore = DeferredStore(dao, clock = { System.currentTimeMillis() })
        val localCoordinator = coordinator(localStore)
        val authorized = AtomicBoolean(true)
        val handle = localCoordinator.embedBatchDeferred(
            7,
            listOf(EmbeddingRequest(text = "revoke")),
        ) { authorized.get() }
        awaitReady(dao, handle.requestId)
        val blob = File(dao.snapshot(handle.requestId)!!.blobPath!!)

        authorized.set(false)
        localCoordinator.revokeAllForUid(7)

        assertNull(dao.snapshot(handle.requestId))
        assertFalse(blob.exists())

        assertCode(MindlayerErrorCode.CONSENT_REQUIRED) {
            localCoordinator.embedBatchDeferred(
                7,
                listOf(EmbeddingRequest(text = "late revoked call")),
            ) { authorized.get() }
        }
        assertEquals(0, localCoordinator.activeEmbeddingBatchCount)

        authorized.set(true)
        val reapproved = localCoordinator.embedBatchDeferred(
            7,
            listOf(EmbeddingRequest(text = "reapproved")),
        ) { authorized.get() }
        awaitReady(dao, reapproved.requestId)
        assertTrue(localCoordinator.acknowledgeEmbeddingBatchResult(7, reapproved.requestId))
    }

    @Test fun `registration authorized before revoke cannot start after revoke`() = runTest {
        val delegate = FakeDeferredDao()
        val dao = BlockingCreateDeferredDao(delegate)
        val localCoordinator = coordinator(DeferredStore(dao, clock = { System.currentTimeMillis() }))
        val authorized = AtomicBoolean(true)

        val submission = async(Dispatchers.Default) {
            runCatching {
                localCoordinator.embedBatchDeferred(
                    7,
                    listOf(EmbeddingRequest(text = "stale registration")),
                ) { authorized.get() }
            }
        }
        withContext(Dispatchers.Default) {
            withTimeout(5_000L) { dao.createEntered.await() }
        }

        authorized.set(false)
        val cleanup = async(start = CoroutineStart.UNDISPATCHED) {
            localCoordinator.revokeAllForUid(7)
        }
        dao.releaseCreate.complete(Unit)

        val result = submission.await()
        assertCode(MindlayerErrorCode.CONSENT_REQUIRED) { result.getOrThrow() }
        cleanup.await()
        assertTrue(delegate.rowsForUid(7).isEmpty())
        assertEquals(0, localCoordinator.activeEmbeddingBatchCount)
    }

    @Test fun `coordinator constructor reads production-ready flag with default`() {
        // Documents the test contract: the flag's default is the compile-time
        // EmbeddingFeatureFlags constant. If the constant flips, this
        // assertion's expected value follows automatically — but the
        // EmbeddingCoordinatorTest covers the default-binding contract,
        // not the value choice.
        assertEquals(EmbeddingFeatureFlags.IS_PRODUCTION_READY, coordinator.isProductionReady)
    }

    private inline fun assertCode(code: Int, block: () -> Unit) {
        try {
            block()
            error("expected SecurityException")
        } catch (e: SecurityException) {
            assertEquals(code, MindlayerErrorCode.codeFromWireMessage(e.message))
        }
    }

    private fun coordinator(
        localStore: DeferredStore,
        blobCipher: EmbeddingBlobCipher = RecordingEmbeddingBlobCipher(),
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    ): EmbeddingCoordinator =
        EmbeddingCoordinator(
            engine,
            localStore,
            context,
            scope,
            callbacks,
            SharedMemoryPool(context.cacheDir),
            blobCipher = blobCipher,
        )

    private suspend fun awaitReady(dao: FakeDeferredDao, requestId: String) {
        withContext(Dispatchers.Default) {
            withTimeout(5_000L) {
                while (dao.snapshot(requestId)?.statusCode != DeferredResult.READY) {
                    delay(10)
                }
            }
        }
    }

    private suspend fun awaitInactive(localCoordinator: EmbeddingCoordinator) {
        withContext(Dispatchers.Default) {
            withTimeout(5_000L) {
                while (localCoordinator.activeEmbeddingBatchCount != 0) {
                    delay(10)
                }
            }
        }
    }

    private suspend fun awaitNotRunning(dao: FakeDeferredDao, requestId: String) {
        withContext(Dispatchers.Default) {
            withTimeout(5_000L) {
                while (dao.snapshot(requestId)?.statusCode == DeferredResult.STILL_RUNNING) {
                    delay(10)
                }
            }
        }
    }

    private class RecordingEmbeddingBlobCipher : EmbeddingBlobCipher {
        var lastPlaintext: ByteArray? = null
            private set

        override fun encrypt(uid: Int, plaintext: ByteArray): ByteArray =
            "enc:".encodeToByteArray() + plaintext.reversedArray()

        override fun decrypt(uid: Int, ciphertext: ByteArray): ByteArray {
            require(ciphertext.take(4).toByteArray().contentEquals("enc:".encodeToByteArray()))
            return ciphertext.drop(4).toByteArray().reversedArray().also {
                lastPlaintext = it.copyOf()
            }
        }
    }

    private class BlockingQuotaDeferredDao(
        private val delegate: FakeDeferredDao,
    ) : DeferredDao by delegate {
        val quotaCheckEntered = CompletableDeferred<Unit>()
        val releaseQuotaCheck = CompletableDeferred<Unit>()

        override suspend fun resultBytes(uid: Int, kind: String): Long {
            quotaCheckEntered.complete(Unit)
            releaseQuotaCheck.await()
            return delegate.resultBytes(uid, kind)
        }
    }

    private class BlockingCreateDeferredDao(
        private val delegate: FakeDeferredDao,
    ) : DeferredDao by delegate {
        val createEntered = CompletableDeferred<Unit>()
        val releaseCreate = CompletableDeferred<Unit>()

        override suspend fun createIfWithinQuota(
            entity: DeferredEntity,
            maxRunning: Int,
            maxCompletedPending: Int,
        ): Boolean {
            createEntered.complete(Unit)
            releaseCreate.await()
            return delegate.createIfWithinQuota(entity, maxRunning, maxCompletedPending)
        }
    }
}
