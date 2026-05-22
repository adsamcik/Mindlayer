# Multi-frame OCR / parsing API

> v0.8 Phase 1. Single source of truth for the Mindlayer multi-frame
> OCR API. Composed alongside `files/final-design.md` (session
> workspace) which carries the implementation-locked design notes.

## TL;DR

```kotlin
val mindlayer = Mindlayer.connect(context)
mindlayer.awaitConnected()

if (!mindlayer.getCapabilities().supports(ServiceCapabilities.FEATURE_OCR_SESSION)) {
    return  // Service hasn't shipped the engine path yet.
}

mindlayer.ocrSession(OcrProfile.Receipt) {
    languageHints = listOf("en", "de-DE")
    maxFrames = 30
    frameRateLimitFps = 5
}.use { session ->
    // For each captured frame: presort -> push -> read ack
    val ack = session.pushFrame(meta)
    when (ack.status) {
        OcrFrameAck.STATUS_ACCEPTED -> { /* keep capturing */ }
        OcrFrameAck.STATUS_DROPPED_BUSY -> { /* slow down, ack.retryAfterMs */ }
        OcrFrameAck.STATUS_REJECTED_QUALITY -> { /* recapture */ }
        OcrFrameAck.STATUS_REJECTED_FINALIZED -> { /* session ended */ }
    }
    session.finalize()
    // (Future) collect session.events.takeUntilFinalized().toList()
}
```

## Capability discovery

The service advertises OCR support via three flags in
`ServiceCapabilities.supportedFeatures`:

| Flag | Meaning |
|---|---|
| `FEATURE_OCR_SESSION` | The multi-frame OCR session API works (lifecycle + intake). **Not yet advertised** — flips when the engine recognition path is wired. |
| `FEATURE_OCR_PRESORT_SERVICE_SIDE` | Service runs its own quality presort. Advertised. |
| `FEATURE_OCR_BARCODE_ANCHOR` | ZXing barcode anchor in evidence package. Future. |
| `FEATURE_OCR_BOUNDING_BOXES` | Per-line bounding boxes in extraction output. Future. |

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
   - `RESULT_FINALIZED` — terminal event after `finalize()` drains
     in-flight frames. Carries the fused output.
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
own version. The minimum tested CameraX is 1.3.

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

## Implementation status (Phase 1 -> 4)

`FEATURE_OCR_SESSION` is additionally guarded by
`OcrFeatureFlags.IS_PRODUCTION_READY`. Phase 4 leaves the flag
`false` until the device-validation matrix and a first-party driver
land (Phase 4 PR #6, single-line atomic flip). Callers must continue
to check `ServiceCapabilities.supports(ServiceCapabilities.FEATURE_OCR_SESSION)`
before opening sessions — the capability stays dark on every device
until both gates flip.

| Phase | Piece | Status | PR | Notes |
|---|---|---|---|---|
| 1 | Wire types + AIDL surface | Merged | #52 | Wire-frozen parcelables. |
| 1 | `paddleocr_model` AAB + CI conversion | Merged | #53 | Install-time AI Pack scaffold. |
| 1 | Model registry + frame presort + field fusion | Merged | #55 | Service-side presort. |
| 1 | `PaddleOcrEngine` scaffold | Merged | #56 | Lazy engine lifecycle. |
| 1 | `OcrSessionManager` + binder wiring | Merged | #57 | Session lifecycle/intake surface. |
| 1 | SDK DSL + 5 profiles | Merged | #58 | Capability checks use `ServiceCapabilities.supports(...)`. |
| 1 | `:sdk-camerax` optional module | Merged | #59 | CameraX helper module. |
| 2 | LLM extraction + structured output | Merged | #69 | `OcrLlmExtractor` Strategy A reset-per-frame KV. |
| 2 | `FEATURE_OCR_SESSION` capability flip wiring | Merged | #70 | Engine-ready gate (still product-flag gated below). |
| 3 | Bounding boxes + barcode anchor | Merged | #72–#74 | Wire-shape features always advertised. |
| 4 | Safety gates + dictionary sanity + transient-init retry + log redaction + CPU-only fallback | Merged | #78 (Phase 4 PR #1) | Production flag, `safeLabel` redaction on inference paths, dictionary length/encoding validation. |
| 4 | Real frame transport (Y-plane + encoded + SHM) + per-session finalization + drain semantics + `FRAME_DROPPED` | Merged | #85 (Phase 4 PR #2) | Three transports, `MAX_FRAME_BYTES` guard, `STATUS_REJECTED_OVERSIZED`. |
| 4 | Bilinear sampling + aspect-preserved rec preprocessing + pure-Kotlin `DbPostProcessor` (Suzuki–Abe + minAreaRect + unclip + NMS) | Merged | #87 (Phase 4 PR #3) | Algorithm correctness; numeric ICDAR2015 validation still device-gated. |
| 4 | Shared `LiteRtAcceleratorResolver` + per-feature override | Merged | (Phase 4 PR #4) | OCR is CPU-locked under reason `OCR_CPU_LOCK_UNTIL_COEXISTENCE_VALIDATED`. |
| 4 | OCR end-to-end smoke (`OcrEndToEndInstrumentedTest`) | Merged | #88 | Coverage reference for the live OCR pipe. |
| 4 | SDK Robolectric end-to-end (`OcrSdkEndToEndTest`) | Merged | #89 (Phase 4 PR #2) | Three transports + finalization drain on the SDK side. |
| 4 | Backend test backfill + EngineCoexistence production gate + OCR_API refresh | This PR | Phase 4 PR #5 | Init-failure matrix, runner-throws, mutex, CTC blank-only, dictionary edge cases, `OcrFeatureFlagsTest`, `@assumeTrue`-gated real-asset coexistence variant. |
| 4 | `IS_PRODUCTION_READY` → true | Future | Phase 4 PR #6 | Single-line atomic flip; gated on a signed-off device-validation matrix + first-party driver. |
| 4 | Per-call GPU/NPU opt-in | Future | Phase 4 PR #7 | Once the process-wide accelerator lease is mature. |

### Accelerator selection (Phase 4 PR #4)

`LiteRtAcceleratorResolver` is the single owner of CPU/GPU/NPU
resolution for both embeddings and OCR. OCR currently forces the
backend to CPU regardless of `preferredBackend` with the recorded
reason `OCR_CPU_LOCK_UNTIL_COEXISTENCE_VALIDATED` — the dashboard
surfaces the downgrade alongside any GPU/NPU downgrade taken by the
embedding stack. Once the coexistence checklist signs off and PR #6
ships the production flag, OCR can opt back into the resolver's
GPU/NPU path on allowlisted SoCs.

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
