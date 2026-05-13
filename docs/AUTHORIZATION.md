# Caller Authorization

Mindlayer runs as a standalone Android service and exposes on-device LLM
inference over AIDL. Any app on the device can *attempt* to bind to it, so
every AIDL entry point enforces a four-stage gate before any work is done:

1. **Identity verification** — resolve the calling UID to a single package
   and its current signing-cert SHA-256.
2. **Allowlist check** — the (package, signing-cert) pair must appear in a
   user-approved allowlist.
3. **Rate limiting** — per-UID request-per-minute and concurrent-inference
   caps.
4. **Session ownership** — session-scoped operations (`infer`, `destroy`,
   `cancel`, …) must target a session the caller previously created.

This document describes how the allowlist works end-to-end: the data model,
cross-process semantics, the approval UX, failure modes, and the guarantees
callers can rely on.

## TL;DR for client integrators

- The first time your app calls any AIDL method, it will fail with
  `SecurityException("App <pkg> not authorized — user approval required")`.
- The service has recorded your package as **pending**. The user must open
  the Mindlayer dashboard and tap **Approve** next to your entry.
- Once approved, the next call succeeds. Mindlayer has pinned the SHA-256
  of your **current** signing certificate at approval time. A re-signed
  APK is implicitly rejected and must be re-approved.
