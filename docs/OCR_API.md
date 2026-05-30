# Multi-frame OCR / parsing API

> v0.8 Phase 1–4 + Wave 1, plus v0.9 single-image one-shot, plus v0.10
> SDK rename + camera-launcher module. Single source of truth for the
> Mindlayer OCR APIs. The AIDL/SDK/event stream is wired; production
> exposure remains gated by `OcrFeatureFlags.IS_PRODUCTION_READY = false`
> until the real-device validation matrix signs off.
>
> **New consumer guide:** [`OCR_SDK_GUIDE.md`](OCR_SDK_GUIDE.md) is the
> primary entry point for SDK consumers (decision rule, snippets, error
> handling, full sample). This document remains the authoritative
> reference for the AIDL surface, wire details, and engine semantics.

## Two shapes, one engine

Mindlayer exposes OCR through two AIDL surfaces backed by the same
`LiteRtPaddleOcrBackend`:

| Surface | When to use | Capability flag |
|---|---|---|
| `ocrImage(MediaPart, OcrImageOptions): OcrImageResult` — **v0.9** | Single captured image (gallery picker, sharesheet, screenshot text-extraction, "scan this receipt" one-shot). Sync, no session ceremony. | `FEATURE_OCR_IMAGE_ONESHOT` |
| `createOcrSession` / `pushOcrFrame` / `streamOcrEvents` / `finalizeOcrSession` — **v0.8** | Streaming camera frames with cross-frame fusion, presort, barcode anchoring, structured-output finalization. | `FEATURE_OCR_SESSION` |

**SDK surface naming (v0.10):** `Mindlayer.ocrSession()` was renamed to
`Mindlayer.ocrRealtime()`, and `Mindlayer.ocrImage()` was renamed to
`Mindlayer.ocrAsync()`. The old names remain as `@Deprecated`
(WARNING-level) aliases that delegate to the new ones — the AIDL methods
themselves are unchanged, so wire compatibility is preserved. New code
should use the new names; see [`OCR_SDK_GUIDE.md`](OCR_SDK_GUIDE.md)
for the decision rule.

Both flags flip together (same production-readiness gate). The engine's
per-instance mutex serialises all calls so the two APIs share throughput
without racing for the native delegate.

## TL;DR — single-image (v0.9)

```kotlin
val mindlayer = Mindlayer.connect(context)
mindlayer.awaitConnected()

if (!mindlayer.getCapabilities().supports(ServiceCapabilities.FEATURE_OCR_IMAGE_ONESHOT)) {
    return  // OCR is still hidden by the production-readiness gate.
}

// Raw OCR only — ~1-2s on real hardware.
val result = mindlayer.ocrImage(
    bytes = jpegBytes,
    mimeType = "image/jpeg",
)
result.lines.forEach { line ->
    Log.i("ocr", "${line.text} (conf=${line.confidence})")
}

// OCR + structured extraction (Gemma) — adds ~2-5s of LLM decode.
val structured = mindlayer.ocrImage(
    bytes = jpegBytes,
    mimeType = "image/jpeg",
    options = OcrImageOptions(
        emitBoundingBoxes = true,
        runLlmExtraction = true,
        extractionSchemaJson = """{"type":"object","properties":{"total":{"type":"string"}}}""",
    ),
)
structured.extractionFields.forEach { field ->
    Log.i("ocr", "${field.name} = ${field.value}")
}
structured.extractionJson?.let { Log.i("ocr", "raw: $it") }
```

The bytes path picks SharedMemory or pipe transport automatically based on
size (payloads under `OCR_INLINE_PIPE_THRESHOLD_BYTES` use a PFD pipe;
larger payloads use SharedMemory on API 27+). Caller does not manage the
file descriptor.

### Single-image semantics

