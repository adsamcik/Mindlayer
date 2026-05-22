package com.adsamcik.mindlayer.service.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Chat-feature matrix + cross-cutting coverage for
 * [LiteRtAcceleratorResolver]. Complements [LiteRtAcceleratorResolverTest]
 * (embeddings + OCR happy paths) with the chat branches from PR #99
 * plus the gap categories listed in the Phase 5 test-coverage audit:
 *
 *  - chat default → `GPU-then-CPU` decision chain;
 *  - chat explicit `CPU` / `GPU` requests;
 *  - chat `NPU` request — allowlisted SoC w/ QNN lib → NPU;
 *  - chat `NPU` request — allowlisted SoC w/o native lib → GPU fallback;
 *  - chat unknown requested backend → GPU fallback;
 *  - unsupported `featureName` rejection;
 *  - [LiteRtAcceleratorResolver.latestDecision] last-write-wins + case
 *    normalization;
 *  - concurrent `resolveBackend` calls produce a valid decision per call.
 */
class LiteRtAcceleratorResolverChatMatrixTest {

    @Before fun setUp() { LiteRtAcceleratorResolver.resetForTesting() }
    @After fun tearDown() { LiteRtAcceleratorResolver.resetForTesting() }

    // ── Chat branches ───────────────────────────────────────────────────

    @Test fun `chat with no requested backend defaults to GPU then CPU chain`() {
        val decision = LiteRtAcceleratorResolver.resolveBackend(requested = null, featureName = "chat")
        assertEquals("GPU", decision.backend)
        assertEquals("DEFAULT_GPU_THEN_CPU_CHAIN", decision.reason)
        assertEquals(listOf("GPU" to "selected"), decision.attempted)
    }

    @Test fun `chat explicit CPU is honoured verbatim`() {
        val decision = LiteRtAcceleratorResolver.resolveBackend("CPU", "chat")
        assertEquals("CPU", decision.backend)
        assertEquals("REQUESTED_CPU", decision.reason)
    }

    @Test fun `chat explicit GPU is honoured verbatim`() {
        val decision = LiteRtAcceleratorResolver.resolveBackend("GPU", "chat")
        assertEquals("GPU", decision.backend)
        assertEquals("REQUESTED_GPU", decision.reason)
    }

    @Test fun `chat lowercase backend label normalises to uppercase`() {
        val decision = LiteRtAcceleratorResolver.resolveBackend("gpu", "chat")
        // normalizedBackend() uppercases → matches the GPU branch.
        assertEquals("GPU", decision.backend)
        assertEquals("REQUESTED_GPU", decision.reason)
    }

    @Test fun `chat NPU on allowlisted SoC with QNN lib selects NPU`() {
        LiteRtAcceleratorResolver.setEnvironmentForTesting(
            apiLevel = 33,
            socModel = "sm8550",
            libs = listOf("libQnnHtp.so", "libLiteRtQnnAccelerator.so"),
        )
        val decision = LiteRtAcceleratorResolver.resolveBackend("NPU", "chat")
        assertEquals("NPU", decision.backend)
        assertEquals("REQUESTED_NPU_SUPPORTED", decision.reason)
        assertEquals("NPU" to "NPU_SUPPORTED", decision.attempted.last())
    }

    @Test fun `chat NPU on allowlisted SoC without QNN lib falls back to GPU not CPU`() {
        // KEY chat-vs-embedding distinction: embeddings falls back to CPU
        // when NPU is unsupported. Chat falls back to GPU (the resolver's
        // documented chain) because the LiteRT-LM GPU delegate is
        // production-validated for chat workloads.
        LiteRtAcceleratorResolver.setEnvironmentForTesting(
            apiLevel = 33,
            socModel = "sm8550",
            libs = listOf("libsomething-unrelated.so"),
        )
        val decision = LiteRtAcceleratorResolver.resolveBackend("NPU", "chat")
        assertEquals("GPU", decision.backend)
        assertTrue(
            "reason must surface the NPU downgrade detail, was ${decision.reason}",
            decision.reason.startsWith("REQUESTED_NPU_UNSUPPORTED_GPU_FALLBACK_"),
        )
        assertEquals("GPU" to "selected", decision.attempted.last())
    }

    @Test fun `chat NPU on API below 31 falls back to GPU`() {
        LiteRtAcceleratorResolver.setEnvironmentForTesting(
            apiLevel = 30,
            socModel = "sm8550",
            libs = listOf("libQnnHtp.so"),
        )
        val decision = LiteRtAcceleratorResolver.resolveBackend("NPU", "chat")
        assertEquals("GPU", decision.backend)
        assertTrue(
            "reason must reference NPU_API_BELOW_31, was ${decision.reason}",
            decision.reason.contains("NPU_API_BELOW_31"),
        )
    }

