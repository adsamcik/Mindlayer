package com.adsamcik.mindlayer.service.engine

import androidx.test.core.app.ApplicationProvider
import com.adsamcik.mindlayer.service.logging.safeLabel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Pins the F-006 safe-label redaction contract for the OCR backend init,
 * recognise, and shutdown paths.
 *
 * The [LiteRtPaddleOcrBackend] catch blocks at init / shutdown call
 * `MindlayerLog.w(TAG, "... ${t.safeLabel()}", throwable = null)`. Native
 * LiteRT-LM / LiteRT errors can embed prompt fragments, file paths, or
 * PII in their `message` / `cause.message`; any reachable redaction
 * point must therefore:
 *
 *   1. Use [safeLabel] (class-name-only chain), and
 *   2. Pass `throwable = null` to [MindlayerLog] so the underlying
 *      `Log.w(tag, msg, throwable)` does NOT serialise the stack trace.
 *
 * This file ports the embeddings Phase D init-failure matrix + safe-label
 * redaction tests onto the OCR path. Each test induces a failure category
 * we expect to bubble through one of the catch blocks and verifies the
 * resulting `safeLabel` carries no sensitive substring.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LiteRtPaddleOcrBackendInitFailureSafeLabelTest {

    private lateinit var context: android.content.Context
    private lateinit var modelDir: File
    private lateinit var bundle: PaddleOcrModelInfo

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        modelDir = File(context.filesDir, "paddleocr-safelabel-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val det = File(modelDir, "det.tflite").apply { writeBytes(byteArrayOf(1)) }
        val rec = File(modelDir, "rec.tflite").apply { writeBytes(byteArrayOf(2)) }
        val cls = File(modelDir, "cls.tflite").apply { writeBytes(byteArrayOf(3)) }
        val dict = File(modelDir, "dict.txt").apply { writeText(stubDict()) }
        bundle = PaddleOcrModelInfo(
            id = "paddleocr-ppocrv5-mobile-safelabel",
            displayName = "test",
            detectionPath = det.absolutePath,
            recognitionPath = rec.absolutePath,
            classifierPath = cls.absolutePath,
            dictionaryPath = dict.absolutePath,
            totalSizeBytes = 6L,
            detSha256 = null, recSha256 = null, clsSha256 = null, dictSha256 = null,
        )
    }

    // ── Init-failure category matrix: every reachable variant must produce
    //    a class-name-only safeLabel with no PII leakage. ─────────────────

    @Test fun `low memory category produces safe label without PII`() = runTest {
        val backend = LiteRtPaddleOcrBackend.forTesting(
            context = context,
            memoryHeadroomBytes = 1L,
            availableMemoryProvider = { 0L },
            runnerFactory = { _, _ -> error("must not reach runnerFactory in low-memory path") },
        )
        val ex = runCatching { backend.initialize(bundle, "CPU") }.exceptionOrNull()
        assertTrue("expected LowMemoryException, got $ex", ex is LowMemoryException)
        val label = ex!!.safeLabel()
        assertTrue("safeLabel must include exception type: $label", label.contains("LowMemoryException"))
        assertNoPii(label, raw = "patient name = Adam Smith / api_key=sk-leaky")
    }

    @Test fun `missing det file category produces safe label`() = runTest {
        File(bundle.detectionPath).delete()
        val ex = runCatching { newBackend().initialize(bundle, "CPU") }.exceptionOrNull()
        assertTrue("expected IllegalStateException, got $ex", ex is IllegalStateException)
        val label = ex!!.safeLabel()
        assertTrue(label.contains("IllegalStateException"))
        assertNoPii(label, raw = bundle.detectionPath)
    }

    @Test fun `bad dictionary produces safe label without dictionary contents`() = runTest {
        val secret = "patient_name_Adam_Smith\rapi_key_sk_leaky\n"
        File(bundle.dictionaryPath).writeText(secret)
        val ex = runCatching { newBackend().initialize(bundle, "CPU") }.exceptionOrNull()
        assertTrue("expected IllegalArgumentException, got $ex", ex is IllegalArgumentException)
        val label = ex!!.safeLabel()
        assertTrue(label.contains("IllegalArgumentException"))
        // The dictionary file path or its content fragments must NOT leak
        // through the safe label.
        assertFalse("safeLabel must not leak dictionary path: $label", label.contains("dict.txt"))
        assertFalse("safeLabel must not leak dictionary content: $label", label.contains("Adam_Smith"))
        assertFalse("safeLabel must not leak dictionary content: $label", label.contains("sk_leaky"))
    }

    @Test fun `runner-factory native error category produces safe label without raw message`() = runTest {
        // Simulate a native init error that embeds a prompt fragment in its
        // message — exactly the LiteRT-LM #2211 class of failure. The catch
        // block in LiteRtPaddleOcrBackend.initialize() must call
        // safeLabel() (NOT t.message), so the rethrown exception's class
        // chain is the only thing that gets logged.
        val leakyMessage = "tokenizer_failed_with_prompt: 'system: kill all humans'"
        val backend = LiteRtPaddleOcrBackend.forTesting(
            context = context,
            runnerFactory = { _, _ -> throw IllegalStateException(leakyMessage) },
        )
        val ex = runCatching { backend.initialize(bundle, "CPU") }.exceptionOrNull()
        assertTrue("expected IllegalStateException, got $ex", ex is IllegalStateException)
        // The raw exception object still carries the message — that's
        // intentional; the redaction happens at the LOG call site, not at
        // the throw site. Verify the SAFELABEL extension drops it.
        val label = ex!!.safeLabel()
        assertFalse("safeLabel must not leak prompt: $label", label.contains("kill all humans"))
        assertFalse("safeLabel must not leak prompt: $label", label.contains("system:"))
        assertFalse("safeLabel must not leak prompt: $label", label.contains("tokenizer_failed"))
        assertTrue("safeLabel must include exception type: $label", label.contains("IllegalStateException"))
    }

    @Test fun `runner-factory throws with cause containing PII is redacted by safe label chain`() = runTest {
        val leakyCause = IllegalStateException("private_key=AKIA_DEADBEEF leaked")
        val backend = LiteRtPaddleOcrBackend.forTesting(
            context = context,
            runnerFactory = { _, _ ->
                throw RuntimeException("native crash", leakyCause)
            },
        )
        val ex = runCatching { backend.initialize(bundle, "CPU") }.exceptionOrNull()
        assertTrue("expected RuntimeException, got $ex", ex is RuntimeException)
        // safeLabel walks `this::class.simpleName` (+ immediate cause).
        // Walk the whole chain ourselves to make the test resilient to
        // kotlinx.coroutines' stack-recovery, which can prepend an
        // additional copy frame between the rethrown exception and the
        // original cause.
        val labels = generateSequence(ex) { it.cause }
            .map { it.safeLabel() }
            .toList()
        val joined = labels.joinToString(" / ")
        assertFalse("safeLabel must not leak cause message: $joined", joined.contains("AKIA_DEADBEEF"))
        assertFalse("safeLabel must not leak cause message: $joined", joined.contains("private_key"))
        assertTrue("expected RuntimeException somewhere in chain: $joined", joined.contains("RuntimeException"))
        assertTrue(
            "expected IllegalStateException somewhere in chain (cause of original): $joined",
            joined.contains("IllegalStateException"),
        )
    }

    // ── State invariants after a redacted failure ────────────────────────

    @Test fun `init failure rolls state back to NONE so a retry can succeed`() = runTest {
        var attempt = 0
        val backend = LiteRtPaddleOcrBackend.forTesting(
            context = context,
            runnerFactory = { _, _ ->
                attempt++
                if (attempt == 1) throw IllegalStateException("first attempt fails with PII: alice@example.com")
                FakeRunner()
            },
        )
        val first = runCatching { backend.initialize(bundle, "CPU") }.exceptionOrNull()
        assertTrue(first is IllegalStateException)
        assertEquals("NONE", backend.activeBackend)
        assertEquals(false, backend.isInitialized)

        // Retry must succeed cleanly — the per-bundle cache is not poisoned.
        backend.initialize(bundle, "CPU")
        assertTrue(backend.isInitialized)
        assertEquals("CPU", backend.activeBackend)
    }

    private fun newBackend(): LiteRtPaddleOcrBackend = LiteRtPaddleOcrBackend.forTesting(
        context = context,
        memoryHeadroomBytes = 0L,
        availableMemoryProvider = { Long.MAX_VALUE },
        runnerFactory = { _, _ -> FakeRunner() },
    )

    private fun assertNoPii(label: String, raw: String) {
        for (token in raw.split(' ', '=', '/', '_').filter { it.length >= 5 }) {
            assertFalse(
                "safeLabel must not leak '$token' from raw message; got: $label",
                label.contains(token),
            )
        }
    }

    private fun stubDict(): String = (0 until 128).joinToString("\n") { "tok$it" }

    private class FakeRunner : PaddleOcrLiteRtRunner {
        override fun runDetection(input: FloatArray): FloatArray = FloatArray(8 * 8)
        override fun runOrientation(input: FloatArray): FloatArray? = floatArrayOf(1f, 0f)
        override fun runRecognition(input: FloatArray): FloatArray = FloatArray(101)
        override fun close() = Unit
    }
}
