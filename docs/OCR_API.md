# Multi-frame OCR / parsing API

> v0.8 Phase 1. Single source of truth for the Mindlayer multi-frame
> OCR API. Composed alongside `files/final-design.md` (session
> workspace) which carries the implementation-locked design notes.

## TL;DR

```kotlin
val mindlayer = Mindlayer.connect(context)
mindlayer.awaitConnected()

if (!mindlayer.getCapabilities().supportsFeature(ServiceCapabilities.FEATURE_OCR_SESSION)) {
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
2. **Push** — `session.pushFrame(meta)` →
   `OcrFrameAck(status, queueDepth, retryAfterMs)`. The wire-stable
   status enum:
   - `STATUS_ACCEPTED (1)` — frame entered the engine queue.
   - `STATUS_DROPPED_BUSY (2)` — rate-limit token bucket exhausted;
     retry after `retryAfterMs`.
   - `STATUS_REJECTED_QUALITY (3)` — service-side presort rejected.
   - `STATUS_REJECTED_FINALIZED (4)` — session is finalizing/closed.
3. **Poll** — `session.state()` returns `OcrSessionState` with
   counters + phase. Phase machine: `PHASE_ACTIVE → PHASE_FINALIZING
   → PHASE_FINALIZED → PHASE_CLOSED`.
4. **Stream** (future) — `session.events: Flow<OcrEvent>` over the
   `mindlayer.stream.ocr.v1` pipe. Currently empty; the service
   closes the pipe immediately until the engine recognition path
   lands.
5. **Finalize** — `session.finalize()` drains in-flight work and
   emits the final `OcrEvent.ResultFinalized` (once events flow).
6. **Close** — `session.close()` / `session.use { }`. Idempotent.

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

## Implementation status (Phase 1)

| Piece | Status | PR |
|---|---|---|
| Wire types + AIDL surface | ✅ Merged | #52 |
| `paddleocr_model` AAB + CI conversion | ✅ Merged | #53 |
| Model registry + frame presort + field fusion | ✅ Merged | #55 |
| `PaddleOcrEngine` scaffold | ✅ Merged | #56 |
| `OcrSessionManager` + binder wiring | ✅ Merged | #57 |
| SDK DSL + 5 profiles | ✅ Merged | #58 |
| `:sdk-camerax` optional module | 🔄 PR | #59 |
| Integration tests + this doc + CI gates | 🔄 PR | (this PR) |

## Roadmap (after Phase 1)

| Follow-up | What lands |
|---|---|
| PR-engine-pipeline | Native PP-OCRv5 mobile det/cls/rec wired against LiteRT |
| PR-y-plane-extraction | Service-side Y-plane decode from `MediaPart` in `pushOcrFrame` |
| PR-event-stream | `OCR_V1` stream pipe writer + Flow consumer |
| PR-feature-flip | `FEATURE_OCR_SESSION` added to `SUPPORTED_FEATURES` |
| PR-llm-extraction | Gemma structured extraction pass + field fusion integration |
| PR-barcode-anchor | ZXing barcode anchor evidence injection |
| PR-bounding-boxes | Per-line bounding box emit (gated by `FEATURE_OCR_BOUNDING_BOXES`) |

## References

- `files/final-design.md` — implementation-locked design notes.
- `.github/instructions/privacy-offline.instructions.md` — privacy +
  offline + security invariants.
- `docs/AIDL_STABILITY.md` — wire-stability rules the OCR
  parcelables follow.
- `.github/workflows/build-paddleocr-models.yml` — manual
  conversion pipeline for the four PaddleOCR PP-OCRv5 mobile
  `.tflite` artifacts.
