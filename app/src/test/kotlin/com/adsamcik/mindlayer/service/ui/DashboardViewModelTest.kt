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

}
