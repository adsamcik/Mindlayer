---
applyTo: "{app,sdk}/src/main/aidl/**/*.aidl,app/src/main/kotlin/com/adsamcik/mindlayer/service/ipc/**/*.kt,shared/src/main/kotlin/**/*.kt,sdk/src/main/kotlin/com/adsamcik/mindlayer/sdk/TokenStreamReader.kt"
description: "IPC, media transfer, and streaming protocol rules"
---

<!-- context-init:managed -->

- Preserve the 3-plane IPC split: Binder/AIDL for control, SharedMemory/PFD for media, PFD pipes for streaming.
- Do not move image/audio bytes into Binder transactions; Binder has a practical 1 MB limit.
- Pipe frames are exactly 4-byte little-endian u32 length + UTF-8 JSON payload.
- Keep writer/reader max frame sizes aligned at 1 MiB and test drift with protocol tests.
- Validate dimensions, pixel formats, strides, payload sizes, request IDs, session IDs, roles, and ownership at boundaries.
- Explicitly close/cleanup pipe and staged media resources on success, error, cancellation, and service shutdown.
