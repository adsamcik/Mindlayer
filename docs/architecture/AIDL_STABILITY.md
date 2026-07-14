# AIDL Stability Policy

This document defines the wire-compat rules for the Mindlayer service. It exists because the AIDL surface is **bound at runtime by independently-versioned client apps** (today co-signed first-party apps, tomorrow possibly third-party). Any change that breaks parcel layout or method-table alignment will manifest as `BadParcelableException`, `AbstractMethodError`, or — worse — silent data corruption.

If you want to change the AIDL surface, **read this document first**.

> Status: post-`v02-error-codes`. The pre-v0.1 surface is treated as schema version 0; from v0.2 onwards the rules below apply.

## TL;DR

| You want to… | Allowed? | How |
|---|---|---|
| Add a new method to `IMindlayerService` | ✅ | Append to the end of the AIDL in `:sdk` (the only AIDL source). |
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

**Single-source invariant**: ALL AIDL files — interfaces (`IMindlayerService.aidl` + `IClientCallback.aidl`) and parcelables — live ONLY in `sdk/src/main/aidl/com/adsamcik/mindlayer/`. `:app` does not compile its own AIDL (`aidl = false`); it consumes the generated Binder classes via `implementation(project(":sdk"))`. Adding AIDL to `:app` duplicates the classes and breaks the release R8 merge. `AidlContractDriftTest` enforces "no AIDL in `:app`" on every unit-test run.

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
| `ServiceCapabilities` (v0.2, schema v2 today) | `schemaVersion: Int = CURRENT_SCHEMA_VERSION` first; protocol/limit fields plus embedding limit/model metadata fields | First parcelable to follow the schemaVersion convention. Current schema is `2`; see capability gating and embeddings notes below. |
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

| Wire string | Meaning |
|---|---|
| `"typed_errors"` | Service throws wire-prefixed `SecurityException`s that SDKs parse into typed `MindlayerException`s. |
| `"pipe_proto_v1"` | SDK can validate `StreamHeader.protocol` against `mindlayer.stream.v1`. |
| `"pipe_stream_v1"` | Service streams length-prefixed JSON frames using the v1 envelope. |
| `"shared_memory_media"` | Image/audio media can travel via SharedMemory-backed descriptors. |
| `"tool_results"` | Tool-call round trips via `submitToolResult` are implemented. |
| `"history_recovery"` | `SessionConfig.initialHistory` is honored for SDK replay/recovery. |
| `"structured_output"` | `extraContextJson` structured-output envelope is supported. |
| `"media_list"` | `inferMulti(...)` and `MediaPart` are available. |
| `"detailed_cancel"` | `cancelInferenceV2` / `submitToolResultV2` return tri-state results. |
| `"prewarm_await"` | `prewarmAndAwait(...)` is non-`oneway` and waits for engine init. |
| `"typed_diagnostics"` | `getDiagnosticsTyped()` returns a typed snapshot. |
| `"eviction_callback"` | `subscribeEvictionNotices(...)` is implemented. |
| `"token_batch"` | Pipe emits `mindlayer.stream.v2` with `TOKEN_DELTA_BATCH`. |
| `"thinking_mode"` | Gemma 4 thinking-mode opt-in is honoured: pipe negotiates `mindlayer.stream.v3` and emits `THOUGHT_DELTA` / `THOUGHT_DELTA_BATCH` for sessions created with `extraContextJson.thinking = { "enable": true }`. See [`docs/engine/THINKING.md`](../engine/THINKING.md). |
| `"deferred_inference"` | Durable deferred inference with fetch, cancel, acknowledge, and completion callback. |
| `"embeddings"` | Text embeddings are available across inline, batch, SharedMemory, and deferred batch endpoints. |
| `"ocr_session"` | Multi-frame OCR session API (`create/push/stream/finalize/close/state/limits`) is callable. |
| `"ocr_presort_service_side"` | Service-side OCR quality presort is available as a version signal. |
| `"ocr_barcode_anchor"` | OCR evidence/events include ZXing barcode anchors. |
| `"ocr_bounding_boxes"` | OCR field events can include per-line bounding-box geometry. |
| `"health_check"` | Lightweight `ping()` health check returning `HealthCheck` is implemented. |

Old SDKs that don't probe capabilities should call new methods inside a `try/catch (e: AbstractMethodError)` (or `NoSuchMethodError`) and fall back. New SDKs that probe should consult `supportedFeatures` first.

Feature flag strings are **wire-stable** in the same sense as error codes: append-only, never repurposed.

## What goes wrong if you ignore this document

Real failure modes observed when AIDL discipline lapses:

