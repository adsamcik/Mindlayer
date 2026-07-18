package com.adsamcik.mindlayer.service.ui

import com.adsamcik.mindlayer.service.engine.InitFailure
import com.adsamcik.mindlayer.service.modeldelivery.ModelDeliveryIssue
import com.adsamcik.mindlayer.service.modeldelivery.ModelDeliveryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelsScreenStateTest {

    @Test
    fun `chat role is READY only from live loaded runtime evidence`() {
        val nowMs = 20_000L
        val state = DashboardUiState(
            connectionState = DashboardConnectionState.CONNECTED,
            isStatusLoading = false,
            lastStatusUpdateMs = nowMs - 1_000L,
            isEngineLoaded = true,
            backend = "GPU",
            modelId = "path/to/gemma",
            modelDelivery = mapOf(
                ModelRole.CHAT_AND_VISION to ModelDeliveryState.Installed,
            ),
        )

        val chat = state.modelSummaries(nowMs).single { it.role == ModelRole.CHAT_AND_VISION }

        assertEquals(ModelLoadState.READY, chat.state)
        assertEquals(ModelRuntimeEvidence.LIVE_SERVICE, chat.evidence)
        assertEquals(ModelReadiness.READY, chat.readiness)
        assertEquals("GPU", chat.backend)
        assertEquals("gemma", chat.modelDisplayName)
        assertNull(chat.runtimeIssue)
    }

    @Test
    fun `loaded chat is not marked failed by a stale optional-engine failure row`() {
        val nowMs = 20_000L
        val state = DashboardUiState(
            connectionState = DashboardConnectionState.CONNECTED,
            isStatusLoading = false,
            lastStatusUpdateMs = nowMs - 1_000L,
            isEngineLoaded = true,
            backend = "CPU",
            modelId = "gemma",
            lastInitFailure = InitFailure.ModelMissing,
            modelDelivery = mapOf(
                ModelRole.CHAT_AND_VISION to ModelDeliveryState.Installed,
            ),
        )

        val chat = state.modelSummaries(nowMs).single { it.role == ModelRole.CHAT_AND_VISION }

        assertEquals(ModelLoadState.READY, chat.state)
        assertNull(chat.runtimeIssue)
    }

    @Test
    fun `fresh chat sample requires an active backend before claiming ready`() {
        val nowMs = 20_000L
        val chat = DashboardUiState(
            connectionState = DashboardConnectionState.CONNECTED,
            isStatusLoading = false,
            lastStatusUpdateMs = nowMs - 1_000L,
            isEngineLoaded = true,
            backend = "NONE",
            modelId = "gemma",
            modelDelivery = mapOf(
                ModelRole.CHAT_AND_VISION to ModelDeliveryState.Installed,
            ),
        ).modelSummaries(nowMs).single { it.role == ModelRole.CHAT_AND_VISION }

        assertEquals(ModelRuntimeEvidence.LIVE_SERVICE, chat.evidence)
        assertEquals(ModelLoadState.IDLE, chat.state)
        assertEquals(ModelReadiness.DOWNLOADED_IDLE, chat.readiness)
        assertNull(chat.backend)
    }

    @Test
    fun `chat role exposes typed init failure without user-facing failure copy`() {
        val nowMs = 20_000L
        val state = DashboardUiState(
            connectionState = DashboardConnectionState.CONNECTED,
            isStatusLoading = false,
            lastStatusUpdateMs = nowMs - 1_000L,
            isEngineLoaded = false,
            backend = "NONE",
            modelId = "",
            lastInitFailure = InitFailure.ModelMissing,
            modelDelivery = mapOf(
                ModelRole.CHAT_AND_VISION to ModelDeliveryState.Installed,
            ),
        )

        val chat = state.modelSummaries(nowMs).single { it.role == ModelRole.CHAT_AND_VISION }

        assertEquals(ModelLoadState.FAILED, chat.state)
        assertEquals(ModelRuntimeIssue.InitializationFailed(InitFailure.ModelMissing), chat.runtimeIssue)
        assertEquals(ModelReadiness.NEEDS_ATTENTION, chat.readiness)
        assertNull(chat.backend)
    }

    @Test
    fun `chat role never treats unavailable or stale status as current ready evidence`() {
        val nowMs = 20_000L
        val cases = listOf(
            DashboardUiState(
                connectionState = DashboardConnectionState.DISCONNECTED,
                isStatusLoading = false,
                lastStatusUpdateMs = nowMs - 1_000L,
            ) to ModelRuntimeEvidence.LAST_KNOWN_SERVICE,
            DashboardUiState(
                connectionState = DashboardConnectionState.CONNECTING,
                isStatusLoading = false,
                lastStatusUpdateMs = nowMs - 1_000L,
            ) to ModelRuntimeEvidence.LAST_KNOWN_SERVICE,
            DashboardUiState(
                connectionState = DashboardConnectionState.CONNECTED,
                isStatusLoading = true,
                lastStatusUpdateMs = nowMs - 1_000L,
            ) to ModelRuntimeEvidence.LAST_KNOWN_SERVICE,
            DashboardUiState(
                connectionState = DashboardConnectionState.CONNECTED,
                isStatusLoading = false,
                lastStatusUpdateMs = nowMs - 1_000L,
                statusErrorMessage = "typed polling failure",
            ) to ModelRuntimeEvidence.LAST_KNOWN_SERVICE,
            DashboardUiState(
                connectionState = DashboardConnectionState.CONNECTED,
                isStatusLoading = false,
            ) to ModelRuntimeEvidence.SERVICE_STATUS_UNAVAILABLE,
            DashboardUiState(
                connectionState = DashboardConnectionState.CONNECTED,
                isStatusLoading = false,
                lastStatusUpdateMs = nowMs - 7_000L,
            ) to ModelRuntimeEvidence.LAST_KNOWN_SERVICE,
        )

        cases.forEach { (baseState, expectedEvidence) ->
            val chat = baseState.copy(
                isEngineLoaded = true,
                backend = "GPU",
                modelId = "path/to/gemma",
                modelDelivery = mapOf(
                    ModelRole.CHAT_AND_VISION to ModelDeliveryState.Installed,
                ),
            ).modelSummaries(nowMs).single { it.role == ModelRole.CHAT_AND_VISION }

            assertEquals(expectedEvidence, chat.evidence)
            assertEquals(
                ModelLoadState.READY.takeIf {
                    expectedEvidence == ModelRuntimeEvidence.LAST_KNOWN_SERVICE
                } ?: ModelLoadState.NOT_VERIFIED,
                chat.state,
            )
            assertEquals(ModelReadiness.DOWNLOADED_IDLE, chat.readiness)
            assertEquals("GPU", chat.backend)
            assertEquals("gemma", chat.modelDisplayName)
            assertEquals(
                baseState.lastStatusUpdateMs,
                chat.lastRuntimeStatusAtMs,
            )
        }
    }

    @Test
    fun `embeddings role is NOT_VERIFIED before any dashboard verification`() {
        val embeddings = DashboardUiState().modelSummaries()
            .single { it.role == ModelRole.EMBEDDINGS }

        assertEquals(ModelLoadState.NOT_VERIFIED, embeddings.state)
        assertEquals(ModelRuntimeEvidence.DASHBOARD_VERIFICATION, embeddings.evidence)
        assertNull(embeddings.lastVerificationPassed)
        assertNull(embeddings.backend)
        assertNull(embeddings.initTimeSeconds)
    }

    @Test
    fun `historical embeddings success remains downloaded idle rather than live ready`() {
        val state = DashboardUiState(
            embeddingTest = EngineTestState(
                isRunning = false,
                status = "ok",
                tone = DashboardMessageTone.SUCCESS,
                lastCompletedAtMs = 123L,
            ),
            modelDelivery = mapOf(
                ModelRole.EMBEDDINGS to ModelDeliveryState.Installed,
            ),
        )

        val embeddings = state.modelSummaries().single { it.role == ModelRole.EMBEDDINGS }

        assertEquals(ModelLoadState.IDLE, embeddings.state)
        assertEquals(ModelRuntimeEvidence.DASHBOARD_VERIFICATION, embeddings.evidence)
        assertEquals(true, embeddings.lastVerificationPassed)
        assertEquals(ModelReadiness.DOWNLOADED_IDLE, embeddings.readiness)
    }

    @Test
    fun `running ocr verification maps to STARTING`() {
        val state = DashboardUiState(
            ocrTest = EngineTestState(isRunning = true),
            modelDelivery = mapOf(
                ModelRole.OCR to ModelDeliveryState.Installed,
            ),
        )

        val ocr = state.modelSummaries().single { it.role == ModelRole.OCR }

        assertEquals(ModelLoadState.STARTING, ocr.state)
        assertEquals(ModelReadiness.STARTING, ocr.readiness)
        assertNull(ocr.lastVerificationPassed)
    }

    @Test
    fun `failed ocr verification exposes typed issue`() {
        val state = DashboardUiState(
            ocrTest = EngineTestState(
                status = "raw test detail must not enter the model summary",
                tone = DashboardMessageTone.ERROR,
                lastCompletedAtMs = 99L,
            ),
            modelDelivery = mapOf(
                ModelRole.OCR to ModelDeliveryState.Installed,
            ),
        )

        val ocr = state.modelSummaries().single { it.role == ModelRole.OCR }

        assertEquals(ModelLoadState.FAILED, ocr.state)
        assertEquals(ModelRuntimeIssue.VerificationFailed, ocr.runtimeIssue)
        assertEquals(false, ocr.lastVerificationPassed)
        assertEquals(ModelReadiness.NEEDS_ATTENTION, ocr.readiness)
    }

    @Test
    fun `delivery phase takes precedence in readiness summary`() {
        val cases = listOf(
            ModelDeliveryState.Checking to ModelReadiness.CHECKING,
            ModelDeliveryState.NotInstalled to ModelReadiness.DOWNLOAD_REQUIRED,
            ModelDeliveryState.Pending to ModelReadiness.WAITING,
            ModelDeliveryState.WaitingForWifi to ModelReadiness.WAITING,
            ModelDeliveryState.RequiresConfirmation to ModelReadiness.WAITING,
            ModelDeliveryState.Downloading(25L, 100L) to ModelReadiness.DOWNLOADING,
            ModelDeliveryState.Downloading(25L, 0L) to ModelReadiness.DOWNLOADING,
            ModelDeliveryState.Transferring to ModelReadiness.PREPARING,
            ModelDeliveryState.Provisioning to ModelReadiness.PREPARING,
            ModelDeliveryState.Activating to ModelReadiness.PREPARING,
            ModelDeliveryState.InstalledWithActivationError to ModelReadiness.NEEDS_ATTENTION,
            ModelDeliveryState.Removing to ModelReadiness.REMOVING,
            ModelDeliveryState.Quiescing to ModelReadiness.REMOVING,
            ModelDeliveryState.RemovalFailed(ModelDeliveryIssue.RemovalFailed) to
                ModelReadiness.NEEDS_ATTENTION,
            ModelDeliveryState.Failed(ModelDeliveryIssue.PlayDeliveryFailed) to
                ModelReadiness.NEEDS_ATTENTION,
            ModelDeliveryState.Unsupported to ModelReadiness.UNAVAILABLE,
        )

        cases.forEach { (delivery, expected) ->
            assertEquals(
                "delivery=$delivery",
                expected,
                modelReadiness(
                    role = ModelRole.CHAT_AND_VISION,
                    deliveryState = delivery,
                    runtimeState = ModelLoadState.READY,
                    evidence = ModelRuntimeEvidence.LIVE_SERVICE,
                ),
            )
        }
    }

    @Test
    fun `installed readiness reflects runtime state and evidence honestly`() {
        val cases = listOf(
            Triple(ModelLoadState.NOT_VERIFIED, ModelRuntimeEvidence.LIVE_SERVICE, ModelReadiness.DOWNLOADED_IDLE),
            Triple(ModelLoadState.IDLE, ModelRuntimeEvidence.LIVE_SERVICE, ModelReadiness.DOWNLOADED_IDLE),
            Triple(ModelLoadState.STARTING, ModelRuntimeEvidence.LIVE_SERVICE, ModelReadiness.STARTING),
            Triple(ModelLoadState.FAILED, ModelRuntimeEvidence.LIVE_SERVICE, ModelReadiness.NEEDS_ATTENTION),
            Triple(ModelLoadState.READY, ModelRuntimeEvidence.LIVE_SERVICE, ModelReadiness.READY),
            Triple(
                ModelLoadState.READY,
                ModelRuntimeEvidence.DASHBOARD_VERIFICATION,
                ModelReadiness.DOWNLOADED_IDLE,
            ),
        )

        cases.forEach { (runtime, evidence, expected) ->
            assertEquals(
                expected,
                modelReadiness(
                    role = ModelRole.CHAT_AND_VISION,
                    deliveryState = ModelDeliveryState.Installed,
                    runtimeState = runtime,
                    evidence = evidence,
                ),
            )
        }
        assertEquals(
            ModelReadiness.DOWNLOADED_IDLE,
            modelReadiness(
                role = ModelRole.EMBEDDINGS,
                deliveryState = ModelDeliveryState.Installed,
                runtimeState = ModelLoadState.READY,
                evidence = ModelRuntimeEvidence.LIVE_SERVICE,
            ),
        )
    }

    @Test
    fun `every delivery state maps to a precise primary action`() {
        val cases = listOf(
            ModelDeliveryState.Checking to ModelDeliveryAction.NONE,
            ModelDeliveryState.NotInstalled to ModelDeliveryAction.DOWNLOAD,
            ModelDeliveryState.Pending to ModelDeliveryAction.NONE,
            ModelDeliveryState.Downloading(0L, 0L) to ModelDeliveryAction.NONE,
            ModelDeliveryState.Transferring to ModelDeliveryAction.NONE,
            ModelDeliveryState.WaitingForWifi to ModelDeliveryAction.NONE,
            ModelDeliveryState.RequiresConfirmation to ModelDeliveryAction.CONFIRM,
            ModelDeliveryState.Provisioning to ModelDeliveryAction.NONE,
            ModelDeliveryState.Installed to ModelDeliveryAction.REMOVE,
            ModelDeliveryState.Activating to ModelDeliveryAction.NONE,
            ModelDeliveryState.InstalledWithActivationError to ModelDeliveryAction.RETRY_ACTIVATION,
            ModelDeliveryState.Removing to ModelDeliveryAction.NONE,
            ModelDeliveryState.Quiescing to ModelDeliveryAction.NONE,
            ModelDeliveryState.RemovalFailed(ModelDeliveryIssue.RemovalFailed) to
                ModelDeliveryAction.RETRY_REMOVE,
            ModelDeliveryState.Failed(ModelDeliveryIssue.PlayDeliveryFailed) to
                ModelDeliveryAction.RETRY_DOWNLOAD,
            ModelDeliveryState.Failed(ModelDeliveryIssue.ConfirmationUnavailable) to
                ModelDeliveryAction.CONFIRM,
            ModelDeliveryState.Unsupported to ModelDeliveryAction.NONE,
        )

        cases.forEach { (delivery, expected) ->
            assertEquals("delivery=$delivery", expected, modelDeliveryAction(delivery))
        }
        assertEquals(0, ModelDeliveryState.Downloading(25L, 0L).progressPercent)
        assertEquals(25, ModelDeliveryState.Downloading(25L, 100L).progressPercent)
    }

    @Test
    fun `overview prioritizes attention then model activity then downloaded count`() {
        val attention = modelOverview(
            listOf(
                summary(
                    deliveryState = ModelDeliveryState.Failed(ModelDeliveryIssue.PlayDeliveryFailed),
                    readiness = ModelReadiness.NEEDS_ATTENTION,
                ),
                summary(
                    role = ModelRole.EMBEDDINGS,
                    deliveryState = ModelDeliveryState.Downloading(20L, 100L),
                    readiness = ModelReadiness.DOWNLOADING,
                ),
                summary(
                    role = ModelRole.OCR,
                    deliveryState = ModelDeliveryState.Installed,
                    readiness = ModelReadiness.DOWNLOADED_IDLE,
                ),
            ),
        )
        assertEquals(ModelOverviewKind.NEEDS_ATTENTION, attention.kind)
        assertEquals(1, attention.affectedCount)

        val progress = modelOverview(
            listOf(
                summary(
                    deliveryState = ModelDeliveryState.Downloading(20L, 100L),
                    readiness = ModelReadiness.DOWNLOADING,
                ),
                summary(
                    role = ModelRole.EMBEDDINGS,
                    deliveryState = ModelDeliveryState.Installed,
                    readiness = ModelReadiness.DOWNLOADED_IDLE,
                ),
                summary(
                    role = ModelRole.OCR,
                    deliveryState = ModelDeliveryState.NotInstalled,
                    readiness = ModelReadiness.DOWNLOAD_REQUIRED,
                ),
            ),
        )
        assertEquals(ModelOverviewKind.ACTIVITY_IN_PROGRESS, progress.kind)
        assertEquals(1, progress.affectedCount)

        val partial = modelOverview(
            listOf(
                summary(
                    deliveryState = ModelDeliveryState.Installed,
                    readiness = ModelReadiness.DOWNLOADED_IDLE,
                ),
                summary(
                    role = ModelRole.EMBEDDINGS,
                    deliveryState = ModelDeliveryState.NotInstalled,
                    readiness = ModelReadiness.DOWNLOAD_REQUIRED,
                ),
                summary(
                    role = ModelRole.OCR,
                    deliveryState = ModelDeliveryState.NotInstalled,
                    readiness = ModelReadiness.DOWNLOAD_REQUIRED,
                ),
            ),
        )
        assertEquals(ModelOverviewKind.DOWNLOADED_COUNT, partial.kind)
        assertEquals(1, partial.downloadedCount)
        assertEquals(3, partial.totalCount)

        val available = modelOverview(
            ModelRole.entries.map { role ->
                summary(
                    role = role,
                    deliveryState = ModelDeliveryState.Installed,
                    readiness = ModelReadiness.DOWNLOADED_IDLE,
                )
            },
        )
        assertEquals(ModelOverviewKind.ALL_AVAILABLE, available.kind)
        assertEquals(3, available.downloadedCount)
    }

    @Test
    fun `readiness maps to stable semantic tones`() {
        val cases = mapOf(
            ModelReadiness.CHECKING to DashboardMessageTone.INFO,
            ModelReadiness.DOWNLOAD_REQUIRED to DashboardMessageTone.INFO,
            ModelReadiness.WAITING to DashboardMessageTone.WARNING,
            ModelReadiness.DOWNLOADING to DashboardMessageTone.INFO,
            ModelReadiness.PREPARING to DashboardMessageTone.INFO,
            ModelReadiness.DOWNLOADED_IDLE to DashboardMessageTone.SUCCESS,
            ModelReadiness.STARTING to DashboardMessageTone.INFO,
            ModelReadiness.READY to DashboardMessageTone.SUCCESS,
            ModelReadiness.NEEDS_ATTENTION to DashboardMessageTone.ERROR,
            ModelReadiness.REMOVING to DashboardMessageTone.INFO,
            ModelReadiness.UNAVAILABLE to DashboardMessageTone.WARNING,
        )

        cases.forEach { (readiness, expectedTone) ->
            assertEquals("readiness=$readiness", expectedTone, modelReadinessTone(readiness))
        }
    }

    @Test
    fun `download progress is determinate only when total bytes are known`() {
        assertEquals(
            ModelProgressPresentation(ModelProgressKind.DETERMINATE, 0.25f),
            modelProgressPresentation(
                summary(
                    deliveryState = ModelDeliveryState.Downloading(25L, 100L),
                    readiness = ModelReadiness.DOWNLOADING,
                ),
            ),
        )
        assertEquals(
            ModelProgressPresentation(ModelProgressKind.INDETERMINATE),
            modelProgressPresentation(
                summary(
                    deliveryState = ModelDeliveryState.Downloading(25L, 0L),
                    readiness = ModelReadiness.DOWNLOADING,
                ),
            ),
        )
        assertEquals(
            ModelProgressPresentation(ModelProgressKind.INDETERMINATE),
            modelProgressPresentation(
                summary(
                    deliveryState = ModelDeliveryState.Installed,
                    state = ModelLoadState.STARTING,
                    readiness = ModelReadiness.STARTING,
                ),
            ),
        )
        assertEquals(
            ModelProgressPresentation(ModelProgressKind.INDETERMINATE),
            modelProgressPresentation(
                summary(
                    deliveryState = ModelDeliveryState.Pending,
                    readiness = ModelReadiness.WAITING,
                ),
            ),
        )
        assertEquals(
            ModelProgressPresentation(ModelProgressKind.NONE),
            modelProgressPresentation(
                summary(
                    deliveryState = ModelDeliveryState.WaitingForWifi,
                    readiness = ModelReadiness.WAITING,
                ),
            ),
        )
    }

    @Test
    fun `delivery phases select specific presentations`() {
        val cases = listOf(
            summary(
                deliveryState = ModelDeliveryState.Pending,
                readiness = ModelReadiness.WAITING,
            ) to ModelPhasePresentation.PENDING,
            summary(
                deliveryState = ModelDeliveryState.WaitingForWifi,
                readiness = ModelReadiness.WAITING,
            ) to ModelPhasePresentation.WAITING_FOR_WIFI,
            summary(
                deliveryState = ModelDeliveryState.RequiresConfirmation,
                readiness = ModelReadiness.WAITING,
            ) to ModelPhasePresentation.CONFIRMATION_REQUIRED,
            summary(
                deliveryState = ModelDeliveryState.Failed(ModelDeliveryIssue.ConfirmationUnavailable),
                readiness = ModelReadiness.NEEDS_ATTENTION,
            ) to ModelPhasePresentation.CONFIRMATION_UNAVAILABLE,
            summary(
                deliveryState = ModelDeliveryState.Transferring,
                readiness = ModelReadiness.PREPARING,
            ) to ModelPhasePresentation.TRANSFERRING,
            summary(
                deliveryState = ModelDeliveryState.Provisioning,
                readiness = ModelReadiness.PREPARING,
            ) to ModelPhasePresentation.PROVISIONING_CHAT,
            summary(
                role = ModelRole.EMBEDDINGS,
                deliveryState = ModelDeliveryState.Provisioning,
                readiness = ModelReadiness.PREPARING,
            ) to ModelPhasePresentation.PROVISIONING,
            summary(
                deliveryState = ModelDeliveryState.Quiescing,
                readiness = ModelReadiness.REMOVING,
            ) to ModelPhasePresentation.QUIESCING,
            summary(
                deliveryState = ModelDeliveryState.Removing,
                readiness = ModelReadiness.REMOVING,
            ) to ModelPhasePresentation.REMOVING,
            summary(
                role = ModelRole.EMBEDDINGS,
                deliveryState = ModelDeliveryState.Installed,
                state = ModelLoadState.STARTING,
                evidence = ModelRuntimeEvidence.DASHBOARD_VERIFICATION,
                readiness = ModelReadiness.STARTING,
            ) to ModelPhasePresentation.VERIFICATION_RUNNING,
        )

        cases.forEach { (summary, expected) ->
            assertEquals("delivery=${summary.deliveryState}", expected, modelPhasePresentation(summary))
        }
    }

    @Test
    fun `stale runtime evidence preserves state-specific historical categories`() {
        val ready = summary(
            state = ModelLoadState.READY,
            evidence = ModelRuntimeEvidence.LAST_KNOWN_SERVICE,
            readiness = ModelReadiness.DOWNLOADED_IDLE,
        )
        val idle = summary(
            state = ModelLoadState.IDLE,
            evidence = ModelRuntimeEvidence.LAST_KNOWN_SERVICE,
            readiness = ModelReadiness.DOWNLOADED_IDLE,
        )
        val failed = summary(
            state = ModelLoadState.FAILED,
            evidence = ModelRuntimeEvidence.LAST_KNOWN_SERVICE,
            readiness = ModelReadiness.DOWNLOADED_IDLE,
        )
        assertEquals(RuntimeSummaryCategory.LAST_KNOWN_READY, runtimeSummaryCategory(ready))
        assertEquals(RuntimeSummaryCategory.LAST_KNOWN_NOT_LOADED, runtimeSummaryCategory(idle))
        assertEquals(RuntimeSummaryCategory.LAST_KNOWN_FAILED, runtimeSummaryCategory(failed))

        val passedVerification = summary(
            role = ModelRole.EMBEDDINGS,
            state = ModelLoadState.IDLE,
            evidence = ModelRuntimeEvidence.DASHBOARD_VERIFICATION,
            readiness = ModelReadiness.DOWNLOADED_IDLE,
            lastVerificationPassed = true,
        )
        assertEquals(
            RuntimeSummaryCategory.VERIFICATION_PASSED,
            runtimeSummaryCategory(passedVerification),
        )
    }

    @Test
    fun `stale runtime failure stays historical rather than current readiness failure`() {
        assertEquals(
            ModelReadiness.DOWNLOADED_IDLE,
            modelReadiness(
                role = ModelRole.CHAT_AND_VISION,
                deliveryState = ModelDeliveryState.Installed,
                runtimeState = ModelLoadState.FAILED,
                evidence = ModelRuntimeEvidence.LAST_KNOWN_SERVICE,
            ),
        )
    }

    @Test
    fun `action availability keeps destructive remove secondary`() {
        assertEquals(
            ModelActionAvailability(
                primary = ModelDeliveryAction.NONE,
                secondary = ModelDeliveryAction.REMOVE,
            ),
            modelActionAvailability(ModelDeliveryState.Installed),
        )
        assertEquals(
            ModelActionAvailability(
                primary = ModelDeliveryAction.RETRY_ACTIVATION,
                secondary = ModelDeliveryAction.REMOVE,
            ),
            modelActionAvailability(ModelDeliveryState.InstalledWithActivationError),
        )
        assertEquals(
            ModelActionAvailability(primary = ModelDeliveryAction.RETRY_REMOVE),
            modelActionAvailability(
                ModelDeliveryState.RemovalFailed(ModelDeliveryIssue.RemovalInterrupted),
            ),
        )
        assertEquals(
            ModelActionAvailability(primary = ModelDeliveryAction.NONE),
            modelActionAvailability(ModelDeliveryState.Removing),
        )
    }

    private fun summary(
        role: ModelRole = ModelRole.CHAT_AND_VISION,
        deliveryState: ModelDeliveryState = ModelDeliveryState.Installed,
        state: ModelLoadState = ModelLoadState.IDLE,
        evidence: ModelRuntimeEvidence = ModelRuntimeEvidence.LIVE_SERVICE,
        readiness: ModelReadiness = ModelReadiness.DOWNLOADED_IDLE,
        runtimeIssue: ModelRuntimeIssue? = null,
        lastVerificationPassed: Boolean? = null,
        lastRuntimeStatusAtMs: Long? = null,
    ): RoleModelSummary = RoleModelSummary(
        role = role,
        modelDisplayName = "Model",
        deliveryPackNames = listOf("pack"),
        state = state,
        evidence = evidence,
        readiness = readiness,
        backend = null,
        initTimeSeconds = null,
        runtimeIssue = runtimeIssue,
        lastRuntimeStatusAtMs = lastRuntimeStatusAtMs,
        lastVerificationAtMs = if (lastVerificationPassed == null) null else 123L,
        lastVerificationPassed = lastVerificationPassed,
        deliveryState = deliveryState,
    )
}
