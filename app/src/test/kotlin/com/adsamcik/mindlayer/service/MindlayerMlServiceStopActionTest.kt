package com.adsamcik.mindlayer.service

import android.content.Intent
import com.adsamcik.mindlayer.service.engine.EngineManager
import com.adsamcik.mindlayer.service.engine.InferenceOrchestrator
import com.adsamcik.mindlayer.service.engine.MemoryBudget
import com.adsamcik.mindlayer.service.engine.SessionManager
import com.adsamcik.mindlayer.service.engine.ThermalMonitor
import com.adsamcik.mindlayer.service.logging.LogRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * F-043: pressing the foreground notification's STOP action posts an Intent
 * with [MindlayerMlService.ACTION_STOP]. The service responds by:
 *   1. Calling [InferenceOrchestrator.cancelAll] so every in-flight request
 *      is aborted (native cancelProcess + clean cancellation frame).
 *   2. Dropping foreground state.
 *   3. Calling [android.app.Service.stopSelf] so the bound clients see the
 *      service go down deterministically.
 *
 * The full onCreate path drags in SQLCipher + LiteRT-LM and is out of scope
 * for a unit test, so we instantiate the service via Robolectric's
 * ServiceController and directly inject mocks for the lateinit collaborators
 * via reflection, skipping `onCreate`. The behaviour under test is the
 * [MindlayerMlService.onStartCommand] dispatch only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MindlayerMlServiceStopActionTest {

    private lateinit var service: MindlayerMlService
    private lateinit var orchestrator: InferenceOrchestrator

    @Before
    fun setUp() {
        service = Robolectric.buildService(MindlayerMlService::class.java).get()
        orchestrator = mockk(relaxed = true)

        // Inject the mock orchestrator + harmless stand-ins for the other
        // lateinit collaborators referenced by service code paths we might
        // reach. We don't call onCreate; everything else is left default.
        ReflectionHelpers.setField(service, "orchestrator", orchestrator)
        ReflectionHelpers.setField(service, "engineManager", mockk<EngineManager>(relaxed = true))
        ReflectionHelpers.setField(service, "sessionManager", mockk<SessionManager>(relaxed = true))
        ReflectionHelpers.setField(service, "memoryBudget", mockk<MemoryBudget>(relaxed = true))
        ReflectionHelpers.setField(service, "thermalMonitor", mockk<ThermalMonitor>(relaxed = true))
        ReflectionHelpers.setField(service, "logRepository", mockk<LogRepository>(relaxed = true))
    }

    @Test
    fun `ACTION_STOP cancels all inferences via orchestrator`() {
        val intent = Intent(MindlayerMlService.ACTION_STOP)
        service.onStartCommand(intent, 0, 1)
        verify { orchestrator.cancelAll() }
    }

    @Test
    fun `ACTION_STOP returns START_NOT_STICKY`() {
        val intent = Intent(MindlayerMlService.ACTION_STOP)
        val result = service.onStartCommand(intent, 0, 1)
        org.junit.Assert.assertEquals(android.app.Service.START_NOT_STICKY, result)
    }

    @Test
    fun `non-STOP action does not cancel inferences`() {
        // A bind-only or unrelated start-foreground request.
        val intent = Intent("com.example.OTHER")
        service.onStartCommand(intent, 0, 1)
        verify(exactly = 0) { orchestrator.cancelAll() }
    }

    @Test
    fun `null intent does not cancel inferences`() {
        // Service can be re-delivered with a null intent on a START_STICKY
        // restart. We're START_NOT_STICKY but defend against the case anyway.
        service.onStartCommand(null, 0, 1)
        verify(exactly = 0) { orchestrator.cancelAll() }
    }
}