- **`BadParcelableException`** at the binder boundary — added a field to a Parcelable, old client tries to read it.
- **`AbstractMethodError`** — added a method, old service doesn't have it. SDK call site didn't guard with `try/catch`.
- **Silent semantic drift** — reused or repurposed an error code; SDK retry logic now matches the wrong condition. (This is roughly the bug `v02-error-codes` cleaned up — `Conversation.withSession` was catching `MindlayerException` codes the service never emitted, so eviction recovery was dead code in production.)
- **`NullPointerException` deep in service code** — silently dropped a field's required validation by sneaking a default in. The validator's `require` blocks downstream throw NPE on the unmocked path.

## Contract version and compatibility policy

`com.adsamcik.mindlayer.shared.ContractVersion` (`shared/src/main/kotlin/com/adsamcik/mindlayer/shared/ContractVersion.kt`) is the formal, semver-numbered successor to the informal "(vX.Y)" labels used throughout this document and the codebase's comments (§ "Deferred inference surface (v0.6)" below, `"v1.1: ..."` comments in `SessionManager.kt`, etc.). Those labels tracked the same thing this object now tracks explicitly — the shape of the AIDL/wire contract — just without a single source of truth. Current value: `1.1.1`.

**Relationship to the product version.** `ContractVersion` is a deliberately separate number from the product/SDK version (`publishVersion` in the root `build.gradle.kts`, e.g. `"1.0.0-alpha.5"`) — most product releases ship zero AIDL changes, so forcing them to share a full version would be misleading. They are linked at exactly one level:

- **MAJOR is shared.** The root `build.gradle.kts` declares `contractMajorVersion` next to `publishVersion` and `require()`s they match at configuration time — any Gradle invocation fails immediately if one is bumped without the other. Neither is derived from the other; both are hand-maintained and kept in sync deliberately. A shared major is where either side may break compatibility outright.
- **MINOR and PATCH are independent.** The contract's minor/patch move on their own cadence, tied to real wire changes (see the Process list below), completely decoupled from the product's own release cadence.

**Compatibility guarantee — applies from product `1.0.0` stable onward.** Once the product ships `1.0.0`: any two builds whose `ContractVersion` values share the same MAJOR.MINOR are guaranteed wire-compatible; a PATCH difference never breaks compatibility. Only a MAJOR bump may break wire compatibility outright, and it always ships together with a product major bump.

**Before `1.0.0` (current status).** The product is still on `1.0.0-alpha.x`, so this guarantee does **not** yet apply — no compatibility is promised between any two contract versions, including within the same MINOR, mirroring standard semver's own pre-1.0 rules. During this period (and always, as defense in depth once 1.0 ships too) the only real compatibility mechanism remains runtime capability negotiation: `ServiceCapabilities.supportedFeatures`, `apiVersion`, and `schemaVersion`. `ContractVersion` is a human/tooling-facing summary of "what generation of wire contract this build was compiled against" — it is never itself sent over the wire, so it cannot be used to negotiate compatibility between two different builds at runtime; that's what the capability-flag registry is for.

## Process

When changing the AIDL surface:

1. **Read this document.** Confirm your change is in the "allowed" column.
2. **All AIDL lives in `:sdk`** (`sdk/src/main/aidl/`) — interfaces (`IMindlayerService.aidl` + `IClientCallback.aidl`) and parcelables. `:app` adds no AIDL of its own. CI + `AidlContractDriftTest` reject any `.aidl` under `app/src/main/aidl/`.
3. **Add tests** that round-trip the new Parcelable / method through `Parcel.marshall` + `unmarshall` to verify wire compat.
4. **Document the new feature flag** in this file's table if you're adding capability-gated behavior.
5. **Bump `MindlayerErrorCode`** if you're adding a typed error path. Mirror the symbolic name in `nameOf()` + `categoryOf()`.
6. **Bump `ContractVersion.MINOR`** (or `PATCH` for a wire-invisible fix) in `shared/.../ContractVersion.kt`. Bump `MAJOR` there — and `contractMajorVersion` in the root `build.gradle.kts`, together — only for an intentionally wire-breaking change, and only in lockstep with a product major bump. See "Contract version and compatibility policy" above.

If this document is wrong, **fix the document in the same PR as the code change**. Don't let the docs drift silently.

## Deferred inference surface (v0.6)

`IMindlayerService` appends `inferDeferred`, `fetchDeferredResult`, `cancelDeferredInference`, and `acknowledgeDeferredResult` after the existing v0.4 callback methods. `IClientCallback` appends `onDeferredInferenceComplete`. Existing transaction codes are preserved because no existing method was reordered or renumbered.

New parcelables: `DeferredHandle` and `DeferredResult`. The AIDL declaration files live in `sdk/src/main/aidl/` and are pulled into `:app` transitively via `implementation(project(":sdk"))`.

Capability flag: `ServiceCapabilities.FEATURE_DEFERRED_INFERENCE`
(`"deferred_inference"`). New SDKs must check it and throw
`NOT_SUPPORTED` when connected to an older service.

## Embeddings surface (v0.7)

