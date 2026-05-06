package com.adsamcik.mindlayer.service.ui

import android.os.Binder
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.adsamcik.mindlayer.IMindlayerService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

        viewModel.runTestInference()

        viewModel.uiState.awaitState {
            shadowOf(Looper.getMainLooper()).idle()
            !it.isTestRunning
        }
        assertEquals(
            listOf("prewarmAndAwait:GPU:30000", "createSession", "infer", "destroySession"),
            calls,
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
}
