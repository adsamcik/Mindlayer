# AIDL Stability Policy

This document defines the wire-compat rules for the Mindlayer service. It exists because the AIDL surface is **bound at runtime by independently-versioned client apps** (today co-signed first-party apps, tomorrow possibly third-party). Any change that breaks parcel layout or method-table alignment will manifest as `BadParcelableException`, `AbstractMethodError`, or — worse — silent data corruption.

If you want to change the AIDL surface, **read this document first**.

> Status: post-`v02-error-codes`. The pre-v0.1 surface is treated as schema version 0; from v0.2 onwards the rules below apply.

## TL;DR

| You want to… | Allowed? | How |
|---|---|---|
| Add a new method to `IMindlayerService` | ✅ | Append to the end of the AIDL. Mirror byte-identically between `:app` and `:sdk`. |
| Remove a method | ❌ | Methods are append-only. Deprecate via `@Deprecated` + no-op stub. |
| Reorder methods | ❌ | Method positions are part of the wire contract. |
| Add a field to an existing `@Parcelize` data class | ❌ | Wire-breaking. See "Parcelable evolution" below. |
| Remove or retype a field on an existing Parcelable | ❌ | Wire-breaking. See "Parcelable evolution". |
| Add a new Parcelable | ✅ | Include `schemaVersion: Int = 1` (see "schemaVersion convention"). |
| Add a new event type to the pipe stream | ✅ | New `StreamEventType` constant. Old readers ignore unknown events (`MindlayerEvent.Unknown`). |
| Change pipe frame format | ❌ | Bump pipe protocol (`mindlayer.stream.v1` → `v2`) and capability-gate. |
| Add a new error code | ✅ | Append to `MindlayerErrorCode`. Never reuse retired numbers. |
| Change an error code's meaning | ❌ | Codes are wire-stable. Allocate a new one. |

## Wire surfaces and their stability primitives

Mindlayer has **four** wire surfaces. Each has its own evolution rules.

### 1. `IMindlayerService` AIDL methods

- **Method positions are wire-fixed.** AIDL transactions are dispatched by ordinal. Removing or reordering breaks every existing client.
- **Append-only.** New methods get a new ordinal at the end of the interface.
- **Old SDKs calling new methods will get `AbstractMethodError`** at the binder stub. The SDK must catch this and fall back to a `v0Capabilities` baseline (see `v02-capabilities` work).
- **New SDKs calling old services** are detected via `getCapabilities().supportedFeatures` / capability bits — once that ships. Until then, a new SDK against an old service must catch `AbstractMethodError`/`NoSuchMethodError` per call site.

**Mirror invariant**: AIDL files in `app/src/main/aidl/com/adsamcik/mindlayer/` and `sdk/src/main/aidl/com/adsamcik/mindlayer/` must be **byte-identical**. The PR template asks for explicit confirmation of this.

### 2. `@Parcelize` data classes

Every public AIDL Parcelable lives in `:shared` and crosses process boundaries via `Parcel.writeXxx` / `readXxx` in **constructor parameter declaration order**. Adding, removing, reordering, or retyping a parameter changes the read-write protocol.

**The rule**: existing public Parcelables are **frozen** at their current parameter shape. To carry new data:

1. **Preferred**: add the data to a brand-new Parcelable + a new AIDL method that consumes it. E.g. v0.4 adds `inferMulti(meta, List<MediaPart>, eventWriteEnd)` rather than extending `infer(meta, image, audio, eventWriteEnd)`.
2. **Acceptable for opaque blobs**: stuff structured data into an existing String field. `SessionConfig.extraContextJson` is intentionally untyped for exactly this reason.
3. **Not acceptable**: adding a new constructor parameter with a Kotlin default. See "schemaVersion is not a substitute for v2 types" below.