| Property | Value |
|---|---|
| Transport | `ocrImage` accepts an encoded image bytes (JPEG / PNG / WEBP) via the bytes-in SDK wrapper, or any `MediaPart` (SHM or PFD) via the direct AIDL call. |
| Concurrency | Per-engine mutex serialises with concurrent session-pipeline pushes. |
| Synchronicity | Sync binder transaction. The call returns when both OCR and (optionally) LLM extraction are complete. |
| Failure modes | `INVALID_REQUEST` (options or MediaPart shape), `SERVICE_UNAVAILABLE` (engine not ready or never wired), `LOW_MEMORY` (engine refused for memory headroom), `TRANSIENT_RESOURCE_EXHAUSTED` (SharedMemory pool saturated). |
| Persistence | None. Recognized text and extraction output live in the response parcelable only; never persisted to filesDir / cacheDir / external storage. Caller may persist via the SDK history DB. |

### When to choose `ocrAsync` vs `ocrRealtime`

> Renamed in v0.10. The old SDK names `ocrImage` / `ocrSession` still
> work as deprecated aliases.

- ✅ Use `ocrAsync` (formerly `ocrImage`) for one-off captures, deep
  links, screenshots, gallery pickers, sharesheet targets. Less
  ceremony, no event pipe, no per-call setup cost beyond the engine
  itself.
- ✅ Use `ocrRealtime` (formerly `ocrSession`) for streaming camera
  feeds where multi-frame fusion meaningfully improves quality
  (cross-frame voting, barcode anchor lock, schema-shaped structured
  output across frames). The session pipeline also runs the
  service-side presort so blurry / dark frames get rejected before
  reaching the engine.
- ✅ Use the **`:sdk-camera-launcher`** module for either when you
  want a turn-key camera capture flow — see
  [`OCR_SDK_GUIDE.md`](OCR_SDK_GUIDE.md). The launcher registers a
  single `ActivityResultContract` that opens a Mindlayer-owned camera
  Activity, asks for the `CAMERA` permission, runs the chosen surface,
  and returns a structured result. Consumers never touch CameraX,
  permissions, or the session lifecycle themselves.

## TL;DR — multi-frame session (v0.8)

```kotlin
val mindlayer = Mindlayer.connect(context)
mindlayer.awaitConnected()

if (!mindlayer.getCapabilities().supports(ServiceCapabilities.FEATURE_OCR_SESSION)) {
    return  // OCR is still hidden by the production-readiness gate.
}

mindlayer.ocrSession(OcrProfile.Receipt) {
    languageHints = listOf("en", "de-DE")
    maxFrames = 30
    frameRateLimitFps = 5
}.use { session ->
    // Attach before pushing frames; otherwise the service rejects intake
    // with STATUS_REJECTED_STREAM_NOT_ATTACHED to avoid lost events.
    val eventsJob = launch {
        session.events.collect { event ->
            when (event) {
                is OcrEvent.ResultFinalized -> render(event.fullJson)
                is OcrEvent.Error -> showError(event.code, event.message)
                else -> Unit
            }
        }
    }

    // For each captured frame: presort -> push -> read ack.
    val ack = session.pushFrame(meta)
    when (ack.status) {
        OcrFrameAck.STATUS_ACCEPTED -> { /* keep capturing */ }
        OcrFrameAck.STATUS_DROPPED_BUSY -> { /* slow down, ack.retryAfterMs */ }
        OcrFrameAck.STATUS_REJECTED_QUALITY -> { /* recapture */ }
        OcrFrameAck.STATUS_REJECTED_STREAM_NOT_ATTACHED -> { /* attach events first */ }
        OcrFrameAck.STATUS_REJECTED_FINALIZED -> { /* session ended */ }
    }
    session.finalize()
    eventsJob.join()
}
```

## Capability discovery

The service advertises OCR support via flags in
`ServiceCapabilities.supportedFeatures`:

| Flag | Meaning |
|---|---|
| `FEATURE_OCR_SESSION` | Multi-frame OCR sessions are callable. The code path is wired, but this flag is advertised only when the PaddleOCR engine is ready **and** `OcrFeatureFlags.IS_PRODUCTION_READY` is `true`; Wave 1 keeps it dark. |
| `FEATURE_OCR_IMAGE_ONESHOT` | v0.9 single-image `ocrImage(MediaPart, OcrImageOptions): OcrImageResult` AIDL is callable. Shares the same engine-ready + production-readiness gate as `FEATURE_OCR_SESSION` — the two flags flip together. |
| `FEATURE_OCR_PRESORT_SERVICE_SIDE` | Service-side quality presort capability. The presort runs on accepted OCR frames; callers should still check the advertised flag before relying on it as a version signal. |
| `FEATURE_OCR_BARCODE_ANCHOR` | ZXing barcode anchor in the evidence package. Advertised as a wire-shape capability. |
| `FEATURE_OCR_BOUNDING_BOXES` | Per-line bounding boxes in extraction output. Advertised as a wire-shape capability. |

