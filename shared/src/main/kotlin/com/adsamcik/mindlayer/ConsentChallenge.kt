package com.adsamcik.mindlayer

import android.app.PendingIntent
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * The response from [IMindlayerService.requestConsentChallenge].
 *
 * Issued by the `:ml` service when an external (non-allowlisted) caller wants
 * to request user consent. The caller fires [consentIntent] via
 * `startActivityForResult` to launch the Mindlayer-owned consent UI. The
 * [nonce] is the opaque single-use token that ties the activity invocation
 * back to the server-recorded identity. Clients should treat [nonce] as
 * opaque and never inspect it.
 *
 * # Identity capture
 *
 * Caller identity (package, signing cert, label) is recorded server-side
 * at the moment [IMindlayerService.requestConsentChallenge] is invoked,
 * using the real `Binder.getCallingUid()` available inside that AIDL
 * transaction. The activity does NOT re-derive identity from the launching
 * Intent — instead it calls [IMindlayerService.lookupChallenge] with this
 * nonce to retrieve the server-pinned identity. This is the only path that
 * cryptographically binds the consent decision to the actual calling app;
 * relying on `getCallingActivity()` or `Binder.getCallingUid()` inside the
 * activity's lifecycle is unreliable.
 *
 * # Lifetime
 *
 * The nonce is valid for [ConsentChallenge.TTL_MS_DEFAULT] (5 min) after
 * issuance and is **single-use**. Re-firing [consentIntent] after expiry or
 * after a successful `completeConsent` call will land in `ConsentActivity`,
 * which immediately finishes with `RESULT_CANCELED`. Clients that need to
 * retry must call [IMindlayerService.requestConsentChallenge] again.
 *
 * # Wire stability
 *
 * Per `docs/architecture/AIDL_STABILITY.md`: parcelables are wire-frozen once shipped.
 * [schemaVersion] is the first field. Adding fields later is a wire break —
 * use [extensionsJson] for forward-compatible extensions.
 *
 * @property schemaVersion Wire-stable. Currently `1`.
 * @property nonce Server-issued URL-safe Base64 of 256 random bits.
 *   Opaque to the client. Single-use, TTL-bounded.
 * @property consentIntent A [PendingIntent] minted by Mindlayer that
 *   launches [ConsentActivity] with [nonce] embedded as an extra. Fire
 *   via `startActivityForResult(intent.intentSender, ...)`. The
 *   PendingIntent is immutable per Android security best practices.
 * @property expiresAtMs Wall-clock ms-since-epoch after which the server
 *   will refuse `lookupChallenge`/`completeConsent` for this nonce.
 *   Surfaced so callers can implement local timeouts without polling.
 * @property extensionsJson Reserved forward-compatibility envelope.
 *   `null` in v1. Unknown keys are tolerated; the parser MUST NOT
 *   reject on unknown fields.
 */
@Parcelize
data class ConsentChallenge(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val nonce: String,
    val consentIntent: PendingIntent,
    val expiresAtMs: Long,
    val extensionsJson: String? = null,
) : Parcelable {

    override fun toString(): String =
        "ConsentChallenge(nonce=${nonce.take(8)}…, expiresAtMs=$expiresAtMs)"

    companion object {
        /** Current parcelable schema version. Wire-stable. */
        const val CURRENT_SCHEMA_VERSION: Int = 1

        /** Default TTL between issuance and expiry (5 minutes). */
        const val TTL_MS_DEFAULT: Long = 5 * 60 * 1000L
    }
}
