# Mindlayer SDK v1 Migration Guide (`1.0.0-alpha01`)

This release finalizes the v1 SDK surface. The canonical builders and terminals
are now implemented; several legacy types and methods were renamed, hidden, or
removed. This document lists what changed and the intentional alpha-stage
deviations consumers should be aware of.

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
