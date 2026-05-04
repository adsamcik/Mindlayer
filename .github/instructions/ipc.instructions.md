---
applyTo: "**/ipc/**, **/Protocol.kt, **/TokenStreamReader.kt, **/MediaTransfer.kt"
description: "Pipe streaming protocol + SharedMemory media transfer"
---

<!-- context-init:managed -->

## Wire protocol (v1)

- Identifier: `protocol = "mindlayer.stream.v1"` (`StreamHeader` in `shared/.../Protocol.kt`).
- Frame format: `[4-byte little-endian u32 length][UTF-8 JSON payload]`.
- First frame: `StreamHeader { protocol, requestId }`. Subsequent frames: `StreamEvent { seq, type, tsMs, payload }`.
- Event types (`StreamEventType`): `start`, `token_delta`, `tool_call`, `tool_result`, `metrics`, `error`, `done`.
- Hard cap: `MAX_FRAME_BYTES = 1_048_576`. Duplicated in `TokenStreamWriter` and `TokenStreamReader` on purpose — don't unify the constant by sharing a class across modules without measuring impact.
- A frame > `MAX_FRAME_BYTES` is a programmer error — fail fast, do not truncate.

## Threading

- `TokenStreamWriter` is **not** thread-safe. The orchestrator's per-session `Mutex` is the serialisation boundary.
- `TokenStreamReader` is consumed from a single coroutine on the SDK side and emits a `Flow<MindlayerEvent>`. Don't fan out reads.
- All payloads serialise via `kotlinx.serialization` `Json { encodeDefaults = true }` — keep `encodeDefaults` true so optional fields with defaults stay forward/backward-compatible.

## SharedMemory media transfer

- `ImageTransfer` and `AudioTransfer` carry **only** a SharedMemory descriptor and metadata across AIDL — never raw bytes. Binder transaction limit is 1 MB.
- `:sdk` stages bytes via `MediaTransfer.stage*`; `:ml` reads via `SharedMemoryPool` and is responsible for unmapping.
- Always close the descriptor on the caller side once the AIDL call returns — the service makes its own copy if it needs to retain.

## Pipe lifecycle

- Client side (`:sdk`) creates the pipe with `ParcelFileDescriptor.createPipe()`, hands the *write* end to the service in the `infer` AIDL call, and reads from the *read* end on its own thread.
- Service writes the header + events, then a final `done` event, then closes the write end.
- Both sides must `use { … }` / try-finally `close()` — leaked PFDs are a process-wide resource leak.
- Cancellation: client closes the read end → writer next `write()` raises `IOException` → orchestrator catches and stops the session. The orchestrator additionally calls `handle.conversation.cancelProcess()` for native cancellation.

## Cross-module synchronisation

When you change anything in this scope, update **all** of these in the same change:

- `shared/src/main/kotlin/com/adsamcik/mindlayer/shared/Protocol.kt` (canonical types)
- `app/src/main/kotlin/com/adsamcik/mindlayer/service/ipc/TokenStreamWriter.kt` (emit)
- `sdk/src/main/kotlin/com/adsamcik/mindlayer/sdk/TokenStreamReader.kt` (consume)
- `app/src/test/.../testutil/TestPipeHelper.kt` (event-sequence assertions)
- `shared/src/test/.../shared/ProtocolTest.kt` and the writer/reader unit tests
- AIDL signatures in `IMindlayerService.aidl` if any new media or transfer types are introduced

## JSON encoding rules

- Keep `StreamEvent.payload : JsonObject` — opaque to the wire, schema is per `type`. Don't add new top-level fields to `StreamEvent`; add them inside `payload`.
- Use `buildJsonObject { put("k", v) }` over manual string concat for new payloads.
- Stable field names — clients pin them.

## Don't

- Don't introduce a new framing format or binary protocol. Mix in a sibling pipe if you need it.
- Don't add a `Gson` dependency to anything in this scope. `kotlinx.serialization` only.
- Don't write protocol metadata into AIDL parcels — it belongs in pipe frames.
