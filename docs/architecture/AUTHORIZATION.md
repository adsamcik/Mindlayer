# Caller Authorization

> **Current model: v0.10 consent architecture.**
>
> The service no longer uses the legacy `signature|knownSigner` manifest
> permission, `QUERY_ALL_PACKAGES`, trusted-certificate arrays, first-party
> seeding, or a dashboard pending-approval inbox. The design rationale and
> migration details live in [`CONSENT_ARCHITECTURE.md`](CONSENT_ARCHITECTURE.md);
> keep this reference aligned with that document whenever authorization changes.

Mindlayer runs as a standalone Android service and exposes on-device LLM
inference over AIDL. The service is exported with **no bind permission**, so any
installed app can bind and obtain the Binder. Binding is not trust: every real
AIDL entry point enforces authorization before doing work.

The current gate is:

1. **Identity verification** — resolve `Binder.getCallingUid()` to one package
   and its current signing-cert SHA-256. Binder identity inside the AIDL
   transaction is authoritative; Activity caller identity is never trusted.
2. **Consent / denial check** — explicit denials reject with
   `CONSENT_DENIED`; missing approvals reject with `CONSENT_REQUIRED`.
3. **Rate limiting** — approved callers share the same per-UID RPM and
   concurrent-inference budgets.
4. **Session ownership** — session-scoped operations (`infer`, `destroy`,
   `cancel`, …) must target a session the caller previously created.

The dashboard runs under Mindlayer's own UID and keeps the self-UID bypass so it
can poll `:ml` over the same AIDL surface without needing user consent.

## TL;DR for client integrators

- Any installed app can bind to `MindlayerMlService`; there is no
  `BIND_ML_SERVICE` permission to request.
- Before consent, only `requestConsentChallenge()` and `ping()` are reachable.
  `ping()` returns only coarse `{ alive, apiVersion }` liveness.
- A real AIDL method called before approval fails with
  `CONSENT_REQUIRED = 6005`. Use
  `MindlayerConsent.requestConsent(context): ConsentRequestResult` and launch
  the returned `Available(intentSender)` in a user-visible flow.
- The consent screen is Mindlayer-owned. `requestConsentChallenge()` captures
  the caller's UID/package/cert server-side, mints a 256-bit single-use nonce
  and immutable one-shot `PendingIntent`, and `ConsentActivity` displays that
  recorded identity.
- Approval requires `BiometricPrompt`. On `GRANT`, the service re-verifies the
  live signing certificate under the allowlist file lock before writing the
  `(packageName, signingCertSha256)` approval. `RESULT_OK` means the next
  bind+AIDL call from that app can succeed.
- Users can choose **Not now**, **Deny for 24 hours**, or **Block permanently**.
  Denied callers receive `CONSENT_DENIED = 6006` until the denial lapses or the
  user unblocks them from the dashboard.
- The full SDK migration of `connect` / `createSession` / `getStatus` to Result
  types is deferred; do not rely on those APIs being fully Result-based yet.

## Default-deny posture

Mindlayer now has one trust boundary: per-app user consent. The app and SDK
manifests do not declare or request `com.adsamcik.mindlayer.permission.BIND_ML_SERVICE`,
and the service declaration intentionally has no `android:permission` attribute.
`QUERY_ALL_PACKAGES`, `mindlayer_trusted_client_certs`, `seedIfEmpty`,
`seedVerified`, and debug first-party cert seeding are removed.

Default-deny still holds because an unapproved Binder can do only two things:

1. call `requestConsentChallenge()` to ask the user for consent; or
2. call `ping()` for coarse service liveness.

Every other method fails closed until an approved allowlist row exists. Existing
approved allowlist entries are preserved during upgrade; legacy `PENDING` /
`pending.json` state is discarded because in-flight consent now lives only in
memory.

## Public authorization error codes

| Code | Value | Meaning |
|---|---:|---|
| `CONSENT_REQUIRED` | 6005 | Caller is identified but lacks an approved `(packageName, signingCertSha256)` row. Start the consent-Intent flow. |
| `CONSENT_DENIED` | 6006 | Caller is explicitly denied, blocked, in cooldown, or cannot be trusted enough to show consent. |
| `INPUT_REJECTED` | 3009 | Request content failed input validation before reaching the engine. This is not a consent state. |

