package com.adsamcik.mindlayer

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Server-pinned identity bound to a [ConsentChallenge.nonce].
 *
 * Returned by [IMindlayerService.lookupChallenge]. The Mindlayer consent
 * activity calls `lookupChallenge(nonce)` after extracting the nonce from
 * its launching Intent, and uses the returned identity to populate the
 * approval UI (app label, signing cert SHA-256, install source, optional
 * cert-rotation banner).
 *
 * The values are pinned server-side at [IMindlayerService.requestConsentChallenge]
 * time using real Binder-layer caller identity, then locked until the
 * nonce is consumed or expires. They are NEVER recomputed from
 * Activity-layer APIs — `getCallingActivity()` / `Binder.getCallingUid()`
 * inside an Activity's `onCreate` do not carry verifiable caller identity.
 *
 * # Wire stability
 *
 * Per `docs/AIDL_STABILITY.md`: parcelables are wire-frozen once shipped.
 * [schemaVersion] is the first field. Adding fields later is a wire break —
 * use [extensionsJson] for forward-compatible extensions.
 *
 * @property schemaVersion Wire-stable. Currently `1`.
 * @property packageName Caller package, resolved from `getPackagesForUid`.
 * @property displayName User-visible application label, sanitised per F-030
 *   (NFKC normalised, Cf/Cc/Co/Cn codepoints stripped, capped at 64 chars).
 *   May be `null` if PackageManager resolution failed or sanitisation
 *   stripped the entire string — fall back to [packageName] for display.
 * @property signingCertSha256 Lowercase hex SHA-256 of the caller's current
 *   signing certificate. For rotated single-signer apks this is the
 *   tail of `signingCertificateHistory`. For multi-signer apks this is
 *   the deterministic two-stage hash described in
 *   `CallerVerifier.certificateSha256`.
 * @property installSource Package name of the installer (e.g.
 *   `"com.android.vending"` for Play, `"com.android.shell"` for adb
 *   sideload, `"com.aurora.store"` for Aurora). `null` if unresolved or
 *   pre-API-30 limited. UI uses this to badge "Installed from Play"
 *   vs. "Side-loaded" so the user has supply-chain context.
 * @property previousSigSha256 If the package was previously approved
 *   under a different signing cert (F-032 cert rotation), the prior
 *   cert hash is carried here so the consent UI can render the red
 *   rotation banner. `null` for first-time consent or for callers
 *   whose cert is unchanged.
 * @property expiresAtMs Wall-clock ms-since-epoch after which this
 *   identity binding is no longer valid (mirrors
 *   [ConsentChallenge.expiresAtMs]).
 * @property extensionsJson Reserved forward-compatibility envelope.
 *   `null` in v1.
 */
@Parcelize
data class ConsentIdentity(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val packageName: String,
    val displayName: String?,
    val signingCertSha256: String,
    val installSource: String?,
    val previousSigSha256: String?,
    val expiresAtMs: Long,
    val extensionsJson: String? = null,
) : Parcelable {

    override fun toString(): String =
        "ConsentIdentity(pkg=$packageName, " +
            "sig=${signingCertSha256.take(8)}…, " +
            "label=${displayName ?: "<none>"}, " +
            "source=${installSource ?: "<unknown>"}, " +
            "rotation=${previousSigSha256 != null})"

    companion object {
        /** Current parcelable schema version. Wire-stable. */
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}
