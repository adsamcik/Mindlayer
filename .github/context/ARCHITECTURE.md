<!-- context-init:version:3.1.0 -->
<!-- context-init:managed -->

# Mindlayer Architecture

> Mindlayer is an Android service app that loads a single LLM (Gemma 4 E2B via LiteRT-LM) and serves inference to **user-approved client apps** over IPC. It is not a public-internet SDK; it is an on-device, on-host shared-runtime.

## System Topology

```
┌────────────────────────────────────────────────────────────────────────────┐
│  CLIENT APPS (separate processes / UIDs)                                   │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐                      │
│  │  Client A    │  │  Client B    │  │  Client C    │ (user-approved)      │
│  │  uses :sdk   │  │  uses :sdk   │  │  uses :sdk   │                      │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘                      │
│         │                 │                 │                              │
│         └─────────────────┼─────────────────┘                              │
│                           │  3-plane IPC                                   │
│            ┌──────────────┴───────────────┐                                │
│            │  AIDL/Binder  — control      │  (IMindlayerService)           │
│            │  SharedMemory — media        │  (ImageTransfer/AudioTransfer) │
│            │  PFD pipe     — token stream │  (length-prefixed JSON frames) │
│            └──────────────┬───────────────┘                                │
└───────────────────────────┼────────────────────────────────────────────────┘
                            │
┌───────────────────────────┼────────────────────────────────────────────────┐
│  MINDLAYER SERVICE  (`com.adsamcik.mindlayer`, process `:ml`)      │
│                                                                            │
│  ServiceBinder ──► [authorize: identity → consent → rate → ownership]      │
│        │                                                                   │
│        ├─► InferenceOrchestrator ─► SessionManager ─► EngineManager        │
│        │           │                     │                │                │
│        │           ├─ TokenStreamWriter  ├─ ToolCallBridge├─ Backend       │
│        │           ├─ SharedMemoryPool   ├─ ConcurrentMap │   GPU→CPU      │
│        │           └─ LogRepository                       │   (NPU planned)│
│        │                                                  │                │
│        ├─► ThermalMonitor (4-band: COOL/WARM/HOT/CRITICAL, 1 Hz)           │
│        ├─► MemoryBudget   (NORMAL/WARNING/CRITICAL/EMERGENCY)              │
│        └─► DiagnosticExporter, LogRepository (Room, app-process)           │
│                                                                            │
│  Dashboard UI runs in the *main* process and talks to `:ml` over the       │
│  same AIDL surface (self-UID bypasses the consent gate).                   │
└────────────────────────────────────────────────────────────────────────────┘
```

## Modules

| Module | GAV | Purpose |
|---|---|---|
| `:app` | (application) `com.adsamcik.mindlayer` | Service implementation, dashboard UI, engine, security, logging. Hosts the `:ml` process. |
| `:sdk` | `com.adsamcik.mindlayer:sdk` | Client-facing SDK: `Mindlayer`, `Conversation`, `ConnectionManager`, `TokenStreamReader`, encrypted history DB, embeddings, OCR sessions. |
| `:sdk-camerax` | `com.adsamcik.mindlayer:sdk-camerax` | Optional CameraX adapter for OCR (`OcrImageAnalyzer`) with client-side presort. |
| `:shared` | `com.adsamcik.mindlayer:shared` | Wire types only — `StreamEvent`, `StreamEventType`, `StreamHeader`, AIDL-adjacent Parcelables. Pure Kotlin + `kotlinx.serialization`. |
| `:gemma_model` | (asset pack) | Play for On-device AI install-time pack delivering the ~2.4 GB `.litertlm` model file. |
| `:gemma_embed_model` | (asset pack) | Install-time EmbeddingGemma `.tflite` weights plus SentencePiece tokenizer. |
| `:paddleocr_model` | (asset pack) | Install-time PaddleOCR PP-OCRv5 mobile detector/recognizer/classifier/dictionary assets. |

`:sdk` declares `api(project(":shared"))` so consumers transitively get the wire types.

## Process Model

| Process | What runs there | Why |
|---|---|---|
| `com.adsamcik.mindlayer` (main) | Dashboard UI (Compose), the *client side* of the AIDL channel that the dashboard uses to read service state. | UI must be in the main process. |
| `com.adsamcik.mindlayer:ml` | `MindlayerMlService`, `EngineManager`, all native LiteRT-LM allocations, KV caches, thermal/memory monitors. | Isolation: a model crash kills `:ml` only; the main process and dashboard survive. |
| Client app processes | `:sdk` only. Bind to `:ml` via the AIDL `IMindlayerService`. | One model loaded once, many callers. |

Cross-process state (allowlist) is a JSON file under the service's `filesDir/mindlayer_allowlist/` with a `FileLock` sidecar — **never** `SharedPreferences`; `MODE_MULTI_PROCESS` is deprecated and racy.

## Key Components

