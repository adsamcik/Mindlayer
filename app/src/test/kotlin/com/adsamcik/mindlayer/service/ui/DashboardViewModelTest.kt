package com.adsamcik.mindlayer.service.ui

import android.os.Binder
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.adsamcik.mindlayer.IMindlayerService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
    fun `rejected test inference does not mark a completion timestamp`() {
        val viewModel = DashboardViewModel()

        viewModel.runTestInference()

        val state = viewModel.uiState.value
        assertFalse(state.isTestRunning)
        assertNull(state.lastTestCompletedAtMs)
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

    @Test
    fun `runSdkInferAsyncTest records success on happy-path response`() = runTest {
        val sdk = io.mockk.mockk<com.adsamcik.mindlayer.sdk.Mindlayer>(relaxed = true)
        io.mockk.coEvery { sdk.awaitConnected() } returns Unit
        io.mockk.coEvery { sdk.createSession(any()) } returns "sdk-session-1"
        io.mockk.coEvery { sdk.inferAsync(any(), any()) } returns "I am a helpful assistant."

        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val viewModel = DashboardViewModel()
        viewModel.markReadyForTest()
        viewModel.setSdkClientForTest(sdk)

        viewModel.runSdkInferAsyncTest(context)

        val state = viewModel.uiState.awaitState {
            shadowOf(Looper.getMainLooper()).idle()
            !it.sdkInferAsyncTest.isRunning && it.sdkInferAsyncTest.lastCompletedAtMs != null
        }
        assertEquals(DashboardMessageTone.SUCCESS, state.sdkInferAsyncTest.tone)
        assertTrue(state.sdkInferAsyncTest.output.contains("helpful assistant"))
    }

    @Test
    fun `runSdkInferAsyncTest records error when inferAsync throws`() = runTest {
        val sdk = io.mockk.mockk<com.adsamcik.mindlayer.sdk.Mindlayer>(relaxed = true)
        io.mockk.coEvery { sdk.awaitConnected() } returns Unit
        io.mockk.coEvery { sdk.createSession(any()) } returns "sdk-session-err"
        io.mockk.coEvery { sdk.inferAsync(any(), any()) } throws RuntimeException("backend offline")

        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val viewModel = DashboardViewModel()
        viewModel.markReadyForTest()
        viewModel.setSdkClientForTest(sdk)

        viewModel.runSdkInferAsyncTest(context)

        val state = viewModel.uiState.awaitState {
            shadowOf(Looper.getMainLooper()).idle()
            !it.sdkInferAsyncTest.isRunning &&
                it.sdkInferAsyncTest.status.startsWith("SDK infer-async test failed")
        }
        assertEquals(DashboardMessageTone.ERROR, state.sdkInferAsyncTest.tone)
        assertTrue(state.sdkInferAsyncTest.output.isNotBlank())
    }

    @Test
    fun `runSdkInferRealtimeTest records success when Done event received`() = runTest {
        val events = kotlinx.coroutines.flow.flowOf(
            com.adsamcik.mindlayer.sdk.InferenceEvent.TextDelta("1, 2, 3.", 0L),
            com.adsamcik.mindlayer.sdk.InferenceEvent.Done("stop", "1, 2, 3.", 1L),
        )
        val handle = com.adsamcik.mindlayer.sdk.InferenceHandle("req-rt-1", events)

        val sdk = io.mockk.mockk<com.adsamcik.mindlayer.sdk.Mindlayer>(relaxed = true)
        io.mockk.coEvery { sdk.awaitConnected() } returns Unit
        io.mockk.coEvery { sdk.createSession(any()) } returns "sdk-session-rt"
        io.mockk.coEvery { sdk.inferRealtime(any(), any()) } returns handle

        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val viewModel = DashboardViewModel()
        viewModel.markReadyForTest()
        viewModel.setSdkClientForTest(sdk)

        viewModel.runSdkInferRealtimeTest(context)

        val state = viewModel.uiState.awaitState {
            shadowOf(Looper.getMainLooper()).idle()
            !it.sdkInferRealtimeTest.isRunning && it.sdkInferRealtimeTest.lastCompletedAtMs != null
        }
        assertEquals(DashboardMessageTone.SUCCESS, state.sdkInferRealtimeTest.tone)
        assertTrue(state.sdkInferRealtimeTest.output.contains("1, 2, 3"))
    }

    @Test
    fun `runSdkInferRealtimeTest records error on stream Error event`() = runTest {
        val errorEvent = com.adsamcik.mindlayer.sdk.InferenceEvent.Error(
            message = "stream interrupted",
            code = "STREAM_ERROR",
            seq = 0L,
            tsMs = System.currentTimeMillis(),
            codeInt = 500,
        )
        val events = kotlinx.coroutines.flow.flowOf(errorEvent)
        val handle = com.adsamcik.mindlayer.sdk.InferenceHandle("req-rt-err", events)

        val sdk = io.mockk.mockk<com.adsamcik.mindlayer.sdk.Mindlayer>(relaxed = true)
        io.mockk.coEvery { sdk.awaitConnected() } returns Unit
        io.mockk.coEvery { sdk.createSession(any()) } returns "sdk-session-rt-err"
        io.mockk.coEvery { sdk.inferRealtime(any(), any()) } returns handle

        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val viewModel = DashboardViewModel()
        viewModel.markReadyForTest()
        viewModel.setSdkClientForTest(sdk)

        viewModel.runSdkInferRealtimeTest(context)

        val state = viewModel.uiState.awaitState {
            shadowOf(Looper.getMainLooper()).idle()
            !it.sdkInferRealtimeTest.isRunning &&
                it.sdkInferRealtimeTest.status.startsWith("SDK infer-realtime test failed")
        }
        assertEquals(DashboardMessageTone.ERROR, state.sdkInferRealtimeTest.tone)
        assertTrue(state.sdkInferRealtimeTest.output.isNotBlank())
    }

    @Test
    fun `runSdkGenerateWithImageTest records success on non-empty response`() = runTest {
        val sdk = io.mockk.mockk<com.adsamcik.mindlayer.sdk.Mindlayer>(relaxed = true)
        io.mockk.coEvery { sdk.awaitConnected() } returns Unit
        io.mockk.coEvery { sdk.generateWithImage(any(), any(), any()) } returns "A colourful test image."

        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val viewModel = DashboardViewModel()
        viewModel.markReadyForTest()
        viewModel.setSdkClientForTest(sdk)

        viewModel.runSdkGenerateWithImageTest(context)

        val state = viewModel.uiState.awaitState {
            shadowOf(Looper.getMainLooper()).idle()
            !it.sdkGenerateWithImageTest.isRunning && it.sdkGenerateWithImageTest.lastCompletedAtMs != null
        }
        assertEquals(DashboardMessageTone.SUCCESS, state.sdkGenerateWithImageTest.tone)
        assertTrue(state.sdkGenerateWithImageTest.output.contains("colourful"))
    }

    @Test
    fun `runSdkGenerateWithImageTest records error when generateWithImage throws`() = runTest {
        val sdk = io.mockk.mockk<com.adsamcik.mindlayer.sdk.Mindlayer>(relaxed = true)
        io.mockk.coEvery { sdk.awaitConnected() } returns Unit
        io.mockk.coEvery { sdk.generateWithImage(any(), any(), any()) } throws RuntimeException("model unavailable")

        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val viewModel = DashboardViewModel()
        viewModel.markReadyForTest()
        viewModel.setSdkClientForTest(sdk)

        viewModel.runSdkGenerateWithImageTest(context)

        val state = viewModel.uiState.awaitState {
            shadowOf(Looper.getMainLooper()).idle()
            !it.sdkGenerateWithImageTest.isRunning &&
                it.sdkGenerateWithImageTest.status.startsWith("SDK generate-with-image test failed")
        }
        assertEquals(DashboardMessageTone.ERROR, state.sdkGenerateWithImageTest.tone)
        assertTrue(state.sdkGenerateWithImageTest.output.isNotBlank())
    }

    @Test
    fun `runOcrLlmExtractionTest records success when lines are returned`() = runTest {
        val lines = listOf(
            com.adsamcik.mindlayer.OcrImageLine(text = "Arabica 200g"),
        )
        val result = com.adsamcik.mindlayer.OcrImageResult(
            lines = lines,
            extractionJson = """{"product":"Arabica 200g"}""",
            extractionFields = emptyList(),
            backend = "CPU",
            ocrDurationMs = 120L,
            llmDurationMs = 80L,
        )

        val sdk = io.mockk.mockk<com.adsamcik.mindlayer.sdk.Mindlayer>(relaxed = true)
        io.mockk.coEvery { sdk.awaitConnected() } returns Unit
        io.mockk.coEvery { sdk.ocrAsync(any(), any(), any()) } returns result

        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val viewModel = DashboardViewModel()
        viewModel.markReadyForTest()
        viewModel.setSdkClientForTest(sdk)

        viewModel.runOcrLlmExtractionTest(context)

        val state = viewModel.uiState.awaitState {
            shadowOf(Looper.getMainLooper()).idle()
            !it.ocrLlmExtractionTest.isRunning && it.ocrLlmExtractionTest.lastCompletedAtMs != null
        }
        assertEquals(DashboardMessageTone.SUCCESS, state.ocrLlmExtractionTest.tone)
        assertTrue(state.ocrLlmExtractionTest.output.contains("Arabica"))
    }

    @Test
    fun `runOcrLlmExtractionTest records warning on unsupported capability`() = runTest {
        val sdk = io.mockk.mockk<com.adsamcik.mindlayer.sdk.Mindlayer>(relaxed = true)
        io.mockk.coEvery { sdk.awaitConnected() } returns Unit
        io.mockk.coEvery { sdk.ocrAsync(any(), any(), any()) } throws
            com.adsamcik.mindlayer.sdk.MindlayerException("OCR_IMAGE_ONESHOT feature unsupported")

        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val viewModel = DashboardViewModel()
        viewModel.markReadyForTest()
        viewModel.setSdkClientForTest(sdk)

        viewModel.runOcrLlmExtractionTest(context)

        val state = viewModel.uiState.awaitState {
            shadowOf(Looper.getMainLooper()).idle()
            !it.ocrLlmExtractionTest.isRunning &&
                it.ocrLlmExtractionTest.status.startsWith("OCR + LLM extraction test failed")
        }
        assertEquals(DashboardMessageTone.WARNING, state.ocrLlmExtractionTest.tone)
        assertTrue(state.ocrLlmExtractionTest.output.isNotBlank())
    }

}
