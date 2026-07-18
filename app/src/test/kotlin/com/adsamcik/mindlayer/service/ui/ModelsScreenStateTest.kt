package com.adsamcik.mindlayer.service.ui

import com.adsamcik.mindlayer.service.engine.InitFailure
import com.adsamcik.mindlayer.service.modeldelivery.ModelDeliveryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pinning tests for [DashboardUiState.modelSummaries], the pure derivation
 * backing the Models tab. The function must stay Compose-free so the table
 * can be exercised without spinning up a Robolectric / Compose runtime.
 *
 * Each test covers one of the documented LOADED / IDLE / UNKNOWN / FAILED
 * branches across the three roles.
 */
class ModelsScreenStateTest {

    @Test
    fun `chat role is LOADED when engine is loaded with backend and model id`() {
        val state = DashboardUiState(
            isEngineLoaded = true,
            backend = "GPU",
            modelId = "path/to/gemma",
        )
        val chat = state.modelSummaries().single { it.role == ModelRole.CHAT_AND_VISION }
        assertEquals(ModelLoadState.LOADED, chat.state)
        assertEquals("GPU", chat.backend)
        // modelDisplayName trims to the leaf segment, matching the existing helper.
        assertEquals("gemma", chat.modelDisplayName)
        assertNull(chat.failureDetail)
    }

    @Test
    fun `loaded chat is not marked failed by a stale optional-engine failure row`() {
        val state = DashboardUiState(
            isEngineLoaded = true,
            backend = "CPU",
            modelId = "gemma",
            lastInitFailure = InitFailure.ModelMissing,
        )

        val chat = state.modelSummaries().single { it.role == ModelRole.CHAT_AND_VISION }

        assertEquals(ModelLoadState.LOADED, chat.state)
        assertNull(chat.failureDetail)
    }

    @Test
    fun `chat role is FAILED when init failure recorded`() {
        val state = DashboardUiState(
            isEngineLoaded = false,
            backend = "NONE",
            modelId = "",
            lastInitFailure = InitFailure.ModelMissing,
        )
        val chat = state.modelSummaries().single { it.role == ModelRole.CHAT_AND_VISION }
        assertEquals(ModelLoadState.FAILED, chat.state)
        // No live backend → backend field is null so the card doesn't render a chip.
        assertNull(chat.backend)
        assertNotNull(chat.failureDetail)
        assertTrue(
            "failureDetail should mention the Models tab remediation, was: ${chat.failureDetail}",
            chat.failureDetail!!.contains("Models", ignoreCase = true),
        )
    }

    @Test
    fun `embeddings role is UNKNOWN with default test state`() {
        val state = DashboardUiState()
        val embeddings = state.modelSummaries().single { it.role == ModelRole.EMBEDDINGS }
        assertEquals(ModelLoadState.UNKNOWN, embeddings.state)
        assertNull(embeddings.backend)
        assertNull(embeddings.initTimeSeconds)
    }

    @Test
    fun `embeddings role is LOADED after a successful completed test`() {
        val state = DashboardUiState(
            embeddingTest = EngineTestState(
                isRunning = false,
                status = "ok",
                tone = DashboardMessageTone.SUCCESS,
                lastCompletedAtMs = 123L,
            ),
        )
        val embeddings = state.modelSummaries().single { it.role == ModelRole.EMBEDDINGS }
        assertEquals(ModelLoadState.LOADED, embeddings.state)
    }

    @Test
    fun `ocr role is FAILED when ocr test tone is ERROR`() {
        val state = DashboardUiState(
            ocrTest = EngineTestState(
                isRunning = false,
                status = "OCR engine init failed",
                tone = DashboardMessageTone.ERROR,
                lastCompletedAtMs = 99L,
            ),
        )
        val ocr = state.modelSummaries().single { it.role == ModelRole.OCR }
        assertEquals(ModelLoadState.FAILED, ocr.state)
        assertEquals("OCR engine init failed", ocr.failureDetail)
    }

    @Test
    fun `delivery state is separate from runtime load and exposes the right primary action`() {
        val state = DashboardUiState(
            isEngineLoaded = true,
            modelDelivery = mapOf(
                ModelRole.CHAT_AND_VISION to ModelDeliveryState.NotInstalled,
                ModelRole.EMBEDDINGS to ModelDeliveryState.RequiresConfirmation,
                ModelRole.OCR to ModelDeliveryState.Installed,
            ),
        )

        val summaries = state.modelSummaries()
        assertEquals(ModelLoadState.LOADED, summaries.single { it.role == ModelRole.CHAT_AND_VISION }.state)
        assertEquals(ModelDeliveryAction.DOWNLOAD, modelDeliveryAction(ModelDeliveryState.NotInstalled))
        assertEquals(ModelDeliveryAction.CONFIRM, modelDeliveryAction(ModelDeliveryState.RequiresConfirmation))
        assertEquals(ModelDeliveryAction.REMOVE, modelDeliveryAction(ModelDeliveryState.Installed))
        assertEquals(
            ModelDeliveryAction.RETRY_ACTIVATION,
            modelDeliveryAction(ModelDeliveryState.InstalledWithActivationError),
        )
        assertEquals(ModelDeliveryAction.NONE, modelDeliveryAction(ModelDeliveryState.Activating))
        assertEquals(
            25,
            ModelDeliveryState.Downloading(downloadedBytes = 25L, totalBytes = 100L).progressPercent,
        )
    }
}
