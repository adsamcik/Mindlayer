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
  ```` ```json ```` fences and isolates the outermost JSON object). As of the
  current `[Unreleased]` line, `infer { outputJson(schema) }` and the
  `extractJson(...)` helper now **enforce** the schema: the ephemeral session is
  created with the `{"structured_output":{schema,strategy,max_retries,validation_depth}}`
  envelope (the same one `SessionConfigBuilder.jsonOutput { }` emits), so the
  service validates `enum`/`type`/`required`/… and retries or fails closed.
  Strategy defaults to `PromptAndValidate` (pass
  `outputJson(schema, JsonOutputStrategy.ToolRouting)` to switch);
  `max_retries`/`validation_depth` use the `JsonOutputBuilder` defaults (`3` /
  `shallow`). `awaitJson()` itself still does the lenient client-side extraction —
  enforcement is server-side. Note: this wiring applies to ephemeral sessions
  (`infer { outputJson(...) }` / `extractJson(...)`); for a **named** session,
  configure the schema up-front via `openSession { jsonOutput { … } }` (or
  `extraContextJson`), because a per-`infer` `outputJson` cannot re-configure an
  already-created session.
- `InferenceHandle.Tools.awaitToolCalls(): List<ToolCall>`

### 3. Cancellation API removed
Public `InferenceHandle.cancel()` / `isCancelled` are removed. Cancellation is
handled internally by `Conversation.close()` and session teardown. There is no
consumer-facing replacement in alpha.

### 4. `prewarm` / `getEngineInfo` promoted to the interface
`connect()` returns the `Mindlayer` interface. `prewarm(backend)` and
`getEngineInfo()` are now declared on that interface (previously only on the
implementation), so consumers can call them on the connected handle.

### 5. Public `SessionScope.jsonOutput { }` DSL (+ `SessionScope` breaking change)
Structured JSON output is now a first-class, typed session knob — apps no longer
hand-build the `extraContextJson` `structured_output` envelope string:

```kotlin
// before — hand-built envelope string, easy to get the wire shape wrong:
mindlayer.openSession {
    extraContextJson =
        """{"structured_output":{"schema":{"type":"object","properties":{"severity":{"enum":["low","high"]}}},"strategy":"prompt_and_validate","max_retries":3,"validation_depth":"shallow"}}"""
}

// after — typed DSL, single-sourced through JsonOutputBuilder:
mindlayer.openSession {
    systemPrompt = "You classify support tickets."
    jsonOutput {
        schema("""{"type":"object","properties":{"severity":{"enum":["low","high"]}},"required":["severity"]}""")
        strategy(JsonOutputStrategy.PromptAndValidate)
        maxRetries(3)
        validationDepth(JsonValidationDepth.SHALLOW)
    }
}
```

The same `jsonOutput { }` works inside `infer { ephemeralSession { … } }`. It
merges the envelope into `extraContextJson` (preserving other keys; replacing an
existing `structured_output`).

Two **breaking** knock-on changes:

- `SessionScope.toolsJson` and `SessionScope.extraContextJson` changed from `var`s
  with no-op default getters/setters to **abstract** interface members. Callers of
  `openSession { }` / `infer { ephemeralSession { } }` are unaffected (the
  first-party implementer already backed them with real fields). Only code that
  *directly implements* `SessionScope` (e.g. an `object : SessionScope { }`) must
  now provide both properties.
- `JsonOutputBuilder.validation(depth)` was renamed to `validationDepth(depth)`
  (matches the DSL example above and the internal field). Update any call sites.

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