**Existing inventory** (the v0.x parcelable surface — frozen unless explicitly v2'd):

| Parcelable | Parameter shape | Notes |
|---|---|---|
| `HistoryTurn` | `role: String, text: String` | Role values from `IpcInputValidator.ALLOWED_ROLES`. |
| `SessionConfig` | 11 fields | `extraContextJson` is the open extension point. Avoid bolting on more parameters. |
| `RequestMeta` | `requestId, sessionId, textContent?, role, priority` | `priority` and `role` are currently vestigial — see `v03-role-strings`. |
| `ImageTransfer` | 9 fields incl. `ParcelFileDescriptor source` | Frozen — use the v0.4 `MediaPart` for new media kinds. |
| `AudioTransfer` | 5 fields | Same. |
| `ToolResult` | `requestId, callId, toolName, resultJson` | `resultJson` is the extension point. |
| `ServiceStatus` | 12 fields | Polled at 2 s by the dashboard — keep cheap. |
| `EngineInfo` | 7 fields | Static-ish; refresh on engine reload. |
| `SessionInfo` | 10 fields incl. `expirationMs`, `expiresAtMs` | These two were added post-v0 with defaults; pre-1.0 audience accepted the wire break. **Do not repeat this pattern.** |
| `ServiceCapabilities` (v0.2) | `schemaVersion: Int = 1` first; 13 numeric/feature fields | First parcelable to follow the schemaVersion convention. |
| `MediaPart` (v0.4) | `schemaVersion: Int = 1` first; tagged-union kind + image/audio/video/document fields | Carries an ordered list via `inferMulti`. Wire reserves `KIND_VIDEO`/`KIND_DOCUMENT` for engines that aren't here yet. |

### 3. Pipe stream protocol (`mindlayer.stream.v1` / `mindlayer.stream.v2`)

The inference event stream uses length-prefixed JSON frames. Today's contract:

- 4-byte little-endian u32 length + UTF-8 JSON payload (envelope: `StreamEvent`).
- `StreamHeader` first frame carries `protocol = "mindlayer.stream.v1"` or `"mindlayer.stream.v2"`. As of v0.5 the SDK reader supports v2 token batching and validates the protocol string, emitting `MindlayerEvent.Error("PROTOCOL_MISMATCH")` on a mismatch.
- Event types are stringly-keyed (`StreamEventType.*`). Unknown event types on the reader side become `MindlayerEvent.Unknown` so old readers don't crash on new event types.

**Adding event types** is forward-compatible: do it freely. Old readers see `Unknown(type)` and ignore.

**Changing the frame format itself** is wire-breaking. Additive JSON payload fields are compatible. `mindlayer.stream.v2` is currently supported for token batching; callers opt in via session `extraContextJson` and capability checks.

**Stream `ERROR` frames** carry the same code vocabulary as AIDL exceptions: `code` is the symbolic `MindlayerErrorCode` name and v2-capable writers also include `codeInt`, the stable integer code. New SDKs tolerate old writers where `codeInt == null`; old SDKs continue reading the unchanged `code` string.

### 4. Error code allocation (`MindlayerErrorCode`)

Codes are wire-stable integers. The allocation table:

| Range | Domain | Examples |
|---|---|---|
| 1xxx | Engine lifecycle | `ENGINE_INITIALIZING`, `ENGINE_LOAD_FAILED` |
| 2xxx | Session lifecycle | `SESSION_NOT_FOUND_OR_NOT_OWNED` (single anti-enumeration code), `SESSION_EVICTED`, `SESSION_EXPIRED` |
| 3xxx | Request validation | `INVALID_REQUEST`, `INVALID_SESSION_CONFIG`, `INVALID_TOOL_RESULT`, `DUPLICATE_REQUEST`, `NO_ACTIVE_REQUEST`, `INPUT_EXCEEDS_CONTEXT` |
| 4xxx | Resource exhaustion | `THERMAL_CRITICAL`, `MEMORY_PRESSURE`, `LOW_MEMORY` |
| 5xxx | Quota / rate limit | `CONCURRENT_LIMIT`, `RATE_LIMITED`, `SERVICE_THROTTLED`, `TRANSIENT_RESOURCE_EXHAUSTED` |
| 6xxx | Auth / allowlist | `ALLOWLIST_PENDING`, `ALLOWLIST_REVOKED`, `IDENTITY_UNKNOWN` |
| 9999 | `INTERNAL` | Should not appear in healthy operation |

**Rules**:

- Codes are **append-only**. Once allocated, never reuse, never change semantics.
- `MindlayerErrorCode.UNKNOWN = 0` is reserved as the "no typed code" sentinel.
- New codes: pick the next free number in the relevant range, add to `nameOf()` and `categoryOf()`, and document the human-readable meaning in KDoc.
- **Anti-enumeration**: `SESSION_NOT_FOUND_OR_NOT_OWNED` is intentionally a single code that conflates "no such session" and "session exists but not yours". Splitting these would leak cross-UID session existence (F-008). **Do not introduce a `SESSION_NOT_OWNED` code.** This is load-bearing security.

## schemaVersion convention (for new Parcelables)

Every Parcelable created from v0.2 onwards must include a `schemaVersion: Int` parameter as its **first** field, with a documented default:

```kotlin
@Parcelize
data class ServiceCapabilities(
    val schemaVersion: Int = 1,
    // ... rest of the fields
) : Parcelable {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}
```

**Purpose**: when (not if) a future schema-breaking change is needed, the receiver can:

1. Read `schemaVersion` from the parcel.
2. If it's a known older version, fall back to a soft-degrade path (e.g. fill missing fields with safe defaults).
3. If it's a known newer version, the consumer is on an old binary and should warn / refuse / degrade depending on caller intent.

**This does NOT make the Parcelable mutable.** Adding a field still breaks wire — you still have to ship a v2 Parcelable + new AIDL method. `schemaVersion` lets the *new* Parcelable handle its own forward evolution one extra time before forking.

**For existing Parcelables** (the table above): do **not** retrofit `schemaVersion`. Adding even a defaulted field changes wire layout. They are frozen.

## Capability gating

Once `getCapabilities()` ships (`v02-capabilities`), every new feature that crosses the wire must be gated behind a capability flag in `ServiceCapabilities.supportedFeatures: Set<String>`. The SDK probes capabilities once after `awaitConnected()` and caches them; consumers check for the feature string before invoking the new method or expecting the new behavior.

Stable feature-flag strings allocated:

- `"media_list"` — `inferMulti(...)` and `MediaPart` available
- `"detailed_cancel"` — `cancelInferenceV2` / `submitToolResultV2` return tri-state results
- `"prewarm_await"` — `prewarmAndAwait(...)` is non-`oneway` and waits for engine init
- `"typed_diagnostics"` — `getDiagnosticsTyped()` returns a typed snapshot
- `"eviction_callback"` — `subscribeEvictionNotices(...)` is implemented
- `"token_batch"` — pipe emits `mindlayer.stream.v2` with `TOKEN_DELTA_BATCH`

Old SDKs that don't probe capabilities should call new methods inside a `try/catch (e: AbstractMethodError)` (or `NoSuchMethodError`) and fall back. New SDKs that probe should consult `supportedFeatures` first.

Feature flag strings are **wire-stable** in the same sense as error codes: append-only, never repurposed.

## What goes wrong if you ignore this document

Real failure modes observed when AIDL discipline lapses:

- **`BadParcelableException`** at the binder boundary — added a field to a Parcelable, old client tries to read it.
- **`AbstractMethodError`** — added a method, old service doesn't have it. SDK call site didn't guard with `try/catch`.
- **Silent semantic drift** — reused or repurposed an error code; SDK retry logic now matches the wrong condition. (This is roughly the bug `v02-error-codes` cleaned up — `Conversation.withSession` was catching `MindlayerException` codes the service never emitted, so eviction recovery was dead code in production.)
- **`NullPointerException` deep in service code** — silently dropped a field's required validation by sneaking a default in. The validator's `require` blocks downstream throw NPE on the unmocked path.

## Process

When changing the AIDL surface:

1. **Read this document.** Confirm your change is in the "allowed" column.
2. **Mirror AIDL files** byte-identically between `app/src/main/aidl/` and `sdk/src/main/aidl/`. CI will reject mismatches.
3. **Add tests** that round-trip the new Parcelable / method through `Parcel.marshall` + `unmarshall` to verify wire compat.
4. **Document the new feature flag** in this file's table if you're adding capability-gated behavior.
5. **Bump `MindlayerErrorCode`** if you're adding a typed error path. Mirror the symbolic name in `nameOf()` + `categoryOf()`.

If this document is wrong, **fix the document in the same PR as the code change**. Don't let the docs drift silently.

## Deferred inference surface (v0.6)

`IMindlayerService` appends `inferDeferred`, `fetchDeferredResult`, `cancelDeferredInference`, and `acknowledgeDeferredResult` after the existing v0.4 callback methods. `IClientCallback` appends `onDeferredInferenceComplete`. Existing transaction codes are preserved because no existing method was reordered or renumbered.

New parcelables: `DeferredHandle` and `DeferredResult`. The AIDL declaration files are mirrored byte-for-byte between `app/src/main/aidl` and `sdk/src/main/aidl`.

Capability flag: `ServiceCapabilities.FEATURE_DEFERRED_INFERENCE`. New SDKs must check it and throw `NOT_SUPPORTED` when connected to an older service.
