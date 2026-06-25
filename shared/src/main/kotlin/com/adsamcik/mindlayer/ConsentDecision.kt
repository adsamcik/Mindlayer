package com.adsamcik.mindlayer

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * The user's choice in the Mindlayer consent activity.
 *
 * Passed by [ConsentActivity] to [IMindlayerService.completeConsent] when
 * the user clicks Approve or one of the three Deny variants. The server
 * consumes the matching [ConsentChallenge.nonce] and writes the
 * corresponding row to `entries.json` (for [KIND_GRANT]),
 * `denied.json` (for [KIND_DENY_24H] / [KIND_DENY_PERMANENT]), or just
 * increments the dismiss counter in `consent_attempts.json` (for
 * [KIND_DENY_ONCE]).
 *
 * # Wire stability
 *
 * Per `docs/architecture/AIDL_STABILITY.md`: parcelables are wire-frozen once shipped.
 * [schemaVersion] is the first field. The integer [kind] codes are
 * wire-stable and append-only — once allocated, never reuse.
 *
 * @property schemaVersion Wire-stable. Currently `1`.
 * @property kind One of [KIND_GRANT], [KIND_DENY_ONCE], [KIND_DENY_24H],
 *   [KIND_DENY_PERMANENT]. Any other value is rejected by the server.
 * @property extensionsJson Reserved forward-compatibility envelope.
 *   `null` in v1.
 */
@Parcelize
data class ConsentDecision(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val kind: Int,
    val extensionsJson: String? = null,
) : Parcelable {

    override fun toString(): String =
        "ConsentDecision(kind=${nameOfKind(kind)})"

    companion object {
        /** Current parcelable schema version. Wire-stable. */
        const val CURRENT_SCHEMA_VERSION: Int = 1

        // ---- kind values (wire-stable, append-only) -------------------------

        /**
         * User approved. Server writes an [AllowlistEntry] (tier inferred
         * from the caller's cert vs. Mindlayer's own cert) and clears any
         * matching denial / dismiss tracking.
         */
        const val KIND_GRANT: Int = 1

        /**
         * User dismissed without committing to a persistent denial ("Not
         * now" / back button / swipe). No row written to `denied.json`.
         * `consent_attempts.json` dismiss counter is incremented;
         * escalation to silent-cooldown kicks in after the threshold.
         */
        const val KIND_DENY_ONCE: Int = 2

        /**
         * User explicitly denied for 24 hours. Server writes a temporary
         * `DeniedEntry` scoped to the specific `(pkg, cert)` pair with
         * `expiresAtMs = now + 24h, permanent = false`. A subsequent
         * `requestConsentChallenge` from the same package + cert returns
         * the deny without showing UI until expiry.
         */
        const val KIND_DENY_24H: Int = 3

        /**
         * User explicitly blocked the package permanently. Server writes
         * a `DeniedEntry` with `permanent = true` and `scope = PACKAGE_WIDE`
         * — the block applies to **any** future cert under the package,
         * so cert rotation cannot bypass it. User unblocks via the
         * dashboard's "Blocked apps" list.
         */
        const val KIND_DENY_PERMANENT: Int = 4

        /** Symbolic name for a kind value, suitable for logs. */
        fun nameOfKind(kind: Int): String = when (kind) {
            KIND_GRANT -> "GRANT"
            KIND_DENY_ONCE -> "DENY_ONCE"
            KIND_DENY_24H -> "DENY_24H"
            KIND_DENY_PERMANENT -> "DENY_PERMANENT"
            else -> "UNKNOWN($kind)"
        }
    }
}