`Mindlayer.ocrLimits()` returns the `OcrLimits` parcelable so callers
can size their UI throttling:

| Field | Default | Meaning |
|---|---|---|
| `maxConcurrentOcrSessions` | 1 | Per-UID concurrent session cap |
| `maxOcrFramesPerMinute` | 120 | Per-UID sliding-window frame budget |
| `maxFramesPerOcrSession` | 60 | Hard cap per session before auto-finalize |
| `maxOcrSessionDurationMs` | 300_000 | Hard wall-clock cap (5 min) regardless of activity |
| `ocrPerFrameDecodeBudgetTokens` | 1024 | LLM decode budget per frame (Strategy A reset-per-frame KV) |
| `ocrSchemaJsonMaxLen` | 16 KiB | Max length of `outputSchemaJson` |

## Profiles

5 built-in `OcrProfile` presets each map to a wire-stable
`OcrSessionConfig.MODE_*` and a coherent default schema. Override
the schema via the builder DSL when you need custom fields.

| Profile | Mode | Shape |
|---|---|---|
| `GeneralDocument` | 1 | `lines: string[]` |
| `Receipt` | 2 | merchant + items[] + subtotal/tax/total/currency |
| `IdCard` | 3 | MRZ lines + parsed personal fields |
| `Whiteboard` | 4 | `paragraphs: string[]` |
| `ScreenCapture` | 5 | UI blocks with text + kind |

Find a profile by integer mode via `OcrProfile.forMode(int)`.

## Frame intake lifecycle

1. **Open** — `Mindlayer.ocrSession(profile) { configure }` →
   `OcrSession`. Service-side `OcrSessionManager.createSession`
   enforces per-UID concurrent limit and starts the idle timer.
