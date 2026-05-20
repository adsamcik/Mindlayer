package com.adsamcik.mindlayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the [HealthCheck] parcelable's data-shape
 * invariants — Phase 3 #8.
 *
 * (Parcel-binary round-trip is exercised at the :app layer, where
 * Robolectric is on the test classpath — see
 * `ServiceBinderPingTest`.)
 *
 * Verifies:
 *  - `allEnginesReady` / `anyEngineReady` derived flags reflect the
 *    state-integer enum correctly.
 *  - Wire-stable constants (schemaVersion = 1, ENGINE_STATE_* values).
 *  - `toString` is redaction-friendly (no extension payload leak).
 *  - Default values for engine state fields are IDLE.
 */
class HealthCheckTest {

    @Test fun `wire-stable constants are pinned`() {
        assertEquals(1, HealthCheck.CURRENT_SCHEMA_VERSION)
        assertEquals(0, HealthCheck.ENGINE_STATE_IDLE)
        assertEquals(1, HealthCheck.ENGINE_STATE_INITIALIZING)
        assertEquals(2, HealthCheck.ENGINE_STATE_READY)
        assertEquals(3, HealthCheck.ENGINE_STATE_FAILED)
    }

    @Test fun `allEnginesReady requires every engine to be READY`() {
        val allReady = HealthCheck(
            serverTimestampMs = 1_000L,
            serviceUptimeMs = 500L,
            apiVersion = 5,
            llmEngineState = HealthCheck.ENGINE_STATE_READY,
            embeddingEngineState = HealthCheck.ENGINE_STATE_READY,
            ocrEngineState = HealthCheck.ENGINE_STATE_READY,
        )
        assertTrue(allReady.allEnginesReady)

        val oneNotReady = allReady.copy(ocrEngineState = HealthCheck.ENGINE_STATE_INITIALIZING)
        assertFalse(oneNotReady.allEnginesReady)
    }

    @Test fun `anyEngineReady requires at least one engine to be READY`() {
        val noneReady = HealthCheck(
            serverTimestampMs = 1L,
            serviceUptimeMs = 0L,
            apiVersion = 1,
            llmEngineState = HealthCheck.ENGINE_STATE_IDLE,
            embeddingEngineState = HealthCheck.ENGINE_STATE_INITIALIZING,
            ocrEngineState = HealthCheck.ENGINE_STATE_FAILED,
        )
        assertFalse(noneReady.anyEngineReady)
        assertFalse(noneReady.allEnginesReady)

        val justEmbeddingReady = noneReady.copy(embeddingEngineState = HealthCheck.ENGINE_STATE_READY)
        assertTrue(justEmbeddingReady.anyEngineReady)
        assertFalse(justEmbeddingReady.allEnginesReady)
    }

    @Test fun `default values are IDLE for engine state fields`() {
        val healthCheck = HealthCheck(
            serverTimestampMs = 1L,
            serviceUptimeMs = 1L,
            apiVersion = 1,
        )
        assertEquals(HealthCheck.ENGINE_STATE_IDLE, healthCheck.llmEngineState)
        assertEquals(HealthCheck.ENGINE_STATE_IDLE, healthCheck.embeddingEngineState)
        assertEquals(HealthCheck.ENGINE_STATE_IDLE, healthCheck.ocrEngineState)
        assertFalse(healthCheck.anyEngineReady)
    }

    @Test fun `default extensionsJson is null`() {
        val healthCheck = HealthCheck(
            serverTimestampMs = 1L,
            serviceUptimeMs = 1L,
            apiVersion = 1,
        )
        assertNull(healthCheck.extensionsJson)
    }

    @Test fun `default schemaVersion matches CURRENT_SCHEMA_VERSION`() {
        val healthCheck = HealthCheck(
            serverTimestampMs = 1L,
            serviceUptimeMs = 1L,
            apiVersion = 1,
        )
        assertEquals(HealthCheck.CURRENT_SCHEMA_VERSION, healthCheck.schemaVersion)
    }

    @Test fun `toString does not leak extensionsJson body`() {
        val healthCheck = HealthCheck(
            serverTimestampMs = 1L,
            serviceUptimeMs = 1L,
            apiVersion = 1,
            extensionsJson = """{"secret":"PII"}""",
        )
        val s = healthCheck.toString()
        assertFalse("toString must not leak extensionsJson body: $s", s.contains("PII"))
        assertTrue("toString should still expose api version", s.contains("api=1"))
        assertTrue("toString should expose allReady summary", s.contains("allReady="))
    }

    @Test fun `toString surfaces every engine state field`() {
        val healthCheck = HealthCheck(
            serverTimestampMs = 1L,
            serviceUptimeMs = 1L,
            apiVersion = 1,
            llmEngineState = HealthCheck.ENGINE_STATE_READY,
            embeddingEngineState = HealthCheck.ENGINE_STATE_INITIALIZING,
            ocrEngineState = HealthCheck.ENGINE_STATE_FAILED,
        )
        val s = healthCheck.toString()
        assertTrue(s.contains("llm=2"))
        assertTrue(s.contains("emb=1"))
        assertTrue(s.contains("ocr=3"))
    }
}
