---
applyTo: "**/*.kt"
description: "Kotlin and coroutine rules for Mindlayer"
---

<!-- context-init:managed -->

- Keep all code Kotlin-first and Java 17 compatible.
- Run blocking model, DB, file, pipe, and SDK service calls on coroutine dispatchers; prefer `Dispatchers.IO` for blocking work.
- Use `MindlayerLog` for correlated service logs when request/session context matters; sanitize caller-controlled IDs.
- Do not log raw prompts, tool results, model outputs, media payloads, or untrusted exception messages.
- Prefer `kotlinx.serialization` for shared protocol JSON. Do not add Gson.
- Document lifecycle/thread-safety expectations on public service/SDK APIs when they affect callers.
