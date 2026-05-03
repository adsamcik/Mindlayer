package com.adsamcik.mindlayer.service

import android.os.Binder
import android.os.IBinder
import android.os.Process
import com.adsamcik.mindlayer.IMindlayerService
import com.adsamcik.mindlayer.SessionConfig
import com.adsamcik.mindlayer.service.engine.EngineManager
import com.adsamcik.mindlayer.service.engine.InferenceOrchestrator
import com.adsamcik.mindlayer.service.engine.MemoryBudget
import com.adsamcik.mindlayer.service.engine.ThermalMonitor
import com.adsamcik.mindlayer.service.health.MlHealthRecorder
import com.adsamcik.mindlayer.service.logging.DiagnosticExporter
import com.adsamcik.mindlayer.service.security.CallerIdentity
import com.adsamcik.mindlayer.service.security.RateLimiter
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * F-074: ServiceBinder integration tests for the crash-loop watchdog.
 *
 * Verifies the throttle gate in [ServiceBinder.authorizeCall]:
 *  - External callers are rejected with the typed
 *    [MindlayerErrorCode.SERVICE_THROTTLED] when
 *    [MlHealthRecorder.shouldThrottleBinds] is `true`.
 *  - Self-UID (dashboard) callers bypass the throttle so the user can
 *    still observe the throttle banner from the main process.
 *  - The wire payload carries `cooldown=<wallClockMs>` so SDK reconnect
 *    loops can defer instead of hot-spinning.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ServiceBinderThrottleTest {

    private lateinit var service: MindlayerMlService
    private lateinit var engineManager: EngineManager
    private lateinit var orchestrator: InferenceOrchestrator
    private lateinit var diagnosticExporter: DiagnosticExporter
    private lateinit var thermalMonitor: ThermalMonitor
    private lateinit var memoryBudget: MemoryBudget
    private lateinit var binder: ServiceBinder
    private lateinit var recorderDir: File
    private lateinit var recorder: MlHealthRecorder

    private val externalUid = 12_345
    private var virtualClockMs: Long = 1_000_000L

    @Before
    fun setUp() {
        service = mockk(relaxed = true)
        engineManager = mockk(relaxed = true)
        orchestrator = mockk(relaxed = true) {
            every { listSessions() } returns emptyList()
            every { listSessionsOwnedBy(any()) } returns emptyList()
            every { closeAllOwnedBy(any()) } returns emptyList()
            every { destroySession(any()) } returns Unit
            every { getSessionOwner(any()) } returns null
            every { getSessionInfo(any()) } returns null
            every { createSession(any(), any<Any>()) } returns "default-session-id"
        }
        diagnosticExporter = mockk(relaxed = true)
        thermalMonitor = mockk(relaxed = true)
        memoryBudget = mockk(relaxed = true)

        recorderDir = Files.createTempDirectory("watchdog_binder_").toFile()
        recorder = MlHealthRecorder(recorderDir) { virtualClockMs }

        val gate = ServiceBinder.CallerVerifierGate { _, _ ->
            CallerIdentity(
                packageName = "com.test.caller",
                signingCertSha256 = "deadbeef",
                displayName = "Test Caller",
            )
        }

        binder = ServiceBinder(
            service = service,
            engineManager = engineManager,
            orchestrator = orchestrator,
            diagnosticExporter = diagnosticExporter,
            thermalMonitor = thermalMonitor,
            memoryBudget = memoryBudget,
            context = mockk(relaxed = true),
            callerVerifier = gate,
            allowlistStore = null,
            rateLimiter = RateLimiter(
                maxRequestsPerMinute = 60_000,
                maxConcurrent = 1_000,
                timeSource = advancingTimeSource(),
            ),
            mlHealthRecorder = recorder,
        )

        mockkStatic(Binder::class)
        every { Binder.getCallingUid() } returns externalUid
        // Caller registers BEFORE we drive the throttle, so the throttle
        // applies to the next AIDL invocation, not registerClient itself.
        registerExternalCaller()
    }

    @After
    fun tearDown() {
        unmockkAll()
        recorderDir.deleteRecursively()
    }

    private fun registerExternalCaller() {
        val token = mockk<IBinder>(relaxed = true)
        every { token.interfaceDescriptor } returns IMindlayerService.DESCRIPTOR
        every { token.linkToDeath(any(), 0) } returns Unit
        binder.registerClient(token)
    }

    private fun trippWatchdog() {
        recorder.recordHealthyBoot()
        repeat(MlHealthRecorder.DEATH_COUNT_THRESHOLD) {
            virtualClockMs += 1_000L
            recorder.recordAbnormalDeath()
        }
        assertTrue("watchdog must be tripped before the test continues", recorder.shouldThrottleBinds())
    }

    private fun advancingTimeSource(): () -> Long {
        var counter = 0L
        return {
            counter += 1_000L
            counter
        }
    }

    private inline fun assertCode(expected: Int, crossinline block: () -> Unit) {
        try {
            block()
            fail("Expected coded RuntimeException(code=$expected) to be thrown")
        } catch (e: RuntimeException) {
            val actual = MindlayerErrorCode.codeFromWireMessage(e.message)
            assertEquals(
                "expected code $expected (${MindlayerErrorCode.nameOf(expected)}), " +
                    "got $actual (${actual?.let { MindlayerErrorCode.nameOf(it) }}): ${e.message}",
                expected,
                actual,
            )
        }
    }

    @Test
    fun `external createSession is throttled when watchdog is tripped`() {
        trippWatchdog()
        assertCode(MindlayerErrorCode.SERVICE_THROTTLED) {
            binder.createSession(SessionConfig(maxTokens = 2048))
        }
    }

    @Test
    fun `external destroySession is throttled when watchdog is tripped`() {
        trippWatchdog()
        assertCode(MindlayerErrorCode.SERVICE_THROTTLED) {
            binder.destroySession("00000000-0000-0000-0000-000000000001")
        }
    }

    @Test
    fun `external listSessions is throttled when watchdog is tripped`() {
        trippWatchdog()
        assertCode(MindlayerErrorCode.SERVICE_THROTTLED) {
            binder.listSessions()
        }
    }

    @Test
    fun `throttled SecurityException carries cooldown in the wire payload`() {
        trippWatchdog()
        val expectedCooldown = recorder.cooldownEndsAt()
        try {
            binder.createSession(SessionConfig(maxTokens = 2048))
            fail("expected SecurityException")
        } catch (e: SecurityException) {
            val msg = e.message ?: fail("missing message").let { return }
            // The wire message body must contain `cooldown=<digits>` so
            // SDK ConnectionManager can parse the deferred-reconnect target.
            assertTrue(
                "wire message should embed cooldown=$expectedCooldown — got: $msg",
                msg.contains("cooldown=$expectedCooldown"),
            )
        }
    }

    @Test
    fun `self-UID dashboard bypasses the throttle even when watchdog is tripped`() {
        trippWatchdog()
        every { Binder.getCallingUid() } returns Process.myUid()
        // Self-UID skips the throttle (and the allowlist + rate limit)
        // so the dashboard can keep polling status while the watchdog
        // is engaged. The orchestrator mock returns an empty list for
        // listSessions so the call should succeed without throwing.
        binder.listSessions()
    }

    @Test
    fun `throttle clears once the rolling window expires`() {
        trippWatchdog()
        // Advance the watchdog clock past the rolling window.
        virtualClockMs += MlHealthRecorder.THROTTLE_WINDOW_MS + 1_000L
        // The next external bind should pass the throttle gate (it can
        // still fail validation downstream — that's fine, the wire code
        // just must not be SERVICE_THROTTLED any more).
        try {
            binder.listSessions()
        } catch (e: SecurityException) {
            val code = MindlayerErrorCode.codeFromWireMessage(e.message)
            assertEquals(
                "throttle should have cleared after the window expired",
                null,
                if (code == MindlayerErrorCode.SERVICE_THROTTLED) code else null,
            )
        }
    }
}