Parcelable inventory additions: `EmbeddingRequest` (schemaVersion=1; text, tag, modelId, normalize, outputDim, taskType), `EmbeddingResult` (schemaVersion=1; tag, vector, dim, modelId, tokenCount, truncated, backend, durationMs), `EmbeddingBatchResult` (schemaVersion=1; results, totalDurationMs, backend), `EmbeddingBatchTransfer` (schemaVersion=1; PFD/SharedMemory transfer, count, dim, modelId, perItemMetadata, totalDurationMs, backend), `EmbeddingItemMetadata` (v1 metadata shape; tag, tokenCount, truncated), and `VectorBlobHandle` (schemaVersion=1; status, transfer, errorCodeInt, errorCodeName).

AIDL method inventory additions are appended at the end of `IMindlayerService`: `embed`, `embedBatch`, `embedBatchShm`, `embedBatchDeferred`, `fetchEmbeddingBatchResult`, `cancelEmbeddingBatch`, `acknowledgeEmbeddingBatchResult`, and `cancelEmbed`.

Capability flag registry: `ServiceCapabilities.FEATURE_EMBEDDINGS` (`"embeddings"`) means text embeddings are available and model/tokenizer discovery passed integrity verification. `ServiceCapabilities` schemaVersion moved from 1 to 2 for `maxEmbeddingBatchInline`, `maxEmbeddingBatchShm`, `maxEmbeddingBatchTotal`, `maxEmbeddingInputBytes`, `embeddingModelIds`, and `embeddingDims`; `v1Baseline()` keeps old clients forward-compatible with zero embedding limits.

Error code registry additions: `EMBEDDING_BATCH_TOO_LARGE`, `EMBEDDING_MODEL_UNAVAILABLE`, `EMBEDDING_INPUT_TOO_LONG`, and `EMBEDDING_DISABLED`.

## OCR surface (v0.8)

Capability flag registry additions:
`ServiceCapabilities.FEATURE_OCR_SESSION` (`"ocr_session"`) gates the
multi-frame session methods. The flag is conditionally advertised only when the
PaddleOCR engine is ready and `OcrFeatureFlags.IS_PRODUCTION_READY` is true.
`FEATURE_OCR_BARCODE_ANCHOR` (`"ocr_barcode_anchor"`) and
`FEATURE_OCR_BOUNDING_BOXES` (`"ocr_bounding_boxes"`) are additive wire-shape
signals for barcode evidence and optional field geometry.

OCR stream evolution remains additive under `mindlayer.stream.ocr.v1`:
`FRAME_DROPPED`, terminal `RESULT_FINALIZED`, and terminal `ERROR` map to SDK
`OcrEvent.FrameDroppedBusy`/drop signals, `OcrEvent.ResultFinalized`, and
`OcrEvent.Error`. Frame intake also has the wire-stable
`OcrFrameAck.STATUS_REJECTED_STREAM_NOT_ATTACHED` status so callers attach the
event pipe before pushing frames.

## Health-check surface (v0.8.1)

Capability flag: `ServiceCapabilities.FEATURE_HEALTH_CHECK`
(`"health_check"`). The appended `ping()` method returns a `HealthCheck`
parcelable with schemaVersion, server timestamp, uptime, apiVersion, and
per-engine state. Old SDKs must gate on the capability or catch
`NoSuchMethodError` / `AbstractMethodError` and fall back to `getStatus()` or
Binder liveness checks.

## Audio surface (v1.0)

Capability flag: `ServiceCapabilities.FEATURE_AUDIO_INPUT` (`"audio_input"`).
Advertises that the engine consumes single-clip audio attachments via
`infer(...)` / `inferMulti(...)` with one `MediaPart` of kind `KIND_AUDIO`
(or the legacy `AudioTransfer`). The contract is documented in
[`docs/engine/AUDIO.md`](../engine/AUDIO.md) and the per-clip cap lives on
`com.adsamcik.mindlayer.GemmaAudioSpec.MAX_DURATION_MS` (30 s today).

No new AIDL methods or parcelables — the surface piggybacks on existing
`infer` / `inferMulti` / `AudioTransfer` / `MediaPart.KIND_AUDIO`. The
flag is a pure capability signal so SDKs can fail fast against services
that haven't loaded an audio-capable engine.

`IpcInputValidator.validateAudioTransfer` and `validateAudioPart`
tightened the `durationMs` cap from 60 minutes to
`GemmaAudioSpec.MAX_DURATION_MS` (30 s). Callers who relied on the
larger window must chunk their audio — Gemma 4 silently truncates above
30 s, so the previous behaviour was undefined anyway.

Multi-audio prompts (the upstream Google docs page demonstrates them
for the journal1…5 example) remain rejected by the validator
(`audioCount > 1`). The capability flag is **single-clip only**; do
not infer multi-clip support from its presence.