| Component | Path | Responsibility |
|---|---|---|
| `MindlayerMlService` | `app/.../service/MindlayerMlService.kt` | Hosts the binder; promotes to FGS `specialUse` only during active inference; routes `onTrimMemory` to `MemoryBudget`. `START_NOT_STICKY`. |
| `ServiceBinder` | `app/.../service/ServiceBinder.kt` | AIDL stub. Every real entry point starts with `authorizeCall()` (4-stage gate). Binder-death linkage tears down a UID's sessions when its process dies. |
| `EngineManager` | `app/.../engine/EngineManager.kt` | LiteRT-LM `Engine` lifecycle. Backend chain: NPU (when supported SoC) → GPU → CPU. Init can take ~10 s — never block the main thread. Mutex-serialized. |
| `SessionManager` | `app/.../engine/SessionManager.kt` | Per-session `Conversation` instances. Memory-pressure eviction by priority (streaming +1000, pinned +400, recent +300/150, hint 0–100). Per-session `Mutex`. |
| `InferenceOrchestrator` | `app/.../engine/InferenceOrchestrator.kt` | Streams tokens to a PFD pipe; cancellation via `cancelInference(requestId)`; bridges tool calls to the client and awaits results. `MAX_TOOL_ROUNDS = 25`. |
| `ThermalMonitor` | `app/.../engine/ThermalMonitor.kt` | Samples `getCurrentThermalStatus` + `getThermalHeadroom` at 1 Hz. Emits `ThermalPolicy` via `StateFlow`. Backend switches happen at request boundaries only. |
| `MemoryBudget` | `app/.../engine/MemoryBudget.kt` | Static device tier from total RAM (`≤6 GB / ≤8 GB / ≤12 GB / >12 GB`) caps session count + token budget. Emits dynamic `MemoryPressure`. |
| `TokenStreamWriter` | `app/.../ipc/TokenStreamWriter.kt` | Writes 4-byte LE length-prefixed JSON frames to the PFD pipe. Not thread-safe — orchestrator owns serialization. |
| `SharedMemoryPool` | `app/.../ipc/SharedMemoryPool.kt` | Backing for `ImageTransfer` / `AudioTransfer` — Binder's 1 MB transaction limit forces media off the parcel. |
| `AllowlistStore` | `app/.../security/AllowlistStore.kt` | File-backed approved and denied callers, sig-pinned. `isAllowed()` always re-reads disk so consent grants, revokes, and unblocks are visible to `:ml`. |
| `CallerVerifier` | `app/.../security/CallerVerifier.kt` | UID → `(packageName, signingCertSha256)`. Rejects shared-UID callers. Multi-signer: hash each cert, sort hex digests, hash concatenation. |
| `RateLimiter` | `app/.../security/RateLimiter.kt` | Per-UID token bucket (60 RPM default) + concurrent-inference semaphore. |
| `LogRepository` | `app/.../logging/LogRepository.kt` | Fire-and-forget Room writer on `Dispatchers.IO`. **Persists metadata only** — never prompt or model output text. |
| `ConnectionManager` | `sdk/.../ConnectionManager.kt` | 3-signal death detection (`onServiceDisconnected`, `onBindingDied`, `binderDied`). Auto-reconnect with exponential backoff. Holds a stable `Binder` token for `registerClient`. |
| `MindlayerDatabase` | `sdk/.../db/MindlayerDatabase.kt` | SQLCipher-encrypted Room. Passphrase wrapped in AndroidKeystore via `DbKeyProvider`. Cross-install backup is unreadable by design. |

## Inference Flow (happy path)

```
client.chat(sid, "hi")
  └─► sdk.Mindlayer.chat()
        └─► sdk.MediaTransfer.stage(image|audio)         # if any → SharedMemory
        └─► sdk.TokenStreamReader.openPipe()              # PFD.createPipe()
        └─► AIDL: service.infer(meta, img, audio, writeEnd)
              └─► ServiceBinder.authorizeCall()           # 4-stage gate
              └─► InferenceOrchestrator.infer()
                    ├─ trace.start(...)                    # RequestTrace
                    ├─ session.send(...)                   # per-session Mutex
                    ├─ engine.sendMessageAsync(...)        # Flow<Message>, native
                    │     while collecting:
                    │       writer.writeTokenDelta(text)
                    │       on tool call → ToolCallBridge.awaitResults()
                    │                       client submits via submitToolResult()
                    └─ writer.writeDone(metrics) ─► pipe.close()
        └─► sdk.TokenStreamReader emits MindlayerEvent.{TextDelta|ToolCall|Done}
```

Cancellation: LiteRT-LM `Conversation.cancelProcess()` is **explicit** — Flow cancellation alone does not stop native work. The orchestrator calls `handle.conversation.cancelProcess()` from `cancelInference`.

## Trust Model

1. **Open bind, closed methods** — `MindlayerMlService` is exported with no
   custom bind permission. Any installed app can bind, but binding carries no
   trust and every real method starts with `ServiceBinder.authorizeCall()`.
