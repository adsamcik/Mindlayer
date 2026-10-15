package com.adsamcik.mindlayer.sdk.camera.launcher

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.adsamcik.mindlayer.sdk.ConnectionState
import com.adsamcik.mindlayer.sdk.Mindlayer
import com.adsamcik.mindlayer.sdk.OcrEvent
import com.adsamcik.mindlayer.sdk.OcrHandle
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OcrCaptureViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val client = mockk<Mindlayer>(relaxed = true)
    private val provider = mockk<ProcessCameraProvider>(relaxed = true)
    private lateinit var viewModel: OcrCaptureViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { client.connectionState } returns MutableStateFlow(ConnectionState.CONNECTED)
        every {
            provider.bindToLifecycle(any(), any<CameraSelector>(), any<Preview>(), any<ImageCapture>())
        } returns mockk(relaxed = true)
    }

    @After fun tearDown() {
        if (::viewModel.isInitialized) viewModel.shutdown()
        Dispatchers.resetMain()
    }

    @Test fun `permission grant binds camera and enables capture`() = runTest(dispatcher) {
        createViewModel()
        val owner = mockk<LifecycleOwner>()
        viewModel.onCameraPermissionResult(true)
        assertEquals(CapturePhase.Connecting, viewModel.state.value.phase)

        viewModel.bindCamera(RuntimeEnvironment.getApplication(), owner, preview())
        runCurrent()

        assertEquals(CapturePhase.Ready, viewModel.state.value.phase)
        verify(exactly = 1) {
            provider.bindToLifecycle(owner, any<CameraSelector>(), any<Preview>(), any<ImageCapture>())
        }
    }

    @Test fun `replacement activity rebinds without disconnecting retained client`() = runTest(dispatcher) {
        createViewModel()
        val oldOwner = mockk<LifecycleOwner>()
        val newOwner = mockk<LifecycleOwner>()
        viewModel.onCameraPermissionResult(true)
        viewModel.bindCamera(RuntimeEnvironment.getApplication(), oldOwner, preview())
        runCurrent()

        viewModel.detachCamera(oldOwner)
        viewModel.bindCamera(RuntimeEnvironment.getApplication(), newOwner, preview())
        runCurrent()
        // A late destroy callback from the old Activity must not detach the new camera.
        viewModel.detachCamera(oldOwner)

        assertEquals(CapturePhase.Ready, viewModel.state.value.phase)
        verify(exactly = 1) {
            provider.bindToLifecycle(newOwner, any<CameraSelector>(), any<Preview>(), any<ImageCapture>())
        }
        verify(exactly = 1) { provider.unbind(*anyVararg()) }
        verify(exactly = 0) { client.disconnect() }
        viewModel.shutdown()
        viewModel.shutdown()
        verify(exactly = 1) { client.disconnect() }
    }

    @Test fun `destroy during initialization cannot bind a stale activity`() = runTest(dispatcher) {
        val pending = CompletableDeferred<ProcessCameraProvider>()
        createViewModel { pending.await() }
        val oldOwner = mockk<LifecycleOwner>()
        val newOwner = mockk<LifecycleOwner>()
        viewModel.onCameraPermissionResult(true)
        viewModel.bindCamera(RuntimeEnvironment.getApplication(), oldOwner, preview())
        runCurrent()
        viewModel.detachCamera(oldOwner)
        viewModel.bindCamera(RuntimeEnvironment.getApplication(), newOwner, preview())
        pending.complete(provider)
        runCurrent()

        verify(exactly = 0) {
            provider.bindToLifecycle(oldOwner, any<CameraSelector>(), any<Preview>(), any<ImageCapture>())
        }
        verify(exactly = 1) {
            provider.bindToLifecycle(newOwner, any<CameraSelector>(), any<Preview>(), any<ImageCapture>())
        }
        assertEquals(CapturePhase.Ready, viewModel.state.value.phase)
    }

    @Test fun `cancel before camera provider resolves never binds camera`() = runTest(dispatcher) {
        val pending = CompletableDeferred<ProcessCameraProvider>()
        var result: OcrCaptureResult? = null
        createViewModel { pending.await() }
        viewModel.initialise(request()) { result = it }
        viewModel.onCameraPermissionResult(true)
        viewModel.bindCamera(RuntimeEnvironment.getApplication(), mockk(), preview())
        runCurrent()
        viewModel.onCancel()
        pending.complete(provider)
        runCurrent()

        assertTrue(result is OcrCaptureResult.Cancelled)
        verify(exactly = 0) {
            provider.bindToLifecycle(any(), any<CameraSelector>(), any<Preview>(), any<ImageCapture>())
        }
    }

    @Test fun `realtime recreation retains one session and counts each frame once`() = runTest(dispatcher) {
        val events = MutableSharedFlow<OcrEvent>()
        val session = mockk<OcrHandle.MultiFrame>(relaxed = true)
        every { session.events } returns events
        coEvery { client.ocrSession(any()) } returns session
        createViewModel(mode = OcrCaptureMode.Realtime)
        val oldOwner = mockk<LifecycleOwner>()
        val newOwner = mockk<LifecycleOwner>()
        viewModel.onCameraPermissionResult(true)
        viewModel.bindCamera(RuntimeEnvironment.getApplication(), oldOwner, preview())
        runCurrent()
        events.emit(OcrEvent.FrameReceived(1))
        events.emit(OcrEvent.FrameProcessing(1))
        events.emit(OcrEvent.FrameProcessed(1, 2))
        runCurrent()
        assertEquals(1, viewModel.state.value.framesPushed)

        viewModel.detachCamera(oldOwner)
        viewModel.bindCamera(RuntimeEnvironment.getApplication(), newOwner, preview())
        runCurrent()

        assertEquals(CapturePhase.RealtimeStreaming, viewModel.state.value.phase)
        coVerify(exactly = 1) { client.ocrSession(any()) }
        verify(exactly = 0) { session.close() }
        viewModel.shutdown()
        verify(exactly = 1) { session.close() }
    }

    @Test fun `finalization timeout returns error instead of successful empty result`() = runTest(dispatcher) {
        val session = mockk<OcrHandle.MultiFrame>(relaxed = true)
        every { session.events } returns MutableSharedFlow()
        coEvery { session.finalize() } coAnswers { awaitCancellation() }
        coEvery { client.ocrSession(any()) } returns session
        createViewModel(mode = OcrCaptureMode.Realtime)
        var result: OcrCaptureResult? = null
        viewModel.initialise(request(OcrCaptureMode.Realtime)) { result = it }
        viewModel.onCameraPermissionResult(true)
        viewModel.bindCamera(RuntimeEnvironment.getApplication(), mockk(), preview())
        runCurrent()
        viewModel.onFinalize()
        advanceTimeBy(15_001)
        runCurrent()

        assertTrue(result is OcrCaptureResult.Error)
        assertEquals(CapturePhase.Completed, viewModel.state.value.phase)
    }

    @Test fun `recreation during session creation attaches the eventual session once`() = runTest(dispatcher) {
        val pending = CompletableDeferred<OcrHandle.MultiFrame>()
        val session = mockk<OcrHandle.MultiFrame>(relaxed = true)
        every { session.events } returns MutableSharedFlow()
        coEvery { client.ocrSession(any()) } coAnswers { pending.await() }
        createViewModel(mode = OcrCaptureMode.Realtime)
        val oldOwner = mockk<LifecycleOwner>()
        val newOwner = mockk<LifecycleOwner>()
        viewModel.onCameraPermissionResult(true)
        viewModel.bindCamera(RuntimeEnvironment.getApplication(), oldOwner, preview())
        runCurrent()
        viewModel.detachCamera(oldOwner)
        viewModel.bindCamera(RuntimeEnvironment.getApplication(), newOwner, preview())
        runCurrent()
        pending.complete(session)
        runCurrent()

        coVerify(exactly = 1) { client.ocrSession(any()) }
        verify(exactly = 1) { session.events }
        assertEquals(CapturePhase.RealtimeStreaming, viewModel.state.value.phase)
    }

    private fun createViewModel(
        mode: OcrCaptureMode = OcrCaptureMode.Async,
        getProvider: suspend () -> ProcessCameraProvider = { provider },
    ) {
        viewModel = OcrCaptureViewModel(
            RuntimeEnvironment.getApplication(),
            connect = { client },
            getCameraProvider = { getProvider() },
        )
        viewModel.initialise(request(mode)) {}
    }

    private fun request(mode: OcrCaptureMode = OcrCaptureMode.Async) = OcrCaptureRequest(
        mode = mode,
        profileId = OcrProfileId.Receipt,
    )

    private fun preview() = mockk<PreviewView>(relaxed = true)
}
