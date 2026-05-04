<!-- context-init:version:3.1.0 -->
<!-- context-init:managed -->

# Mindlayer Architecture

> Mindlayer is an Android service app that loads a single LLM (Gemma 4 E2B via LiteRT-LM) and serves inference to **trusted first-party client apps** over IPC. It is not a public-internet SDK; it is an on-device, on-host shared-runtime.

## System Topology

```
┌────────────────────────────────────────────────────────────────────────────┐
│  CLIENT APPS (separate processes / UIDs)                                   │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐                      │
│  │  Client A    │  │  Client B    │  │  Client C    │   (signed by us)    │
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
│  MINDLAYER SERVICE  (`com.adsamcik.mindlayer.service`, process `:ml`)      │
│                                                                            │
│  ServiceBinder ──► [authorize: identity → allowlist → rate → ownership]   │
│        │                                                                  │
│        ├─► InferenceOrchestrator ─► SessionManager ─► EngineManager       │
│        │           │                     │                │               │
│        │           ├─ TokenStreamWriter  ├─ ToolCallBridge├─ Backend       │
│        │           ├─ SharedMemoryPool   ├─ ConcurrentMap │   GPU→CPU      │
│        │           └─ LogRepository                       │   (NPU planned)│
│        │                                                  │               │
│        ├─► ThermalMonitor (4-band: COOL/WARM/HOT/CRITICAL, 1 Hz)          │
│        ├─► MemoryBudget   (NORMAL/WARNING/CRITICAL/EMERGENCY)             │
│        └─► DiagnosticExporter, LogRepository (Room, app-process)          │
│                                                                            │
│  Dashboard UI runs in the *main* process and talks to `:ml` over the      │
│  same AIDL surface (self-UID bypasses the allowlist gate).                │
└────────────────────────────────────────────────────────────────────────────┘
```

## Modules

| Module | GAV | Purpose |
|---|---|---|
| `:app` | (application) `com.adsamcik.mindlayer.service` | Service implementation, dashboard UI, engine, security, logging. Hosts the `:ml` process. |
| `:sdk` | `com.adsamcik.mindlayer:sdk` | Client-facing SDK: `Mindlayer`, `Conversation`, `ConnectionManager`, `TokenStreamReader`, encrypted history DB. |
| `:shared` | `com.adsamcik.mindlayer:shared` | Wire types only — `StreamEvent`, `StreamEventType`, `StreamHeader`, AIDL-adjacent Parcelables. Pure Kotlin + `kotlinx.serialization`. |
| `:gemma_model` | (asset pack) | Play for On-device AI install-time pack delivering the ~2.4 GB `.litertlm` model file. |

`:sdk` declares `api(project(":shared"))` so consumers transitively get the wire types.

## Process Model

| Process | What runs there | Why |
|---|---|---|
| `com.adsamcik.mindlayer.service` (main) | Dashboard UI (Compose), the *client side* of the AIDL channel that the dashboard uses to read service state. | UI must be in the main process. |
| `com.adsamcik.mindlayer.service:ml` | `MindlayerMlService`, `EngineManager`, all native LiteRT-LM allocations, KV caches, thermal/memory monitors. | Isolation: a model crash kills `:ml` only; the main process and dashboard survive. |
| Client app processes | `:sdk` only. Bind to `:ml` via the AIDL `IMindlayerService`. | One model loaded once, many callers. |

Cross-process state (allowlist) is a JSON file under the service's `filesDir/mindlayer_allowlist/` with a `FileLock` sidecar — **never** `SharedPreferences`; `MODE_MULTI_PROCESS` is deprecated and racy.

## Key Components

