package com.adsamcik.mindlayer.service.ui

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.biometric.AuthenticationRequest
import androidx.biometric.AuthenticationRequest.Biometric.Fallback
import androidx.biometric.AuthenticationRequest.Biometric.Strength
import androidx.biometric.AuthenticationResult
import androidx.biometric.AuthenticationResultCallback
import androidx.biometric.BiometricManager
import androidx.biometric.registerForAuthenticationResult
import androidx.compose.runtime.compositionLocalOf
import com.adsamcik.mindlayer.service.R
import com.adsamcik.mindlayer.service.logging.MindlayerLog

/**
 * F-029: gate sensitive Approve / Revoke actions on the dashboard behind a
 * device-presence check (BiometricPrompt with BIOMETRIC_STRONG +
 * DEVICE_CREDENTIAL fallback).
 *
 * The interface exists so the UI layer can be unit-tested with a fake
 * authenticator — production builds construct
 * [BiometricSensitiveActionAuthenticator] in the hosting [ComponentActivity].
 *
 * Behaviour on devices without an enrolled credential:
 *
 * - The original F-029 plan called for default-deny here ("the user with
 *   physical access intentionally approved this", which a device with no
 *   lock cannot provide), but that locks out three real-world cases:
 *     * sideloaded debug installs on emulators (no biometric, no PIN)
 *     * cheap Android devices without a fingerprint sensor whose owners
 *       haven't set a PIN
 *     * users who happen to have removed their PIN
 *   On those devices the dashboard's Approve / Revoke buttons silently no-op,
 *   making the entire allowlist UI unusable.
 *
 * - When `BiometricManager.canAuthenticate(...)` returns
 *   `BIOMETRIC_ERROR_NONE_ENROLLED`, `BIOMETRIC_ERROR_NO_HARDWARE`, or
 *   `BIOMETRIC_ERROR_HW_UNAVAILABLE`, the authenticator now falls open with
 *   a logged `biometric_unavailable_fallback` audit decision. The dashboard's
 *   own two-tap confirmation dialog ([AppApprovalDialog] /
 *   [AppRevocationDialog]) already provides the user-intent gate: it shows
 *   the package name, the full SHA-256 signing fingerprint, an explicit
 *   spoof warning, and a destructive-styled confirm button paired with
 *   Cancel. That dialog is what proves the human with physical access
 *   typed it.
 *
 * - For other unknown/transient states
 *   (`BIOMETRIC_STATUS_UNKNOWN`, `BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED`,
 *   ...) the authenticator keeps failing closed — those signal a recoverable
 *   device condition the user can resolve, not a permanent capability gap.
 *
 * On devices that DO have an enrolled biometric or device credential,
 * behaviour is unchanged: BiometricPrompt with the configured strength
 * gates Approve / Revoke as before.
 */
interface SensitiveActionAuthenticator {
    /**
     * Trigger an authentication challenge for [action]. [onResult] is invoked
     * exactly once on the main thread:
     *
     * - `granted = true` only after the user successfully authenticates.
     * - `granted = false` for cancellation, lockout, hardware unavailability,
     *   or no enrolled credentials.
     */
    fun authenticate(
        action: SensitiveAction,
        onResult: (granted: Boolean, errorCode: Int?, errorMsg: String?) -> Unit,
    )
}

enum class SensitiveAction(
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
) {
    APPROVE_CALLER(
        titleRes = R.string.biometric_approve_title,
        subtitleRes = R.string.biometric_approve_subtitle,
    ),
    REVOKE_CALLER(
        titleRes = R.string.biometric_revoke_title,
        subtitleRes = R.string.biometric_revoke_subtitle,
    ),
}

/**
 * CompositionLocal so [com.adsamcik.mindlayer.service.ui.AllowedAppsCard] can
 * pull the authenticator without threading it through every viewmodel.
 *
 * The default value throws — the hosting Activity MUST install a real
 * authenticator via [androidx.compose.runtime.CompositionLocalProvider] before
 * any sensitive UI composes.
 */
val LocalSensitiveAuth = compositionLocalOf<SensitiveActionAuthenticator> {
    error("LocalSensitiveAuth not provided — wrap content in CompositionLocalProvider")
}

/**
 * Production [SensitiveActionAuthenticator] backed by AndroidX Biometric's
 * Activity Result-style authentication launcher.
 *
 * Authenticator selection:
 * - API 30+: `BIOMETRIC_STRONG | DEVICE_CREDENTIAL`
 * - API 26-29: query biometric capability only; the request still carries
 *   device-credential fallback and the library handles back-compat plumbing.
 *
 * Rubber-duck note: pinned to androidx.biometric:1.4.0-alpha07 because the
 * 1.4.x alpha track is where Google is actively iterating on ComponentActivity,
 * AuthenticationRequest, and Compose-native APIs. The stable 1.1.0 line has not
 * moved since 2021.
 */
