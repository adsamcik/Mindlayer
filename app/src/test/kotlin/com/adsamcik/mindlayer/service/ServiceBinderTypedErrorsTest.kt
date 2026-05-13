package com.adsamcik.mindlayer.service

import android.os.Binder
import android.os.IBinder
import com.adsamcik.mindlayer.IMindlayerService
import com.adsamcik.mindlayer.SessionConfig
import com.adsamcik.mindlayer.ToolResult
import com.adsamcik.mindlayer.service.engine.EngineManager
import com.adsamcik.mindlayer.service.engine.EngineNotReadyException
import com.adsamcik.mindlayer.service.engine.InferenceOrchestrator
import com.adsamcik.mindlayer.service.engine.MemoryBudget
import com.adsamcik.mindlayer.service.engine.ThermalMonitor
import com.adsamcik.mindlayer.service.logging.DiagnosticExporter
import com.adsamcik.mindlayer.service.security.AllowlistStore
import com.adsamcik.mindlayer.service.security.CallerIdentity
import com.adsamcik.mindlayer.service.security.RateLimiter
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for v02-error-codes: verify [ServiceBinder] throws a wire-coded
 * [SecurityException] (parseable by [MindlayerErrorCode.codeFromWireMessage])
 * for every non-auth-gate failure path. This is the load-bearing wire-contract
 * test — regressions to free-form messages would silently re-break SDK retry
 * decisions and `Conversation.withSession` eviction recovery.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ServiceBinderTypedErrorsTest {

    private lateinit var service: MindlayerMlService
    private lateinit var engineManager: EngineManager
    private lateinit var orchestrator: InferenceOrchestrator
    private lateinit var diagnosticExporter: DiagnosticExporter
    private lateinit var thermalMonitor: ThermalMonitor
    private lateinit var memoryBudget: MemoryBudget
    private lateinit var binder: ServiceBinder

    private val externalUid = 12_345

    @Before
    fun setUp() {
        service = mockk(relaxed = true)
        engineManager = mockk(relaxed = true) {
            // Tests in this class verify orchestrator-level translations.
            // ensureEngineReadyOrStart short-circuits when the engine is
            // already loaded, so we set isInitialized = true to let calls
            // reach orchestrator.createSession (which the tests stub to
            // throw the typed exceptions they're verifying).
            every { isInitialized } returns true
        }
        // Explicit method stubs in the constructor lambda — matches the
        // ServiceBinderTest pattern. With just `relaxed = true` the
        // Robolectric+mockk inline-class-mock transformer doesn't reliably
        // intercept InferenceOrchestrator's methods (they fall through to the
        // real impl with a null SessionManager).
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

        // Bypass identity/rate-limit checks for these tests — we're testing
        // post-auth-gate throws, not the gate itself.
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
            // Always-allow store bypasses the allowlist gate for unit tests.
            allowlistStore = mockk<AllowlistStore>(relaxed = true) {
                every { isDenied(any(), any()) } returns false
                every { isAllowed(any(), any()) } returns true
            },
            // Provide an auto-advancing timeSource so the per-UID token bucket
            // accumulates ~1 token per virtual second; otherwise Robolectric's
            // frozen SystemClock leaves the bucket at zero forever.
            rateLimiter = RateLimiter(
                maxRequestsPerMinute = 60_000,
                maxConcurrent = 1_000,
                timeSource = advancingTimeSource(),
            ),
        )

        mockkStatic(Binder::class)
        every { Binder.getCallingUid() } returns externalUid
        registerExternalCaller()
    }

    @After
    fun tearDown() = unmockkAll()

    private fun registerExternalCaller() {
        val token = mockk<IBinder>(relaxed = true)
        every { token.interfaceDescriptor } returns IMindlayerService.DESCRIPTOR
        every { token.linkToDeath(any(), 0) } returns Unit
        binder.registerClient(token)
    }

    // ---- Engine -----------------------------------------------------------

    @Test
    fun `createSession throws ENGINE_INITIALIZING when orchestrator says engine not ready`() {
        every { orchestrator.createSession(any(), any()) } throws EngineNotReadyException(retryAfterMs = 100L)

        assertCode(MindlayerErrorCode.ENGINE_INITIALIZING) {
            binder.createSession(SessionConfig(maxTokens = 2048))
        }
    }

    @Test
    fun `createSession throws INVALID_SESSION_CONFIG on bad input`() {
        // maxTokens=0 is below the boundary validator's 1..32_768 range;
        // earlier versions of this test used maxTokens=1 which sits at
        // the lower edge but is *valid* — production correctly accepted
        // it and never reached the orchestrator stub, so the test
        // expected-code throw never happened.
        assertCode(MindlayerErrorCode.INVALID_SESSION_CONFIG) {
            binder.createSession(SessionConfig(maxTokens = 0))
        }
    }

    @Test
    fun `createSession translates orchestrator IllegalArgument into INVALID_SESSION_CONFIG`() {
        every { orchestrator.createSession(any(), any()) } throws
            IllegalArgumentException("unsupported backend")

        assertCode(MindlayerErrorCode.INVALID_SESSION_CONFIG) {
            binder.createSession(SessionConfig(maxTokens = 2048))
        }
    }

    // ---- Session ownership ------------------------------------------------

    @Test
    fun `destroySession on unknown session yields SESSION_NOT_FOUND_OR_NOT_OWNED`() {
        every { orchestrator.getSessionOwner(any()) } returns null

        assertCode(MindlayerErrorCode.SESSION_NOT_FOUND_OR_NOT_OWNED) {
            binder.destroySession("00000000-0000-0000-0000-000000000001")
        }
    }

    @Test
    fun `destroySession on session owned by other UID yields SESSION_NOT_FOUND_OR_NOT_OWNED`() {
        // Anti-enumeration: same code for "doesn't exist" and "not yours".
        every { orchestrator.getSessionOwner(any()) } returns externalUid + 1

        assertCode(MindlayerErrorCode.SESSION_NOT_FOUND_OR_NOT_OWNED) {
            binder.destroySession("00000000-0000-0000-0000-000000000001")
        }
    }

    @Test
    fun `destroySession with invalid id yields INVALID_REQUEST`() {
        assertCode(MindlayerErrorCode.INVALID_REQUEST) {
            binder.destroySession("not a uuid !!!")
        }
    }

    // ---- Inference --------------------------------------------------------

    @Test
    fun `cancelInference with invalid id yields INVALID_REQUEST`() {
        assertCode(MindlayerErrorCode.INVALID_REQUEST) {
            binder.cancelInference("..")
        }
    }

    @Test
    fun `submitToolResult with invalid result yields INVALID_TOOL_RESULT`() {
        // Empty toolName trips IpcInputValidator.validateToolResult.
        val invalid = ToolResult(
            requestId = "00000000-0000-0000-0000-000000000001",
            callId = "call-1",
            toolName = "",
            resultJson = "{}",
        )
        assertCode(MindlayerErrorCode.INVALID_TOOL_RESULT) {
            binder.submitToolResult("00000000-0000-0000-0000-000000000001", invalid)
        }
    }

    @Test
    fun `submitToolResult with no active request yields NO_ACTIVE_REQUEST`() {
        val ok = ToolResult(
            requestId = "00000000-0000-0000-0000-000000000001",
            callId = "call-1",
            toolName = "echo",
            resultJson = "{}",
        )
        assertCode(MindlayerErrorCode.NO_ACTIVE_REQUEST) {
            binder.submitToolResult("00000000-0000-0000-0000-000000000001", ok)
        }
    }

    // ---- Helpers ----------------------------------------------------------

    /**
     * Returns a `() -> Long` whose value advances by 1 second per invocation.
     * The RateLimiter computes refill from `(now - lastRefill)`; with a frozen
     * Robolectric clock the bucket would never refill above zero, so we feed
     * it a virtual clock that grows monotonically.
     */
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
}
