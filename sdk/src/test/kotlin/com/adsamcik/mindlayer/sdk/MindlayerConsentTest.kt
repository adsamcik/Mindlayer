package com.adsamcik.mindlayer.sdk

import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the pure wire-decoding logic of [MindlayerConsent] — the
 * mapping of a `requestConsentChallenge()` failure into a
 * [ConsentRequestResult], and the `until=` token parser that distinguishes a
 * temporary 24h/cooldown denial from a permanent block.
 *
 * The bind/AIDL round-trip is exercised by instrumented tests; here we pin the
 * classification so a future error-code change can't silently mis-route an
 * already-approved or denied caller.
 */
class MindlayerConsentTest {

    private fun wire(code: Int, message: String): String =
        MindlayerErrorCode.wireMessage(code, message)

    @Test fun `INVALID_REQUEST maps to AlreadyApproved`() {
        val r = MindlayerConsent.classifyWireMessage(
            wire(MindlayerErrorCode.INVALID_REQUEST, "Caller is already approved"),
        )
        assertEquals(ConsentRequestResult.AlreadyApproved, r)
    }

    @Test fun `CONSENT_DENIED with a numeric until maps to a temporary Denied`() {
        val r = MindlayerConsent.classifyWireMessage(
            wire(MindlayerErrorCode.CONSENT_DENIED, "until=86400000 reason=user_denied"),
        )
        assertEquals(ConsentRequestResult.Denied(86_400_000L), r)
    }

    @Test fun `CONSENT_DENIED with until=permanent maps to a permanent Denied`() {
        val r = MindlayerConsent.classifyWireMessage(
            wire(MindlayerErrorCode.CONSENT_DENIED, "until=permanent reason=user_denied"),
        )
        assertEquals(ConsentRequestResult.Denied(null), r)
    }

    @Test fun `CONSENT_DENIED without an until token is a permanent Denied`() {
        val r = MindlayerConsent.classifyWireMessage(
            wire(MindlayerErrorCode.CONSENT_DENIED, "App access denied by user"),
        )
        assertEquals(ConsentRequestResult.Denied(null), r)
    }

    @Test fun `a non-wire message is an UNKNOWN Failed`() {
        val raw = "android.os.DeadObjectException"
        val r = MindlayerConsent.classifyWireMessage(raw)
        assertEquals(ConsentRequestResult.Failed(MindlayerErrorCode.UNKNOWN, raw), r)
    }

    @Test fun `a null message is an UNKNOWN Failed`() {
        val r = MindlayerConsent.classifyWireMessage(null)
        assertEquals(ConsentRequestResult.Failed(MindlayerErrorCode.UNKNOWN, null), r)
    }

    @Test fun `another typed code maps to Failed with that code`() {
        val r = MindlayerConsent.classifyWireMessage(
            wire(MindlayerErrorCode.RATE_LIMITED, "Consent challenge rate limit exceeded"),
        )
        assertEquals(
            ConsentRequestResult.Failed(
                MindlayerErrorCode.RATE_LIMITED,
                "Consent challenge rate limit exceeded",
            ),
            r,
        )
    }

    @Test fun `parseUntil extracts a numeric epoch`() {
        assertEquals(123_456L, MindlayerConsent.parseUntil("until=123456 reason=user_denied"))
    }

    @Test fun `parseUntil returns null for permanent`() {
        assertNull(MindlayerConsent.parseUntil("until=permanent reason=user_denied"))
    }

    @Test fun `parseUntil returns null when no token is present`() {
        assertNull(MindlayerConsent.parseUntil("App access denied by user"))
    }
}
