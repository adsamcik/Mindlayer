package com.adsamcik.mindlayer.service.ui.consent

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import com.adsamcik.mindlayer.ConsentDecision
import com.adsamcik.mindlayer.service.ServiceBinder
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.ui.BiometricSensitiveActionAuthenticator
import com.adsamcik.mindlayer.service.ui.SensitiveAction
import com.adsamcik.mindlayer.service.ui.SensitiveActionAuthenticator
import com.adsamcik.mindlayer.service.ui.theme.MindlayerTheme

/**
 * Mindlayer's user-consent screen for the v0.10 consent-Intent flow.
 *
 * Launched only by an explicit, Mindlayer-minted `PendingIntent` carrying the
 * consent nonce. Binds to the in-process `:ml` service, resolves the nonce to
 * a `ConsentIdentity` via `lookupChallenge`, renders the consent UI, and
 * submits the user's decision via `completeConsent` (Approve is gated by
 * F-029 biometric). All AIDL calls are self-UID and server-enforced.
 *
 * Window hardening (security review):
 *  - opaque, branded theme (no translucency — prevents background-blend
 *    phishing);
 *  - `FLAG_SECURE` to suppress screenshots / screen recording of the
 *    approval surface;
 *  - `setHideOverlayWindows(true)` on API 31+ (`Build.VERSION_CODES.S`,
 *    NOT `S_V2`) to block tapjacking overlays;
 *  - `filterTouchesWhenObscured` so a partially-obscured Approve tap is
 *    dropped.
 *
 * See `docs/architecture/CONSENT_ARCHITECTURE.md § ConsentActivity` and
 * `.github/instructions/security.instructions.md § ConsentActivity
 * invariants`.
 */
class ConsentActivity : ComponentActivity() {

    private val viewModel: ConsentViewModel by viewModels()
    private lateinit var authenticator: SensitiveActionAuthenticator
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        window.decorView.filterTouchesWhenObscured = true
        // setHideOverlayWindows is available from API 31 (S). MainActivity
        // historically gated on S_V2 (API 32); the consent screen uses the
        // correct S floor so API 31 devices are also protected.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                window.setHideOverlayWindows(true)
            } catch (_: Throwable) {
                // OEM may have stripped the API — best-effort.
            }
        }
        // Edge-to-edge is enforced on targetSdk 35+, so content already draws
        // under the system bars. Opt in explicitly (matching MainActivity) for
        // transparent bars; the Surface below + safeDrawing insets in the
        // composables keep content readable and clear of the status/nav bars.
        // This does NOT make the activity translucent — the Surface paints an
        // opaque branded background, preserving the anti-phishing invariant.
        enableEdgeToEdge()

        authenticator = BiometricSensitiveActionAuthenticator(this)

        // Back press without an explicit decision is treated as a "Not now"
        // dismiss so the per-(pkg,sig) escalation advances. Swipe-away / home
        // are already counted toward the device-wide throttle at
        // lookupChallenge time, so a missed dismiss there is not a gap.
        onBackPressedDispatcher.addCallback(this) {
            viewModel.dismissIfUndecided()
            finishWith(granted = false)
        }

        val nonce = intent?.getStringExtra(ServiceBinder.EXTRA_CONSENT_NONCE)
        viewModel.start(this, nonce)

        setContent {
            MindlayerTheme {
                // Surface provides the opaque branded background AND sets
                // LocalContentColor to onSurface. Without it, Compose Text with
                // no explicit color falls back to LocalContentColor's default
                // (black), rendering unreadably on the dark window background.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val state by viewModel.state.collectAsState()
                    when (val s = state) {
                        is ConsentUiState.Loading,
                        is ConsentUiState.Submitting,
                        -> ConsentLoading()

                        is ConsentUiState.Prompt -> ConsentScreen(
                            identity = s.identity,
                            submitting = false,
                            actions = ConsentActions(
                                onApprove = ::onApproveTapped,
                                onDenyOnce = { viewModel.submit(ConsentDecision.KIND_DENY_ONCE) },
                                onDeny24h = { viewModel.submit(ConsentDecision.KIND_DENY_24H) },
                                onDenyPermanent = { viewModel.submit(ConsentDecision.KIND_DENY_PERMANENT) },
                            ),
                        )

                        is ConsentUiState.Error -> ConsentError(
                            expired = s.expired,
                            onDismiss = { finishWith(granted = false) },
                        )

                        is ConsentUiState.Finished -> {
                            MindlayerLog.i(TAG, "Consent finished: ${s.reason}")
                            finishWith(granted = s.granted)
                        }
                    }
                }
            }
        }
    }

    private fun onApproveTapped() {
        // F-029: gate the grant behind a device-presence check.
        authenticator.authenticate(SensitiveAction.APPROVE_CALLER) { granted, _, _ ->
            if (granted) {
                viewModel.submit(ConsentDecision.KIND_GRANT)
            }
            // On biometric failure/cancel we leave the prompt up so the user
            // can retry or choose Deny.
        }
    }

    private fun finishWith(granted: Boolean) {
        if (finished) return
        finished = true
        setResult(if (granted) RESULT_OK else RESULT_CANCELED)
        finish()
    }

    private companion object {
        private const val TAG = "ConsentActivity"
    }
}
