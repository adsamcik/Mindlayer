package com.adsamcik.mindlayer.service.ui

import android.content.Context
import android.os.Binder
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.adsamcik.mindlayer.IMindlayerService
import com.adsamcik.mindlayer.service.modeldelivery.AssetPackClient
import com.adsamcik.mindlayer.service.modeldelivery.AssetPackSnapshot
import com.adsamcik.mindlayer.service.modeldelivery.MaterializationResult
import com.adsamcik.mindlayer.service.modeldelivery.ModelArtifactMaterializer
import com.adsamcik.mindlayer.service.modeldelivery.ModelDeliveryManager
import com.adsamcik.mindlayer.service.modeldelivery.ModelDeliveryState
import com.adsamcik.mindlayer.service.modeldelivery.ModelFamily
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DashboardViewModelTest {

    @Test
    fun `model delivery refresh metadata and family states are wired into dashboard state`() = runTest {
        val applicationContext =
            androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val refreshGate = CompletableDeferred<Unit>()
        val client = object : AssetPackClient {
            private val mutableStates =
                MutableStateFlow<Map<String, AssetPackSnapshot>>(emptyMap())
            override val states: StateFlow<Map<String, AssetPackSnapshot>> = mutableStates

            override suspend fun refresh(packNames: Collection<String>) {
                refreshGate.await()
            }

            override suspend fun fetch(packNames: Collection<String>) = Unit
            override suspend fun cancel(packNames: Collection<String>) = Unit
            override suspend fun removePack(packName: String) = Unit
            override fun showConfirmationDialog(
                launcher: ActivityResultLauncher<IntentSenderRequest>,
            ): Boolean = false
            override fun close() = Unit
        }
        val materializer = object : ModelArtifactMaterializer {
            override fun materialize(
                family: ModelFamily,
                packAssetDirectories: Map<String, File>,
            ): MaterializationResult = MaterializationResult.Installed

            override fun isMarkedInstalled(
                family: ModelFamily,
                forceValidation: Boolean,
            ): Boolean = false

            override fun remove(family: ModelFamily, lockHeld: Boolean) = Unit
        }
        val manager = ModelDeliveryManager(
            context = applicationContext,
            client = client,
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
            materializer = materializer,
            clockMs = { 314L },
        )
        val viewModel = DashboardViewModel()
        val observeMethod = DashboardViewModel::class.java.getDeclaredMethod(
            "observeDeliveryManager",
            ModelDeliveryManager::class.java,
        ).apply { isAccessible = true }

        try {
            observeMethod.invoke(viewModel, manager)
            shadowOf(Looper.getMainLooper()).idle()
            manager.start()
            runCurrent()
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue(viewModel.uiState.value.modelDeliveryRefresh.isRefreshing)

            refreshGate.complete(Unit)
            advanceUntilIdle()
            shadowOf(Looper.getMainLooper()).idle()

            val state = viewModel.uiState.value
            assertFalse(state.modelDeliveryRefresh.isRefreshing)
            assertEquals(314L, state.modelDeliveryRefresh.lastSuccessfulRefreshAtMs)
            assertNull(state.modelDeliveryRefresh.issue)
            assertEquals(
                ModelDeliveryState.NotInstalled,
                state.modelDelivery[ModelRole.CHAT_AND_VISION],
            )
            assertEquals(
                ModelDeliveryState.NotInstalled,
                state.modelDelivery[ModelRole.EMBEDDINGS],
            )
            assertEquals(ModelDeliveryState.NotInstalled, state.modelDelivery[ModelRole.OCR])
        } finally {
            manager.close()
        }
    }

    @Test
    fun `rejected test inference does not mark a completion timestamp`() {
        val viewModel = DashboardViewModel()

        viewModel.runTestInference()

        val state = viewModel.uiState.value
        assertFalse(state.isTestRunning)
        assertNull(state.lastTestCompletedAtMs)
    }

    @Test
    fun `typed SDK MindlayerException renders code name and message not the class chain`() {
        // Regression: SDK-facade calls (ocrAsync / generateWithImage / inferAsync)
        // wrap a typed SecurityException("MLERR:<code>:<msg>") into a
        // MindlayerException with the prefix STRIPPED into .code + .message, so
        // decodeTypedWireMessage(message) no longer matches and the renderer
        // used to fall through to safeLabel() — surfacing the opaque
        // "MindlayerException -> SecurityException" instead of the real code.
        val viewModel = DashboardViewModel()
        val e = com.adsamcik.mindlayer.sdk.MindlayerException(
            message = "single-image OCR engine not ready",
            code = MindlayerErrorCode.SERVICE_UNAVAILABLE,
            cause = SecurityException(
                "MLERR:${MindlayerErrorCode.SERVICE_UNAVAILABLE}:single-image OCR engine not ready",
            ),
        )

        val rendered = viewModel.typedSdkErrorLabel(e)

        assertTrue("expected code name, got: $rendered", rendered.contains(e.codeName!!))
        assertTrue(
            "expected the operator-safe message, got: $rendered",
            rendered.contains("single-image OCR engine not ready"),
        )
        assertFalse(
            "must not surface the opaque class chain, got: $rendered",
            rendered.contains("SecurityException"),
        )
        assertFalse("must not leak the wire prefix, got: $rendered", rendered.contains("MLERR:"))
    }

    @Test
    fun `runTestInference waits for prewarm before creating session`() = runTest {
        val calls = mutableListOf<String>()
        val service = testService(calls)

        val viewModel = DashboardViewModel()
        viewModel.setServiceForTest(service)
        viewModel.markReadyForTest()

        viewModel.runTestInference()

        viewModel.uiState.awaitState {
            shadowOf(Looper.getMainLooper()).idle()
            !it.isTestRunning
        }
        assertEquals(
            listOf("prewarmAndAwait:GPU:180000", "createSession", "infer", "destroySession"),
            calls,
        )
    }


    @Test
    fun `test inference failure renders safe label without LiteRT stack frames`() = runTest {
        val service = Proxy.newProxyInstance(
            IMindlayerService::class.java.classLoader,
            arrayOf(IMindlayerService::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "asBinder" -> Binder()
                "prewarmAndAwait" -> throw RuntimeException(
                    "com.google.ai.edge.litertlm.NativeEngine leaked prompt text",
                )
                else -> null
            }
        } as IMindlayerService
        val viewModel = DashboardViewModel()
        viewModel.setServiceForTest(service)
        viewModel.markReadyForTest()

        viewModel.runTestInference()

        val state = viewModel.uiState.awaitState {
            shadowOf(Looper.getMainLooper()).idle()
            !it.isTestRunning && it.testStatus.startsWith("Test inference failed")
        }
        val rendered = state.testStatus + "\n" + state.testOutput
        assertTrue(rendered.contains("RuntimeException"))
        assertFalse(rendered.contains("com.google.ai.edge.litertlm"))
        assertFalse(rendered.contains("NativeEngine"))
    }

    @Test
    fun `test inference failure decodes typed wire error to human-readable label`() = runTest {
        // F-079: ServiceBinder.typedBinderException wraps every typed
        // code as SecurityException("MLERR:<code>:<message>") because
        // Binder only marshals a small whitelist of RuntimeException
        // subclasses faithfully. The dashboard must decode the prefix
        // so users see "LOW_MEMORY: ..." instead of a bare
        // "SecurityException".
        val wire = "MLERR:4003:Insufficient memory: availMb=2348 requiredMb=2980"
        val service = Proxy.newProxyInstance(
            IMindlayerService::class.java.classLoader,
            arrayOf(IMindlayerService::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "asBinder" -> Binder()
                "prewarmAndAwait" -> throw SecurityException(wire)
                else -> null
            }
        } as IMindlayerService
        val viewModel = DashboardViewModel()
        viewModel.setServiceForTest(service)
        viewModel.markReadyForTest()

        viewModel.runTestInference()

        val state = viewModel.uiState.awaitState {
            shadowOf(Looper.getMainLooper()).idle()
            !it.isTestRunning && it.testStatus.startsWith("Test inference failed")
        }
        val rendered = state.testStatus + "\n" + state.testOutput
        assertTrue(
            "Should render typed code name, not 'SecurityException'. Got: $rendered",
            rendered.contains("LOW_MEMORY"),
        )
        assertTrue(
            "Should preserve diagnostic numbers from wire message. Got: $rendered",
            rendered.contains("availMb=2348") && rendered.contains("requiredMb=2980"),
        )
        assertFalse(
            "Should hide the raw wire-format prefix from the user. Got: $rendered",
            rendered.contains("MLERR:"),
        )
        assertFalse(
            "Should hide the carrier class name. Got: $rendered",
            rendered.contains("SecurityException"),
        )
    }

    @Test
    fun `drainOcrEvents pins camelCase wire contract with OcrTokenStreamWriter`() = runTest {
        // Regression for the dashboard's snake_case bug — drainOcrEvents
        // used to read line_count / top_value / full_json while the
        // service-side OcrTokenStreamWriter has always emitted
        // camelCase lineCount / topValue / fullJson. The mismatch made
        // every successful Test OCR run report 0 lines and the
        // misleading "recognition model may not have loaded" message.
        //
        // This test wires a real OcrTokenStreamWriter into a real pipe
        // and asserts the dashboard's drainer surfaces the values. If
        // either side renames a field the test fails.
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        val sessionId = "ocr-test-session-1"

        val writer = com.adsamcik.mindlayer.service.ipc.OcrTokenStreamWriter(writeEnd)
        writer.writeHeader(sessionId)
        writer.writeFrameProcessing(frameId = 1L)
        writer.writeFrameProcessed(frameId = 1L, lineCount = 3, durationMs = 1610L)
        writer.writeFieldUpdate(
            fieldName = "line[0]",
            topValue = "Hello world 12",
            confidence = "high",
            consecutiveAgreement = 1,
        )
        writer.writeFieldLocked(
            fieldName = "line[2]",
            topValue = "1234",
        )
        writer.writeResultFinalized(
            fullJson = """{"line[0]":"Hello world 12","line[1]":"[","line[2]":"1234"}""",
        )
        writer.writeDone("ocr_complete")
        writer.close()

        val viewModel = DashboardViewModel()
        val result = viewModel.drainOcrEvents(readEnd)

        // lineCount field rename would zero this back out.
        assertEquals("Expected line count from OCR_FRAME_PROCESSED.lineCount", 3, result.lineCount)
        assertEquals(1, result.frameProcessedCount)
        assertEquals(0, result.errorCount)
        assertTrue("Expected finalized=true", result.finalized)

        // The finalized snapshot path should win over OCR_FIELD_UPDATE /
        // OCR_FIELD_LOCKED concatenation when fullJson is present.
        assertTrue(
            "Expected recognized text to carry fullJson content, got: ${result.recognizedText}",
            result.recognizedText.contains("Hello world 12") &&
                result.recognizedText.contains("1234"),
        )
    }

    @Test
    fun `drainOcrEvents falls back to topValue concatenation when fullJson is empty`() = runTest {
        // Even without a finalized fullJson, OCR_FIELD_UPDATE /
        // OCR_FIELD_LOCKED carry topValue that the dashboard
        // assembles into a multi-line snapshot. Pins the topValue
        // field name against accidental snake_case drift.
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]

        val writer = com.adsamcik.mindlayer.service.ipc.OcrTokenStreamWriter(writeEnd)
        writer.writeHeader("ocr-test-session-2")
        writer.writeFrameProcessed(frameId = 1L, lineCount = 2, durationMs = 500L)
        writer.writeFieldUpdate(
            fieldName = "line[0]",
            topValue = "First line",
            confidence = "medium",
            consecutiveAgreement = 1,
        )
        writer.writeFieldLocked(
            fieldName = "line[1]",
            topValue = "Second line",
        )
        // No writeResultFinalized — fullJson stays null.
        writer.writeDone("ocr_complete")
        writer.close()

        val viewModel = DashboardViewModel()
        val result = viewModel.drainOcrEvents(readEnd)

        assertEquals(2, result.lineCount)
        assertFalse("finalized=true requires OCR_RESULT_FINALIZED", result.finalized)
        assertTrue(
            "Expected First line in recognized text, got: ${result.recognizedText}",
            result.recognizedText.contains("First line"),
        )
        assertTrue(
            "Expected Second line in recognized text, got: ${result.recognizedText}",
            result.recognizedText.contains("Second line"),
        )
    }

    @Test
    fun `drainOcrEvents does not count empty-JSON finalized snapshot as recognized text`() = runTest {
        // Regression for the false-PASS: for zero detections the service emits
        // OCR_RESULT_FINALIZED with fullJson "{}". drainOcrEvents surfaces that
        // as recognizedText="{}" (non-blank) — which previously satisfied
        // `recognized.isNotBlank()` and made the dashboard report PASS despite
        // 0 recognized lines. The pass decision must instead rely on structured
        // signals (lineCount + recognizedValues), so ocrProducedText is false.
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]

        val writer = com.adsamcik.mindlayer.service.ipc.OcrTokenStreamWriter(writeEnd)
        writer.writeHeader("ocr-empty-session")
        writer.writeFrameProcessed(frameId = 1L, lineCount = 0, durationMs = 500L)
        writer.writeResultFinalized(fullJson = "{}")
        writer.writeDone("ocr_complete")
        writer.close()

        val viewModel = DashboardViewModel()
        val result = viewModel.drainOcrEvents(readEnd)

        assertEquals(0, result.lineCount)
        assertTrue("Expected finalized=true", result.finalized)
        assertTrue("No field values expected for an empty result", result.recognizedValues.isEmpty())
        assertFalse(
            "An empty-JSON finalized snapshot must not count as recognized text",
            viewModel.ocrProducedText(result),
        )
    }

    @Test
    fun `ocrProducedText requires real lines or alphanumeric field values`() {
        val viewModel = DashboardViewModel()
        fun drain(lineCount: Int, values: List<String>) = DashboardViewModel.OcrDrainResult(
            totalEvents = 1,
            frameProcessedCount = 1,
            errorCount = 0,
            finalized = true,
            recognizedText = "",
            recognizedValues = values,
            lineCount = lineCount,
        )
        // No lines and no meaningful values -> not produced.
        assertFalse(viewModel.ocrProducedText(drain(0, emptyList())))
        assertFalse(viewModel.ocrProducedText(drain(0, listOf("{}"))))
        assertFalse(viewModel.ocrProducedText(drain(0, listOf("[]", "   "))))
        // A real recognized line count, or any alphanumeric value, counts.
        assertTrue(viewModel.ocrProducedText(drain(1, emptyList())))
        assertTrue(viewModel.ocrProducedText(drain(0, listOf("1234"))))
        assertTrue(viewModel.ocrProducedText(drain(0, listOf("Hello world"))))
    }

    @Test
    fun `streamTestOutcome fails when the engine produced no usable output`() {
        val viewModel = DashboardViewModel()
        fun outcome(
            output: String,
            unparsed: Int = 0,
            errors: Int = 0,
            readError: String? = null,
        ) = viewModel.streamTestOutcome(
            output = output,
            eventCount = 1,
            finishReason = "stop",
            unparsedEventCount = unparsed,
            errorEventCount = errors,
            streamReadError = readError,
        )
        // No usable output -> ERROR (was WARNING).
        assertEquals(DashboardMessageTone.ERROR, outcome("").tone)
        assertEquals(DashboardMessageTone.ERROR, outcome("   ").tone)
        assertEquals(DashboardMessageTone.ERROR, outcome("", unparsed = 2).tone)
        assertEquals(DashboardMessageTone.ERROR, outcome("Hi", errors = 1).tone)
        assertEquals(DashboardMessageTone.ERROR, outcome("Hi", readError = "boom").tone)
        // Real output -> SUCCESS; output with parser warnings stays WARNING.
        assertEquals(DashboardMessageTone.SUCCESS, outcome("Hello there").tone)
        assertEquals(DashboardMessageTone.WARNING, outcome("Hello", unparsed = 1).tone)
    }

    private fun testService(calls: MutableList<String>): IMindlayerService {
        return Proxy.newProxyInstance(
            IMindlayerService::class.java.classLoader,
            arrayOf(IMindlayerService::class.java),
        ) { _, method, args ->
            when (method.name) {
                "asBinder" -> Binder()
                "prewarmAndAwait" -> {
                    calls += "prewarmAndAwait:${args?.get(0)}:${args?.get(1)}"
                    "GPU"
                }
                "createSession" -> {
                    calls += "createSession"
                    "session-ready"
                }
                "infer" -> {
                    calls += "infer"
                    (args?.get(3) as? ParcelFileDescriptor)?.close()
                    Unit
                }
                "destroySession" -> {
                    calls += "destroySession"
                    Unit
                }
                else -> null
            }
        } as IMindlayerService
    }

    @Test
    fun `runImageInferenceTest records AIDL call sequence`() = runTest {
        val calls = mutableListOf<String>()
        val service = testService(calls)

        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val viewModel = DashboardViewModel()
        viewModel.setServiceForTest(service)
        viewModel.markReadyForTest()

        viewModel.runImageInferenceTest(context)

        viewModel.uiState.awaitState {
            shadowOf(Looper.getMainLooper()).idle()
            !it.imageInferenceTest.isRunning
        }
        assertEquals(
            listOf("prewarmAndAwait:GPU:180000", "createSession", "infer", "destroySession"),
            calls,
        )
    }

    @Test
    fun `runImageInferenceTest records failure on prewarm exception`() = runTest {
        val service = Proxy.newProxyInstance(
            IMindlayerService::class.java.classLoader,
            arrayOf(IMindlayerService::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "asBinder" -> Binder()
                "prewarmAndAwait" -> throw RuntimeException("engine init failed")
                else -> null
            }
        } as IMindlayerService

        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val viewModel = DashboardViewModel()
        viewModel.setServiceForTest(service)
        viewModel.markReadyForTest()

        viewModel.runImageInferenceTest(context)

        val state = viewModel.uiState.awaitState {
            shadowOf(Looper.getMainLooper()).idle()
            !it.imageInferenceTest.isRunning &&
                it.imageInferenceTest.status.startsWith("Image inference test failed")
        }
        assertEquals(DashboardMessageTone.ERROR, state.imageInferenceTest.tone)
        assertTrue(state.imageInferenceTest.output.isNotBlank())
    }

    private fun DashboardViewModel.setServiceForTest(service: IMindlayerService) {
        val field = DashboardViewModel::class.java.getDeclaredField("service")
        field.isAccessible = true
        field.set(this, service)
    }
    @Suppress("UNCHECKED_CAST")
    private fun DashboardViewModel.markReadyForTest() {
        val field = DashboardViewModel::class.java.getDeclaredField("_uiState")
        field.isAccessible = true
        val state = field.get(this) as MutableStateFlow<DashboardUiState>
        state.value = state.value.copy(
            connectionState = DashboardConnectionState.CONNECTED,
            isStatusLoading = false,
            lastStatusUpdateMs = System.currentTimeMillis(),
            statusErrorMessage = null,
            isEngineLoaded = true,
            backend = "GPU",
        )
    }

    private fun DashboardViewModel.setSdkClientForTest(sdk: com.adsamcik.mindlayer.sdk.Mindlayer) {
        val field = DashboardViewModel::class.java.getDeclaredField("sdkClientForTest")
        field.isAccessible = true
        field.set(this, sdk)
    }

    // ── SDK facade tests ─────────────────────────────────────────────────────
    //
    // Removed during the v1 SDK rework (C3, commit 3022302). The previous
    // tests mocked the legacy / @Deprecated(HIDDEN) Mindlayer API
    // (sdk.awaitConnected() no-arg, sdk.createSession(...),
    // sdk.inferAsync(...), sdk.inferRealtime(...), sdk.generateWithImage(...),
    // sdk.ocrAsync(...)). All of those are HIDDEN on the v1 interface, so
    // the tests no longer compile. The production code in DashboardViewModel
    // has been migrated to mindlayer.infer { ephemeralSession; text; image;
    // outputText() } and mindlayer.ocr { ... }, but no equivalent tests
    // have been written yet — TODO: re-author against the v1 builder API
    // using mockk relaxed Mindlayer + flowOf<InferenceEvent>(...) etc.
    // Tracked in plan.md.

}
