# Mindlayer — GitHub Copilot Instructions

## Project Overview

Mindlayer is an Android service app that provides on-device LLM inference to other apps via IPC. It uses **LiteRT-LM** with the **Gemma 4 E2B** model (2.3B effective params, multimodal text/image/audio).

## Architecture

```
mindlayer/
├── app/          # Android service app (headless + dashboard)
│   ├── engine/   # LiteRT-LM Engine, Session, Thermal, Memory management
│   ├── ipc/      # SharedMemory media transfer, pipe-based token streaming
│   ├── logging/  # Room-based usage logs, structured logcat, request tracing
│   └── ui/       # Jetpack Compose dashboard (single screen)
├── sdk/          # Client SDK library consumed by other apps
│   ├── db/       # Room database for conversation history persistence
│   └── ...       # ConnectionManager, TokenStreamReader, MediaTransfer, etc.
├── shared/       # Shared Parcelable types + streaming protocol
└── gemma_model/  # Play for On-device AI pack (install-time delivery)
```

## Key Design Decisions

- **Kotlin-first** — all code is Kotlin. LiteRT-LM is accessed via its Kotlin API.
- **3-plane hybrid IPC**: AIDL/Binder (control) + SharedMemory (media) + ParcelFileDescriptor pipe (streaming)
- **Foreground service with `specialUse`** — FGS only during active inference, bound service otherwise
- **`START_NOT_STICKY`** — client SDK owns durability via Room; recovery is client-driven replay
- **4-band thermal controller**: COOL → WARM → HOT → CRITICAL with hysteresis
- **Backend fallback**: GPU → CPU (NPU when available). Switch only at request boundaries.
- **Structured logging**: `MindlayerLog` wrapper prefixes `Mindlayer.<Component> [req=X sess=Y]`

## Coding Conventions

- Use `MindlayerLog.d/i/w/e(TAG, message, requestId, sessionId)` instead of `android.util.Log`
- Every public function documents thread-safety expectations
- Conversation is single-writer — serialize with per-session mutex
- All suspend functions run on `Dispatchers.IO` for blocking work
- Fire-and-forget logging via `LogRepository.log()` (launches on IO)
- Use `kotlinx.serialization` for JSON, not Gson (except where AIDL requires it)
- Pipe framing: 4-byte LE u32 length + UTF-8 JSON payload

## LiteRT-LM API Notes

- `Engine(EngineConfig)` → `engine.initialize()` → `engine.createConversation(ConversationConfig)`
- `sendMessageAsync()` returns `Flow<Message>` — each emission is a CHUNK, not accumulated
- `cancelProcess()` must be called explicitly — Flow cancellation does NOT cancel native inference
- `maxNumTokens` = KV cache budget (input + output combined)
- ⚠️ Gemma 4 multimodal in Kotlin blocked by issue #1874 (missing prompt-template override)

## Testing

- Unit tests: JUnit 4 + MockK + kotlinx-coroutines-test
- Integration tests: Robolectric for Room/Context, piped streams for pipe protocol
- SharedMemory tests: `@Ignore` on JVM (need real Android runtime)
- Test helper: `TestPipeHelper` for event contract assertions
- Run: `./gradlew :app:testDebugUnitTest :sdk:testDebugUnitTest :shared:testDebugUnitTest`

## Important Constraints

- Model file (2.4GB) is NOT in git — delivered via Play AI Pack or adb push
- Service runs in `:ml` isolated process — cross-process AIDL for all communication
- Binder transaction limit: 1MB — use SharedMemory for images/audio
- Engine init can take up to 10s — never block main thread
- Thermal throttle: 40-60% after 60-90s sustained GPU — duty cycle and backend switch