2. **Pre-consent surface** — only `requestConsentChallenge()` and coarse
   `ping()` are reachable before approval. `ping()` returns `{alive,
   apiVersion}` only.
3. **Consent challenge** — `requestConsentChallenge()` captures Binder
   UID/package/cert server-side, mints a 256-bit single-use nonce and immutable
   one-shot `PendingIntent`, and launches Mindlayer-owned `ConsentActivity`.
4. **Identity** — `CallerVerifier.identifyCaller(uid)` resolves UID →
   `(pkg, signingCertSha256)`. Shared-UID callers are rejected.
5. **Consent allowlist / denial** — `AllowlistStore.isAllowed(pkg, sig)` checks
   the HMAC-sealed JSON allowlist in service `filesDir`. Explicit denials return
   `CONSENT_DENIED`; missing approvals return `CONSENT_REQUIRED`.
6. **Rate limit** — approved callers share the same per-UID token bucket
   (60 RPM default) plus concurrent-inference cap. There are no trust tiers.
7. **Ownership** — Session-scoped methods (`infer`, `destroy`, `cancel`,
   `submitToolResult`) require the calling UID to own the session.
8. **Self-UID bypass** — When `Binder.getCallingUid() == Process.myUid()` the
   dashboard's own AIDL traffic skips the consent + rate gate; otherwise it
   would self-deny.

The old `signature|knownSigner` permission, trusted-cert array, first-party seed
list, and API 26–30 `knownSigner` caveat are gone with the consent model. See
[`../../docs/architecture/AUTHORIZATION.md`](../../docs/architecture/AUTHORIZATION.md) and
[`../../docs/architecture/CONSENT_ARCHITECTURE.md`](../../docs/architecture/CONSENT_ARCHITECTURE.md).

## Wire Protocol (v1)

- Frame: `[4-byte LE u32 length][UTF-8 JSON payload]`
- Hard cap: `MAX_FRAME_BYTES = 1_048_576` — duplicated in `TokenStreamWriter` and `TokenStreamReader`. Exceeding it is a programmer error; fail fast.
- Header (first frame): `StreamHeader { protocol = "mindlayer.stream.v1", requestId }` (`shared/.../Protocol.kt`).
- Event types (`StreamEventType`): `start`, `token_delta`, `tool_call`, `tool_result`, `metrics`, `error`, `done`.

**Cross-module contract invariant:** any change to the AIDL surface, `StreamEvent` shape, frame format, or `StreamEventType` constants must update `:app`, `:sdk`, `:shared`, and the corresponding tests in the same change. ALL AIDL files (interfaces `IMindlayerService.aidl` + `IClientCallback.aidl` and parcelables) live ONLY in `sdk/src/main/aidl/`; `:app` consumes the generated Binder classes via `implementation(project(":sdk"))` and has no AIDL of its own (`AidlContractDriftTest` enforces — adding AIDL to `:app` duplicates the classes and breaks the release R8 merge).

## See also

- Patterns and conventions: [`PATTERNS.md`](PATTERNS.md)
- Build, test, release, troubleshooting: [`DEVELOPMENT.md`](DEVELOPMENT.md)
- Authorization deep-dive: [`../../docs/architecture/AUTHORIZATION.md`](../../docs/architecture/AUTHORIZATION.md)
- Client integration: [`SDK_INTEGRATION.md`](../../docs/sdk/SDK_INTEGRATION.md)

## Embedding runtime addendum

- Module `:gemma_embed_model` is a Play for On-device AI install-time asset pack for EmbeddingGemma-300M `.tflite` weights plus the SentencePiece tokenizer.
- LiteRT-LM 0.12.0 handles the generative model (Gemma 4 E2B); base LiteRT 2.1.5 handles the embedding model (EmbeddingGemma-300M).
- The two runtimes have separate native handles and lifecycles. GPU/NPU coexistence is unverified on real devices; fallback is a process-wide accelerator mutex serializing both runtimes.
- Memory pressure unloads the embedding model first.

## OCR runtime addendum

- Module `:paddleocr_model` delivers the PaddleOCR PP-OCRv5 mobile assets as an install-time AI pack.
- OCR exposes multi-frame sessions through AIDL + `OcrSession`/`OcrEvent` in the SDK, with optional CameraX integration in `:sdk-camerax`.
- The OCR stream is wired, including `FRAME_DROPPED`, terminal `RESULT_FINALIZED`, and terminal `OcrEvent.Error`.
- Production exposure is gated by `OcrFeatureFlags.IS_PRODUCTION_READY=false` until real-device validation signs off; OCR defaults to GPU via `LiteRtAcceleratorResolver` (mirroring chat — `null` → GPU; explicit `NPU` probed with GPU-fallback; explicit `CPU`/`GPU` honored). LiteRT/LiteRT-LM coexistence remains real-device-gated.

