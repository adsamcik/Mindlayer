package com.adsamcik.mindlayer.service.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adsamcik.mindlayer.EmbeddingRequest
import com.adsamcik.mindlayer.IMindlayerService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * End-to-end embedding validation through the live `:ml` service.
 *
 * Binds directly to [IMindlayerService] via raw AIDL rather than the
 * public SDK, because the SDK hardcodes the release package name
 * `com.adsamcik.mindlayer` and we're targeting the
 * `com.adsamcik.mindlayer.debug` variant on the emulator. The
 * test still exercises the full path: real EmbeddingGemma tflite +
 * SentencePiece tokenizer load on disk, then `embed()` round-trip
 * through AIDL into LiteRtEmbeddingBackend.
 *
 * Gated on the presence of `embedding-gemma-300m-v1.tflite` +
 * `embedding-gemma-300m-v1.spm.model` in `getExternalFilesDir(null)`.
 * When absent (clean CI emulator without provisioned assets), the test
 * `assumeTrue`-skips so the lane stays green — same gating pattern as
 * [EngineCoexistenceInstrumentedTest.paddleocr_production_backend_loads_real_ai_pack_assets].
 *
 * Auth: the test runs inside the target app's UID (instrumentation
 * default targetProcess), so the service's self-UID bypass on the
 * 4-stage gate applies — no AllowlistStore approval is needed.
 */
@RunWith(AndroidJUnit4::class)
class EmbeddingEndToEndInstrumentedTest {

    private lateinit var ctx: Context
    private var binder: IMindlayerService? = null
    private var conn: ServiceConnection? = null

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        val ext = ctx.getExternalFilesDir(null)
        val weights = File(ext, "embedding-gemma-300m-v1.tflite")
        val tokenizer = File(ext, "embedding-gemma-300m-v1.spm.model")
        assumeTrue(
            "EmbeddingGemma weights + tokenizer must be present in externalFilesDir. " +
                "Sideload via adb push to /storage/emulated/0/Android/data/" +
                "${ctx.packageName}/files/. Got weights.exists=${weights.exists()} " +
                "tokenizer.exists=${tokenizer.exists()}.",
            weights.exists() && tokenizer.exists(),
        )
        binder = bindServiceBlocking(timeoutMs = 30_000L)
        assertNotNull("bindService must succeed for in-process test", binder)
    }

    @After
    fun tearDown() {
        conn?.let {
            try { ctx.unbindService(it) } catch (_: IllegalArgumentException) {}
        }
        conn = null
        binder = null
    }

    @Test
    fun embed_returns_normalised_vector_for_simple_text() = runBlocking {
        val svc = binder!!
        val result = withTimeout(120_000L) {
            svc.embed(EmbeddingRequest(text = "Hello world", tag = "smoke"))
        }
        assertNotNull("EmbeddingResult must not be null", result)
        val v = result.vector
        assertTrue(
            "Vector dimension should be > 0 (EmbeddingGemma typically 768). Got ${v.size}.",
            v.size > 0,
        )
        // Default is RETRIEVAL_DOCUMENT + normalize=true → L2 norm ≈ 1.0
        val norm = sqrt(v.fold(0.0) { acc, x -> acc + x * x }).toFloat()
        assertEquals(
            "Vector should be L2-normalised. Got ‖v‖=$norm.",
            1.0f, norm, 0.01f,
        )
        val anyNonZero = v.any { abs(it) > 1e-6f }
        assertTrue("Vector must have at least one non-zero component", anyNonZero)
        val anyNaN = v.any { it.isNaN() }
        assertEquals("Vector must not contain NaN values", false, anyNaN)
        assertEquals("Tag should round-trip back", "smoke", result.tag)
    }

    @Test
    fun embed_produces_distinguishable_vectors_for_different_inputs() = runBlocking {
        val svc = binder!!
        val a = withTimeout(120_000L) {
            svc.embed(EmbeddingRequest(text = "The cat sits on the mat.", tag = "a"))
        }
        val b = withTimeout(120_000L) {
            svc.embed(EmbeddingRequest(text = "Quantum mechanics describes particles.", tag = "b"))
        }
        assertEquals("Both vectors should have the same dimension.", a.vector.size, b.vector.size)
        var cosine = 0.0
        for (i in a.vector.indices) cosine += a.vector[i].toDouble() * b.vector[i].toDouble()
        assertNotEquals(
            "Topically unrelated inputs should not produce identical vectors. cosine=$cosine.",
            1.0, cosine,
        )
        assertTrue(
            "Topically unrelated inputs should produce vectors with cosine < 0.99. Got cosine=$cosine.",
            cosine < 0.99,
        )
    }

    private fun bindServiceBlocking(timeoutMs: Long): IMindlayerService? {
        val latch = CountDownLatch(1)
        var result: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                result = service
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        conn = connection
        val intent = Intent().setComponent(
            ComponentName(ctx.packageName, "com.adsamcik.mindlayer.service.MindlayerMlService"),
        )
        val ok = ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        assertTrue("bindService(${intent.component}) must return true", ok)
        assertTrue(
            "Service must connect within ${timeoutMs}ms",
            latch.await(timeoutMs, TimeUnit.MILLISECONDS),
        )
        return result?.let { IMindlayerService.Stub.asInterface(it) }
    }
}
