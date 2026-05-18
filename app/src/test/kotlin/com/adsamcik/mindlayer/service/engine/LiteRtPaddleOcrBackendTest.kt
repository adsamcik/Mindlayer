package com.adsamcik.mindlayer.service.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit tests for [LiteRtPaddleOcrBackend] scaffold.
 *
 * These tests cover the lifecycle seam that ships in PR C2 — file
 * existence checks, memory headroom, backend resolution, idempotence
 * — without exercising the actual det/rec/cls interpreter path
 * (which is marked ``TODO(verifyOnDevice)`` and fails closed with a
 * recognisable error).
 *
 * Real PP-OCRv5 inference is verified at androidTest time on a real
 * emulator after the conversion workflow uploads ``.tflite`` artifacts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LiteRtPaddleOcrBackendTest {

    private lateinit var context: Context
    private lateinit var bundle: PaddleOcrModelInfo

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val dir = context.filesDir
        dir.listFiles()?.forEach { it.delete() }
        val det = File(dir, "paddleocr-ppocrv5-mobile-det.tflite").apply { writeBytes(byteArrayOf(1)) }
        val rec = File(dir, "paddleocr-ppocrv5-mobile-rec.tflite").apply { writeBytes(byteArrayOf(2)) }
        val cls = File(dir, "paddleocr-ppocrv5-mobile-cls.tflite").apply { writeBytes(byteArrayOf(3)) }
        val dict = File(dir, "paddleocr-ppocrv5-mobile-dict.txt").apply { writeBytes(byteArrayOf(4)) }
        bundle = PaddleOcrModelInfo(
            id = "paddleocr-ppocrv5-mobile",
            displayName = "PaddleOCR PP-OCRv5 mobile",
            detectionPath = det.absolutePath,
            recognitionPath = rec.absolutePath,
            classifierPath = cls.absolutePath,
            dictionaryPath = dict.absolutePath,
            totalSizeBytes = 4L,
            detSha256 = null,
            recSha256 = null,
            clsSha256 = null,
            dictSha256 = null,
        )
    }

    // ── Initial state ────────────────────────────────────────────────────

    @Test fun `before init activeBackend is NONE and isInitialized is false`() {
        val backend = LiteRtPaddleOcrBackend(context)
        assertEquals("NONE", backend.activeBackend)
        assertEquals(false, backend.isInitialized)
        assertNull(backend.currentBundle)
    }

    // ── initialize() ─────────────────────────────────────────────────────

    private fun backend() = LiteRtPaddleOcrBackend(
        context,
        memoryHeadroomBytes = 0L,
        availableMemoryProvider = { Long.MAX_VALUE },
    )

    @Test fun `initialize sets activeBackend and currentBundle`() = runTest {
        val backend = backend()
        backend.initialize(bundle, preferredBackend = "CPU")
        assertEquals("CPU", backend.activeBackend)
        assertTrue(backend.isInitialized)
        assertEquals(bundle.id, backend.currentBundle?.id)
    }

    @Test fun `initialize with null preferredBackend defaults to GPU`() = runTest {
        val backend = backend()
        backend.initialize(bundle, preferredBackend = null)
        assertEquals("GPU", backend.activeBackend)
    }

    @Test fun `initialize with unknown preferredBackend falls back to CPU`() = runTest {
        val backend = backend()
        backend.initialize(bundle, preferredBackend = "DSP")
        assertEquals("CPU", backend.activeBackend)
    }

    @Test fun `initialize is idempotent for same bundle`() = runTest {
        val backend = backend()
        backend.initialize(bundle, "GPU")
        backend.initialize(bundle, "GPU")
        assertEquals("GPU", backend.activeBackend)
    }

    @Test fun `initialize fails when det file missing`() = runTest {
        File(bundle.detectionPath).delete()
        val backend = backend()
        val ex = assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { backend.initialize(bundle) }
        }
        assertTrue("Error should mention det: ${ex.message}", ex.message!!.contains("det"))
    }

    @Test fun `initialize fails when rec file missing`() = runTest {
        File(bundle.recognitionPath).delete()
        val backend = backend()
        val ex = assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { backend.initialize(bundle) }
        }
        assertTrue("Error should mention rec: ${ex.message}", ex.message!!.contains("rec"))
    }

    @Test fun `initialize fails when dict file missing`() = runTest {
        File(bundle.dictionaryPath).delete()
        val backend = backend()
        val ex = assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { backend.initialize(bundle) }
        }
        assertTrue("Error should mention dict: ${ex.message}", ex.message!!.contains("dict"))
    }

    @Test fun `initialize fails when memory headroom is insufficient`() = runTest {
        // Set absurdly high headroom requirement.
        val backend = LiteRtPaddleOcrBackend(
            context,
            memoryHeadroomBytes = Long.MAX_VALUE / 2,
        )
        assertThrows(LowMemoryException::class.java) {
            kotlinx.coroutines.runBlocking { backend.initialize(bundle) }
        }
    }

    // ── recognise() — scaffolded path ────────────────────────────────────

    @Test fun `recognise throws when not initialised`() = runTest {
        val backend = backend()
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking {
                backend.recognise(ByteArray(64 * 64), 64, 64)
            }
        }
    }

    @Test fun `recognise after init fails closed with the scaffolded error`() = runTest {
        val backend = backend()
        backend.initialize(bundle, "GPU")
        val ex = assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking {
                backend.recognise(ByteArray(64 * 64), 64, 64)
            }
        }
        // The recognise() body must carry a hint pointing at the
        // conversion-pipeline workflow so future investigators know
        // where the missing piece comes from.
        assertNotNull(ex.message)
        assertTrue(
            "Scaffold error should reference build-paddleocr-models workflow: ${ex.message}",
            ex.message!!.contains("build-paddleocr-models"),
        )
    }

    // ── shutdown() ───────────────────────────────────────────────────────

    @Test fun `shutdown on uninitialised backend is a no-op`() = runTest {
        val backend = backend()
        backend.shutdown()
        assertEquals("NONE", backend.activeBackend)
    }

    @Test fun `shutdown clears state`() = runTest {
        val backend = backend()
        backend.initialize(bundle, "GPU")
        backend.shutdown()
        assertEquals("NONE", backend.activeBackend)
        assertEquals(false, backend.isInitialized)
        assertNull(backend.currentBundle)
    }

    @Test fun `shutdown is idempotent`() = runTest {
        val backend = backend()
        backend.initialize(bundle, "GPU")
        backend.shutdown()
        backend.shutdown()
        assertEquals("NONE", backend.activeBackend)
    }
}