## Components

### `CallerVerifier` (app/src/main/.../security/CallerVerifier.kt)

Pure utility that turns `Binder.getCallingUid()` into a `CallerIdentity`:

```kotlin
data class CallerIdentity(
    val packageName: String,
    val signingCertSha256: String,
    val displayName: String?,
)
```

Rules:

- If the UID maps to more than one package (shared UID), returns `null`.
  Shared-UID callers are refused outright because their identity is ambiguous.
- On API 28+ uses `PackageManager.GET_SIGNING_CERTIFICATES` + `SigningInfo`:
  - **Single signer, rotated**: pins the current signer (the last entry in
    `signingCertificateHistory`). Rotating away from a compromised key
    invalidates prior approvals, which is the point.
  - **Multi-signer APKs**: hashes each cert, sorts the hex digests,
    concatenates with `:`, and hashes the result. Ordering in
    `apkContentsSigners` is not guaranteed, so the two-stage hash makes the
    fingerprint deterministic across platform quirks.
- On API < 28 falls back to the deprecated `GET_SIGNATURES` with the same
  multi-signer normalisation.

### `AllowlistStore` (app/src/main/.../security/AllowlistStore.kt)

File-backed JSON store under `filesDir/mindlayer_allowlist/`:

| File | Contents |
|---|---|
| `entries.json` | Approved callers: `[{pkg, sig, grantedAtMs, displayName?}, …]` |
| `denied.json` | Explicit denials, including cert-pair 24-hour denials and package-wide permanent blocks |
| `consent_attempts.json` | HMAC-signed dismiss/cooldown tracking for repeated **Not now** outcomes |
| `allowlist.lock` | Sidecar file used for a cross-process `FileLock` on writes |

Key invariants:

- `isAllowed(pkg, sig)` **always re-reads from disk**. This is critical: the
  dashboard Compose UI lives in the main process while the AIDL server lives in
  the `:ml` process, so any in-memory cache would go stale after a consent
  grant, revoke, or unblock.
- Writes are serialised across processes with `FileLock` on the sidecar
  `.lock` file, then an atomic `tmp -> rename` replaces the target JSON.
- `approve(context, pkg, expectedSig, displayName)` re-resolves the live signing
  certificate under that file lock before writing. This F-031 check closes the
  time-of-check/time-of-use window between the consent prompt and the stored
  approval.
- Legacy `pending.json` is not part of the live architecture. Upgrade migration
  deletes it; deprecated symbols that may still physically exist are not a live
  approval path.

### `ConsentChallengeStore` (app/src/main/.../security/ConsentChallengeStore.kt)

In-memory challenge table owned by the `:ml` process. Each
`requestConsentChallenge()` call:

1. rate-limits the caller at 10/hour/UID;
2. resolves the caller identity from `Binder.getCallingUid()`;
3. checks explicit denial and dismiss cooldown state;
4. mints a 256-bit URL-safe random nonce;
5. stores the full caller identity, display label, install source, previous
   signer (if any), creation time, and 5-minute expiry; and
6. returns only the nonce plus a one-shot immutable `PendingIntent` to the
   client.

Nonces are single-use and in-memory only. If `:ml` dies before the user acts,
the challenge is lost and the client must request a fresh one.

### `ServiceBinder.authorizeCall()` (app/src/main/.../ServiceBinder.kt)

Called as the first statement of every real AIDL entry point. Sequence:

```kotlin
uid = Binder.getCallingUid()
if (uid == Process.myUid())      -> return SELF_IDENTITY   // dashboard
identity = CallerVerifier.identifyCaller(ctx, uid)
    ?: throw SecurityException(CONSENT_DENIED)
if (allowlistStore.isDenied(identity.pkg, identity.sig)) {
    throw SecurityException(CONSENT_DENIED)
}
if (!allowlistStore.isAllowed(identity.pkg, identity.sig)) {
    throw SecurityException(CONSENT_REQUIRED)
}
if (!rateLimiter.tryAcquire(uid)) throw SecurityException("Rate limit exceeded")
return identity
```

