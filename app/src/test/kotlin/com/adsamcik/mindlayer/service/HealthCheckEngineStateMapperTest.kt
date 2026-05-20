package com.adsamcik.mindlayer.service

import com.adsamcik.mindlayer.HealthCheck
import com.adsamcik.mindlayer.service.engine.EmbeddingEngineState
import com.adsamcik.mindlayer.service.engine.EngineState
import com.adsamcik.mindlayer.service.engine.InitFailure
import com.adsamcik.mindlayer.service.engine.PaddleOcrEngineState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for [HealthCheckEngineStateMapper] — Phase 3 #8.
 *
 * Pins the four state-name -> wire-int mappings for each of the
 * three sealed-class state holders ([EngineState],
 * [EmbeddingEngineState], [PaddleOcrEngineState]).
 *
 * Cannot use MockK against any of them — they are all `final class`
 * with `final val` properties, and MockK on the JVM cannot intercept
 * those. The mapper takes `Any?` and reads `state::class.java.simpleName`
 * so the real sealed-object instances work directly without any mocking.
 */
class HealthCheckEngineStateMapperTest {

    @Test fun `null maps to IDLE`() {
        assertEquals(HealthCheck.ENGINE_STATE_IDLE, HealthCheckEngineStateMapper.map(null))
    }

    @Test fun `EngineState Idle maps to IDLE`() {
        assertEquals(HealthCheck.ENGINE_STATE_IDLE, HealthCheckEngineStateMapper.map(EngineState.Idle))
    }

    @Test fun `EngineState Initializing maps to INITIALIZING`() {
        assertEquals(
            HealthCheck.ENGINE_STATE_INITIALIZING,
            HealthCheckEngineStateMapper.map(EngineState.Initializing),
        )
    }

    @Test fun `EngineState Ready maps to READY`() {
        assertEquals(HealthCheck.ENGINE_STATE_READY, HealthCheckEngineStateMapper.map(EngineState.Ready))
    }

    @Test fun `EngineState Failed maps to FAILED`() {
        val state = EngineState.Failed(cause = InitFailure.ModelMissing)
        assertEquals(HealthCheck.ENGINE_STATE_FAILED, HealthCheckEngineStateMapper.map(state))
    }

    @Test fun `EmbeddingEngineState Idle maps to IDLE`() {
        assertEquals(
            HealthCheck.ENGINE_STATE_IDLE,
            HealthCheckEngineStateMapper.map(EmbeddingEngineState.Idle),
        )
    }

    @Test fun `EmbeddingEngineState Initializing maps to INITIALIZING`() {
        assertEquals(
            HealthCheck.ENGINE_STATE_INITIALIZING,
            HealthCheckEngineStateMapper.map(EmbeddingEngineState.Initializing),
        )
    }

    @Test fun `EmbeddingEngineState Ready maps to READY`() {
        assertEquals(
            HealthCheck.ENGINE_STATE_READY,
            HealthCheckEngineStateMapper.map(EmbeddingEngineState.Ready),
        )
    }

    @Test fun `EmbeddingEngineState Failed maps to FAILED`() {
        val state = EmbeddingEngineState.Failed(cause = InitFailure.ModelMissing)
        assertEquals(HealthCheck.ENGINE_STATE_FAILED, HealthCheckEngineStateMapper.map(state))
    }

    @Test fun `PaddleOcrEngineState Idle maps to IDLE`() {
        assertEquals(
            HealthCheck.ENGINE_STATE_IDLE,
            HealthCheckEngineStateMapper.map(PaddleOcrEngineState.Idle),
        )
    }

    @Test fun `PaddleOcrEngineState Initializing maps to INITIALIZING`() {
        assertEquals(
            HealthCheck.ENGINE_STATE_INITIALIZING,
            HealthCheckEngineStateMapper.map(PaddleOcrEngineState.Initializing),
        )
    }

    @Test fun `PaddleOcrEngineState Ready maps to READY`() {
        assertEquals(
            HealthCheck.ENGINE_STATE_READY,
            HealthCheckEngineStateMapper.map(PaddleOcrEngineState.Ready),
        )
    }

    @Test fun `PaddleOcrEngineState Failed maps to FAILED`() {
        val state = PaddleOcrEngineState.Failed(cause = InitFailure.ModelMissing)
        assertEquals(HealthCheck.ENGINE_STATE_FAILED, HealthCheckEngineStateMapper.map(state))
    }

    @Test fun `unknown subtype maps to IDLE`() {
        // Pass an arbitrary Any whose class name does not match any of
        // the four known sealed-class subtypes. The mapper falls back
        // to IDLE (conservative).
        val unrelated = Any()
        assertEquals(HealthCheck.ENGINE_STATE_IDLE, HealthCheckEngineStateMapper.map(unrelated))
    }
}
