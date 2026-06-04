package com.adsamcik.mindlayer.service.security

/**
 * Trust tier assigned to an [AllowlistEntry] at user-consent time. Drives
 * per-tier [RateLimiter] budgets and [IpcInputValidator] byte caps. Does
 * NOT gate access — every approved caller can call every AIDL method;
 * the tier only affects how much / how often / with what payloads.
 *
 * See `docs/CONSENT_ARCHITECTURE.md § Trust tiers`.
 *
 * Tier assignment rules at `completeConsent(GRANT)` time:
 *  - [FIRST_PARTY] if the caller's signing certificate matches Mindlayer's
 *    own APK signing certificate (computed once at startup from
 *    `Process.myUid()`'s `PackageInfo`).
 *  - [FIRST_PARTY] for entries migrated from the pre-v0.10 schema — those
 *    rows were seeded from the legacy `FIRST_PARTY_ALLOWLIST_SEEDS`
 *    constant, so by definition all were first-party.
 *  - [THIRD_PARTY] otherwise.
 *
 * The dashboard exposes a "Promote to first-party" action (third-party →
 * first-party) for users who want to grant higher budgets to a trusted
 * third-party app. There is no demote action — first-party-to-third-party
 * weakens trust and should require an explicit revoke + re-consent so the
 * user actively re-evaluates.
 */
enum class TrustTier {
    /**
     * Generous budgets matching pre-v0.10 first-party-only operation.
     * Default RPM 60, concurrent 4, validator caps at the historical
     * `MAX_TOOLS_JSON_LEN` (256 KB) / `MAX_TEXT_CONTENT_LEN` (256 KB) /
     * `MAX_HISTORY_TURNS` (64) / `MAX_SESSION_EXPIRATION_MS` (90 days).
     * Prompt-injection scoring is **bypassed** for first-party callers
     * (they are trusted not to attempt injection).
     */
    FIRST_PARTY,

    /**
     * Tightened budgets for unknown third-party callers. Default RPM 15,
     * concurrent 1, validator caps reduced: 16 KB tools JSON / 32 KB text /
     * 16 history turns / 7 days session expiration. Prompt-injection
     * scoring is **applied** for third-party callers.
     */
    THIRD_PARTY,
}
