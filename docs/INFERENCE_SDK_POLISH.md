# Inference SDK Polish — design proposal

> Status: **proposal**. This document describes the eventual coherent shape for
> the inference public surface of `:sdk` and the **minimal slice** that lands
> in PR `feat/inference-sdk-polish`. The wire surface (`IMindlayerService`
> AIDL, `:shared` parcelables, stream protocol) is **frozen** for this PR per
> [`docs/AIDL_STABILITY.md`](AIDL_STABILITY.md); any future AIDL change is
> a separate, capability-gated proposal.

## Problem statement

The inference public surface on [`Mindlayer.kt`](../sdk/src/main/kotlin/com/adsamcik/mindlayer/sdk/Mindlayer.kt)
has grown organically across six wire generations (v0.1 → v0.8.1) and now
spreads inference across **15+ public methods** with three different naming
families (`chat*`, `*Once`, `generate*`), two different return shapes
(`InferenceHandle` vs `String`), and at least two different parameter idioms
(separate `Bitmap`/`File` arguments vs `vararg MediaPart`).

Consumer apps reading the surface for the first time hit decision fatigue
before they have written a single line of inference code:

> *"Do I want `chat`, `chatOnce`, `chatWithImage`, `chatWithImageOnce`,
> `chatWithMedia`, `generate`, `generateWithImage`, `chatTextFlow`,
> `chatFullTextFlow`, `chatDeferred`, or `session(...).chat(...)`?"*

The wire surface is sound. The Kotlin facade has accreted naming and shape
inconsistencies that we can fix without breaking the wire.

## Goals of this PR

1. **Land a coherent top-level entry point trio** — `inferRealtime`,
   `inferAsync`, `inferTools` — as the canonical inference API.
2. **Deprecate the sprawled aliases** with `ReplaceWith` so old callers get
   IDE-driven migration without runtime breakage.
3. **Consolidate the error / capability matrix** into a single class-level
   KDoc table on `Mindlayer` so consumers don't have to grep through 15
   method-level KDocs to learn the throw contract.
4. **Avoid all wire-level changes** — no AIDL edits, no parcelable shape
   changes, no removed methods.

## Non-goals (explicitly deferred to follow-up PRs)

- **AIDL changes**: not in this PR. If the consolidated facade later needs a
  new method shape (e.g. unified `infer(InferenceRequest)`), that's a separate
  capability-gated AIDL evolution.
- **Removing deprecated methods**: not in this PR or the next one. Deprecation
  is a long-tail process; targeted removal happens after at least two
  consumer-app release cycles confirm no remaining call sites.
- **Consolidating parameter structs** (`InferenceRequest` / `GenerationOptions`
  Parcelables): a *separate* design pass. The current AIDL takes parameters
  positionally; turning that into a structured request envelope is a wire
  break and needs the v2 capability story.
- **OCR or embedding surfaces**: owned by sister polish PRs.
- **Stateless one-shot `chat(text)` / `generate(text)`** (no `sessionId`):
  a different shape (creates + destroys a session). Left untouched in this
  PR; the design doc proposes folding them into `inferAsync` in a follow-up.

---

## Inventory — every public method on `Mindlayer.kt` that touches inference

Grouped by use case. All methods are in
`com.adsamcik.mindlayer.sdk.Mindlayer`.

### 1. Streaming chat (returns `InferenceHandle` with `Flow<MindlayerEvent>`)

| Method | Parameters | Underlying AIDL | Notes |
|---|---|---|---|
| `chat(sessionId, text)` | text only | `infer(meta, null, null, pfd)` | v0.1 legacy |
| `chatWithImage(sessionId, text, bitmap)` | one `Bitmap` | `infer(meta, image, null, pfd)` | v0.1 legacy |
| `chatWithAudio(sessionId, text, audioFile)` | one `File` | `infer(meta, null, audio, pfd)` | v0.1 legacy |
| `chatWithMedia(sessionId, text, vararg parts)` | ordered `MediaPart[]` | `inferMulti(meta, parts, pfd)` with fallback | v0.4 multi-modal; transparent fallback to legacy `infer` if `FEATURE_MEDIA_LIST` absent |

