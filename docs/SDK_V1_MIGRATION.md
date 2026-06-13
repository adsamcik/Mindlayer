# Mindlayer SDK v1 Migration Guide (`1.0.0-alpha02`)

This release finalizes the v1 SDK surface. The canonical builders and terminals
are now implemented; several legacy types and methods were renamed, hidden, or
removed. This document lists what changed and the intentional alpha-stage
deviations consumers should be aware of.

## `1.0.0-alpha.2` — all deprecated methods removed

Every remaining `@Deprecated` carry-over was deleted. The canonical surface was
first enriched into a **strict superset**, so each removed method has a
1:1 (or richer) replacement. Map your call sites as follows:

### Embeddings

| Removed | Replacement |
|---|---|
| `embedOne(text, task, modelId, outputDim, normalize, tag)` | `vector(text, task)` — for the knobs use `embed { text(text, tag, task, modelId, outputDim, normalize) }.awaitVector()` |
| `embed(text)` | `vector(text)` |
| `embed(config): EmbeddingResult` | `embed { text(config.text, …) }` → read the enriched `EmbeddingResultItem` (`dim`/`modelId`/`tokenCount`/`truncated`/`backend`/`durationMs`) |
| `embedMany(configs)` / `embedMany(texts, …)` / `embedBatch` / `embedBatchLarge` | `embed { items(listOf(EmbeddingItem(...))) }.awaitVectors()` or `vectors(items)` — transport is selected automatically |

### OCR

| Removed | Replacement |
|---|---|
| `ocrAsync(bytes, mime, options)` | `ocr { image(bytes, mime); /* emitBoundingBoxes(); languageHints; extractWithLlm(schema) */ }.awaitResult()` → `OcrResult` (timing on `metrics.ocrDurationMs`/`llmDurationMs`, `metrics.backend`, `extractionFields`, per-line `boundingBoxQuad`/`orientationDegrees`) |
| `ocrRealtime(profile, …)` / `ocrRealtime(config)` | `ocrSession { profile(...); … }` → `OcrHandle.MultiFrame` (`pushFrame(meta, yPlane, w, h, …)` for raw frames, `finalize()` → `OcrResult`, `state()`) |

### Inference & sessions

| Removed | Replacement |
|---|---|
| `createSession { … }: String` | `openSession { … }` (`MindlayerSession`, `.id` for the raw id); tool sessions via `openSession { toolsJson = "…" }`, opaque context via `extraContextJson` |
| `chat(sid, text)` / `inferRealtime(sid, text, *media)` | `infer { session(sid); text(text); image(...)/audio(...)/media(...) }` (streams) |
| `chatWithImage` / `chatWithAudio` / `chatWithMedia` | `infer { session(sid); text(text); image(bitmap) / audio(file) / media(part) }` |
| `inferAsync(sid, text, *media)` | `infer { session(sid); text(text); … }.awaitText()` |
| `inferTools(sid, text, *media)` | `infer { session(toolSessionId); text(text); … }` — `ToolCall` events stream; answer with `submitToolResultDetailed` |
| `chat(text)` / `generate(text, …)` | `ask(text) { … }` |
| `chat(text, image)` / `generateWithImage(…)` | `describe(text, image) { … }` |
| `generateWithAudio(…)` | `transcribe(prompt, audio) { … }` |
| `chatOnce` / `chatWithImageOnce` / `chatWithAudioOnce` | `infer { session(sid); … }.awaitText()` |
| `chatTextFlow` / `chatFullTextFlow` | `infer { session(sid); … }.events` (filter `InferenceEvent.TextDelta`) |
| `awaitConnected()` | `awaitConnected(kotlin.time.Duration.INFINITE)` |

### Module breaking changes

- `:sdk-camerax` `OcrImageAnalyzer(session: OcrSession, …)` → `OcrImageAnalyzer(session: OcrHandle.MultiFrame, …)`. Obtain the session via `ocrSession { profile(...) }`.
- `:sdk-camera-launcher` `OcrCaptureResult.Async` now exposes `fullJson: String`, `extractionJson: String?`, and timing primitives instead of an `OcrImageResult` (canonical `OcrResult.fullJson` is a non-`Parcelable` `JsonObject`).

## Breaking changes

### 1. Event stream: `MindlayerEvent` → `InferenceEvent`
The streaming event type is now a single `sealed class InferenceEvent`:

| Variant | Notes |
|---------|-------|
| `Started(requestId)` | emitted once at stream open |
| `TextDelta(text, seq?)` | incremental text; `seq` is nullable in alpha |
| `ToolCall(toolName, arguments, callId, seq?)` | tool invocation |
| `Metrics(...)` | terminal perf metrics (see `Metrics.kt`) |
| `Error(message, code?, seq?, tsMs?, codeInt?)` | terminal error |
| `Done(finishReason, fullText?, seq?)` | terminal success |

Helper extensions `textDeltas()` and `throwOnError()` are provided.

### 2. Canonical builders are now implemented
`infer { }`, `ocr { }`, and `openSession { }` previously threw
`NotImplementedError`. They now bridge onto the legacy one-shot methods and
return working handles. Terminals:

- `InferenceHandle.Text.awaitText(): String`
- `InferenceHandle.Structured.awaitJson(): JsonObject` (lenient parse — strips
  ```` ```json ```` fences and isolates the outermost JSON object)
- `InferenceHandle.Tools.awaitToolCalls(): List<ToolCall>`

### 3. Cancellation API removed
Public `InferenceHandle.cancel()` / `isCancelled` are removed. Cancellation is
handled internally by `Conversation.close()` and session teardown. There is no
consumer-facing replacement in alpha.

### 4. `prewarm` / `getEngineInfo` promoted to the interface
`connect()` returns the `Mindlayer` interface. `prewarm(backend)` and
`getEngineInfo()` are now declared on that interface (previously only on the
implementation), so consumers can call them on the connected handle.

## Intentional alpha deviations

- **Eager (non-streaming) `infer` bridge.** The `infer { }` path runs the legacy
  one-shot under the hood and replays a synthetic
  `Started → TextDelta(full) → Done` stream. For true token streaming, use the
  legacy streaming methods directly.
- **`SamplerScope.seed` is dropped.** The underlying `SessionConfigBuilder` has
  no seed field, so a seed supplied through `infer { }` is ignored.
- **Tool-calling via `infer { }` is not wired.** Requesting tools through the
  canonical builder throws `NOT_SUPPORTED`; `submitToolResult` throws
  `NotImplementedError`.
- **OCR bounding boxes are denormalized.** The legacy engine returns an 8-float
  normalized quad `[x1,y1,…,x4,y4]` (0..1, clockwise). The v1 `OcrLine.boundingBox`
  is an axis-aligned source-pixel rect `[left, top, right, bottom]`, computed by
  collapsing the quad against the decoded image dimensions. Returns `null` when
  the quad or image dimensions are unavailable.
- **`ocrSession` / `embed` remain unimplemented** and throw `NOT_SUPPORTED`.
- **`InferenceEvent.seq` is nullable** in alpha (the spec models it as a
  monotonic `Long`).
