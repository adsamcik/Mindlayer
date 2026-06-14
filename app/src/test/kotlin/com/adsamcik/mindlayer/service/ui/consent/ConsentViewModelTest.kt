package com.adsamcik.mindlayer.service.ui.consent

import android.app.Application
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.mindlayer.ConsentDecision
import com.adsamcik.mindlayer.ConsentIdentity
import com.adsamcik.mindlayer.IMindlayerService
import com.adsamcik.mindlayer.service.ui.awaitState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/**
 * Unit tests for [ConsentViewModel] — the driver behind the trust-boundary
 * consent screen.
 *
 * The view model binds to the in-process `:ml` service, resolves a challenge
 * nonce to a server-pinned [ConsentIdentity], and submits the user's decision.
 * These tests pin the full state machine (Loading -> Prompt / Error ->
 * Submitting -> Finished), the GRANT/DENY mapping, the failure-is-not-granted
 * rule, and the single-decision idempotency that protects the dismiss path.
 *
 * The real `bindService` -> `onServiceConnected` wiring is exercised by firing
 * the view model's own private [ServiceConnection] with a fake
 * [IMindlayerService] whose binder reports it as a local interface — exactly
 * what `IMindlayerService.Stub.asInterface` resolves in-process.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConsentViewModelTest {

    private lateinit var application: Application

    /** Fake-service behaviour, reset per test. */
    private var identityToReturn: ConsentIdentity? = null
    private var completeConsentThrows: Boolean = false
    private val recordedDecisions = mutableListOf<ConsentDecision>()

    private val sampleIdentity = ConsentIdentity(
        packageName = "com.example.client",
        displayName = "Example Client",
        signingCertSha256 = "a".repeat(64),
        installSource = "com.android.vending",
        previousSigSha256 = null,
        expiresAtMs = 1_700_000_300_000L,
    )

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        identityToReturn = null
        completeConsentThrows = false
        recordedDecisions.clear()
    }

    private fun fakeService(): IMindlayerService = Proxy.newProxyInstance(
        IMindlayerService::class.java.classLoader,
        arrayOf(IMindlayerService::class.java),
    ) { _, method, args ->
        when (method.name) {
            "asBinder" -> Binder()
            "lookupChallenge" -> identityToReturn
            "completeConsent" -> {
                if (completeConsentThrows) throw RuntimeException("completeConsent failed")
                synchronized(recordedDecisions) { recordedDecisions.add(args!![1] as ConsentDecision) }
                null
            }
            "toString" -> "FakeMindlayerService"
            "hashCode" -> 0
            "equals" -> false
            else -> null
        }
    } as IMindlayerService

    private fun binderFor(service: IMindlayerService): IBinder = object : Binder() {
        override fun queryLocalInterface(descriptor: String): IInterface = service
    }

    /** Fire the view model's private ServiceConnection as the framework would. */
    private fun deliverConnected(vm: ConsentViewModel, service: IMindlayerService) {
        val field = ConsentViewModel::class.java.getDeclaredField("connection").apply { isAccessible = true }
        val connection = field.get(vm) as ServiceConnection
        val component = ComponentName(
            application.packageName,
            "com.adsamcik.mindlayer.service.MindlayerMlService",
        )
        connection.onServiceConnected(component, binderFor(service))
    }

    private fun resolveToPrompt(): ConsentViewModel {
        identityToReturn = sampleIdentity
        val vm = ConsentViewModel(application)
        // Set the nonce directly and fire the connection ourselves rather than
        // going through start()/bindService — Robolectric's BIND_AUTO_CREATE
        // machinery fires its own onServiceDisconnected on the next looper idle,
        // which would null the `service` field after resolution.
        setField(vm, "nonce", NONCE)
        deliverConnected(vm, fakeService())
        vm.state.awaitState {
            shadowOf(Looper.getMainLooper()).idle()
            it is ConsentUiState.Prompt
        }
        return vm
    }

    private fun lastDecisionKind(): Int = synchronized(recordedDecisions) { recordedDecisions.last().kind }

    private fun decisionCount(): Int = synchronized(recordedDecisions) { recordedDecisions.size }

    private fun awaitCondition(timeoutMs: Long = 3_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out waiting for condition")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    // ── start() guards ───────────────────────────────────────────────────

    @Test
    fun `null nonce finishes in a non-expired error without binding`() {
        val vm = ConsentViewModel(application)
        vm.start(application, null)
        val state = vm.state.value
        assertTrue(state is ConsentUiState.Error)
        assertFalse((state as ConsentUiState.Error).expired)
    }

    @Test
    fun `empty nonce finishes in a non-expired error`() {
        val vm = ConsentViewModel(application)
        vm.start(application, "")
        val state = vm.state.value as ConsentUiState.Error
        assertFalse(state.expired)
    }

    // ── challenge resolution ─────────────────────────────────────────────

    @Test
    fun `resolved challenge renders the prompt with the server identity`() {
        val vm = resolveToPrompt()
        val prompt = vm.state.value as ConsentUiState.Prompt
        assertEquals(sampleIdentity, prompt.identity)
    }

    @Test
    fun `unknown or expired nonce resolves to an expired error`() {
        identityToReturn = null
        val vm = ConsentViewModel(application)
        setField(vm, "nonce", NONCE)
        deliverConnected(vm, fakeService())

        val state = vm.state.awaitState {
            shadowOf(Looper.getMainLooper()).idle()
            it is ConsentUiState.Error
        } as ConsentUiState.Error
        assertTrue(state.expired)
    }

    // ── decision submission ──────────────────────────────────────────────

    private fun setField(vm: ConsentViewModel, name: String, value: Any?) {
        ConsentViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)
    }

    @Test
    fun `approve submits a GRANT decision and finishes granted`() {
        val vm = resolveToPrompt()
        vm.submit(ConsentDecision.KIND_GRANT)

        val finished = vm.state.awaitState {
            shadowOf(Looper.getMainLooper()).idle()
            it is ConsentUiState.Finished
        } as ConsentUiState.Finished
        assertTrue(finished.granted)
        assertEquals("decision_GRANT", finished.reason)
        assertEquals(ConsentDecision.KIND_GRANT, lastDecisionKind())
    }

    @Test
    fun `deny-24h submits the decision and finishes not granted`() {
        val vm = resolveToPrompt()
        vm.submit(ConsentDecision.KIND_DENY_24H)

        val finished = vm.state.awaitState {
            shadowOf(Looper.getMainLooper()).idle()
            it is ConsentUiState.Finished
        } as ConsentUiState.Finished
        assertFalse(finished.granted)
        assertEquals(ConsentDecision.KIND_DENY_24H, lastDecisionKind())
    }

    @Test
    fun `a completeConsent failure still finishes not granted`() {
        val vm = resolveToPrompt()
        completeConsentThrows = true
        vm.submit(ConsentDecision.KIND_GRANT)

        val finished = vm.state.awaitState {
            shadowOf(Looper.getMainLooper()).idle()
            it is ConsentUiState.Finished
        } as ConsentUiState.Finished
        assertFalse("a failed submission must never report granted", finished.granted)
    }

    @Test
    fun `a second submit after a decision is a no-op`() {
        val vm = resolveToPrompt()
        vm.submit(ConsentDecision.KIND_GRANT)
        vm.state.awaitState {
            shadowOf(Looper.getMainLooper()).idle()
            it is ConsentUiState.Finished
        }

        vm.submit(ConsentDecision.KIND_DENY_PERMANENT)
        vm.dismissIfUndecided()
        // give any erroneously-scheduled work a chance to run
        repeat(5) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20) }

        assertEquals(1, decisionCount())
        assertEquals(ConsentDecision.KIND_GRANT, lastDecisionKind())
    }

    // ── dismiss path ─────────────────────────────────────────────────────

    @Test
    fun `dismissIfUndecided records a DENY_ONCE`() {
        val vm = resolveToPrompt()
        vm.dismissIfUndecided()

        awaitCondition { decisionCount() == 1 }
        assertEquals(ConsentDecision.KIND_DENY_ONCE, lastDecisionKind())
    }

    @Test
    fun `dismissIfUndecided after a decision is a no-op`() {
        val vm = resolveToPrompt()
        vm.submit(ConsentDecision.KIND_GRANT)
        vm.state.awaitState {
            shadowOf(Looper.getMainLooper()).idle()
            it is ConsentUiState.Finished
        }

        vm.dismissIfUndecided()
        repeat(5) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20) }

        assertEquals(1, decisionCount())
        assertEquals(ConsentDecision.KIND_GRANT, lastDecisionKind())
    }

    private companion object {
        private const val NONCE = "test-nonce-abcdefgh"
    }
}