### 2. One-shot blocking (returns `String`, collects the stream to completion)

| Method | Parameters | Delegates to | Notes |
|---|---|---|---|
| `chatOnce(sessionId, text)` | text only | `chat` + `collectHandleToString` | |
| `chatWithImageOnce(sessionId, text, bitmap)` | one `Bitmap` | `chatWithImage` + collect | |
| `chatWithAudioOnce(sessionId, text, audioFile)` | one `File` | `chatWithAudio` + collect | |

### 3. Text-delta-only `Flow<String>` helpers

| Method | Output | Notes |
|---|---|---|
| `chatTextFlow(sessionId, text)` | per-delta `Flow<String>` | filters out `Started`, `Metrics`; raises on `Error`/`ToolCall` |
| `chatFullTextFlow(sessionId, text)` | cumulative `Flow<String>` | each emission is the running prefix |

### 4. Stateless one-shot (creates + destroys a temporary session)

| Method | Parameters | Notes |
|---|---|---|
| `chat(text)` | text only | thin alias for `generate(text)` |
| `chat(text, image)` | text + `Bitmap` | thin alias for `generateWithImage(text, image)` |
| `generate(text, configure)` | text + `SessionConfigBuilder` block | creates session, runs `chatOnce`, destroys session |
| `generateWithImage(text, bitmap, configure)` | + `Bitmap` | as above |
| `generateWithAudio(text, audioFile, configure)` | + `File` | as above |

### 5. Deferred (fire-and-forget, server-side persisted)

| Method | Parameters | Underlying AIDL | Capability |
|---|---|---|---|
| `chatDeferred(sessionId, text, media)` | `List<MediaPart>` | `inferDeferred(meta, media)` | `FEATURE_DEFERRED_INFERENCE` |
| `fetchDeferredResult(requestId)` | | `fetchDeferredResult` | same |
| `cancelDeferred(requestId)` | | `cancelDeferredInference` | same |
| `acknowledgeDeferred(requestId)` | | `acknowledgeDeferredResult` | same |
| `awaitDeferred(requestId, poll, timeout)` | | push (`deferredCompletions`) + poll fallback | same |
| `deferredCompletions()` | | `IClientCallback.onDeferredInferenceComplete` | same |

### 6. Tool-calling round-trip

| Method | Parameters | Underlying AIDL | Capability |
|---|---|---|---|
| `submitToolResult(requestId, callId, toolName, resultJson)` | | `submitToolResult` | always (v0.1) |
| `submitToolResultDetailed(...)` | | `submitToolResultV2` with fallback | `FEATURE_DETAILED_CANCEL` |

### 7. Cancel

| Method | Parameters | Underlying AIDL | Capability |
|---|---|---|---|
| `cancelInference(requestId)` | | `cancelInference` | always |
| `cancelInferenceDetailed(requestId)` | | `cancelInferenceV2` with fallback | `FEATURE_DETAILED_CANCEL` |
| `InferenceHandle.cancel()` | | piped through to `cancelInference` | always |

### 8. Convenience wrappers

| Method | Notes |
|---|---|
| `session(sessionId)` | returns a `MindlayerSession` — thin per-session wrapper |
| `conversation { … }` | returns a `Conversation` — multi-turn DSL with built-in session creation |

---

## Seams — where consumers get confused

### S1. Two name families, same job

`chat*` and `generate*` both produce text from text(+media):

- `chat(sessionId, text)` requires an existing session, returns a streaming
  `InferenceHandle`.
- `chat(text)` (the no-sessionId overload) creates a temp session, returns
  a complete `String`.
- `generate(text)` is a third name for the second behavior, with an exposed
  configuration DSL.

Same English verb, three different shapes. Choosing between them requires
reading three KDocs.

### S2. `*Once` suffix implies "happens once" but actually means "returns a single value"