`requestConsentChallenge()` and `ping()` are the only deliberate pre-consent
exceptions. Session-scoped entry points add a second hop after authorization:
`requireOwnership(sessionId)` looks up the session's creator UID in the
`InferenceOrchestrator` and rejects calls from any other UID. Unknown sessions
are also rejected to avoid leaking which session IDs exist to arbitrary callers.

### Open exported service declaration

The service remains exported so external apps can bind, but the manifest carries
no custom bind permission:

```xml
<service
    android:name=".MindlayerMlService"
    android:exported="true"
    android:process=":ml">
    …
</service>
```

This is intentional. Android's manifest permission layer no longer decides who
is trusted; user consent and per-method AIDL checks do. The old API 26–30
`knownSigner` quirk is therefore gone with the permission.

### Rate limiter

`RateLimiter` (per-UID token bucket for RPM + a per-UID semaphore for
concurrent inferences) runs after consent for real methods. All approved callers
receive the same default budget; there are no first-party / third-party trust
tiers or per-tier validator caps.

Pre-consent endpoints have separate small buckets: `requestConsentChallenge()` is
limited to 10/hour/UID, and `ping()` has its own liveness bucket so unapproved
apps cannot fingerprint the service at high frequency.

### Deferred result privacy exception

The normal service invariant is "return model output over IPC, do not persist it
in service storage." Deferred APIs are the documented exception:

- `inferDeferred` persists generated text in the SQLCipher-encrypted
  `mindlayer-deferred.db` until fetch/ack/cancel or expiry.
- `embedBatchDeferred` persists embedding vectors as AES-256-GCM encrypted
  blobs under `cacheDir/embedding-blobs/<uid>/<requestId>.bin`. The blob key is
  derived per UID from the same Keystore-wrapped SQLCipher root key, with
  `mindlayer-embed-blob-v1` authenticated as AAD.
- Deferred rows and encrypted blobs are caller-scoped by UID. Fetch, cancel,
  acknowledge, quota eviction, startup orphan sweep, and expiry all enforce that
  scope before returning or deleting data.
- The retention policy is 24 hours by default (`DeferredStore.DEFAULT_TTL_MS`)
  unless the caller acknowledges/cancels earlier.

## Signing-cert changes

Allowlist approvals pin the caller's current signing certificate. If an app is
re-signed or rotates certificates, the old `(pkg, sig)` approval no longer
matches and real AIDL methods return `CONSENT_REQUIRED`.

The consent prompt can show a cert-rotation warning when the same package has a
previous approval under a different signer. A successful `GRANT` still calls
`AllowlistStore.approve(...)`, which re-checks the live certificate under the
file lock before writing the new row. A mismatch fails closed and the client must
start over with a fresh challenge.

There are no trusted-cert arrays or first-party seed lists to update for
rotation.

## Local sideloading

Production and debug builds use the same consent model. A sideloaded app binds,
receives `CONSENT_REQUIRED` for real methods, launches the consent-Intent flow,
and succeeds only after the user approves the Mindlayer-owned prompt.

Debug builds do not auto-seed apps signed with the Android debug keystore. This
avoids accidentally trusting unrelated debug APKs installed on the same emulator
or device.

### Debug-only "auto-accept all callers" (CI / instrumented tests)

Interactive consent (the ConsentActivity prompt) cannot be driven by a headless
`connectedAndroidTest` run. **Debug builds only** therefore ship a developer
escape hatch that makes `authorizeCall()` treat an *identified, not-user-denied,
but unconsented* caller as approved — skipping the consent prompt. It is
deliberately narrow:

- **Debug-only and physically absent from release.** The toggle is backed by
  `DebugAutoAcceptStore` (a sentinel file under
  `filesDir/debug_auto_accept/enabled`) which exists **only** in
  `app/src/debug`. The release source set compiles a no-op seam
  (`debugAutoAcceptAllEnabled()` → always `false`), and
  `DebugAutoAcceptReleaseAbsenceTest` (wired into the `testReleaseUnitTest`
  filter) asserts the class is not on the release classpath.
- **Narrow effect.** Identity verification, explicit user denials
  (`isDenied`), and the per-UID rate limit all still apply, and the OS-level
  `signature|knownSigner` `BIND_ML_SERVICE` permission is untouched — only apps
  that can already bind are affected.
