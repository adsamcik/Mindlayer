package com.adsamcik.mindlayer.service

import android.os.Binder
import android.os.Process
import com.adsamcik.mindlayer.ConsentDecision
import com.adsamcik.mindlayer.service.engine.EngineManager
import com.adsamcik.mindlayer.service.engine.InferenceOrchestrator
import com.adsamcik.mindlayer.service.engine.MemoryBudget
import com.adsamcik.mindlayer.service.engine.SessionManager
import com.adsamcik.mindlayer.service.engine.ThermalMonitor
import com.adsamcik.mindlayer.service.logging.DiagnosticExporter
import com.adsamcik.mindlayer.service.security.AllowlistStore
import com.adsamcik.mindlayer.service.security.CallerIdentity
import com.adsamcik.mindlayer.service.security.ConsentAttemptStore
import com.adsamcik.mindlayer.service.security.ConsentChallengeStore
import com.adsamcik.mindlayer.service.security.ConsentGate
import com.adsamcik.mindlayer.service.security.RateLimiter
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Security-guard tests for the v0.10 consent-Intent AIDL methods. The full
 * happy-path (PendingIntent mint + ConsentActivity round-trip) is exercised
 * by instrumented tests; here we pin the server-side guards that an attacker
 * would probe: self-UID enforcement, decision-shape validation, rate-limit
 * and cooldown gating, and atomic single-use consume.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ServiceBinderConsentFlowTest {

    private val selfUid = 1000
    private val externalUid = 24_681

    private lateinit var allowlist: AllowlistStore
    private lateinit var rateLimiter: RateLimiter
    private lateinit var challengeStore: ConsentChallengeStore
    private lateinit var attemptStore: ConsentAttemptStore
    private lateinit var binder: ServiceBinder

    @Before fun setUp() {
        mockkStatic(Binder::class)
        mockkStatic(Process::class)
        every { Process.myUid() } returns selfUid

        val service = mockk<MindlayerMlService>(relaxed = true) {
            every { sessionManager } returns mockk<SessionManager>(relaxed = true)
            every { packageName } returns "com.adsamcik.mindlayer.service"
        }
        allowlist = mockk(relaxed = true) {
            every { isDenied(any(), any()) } returns false
            every { isAllowed(any(), any()) } returns false
            every { list() } returns emptyList()
        }
        rateLimiter = mockk(relaxed = true) {
            every { tryAcquireConsentChallenge(any()) } returns true
        }
        challengeStore = mockk(relaxed = true)
        attemptStore = mockk(relaxed = true) {
            every { checkGate(any(), any()) } returns ConsentGate.Allow
        }

        binder = ServiceBinder(
            service = service,
            engineManager = mockk<EngineManager>(relaxed = true),
            orchestrator = mockk<InferenceOrchestrator>(relaxed = true),
            diagnosticExporter = mockk<DiagnosticExporter>(relaxed = true),
            thermalMonitor = mockk<ThermalMonitor>(relaxed = true) {
                every { currentPolicy } returns MutableStateFlow(mockk(relaxed = true))
            },
            memoryBudget = mockk<MemoryBudget>(relaxed = true),
            callerVerifier = { _, _ -> CallerIdentity("com.client", "sigC", "Client") },
            allowlistStore = allowlist,
            rateLimiter = rateLimiter,
            consentChallengeStore = challengeStore,
            consentAttemptStore = attemptStore,
        )
    }

    @After fun tearDown() = unmockkAll()

    @Test fun `lookupChallenge rejects non-self UID`() {
        every { Binder.getCallingUid() } returns externalUid
        assertThrows(SecurityException::class.java) { binder.lookupChallenge("nonce") }
    }

    @Test fun `completeConsent rejects non-self UID`() {
        every { Binder.getCallingUid() } returns externalUid
        assertThrows(SecurityException::class.java) {
            binder.completeConsent("nonce", ConsentDecision(kind = ConsentDecision.KIND_GRANT))
        }
    }

    @Test fun `completeConsent rejects an invalid decision kind`() {
        every { Binder.getCallingUid() } returns selfUid
        assertThrows(SecurityException::class.java) {
            binder.completeConsent("nonce", ConsentDecision(kind = 999))
        }
    }

    @Test fun `completeConsent rejects a null decision`() {
        every { Binder.getCallingUid() } returns selfUid
        assertThrows(SecurityException::class.java) {
            binder.completeConsent("nonce", null)
        }
    }

    @Test fun `completeConsent on an unknown nonce fails closed`() {
        every { Binder.getCallingUid() } returns selfUid
        every { challengeStore.consume("gone") } returns null
        assertThrows(SecurityException::class.java) {
            binder.completeConsent("gone", ConsentDecision(kind = ConsentDecision.KIND_GRANT))
        }
    }

    @Test fun `lookupChallenge returns null for unknown nonce`() {
        every { Binder.getCallingUid() } returns selfUid
        every { challengeStore.lookup("gone") } returns null
        assertNull(binder.lookupChallenge("gone"))
    }

    @Test fun `requestConsentChallenge rejects self UID`() {
        every { Binder.getCallingUid() } returns selfUid
        assertThrows(SecurityException::class.java) { binder.requestConsentChallenge() }
    }

    @Test fun `requestConsentChallenge is rate-limited`() {
        every { Binder.getCallingUid() } returns externalUid
        every { rateLimiter.tryAcquireConsentChallenge(externalUid) } returns false
        assertThrows(SecurityException::class.java) { binder.requestConsentChallenge() }
        verify { rateLimiter.tryAcquireConsentChallenge(externalUid) }
    }

    @Test fun `requestConsentChallenge is blocked when in cooldown`() {
        every { Binder.getCallingUid() } returns externalUid
        every { attemptStore.checkGate("com.client", "sigC") } returns
            ConsentGate.Blocked(untilMs = 9_999L, reason = "dismiss_cooldown")
        assertThrows(SecurityException::class.java) { binder.requestConsentChallenge() }
        // No challenge is issued when blocked.
        verify(exactly = 0) { challengeStore.issue(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `requestConsentChallenge rejects an already-approved caller`() {
        every { Binder.getCallingUid() } returns externalUid
        every { allowlist.isAllowed("com.client", "sigC") } returns true
        assertThrows(SecurityException::class.java) { binder.requestConsentChallenge() }
    }

    @Test fun `completeConsent GRANT approves and clears attempt tracking`() {
        every { Binder.getCallingUid() } returns selfUid
        every { challengeStore.consume("n1") } returns ConsentChallengeStore.ChallengeRecord(
            nonce = "n1",
            callerUid = externalUid,
            packageName = "com.client",
            signingCertSha256 = "sigC",
            displayName = "Client",
            installSource = "com.android.vending",
            previousSigSha256 = null,
            createdAtMs = 0L,
            expiresAtMs = Long.MAX_VALUE,
        )
        binder.completeConsent("n1", ConsentDecision(kind = ConsentDecision.KIND_GRANT))
        verify { allowlist.approve(any(), "com.client", "sigC", "Client") }
        verify { attemptStore.clear("com.client", "sigC") }
    }

    @Test fun `completeConsent DENY_ONCE records a dismiss`() {
        every { Binder.getCallingUid() } returns selfUid
        every { challengeStore.consume("n2") } returns ConsentChallengeStore.ChallengeRecord(
            nonce = "n2",
            callerUid = externalUid,
            packageName = "com.client",
            signingCertSha256 = "sigC",
            displayName = "Client",
            installSource = null,
            previousSigSha256 = null,
            createdAtMs = 0L,
            expiresAtMs = Long.MAX_VALUE,
        )
        binder.completeConsent("n2", ConsentDecision(kind = ConsentDecision.KIND_DENY_ONCE))
        verify { attemptStore.recordDismiss("com.client", "sigC") }
        verify(exactly = 0) { allowlist.approve(any(), any(), any(), any()) }
    }

    @Test fun `completeConsent DENY_PERMANENT revokes and blocks package-wide`() {
        every { Binder.getCallingUid() } returns selfUid
        every { challengeStore.consume("n3") } returns ConsentChallengeStore.ChallengeRecord(
            nonce = "n3",
            callerUid = externalUid,
            packageName = "com.client",
            signingCertSha256 = "sigC",
            displayName = "Client",
            installSource = null,
            previousSigSha256 = null,
            createdAtMs = 0L,
            expiresAtMs = Long.MAX_VALUE,
        )
        binder.completeConsent("n3", ConsentDecision(kind = ConsentDecision.KIND_DENY_PERMANENT))
        verify { allowlist.revoke("com.client") }
        verify { allowlist.deny("com.client", null, ConsentDecision.KIND_DENY_PERMANENT) }
    }
}
