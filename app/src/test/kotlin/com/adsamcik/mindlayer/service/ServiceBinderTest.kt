package com.adsamcik.mindlayer.service

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.adsamcik.mindlayer.RequestMeta
import com.adsamcik.mindlayer.SessionConfig
import com.adsamcik.mindlayer.SessionInfo
import com.adsamcik.mindlayer.ToolResult
import com.adsamcik.mindlayer.service.engine.DeviceTier
import com.adsamcik.mindlayer.service.engine.EngineManager
import com.adsamcik.mindlayer.service.engine.InferenceOrchestrator
import com.adsamcik.mindlayer.service.engine.MemoryBudget
import com.adsamcik.mindlayer.service.engine.MemoryPressure
import com.adsamcik.mindlayer.service.engine.MemorySnapshot
import com.adsamcik.mindlayer.service.engine.ModelInfo
import com.adsamcik.mindlayer.service.engine.ThermalBand
import com.adsamcik.mindlayer.service.engine.ThermalMonitor
import com.adsamcik.mindlayer.service.engine.ThermalPolicy
import com.adsamcik.mindlayer.service.engine.ThermalSample
import com.adsamcik.mindlayer.service.engine.ToolCallBridge
import com.adsamcik.mindlayer.service.logging.DiagnosticExporter
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.security.AllowlistStore
import com.adsamcik.mindlayer.service.security.CallerIdentity
import com.adsamcik.mindlayer.service.security.RateLimiter
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [ServiceBinder]: AIDL delegation, status assembly, and
 * engine info reporting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ServiceBinderTest {

    private lateinit var service: MindlayerMlService
    private lateinit var engineManager: EngineManager
    private lateinit var orchestrator: InferenceOrchestrator
    private lateinit var diagnosticExporter: DiagnosticExporter
    private lateinit var thermalMonitor: ThermalMonitor
    private lateinit var memoryBudget: MemoryBudget
    private lateinit var toolCallBridge: ToolCallBridge
    private lateinit var binder: ServiceBinder

    private val defaultPolicy = ThermalPolicy(
        band = ThermalBand.COOL,
        recommendedBackend = "GPU",
        burstSeconds = 12,
        restSeconds = 0,
        chunkTokens = 128,
    )
    private val defaultSample = ThermalSample(
        status = 0,
        headroomNow = 5.0f,
        headroom10s = 4.5f,
        timestampMs = 1000L,
    )
    private val defaultMemSnapshot = MemorySnapshot(
        availableMb = 6000L,
        totalMb = 12000L,
        lowMemory = false,
        pressure = MemoryPressure.NORMAL,
        recommendedMaxTokens = 16384,
    )
    private val defaultTier = DeviceTier(
        maxSessions = 4,
        defaultMaxTokens = 8192,
        maxMaxTokens = 32768,
        deviceRamMb = 12000L,
    )

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        mockkStatic(SystemClock::class)
        mockkObject(MindlayerLog)

        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { SystemClock.elapsedRealtime() } returns 200_000L
        every { MindlayerLog.d(any(), any(), any(), any()) } returns Unit
        every { MindlayerLog.i(any(), any(), any(), any()) } returns Unit
        every { MindlayerLog.w(any(), any(), any(), any(), any()) } returns Unit
        every { MindlayerLog.e(any(), any(), any(), any(), any()) } returns Unit

        toolCallBridge = mockk(relaxed = true)

        service = mockk(relaxed = true) {
            every { activeInferenceCount } returns 2
            every { createdAtMs } returns 100_000L
        }
        engineManager = mockk(relaxed = true) {
            every { isInitialized } returns true
            every { currentBackend } returns "GPU"
            every { initTimeSeconds } returns 1.5f
        }
        orchestrator = mockk(relaxed = true) {
            every { this@mockk.toolCallBridge } returns this@ServiceBinderTest.toolCallBridge
            every { listSessions() } returns emptyList()
        }
        diagnosticExporter = mockk(relaxed = true)
        thermalMonitor = mockk(relaxed = true) {
            every { currentPolicy } returns MutableStateFlow(defaultPolicy)
            every { latestSample } returns MutableStateFlow(defaultSample)
        }
        memoryBudget = mockk(relaxed = true) {
            every { currentSnapshot() } returns defaultMemSnapshot
            every { deviceTier } returns defaultTier
        }

        binder = ServiceBinder(
            service = service,
            engineManager = engineManager,
            orchestrator = orchestrator,
            diagnosticExporter = diagnosticExporter,
            thermalMonitor = thermalMonitor,
            memoryBudget = memoryBudget,
            callerVerifier = { _, uid ->
                CallerIdentity(
                    packageName = "test.caller",
                    signingCertSha256 = "testsig",
                    displayName = "Test Caller",
                )
            },
            // Use a null store so the binder skips the allowlist gate in unit tests.
            allowlistStore = null,
            rateLimiter = RateLimiter(
                maxRequestsPerMinute = 100_000,
                maxConcurrent = 1_000,
            ),
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ---- Session management delegation -------------------------------------

    @Test
    fun `createSession delegates to orchestrator`() {
        val config = SessionConfig(sessionId = "s1", maxTokens = 2048)
        every { orchestrator.createSession(config, any()) } returns "s1"

        val result = binder.createSession(config)

        assertEquals("s1", result)
        verify { orchestrator.createSession(config, any()) }
    }

    @Test
    fun `destroySession delegates to orchestrator`() {
        binder.destroySession("s1")
        verify { orchestrator.destroySession("s1") }
    }

    @Test
    fun `getSessionInfo delegates to orchestrator`() {
        val info = SessionInfo(
            sessionId = "s1",
            backend = "GPU",
            maxTokens = 4096,
            currentTokenCount = 100,
            turnCount = 3,
            createdAtMs = 1000L,
            lastAccessedAtMs = 2000L,
            isStreaming = false,
        )
        every { orchestrator.getSessionInfo("s1") } returns info

        val result = binder.getSessionInfo("s1")

        assertEquals(info, result)
        verify { orchestrator.getSessionInfo("s1") }
    }

    @Test
    fun `getSessionInfo returns null for unknown session`() {
        every { orchestrator.getSessionInfo("unknown") } returns null
        assertNull(binder.getSessionInfo("unknown"))
    }

    @Test
    fun `listSessions delegates to orchestrator`() {
        val sessions = listOf(
            SessionInfo("s1", "GPU", 4096, 0, 0, 0, 0, false),
            SessionInfo("s2", "CPU", 2048, 50, 2, 0, 0, true),
        )
        every { orchestrator.listSessions() } returns sessions

        val result = binder.listSessions()

        assertEquals(2, result.size)
        assertEquals("s1", result[0].sessionId)
        assertEquals("s2", result[1].sessionId)
        verify { orchestrator.listSessions() }
    }

    // ---- Inference delegation -----------------------------------------------

    @Test
    fun `infer delegates to orchestrator with correct args`() {
        val meta = RequestMeta(requestId = "r1", sessionId = "s1", textContent = "hello")
        val pfd = mockk<ParcelFileDescriptor>(relaxed = true)

        binder.infer(meta, null, null, pfd)

        verify { orchestrator.infer(any<String>(), meta, null, null, pfd, any()) }
    }

    @Test
    fun `infer passes image and audio to orchestrator`() {
        val meta = RequestMeta(requestId = "r2", sessionId = "s1")
        val image = mockk<com.adsamcik.mindlayer.ImageTransfer>(relaxed = true)
        every { image.requestId } returns "r2"
        every { image.payloadBytes } returns 1024
        every { image.width } returns 0
        every { image.height } returns 0
        every { image.pixelFormat } returns 0
        every { image.rowStride } returns 0
        every { image.isSharedMemory } returns false
        every { image.mimeType } returns "image/jpeg"
        val audio = mockk<com.adsamcik.mindlayer.AudioTransfer>(relaxed = true)
        every { audio.requestId } returns "r2"
        every { audio.mimeType } returns "audio/wav"
        every { audio.durationMs } returns null
        val pfd = mockk<ParcelFileDescriptor>(relaxed = true)

        binder.infer(meta, image, audio, pfd)

        verify { orchestrator.infer(any<String>(), meta, image, audio, pfd, any()) }
    }

    @Test
    fun `cancelInference delegates to orchestrator`() {
        binder.cancelInference("r1")
        verify { orchestrator.cancelInference(any<String>()) }
    }

    // ---- Tool results -------------------------------------------------------

    @Test
    fun `submitToolResult delegates to toolCallBridge`() {
        val result = ToolResult(
            requestId = "r1",
            toolName = "calculator",
            resultJson = """{"answer": 42}""",
        )

        binder.submitToolResult("r1", result)

        verify {
            toolCallBridge.submitResult(
                scopedKey = any<String>(),
                toolName = "calculator",
                resultJson = """{"answer": 42}""",
            )
        }
    }

    // ---- getStatus ----------------------------------------------------------

    @Test
    fun `getStatus returns correct ServiceStatus with real data`() {
        every { orchestrator.listSessions() } returns listOf(
            SessionInfo("s1", "GPU", 4096, 0, 0, 0, 0, false),
        )

        val status = binder.getStatus()

        assertTrue(status.isEngineLoaded)
        assertEquals(1, status.activeSessionCount)
        assertEquals(2, status.activeInferenceCount)
        assertEquals("GPU", status.backend)
        assertEquals("COOL", status.thermalBand)
        assertTrue(status.isForeground) // activeInferenceCount > 0
        assertEquals(100_000L, status.uptimeMs) // 200_000 - 100_000
        assertEquals("NORMAL", status.memoryPressure)
        assertEquals(6000L, status.availableRamMb)
        assertEquals(12000L, status.totalRamMb)
        assertEquals(4, status.maxSessions)
        assertEquals(4.5f, status.headroom!!, 0.001f)
    }

    @Test
    fun `getStatus handles null thermal sample (null headroom)`() {
        every { thermalMonitor.latestSample } returns MutableStateFlow(null)

        val status = binder.getStatus()
        assertNull(status.headroom)
    }

    @Test
    fun `getStatus handles null headroom10s in sample`() {
        val sampleNoHeadroom = ThermalSample(
            status = 0,
            headroomNow = 3.0f,
            headroom10s = null,
            timestampMs = 1000L,
        )
        every { thermalMonitor.latestSample } returns MutableStateFlow(sampleNoHeadroom)

        val status = binder.getStatus()
        assertNull(status.headroom)
    }

    @Test
    fun `getStatus isForeground false when no active inferences`() {
        every { service.activeInferenceCount } returns 0

        val status = binder.getStatus()
        assertFalse(status.isForeground)
    }

    @Test
    fun `getStatus reflects different thermal bands`() {
        val hotPolicy = defaultPolicy.copy(band = ThermalBand.HOT, recommendedBackend = "CPU")
        every { thermalMonitor.currentPolicy } returns MutableStateFlow(hotPolicy)

        val status = binder.getStatus()
        assertEquals("HOT", status.thermalBand)
    }

    // ---- getDiagnostics -----------------------------------------------------

    @Test
    fun `getDiagnostics calls diagnosticExporter export`() {
        coEvery { diagnosticExporter.export() } returns """{"test": true}"""

        val result = binder.getDiagnostics()

        assertEquals("""{"test": true}""", result)
    }

    // ---- getEngineInfo ------------------------------------------------------

    @Test
    fun `getEngineInfo returns correct EngineInfo`() {
        val loadedModel = ModelInfo(
            id = "gemma-4-E2B-it",
            displayName = "Gemma 4 E2B Instruct",
            path = "/models/gemma-4-E2B-it.litertlm",
            sizeBytes = 2_400_000_000,
            isDefault = true,
        )
        every { engineManager.currentModel } returns loadedModel
        every { engineManager.modelPath } returns loadedModel.path

        val info = binder.getEngineInfo()

        assertEquals("gemma-4-E2B-it", info.modelId)
        assertEquals(2_400_000_000L, info.modelSizeBytes)
        assertEquals("GPU", info.backend)
        assertEquals(4096, info.maxTokens)
        assertEquals(1.5f, info.initTimeSeconds, 0.001f)
        assertEquals(0f, info.lastPrefillToksPerSec, 0.001f)
        assertEquals(0f, info.lastDecodeToksPerSec, 0.001f)
    }

    @Test
    fun `getEngineInfo handles modelPath exception gracefully`() {
        every { engineManager.modelPath } throws IllegalStateException("not found")

        val info = binder.getEngineInfo()

        assertEquals("", info.modelId)
        assertEquals(0L, info.modelSizeBytes)
    }

    @Test
    fun `getEngineInfo handles non-existent model file`() {
        every { engineManager.modelPath } returns "/nonexistent/model.litertlm"

        val info = binder.getEngineInfo()

        assertEquals("model", info.modelId)
        assertEquals(0L, info.modelSizeBytes)
    }
}