2. **Push frame** — three transports, all converging on the same
   service-side intake path (Phase 4 PR #2):
   - **Y-plane bytes** (`session.pushFrame(meta, yPlane)`) — direct
     `ByteArray` over the binder. Capped at `MAX_FRAME_BYTES`
     (1 MiB). Larger payloads must use SharedMemory; see below.
   - **Encoded bytes** (`session.pushFrame(meta, encodedBytes,
     mimeType)`) — pre-encoded JPEG/PNG passed through to the
     server-side decoder. Same 1 MiB cap.
   - **SharedMemory handle** (`session.pushFrameShm(meta, shm)`) —
     for frames > 1 MiB on API 27+. The handle is mapped read-only
     server-side and unmapped immediately after intake decision.
   All three return `OcrFrameAck(status, queueDepth,
   retryAfterMs)`. The wire-stable status enum:
   - `STATUS_ACCEPTED (1)` — frame entered the engine queue.
   - `STATUS_DROPPED_BUSY (2)` — rate-limit token bucket exhausted;
     retry after `retryAfterMs`.
   - `STATUS_REJECTED_QUALITY (3)` — service-side presort rejected.
    - `STATUS_REJECTED_FINALIZED (4)` — session is finalizing/closed.
    - `STATUS_REJECTED_OVERSIZED (5)` — payload exceeded
      `MAX_FRAME_BYTES`; caller must downscale or switch to SHM.
    - `STATUS_REJECTED_STREAM_NOT_ATTACHED (6)` — the service rejected
      the frame because `streamOcrEvents` has not attached yet. Attach the
      event stream before pushing frames; otherwise OCR events could be lost.
3. **Poll** — `session.state()` returns `OcrSessionState` with
   counters + phase. Phase machine: `PHASE_ACTIVE → PHASE_FINALIZING
   → PHASE_FINALIZED → PHASE_CLOSED`.
4. **Stream events** — `session.events: Flow<OcrEvent>` over the
   `mindlayer.stream.ocr.v1` pipe. Phase 4 PR #2 wires the live
   event stream. Per-frame events:
   - `FRAME_PROCESSING` — engine has started this frame.
   - `FRAME_PROCESSED` — engine finished; carries `lineCount` and
     per-stage timings.
   - `FRAME_DROPPED` — frame entered the queue but was dropped
     before engine pickup (e.g. queue depth exceeded the per-session
     backpressure budget); the Flow stays open.
   - `RESULT_FINALIZED` / SDK `OcrEvent.ResultFinalized` — terminal
     event after `finalize()` drains in-flight frames. Carries the final
     fused JSON and is followed by stream `DONE` + pipe close.
   - `ERROR` / SDK `OcrEvent.Error` — terminal stream failure with typed
     code and optional redacted message. Added in Wave 1 PR #98 so
     collectors do not have to infer terminal failures from pipe closure.
5. **Finalize** — `session.finalize()` triggers the drain. Frames
   already in flight complete; frames pushed AFTER `finalize()` are
   rejected with `STATUS_REJECTED_FINALIZED` and the wire error
   `OCR_SESSION_FINALIZED (3008)`. The `events` Flow emits
   `RESULT_FINALIZED` then closes; the SDK collector terminates
   cleanly without an additional `close()`.
6. **Close** — `session.close()` / `session.use { }`. Idempotent.
   Equivalent to calling `finalize()` then discarding the channel.

### Backpressure

Phase 4 PR #2 introduced explicit backpressure surfaces alongside
the existing rate limiter:

- **Per-frame size guard** — `MAX_FRAME_BYTES = 1 MiB` matches the
  Android Binder transaction cap. Payloads above this fail with
  `STATUS_REJECTED_OVERSIZED` BEFORE traversing the binder.
- **Queue depth** — each `OcrFrameAck` carries `queueDepth` so the
  SDK can throttle ImageAnalysis. When the engine queue would
  overflow, the dispatcher emits `FRAME_DROPPED` for the oldest
  in-flight frame rather than backing up indefinitely.

### Auto-close conditions

The service tears the session down automatically when:

- Idle timeout: no `pushFrame` for `idleTimeoutMs` (default 30 s).
  Surfaces as `MindlayerErrorCode.OCR_IDLE_TIMEOUT (2004)`.
- Max-duration: `maxOcrSessionDurationMs` since creation, regardless
  of activity. Surfaces as `OCR_MAX_DURATION (2005)`.
- Per-session frame cap: `framesAccepted >= maxFramesPerOcrSession`
  triggers auto-finalize.
- Binder death: client process death drops all that UID's sessions.

## Quality presort

**Server-side presort** runs on every accepted frame regardless of
the client's `qualityHint`. The server-side
`OcrFrameQualityPresort` (`com.adsamcik.mindlayer.service.engine`)
scores each frame against pinned thresholds — see the source for
specific values.

**Client-side presort** (optional) via `:sdk-camerax`'s
`OcrFramePresort` mirrors the server thresholds 1:1 so the SDK can
drop bad frames before the binder round-trip. Cross-module drift is
guarded by a test that fails if any threshold goes out of sync.

Quality hints are advisory only — clients cannot bypass quality
gating by mis-labelling.

## CameraX integration (optional)

Add `com.adsamcik.mindlayer:sdk-camerax:<version>` alongside `:sdk`.
Provides `OcrImageAnalyzer` — an `ImageAnalysis.Analyzer` impl that:

1. Extracts the Y-plane from `ImageProxy` (defensive copy, handles
   row-stride padding).
2. Runs client-side presort, dropping bad frames before pushing.
3. Pushes accepted frame's `OcrFrameMeta` to the active session.
4. Closes `ImageProxy` immediately (CameraX contract).

```kotlin
val analyzer = OcrImageAnalyzer(
    session = session,
    runClientSidePresort = true,
    onAck = { frame, ack -> /* UI throttling feedback */ },
)
ImageAnalysis.Builder()
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
    .build()
    .also { it.setAnalyzer(executor, analyzer) }
```

CameraX is `compileOnly` on `:sdk-camerax` so consumers bring their
own version. The repo currently tests against CameraX 1.6.1.

Non-CameraX consumers (MediaProjection, Bitmap pipelines) use
`OcrFrame.fromYPlane(...)` directly and skip the analyzer.

## Privacy & security invariants

- **Offline only.** No `INTERNET` permission; no telemetry; no
  cloud fallback. Pinned by `.github/instructions/privacy-offline.instructions.md`
  and PR #51.
- **No prompt/output text persistence.** The service logs metadata
  only (token counts, durations, error labels). The SDK history
  store records `METADATA_ONLY` by default; full-content mode
  requires explicit opt-in.
- **Anti-enumeration.** Cross-UID session lookups all return
  `SESSION_NOT_FOUND_OR_NOT_OWNED (2001)` — a hostile client
  cannot probe whether a sibling app has an active session.
- **Client hint is advisory.** Service-side presort always runs;
  bad-quality frames are rejected regardless of `qualityHint`.
- **Bounded JSON.** `outputSchemaJson` / `optionsJson` / `regionJson`
  / `extraJson` are each capped at 16 KiB by `IpcInputValidator`.
- **Wire-frozen parcelables.** Per `docs/AIDL_STABILITY.md`, the
  five OCR parcelables carry `schemaVersion` first + reserved
  `featureFlags` bitfield. Adding fields requires a new method.

## Error codes (wire-stable)

| Code | Name | Category | When |
|---|---|---|---|
| 2004 | `OCR_IDLE_TIMEOUT` | SESSION | No frames for 30 s, session auto-closed |
| 2005 | `OCR_MAX_DURATION` | SESSION | 5-min wall-clock cap regardless of activity |
| 3007 | `OCR_SCHEMA_INVALID` | VALIDATION | Malformed config / unknown mode / non-monotonic frameId |
| 3008 | `OCR_SESSION_FINALIZED` | VALIDATION | Push to already-finalized session |
| 5015 | `FRAME_DROPPED_BUSY` | RESOURCE | Intake queue saturated (mirrors `OcrFrameAck.STATUS_DROPPED_BUSY`) |
| 5016 | `FRAME_REJECTED_QUALITY` | RESOURCE | Service-side presort rejection |

Error codes are returned as `SecurityException` with wire-prefixed
message (`mindlayer:<code>:<human-message>`); `MindlayerException`
parses them on the SDK side.

## Current state

| Surface | Current state | Gate / caveat |
|---|---|---|
| AIDL + SDK DSL | Implemented: create, push Y-plane / encoded / SHM frames, state, finalize, close, limits. | `FEATURE_OCR_SESSION` remains absent until production-ready. |
| Event streaming | Implemented: `streamOcrEvents` pipe, SDK `Flow<OcrEvent>`, terminal `OcrEvent.Error`, `FRAME_DROPPED`, and `RESULT_FINALIZED`. | Attach the stream before pushing; otherwise ack `STATUS_REJECTED_STREAM_NOT_ATTACHED`. |
| Backpressure | Implemented: `MAX_FRAME_BYTES` guard, queue-depth ack, busy/quality/finalized/oversized/stream-not-attached statuses. | Payloads above 1 MiB must use SharedMemory or be downscaled. |
| Engine + extraction | Implemented behind the service path with GPU-default OCR accelerator selection (CPU and NPU available via explicit caller request). | Same-process LiteRT/LiteRT-LM coexistence remains real-device-gated — see `docs/LITERT_COEXISTENCE.md`. |
| Public capability | Code exists but is dark. | `OcrFeatureFlags.IS_PRODUCTION_READY = false`; callers must capability-check and degrade. |
| Real-device validation | Required before production exposure. | Needs signed-off device matrix for model assets, thermal/memory behavior, and LiteRT coexistence. |

## Implementation status (Phase 1–4 + Wave 1)

`FEATURE_OCR_SESSION` is additionally guarded by
`OcrFeatureFlags.IS_PRODUCTION_READY`. Phase 4 leaves the flag
`false` until the device-validation matrix and a first-party driver
land (Phase 4 PR #6, single-line atomic flip). Callers must continue
to check `ServiceCapabilities.supports(ServiceCapabilities.FEATURE_OCR_SESSION)`
before opening sessions — the capability stays dark on every device
until both gates flip.

| Phase | Piece | Status | PR | Notes |
|---|---|---|---|---|
| 1 | Wire types, AIDL methods, model-pack scaffold, model registry, presort, field fusion, `PaddleOcrEngine` scaffold, session manager, SDK DSL, `:sdk-camerax` | Complete | #52–#59 | Established the frozen wire surface and client ergonomics. |
| 2 | LLM extraction, structured output, capability flip plumbing | Complete | #69–#70 | Engine-ready gate wired, but still product-flag gated. |
| 3 | Bounding boxes, barcode anchor, typed geometry/evidence events | Complete | #72–#74 | Wire-shape capabilities are additive and safe for old readers. |
| 4 | Safety gates, dictionary sanity, transient-init retry, log redaction, GPU-default-with-CPU-and-NPU-opt-in accelerator selection, real frame transports, finalization drain, `FRAME_DROPPED`, preprocessing/post-processing, coexistence gate, E2E smoke | Complete | #78, #85, #87–#89 | Event streaming is live; remaining production gate is `IS_PRODUCTION_READY=false`. |
| Wave 1 | Stream-attachment ack + terminal OCR error event | Complete | #92, #98 | `STATUS_REJECTED_STREAM_NOT_ATTACHED` prevents lost events; `OcrEvent.Error` surfaces terminal stream failures. |
| Release flip | `IS_PRODUCTION_READY` → true | Future | — | Single-line atomic flip after real-device validation and first-party driver sign-off. |
| Acceleration | Per-call GPU/NPU OCR opt-in | Available | — | OCR resolver mirrors chat (`null` → GPU; explicit `NPU` probed with GPU-fallback; explicit `CPU`/`GPU` honored). Same-process LiteRT/LiteRT-LM coexistence still requires real-device validation. |

### Accelerator selection (Phase 4 PR #4)

`LiteRtAcceleratorResolver` is the single owner of CPU/GPU/NPU
resolution for both embeddings and OCR. OCR mirrors chat: `null`
defaults to GPU; explicit `NPU` is probed (SoC allowlist + native
library check) and falls back to GPU if unsupported; explicit
`CPU` and `GPU` are always honored. The dashboard surfaces every
resolver decision via `LogRepository.logBackendDecision`. The
three sequential `CompiledModel` instances (det + rec + cls) make
this the highest-exposure site for LiteRT issue #5264 — run the
`docs/LITERT_COEXISTENCE.md` validation checklist on target
devices before relying on GPU/NPU OCR in production.

### Logging contract

Every reachable OCR error path goes through
`Throwable.safeLabel()` and passes `throwable = null` to
`MindlayerLog.{w,e}`. The persisted log row therefore carries the
exception **class chain only** — never a `message` / `cause.message`
fragment, which native LiteRT-LM errors can use to inline prompt or
file-path content. Pinned by
`LiteRtPaddleOcrBackendInitFailureSafeLabelTest` and the matching
embeddings Phase D matrix.

### Coverage map (PR #5)

The PR #5 backfill brings `LiteRtPaddleOcrBackend.kt` line coverage
to the agreed bar by exercising the previously untested branches:
missing-cls (no-cls AI Pack variant), runner-throws on each of
det/cls/rec, non-square detection output rejection, recognition
output not divisible by `dictionary.size + 1`, CTC blank-only output
(empty line dropped via `text.isNotBlank()`), all-NaN softmax
fallback, mutex serialisation of concurrent `recognise()` calls,
init → shutdown → init cycle, and the dictionary edge cases
(BOM normalization, bare CR rejection, length bounds, single-space
line preservation). The remaining uncovered lines are the
`defaultAvailableMemoryProvider` path (Android-only `ActivityManager`
service lookup) and the production `RealPaddleOcrLiteRtRunner.create`
factory — both exercised by the on-device coexistence test.

## References

- `files/final-design.md` — implementation-locked design notes.
- `.github/instructions/privacy-offline.instructions.md` — privacy +
  offline + security invariants.
- `docs/AIDL_STABILITY.md` — wire-stability rules the OCR
  parcelables follow.
- `.github/workflows/build-paddleocr-models.yml` — manual
  conversion pipeline for the four PaddleOCR PP-OCRv5 mobile
  `.tflite` artifacts.
