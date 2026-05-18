---
applyTo: "app/src/main/kotlin/com/adsamcik/mindlayer/service/engine/**"
description: "LiteRT-LM engine, sessions, thermal, memory"
---

<!-- context-init:managed -->

## LiteRT-LM lifecycle

```
EngineConfig → Engine(config) → engine.initialize()
  → engine.createConversation(ConversationConfig)
    → conversation.sendMessageAsync(msg)  // Flow<Message> — each emission is a CHUNK
    → conversation.cancelProcess()        // EXPLICIT cancellation (Flow cancel ≠ stop)
```

- `Engine` init can take **up to ~10 s**. Never block the main thread; `EngineManager` runs init under a global `Mutex` on `Dispatchers.IO`.
- Backend chain: NPU (only on known SoC families — see `EngineManager.QUALCOMM_NPU_SOCS`/`MEDIATEK_NPU_SOCS`) → GPU → CPU. Switch at request boundaries only, never mid-stream.
- `lastGpuFailureReason` and `currentBackend` are observable for the dashboard.
- A backend fallback recreates the `Engine`. Existing `Conversation` instances are invalidated — `SessionManager` recreates them lazily on next use.

## Sessions

- One `SessionHandle` per `IMindlayerService.createSession`. Each holds a `Mutex` — sends are single-writer per session, parallel across sessions.
- Session count and `maxNumTokens` cap come from `MemoryBudget.deviceTier`:

  | RAM | Sessions | Default tokens | Max tokens |
  |---|---|---|---|
  | ≤6 GB | 1 | 2 048 | 2 048 |
  | ≤8 GB | 2 | 4 096 | 4 096 |
  | ≤12 GB | 4 | 8 192 | 16 384 |
  | >12 GB | 6 | 16 384 | 32 768 |

- `maxNumTokens` is the **KV cache budget** — input tokens + output tokens combined.
- Eviction priority: `streaming +1000`, `pinned +400`, accessed `<30 s +300` / `<120 s +150`, client hint `0–100`. Lowest priority is dropped first under memory pressure.
- Always tag a session with its `ownerToken` (the caller's `CallerIdentity`) so binder-death tears it down via `closeAllOwnedBy(token)`.

## Inference orchestration

- One `Job` per `requestId` in `InferenceOrchestrator.activeJobs` — used for `cancelInference`.
- Per-session `Mutex.withLock` wraps the entire send → stream → tool-loop sequence. Parallel sends to the **same** session deadlock-safe-fail (the mutex serialises them).
- Tool calls: `ToolCallBridge.registerPendingToolCalls` → emit `tool_call` frames → `awaitResults` (suspends) → client calls `submitToolResult` over AIDL → `bridge.submitResult` resumes the deferred → result is fed back to LiteRT-LM. Hard cap `MAX_TOOL_ROUNDS = 25`.
- Cancellation MUST call `handle.conversation.cancelProcess()` on the affected session's `Conversation`. Cancelling the coroutine alone does **not** stop native inference. (See `InferenceOrchestrator.cancelInference` lines 143 and 442.)

## Thermal

- 4-band: COOL / WARM / HOT / CRITICAL. Hysteresis on exit thresholds; 30 s cooldown before re-enabling GPU after a thermal fallback. Sampled at 1 Hz.
- Consumers observe `thermalMonitor.currentPolicy` (`StateFlow<ThermalPolicy>`) and apply backend swaps **at request boundaries**, never mid-stream.
- `ThermalPolicy.chunkTokens` / `burstSeconds` / `restSeconds` exist to support duty-cycling once implemented; today they're advisory.

## Memory

- `MemoryBudget` watches `ComponentCallbacks2.onTrimMemory` events plus periodic `ActivityManager.MemoryInfo` polls.
- Pressure: NORMAL → WARNING → CRITICAL → EMERGENCY. EMERGENCY forces a 2 048-token context cap regardless of device tier.
- The service routes its own `onTrimMemory` to `MemoryBudget` and then to `SessionManager.evictUnderPressure`.

## Foreground service

- Service is a *bound* service most of the time. It promotes itself to FGS `specialUse` only while `activeInferences > 0`.
- The notification channel ID is `mindlayer_inference`. Don't repurpose it for non-inference notifications.

## Logging on this hot path

- Use `MindlayerLog` with `requestId` and `sessionId` always populated.
- Use `Throwable.safeLabel()` for native exceptions and pass `throwable = null`. LiteRT-LM error messages and stack traces can embed prompt text.
- `RequestTrace` records `prefill_start`, `first_token`, `last_token` etc. — preserve those breakpoint names; the diagnostic exporter parses them.
- Persist via `LogRepository` (metadata only — token counts, durations, never content).

## Don't

- Don't read `currentBackend` / `isInitialized` outside the `Mutex` for decisions; they're `@Volatile` for *display*, not coordination.
- Don't open a new `Engine` per request — there is exactly **one**.
- Don't hold a session reference across an engine reinit — get it from `SessionManager` each time.

## LiteRT + LiteRT-LM coexistence (unverified)

The service loads **two distinct LiteRT-family runtimes** in the
same process: `com.google.ai.edge.litertlm:litertlm-android:0.11.0`
for Gemma, and `com.google.ai.edge.litert:litert:2.1.5` for the
embedding (`LiteRtEmbeddingBackend`) and OCR
(`LiteRtPaddleOcrBackend`) paths. No confirmed incompatibility is
known, but **public LiteRT/LiteRT-LM issues show real failure
modes** around accelerator resources, native library loading,
Android linker namespaces, and LiteRT symbol/version resolution:

- [LiteRT #5264](https://github.com/google-ai-edge/LiteRT/issues/5264) — multi-GPU `CompiledModel` instances in same process fail when the first is still active.
- [LiteRT-LM #2211](https://github.com/google-ai-edge/LiteRT-LM/issues/2211) — GPU samplers `dlopen` fail for AAR consumers due to linker namespace / `libLiteRt.so` resolution.
- [LiteRT-LM #2292](https://github.com/google-ai-edge/LiteRT-LM/issues/2292) — `Backend.GPU()` initialisation fails on Adreno 750 with OpenCL discovery problems + OpenGL fallback gaps.

Treat same-process coexistence as a **verify-on-device prototype
risk**, not as safe-by-construction. The full risk note + a
validation checklist live in [`docs/LITERT_COEXISTENCE.md`](../../docs/LITERT_COEXISTENCE.md).
Run that checklist before relying on any path that needs all
three stacks (Gemma + embedding + OCR) loaded simultaneously.
