package com.adsamcik.mindlayer.service

import com.adsamcik.mindlayer.ModelReadinessItem
import com.adsamcik.mindlayer.service.engine.EngineState
import com.adsamcik.mindlayer.service.engine.InitFailure
import com.adsamcik.mindlayer.service.engine.PaddleOcrEngineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeModelReadinessTest {
    @Test
    fun `ready engines produce ready chat and OCR items`() {
        val snapshot = RuntimeModelReadiness.snapshot(
            chat = EngineState.Ready,
            ocr = PaddleOcrEngineState.Ready,
            nowEpochMs = 42L,
        )

        assertEquals(42L, snapshot.capturedAtEpochMs)
        assertTrue(snapshot.item(ModelReadinessItem.FAMILY_CHAT)!!.isReady)
        assertTrue(snapshot.item(ModelReadinessItem.FAMILY_OCR)!!.isReady)
    }

    @Test
    fun `missing model requests setup while initialization stays in progress`() {
        val snapshot = RuntimeModelReadiness.snapshot(
            chat = EngineState.Failed(InitFailure.ModelMissing),
            ocr = PaddleOcrEngineState.Initializing,
        )

        val chat = snapshot.item(ModelReadinessItem.FAMILY_CHAT)!!
        assertEquals(ModelReadinessItem.STATE_SETUP_REQUIRED, chat.state)
        assertEquals(ModelReadinessItem.REASON_MODEL_MISSING, chat.reasonCode)
        assertEquals(
            ModelReadinessItem.STATE_IN_PROGRESS,
            snapshot.item(ModelReadinessItem.FAMILY_OCR)!!.state,
        )
    }

    @Test
    fun `runtime failures remain failures with a useful reason`() {
        val snapshot = RuntimeModelReadiness.snapshot(
            chat = EngineState.Failed(InitFailure.LowMemory),
            ocr = PaddleOcrEngineState.Failed(InitFailure.IntegrityMismatch),
        )

        assertEquals(
            ModelReadinessItem.REASON_LOW_MEMORY,
            snapshot.item(ModelReadinessItem.FAMILY_CHAT)!!.reasonCode,
        )
        assertEquals(
            ModelReadinessItem.REASON_INTEGRITY_MISMATCH,
            snapshot.item(ModelReadinessItem.FAMILY_OCR)!!.reasonCode,
        )
    }
}
