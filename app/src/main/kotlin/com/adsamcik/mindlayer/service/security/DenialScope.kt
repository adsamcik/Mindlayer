package com.adsamcik.mindlayer.service.security

/**
 * The user-chosen scope of a [DeniedEntry] decision.
 *
 * See `docs/architecture/CONSENT_ARCHITECTURE.md § Denial semantics`.
 *
 * - [CERT_PAIR] — denial applies only to the specific
 *   `(packageName, signingCertSha256)` tuple. A cert rotation creates a
 *   fresh consent request. Used by the "Deny for 24 hours" variant.
 * - [PACKAGE_WIDE] — denial applies to **any** signing certificate under
 *   the package name. Cert rotation does NOT bypass a package-wide block.
 *   Used by the "Block permanently" variant — user intent is "block this
 *   app", not "block this specific build of this app".
 *
 * Mixed scope semantics:
 *  - A package may have at most one denial row at a time. A "Block
 *    permanently" supersedes any prior "Deny for 24 hours" for that
 *    package.
 *  - An [AllowlistStore.approve] call clears any matching denial under
 *    `CERT_PAIR` scope; it does NOT clear a `PACKAGE_WIDE` denial — the
 *    user must explicitly unblock from the dashboard's "Blocked apps"
 *    list first. (Approving a permanently-blocked package without
 *    unblocking first would be confusing and is treated as a programmer
 *    error.)
 */
enum class DenialScope {
    /**
     * Denial keyed by `(packageName, signingCertSha256)` pair. Cert
     * rotation does not bypass — the new cert creates a fresh
     * `ConsentChallenge` and goes through the consent flow.
     */
    CERT_PAIR,

    /**
     * Denial keyed by `packageName` only. Any cert under this package is
     * denied. Cert rotation **cannot** bypass this scope. This is the
     * scope of "Block permanently" decisions; the user is asserting "I
     * don't trust this app, regardless of which signing key it uses."
     */
    PACKAGE_WIDE,
}