- The user can revoke access at any time from the dashboard.
- See [`SDK_INTEGRATION.md`](../SDK_INTEGRATION.md#first-run-user-approval)
  for the SDK-side API (`ConnectionState.REJECTED_NOT_APPROVED`).

## Default-deny posture

Mindlayer uses a two-layer trust model:

1. **OS-level gate** — `BIND_ML_SERVICE` is declared as
   `signature|knownSigner` with trusted first-party cert hashes in
   `R.array.mindlayer_trusted_client_certs`. On Android 12 (API 31+) this
   lets known first-party apps signed with different Play app-signing keys
   bind to the service. On API 26–30, `knownSigner` is ignored by the
   platform and the permission degrades to plain `signature`, so only
   Mindlayer's own UID / same-signing-key callers can bind.
2. **AIDL-level gate** — every Binder method still runs identity → allowlist
   → rate-limit → ownership. First-party callers are seeded as exact
   `(packageName, signingCertSha256)` pairs in
   `MindlayerMlService.FIRST_PARTY_ALLOWLIST_SEEDS`.

The manifest cert array and seed list must stay in sync; the
`TrustedClientCertParityTest` unit test fails CI if they drift.

Unknown callers remain default-deny. The only production path for an
unrecognized app to enter the allowlist is:

1. The app attempts to bind (or make any AIDL call) and passes the OS-level
   permission gate.
2. The service writes a **pending** entry containing its observed
   `(packageName, signingCertSha256, displayName)`.
3. A human with access to the device opens the dashboard and taps **Approve**
   on that entry.

This is deliberate. Allowing manual entry of a package name + cert hash
would let a user approve a package they have never observed running on the
device, trading the sig-pin protection for typos.

## Components

### `CallerVerifier` (app/src/main/.../security/CallerVerifier.kt)

Pure utility that turns `Binder.getCallingUid()` into a `CallerIdentity`:

```
data class CallerIdentity(
    val packageName: String,
    val signingCertSha256: String,
    val displayName: String?,
)
```

Rules:

- If the UID maps to more than one package (shared UID), returns `null`.
  Shared-UID callers are refused outright — their identity is ambiguous.
- On API 28+ uses `PackageManager.GET_SIGNING_CERTIFICATES` + `SigningInfo`:
  - **Single signer, rotated**: pins the *current* signer (the last entry
    in `signingCertificateHistory`). Rotating *away* from a compromised key
    invalidates prior approvals, which is the point.
  - **Multi-signer APKs**: hashes each cert, sorts the hex digests,
    concatenates with `:`, and hashes the result. Ordering in
    `apkContentsSigners` is not guaranteed, so the two-stage hash makes the
    fingerprint deterministic across platform quirks.
- On API < 28 falls back to the deprecated `GET_SIGNATURES` with the same
  multi-signer normalisation.

### `AllowlistStore` (app/src/main/.../security/AllowlistStore.kt)

File-backed JSON store under `filesDir/mindlayer_allowlist/`:

| File            | Contents                                                        |
|-----------------|-----------------------------------------------------------------|
| `entries.json`  | Approved callers: `[{pkg, sig, grantedAtMs, displayName?}, …]`  |
| `pending.json`  | Unapproved callers that have tried to connect                   |
| `allowlist.lock`| Sidecar file used for a cross-process `FileLock` on writes      |

Key invariants:

- `isAllowed(pkg, sig)` **always re-reads from disk**. This is critical:
  the dashboard Compose UI lives in the main process while the AIDL server
  lives in the `:ml` isolated process, so any in-memory cache would go
  stale after an approval. The hot-path cost is bounded because the RPC
  rate limit caps external callers at ~60 RPM per UID by default.
- Writes are serialised across processes with `FileLock` on the sidecar
  `.lock` file, then an atomic `tmp -> rename` replaces the target JSON.
- `StateFlow<List<…>>` per-process streams are maintained for the UI to
  observe; they are refreshed either by the mutating API itself or by
  `refresh()`, which is polled every 2s by the dashboard card.
- `approve()` implicitly clears any matching entry from `pending.json`.

### `ServiceBinder.authorizeCall()` (app/src/main/.../ServiceBinder.kt)

Called as the first statement of every AIDL entry point. Sequence:

```
uid = Binder.getCallingUid()
if (uid == Process.myUid())      -> return SELF_IDENTITY   # dashboard
identity = CallerVerifier.identifyCaller(ctx, uid)
    ?: { rateLimiter.tryAcquireRejected(uid) || throw "Rate limit"
         throw SecurityException }                          # F-033 reject bucket
if (!allowlistStore.isAllowed(identity.pkg, identity.sig)) {
    rateLimiter.tryAcquireRejected(uid) || throw "Rate limit"   # F-033 reject bucket
    allowlistStore.recordPending(identity.pkg, identity.sig, identity.displayName)
    throw SecurityException("... user approval required")
}
if (!rateLimiter.tryAcquire(uid)) throw SecurityException("Rate limit exceeded")
return identity
```

The self-UID bypass is there because the built-in dashboard binds to its
own `:ml` service over AIDL (cross-process within the same app). Without
the bypass it would self-deny (never user-approved) and self-throttle
(polling ~3 RPCs every 2s exceeds the 60 RPM default).

#### Two-tier rate limiting (F-033)

There are now **two** independent token buckets per UID:

- **Main bucket** (`tryAcquire`, default 60 RPM) — consumed only by callers
  that have already cleared identity + allowlist. Sized for normal traffic.
- **Rejected bucket** (`tryAcquireRejected`, default 6 RPM) — consumed
  *before* `recordPending` whenever a caller fails identity verification or
  is not in the allowlist. This bounds the disk I/O a hostile flooder can
  trigger; the FileLock + fsync on `pending.json` was previously the
  cheapest way to saturate the service.

Order matters: the rejected bucket is consumed strictly **after** the
allowlist decision, so its timing cannot leak allowlist state to a probing
attacker. An additional in-memory `(pkg, sig)` dedup TTL inside
`AllowlistStore.recordPending` (30 s) short-circuits hot retries before
they even touch the file lock.

Session-scoped entry points add a second hop: `requireOwnership(sessionId)`
looks up the session's creator UID in the `InferenceOrchestrator` and
rejects calls from any other UID. Unknown sessions are also rejected to
avoid leaking which session IDs exist to arbitrary callers.

### Signature / known-signer manifest permission

`AndroidManifest.xml` declares:

```xml
<permission
    android:name="com.adsamcik.mindlayer.permission.BIND_ML_SERVICE"
    android:protectionLevel="signature|knownSigner"
    android:knownCerts="@array/mindlayer_trusted_client_certs" />
<service
    android:name=".MindlayerMlService"
    android:permission="com.adsamcik.mindlayer.permission.BIND_ML_SERVICE"
    …/>
```

`signature` preserves the self-UID / same-key path used by the dashboard.
`knownSigner` (API 31+) additionally grants the permission to callers whose
signing certificate SHA-256 appears in `mindlayer_trusted_client_certs`.
The AIDL allowlist still pins exact `(pkg, sig)` pairs underneath, so a
known cert on the wrong package name is rejected by `authorizeCall()`.

On API 26–30 the platform silently ignores the `knownSigner` flag and
falls back to `signature`. Cross-app first-party integration with different
Play app-signing keys is therefore supported only on API 31+. The SDK
surfaces this as `MindlayerException` with
`MindlayerErrorCode.UNSUPPORTED_ANDROID_VERSION`.

### Rate limiter

`RateLimiter` (per-UID token bucket for RPM + a per-UID semaphore for
concurrent inferences) runs after the allowlist gate, so it never counts
rejected callers against legitimate traffic and never leaks
allowlist state via timing.

## Cert rotation runbook

1. Add the new Play app-signing certificate hash to both
   `R.array.mindlayer_trusted_client_certs` and
   `FIRST_PARTY_ALLOWLIST_SEEDS` in the same Mindlayer PR.
2. Ship the updated Mindlayer build. First-party clients can stay on the old
   cert during the overlap window.
3. Once telemetry shows the rotated client is the only version in use,
   remove the old hash from both lists.

## Local sideloading

Production builds remain default-deny for unknown callers: a sideloaded app
must be observed by the service and approved in the dashboard before it can
use AIDL methods.

Debug builds add `DebugAllowlistSeeder` from the `src/debug` source set. It
discovers installed packages signed with the same debug certificate as the
Mindlayer debug build and seeds them automatically, while preserving revoked
/ denied packages. Release builds physically do not contain this class (the
release unit test asserts it is absent).

## The approval flow

```
┌─────────────────┐          ┌─────────────────────┐         ┌─────────────────┐
│   Client app    │          │  Mindlayer :ml svc  │         │ Dashboard (UI)  │
│ (external UID)  │          │    ServiceBinder    │         │  (main process) │
└────────┬────────┘          └──────────┬──────────┘         └────────┬────────┘
         │                              │                             │
   1. bind / AIDL call                  │                             │
         │────────────────────────────► │                             │
         │                              │ authorizeCall()             │
         │                              │   identifyCaller()          │
         │                              │   isAllowed(pkg,sig) = false│
         │                              │   recordPending(...)        │
         │                              │     ├── writes pending.json │
         │ SecurityException            │                             │
         │ ◄──────────────────────────────                            │
         │                              │                             │
   2. Client surfaces "please approve"  │                             │
      prompt; user opens dashboard.     │                             │
         │                              │                             │
         │                              │                             │   refresh() every 2s
         │                              │                             │   reads pending.json
         │                              │                             │       │
         │                              │                             │ shows "Pending approvals"
         │                              │                             │       │
         │                              │                             │  user taps "Approve"
         │                              │                             │   store.approve(...)
         │                              │                             │     ├── writes entries.json
         │                              │                             │     └── rewrites pending.json
         │                              │                             │
   3. Retry bind / AIDL call            │                             │
         │────────────────────────────► │                             │
         │                              │ authorizeCall()             │
         │                              │   isAllowed(...) = true     │
         │                              │   rateLimiter.tryAcquire()  │
         │   success response           │                             │
         │ ◄──────────────────────────────                            │
```

Key points:

- The dashboard does not have to be open when the client first calls.
  Pending entries persist on disk until the user reviews them.
- Multiple retries by an un-approved caller are idempotent: `recordPending`
  short-circuits on a 30-second in-memory dedup, then de-duplicates by
  `(packageName, sig)` under the file lock. The pending list is **append-only**
  across cert mismatches (F-031) — a sig swap appends a new row instead of
  silently overwriting the prior one, so the user always sees what was on
  screen.
- If the caller *rotates their signing cert*, the new pending row carries
  `previousSigSha256` and the dashboard renders a red banner + an "I
  understand" gate before the Approve button enables (F-032). A successful
  approval after rotation is logged with a distinct
  `approve_after_cert_rotation` audit event for grep-ability.
- The dashboard's Approve / Revoke / Deny buttons are gated by
  `BiometricPrompt` with `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` fallback
  (F-029). A device with no enrolled biometric AND no screen lock cannot
  approve — this is intentional default-deny.
- At the moment the user taps Approve, `AllowlistStore.approve(context, …)`
  re-resolves the live signing certificate via
  `CallerVerifier.identifyByPackage` *under the file lock* and throws
  `CertificateMismatchException` if it disagrees with the sig the user saw
  (F-031). This closes the TOCTOU window between dashboard render and tap.
- The pending list is FIFO-capped at `MAX_PENDING_ROWS = 32` so a flooder
  cannot push the file to unbounded size; combined with the 6 RPM rejected
  bucket per UID it would take >5 minutes to evict a real user-driven
  request, by which time the user will have noticed.

## Revocation

`AllowlistStore.revoke(pkg)` removes the entry from `entries.json`. The
next `isAllowed()` check (hot-path, re-reads disk) will return `false` and
the caller will be rejected again. Any *already in-flight* AIDL call on a
live binder is not interrupted — there's no active revocation mechanism
mid-call — but:

- No new session or inference can start.
- The SDK's `ConnectionManager` will observe the first new failure and
  transition back to `REJECTED_NOT_APPROVED`.
- Ongoing sessions owned by the UID are still tied to the binder death
  recipient, so if the client process terminates they are torn down
  normally.

If you need hard revocation (kill in-flight requests too), call
`orchestrator.closeAllOwnedBy(uid)` alongside `revoke()`. This is not
currently exposed in the dashboard UI — add it if the threat model
requires it.

## Failure modes and user-visible behaviour

| Situation                                   | Behaviour                                                    |
|---------------------------------------------|--------------------------------------------------------------|
| Client lacks `BIND_ML_SERVICE` permission   | `bindService` fails at the OS layer; AIDL never invoked.     |
| Shared-UID caller                           | `CallerVerifier` returns `null`; `SecurityException`, no pending entry recorded. |
| Unknown package / `PackageManager` error    | `SecurityException`, no pending entry recorded.              |
| Package not in allowlist                    | `SecurityException`, **pending entry recorded**.             |
| Package in allowlist, cert mismatch         | `SecurityException`, pending entry **upserted** with new sig. |
| Allowlist OK, rate limit exhausted          | `SecurityException("Rate limit exceeded …")`.                |
| Allowlist + RPM OK, but concurrent cap hit  | `infer()` rejects with `SecurityException("Concurrent … exceeded")`. |
| Session-scoped call from non-owner UID      | `SecurityException("Session not found or not owned …")`.     |

`recordPending` is deliberately **not** called for the top three rows —
those callers are either broken, malicious, or system edge cases, and we
don't want them to pollute the dashboard's pending list.

## What is *not* currently in place

These are intentional non-goals today; flag them if your threat model
requires them:

- **First-party seeding is opt-in and guarded.** `MindlayerMlService.onCreate`
  calls `AllowlistStore.seedIfEmpty(...)` with the baked-in first-party seed
  list. The hook only runs when the allowlist is empty, verifies each installed
  package against its pinned signing-cert hash, skips previously denied/revoked
  packages, and writes a `security_decision` log row for each insertion. Add new
  co-signed first-party apps in `MindlayerMlService.FIRST_PARTY_ALLOWLIST_SEEDS`.
- **No manual "Add app" UI.** See the rationale in
  [Default-deny posture](#default-deny-posture).
- **No SDK "waiting for approval" coroutine.** Clients see
  `REJECTED_NOT_APPROVED` once and must decide when to retry. The SDK
  does not poll the service (doing so would burn the rate-limit budget
  of *every* un-approved caller).
- **No time-limited approvals.** Entries are sticky until revoked.
- **No audit log of approval decisions.** Approvals / revocations write
  `entries.json` atomically; the diagnostic log captures rejections but
  not the approve/revoke events. Add an audit trail to `LogRepository`
  if compliance requires it.

## Testing

The authorization path is covered by:

- `app/src/test/.../security/AllowlistStoreTest.kt` — approve/deny,
  persistence, sig case-insensitivity, pending dedup, cross-process
  writer (two `AllowlistStore` instances on the same directory).
- `app/src/test/.../security/CallerVerifierTest.kt` — shared UID rejection,
  SHA-256 computation, multi-signer normalisation.
- `app/src/test/.../ServiceBinderTest.kt` — authorize gate integration:
  unknown caller, un-approved caller records pending, approved caller
  passes, self-UID bypass, ownership checks.

Run with:

```
./gradlew :app:testDebugUnitTest --tests "*Allowlist*" --tests "*CallerVerifier*" --tests "*ServiceBinder*"
```