| Component | Path | Responsibility |
|---|---|---|
| `MindlayerMlService` | `app/.../service/MindlayerMlService.kt` | Hosts the binder; promotes to FGS `specialUse` only during active inference; routes `onTrimMemory` to `MemoryBudget`. `START_NOT_STICKY`. |
| `ServiceBinder` | `app/.../service/ServiceBinder.kt` | AIDL stub. Every entry point starts with `authorizeCall()` (4-stage gate). Binder-death linkage tears down a UID's sessions when its process dies. |
| `EngineManager` | `app/.../engine/EngineManager.kt` | LiteRT-LM `Engine` lifecycle. Backend chain: NPU (when supported SoC) → GPU → CPU. Init can take ~10 s — never block the main thread. Mutex-serialized. |
| `SessionManager` | `app/.../engine/SessionManager.kt` | Per-session `Conversation` instances. Memory-pressure eviction by priority (streaming +1000, pinned +400, recent +300/150, hint 0–100). Per-session `Mutex`. |
| `InferenceOrchestrator` | `app/.../engine/InferenceOrchestrator.kt` | Streams tokens to a PFD pipe; cancellation via `cancelInference(requestId)`; bridges tool calls to the client and awaits results. `MAX_TOOL_ROUNDS = 25`. |
| `ThermalMonitor` | `app/.../engine/ThermalMonitor.kt` | Samples `getCurrentThermalStatus` + `getThermalHeadroom` at 1 Hz. Emits `ThermalPolicy` via `StateFlow`. Backend switches happen at request boundaries only. |
| `MemoryBudget` | `app/.../engine/MemoryBudget.kt` | Static device tier from total RAM (`≤6 GB / ≤8 GB / ≤12 GB / >12 GB`) caps session count + token budget. Emits dynamic `MemoryPressure`. |
| `TokenStreamWriter` | `app/.../ipc/TokenStreamWriter.kt` | Writes 4-byte LE length-prefixed JSON frames to the PFD pipe. Not thread-safe — orchestrator owns serialization. |
| `SharedMemoryPool` | `app/.../ipc/SharedMemoryPool.kt` | Backing for `ImageTransfer` / `AudioTransfer` — Binder's 1 MB transaction limit forces media off the parcel. |
| `AllowlistStore` | `app/.../security/AllowlistStore.kt` | File-backed approved/pending callers, sig-pinned. `isAllowed()` always re-reads disk so dashboard approvals are visible to `:ml`. |
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

### Today (verified, see `docs/AUTHORIZATION.md`)

1. **Manifest gate** — `BIND_ML_SERVICE` permission is `signature`. Only apps signed with the same key as the service can even attempt to bind. In a 1P-only deployment this is the primary gate.
2. **Identity** — `CallerVerifier.identifyCaller(uid)` resolves UID → `(pkg, signingCertSha256)`. Shared-UID rejected.
3. **Allowlist** — `AllowlistStore.isAllowed(pkg, sig)` against a JSON file in service `filesDir`. Default-deny; **no first-party seeding today**. First call from any caller (including a co-signed first-party app) records a pending entry and throws `SecurityException`. The user must approve in the dashboard.
4. **Rate limit** — `RateLimiter` per-UID token bucket (60 RPM) + concurrent-inference cap.
5. **Ownership** — Session-scoped methods (`infer`, `destroy`, `cancel`, `submitToolResult`) require the calling UID to own the session.
6. **Self-UID bypass** — When `Binder.getCallingUid() == Process.myUid()` the dashboard's own AIDL traffic skips the allowlist + rate limit; otherwise it would self-deny.

### Intended direction (NOT YET IMPLEMENTED — design constraint)

The product intent is that **first-party apps (signed with the same key) should bypass user approval entirely** — being co-signed is itself the authorization. Third-party support must remain *possible* in the future and the existing user-approval flow must stay intact for that path.

The hook is named in the doc: `AllowlistStore.seedIfEmpty(...)` called from `MindlayerMlService.onCreate`, populating the allowlist with known 1P `(pkg, sig)` pairs. When you implement this:

- Keep `CallerVerifier`, `RateLimiter`, ownership and binder-death intact — they are orthogonal to who is approved.
- Do **not** remove `recordPending` for unknown callers — that is what enables future 3P opt-in.
- The signature-perm manifest gate is the *first* line; the seeded allowlist is defense-in-depth.

Until that lands, even the project's own first-party clients see `REJECTED_NOT_APPROVED` on first connect and need a one-time dashboard approval per install.

## Wire Protocol (v1)

- Frame: `[4-byte LE u32 length][UTF-8 JSON payload]`
- Hard cap: `MAX_FRAME_BYTES = 1_048_576` — duplicated in `TokenStreamWriter` and `TokenStreamReader`. Exceeding it is a programmer error; fail fast.
- Header (first frame): `StreamHeader { protocol = "mindlayer.stream.v1", requestId }` (`shared/.../Protocol.kt`).
- Event types (`StreamEventType`): `start`, `token_delta`, `tool_call`, `tool_result`, `metrics`, `error`, `done`.

**Cross-module contract invariant:** any change to the AIDL surface, `StreamEvent` shape, frame format, or `StreamEventType` constants must update `:app`, `:sdk`, `:shared`, and the corresponding tests in the same change. The AIDL files are duplicated under `app/src/main/aidl/` and `sdk/src/main/aidl/` and must stay byte-identical.

## See also

- Patterns and conventions: [`PATTERNS.md`](PATTERNS.md)
- Build, test, release, troubleshooting: [`DEVELOPMENT.md`](DEVELOPMENT.md)
- Authorization deep-dive: [`../../docs/AUTHORIZATION.md`](../../docs/AUTHORIZATION.md)
- Client integration: [`../../SDK_INTEGRATION.md`](../../SDK_INTEGRATION.md)