    @Test fun `chat unknown requested backend defaults to GPU fallback`() {
        val decision = LiteRtAcceleratorResolver.resolveBackend("TPU", "chat")
        assertEquals("GPU", decision.backend)
        assertEquals("UNKNOWN_REQUESTED_BACKEND_GPU_FALLBACK", decision.reason)
    }

    // ── Feature name rejection ──────────────────────────────────────────

    @Test fun `unsupported featureName is rejected with require`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            LiteRtAcceleratorResolver.resolveBackend(null, "image-classification")
        }
        assertTrue(
            "exception must surface the offending name, was ${ex.message}",
            ex.message?.contains("image-classification") == true,
        )
    }

    @Test fun `featureName lookup is case insensitive on the input`() {
        // The resolver lowercases featureName before the SUPPORTED_FEATURES
        // membership check, so all of these resolve to the same branch.
        val a = LiteRtAcceleratorResolver.resolveBackend(null, "CHAT")
        val b = LiteRtAcceleratorResolver.resolveBackend(null, "Chat")
        val c = LiteRtAcceleratorResolver.resolveBackend(null, "chat")
        assertEquals(a.backend, b.backend)
        assertEquals(b.backend, c.backend)
        assertEquals("GPU", a.backend)
    }

    // ── latestDecision semantics ────────────────────────────────────────

    @Test fun `latestDecision returns null before any resolve call`() {
        assertNull(LiteRtAcceleratorResolver.latestDecision("chat"))
        assertNull(LiteRtAcceleratorResolver.latestDecision("embeddings"))
        assertNull(LiteRtAcceleratorResolver.latestDecision("ocr"))
    }

    @Test fun `latestDecision per feature reflects the most recent call`() {
        LiteRtAcceleratorResolver.resolveBackend("GPU", "chat")
        LiteRtAcceleratorResolver.resolveBackend("CPU", "chat")
        val latest = LiteRtAcceleratorResolver.latestDecision("chat")
        assertNotNull(latest)
        assertEquals("CPU", latest!!.backend)
        assertEquals("REQUESTED_CPU", latest.reason)
    }

    @Test fun `latestDecision keys are case insensitive`() {
        LiteRtAcceleratorResolver.resolveBackend("GPU", "chat")
        // Lookup with uppercase / mixed case must hit the same record.
        assertNotNull(LiteRtAcceleratorResolver.latestDecision("CHAT"))
        assertNotNull(LiteRtAcceleratorResolver.latestDecision("Chat"))
        assertEquals(
            "GPU",
            LiteRtAcceleratorResolver.latestDecision("ChAt")!!.backend,
        )
    }

    @Test fun `latestDecision is recorded independently per feature`() {
        LiteRtAcceleratorResolver.resolveBackend("CPU", "chat")
        LiteRtAcceleratorResolver.resolveBackend("GPU", "embeddings")
        assertEquals("CPU", LiteRtAcceleratorResolver.latestDecision("chat")!!.backend)
        assertEquals("GPU", LiteRtAcceleratorResolver.latestDecision("embeddings")!!.backend)
        // OCR still null — it was never asked.
        assertNull(LiteRtAcceleratorResolver.latestDecision("ocr"))
    }

    // ── Concurrency ─────────────────────────────────────────────────────

    @Test fun `resolveBackend tolerates concurrent calls without corruption`() = runBlocking {
        // Hammer the resolver from a real dispatcher (Dispatchers.Default)
        // with mixed feature+intent combinations. The contract under test
        // is: every call returns a non-null decision with a valid backend
        // string in {CPU, GPU, NPU}, and the internal state never throws.
        LiteRtAcceleratorResolver.setEnvironmentForTesting(
            apiLevel = 33,
            socModel = "sm8650",
            libs = listOf("libLiteRtQnnAccelerator.so"),
        )

        val features = arrayOf("chat", "embeddings", "ocr")
        val intents = arrayOf<String?>("CPU", "GPU", "NPU", null)

        val results = withContext(Dispatchers.Default) {
            (0 until 200).map { i ->
                async {
                    val feature = features[i % features.size]
                    val intent = intents[(i / 3) % intents.size]
                    LiteRtAcceleratorResolver.resolveBackend(
                        requested = intent,
                        featureName = feature,
                        nativeLibraryDir = null,
                    )
                }
            }.awaitAll()
        }

        val allowedBackends = setOf("CPU", "GPU", "NPU")
        assertEquals(200, results.size)
        for (decision in results) {
            assertTrue(
                "Backend must be one of CPU/GPU/NPU, was ${decision.backend}",
                decision.backend in allowedBackends,
            )
            assertTrue(
                "attempted chain must be non-empty, was ${decision.attempted}",
                decision.attempted.isNotEmpty(),
            )
        }
        // latestDecision is populated for every feature touched.
        for (feature in features) {
            assertNotNull("latestDecision missing for $feature",
                LiteRtAcceleratorResolver.latestDecision(feature))
        }
    }
}