- **Two ways to flip it**, both writing the same flag:
  - Dashboard → *Allowed apps* card → the debug-only "auto-accept all callers"
    switch (hidden in release).
  - adb / CI, via the DUMP-guarded, debug-manifest-only
    `DebugAutoAcceptReceiver` (only the shell and system hold `DUMP`, so an
    ordinary app on the device cannot trigger it):

    ```
    adb shell am broadcast \
      -n com.adsamcik.mindlayer.service.debug/com.adsamcik.mindlayer.service.security.DebugAutoAcceptReceiver \
      -a com.adsamcik.mindlayer.debug.SET_AUTO_ACCEPT --ez enabled true
    # → Broadcast completed: result=1, data="auto_accept=true"
    # disable again with --ez enabled false
    ```

## The consent flow

```
┌─────────────────┐        ┌─────────────────────┐        ┌───────────────────┐
│   Client app    │        │  Mindlayer :ml svc  │        │ ConsentActivity   │
│ (external UID)  │        │    ServiceBinder    │        │  (main process)   │
└────────┬────────┘        └──────────┬──────────┘        └────────┬──────────┘
         │                            │                            │
   1. bindService()                   │                            │
         │──────────────────────────► │                            │
   2. requestConsentChallenge()       │                            │
         │──────────────────────────► │                            │
         │                            │ Binder UID -> pkg + cert   │
         │                            │ deny/cooldown checks       │
         │                            │ nonce + PendingIntent      │
         │ ConsentRequestResult       │                            │
         │ ◄──────────────────────────│                            │
         │                            │                            │
   3. startActivityForResult /        │                            │
      launch IntentSender             │                            │
         │────────────────────────────────────────────────────────► │
         │                            │                            │ bind as self UID
         │                            │ ◄──────────────────────────│ lookupChallenge(nonce)
         │                            │ identity snapshot          │
         │                            │──────────────────────────► │
         │                            │                            │ show label, cert,
         │                            │                            │ install source
         │                            │                            │
         │                            │                            │ Approve -> biometric
         │                            │ ◄──────────────────────────│ completeConsent(GRANT)
         │                            │ live cert reverify + write │
         │                            │──────────────────────────► │ success
         │ RESULT_OK                  │                            │
         │ ◄────────────────────────────────────────────────────────│
   4. retry bind + real AIDL call     │                            │
         │──────────────────────────► │ authorizeCall() passes     │
```

Key points:

- The client does not tell Mindlayer who it is. The service captures identity
  from Binder when issuing the challenge, and the Activity can only look up that
  server-side record by nonce.
- `ConsentActivity` is exported only so the Mindlayer-created `PendingIntent`
  can launch it. It has no public intent filter. It uses an opaque, branded UI,
  overlay hiding, `FLAG_SECURE`, and obscured-touch filtering.
- **Approve** requires `BiometricPrompt` with device credential fallback.
- **Not now** records a dismiss. After repeated dismisses the caller enters a
  silent cooldown (3 dismisses -> 1 hour, 4+ -> 24 hours) before the prompt can
  be shown again.
- **Deny for 24 hours** writes a cert-pair denial that expires after 24 hours.
- **Block permanently** writes a package-wide denial (`signingCertSha256 = null`)
  so cert rotation cannot bypass the user's block. The user must unblock it from
  the dashboard's **Blocked apps** list.

## Revocation and blocking

`AllowlistStore.revoke(pkg)` removes approved entries from `entries.json`. The
next `isAllowed()` check (hot path, re-reads disk) returns `false`, so real AIDL
methods return `CONSENT_REQUIRED` again. Already in-flight AIDL calls are not
interrupted; revoke takes effect on the next call.

Blocking is stronger than revoke. A package-wide permanent denial causes future
consent attempts to return `CONSENT_DENIED` without showing UI until the user
unblocks the package from the dashboard. A 24-hour denial applies only to the
observed `(packageName, signingCertSha256)` pair and expires automatically.

If you need hard revocation (kill in-flight requests too), call
`orchestrator.closeAllOwnedBy(uid)` alongside `revoke()`. This is not currently
exposed in the dashboard UI — add it only if the threat model requires it.