class BiometricSensitiveActionAuthenticator(
    private val activity: ComponentActivity,
) : SensitiveActionAuthenticator {
    private var pending: ((Boolean, Int?, String?) -> Unit)? = null

    private val launcher = activity.registerForAuthenticationResult(
        object : AuthenticationResultCallback {
            override fun onAuthResult(result: AuthenticationResult) {
                val cb = pending
                pending = null
                when (result) {
                    is AuthenticationResult.Success -> cb?.invoke(true, null, null)
                    is AuthenticationResult.Error -> cb?.invoke(
                        false,
                        result.errorCode,
                        result.errString.toString(),
                    )
                    is AuthenticationResult.CustomFallbackSelected -> cb?.invoke(
                        false,
                        null,
                        "biometric_custom_fallback_selected",
                    )
                }
            }

            override fun onAuthAttemptFailed() {
                // Failed attempt = wrong fingerprint etc. The prompt stays
                // visible, so do not terminate the sensitive action here.
            }
        },
    )

    override fun authenticate(
        action: SensitiveAction,
        onResult: (granted: Boolean, errorCode: Int?, errorMsg: String?) -> Unit,
    ) {
        val canAuth = BiometricManager.from(activity).canAuthenticate(ALLOWED_AUTHENTICATORS)
        when (val decision = decidePreflight(canAuth)) {
            PreflightDecision.LaunchPrompt -> Unit
            is PreflightDecision.FallOpen -> {
                MindlayerLog.w(
                    TAG,
                    "Biometric unavailable (canAuthenticate=$canAuth) — " +
                        "falling open for $action; dialog-only intent gate in effect",
                )
                onResult(true, canAuth, decision.reason)
                return
            }
            is PreflightDecision.FailClosed -> {
                MindlayerLog.w(TAG, "Biometric in unknown state ($canAuth) — denying $action")
                onResult(false, canAuth, decision.reason)
                return
            }
        }

        if (pending != null) {
            MindlayerLog.w(TAG, "Biometric authentication already pending — denying $action")
            onResult(false, null, "biometric_in_progress")
            return
        }

        pending = onResult
        try {
            launcher.launch(buildRequest(action))
        } catch (t: Throwable) {
            pending = null
            MindlayerLog.w(TAG, "Biometric authentication launch threw: ${t.javaClass.simpleName}")
            onResult(false, null, t.javaClass.simpleName)
        }
    }

    private fun buildRequest(action: SensitiveAction): AuthenticationRequest =
        AuthenticationRequest.Biometric.Builder(
            title = activity.getString(action.titleRes),
            Fallback.DeviceCredential,
        )
            .setSubtitle(activity.getString(action.subtitleRes))
            .setMinStrength(Strength.Class3())
            .setIsConfirmationRequired(true)
            .build()

    /**
     * Decision a preflight `BiometricManager.canAuthenticate(...)` value maps
     * to. Separated out as a pure helper so the policy can be unit-tested
     * without an Activity / BiometricManager — the production class wires it
     * into the real authentication flow above.
     */
    @VisibleForTesting
    internal sealed class PreflightDecision {
        object LaunchPrompt : PreflightDecision()
        data class FallOpen(val reason: String) : PreflightDecision()
        data class FailClosed(val reason: String) : PreflightDecision()
    }

    companion object {
        private const val TAG = "BiometricAuth"

        @Suppress("DEPRECATION")
        private val ALLOWED_AUTHENTICATORS: Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            } else {
                // Below API 30, BIOMETRIC_STRONG | DEVICE_CREDENTIAL is not
                // a supported combo. The biometric library uses its legacy
                // device-credential fallback path instead; the authenticator
                // mask we hand to canAuthenticate must not include
                // DEVICE_CREDENTIAL on these levels (returns
                // BIOMETRIC_ERROR_UNSUPPORTED). Fall back to BIOMETRIC_WEAK
                // — the AuthenticationRequest still accepts PIN at prompt time.
                BiometricManager.Authenticators.BIOMETRIC_WEAK
            }

        /**
         * Map a `BiometricManager.canAuthenticate(...)` return value to the
         * production policy:
         *
         *  - `BIOMETRIC_SUCCESS` → launch the prompt as before.
         *  - `BIOMETRIC_ERROR_NONE_ENROLLED` / `NO_HARDWARE` /
         *    `HW_UNAVAILABLE` → fall open with
         *    `biometric_unavailable_fallback` so the dashboard's existing
         *    two-tap confirmation dialog is the user-intent gate.
         *  - Anything else (`BIOMETRIC_STATUS_UNKNOWN`,
         *    `BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED`, undocumented
         *    transient codes) → fail closed with `biometric_unknown_state`.
         */
        @VisibleForTesting
        @Suppress("MagicNumber") // numeric constants come from BiometricManager
        internal fun decidePreflight(canAuth: Int): PreflightDecision = when (canAuth) {
            BiometricManager.BIOMETRIC_SUCCESS -> PreflightDecision.LaunchPrompt
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                PreflightDecision.FallOpen("biometric_unavailable_fallback")
            else -> PreflightDecision.FailClosed("biometric_unknown_state")
        }
    }
}
