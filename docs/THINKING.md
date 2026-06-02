# Gemma 4 thinking mode

Mindlayer supports the Gemma 4 thinking capability documented at
<https://ai.google.dev/gemma/docs/capabilities/thinking>. The model
produces an internal **reasoning trace** before its user-visible
answer; Mindlayer surfaces the two streams separately so SDK callers
can render reasoning as a collapsible "thought" panel, hide it
entirely, or persist it for audit — without ever mixing it into the
answer the end-user sees.

> **TL;DR — opt in with one builder call.**
>
> ```kotlin
> val conv = mindlayer.conversation {
>     systemPrompt("You are a careful assistant.")
>     enableThinking()
> }
> val response = conv.chat("What is 12 * 17? Explain your reasoning.")
> ```
>
> Existing callers see no change. Thoughts are dropped by the default
> `awaitText()` / `awaitFullText()` terminals; to render them, collect
> the event stream or pipe through `thoughtDeltas()`.

## What thinking mode is (and isn't)

Gemma 4 E2B/E4B and 26B/31B can prepend a structured reasoning block
to their response. The block is delimited by special tokens:

```
<|channel>thought
... model's internal reasoning ...
<channel|>
... user-visible answer ...
```

Mindlayer's service intercepts the `<|channel>thought ... <channel|>`
block (using LiteRT-LM 0.12.0's `Channel` API) and routes it through a
dedicated **wire event type** — `THOUGHT_DELTA` — so the answer stream
remains exactly the text the model intended for the user. Thinking
mode is **not** chain-of-thought prompting, and Mindlayer does **not**
manufacture thoughts when the model doesn't produce them — sessions
that don't opt in see only `TextDelta` events as today.

## Privacy and persistence

Thinking-mode output is treated as sensitive intermediate state:

- **Routed out of the user-visible answer stream** at the LiteRT-LM
  level: thoughts arrive in `Message.channels["thought"]` while the
  answer arrives in `Message.contents`, so the SDK's answer
  accumulator never sees a stray thought fragment.
- **Never persisted** to the SDK's encrypted history database. Only
  the user-visible answer turn is stored (matching how the rest of the
  SDK treats tool inputs/outputs).
- **Never logged** in plaintext. The service's existing logging policy
  applies: only token counts, channel delimiter ratios, and timings
  enter the structured log.
- **Streamed back to the caller** on a v3 protocol pipe — the SDK is
  the only consumer that ever sees the text; nothing on the service
  side stores or forwards it elsewhere.

> **KV-cache caveat (known limitation in v1.1).** LiteRT-LM 0.12.0
> retains channel content in the conversation KV cache by default
> (controlled by `ExperimentalFlags.filterChannelContentFromKvCache`,
> off in this release). That means previous-turn thoughts remain in
> the model's working context across user turns — the Gemma "strip
> thoughts before the next turn" guidance is **not** satisfied
> automatically. The practical effect is faster KV-cache growth on
> long thinking-enabled conversations; a follow-up PR will enable the
> filter once we have verified its semantics around tool-round
> boundaries (the Gemma docs require thoughts to remain in context
> across tool calls within a single turn). Until then, callers with
> long multi-turn thinking sessions should keep `expirationMs` short
> or recycle sessions explicitly.

## SDK API

### High-level DSL — `Mindlayer.conversation { enableThinking() }`

```kotlin
val mindlayer = Mindlayer.connect(context)

val conv = mindlayer.conversation {
    systemPrompt("You are a careful assistant.")
    enableThinking()
}

// `chat()` returns only the user-visible answer — thoughts are dropped.
val answer = conv.chat("What is the boiling point of water?")
println(answer) // "100°C at standard atmospheric pressure …"
```

### Streaming — collect `ThoughtDelta` and `TextDelta` independently

```kotlin
val handle = mindlayer.openSession {
    systemPrompt("You are a careful assistant.")
    enableThinking()
}.use { session ->
    session.infer {
        userText("What is 12 * 17? Walk me through the steps.")
    }
}

val thoughtBuilder = StringBuilder()
val answerBuilder  = StringBuilder()
handle.events.collect { event ->
    when (event) {
        is InferenceEvent.ThoughtDelta -> thoughtBuilder.append(event.text)
        is InferenceEvent.TextDelta    -> answerBuilder.append(event.text)
        is InferenceEvent.Done         -> println("done: ${event.finishReason}")
        is InferenceEvent.Error        -> error(event.message)
        else -> Unit
    }
}
println("Reasoning:\n$thoughtBuilder\n\nAnswer:\n$answerBuilder")
```

### Flow operators

| Operator | Behaviour |
|---|---|
| `Flow<InferenceEvent>.textDeltas()` | Just the answer text fragments (unchanged from prior versions). |
| `Flow<InferenceEvent>.thoughtDeltas()` | Just the thought-text fragments. Empty on non-thinking sessions. |
| `Flow<InferenceEvent>.answerOnly()` | Every event except `ThoughtDelta` — use to forward the stream to a UI that should treat the conversation as if thinking were off. |

### Low-level builder

```kotlin
val sessionId = mindlayer.createSession {
    systemPrompt("…")
    enableThinking()                              // canonical helper
    // …or, equivalently, the raw envelope:
    // extraContext("""{"thinking":{"enable":true}}""")
}
```

The two forms are equivalent — `enableThinking()` writes a
`{"thinking":{"enable":true}}` envelope into `extraContextJson` for
the AIDL `SessionConfig`. Both the nested form
(`{"thinking":{"enable":true}}`) and the bare shorthand
(`{"thinking":true}`) are accepted by the service's parser.

## Capability handshake

The service advertises thinking-mode support via
`ServiceCapabilities.FEATURE_THINKING_MODE`. SDKs that need to render
the "Show reasoning" toggle should check the capability first:

```kotlin
val supports = mindlayer.capabilities().supports(
    ServiceCapabilities.FEATURE_THINKING_MODE,
)
```

When the connected service predates this feature **(or any older
service that has not been updated)**, `enableThinking()` is a no-op:
the opt-in JSON is forwarded but ignored, the stream stays on
`mindlayer.stream.v1` / `v2`, and no `ThoughtDelta` events are
emitted. The model's answer is unchanged.

## Wire protocol

Thinking mode introduces a strict superset of the v2 pipe protocol:

| Field | v1 | v2 | v3 |
|---|---|---|---|
| `StreamHeader.protocol` | `mindlayer.stream.v1` | `mindlayer.stream.v2` | `mindlayer.stream.v3` |
| `TOKEN_DELTA` | ✅ | ✅ | ✅ |
| `TOKEN_DELTA_BATCH` | ❌ | ✅ | ✅ |
| `THOUGHT_DELTA` | ❌ | ❌ | ✅ |
| `THOUGHT_DELTA_BATCH` | ❌ | ❌ | ✅ |
| `TOOL_CALL` / `METRICS` / `ERROR` / `DONE` | ✅ | ✅ | ✅ |

The reader accepts all three protocol identifiers from the same code
path; old SDKs talking to a future v3 service that never enabled
thinking still see only v1/v2 wire types because the service only
negotiates v3 when the caller opted in.

## Multimodal and tool calling

- **Multimodal:** thinking mode composes with the existing multimodal
  inference path. Pass an image or audio attachment to
  `session.infer { }` as usual — the model's reasoning trace will
  cover the visual / audio input before producing its answer.
- **Tool calling:** thoughts emitted between `submitToolResult()`
  rounds are streamed via `ThoughtDelta` like any other thought
  fragment. Per the Gemma docs, the model's thinking across a single
  turn (including tool round-trips) is internally coherent; multi-turn
  history still strips the thoughts.

## How it actually wires up

`enableThinking()` sets `extraContextJson.thinking = { enable: true }`
on the session's `SessionConfig`. The service reads that JSON, and
when the flag is on:

1. Sets `enable_thinking = true` in the LiteRT-LM
   `ConversationConfig.extraContext` map AND in every per-send
   `sendMessage` / `sendMessageAsync` extra-context map. The native
   chat-template engine reads this key (verified by inspection of
   the `gemma-4-E2B-it.litertlm` metadata block, which embeds the
   `enable_thinking` Jinja variable alongside `<|channel`,
   `<|think`, etc.) and inserts the `<|think|>` sentinel as a single
   token id — **not** as a multi-char literal.
2. Adds a `Channel("thought", "<|channel>thought", "<channel|>")` to
   `ConversationConfig.channels`. The runtime routes content emitted
   between those markers into `Message.channels["thought"]` so it
   stays out of the user-visible `contents`.

**Don't try the obvious thing of prepending `<|think|>` to your
system prompt yourself.** SentencePiece will tokenise it as
`<`, `|`, `think`, `|`, `>` plain chars, the model never enters
thinking mode, and you get a normal-looking answer with zero
`ThoughtDelta` events (verified the hard way by an early iteration
of this PR — see commit history).

## Model variant compatibility

The wiring above is complete and correct: the rendered prompt
contains the `<|think|>` sentinel, the `thought` channel is
configured, and any token the model emits between
`<|channel>thought` and `<channel|>` will surface as a
`ThoughtDelta` on the SDK side. **What surfaces depends entirely
on what the bundled Gemma 4 `.litertlm` variant was trained to
emit.**

Observed on `gemma-4-E2B-it.litertlm` (the build packaged with
LiteRT-LM 0.12.0, verified end-to-end on emulator API 36 on
2026-06-02):

| Aspect | Observed | Expected per Gemma docs |
|---|---|---|
| Prompt contains `<|think|>` sentinel | ✅ Yes | ✅ Yes |
| Response **quality** with thinking on | ✅ Longer, more structured, more markdown/LaTeX | ✅ Reasoning-heavy |
| Response **emits** `<|channel>thought ... <channel|>` block | ❌ No — reasoning is inline | ✅ Yes |
| `InferenceEvent.ThoughtDelta` events on the SDK side | 0 | ≥ 1 |

So the SDK surface ships **today** with a real wiring contract, but
the bundled E2B variant won't actually produce `ThoughtDelta` events
until either (a) Google ships a Gemma 4 E2B `.litertlm` that emits
channel-separated thoughts, or (b) Mindlayer adopts a larger Gemma 4
variant (26B / 31B) per `docs/THINKING.md`. The on-device
instrumented probe (`ThinkingModeInstrumentedTest`) reflects this
honestly:

- `thinking_opt_in_inserts_think_marker_in_rendered_prompt` — hard
  assertion. The wiring delivered by this PR is the
  `enable_thinking` extraContext plumbing; that's what this test
  verifies.
- `thinking_enabled_session_emits_ThoughtDelta_when_model_supports_channels`
  — soft assertion. Skips with `assumeTrue` when the model
  produced zero markers (current state); will go from skip → pass
  automatically the moment a channel-emitting model variant lands.

If you've manually pushed a channel-emitting Gemma 4 build to your
device (see `docs/DEV_MODELS.md`), the second test will start
passing — no SDK / service code change required.

## Known limitations

- Thinking mode requires LiteRT-LM ≥ 0.12.0 (the runtime currently
  bundled with Mindlayer). Older builds of the engine — should they
  ever be re-introduced — will not advertise the capability.
- The opt-in is per-session. Mid-session toggling is not supported;
  destroy the session and create a new one with the opposite flag if
  you need to switch behaviour for a different turn.
- The bundled `gemma-4-E2B-it.litertlm` variant emits enhanced
  reasoning inline rather than in a `<|channel>thought` block — see
  "Model variant compatibility" above. The SDK surface ships now so
  consumers can write their `enableThinking()` + `thoughtDeltas()`
  pipelines today; emissions appear automatically when a
  channel-emitting Gemma 4 build replaces the current E2B.

## References

- [Gemma 4 thinking capabilities (Google)](https://ai.google.dev/gemma/docs/capabilities/thinking)
- [`Channel` API in LiteRT-LM 0.12.0](https://github.com/google-ai-edge/litert-lm) (Kotlin `ConversationConfig.channels`)
- `ServiceCapabilities.FEATURE_THINKING_MODE` in
  `shared/src/main/kotlin/com/adsamcik/mindlayer/ServiceCapabilities.kt`
- `StreamProtocol.V3` in
  `shared/src/main/kotlin/com/adsamcik/mindlayer/shared/Protocol.kt`