`chatOnce` returns a complete response `String` instead of a streaming flow.
The "once" prefix made sense relative to `chat`'s streaming flow, but read
on its own it sounds like rate-limiting or a once-per-session call. Users
hit `chatOnce` and ask, "*Can I call it more than once?*"

### S3. Naming inconsistency between legacy and modern media

`chat(sessionId, text)` → `chat(sessionId, text, MediaPart...)` would be
the natural extension. Instead, the v0.4 evolution introduced
`chatWithMedia` while `chat` stayed text-only. Consumers can't reach the
new path without learning a new verb.

### S4. `chatTextFlow` vs `chat` is shape mismatch, not parameter mismatch

`chatTextFlow` accepts the same `(sessionId, text)` as `chat` but returns
a different type. The verb naming hides the type contract.

### S5. Capability surface scattered across KDoc paragraphs

Every method documents its own throw set. To answer "*which features does
this service need to advertise for my inference to work?*", users have to
read ~10 KDocs. The class-level summary on `Mindlayer` mentions none of
this today.

### S6. Tool-calling is not a discoverable entry point

A consumer wanting to use tool calling reads the `tools { }` DSL in
`SessionConfigBuilder`, sees no top-level method called anything related
to tools, and has to grep for `submitToolResult` to discover the round-trip.
The intended pattern (`chat()` → collect → on `ToolCall` → `submitToolResultDetailed()`)
is in `SDK_INTEGRATION.md` but invisible at the method-discovery level.

---

## Proposed shape

Three top-level entry points become the canonical inference API. All three
take the same `(sessionId, text, vararg media)` shape so consumers can move
between them without re-learning parameters.

### `Mindlayer.inferRealtime(sessionId, text, vararg media) → InferenceHandle`

The canonical streaming chat path. Returns an `InferenceHandle` whose
`events: Flow<MindlayerEvent>` yields `Started`, `TextDelta`, `ToolCall`,
`Metrics`, and terminates with `Done` or `Error`.

