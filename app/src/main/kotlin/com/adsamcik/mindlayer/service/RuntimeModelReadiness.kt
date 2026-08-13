package com.adsamcik.mindlayer.service

import com.adsamcik.mindlayer.ModelReadinessItem
import com.adsamcik.mindlayer.ModelReadinessSnapshot
import com.adsamcik.mindlayer.service.engine.EngineState
import com.adsamcik.mindlayer.service.engine.InitFailure
import com.adsamcik.mindlayer.service.engine.PaddleOcrEngineState

/** Maps existing runtime engine states to the small external readiness contract. */
internal object RuntimeModelReadiness {
    fun snapshot(
        chat: EngineState,
        ocr: PaddleOcrEngineState?,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): ModelReadinessSnapshot = ModelReadinessSnapshot(
        capturedAtEpochMs = nowEpochMs,
        items = listOf(
            item(ModelReadinessItem.FAMILY_CHAT, chat),
            item(ModelReadinessItem.FAMILY_OCR, ocr),
        ),
    )

    private fun item(family: String, state: EngineState): ModelReadinessItem = when (state) {
        EngineState.Ready -> ready(family)
        EngineState.Idle, EngineState.Initializing -> inProgress(family)
        is EngineState.Failed -> failed(family, state.cause)
    }

    private fun item(family: String, state: PaddleOcrEngineState?): ModelReadinessItem = when (state) {
        null -> ModelReadinessItem(family = family, state = ModelReadinessItem.STATE_UNSUPPORTED)
        PaddleOcrEngineState.Ready -> ready(family)
        PaddleOcrEngineState.Idle, PaddleOcrEngineState.Initializing -> inProgress(family)
        is PaddleOcrEngineState.Failed -> failed(family, state.cause)
    }

    private fun ready(family: String) = ModelReadinessItem(
        family = family,
        state = ModelReadinessItem.STATE_READY,
    )

    private fun inProgress(family: String) = ModelReadinessItem(
        family = family,
        state = ModelReadinessItem.STATE_IN_PROGRESS,
    )

    private fun failed(family: String, failure: InitFailure): ModelReadinessItem {
        val (state, reason) = when (failure) {
            InitFailure.ModelMissing ->
                ModelReadinessItem.STATE_SETUP_REQUIRED to ModelReadinessItem.REASON_MODEL_MISSING
            InitFailure.LowMemory ->
                ModelReadinessItem.STATE_FAILED to ModelReadinessItem.REASON_LOW_MEMORY
            InitFailure.IntegrityMismatch ->
                ModelReadinessItem.STATE_FAILED to ModelReadinessItem.REASON_INTEGRITY_MISMATCH
            is InitFailure.BackendUnavailable ->
                ModelReadinessItem.STATE_FAILED to ModelReadinessItem.REASON_BACKEND_UNAVAILABLE
            is InitFailure.NativeError ->
                ModelReadinessItem.STATE_FAILED to ModelReadinessItem.REASON_NATIVE_ERROR
        }
        return ModelReadinessItem(family = family, state = state, reasonCode = reason)
    }
}
