package com.adsamcik.mindlayer.service.ui

import androidx.biometric.BiometricManager
import com.adsamcik.mindlayer.service.ui.BiometricSensitiveActionAuthenticator.Companion.decidePreflight
import com.adsamcik.mindlayer.service.ui.BiometricSensitiveActionAuthenticator.PreflightDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F-029: the [SensitiveActionAuthenticator] interface contract — Approve /
 * Revoke must NOT proceed when the authenticator denies. The biometric
 * implementation cannot be exercised in JVM unit tests; this verifies the
 * call-site contract using a fake.
 */
class SensitiveActionAuthenticatorTest {

    private class FakeAuth(
        private val grant: Boolean,
        private val errorCode: Int? = null,
    ) : SensitiveActionAuthenticator {
        var lastAction: SensitiveAction? = null
        var callCount: Int = 0

        override fun authenticate(
            action: SensitiveAction,
            onResult: (granted: Boolean, errorCode: Int?, errorMsg: String?) -> Unit,
        ) {
            lastAction = action
            callCount++
            onResult(grant, errorCode, if (grant) null else "fake_denied")
        }
    }

    private fun guardedAction(
        auth: SensitiveActionAuthenticator,
        action: SensitiveAction,
        onGranted: () -> Unit,
    ): Boolean {
        var fired = false
        auth.authenticate(action) { granted, _, _ ->
            if (granted) {
                fired = true
                onGranted()
            }
        }
        return fired
    }

    @Test
    fun `granted authenticator runs the action exactly once`() {
        val auth = FakeAuth(grant = true)
        var ran = 0
        val ok = guardedAction(auth, SensitiveAction.APPROVE_CALLER) { ran++ }
        assertTrue(ok)
        assertEquals(1, ran)
        assertEquals(SensitiveAction.APPROVE_CALLER, auth.lastAction)
    }

    @Test
    fun `denied authenticator never runs the action`() {
        val auth = FakeAuth(grant = false)
        var ran = 0
        val ok = guardedAction(auth, SensitiveAction.APPROVE_CALLER) { ran++ }
        assertFalse(ok)
        assertEquals(0, ran)
        assertEquals(SensitiveAction.APPROVE_CALLER, auth.lastAction)
    }

    @Test
    fun `revoke flow uses REVOKE_CALLER action label`() {
        val auth = FakeAuth(grant = true)
        guardedAction(auth, SensitiveAction.REVOKE_CALLER) { }
        assertEquals(SensitiveAction.REVOKE_CALLER, auth.lastAction)
    }

    @Test
    fun `each tap re-authenticates`() {
        val auth = FakeAuth(grant = true)
        guardedAction(auth, SensitiveAction.APPROVE_CALLER) { }
        guardedAction(auth, SensitiveAction.APPROVE_CALLER) { }
        guardedAction(auth, SensitiveAction.APPROVE_CALLER) { }
        assertEquals(3, auth.callCount)
    }

    @Test
    fun `error code surfaces via callback`() {
        var capturedCode: Int? = null
        var capturedMsg: String? = null
        val auth = FakeAuth(grant = false, errorCode = 5)
        auth.authenticate(SensitiveAction.APPROVE_CALLER) { _, code, msg ->
            capturedCode = code
            capturedMsg = msg
        }
        assertEquals(5, capturedCode)
        assertTrue(capturedMsg!!.isNotEmpty())
    }

    // ---- decidePreflight policy (production class, no Activity needed) -----

    @Test
    fun `preflight BIOMETRIC_SUCCESS launches the prompt`() {
        val d = decidePreflight(BiometricManager.BIOMETRIC_SUCCESS)
        assertEquals(PreflightDecision.LaunchPrompt, d)
    }

    @Test
    fun `preflight NONE_ENROLLED falls open with explicit reason`() {
        val d = decidePreflight(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED)
        assertTrue("expected FallOpen, got $d", d is PreflightDecision.FallOpen)
        assertEquals("biometric_unavailable_fallback", (d as PreflightDecision.FallOpen).reason)
    }

    @Test
    fun `preflight NO_HARDWARE falls open with explicit reason`() {
        val d = decidePreflight(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE)
        assertTrue("expected FallOpen, got $d", d is PreflightDecision.FallOpen)
        assertEquals("biometric_unavailable_fallback", (d as PreflightDecision.FallOpen).reason)
    }

    @Test
    fun `preflight HW_UNAVAILABLE falls open with explicit reason`() {
        val d = decidePreflight(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE)
        assertTrue("expected FallOpen, got $d", d is PreflightDecision.FallOpen)
        assertEquals("biometric_unavailable_fallback", (d as PreflightDecision.FallOpen).reason)
    }

    @Test
    fun `preflight SECURITY_UPDATE_REQUIRED fails closed`() {
        val d = decidePreflight(BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED)
        assertTrue("expected FailClosed, got $d", d is PreflightDecision.FailClosed)
        assertEquals("biometric_unknown_state", (d as PreflightDecision.FailClosed).reason)
    }

    @Test
    fun `preflight STATUS_UNKNOWN fails closed`() {
        val d = decidePreflight(BiometricManager.BIOMETRIC_STATUS_UNKNOWN)
        assertTrue("expected FailClosed, got $d", d is PreflightDecision.FailClosed)
        assertEquals("biometric_unknown_state", (d as PreflightDecision.FailClosed).reason)
    }

    @Test
    fun `preflight unknown sentinel fails closed`() {
        // Defends against an undocumented future BiometricManager status code.
        val d = decidePreflight(-999)
        assertTrue("expected FailClosed for unknown code, got $d", d is PreflightDecision.FailClosed)
    }
}