## Failure modes and user-visible behaviour

| Situation | Behaviour |
|---|---|
| Mindlayer not installed / service disabled | `bindService` fails or returns null binding; SDK reports service unavailable. |
| Client calls `ping()` before consent | Coarse `{ alive, apiVersion }` only; no engine state, diagnostics, or uptime. |
| Client calls `requestConsentChallenge()` while not denied | Returns a single-use challenge `PendingIntent` unless the 10/hour/UID bucket is exhausted. |
| Client calls a real AIDL method without consent | `SecurityException` with `CONSENT_REQUIRED = 6005`; no pending dashboard entry is written. |
| Client is in dismiss cooldown or has an active 24-hour / permanent denial | `CONSENT_DENIED = 6006`; the prompt is not shown until the denial lapses or the user unblocks. |
| Shared-UID caller | `CallerVerifier` returns `null`; request is rejected with `CONSENT_DENIED` and no consent prompt. |
| Unknown package / `PackageManager` error | Request is rejected with `CONSENT_DENIED`; fail closed. |
| Nonce expired or already consumed | `ConsentActivity` finishes `RESULT_CANCELED`; client must request a fresh challenge. |
| User dismisses consent UI (back / swipe / home) | Treated as **Not now**; dismiss count increments and `RESULT_CANCELED` is returned. |
| User taps Approve but biometric fails / no enrollment | UI shows an error and can retry; repeated failures are treated as dismisses. |
| Caller cert changes between challenge and grant | Live revalidation fails under the file lock; consent commit fails closed and the client must request a fresh challenge. |
| Approved caller rate limit exhausted | AIDL call rejects with `SecurityException("Rate limit exceeded …")`. |
| Session-scoped call from non-owner UID | `SecurityException("Session not found or not owned …")`. |

## What is *not* currently in place

These are intentional non-goals or follow-ups; do not document them as live
features:

- **No dashboard pending-approval inbox.** The old Approve-from-dashboard flow is
  gone. In-flight consent is nonce-scoped and in-memory.
- **No first-party cert seeding.** `seedIfEmpty`, `seedVerified`, trusted-cert
  arrays, and debug allowlist seeders are removed.
- **No trust tiers.** Approved callers share one rate-limit class and one set of
  `IpcInputValidator` bounds. Heavy usage should be surfaced to the user, not
  hidden behind static per-app budgets.
- **Usage-monitoring notifications are planned, not shipped here.** The service
  already records metadata-only usage; a follow-up may notify the user when one
  approved app dominates on-device AI workload.
- **Full SDK Result migration is deferred.** The shipped consent entry point is
  `MindlayerConsent.requestConsent(context): ConsentRequestResult`; the rest of
  the SDK control surface is not fully Result-based in this change.
- **No time-limited approvals.** Approvals are sticky until revoked; denials can
  be temporary.
- **Security audit logging is local-only and metadata-only.** Logs never include
  prompt text, model output, camera frames, recognized text, or structured model
  output.

### `ping()` liveness exception

`ping()` intentionally bypasses consent so an SDK can distinguish "service alive"
from "needs consent" without surfacing model state. Pre-consent callers receive
only `{ alive, apiVersion }`; approved callers can use the normal status and
diagnostics APIs subject to caller scoping.

## Testing

The authorization path is covered by unit and instrumented tests around:

- `AllowlistStore` approval, denial, HMAC persistence, schema migration, and
  cross-process file locking.
- `CallerVerifier` shared-UID rejection, SHA-256 computation, and multi-signer
  normalisation.
- `ConsentChallengeStore` nonce creation, lookup, expiry, single-use semantics,
  and cooldown handling.
- `ServiceBinder` integration: pre-consent `ping` / `requestConsentChallenge`,
  `CONSENT_REQUIRED`, `CONSENT_DENIED`, approved caller pass-through, self-UID
  bypass, rate limiting, and ownership checks.
- `ConsentActivity` user decisions, biometric-gated grant, denial variants, and
  stale nonce handling.

Run the relevant unit tests with the existing Gradle test tasks documented in
`.github/context/DEVELOPMENT.md`.
