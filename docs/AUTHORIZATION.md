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

On a fresh install the allowlist is **empty**. There is no seed data, no
first-party bypass, and no "add by hand" UI. The only way an app can enter
the allowlist is:

1. The app attempts to bind (or make any AIDL call).
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
identity = CallerVerifier.identifyCaller(ctx, uid) ?: throw SecurityException
if (!allowlistStore.isAllowed(identity.pkg, identity.sig)) {
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

Session-scoped entry points add a second hop: `requireOwnership(sessionId)`
looks up the session's creator UID in the `InferenceOrchestrator` and
rejects calls from any other UID. Unknown sessions are also rejected to
avoid leaking which session IDs exist to arbitrary callers.

### Signature-level manifest permission

`AndroidManifest.xml` declares:

```xml
<permission
    android:name="com.adsamcik.mindlayer.permission.BIND_ML_SERVICE"
    android:protectionLevel="signature" />
<service
    android:name=".MindlayerMlService"
    android:permission="com.adsamcik.mindlayer.permission.BIND_ML_SERVICE"
    …/>
```

`signature` protection means only apps signed with the **same signing key
as Mindlayer itself** can hold the permission, and therefore can even
attempt to bind. In a first-party deployment (Mindlayer + one client app
signed with the same key) this is the primary gate; the user-approval
allowlist still runs underneath as defence in depth.

In a multi-vendor deployment where client apps are signed by other
developers, you would remove or relax the `android:permission` attribute
so arbitrary apps can reach `authorizeCall()` and be gated purely by the
user allowlist. Mindlayer ships with the strict variant by default.

### Rate limiter

`RateLimiter` (per-UID token bucket for RPM + a per-UID semaphore for
concurrent inferences) runs after the allowlist gate, so it never counts
rejected callers against legitimate traffic and never leaks
allowlist state via timing.

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
  de-duplicates by `packageName` as long as the signing cert matches.
- If the caller *rotates their signing cert*, `recordPending` upserts the
  entry with the new sig (so the user can see the change and re-approve);
  any prior `entries.json` entry is *not* auto-migrated — it still pins
  the old sig and therefore still fails `isAllowed()`.

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

- **No programmatic seeding.** Not even for first-party client apps. If
  you ship Mindlayer alongside a trusted suite, you'd need to add
  `AllowlistStore.seedIfEmpty(...)` and call it from
  `MindlayerMlService.onCreate`. The tradeoff is that you bake the seed
  into the APK, which makes OTA trust updates harder.
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