- **Replaces**: `chat`, `chatWithImage`, `chatWithAudio`, `chatWithMedia`.
- **Wire shape**: when `media.isEmpty()` it routes to legacy `infer(meta, null, null, pfd)` (same path as today's `chat`); otherwise it routes through `inferMulti` with transparent legacy fallback (same path as `chatWithMedia`).
- **Capability**: `FEATURE_MEDIA_LIST` opportunistically — the SDK falls back transparently when absent for ≤1 image + ≤1 audio per request. Pre-v0.4 services with >1 media element get `INVALID_REQUEST` from the legacy fallback path.

### `Mindlayer.inferAsync(sessionId, text, vararg media) → String`

Single-shot, collects the full response and returns it as a `String`.
A clean coroutine-suspend wrapper around the streaming path.

- **Replaces**: `chatOnce`, `chatWithImageOnce`, `chatWithAudioOnce`.
- **Today**: thin wrapper that calls `inferRealtime(...)` and runs the
  existing `collectHandleToString` helper.
- **Future** (follow-up PR): when the connected service advertises
  `FEATURE_DEFERRED_INFERENCE`, callers will be able to opt into routing
  through `inferDeferred` for resilience against client process death.
  Same return type; only the internal transport changes.
- **Tool-call posture**: this method assumes no tool round-trip is needed;
  a `ToolCall` event mid-stream is converted to a typed
  `MindlayerException` (code `UNSUPPORTED_TOOL_CALL`). Callers that
  expect tools must use `inferTools` instead.

### `Mindlayer.inferTools(sessionId, text, vararg media) → InferenceHandle`

The canonical entry for sessions that have registered tools via
`SessionConfigBuilder.tools { }`. Returns the same `InferenceHandle` as
`inferRealtime`, but its discoverable name + KDoc surface the tool-call
contract loud and clear.

- **Today**: thin facade over `inferRealtime(...)`. The streaming contract
  is unchanged; the rename + KDoc make the intended pattern
  (`collect → on ToolCall → submitToolResultDetailed`) discoverable.
- **Future** (follow-up PR): grow into a higher-level overload that takes
  a `Map<String, suspend (argsJson) -> resultJson>` handler map and runs
  the round-trip loop automatically inside the SDK. That's a non-trivial
  API addition (cancellation semantics, error propagation, history
  bookkeeping per tool call) and deserves its own PR.

### Shared parameter shape

```kotlin
suspend fun inferRealtime(
    sessionId: String,
    text: String,
    vararg media: MediaPart,
): InferenceHandle

suspend fun inferAsync(
    sessionId: String,
    text: String,
    vararg media: MediaPart,
): String

suspend fun inferTools(
    sessionId: String,
    text: String,
    vararg media: MediaPart,
): InferenceHandle
```

The same `(sessionId, text, vararg media)` triple appears on all three. Build
media via the existing
[`MediaTransfer.imagePart(...)`](../sdk/src/main/kotlin/com/adsamcik/mindlayer/sdk/MediaTransfer.kt)
/ `audioPart(...)` helpers — those are unchanged by this PR.

### What stays the same

The following surfaces are intentionally untouched in this PR:

- **Deferred family** (`chatDeferred`, `fetchDeferredResult`, …) — these are
  cleanly named already; they ride a different end-to-end lifecycle and
  rebranding them now would just add a second name family to the surface.
- **Stateless one-shot** (`chat(text)`, `generate(text)`, etc.) — different
  parameter shape (no `sessionId`). A future PR may fold them into
  `inferAsync` via an overload that creates+destroys the session.
- **Text-delta flow helpers** (`chatTextFlow`, `chatFullTextFlow`) — these
  are an *alternative output shape* on top of streaming, useful for direct
  UI binding. Best kept around as convenience wrappers; could be renamed
  in a follow-up to `inferRealtimeTextFlow` / `inferRealtimeTextFlowCumulative`.
- **Tool result + cancel** (`submitToolResultDetailed`, `cancelInferenceDetailed`)
  — referenced by `inferTools` KDoc; keep their names.
- **Session/conversation wrappers** (`session()`, `conversation { }`) — these
  are correctly scoped one level above the inference verbs.

---

## Deprecation strategy

| Existing method | Becomes | Replacement |
|---|---|---|
| `chat(sessionId, text)` | `@Deprecated` (`WARNING`) | `inferRealtime(sessionId, text)` |
| `chatWithImage(sessionId, text, bitmap)` | `@Deprecated` (`WARNING`) | `inferRealtime(sessionId, text, MediaTransfer.imagePart(bitmap))` |
| `chatWithAudio(sessionId, text, file)` | `@Deprecated` (`WARNING`) | `inferRealtime(sessionId, text, MediaTransfer.audioPart(file))` |
| `chatWithMedia(sessionId, text, vararg parts)` | `@Deprecated` (`WARNING`) | `inferRealtime(sessionId, text, *parts)` |
| `chatOnce(sessionId, text)` | `@Deprecated` (`WARNING`) | `inferAsync(sessionId, text)` |
| `chatWithImageOnce(sessionId, text, bitmap)` | `@Deprecated` (`WARNING`) | `inferAsync(sessionId, text, MediaTransfer.imagePart(bitmap))` |
| `chatWithAudioOnce(sessionId, text, file)` | `@Deprecated` (`WARNING`) | `inferAsync(sessionId, text, MediaTransfer.audioPart(file))` |

All deprecation **stays at `WARNING` level** for at least two consumer-app
release cycles. The bodies of the deprecated methods are **unchanged** —
the new facades delegate to them, not the other way around. This keeps the
review surface minimal (no behavior change on any production code path).

We deliberately **do not** deprecate:

- `chat(text)` / `chat(text, image)` — different shape, see non-goals.
- `generate(...)` family — different shape, see non-goals.
- `chatTextFlow`, `chatFullTextFlow` — alternative output shape; renaming
  proposal stays at design-only.
- Deferred and tool-result methods — different lifecycle, see "What stays
  the same".

### Removal timeline

Removal of the deprecated aliases is **out of scope for this PR**. A
follow-up issue will track:

1. Two consumer-app release cycles at `WARNING` level (minimum).
2. Promote to `ERROR` level for one further release cycle.
3. Remove the alias methods in a clearly-flagged breaking-change PR.

Total time horizon: at least three consumer release cycles before any alias
disappears. The bar is "no caller breaks" — the AIDL surface is unchanged
throughout, so the wire stays compatible regardless of which alias the
caller is using.

---

## Error / capability matrix

This table is reproduced verbatim in the class-level KDoc on `Mindlayer`
so consumers don't have to grep through method-level KDocs.

| Method | Capability flag | Throws (typed) | Throws (untyped) |
|---|---|---|---|
| `inferRealtime` | none mandatory; `FEATURE_MEDIA_LIST` if `media.isNotEmpty()` (transparent fallback) | `MindlayerException` via stream `ERROR` frame; `MindlayerException` from binder auth gate | `RemoteException` on binder death; `IllegalArgumentException` on builder-side validation |
| `inferAsync` | same as `inferRealtime` | same as `inferRealtime`; additionally `MindlayerException(UNSUPPORTED_TOOL_CALL)` if a `ToolCall` event arrives | same |
| `inferTools` | same as `inferRealtime` | same as `inferRealtime` (consumer is responsible for handling `ToolCall` events and calling `submitToolResultDetailed`) | same |
| `submitToolResultDetailed` | `FEATURE_DETAILED_CANCEL` (transparent fallback to legacy `submitToolResult`) | `MindlayerException` on auth-gate failures | `RemoteException` |
| `cancelInferenceDetailed` | `FEATURE_DETAILED_CANCEL` (transparent fallback) | `MindlayerException` | `RemoteException` |
| `chatDeferred` | `FEATURE_DEFERRED_INFERENCE` (hard requirement) | `MindlayerException(NOT_SUPPORTED)` when capability missing | `RemoteException` |
| `fetchDeferredResult` | same as `chatDeferred` | same | same |
| `prewarm` | none | none (`oneway`) | `RemoteException` |
| `prewarmAndAwait` | `FEATURE_PREWARM_AWAIT` (falls back to fire-and-forget) | `MindlayerException` from auth gate | `RemoteException` |

For the full error code vocabulary see
[`shared/MindlayerErrorCode.kt`](../shared/src/main/kotlin/com/adsamcik/mindlayer/shared/MindlayerErrorCode.kt).
"Transparent fallback" means the SDK detects `NoSuchMethodError` /
`AbstractMethodError` from an older service binary, swaps to a legacy
implementation, and reports the same outcome shape to the caller.

---

## Implementation slice — what actually lands in this PR

### Added

In `sdk/src/main/kotlin/com/adsamcik/mindlayer/sdk/Mindlayer.kt`:

1. `suspend fun inferRealtime(sessionId, text, vararg media): InferenceHandle`
   — delegates to `chat(sessionId, text)` when `media.isEmpty()`; otherwise
   to `chatWithMedia(sessionId, text, *media)`.
2. `suspend fun inferAsync(sessionId, text, vararg media): String`
   — delegates to `chatOnce(sessionId, text)` when `media.isEmpty()`;
   otherwise calls `chatWithMedia(sessionId, text, *media)` and runs the
   existing `collectHandleToString` helper (made `internal` for this).
3. `suspend fun inferTools(sessionId, text, vararg media): InferenceHandle`
   — delegates to `inferRealtime(sessionId, text, *media)`.
4. A consolidated class-level KDoc block listing the three top-level
   entry points and the error / capability matrix above.

### Marked `@Deprecated(WARNING)`

The seven methods enumerated in "Deprecation strategy". Each carries a
`ReplaceWith(...)` so Android Studio's "Replace with new API" intention
works in consumer projects. Bodies are unchanged.

### Visibility

`collectHandleToString` flips from `private` to `internal` so `inferAsync`
can reuse it. No public-API change.

### Tests

`sdk/src/test/kotlin/com/adsamcik/mindlayer/sdk/MindlayerInferenceFacadesTest.kt`
(new) pins:

1. `inferRealtime`, `inferAsync`, and `inferTools` exist with the expected
   signatures (reflection on `Mindlayer::class.java`).
2. The legacy methods (`chat`, `chatWithImage`, `chatWithAudio`,
   `chatWithMedia`, `chatOnce`, `chatWithImageOnce`, `chatWithAudioOnce`)
   carry `@Deprecated`.
3. `inferRealtime(sid, text)` reaches the same AIDL method (`infer(...)`)
   with the same `RequestMeta` text content as `chat(sid, text)`.
4. `inferAsync(sid, text)` returns the accumulated text from the stream
   exactly as `chatOnce(sid, text)` would.
5. `inferTools(sid, text)` delegates to the same AIDL path as
   `inferRealtime(sid, text)`.

### Not touched

- AIDL files: zero diff.
- `:shared` parcelables: zero diff.
- `MindlayerSession.kt`, `ConnectionManager.kt`, `HistoryStore.kt`: zero diff.
- OCR (`ocrSession`, `ocrLimits`, …): zero diff.
- Embedding (`embed`, `embedBatch`, …): zero diff.
- Deferred (`chatDeferred`, `fetchDeferredResult`, …): zero diff.
- `Conversation.kt`, `MindlayerSession.kt`: zero diff (they delegate to the
  not-yet-deprecated streaming `chat` overload internally).
- `SDK_INTEGRATION.md`: a brief addendum is in scope; full rewrite is a
  follow-up doc PR once the deprecated alias methods are scheduled for
  removal.

---

## Follow-up issues to file

1. **`inferAsync` deferred-routing opt-in**: thread `useDeferred = false`
   parameter through `inferAsync` and route to `inferDeferred` +
   `awaitDeferred` when set. Capability-gated. (Estimated: ~150 LOC + tests.)
2. **`inferTools` with handler map**: higher-level overload taking
   `Map<String, suspend (argsJson: String) -> String>` that runs the
   tool round-trip loop internally. (Estimated: ~300 LOC + tests.)
3. **Rename text-delta flow helpers**: `inferRealtimeTextFlow` and
   `inferRealtimeTextFlowCumulative` as the post-deprecation shapes.
4. **Fold `chat(text)` / `generate(...)` into `inferAsync` overload**:
   add `inferAsync(text, configure: SessionConfigBuilder.() -> Unit)`
   that creates + destroys a temp session.
5. **`SDK_INTEGRATION.md` rewrite** anchored on the three new top-level
   entry points; should happen once the deprecated aliases enter the
   `ERROR` level.
6. **`InferenceRequest` envelope parcelable** — *requires AIDL evolution*
   (new method, schema-versioned parcelable per
   [`docs/AIDL_STABILITY.md`](AIDL_STABILITY.md)). This is the
   long-horizon consolidation; only worth proposing once consumer apps
   actually feel pain from the positional `(meta, image, audio, pfd)`
   shape. Today's positional shape is wire-bound.

---

## Wire-frozen confirmation

This PR makes **zero** changes to:

- `app/src/main/aidl/**`
- `sdk/src/main/aidl/**`
- `shared/src/main/kotlin/com/adsamcik/mindlayer/*Parcelize*`
- `shared/src/main/kotlin/com/adsamcik/mindlayer/shared/Protocol.kt`
- `shared/src/main/kotlin/com/adsamcik/mindlayer/shared/MindlayerErrorCode.kt`

Every behavioral change is contained inside `Mindlayer.kt` and a new test
file. If any future method shape in this proposal requires a wire change,
a separate issue will be filed and the design pass restarted from
[`docs/AIDL_STABILITY.md`](AIDL_STABILITY.md).
