---
applyTo: "app/src/main/kotlin/com/adsamcik/mindlayer/service/security/**, app/src/main/kotlin/com/adsamcik/mindlayer/service/ServiceBinder.kt, app/src/main/kotlin/com/adsamcik/mindlayer/service/MindlayerMlService.kt, app/src/main/AndroidManifest.xml"
description: "Authorization invariants — applies to ServiceBinder, MlService, security/, and the manifest"
---

<!-- context-init:managed -->

## Authorization invariants (do not weaken)

The 4-stage gate runs at the top of every AIDL entry point. **All four stages run for every external caller, every time.** See `docs/AUTHORIZATION.md` for the rationale.

```
authorizeCall(sessionId? = null):
    uid = Binder.getCallingUid()
    if (uid == Process.myUid()) return SELF_IDENTITY            // dashboard bypass
    identity = CallerVerifier.identifyCaller(ctx, uid)
        ?: throw SecurityException("Unknown caller")
    if (!allowlistStore.isAllowed(identity.pkg, identity.sig)) {
        allowlistStore.recordPending(identity.pkg, identity.sig, identity.displayName)
        throw SecurityException("App ${identity.pkg} not authorized — user approval required")
    }
    if (!rateLimiter.tryAcquire(uid)) throw SecurityException("Rate limit exceeded")
    sessionId?.let { requireOwnership(it, uid) }
    return identity
```

### Don't:

- Cache `isAllowed` results in memory. The dashboard runs in the main process; the gate runs in `:ml`. The hot path **must** re-read `entries.json` on every call. Disk cost is bounded by the rate limiter (60 RPM/UID default).
- Skip `recordPending`. It is what makes user approval discoverable in the dashboard. Only the three cases at the top of the failure-mode table in `AUTHORIZATION.md` skip it (broken/malicious/system edge cases).
- Use `SharedPreferences` for the allowlist or any other cross-process state. `MODE_MULTI_PROCESS` is deprecated and racy.
- Bypass the gate for "internal helpers" that take a `Context`. If a method can be reached from AIDL, it must be gated.
- Add a manual "add app by hand" UI. Default-deny is intentional — see the rationale in `AUTHORIZATION.md#default-deny-posture`.

### Do:

- Use `CallerIdentity` (returned by `authorizeCall`) as the UID-equivalent token everywhere downstream. Pass it to `InferenceOrchestrator.createSession(config, ownerToken = identity)` so binder-death tear-down works.
- Keep `recordPending` running on rejected callers — that's how user approval is discovered.
- Persist the *current* signing cert per `signingCertificateHistory.last()`. Rotated certs invalidate prior approvals on purpose.
- Serialise allowlist writes with `withFileLock { … }` and atomic-rename via `atomicWrite`.

## First-party seeding (intended, not yet implemented)

The product intent is: apps signed with the **same key** as Mindlayer service should be auto-approved without dashboard interaction. The hook is named in `docs/AUTHORIZATION.md`:

```kotlin
// FUTURE — call from MindlayerMlService.onCreate before binder is exposed
allowlistStore.seedIfEmpty(
    listOf(
        AllowlistEntry("com.example.firstparty.appA", knownSigSha256, …),
        // …
    ),
)
```

When you implement this:

- Trigger only on first launch (allowlist empty). Don't re-seed on every onCreate — that would silently re-approve revoked apps.
- Keep `recordPending` for unknown callers. Third-party support remains gated by user approval.
- Don't remove the manifest `signature` permission. It is the first line; the seeded allowlist is defense-in-depth.
- Add an audit log entry to `LogRepository` (`LogCategory.SECURITY`?) so seed events are visible.

## Rate limiting

- 60 RPM token bucket + concurrent-inference semaphore, per UID (`RateLimiter.kt`).
- The dashboard's self-UID skips the rate limit so its 2 s polling doesn't burn external budgets.
- Rate-limit rejection happens **after** identity + allowlist so timing doesn't leak allowlist state.

## Session ownership

- Every session is tagged with its creator's `ownerToken` (a `CallerIdentity` for external callers).
- `requireOwnership(sessionId)` rejects calls from any other UID and returns the same error for unknown sessions — don't leak which session IDs exist.
- `closeAllOwnedBy(uid)` is the binder-death tear-down hook.

## Logging

- Log every authorization rejection with `MindlayerLog.w("ServiceBinder", reason, requestId, sessionId)` and a structured `LogRepository` entry — but **never** include the rejected caller's request body, prompt, or any session content.
- Use `Throwable.safeLabel()` when surfacing exceptions thrown by native verification code.
