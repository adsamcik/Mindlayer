---
applyTo: "app/src/main/kotlin/com/adsamcik/mindlayer/service/security/**, app/src/main/kotlin/com/adsamcik/mindlayer/service/ServiceBinder.kt, app/src/main/kotlin/com/adsamcik/mindlayer/service/MindlayerMlService.kt, app/src/main/kotlin/com/adsamcik/mindlayer/service/ui/consent/**, app/src/main/AndroidManifest.xml, sdk/src/main/AndroidManifest.xml"
description: "Authorization invariants — applies to ServiceBinder, MlService, security/, consent UI, and both manifests"
---

<!-- context-init:managed -->

> **⚠️ Branch notice — `feat/consent-architecture` migration in flight.**
>
> The invariants below describe the **target consent-architecture model**. Code that
> still implements the legacy `signature|knownSigner` permission + `recordPending`
> inbox is being deleted phase-by-phase in this PR. See
> [`docs/CONSENT_ARCHITECTURE.md`](../../docs/CONSENT_ARCHITECTURE.md) for the full design
> and the [§ Legacy invariants during migration](#legacy-invariants-during-migration)
> section at the bottom of this file for rules that still apply to un-deleted legacy
> code paths.

## Authorization invariants (do not weaken)

Every external AIDL entry point runs `authorizeCall()` before any work. The
gate is identity → allowlist → rate-limit → ownership. **All four stages run
for every external caller, every time.** See `docs/CONSENT_ARCHITECTURE.md` for
the full data flow and rationale.

> Privacy/offline/security product invariants (no network, no telemetry, RAM-only
> media, model integrity, input validation, ordered binder-death teardown) are
> documented in `.github/instructions/privacy-offline.instructions.md` and apply
> repo-wide. The rules below are the authorization-specific subset.

```
authorizeCall(sessionId? = null):
    uid = Binder.getCallingUid()
    if (uid == Process.myUid()) return SELF_IDENTITY            // dashboard bypass
    identity = CallerVerifier.identifyCaller(ctx, uid)
        ?: throw SecurityException("Unknown caller")
    entry = allowlistStore.lookupAllowed(identity.pkg, identity.sig)
        ?: throw SecurityException(
            "App ${identity.pkg} requires user consent",
            errorCode = MindlayerErrorCode.CONSENT_REQUIRED
        )
    if (!rateLimiter.tryAcquire(uid, tier = entry.trustTier))
        throw SecurityException("Rate limit exceeded")
    sessionId?.let { requireOwnership(it, uid) }
    return identity.copy(trustTier = entry.trustTier)
```

### Don't

- **Do NOT add a `recordPending` equivalent or any "auto-write pending row on
  bind rejection" mechanism.** Pending state is ephemeral and lives only inside
  `ConsentChallengeStore` for the duration of an in-flight consent flow. Spam
  is bounded because nothing happens unless the user goes through `ConsentActivity`.
- **Do NOT cache `lookupAllowed` results in memory.** The dashboard runs in
  the main process; the gate runs in `:ml`. The hot path **must** re-read
  `entries.json` on every call. Disk cost is bounded by the tier-aware rate
  limiter (60 RPM first-party / 15 RPM third-party default).
- **Do NOT use `SharedPreferences`** for `entries.json`, `denied.json`,
  `challenges.json`, or `consent_attempts.json`. `MODE_MULTI_PROCESS` is
  deprecated and racy. All cross-process state goes through
  `filesDir + FileLock + atomic-rename`, HMAC-signed envelopes (`AllowlistStore`,
  `ConsentChallengeStore`, `ConsentAttemptStore`).
- **Do NOT call `AllowlistStore.approveDirect()` from production code.** It
  bypasses the F-031 live cert re-verification. It is `@VisibleForTesting internal`
  and stays that way. Production approvals (from `completeConsent(nonce, GRANT)`)
  must call `approve(context, pkg, expectedSig, trustTier, ...)` which runs the
  cert revalidation under the file lock.
- **Do NOT add a manual "Add app by hand" UI.** Consent must originate from a
  client-initiated `requestConsentChallenge()` call so the cert was verified
  via real `Binder.getCallingUid()`.
- **Do NOT bypass `authorizeCall` for "internal helpers" that take a `Context`.**
  If a method can be reached from AIDL, it must be gated.
- **Do NOT trust `getCallingActivity()` / `getCallingPackage()` / `Binder.getCallingUid()`
  inside `ConsentActivity.onCreate`** to identify the launching app. Those APIs
  do not carry verifiable caller identity in an Activity lifecycle context.
  Identity is captured server-side at `requestConsentChallenge()` time (where
  `Binder.getCallingUid()` IS reliable) and looked up by nonce inside
  `ConsentActivity`.
- **Do NOT widen the pre-consent API surface.** Only `requestConsentChallenge()`
  and a coarse `ping()` are reachable without an `entries.json` row. Every
  new AIDL method must run `authorizeCall()`.
- **Do NOT remove or weaken F-029 (biometric on Approve), F-030 (label
  sanitisation), F-031 (live cert re-verify), or F-032 (cert-rotation banner).**
  These are user-visible safety properties that the consent flow inherits and
  must preserve.

### Do

- Use `CallerIdentity` (returned by `authorizeCall`) as the UID-equivalent
  token everywhere downstream. Pass it to
  `InferenceOrchestrator.createSession(config, ownerToken = identity)` so
  binder-death tear-down works.
- Pass `trustTier` (read from the `AllowlistEntry`) to `IpcInputValidator`
  via `CallerProfile` so byte budgets are tier-aware. First-party callers
  keep current generous limits; third-party get tightened defaults.
- Persist the *current* signing cert per `signingCertificateHistory.last()`.
  Rotated certs invalidate prior approvals on purpose.
- Serialise allowlist writes with `withFileLock { … }` and atomic-rename
  via `atomicWrite`.
- When introducing any new persisted security field (denial flags, attempt
  counters, trust tiers), include it in the canonical HMAC payload at the
  same time. Bump the envelope `version`.

## Consent challenge invariants

`ConsentChallengeStore` is the only legitimate path for an external caller to
land an entry in `entries.json`.

### Don't

- Do NOT issue challenges without rate-limit gating. `requestConsentChallenge()`
  enforces 10/hour/UID by default; never raise this without a documented
  spam-defence story.
- Do NOT reuse nonces. A challenge is single-use: `lookupChallenge` may be
  called multiple times (idempotent read) but `completeConsent` consumes
  the nonce.
- Do NOT skip the per-`(pkg, sig)` dismiss counter in `ConsentAttemptStore`.
  It is the escalation backbone that prevents consent fatigue.
- Do NOT call `lookupChallenge` / `completeConsent` from anywhere other than
  `ConsentActivity`. Both methods enforce `Binder.getCallingUid() == Process.myUid()`.
- Do NOT persist `PendingIntent`s server-side — they are mintable on demand
  in `requestConsentChallenge` from `nonce` alone.

### Do

- Use `SecureRandom` for nonces; 256 bits minimum.
- Bound the in-memory challenge map; prune expired entries on every write.
- Treat `ConsentChallengeStore`'s disk persistence as a soft restart helper,
  not a durable store. Reboots and OS-level kills are expected to clear it.
  Challenge TTL (default 5 min) is what callers actually rely on.

## `ConsentActivity` invariants

The consent UI is the user's sole opportunity to grant a real privilege to an
external app. Treat it as a security boundary, not just a screen.

### Don't

- Do NOT make `ConsentActivity` translucent. It must be opaque so background
  app content cannot blend with the approval surface.
- Do NOT add an intent-filter. The activity is invoked only by an explicit
  `Intent` from a server-issued `PendingIntent`.
- Do NOT use `launchMode="singleTask"` or `singleInstance` — they complicate
  result routing and per-nonce identity. Default launch mode is correct: each
  consent request gets its own activity instance with its own nonce.
- Do NOT render the cert hash in a way that could be confused with the app
  label (different font, monospace, prefixed with `SHA-256:`).
- Do NOT auto-dismiss on any UI signal except an explicit user choice. The
  rubber-duck pass specifically flagged "swipe-away as approval" as a
  catastrophic UX bug.

### Do

- Call `window.setHideOverlayWindows(true)` in `onCreate`. The manifest already
  declares `HIDE_OVERLAY_WINDOWS` permission for exactly this reason.
- Add `WindowManager.LayoutParams.FLAG_SECURE` to suppress screenshots of the
  approval UI.
- Display the install source (`PackageManager.getInstallSourceInfo`) so users
  can tell a Play install from a sideload.
- Render the cert-rotation banner (red, "this app's signing certificate
  changed since you previously interacted with it") whenever
  `ConsentIdentity.previousSigSha256 != null` (F-032).
- Treat back / swipe / home as `DENY_ONCE` (dismiss). Increment the attempt
  counter; do not write to `denied.json`.

## Trust tiers

- `AllowlistEntry.trustTier` is set once at approval time and never
  auto-downgraded. The dashboard exposes a "Promote to first-party" action
  (third-party → first-party) but no demote action — demotion requires full
  revoke + re-consent so the user actively re-evaluates.
- Tier assignment at approval time:
  - `FIRST_PARTY` if `identity.signingCertSha256` matches the Mindlayer APK's
    own signing cert (computed at startup from `Process.myUid()`'s
    `PackageInfo`).
  - `FIRST_PARTY` for migrated entries (pre-existing rows in `entries.json`
    seeded from the legacy `FIRST_PARTY_ALLOWLIST_SEEDS`).
  - `THIRD_PARTY` otherwise.
- Tier-aware defaults are encoded in `RateLimiter` and `IpcInputValidator`.
  See `docs/CONSENT_ARCHITECTURE.md § Trust tiers` for the actual numbers.

## Denial semantics

Three persisted denial scopes:

| User choice | `DeniedEntry.scope` | `DeniedEntry.signingCertSha256` | `permanent` |
|---|---|---|---|
| "Not now" | _(no entry; dismiss counter only)_ | _(n/a)_ | _(n/a)_ |
| "Deny for 24 hours" | `CERT_PAIR` | the specific sig | `false` |
| "Block permanently" | `PACKAGE_WIDE` | `null` | `true` |

Package-wide permanent denial **cannot be bypassed by cert rotation**. A
permanently-blocked package gets `ConsentActivity` returning `RESULT_CANCELED`
without UI for any cert. User unblocks via the dashboard's "Blocked apps" list.

## Rate limiting

- Tier-aware token bucket + concurrent semaphore per UID
  (`RateLimiter.kt`). Defaults: first-party 60 RPM / 4 concurrent, third-party
  15 RPM / 1 concurrent. Pre-consent callers cannot reach `tryAcquire`
  because `authorizeCall` rejects them first.
- `requestConsentChallenge()` has its own per-UID bucket: 10/hour default.
- Pre-consent `ping()` has a 5/min coarse bucket; post-consent `ping()` falls
  into the tier-aware bucket above (or its own 30/min ping-specific bucket,
  whichever ships).
- Brand-new buckets start with `INITIAL_FIRST_CALL_TOKENS = 1.0` so the
  canonical `bindService → onServiceConnected → registerClient` flow
  succeeds without waiting for refill. Don't raise the default; tests pin it.
- The dashboard's self-UID skips the rate limit so its 2 s polling doesn't
  burn external budgets.
- Rate-limit rejection happens **after** identity + allowlist so timing
  doesn't leak allowlist state.

## Session ownership

- Every session is tagged with its creator's `ownerToken` (a `CallerIdentity` for external callers).
- `requireOwnership(sessionId)` rejects calls from any other UID and returns the same error for unknown sessions — don't leak which session IDs exist.
- `closeAllOwnedBy(uid)` is the binder-death tear-down hook.

## Logging

- Log every authorization rejection with `MindlayerLog.w("ServiceBinder", reason, requestId, sessionId)` and a structured `LogRepository` entry — but **never** include the rejected caller's request body, prompt, or any session content.
- Use `Throwable.safeLabel()` when surfacing exceptions thrown by native verification code.
- New consent-flow audit actions emitted via `LogRepository.logSecurityDecision`:
  `consent_requested`, `consent_shown`, `consent_granted`,
  `consent_denied_once`, `consent_denied_temporary`, `consent_denied_permanent`,
  `consent_dismissed`, `consent_biometric_failed`, `consent_cert_mismatch`,
  `consent_nonce_expired`, `consent_cooldown_blocked`,
  `input_rejected_injection_score`, `schema_v3_migration`.

## Legacy invariants during migration

These rules apply to code paths that have not yet been deleted in this PR.
Once the corresponding phase commits, the rule becomes obsolete.

| Legacy rule | Applies until phase | Notes |
|---|---|---|
| Keep `R.array.mindlayer_trusted_client_certs` and `FIRST_PARTY_ALLOWLIST_SEEDS` in sync | Phase 5 | Both deleted in Phase 5. |
| `seedIfEmpty(...)` is intentionally first-launch only | Phase 5 | Deleted in Phase 5. |
| Don't remove or relax the manifest permission | Phase 5 | The permission IS being removed in Phase 5 per `docs/CONSENT_ARCHITECTURE.md`. |
| Debug-only same-cert auto-seeding belongs only in `app/src/debug` | Phase 5 | `DebugAllowlistSeeder` is deleted in Phase 5 (no longer useful when there is no cert array). |
| `recordPending` for unknown callers | Phase 5 | Deleted in Phase 5. Replaced by `ConsentChallengeStore`. |
| `TrustedClientCertParityTest` must pass | Phase 5 | Test deleted in Phase 5. |
