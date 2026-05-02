package com.adsamcik.mindlayer.service.ui

import android.os.Build
import androidx.annotation.StringRes
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.compositionLocalOf
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.adsamcik.mindlayer.service.R
import com.adsamcik.mindlayer.service.logging.MindlayerLog

/**
 * F-029: gate sensitive Approve / Revoke actions on the dashboard behind a
 * device-presence check (BiometricPrompt with BIOMETRIC_STRONG +
 * DEVICE_CREDENTIAL fallback).
 *
 * The interface exists so the UI layer can be unit-tested with a fake
 * authenticator — production builds construct
 * [BiometricSensitiveActionAuthenticator] in the hosting [FragmentActivity].
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
 * Production [SensitiveActionAuthenticator] backed by AndroidX [BiometricPrompt].
 *
 * Authenticator selection:
 * - API 30+: `setAllowedAuthenticators(BIOMETRIC_STRONG | DEVICE_CREDENTIAL)`
 * - API 21-29: `setDeviceCredentialAllowed(true)` — biometric library handles
 *   the back-compat plumbing.
 *
 * Rubber-duck note: pinned to androidx.biometric:1.1.0 (stable). 1.2.0-alphaXX
 * is >3 years old without graduating; we accept slightly less ergonomic API
 * shims in exchange for a maintained release line.
 */
class BiometricSensitiveActionAuthenticator(
    private val activity: FragmentActivity,
) : SensitiveActionAuthenticator {

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

        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onResult(true, null, null)
            }

            override fun onAuthenticationFailed() {
                // Failed = wrong fingerprint etc. but the prompt stays visible.
                // We don't terminate the action here — let the user retry until
                // the prompt itself is dismissed via onAuthenticationError.
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onResult(false, errorCode, errString.toString())
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)
        val info = buildPromptInfo(action)
        try {
            prompt.authenticate(info)
        } catch (t: Throwable) {
            MindlayerLog.w(TAG, "BiometricPrompt.authenticate threw: ${t.javaClass.simpleName}")
            onResult(false, null, t.javaClass.simpleName)
        }
    }

    private fun buildPromptInfo(action: SensitiveAction): BiometricPrompt.PromptInfo {
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(action.titleRes))
            .setSubtitle(activity.getString(action.subtitleRes))
            .setConfirmationRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
        } else {
            // Legacy API — equivalent to BIOMETRIC_WEAK | DEVICE_CREDENTIAL.
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        return builder.build()
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
                // a supported combo. The biometric library uses the legacy
                // setDeviceCredentialAllowed path instead; the authenticator
                // mask we hand to canAuthenticate must not include
                // DEVICE_CREDENTIAL on these levels (returns
                // BIOMETRIC_ERROR_UNSUPPORTED). Fall back to BIOMETRIC_WEAK
                // — we still call setDeviceCredentialAllowed(true) so PIN is
                // accepted at prompt time.
                BiometricManager.Authenticators.BIOMETRIC_WEAK
            }
    }
}
