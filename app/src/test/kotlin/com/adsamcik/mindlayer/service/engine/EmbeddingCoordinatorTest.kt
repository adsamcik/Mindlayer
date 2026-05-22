package com.adsamcik.mindlayer.service.engine

import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.mindlayer.DeferredResult
import com.adsamcik.mindlayer.EmbeddingRequest
import com.adsamcik.mindlayer.EmbeddingTask
import com.adsamcik.mindlayer.service.ipc.SharedMemoryPool
import com.adsamcik.mindlayer.service.security.EvictionRegistry
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

        @Test fun `deferred embedding blobs are encrypted on disk and decrypted for fetch`() = runTest {
            val dao = FakeDeferredDao()
            val localStore = DeferredStore(dao, clock = { System.currentTimeMillis() })
            val cipher = object : EmbeddingBlobCipher {
                override fun encrypt(uid: Int, plaintext: ByteArray): ByteArray =
                    byteArrayOf('e'.code.toByte(), 'n'.code.toByte(), 'c'.code.toByte(), ':'.code.toByte()) + plaintext.reversedArray()

                override fun decrypt(uid: Int, ciphertext: ByteArray): ByteArray {
                    require(ciphertext.take(4).toByteArray().contentEquals(byteArrayOf('e'.code.toByte(), 'n'.code.toByte(), 'c'.code.toByte(), ':'.code.toByte())))
                    return ciphertext.drop(4).toByteArray().reversedArray()
                }
            }
            val localCoordinator = EmbeddingCoordinator(
                engine,
                localStore,
                context,
                CoroutineScope(SupervisorJob() + Dispatchers.Default),
                callbacks,
                SharedMemoryPool(context.cacheDir),
                blobCipher = cipher,
            )

            val handle = localCoordinator.embedBatchDeferred(7, listOf(EmbeddingRequest(text = "secret")))
            withTimeout(5_000L) {
                while (dao.snapshot(handle.requestId)?.statusCode != DeferredResult.READY) {
                    delay(10)
                }
            }
            val row = dao.snapshot(handle.requestId)!!
            val diskBytes = File(row.blobPath!!).readBytes()
            assertTrue(diskBytes.decodeToString().startsWith("enc:"))

            val fetched = localCoordinator.fetchEmbeddingBatchResult(7, handle.requestId)
            val plaintext = ParcelFileDescriptor.AutoCloseInputStream(fetched.transfer!!.pfd).use { it.readBytes() }
            val buffer = ByteBuffer.wrap(plaintext).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(1, buffer.int)
            assertEquals(2, buffer.int)
            assertEquals(1.0f, buffer.float, 0.0f)
            assertEquals(2.0f, buffer.float, 0.0f)
        }
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
}
