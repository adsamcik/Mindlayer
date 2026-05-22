# Third-Party Caller Future Work

> **Status: planning note.** No code changes here — this captures the design
> deltas the codebase needs before the `BIND_ML_SERVICE` permission is
> relaxed below `signature` protection level. Read this whenever you're
> tempted to invoke "third-party" as a justification for a current
> architecture decision.

## Today's posture

Mindlayer is **first-party-only**:

- `<permission android:name="com.adsamcik.mindlayer.permission.BIND_ML_SERVICE" android:protectionLevel="signature|knownSigner" />` (`AndroidManifest.xml`).
- Same-key apps can bind via `signature`; Android 12+ first-party apps signed by known registered certs can bind via `knownSigner`.
- The user-approved allowlist (`AllowlistStore`) gives a second gate even for co-signed apps.
- Per-UID rate limit is generous (60 RPM, 4 concurrent).
- `IpcInputValidator` byte budgets are tuned for trusted callers (256 KB tools JSON, 100 MB media payload, etc.).
- Identity scoping in `getDiagnostics()`, `getStatus()` and `listSessions()` is already caller-scoped for external UIDs (good — that work doesn't need to be redone).

## What changes when we open up

### 1. The `BIND_ML_SERVICE` permission

The current `signature|knownSigner` protection level is the wrong fence for third-party. Two paths:

- **Drop to `normal`** — any installed app can bind. Combine with the existing dashboard-approval flow as the actual gate.
- **Custom permission group** — let the user grant "AI inference" as a runtime permission in Settings. Heavier UX, more granular.

Either way, **the user-approval flow becomes the primary trust boundary** instead of the secondary one it is today. `AllowlistStore.recordPending` and the dashboard's approve/revoke UX must scale to a much larger long tail of unknown apps.

### 2. Per-package quota multipliers

A flat 60 RPM / 4 concurrent for every caller becomes a denial-of-service surface once anyone can bind. Plan:

- Default new third-party callers to `1/4` of the first-party allowance (~15 RPM, 1 concurrent).
- Allow the user to bump trusted apps up via the dashboard (per-package "allow burst" toggle).
- Keep the global `DEFAULT_MAX_GLOBAL_CONCURRENT = 16` cap so N apps can't collectively saturate the engine.

This is a `RateLimiter` constructor change (per-UID limits become a function of the entry's allowlist trust tier) and an `AllowlistEntry` schema addition (`trustTier: Int`).

### 3. Stricter `IpcInputValidator` budgets for unknown callers

Today's validator is the same regardless of caller. For third-party:

- `MAX_TOOLS_JSON_LEN`: drop from 256 KB to 16 KB unless explicitly raised.
- `MAX_TEXT_CONTENT_LEN`: drop from 256 KB to 32 KB.
- `MAX_HISTORY_TURNS`: drop from 64 to 16.
- `MAX_SESSION_EXPIRATION_MS`: drop from 90 days to 7 days.
- Image / audio payload caps: keep at current 100 MB but require Per-attachment validation against device memory headroom.

The validator needs a `CallerProfile` parameter so byte budgets are per-caller, not global.

### 4. Prompt-injection defenses

First-party apps are trusted not to attempt prompt injection. Third-party can't be. Required:

- Score incoming text content for prompt-injection markers before it reaches the model. Heuristics: instruction tokens at start, role-impersonation strings, base64 payloads. Below threshold: pass; above: reject with a typed `MindlayerErrorCode` (allocate `INPUT_REJECTED = 3006`).
- Tool-call result sanitization (`ToolOutputSanitizer` already exists for engine output) needs a counterpart for tool-call **inputs** — a third-party app could exfiltrate via `submitToolResult.resultJson`.
- Document this in `docs/AUTHORIZATION.md` § threat model.

### 5. Cross-process state coherence

`RateLimiter` is in-process today (lives in `:ml`). The dashboard runs in the main process and is self-UID-bypass'd. For third-party:

- Rate counters must be visible across the dashboard's reporting UI (so users can see per-app usage). Either: dashboard reads via its self-UID AIDL access, or a shared file-backed counter with `FileLock` (matches the `AllowlistStore` pattern).
- `concurrentFor(uid)` already exists; expose via a new dashboard-only AIDL method `listCallerUsage(): List<CallerUsage>`.

### 6. Per-caller diagnostics scoping

Already done for `getStatus` / `getDiagnostics` / `listSessions` (external callers see only their own data). **Audit** when adding any new typed-diagnostics method (`v04-typed-diagnostics`) — easy to forget on a new code path.

### 7. AIDL-method-level capability gating

Once `getCapabilities()` ships (`v02-capabilities`), the feature-flag set should be **per-caller**. A first-party app might see `"media_list", "token_batch", "eviction_callback"`, while an unknown third-party gets just `"media_list"`. The `ServiceCapabilities` parcelable already has a `supportedFeatures: Set<String>` — populating it per-caller is a server-side decision based on `AllowlistEntry.trustTier`.

## What we already got right

These don't need to change:

- F-008 anti-enumeration: `SESSION_NOT_FOUND_OR_NOT_OWNED` shares one wire code; cross-UID lookups are already opaque.
- Per-UID session ownership (`InferenceOrchestrator.getSessionOwner` + `requireOwnership`): correct and test-covered.
- Self-UID bypass in `authorizeCall`: necessary for the dashboard, and it's gated by `Process.myUid()` which a third-party UID cannot impersonate.
- `BIND_ML_SERVICE` as a separate permission name: keep the name even if the protection level relaxes, so existing first-party manifests don't need editing.

## When we ship this, what breaks?

- **First-party apps with the current generous quotas** will continue working — `trustTier` for already-approved entries defaults to "first-party" (current limits).
- **The dashboard's UX** needs new flows: per-app trust tier toggles, per-app usage charts, prompt-injection scoring visibility, easier revoke.
- **`AllowlistStore` schema** bumps via the existing JSON `version` field — migration path adds `trustTier: Int = TIER_FIRST_PARTY` for old entries.

## Implementation order (when this lands)

1. `AllowlistEntry.trustTier` schema bump + dashboard UI to set it.
2. `IpcInputValidator` accepts a `CallerProfile`.
3. `RateLimiter` per-UID limits become a function of trust tier.
4. Prompt-injection scoring + `INPUT_REJECTED` error code.
5. Drop `BIND_ML_SERVICE` protection level to `normal`.
6. Document the new threat model in `AUTHORIZATION.md`.

Step 5 is the one-way door. Steps 1-4 ship behind the existing signature gate and become live for everyone the moment 5 lands. Everything before 5 is reversible.
