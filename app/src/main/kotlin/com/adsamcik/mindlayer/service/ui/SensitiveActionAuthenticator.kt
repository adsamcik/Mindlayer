package com.adsamcik.mindlayer.service.ui

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
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
 * Default-deny: if the device has no enrolled biometric AND no screen lock,
 * the action is rejected. The plan's contract is "the user with physical
 * access intentionally approved this", which a device with no lock cannot
 * provide.
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
        when (canAuth) {
            BiometricManager.BIOMETRIC_SUCCESS -> Unit
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
                MindlayerLog.w(
                    TAG,
                    "Biometric not available (canAuthenticate=$canAuth) — denying $action",
                )
                onResult(false, canAuth, "biometric_unavailable")
                return
            }
            else -> {
                // BIOMETRIC_STATUS_UNKNOWN, _SECURITY_UPDATE_REQUIRED, etc. —
                // fail closed; the dashboard surfaces the error to the user.
                MindlayerLog.w(TAG, "Biometric in unknown state ($canAuth) — denying $action")
                onResult(false, canAuth, "biometric_unknown_state")
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
    }
}
