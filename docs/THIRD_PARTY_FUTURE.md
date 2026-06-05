# Third-Party Caller Future Work

> **Status — reconciled for the v0.10 consent architecture.**
>
> The original Phase-0 plan to "open up" Mindlayer has mostly arrived on
> `feat/consent-architecture`: any installed app can bind, and access is gated
> by a Mindlayer-owned per-app user-consent flow. The authoritative current
> design is [`CONSENT_ARCHITECTURE.md`](CONSENT_ARCHITECTURE.md).
>
> This document now tracks what remains future-facing and records which earlier
> ideas were intentionally retired. Do not use the pre-consent-era plan to revive
> `BIND_ML_SERVICE`, trusted signer lists, dashboard pending approvals, or trust
> tiers.

## Current shipped posture

Mindlayer is no longer first-party-only at the Binder layer:

- `MindlayerMlService` is exported with **no** `BIND_ML_SERVICE` permission.
  The app and SDK manifests do not declare/request that permission.
- Any installed app can bind, but only two methods are reachable before consent:
  `requestConsentChallenge()` and coarse `ping()`.
- All real AIDL methods run `ServiceBinder.authorizeCall()` and require an
  approved `(packageName, signingCertSha256)` allowlist entry.
- An unapproved caller receives `CONSENT_REQUIRED = 6005` and must use
  `MindlayerConsent.requestConsent(context)` to launch the consent-Intent flow.
- An explicitly denied caller receives `CONSENT_DENIED = 6006` until a temporary
  denial lapses or the user unblocks the package.
- Approved callers all share the same `RateLimiter` and `IpcInputValidator`
  limits. There is no first-party / third-party trust tier.

## What arrived from the old plan

The old incremental plan had one large one-way door: let apps outside the
first-party signing set bind. The consent-architecture PR takes that door and
ships the surrounding safety rails in one change:

1. **Open bind:** the manifest permission is removed rather than relaxed to
   `normal`.
2. **User consent as the trust boundary:** the old dashboard pending-approval
   inbox is replaced by `requestConsentChallenge()` + Mindlayer-owned
   `ConsentActivity`.
3. **Server-side caller capture:** Binder UID/package/cert are captured before
   the Activity is launched; the client cannot self-assert identity.
4. **Denial controls:** users can dismiss, deny for 24 hours, or permanently
   block a package from the dashboard's **Blocked apps** list.
5. **No Play policy footgun:** removing the pending inbox also removes the need
   for `QUERY_ALL_PACKAGES`.

## Still future-facing

These items are not shipped by the consent-architecture PR. They remain valid
follow-ups only if product needs justify them:

### Usage-monitoring notifications

The service records metadata-only usage today. A planned follow-up may compute a
rolling per-UID load score and notify the user when one approved app dominates
on-device AI workload or appears to affect battery materially. The notification
should deep-link to per-app management with **Revoke** and **Block permanently**
actions. It should not silently throttle or revoke; the user remains the trust
authority.

### Richer per-app management UX

The dashboard now has approved and blocked app concepts. Future UX can add more
context around install source, recent call counts, token volume, or inference
time if that data is already collected metadata-only. Do not add telemetry,
network calls, or prompt/output persistence to support this UI.

### SDK ergonomics beyond consent

The shipped consent entry point is
`MindlayerConsent.requestConsent(context): ConsentRequestResult`. A full
migration of `connect`, `createSession`, `getStatus`, and the rest of the SDK
control surface to Result types is deferred to a follow-up PR.

### Capability scoping, only if a future feature needs it

Every approved caller currently gets the same service capabilities. If a future
feature genuinely should not be available to all consenting callers, introduce a
specific capability model for that feature and document it in
`AUTHORIZATION.md`. Do not reintroduce broad first-party / third-party tiers.

## Retired ideas from the old plan

The following ideas were part of the historical plan but are now rejected or
obsolete:

- **Relax `BIND_ML_SERVICE` to `normal`.** The permission is gone entirely; the
  manifest layer is not the trust boundary.
- **Trusted signer arrays / first-party seed lists.** The service no longer
  predicts trusted callers by certificate. The user approves concrete apps.
- **Dashboard pending approvals.** There is no `pending.json` inbox and no
  dashboard **Approve** button for first connect.
- **Trust tiers and quota multipliers.** Static `FIRST_PARTY` / `THIRD_PARTY`
  budgets were explicitly rejected. Approved apps share one rate-limit class.
- **Stricter validator budgets for "unknown third-party" callers.** Unknown
  callers cannot reach real methods; approved callers share the same
  `IpcInputValidator` bounds.
- **Tier-specific prompt-injection scoring.** Input validation remains uniform;
  do not add a special third-party-only model-policy layer without a new design.

## Migration implications

- Existing approved allowlist entries survive upgrade.
- Legacy pending entries are discarded; clients must use the consent-Intent
  flow instead of asking users to open the dashboard.
- Re-signed apps lose access until the user approves the new signing
  certificate through the normal consent flow.
- Permanently blocked packages stay blocked across signing-cert rotation and
  must be unblocked explicitly by the user.
