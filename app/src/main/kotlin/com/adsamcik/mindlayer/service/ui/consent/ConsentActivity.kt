package com.adsamcik.mindlayer.service.ui.consent

import android.app.Activity
import android.os.Bundle
import com.adsamcik.mindlayer.service.ServiceBinder
import com.adsamcik.mindlayer.service.logging.MindlayerLog

/**
 * Mindlayer's user-consent screen for the v0.10 consent-Intent flow.
 *
 * **Phase 3B: minimal placeholder.** The full Compose UI (app label, cert
 * hash, install-source badge, cert-rotation banner, biometric Approve +
 * three-option Deny) lands in Phase 4. This stub exists so the
 * `PendingIntent` minted by `ServiceBinder.requestConsentChallenge` has a
 * resolvable target component. It currently reads the nonce and finishes
 * `RESULT_CANCELED` — i.e. it can never grant consent yet, which is the
 * safe default while the UI is built out.
 *
 * Launched only by an explicit, Mindlayer-minted `PendingIntent` carrying
 * the consent nonce in both the Intent data (`mindlayer-consent://<nonce>`)
 * and [ServiceBinder.EXTRA_CONSENT_NONCE]. The activity will (Phase 4) bind
 * to `:ml` and call `lookupChallenge(nonce)` / `completeConsent(nonce, …)`;
 * both enforce self-UID server-side, so the nonce in the Intent is the only
 * capability an external launcher conveys.
 *
 * See `docs/CONSENT_ARCHITECTURE.md § ConsentActivity` and
 * `.github/instructions/security.instructions.md § ConsentActivity
 * invariants`.
 */
class ConsentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Phase 4 will add: window.setHideOverlayWindows(true),
        // FLAG_SECURE, opaque branded Compose UI, lookupChallenge +
        // completeConsent wiring, biometric gate (F-029).

        val nonce = intent?.getStringExtra(ServiceBinder.EXTRA_CONSENT_NONCE)
        MindlayerLog.i(
            TAG,
            "ConsentActivity launched (nonce=${nonce?.take(8)}…); " +
                "UI not yet implemented (Phase 4) — cancelling.",
        )

        // Safe default until the UI is built: never grant.
        setResult(RESULT_CANCELED)
        finish()
    }

    private companion object {
        private const val TAG = "ConsentActivity"
    }
}
