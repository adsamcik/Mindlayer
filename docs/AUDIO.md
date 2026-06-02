# Audio capabilities (Gemma 4)

Mindlayer's bundled model is Gemma 4 E2B, which has a built-in audio
frontend. This page documents the contract that flows between caller →
SDK → service → engine, and links the upstream Google reference.

> Upstream reference: **https://ai.google.dev/gemma/docs/capabilities/audio**

## What works today

Mindlayer exposes Gemma's audio modality as a single attachment per
inference. Concretely:

- `Mindlayer.transcribe(audio, language = "English")` → string transcription
  using Google's recommended ASR prompt template.
- `Mindlayer.transcribe(prompt, audio)` → caller-supplied prompt;
  general-purpose audio understanding (summarize, classify, translate,
  …).
- `Mindlayer.infer { text("…"); audio(file) }` → builder-level access
  with full sampler/output control.
- `Mindlayer.extractJson(prompt, schema, audio = file)` → structured
  extraction from speech, sharing the JSON-schema enforcement that
  vision uses.

Capability flag `ServiceCapabilities.FEATURE_AUDIO_INPUT` ("audio_input")
advertises this surface. SDKs should gate calls on it.

## Limits

| Limit | Value | Where it's enforced |
|---|---|---|
| Max clip duration | **30 s** (`GemmaAudioSpec.MAX_DURATION_MS`) | `IpcInputValidator.validateAudioTransfer` / `validateAudioPart` reject `durationMs > 30 000` |
| Token cost | **~25 tokens / second** (`GemmaAudioSpec.TOKENS_PER_SECOND`) | `service.engine.ContextBudget.estimateTokensForAudio(...)` — service-side budget gate |
| Channels | 1 (mono) — model decodes/downmixes | engine, not validated on the wire |
| Sample rate | 16 kHz — model decodes/resamples | engine, not validated on the wire |
| Sample format | float32 in `[-1, 1]`, 32 ms frames | model frontend; caller sends encoded file |
| Attachments per request | **1 audio** + 1 image | `IpcInputValidator.validateMediaParts` |

If `durationMs` is omitted, the service-side budget treats the clip as
the full ceiling (30 s × 25 tok/s = 750 tokens) so omitting metadata
does not bypass the input-tokens check.

## Accepted MIME types

The MIME types validated by `IpcInputValidator.ALLOWED_AUDIO_MIME`:

`audio/wav`, `audio/x-wav`, `audio/mp3`, `audio/mpeg`, `audio/ogg`,
`audio/flac`, `audio/aac`, `audio/mp4`.

You send the **encoded** file across the AIDL boundary; LiteRT-LM owns
decoding. There is no need to pre-resample or pre-downmix client-side,
though doing so reduces the staged file size and the SharedMemory pool
pressure.

## Quick-start

```kotlin
val mindlayer: Mindlayer = Mindlayer.connect(context)
mindlayer.awaitConnected(timeout = 30.seconds)

// 1) Canonical ASR — uses Google's recommended prompt
val text: String = mindlayer.transcribe(audio = clipFile, language = "English")

// 2) ASR with language autodetect
val text: String = mindlayer.transcribe(audio = clipFile)

// 3) General audio understanding
val summary: String = mindlayer.transcribe(
    prompt = "Summarize the audio in two sentences.",
    audio = clipFile,
)

// 4) Builder-level — full control
val handle = mindlayer.infer {
    ephemeralSession()
    text("What instrument is playing?")
    audio(clipFile)
}
val answer = (handle as InferenceHandle.Text).awaitText()
```

The canonical ASR prompt text is centralized in
`com.adsamcik.mindlayer.sdk.GemmaAudioPrompts.transcriptionPrompt(language)`,
straight from the upstream docs page. Use it directly if you want to
mix the recommended phrasing with a custom session config that the
high-level helper doesn't expose.

## Not yet supported

These come from the upstream page but are **not** in Mindlayer today.
Treat the table as the follow-up backlog, not the current contract.

| Surface | Status | Notes |
|---|---|---|
| Multi-audio prompts (multiple clips in one request) | Validator rejects (`audioCount > 1`) | The upstream page demonstrates this for journals 1-5; landing it requires lifting the engine-level constraint and updating the budget aggregator + SharedMemory pool. Tracked as a follow-up; do not advertise a capability flag for it until the path is real. |
| Specialized translation helper (`translate(audio, sourceLang, targetLang)`) | Not implemented | Achievable today via `transcribe(prompt, audio)` with an explicit translation prompt. |
| Specialized "audio Q&A" helper | Not implemented | Same — covered by the general `infer { text(...); audio(...) }` path. |
| ≥30 s clips | Rejected at the validator | Caller must chunk and submit one inference per chunk. A future helper could automate this but would still hit the per-clip cap. |

## Privacy & offline invariants (no change)

Audio is processed entirely on-device by the bundled LiteRT-LM
runtime. Per `.github/instructions/privacy-offline.instructions.md`:

- No network permission is added; no telemetry; no cloud fallback.
- Decoded waveforms and recognized text stay in process memory; nothing
  is persisted under `filesDir` / `cacheDir` / external storage unless
  the caller itself does so (via the existing SQLCipher-backed
  history).
- The validator rejects unsupported MIME types and out-of-bounds
  `durationMs` *before* the engine sees a byte.

## See also

- [`shared/.../GemmaAudioSpec.kt`](../shared/src/main/kotlin/com/adsamcik/mindlayer/GemmaAudioSpec.kt) — single source of truth for the audio constants.
- [`sdk/.../GemmaAudioPrompts.kt`](../sdk/src/main/kotlin/com/adsamcik/mindlayer/sdk/GemmaAudioPrompts.kt) — canonical Gemma ASR prompt template.
- [`docs/AIDL_STABILITY.md`](AIDL_STABILITY.md) §"Audio surface (v1.0)" — capability flag registry entry.
