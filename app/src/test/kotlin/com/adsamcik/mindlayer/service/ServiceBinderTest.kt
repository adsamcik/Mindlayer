package com.adsamcik.mindlayer.service

import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
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
import io.mockk.coVerify
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
        coEvery { diagnosticExporter.export() } returns """{"status": "ready"}"""
        thermalMonitor = mockk(relaxed = true) {
            every { currentPolicy } returns MutableStateFlow(defaultPolicy)
            every { latestSample } returns MutableStateFlow(defaultSample)
        }
        memoryBudget = mockk(relaxed = true) {
            every { currentSnapshot() } returns defaultMemSnapshot
            every { deviceTier } returns defaultTier
        }

        binder = newBinder(diagnosticExporter)
    }

    private fun newBinder(exporter: DiagnosticExporter): ServiceBinder =
        ServiceBinder(
            service = service,
            engineManager = engineManager,
            orchestrator = orchestrator,
            diagnosticExporter = exporter,
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
    fun `createSession starts engine warmup asynchronously when engine is not initialized`() {
        every { engineManager.isInitialized } returns false
        coEvery {
            engineManager.initialize(
                preferredBackend = "CPU",
                maxTokens = 2048,
            )
        } returns mockk(relaxed = true)

        val config = SessionConfig(sessionId = "s1", backend = "CPU", maxTokens = 2048)

        try {
            binder.createSession(config)
            fail("Expected createSession to report that the engine is initializing")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("initialization has been started"))
        }

        verify(exactly = 0) { orchestrator.createSession(any(), any()) }
        coVerify(timeout = 1_000) {
            engineManager.initialize(
                preferredBackend = "CPU",
                maxTokens = 2048,
            )
        }
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

        verify { orchestrator.infer(meta, null, null, pfd, any()) }
    }

    @Test
    fun `infer passes image and audio to orchestrator`() {
        val meta = RequestMeta(requestId = "r2", sessionId = "s1")
        val image = mockk<com.adsamcik.mindlayer.ImageTransfer>(relaxed = true)
        val audio = mockk<com.adsamcik.mindlayer.AudioTransfer>(relaxed = true)
        val pfd = mockk<ParcelFileDescriptor>(relaxed = true)

        binder.infer(meta, image, audio, pfd)

        verify { orchestrator.infer(meta, image, audio, pfd, any()) }
    }

    @Test
    fun `cancelInference delegates to orchestrator`() {
        binder.cancelInference("r1")
        // H3a — ServiceBinder forwards caller uid; in test context the uid is
        // the host process uid (Robolectric default). Match any uid.
        verify { orchestrator.cancelInference(any<Int>(), "r1") }
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
                uid = any<Int>(),
                requestId = "r1",
                callId = null,
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
    fun `getDiagnostics returns cached snapshot while refresh runs asynchronously`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slowExporter = mockk<DiagnosticExporter>()
        coEvery { slowExporter.export() } answers {
            started.countDown()
            release.await(1, TimeUnit.SECONDS)
            """{"test": true}"""
        }
        val localBinder = newBinder(slowExporter)
        assertTrue(started.await(1, TimeUnit.SECONDS))

        val result = localBinder.getDiagnostics()

        assertEquals("""{"status":"warming"}""", result)
        release.countDown()
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
    fun `getEngineInfo handles unloaded engine without modelPath lookup`() {
        every { engineManager.currentModel } returns null

        val info = binder.getEngineInfo()

        assertEquals("", info.modelId)
        assertEquals(0L, info.modelSizeBytes)
        verify(exactly = 0) { engineManager.modelPath }
    }

    @Test
    fun `getEngineInfo handles non-existent model file`() {
        every { engineManager.currentModel } returns ModelInfo(
            id = "",
            displayName = "Model",
            path = "/nonexistent/model.litertlm",
            sizeBytes = 0L,
            isDefault = true,
        )

        val info = binder.getEngineInfo()

        assertEquals("model", info.modelId)
        assertEquals(0L, info.modelSizeBytes)
    }

    // ---- M1/M2: SessionConfig boundary validation --------------------------

    @Test
    fun `createSession rejects invalid backend with SecurityException`() {
        val config = SessionConfig(sessionId = "s1", backend = "TPU")
        try {
            binder.createSession(config)
            fail("Expected SecurityException")
        } catch (e: SecurityException) {
            assertEquals("Invalid SessionConfig", e.message)
        }
        // M1: validation must run before warmup, before orchestrator invocation
        verify(exactly = 0) { orchestrator.createSession(any(), any()) }
    }

    @Test
    fun `createSession rejects maxTokens=0`() {
        val config = SessionConfig(sessionId = "s1", maxTokens = 0)
        try {
            binder.createSession(config)
            fail("Expected SecurityException")
        } catch (e: SecurityException) {
            assertEquals("Invalid SessionConfig", e.message)
        }
    }

    @Test
    fun `createSession rejects maxTokens=Int_MAX_VALUE`() {
        val config = SessionConfig(sessionId = "s1", maxTokens = Int.MAX_VALUE)
        try {
            binder.createSession(config)
            fail("Expected SecurityException")
        } catch (e: SecurityException) {
            assertEquals("Invalid SessionConfig", e.message)
        }
    }

    @Test
    fun `createSession rejects oversize systemPrompt`() {
        val tooLong = "x".repeat(
            com.adsamcik.mindlayer.service.engine.SessionManager.MAX_SYSTEM_PROMPT_CHARS + 1,
        )
        val config = SessionConfig(sessionId = "s1", systemPrompt = tooLong)
        try {
            binder.createSession(config)
            fail("Expected SecurityException")
        } catch (e: SecurityException) {
            assertEquals("Invalid SessionConfig", e.message)
        }
    }

    @Test
    fun `createSession rejects negative expirationMs`() {
        val config = SessionConfig(sessionId = "s1", expirationMs = -1L)
        try {
            binder.createSession(config)
            fail("Expected SecurityException")
        } catch (e: SecurityException) {
            assertEquals("Invalid SessionConfig", e.message)
        }
    }

    @Test
    fun `createSession rejects expirationMs=Long_MAX_VALUE`() {
        val config = SessionConfig(sessionId = "s1", expirationMs = Long.MAX_VALUE)
        try {
            binder.createSession(config)
            fail("Expected SecurityException")
        } catch (e: SecurityException) {
            assertEquals("Invalid SessionConfig", e.message)
        }
    }

    @Test
    fun `createSession rejects sessionId with newline (log-injection candidate)`() {
        val config = SessionConfig(sessionId = "s1\nINJECT")
        try {
            binder.createSession(config)
            fail("Expected SecurityException")
        } catch (e: SecurityException) {
            assertEquals("Invalid SessionConfig", e.message)
        }
    }

    // L2: error redaction — e.message from internal validator MUST NOT leak
    @Test
    fun `createSession SecurityException does not echo internal exception message`() {
        every { orchestrator.createSession(any(), any()) } throws
            IllegalArgumentException("internal: KV cache size 1234 exceeds tier limit")
        val config = SessionConfig(sessionId = "s1", maxTokens = 2048)
        try {
            binder.createSession(config)
            fail("Expected SecurityException")
        } catch (e: SecurityException) {
            assertEquals("Invalid SessionConfig", e.message)
            // No internal details leaked
            assertFalse(e.message!!.contains("KV cache"))
            assertFalse(e.message!!.contains("1234"))
        }
    }

    // ---- M3: RequestMeta boundary validation -------------------------------

    @Test
    fun `infer rejects requestId containing newlines`() {
        val meta = RequestMeta(requestId = "r1\nINJECT", sessionId = "s1")
        val pfd = mockk<ParcelFileDescriptor>(relaxed = true)
        try {
            binder.infer(meta, null, null, pfd)
            fail("Expected SecurityException")
        } catch (e: SecurityException) {
            assertEquals("Invalid request", e.message)
        }
        verify(exactly = 0) { orchestrator.infer(any(), any(), any(), any(), any()) }
        // M11: PFD must be closed when validation rejects
        verify { pfd.close() }
    }

    @Test
    fun `infer rejects role outside ALLOWED_ROLES`() {
        val meta = RequestMeta(requestId = "r1", sessionId = "s1", role = "admin")
        val pfd = mockk<ParcelFileDescriptor>(relaxed = true)
        try {
            binder.infer(meta, null, null, pfd)
            fail("Expected SecurityException")
        } catch (e: SecurityException) {
            assertEquals("Invalid request", e.message)
        }
    }

    @Test
    fun `infer rejects priority out of range`() {
        val meta = RequestMeta(requestId = "r1", sessionId = "s1", priority = 9999)
        val pfd = mockk<ParcelFileDescriptor>(relaxed = true)
        try {
            binder.infer(meta, null, null, pfd)
            fail("Expected SecurityException")
        } catch (e: SecurityException) {
            assertEquals("Invalid request", e.message)
        }
    }

    @Test
    fun `infer rejects empty requestId`() {
        val meta = RequestMeta(requestId = "", sessionId = "s1")
        val pfd = mockk<ParcelFileDescriptor>(relaxed = true)
        try {
            binder.infer(meta, null, null, pfd)
            fail("Expected SecurityException")
        } catch (e: SecurityException) {
            assertEquals("Invalid request", e.message)
        }
    }

    @Test
    fun `infer rejects blank sessionId`() {
        val meta = RequestMeta(requestId = "r1", sessionId = "")
        val pfd = mockk<ParcelFileDescriptor>(relaxed = true)
        try {
            binder.infer(meta, null, null, pfd)
            fail("Expected SecurityException")
        } catch (e: SecurityException) {
            assertEquals("Invalid request", e.message)
        }
    }

    // ---- M11: FD leak handling ---------------------------------------------

    @Test
    fun `infer closes image and audio PFDs on validation failure`() {
        val meta = RequestMeta(requestId = "bad id with spaces!", sessionId = "s1")
        val pfd = mockk<ParcelFileDescriptor>(relaxed = true)
        val imgPfd = mockk<ParcelFileDescriptor>(relaxed = true)
        val audPfd = mockk<ParcelFileDescriptor>(relaxed = true)
        val image = com.adsamcik.mindlayer.ImageTransfer(
            requestId = "r1",
            width = 0,
            height = 0,
            pixelFormat = 0,
            rowStride = 0,
            payloadBytes = 0,
            source = imgPfd,
        )
        val audio = com.adsamcik.mindlayer.AudioTransfer(
            requestId = "r1",
            source = audPfd,
        )
        try {
            binder.infer(meta, image, audio, pfd)
            fail("Expected SecurityException")
        } catch (_: SecurityException) { /* expected */ }
        verify { pfd.close() }
        verify { imgPfd.close() }
        verify { audPfd.close() }
    }

    @Test
    fun `infer does not close PFD on successful handoff to orchestrator`() {
        val meta = RequestMeta(requestId = "r1", sessionId = "s1", textContent = "hi")
        val pfd = mockk<ParcelFileDescriptor>(relaxed = true)
        binder.infer(meta, null, null, pfd)
        verify(exactly = 0) { pfd.close() }
        verify { orchestrator.infer(meta, null, null, pfd, any()) }
    }

    // ---- L1: getStatus scoping ---------------------------------------------

    @Test
    fun `getStatus self-caller sees full memory and headroom data`() {
        // Default setup: Binder.getCallingUid() returns Process.myUid() → self-UID path
        val status = binder.getStatus()
        assertEquals(6000L, status.availableRamMb)
        assertEquals(12000L, status.totalRamMb)
        assertEquals(4.5f, status.headroom!!, 0.001f)
        assertEquals(2, status.activeInferenceCount)
    }

    @Test
    fun `getStatus non-self caller has memory headroom zeroed`() {
        // Force getCallingUid to return a non-self uid
        mockkStatic(android.os.Binder::class)
        every { android.os.Binder.getCallingUid() } returns 99999
        try {
            val status = binder.getStatus()
            assertEquals(0L, status.availableRamMb)
            assertEquals(0L, status.totalRamMb)
            assertNull(status.headroom)
            assertEquals("NORMAL", status.memoryPressure)
            // activeInferenceCount is replaced by caller's own count (0 here)
            assertEquals(0, status.activeInferenceCount)
            assertFalse(status.isForeground)
        } finally {
            io.mockk.unmockkStatic(android.os.Binder::class)
        }
    }

    // ---- H2: rate-limit before allowlist; rejection bucket caps recordPending

    @Test
    fun `un-allowlisted UID hammered N times triggers bounded recordPending count`() {
        val store = mockk<AllowlistStore>(relaxed = true)
        every { store.isDenied(any(), any()) } returns false
        every { store.isAllowed(any(), any()) } returns false

        val rateLimiter = RateLimiter(
            maxRequestsPerMinute = 10_000,    // don't trip the main bucket
            maxConcurrent = 1_000,
            maxRejectionsPerMinute = 6,
        )
        val externalUid = 99001
        val localBinder = ServiceBinder(
            service = service,
            engineManager = engineManager,
            orchestrator = orchestrator,
            diagnosticExporter = diagnosticExporter,
            thermalMonitor = thermalMonitor,
            memoryBudget = memoryBudget,
            callerVerifier = { _, _ ->
                CallerIdentity(
                    packageName = "evil.app",
                    signingCertSha256 = "evilsig",
                    displayName = "Evil",
                )
            },
            allowlistStore = store,
            rateLimiter = rateLimiter,
        )

        mockkStatic(android.os.Binder::class)
        every { android.os.Binder.getCallingUid() } returns externalUid
        try {
            repeat(50) {
                try { localBinder.getStatus() } catch (_: SecurityException) { /* expected */ }
            }
        } finally {
            io.mockk.unmockkStatic(android.os.Binder::class)
        }

        // Only the first ~6 rejections in the minute should call recordPending.
        // Looser upper bound (8) to absorb minor refill timing variance.
        verify(atLeast = 1, atMost = 8) {
            store.recordPending(any(), any(), any())
        }
    }

    // ---- H4-binder: engineWarming in getStatus ----------------------------

    @Test
    fun `getStatus returns engineWarming true when warmup is in flight`() {
        val warmupField = ServiceBinder::class.java.getDeclaredField("engineWarmupInFlight")
        warmupField.isAccessible = true
        val warmupAtomic = warmupField.get(binder) as java.util.concurrent.atomic.AtomicBoolean
        warmupAtomic.set(true)
        try {
            val status = binder.getStatus()
            assertTrue(status.engineWarming)
        } finally {
            warmupAtomic.set(false)
        }
    }

    // ---- M9: getStatus session count scoped to calling UID -----------------

    @Test
    fun `getStatus scopes session count to calling UID for non-self callers`() {
        mockkStatic(Binder::class)
        every { Binder.getCallingUid() } returns 1001
        try {
            val ownedSession = SessionInfo("s1", "GPU", 4096, 0, 0, 0, 0, false)
            every { orchestrator.listSessionsOwnedBy(1001) } returns listOf(ownedSession)

            val status = binder.getStatus()

            assertEquals(1, status.activeSessionCount)
            verify(exactly = 0) { orchestrator.listSessions() }
            verify { orchestrator.listSessionsOwnedBy(1001) }
        } finally {
            io.mockk.unmockkStatic(Binder::class)
        }
    }

    // ---- M7: rate-limit token consumed before allowlist check --------------

    @Test
    fun `authorizeCall consumes rate-limit token even for allowlist-rejected callers`() {
        mockkStatic(Binder::class)
        every { Binder.getCallingUid() } returns 1001

        val mockAllowlist = mockk<AllowlistStore>(relaxed = true)
        every { mockAllowlist.isDenied(any(), any()) } returns false
        every { mockAllowlist.isAllowed(any(), any()) } returns false

        val mockRateLimiter = mockk<RateLimiter>(relaxed = true)
        every { mockRateLimiter.tryAcquire(any()) } returns true

        val testBinder = ServiceBinder(
            service = service,
            engineManager = engineManager,
            orchestrator = orchestrator,
            diagnosticExporter = diagnosticExporter,
            thermalMonitor = thermalMonitor,
            memoryBudget = memoryBudget,
            callerVerifier = { _, _ ->
                CallerIdentity(
                    packageName = "test.caller",
                    signingCertSha256 = "testsig",
                    displayName = "Test Caller",
                )
            },
            allowlistStore = mockAllowlist,
            rateLimiter = mockRateLimiter,
        )

        try {
            try {
                testBinder.getStatus()
                fail("Expected SecurityException from allowlist")
            } catch (e: SecurityException) {
                // expected
            }
            verify { mockRateLimiter.tryAcquire(1001) }
        } finally {
            io.mockk.unmockkStatic(Binder::class)
        }
    }

    // ---- M10: registerClient atomicity (compute-based) ---------------------

    @Test
    fun `registerClient swaps prior token atomically without leaking DeathRecipient`() {
        val first = mockk<IBinder>(relaxed = true)
        val second = mockk<IBinder>(relaxed = true)
        // Default mockk(relaxed=true) accepts linkToDeath/unlinkToDeath without error.

        binder.registerClient(first)
        binder.registerClient(second)

        // The OLD recipient must be unlinked when replaced.
        verify { first.unlinkToDeath(any(), 0) }
        // Both tokens received linkToDeath exactly once.
        verify(exactly = 1) { first.linkToDeath(any(), 0) }
        verify(exactly = 1) { second.linkToDeath(any(), 0) }
    }
}
