# Mindlayer

On-device LLM inference service for Android, powered by [LiteRT-LM](https://ai.google.dev/edge/litert-lm) and [Gemma 4 E2B](https://ai.google.dev/gemma).

Mindlayer runs as a headless Android service that loads the Gemma 4 model once and serves inference requests to your other apps via a clean Kotlin SDK — no cloud, no latency, full privacy.

## Features

- 🧠 **Gemma 4 E2B** — 2.3B effective params, multimodal (text/image/audio), 128K context
- ⚡ **GPU-accelerated** — 3800+ tok/s prefill, 52 tok/s decode on flagship devices
- 🔌 **Service architecture** — one model instance serves all your apps via AIDL IPC
- 🌡️ **Thermal-aware** — 4-band controller (COOL/WARM/HOT/CRITICAL) with automatic GPU↔CPU switching
- 💾 **OOM-resilient** — client-side Room persistence + automatic session replay on crash recovery (requires `historyPolicy = FULL_CONTENT`)
- 🛠️ **Function calling** — manual tool mode with async bridge, structured JSON output
- 📊 **Dashboard** — Jetpack Compose monitoring UI with live engine/thermal/memory/session status
- 📝 **Structured logging** — Room-based usage logs + request tracing with timing breakpoints

## Architecture

```
┌─────────────────────────────┐
│       YOUR CLIENT APPS       │
│  ┌─────┐ ┌─────┐ ┌─────┐   │
│  │App A│ │App B│ │App C│   │
│  └──┬──┘ └──┬──┘ └──┬──┘   │
│     └────┬───┘───────┘      │
│   Mindlayer Client SDK      │
└──────────┼──────────────────┘
           │ AIDL + SharedMemory + Pipe
┌──────────┼──────────────────┐
│  MINDLAYER SERVICE (:ml)     │
│  Engine → Session → Stream   │
│  Thermal → Memory → Logging  │
└──────────────────────────────┘
```

## Quick Start

### For client apps (SDK consumer)

```kotlin
val mindlayer = Mindlayer.shared(context) // one shared client per process
val caps = mindlayer.getCapabilities()
require(caps.supports(ServiceCapabilities.FEATURE_PIPE_STREAM_V1))
val sessionId = mindlayer.createSession {
    systemPrompt("You are a helpful assistant")
}

val handle = mindlayer.chat(sessionId, "Hello!")
handle.events.collect { event ->
    when (event) {
        is MindlayerEvent.TextDelta -> print(event.text)
        is MindlayerEvent.Done -> println()
    }
}
```

> **First-run user consent.** Mindlayer is default-deny: the first call lands the
> client in `REJECTED_NOT_APPROVED` (`MindlayerErrorCode.CONSENT_REQUIRED`). Use
> `MindlayerConsent.requestConsent(context)` to show the consent screen, then
> retry via `awaitConnected()`. Reuse one client (`Mindlayer.shared(context)`) so
> every feature shares a single binding + consent flow. Full walkthrough and a
> copy-paste example: [`docs/sdk/SDK_INTEGRATION.md`](docs/sdk/SDK_INTEGRATION.md#first-run-user-consent).

### Building

> **JDK 21 required.** LiteRT-LM is compiled for Java 21 class-file
> version 65. Set `JAVA_HOME` to a JDK 21+ install before invoking
> `./gradlew` — older JDKs hit `UnsupportedClassVersionError` on every
> test class that touches the engine. CI uses `setup-java@v4` with
> `java-version: 21`; locally point at any JDK 21 (Temurin, JetBrains
> Runtime, Microsoft Build of OpenJDK).

```bash
# Build debug APK
./gradlew :app:assembleDebug

# Build a signed AAB with on-demand Play Asset Delivery packs (for Play Store)
# Requires keystore.properties and the vetted model cache.
./gradlew :app:bundleRelease --no-configuration-cache

# Run tests
./gradlew :app:testDebugUnitTest :sdk:testDebugUnitTest :shared:testDebugUnitTest
```

### Testing on emulator

```bash
# Push model to device
adb push gemma-4-E2B-it.litertlm /data/local/tmp/
adb shell "run-as com.adsamcik.mindlayer cp /data/local/tmp/gemma-4-E2B-it.litertlm files/"

# Launch
adb shell am start -n com.adsamcik.mindlayer/com.adsamcik.mindlayer.service.ui.MainActivity
```

## Project Structure

| Module | Purpose |
|--------|---------|
| `app/` | Service app — engine, IPC, logging, dashboard UI |
| `sdk/` | Client SDK — connect, chat, media transfer, history, recovery, embeddings, OCR |
| `sdk-camerax/` | Optional CameraX OCR adapter (`OcrImageAnalyzer`) |
| `shared/` | Shared Parcelable types + streaming protocol |
| `gemma_model/`, `gemma_model_part_2/` | Standard on-demand PAD fragments for Gemma 4 E2B |
| `gemma_embed_model/` | Standard on-demand PAD pack for EmbeddingGemma |
| `paddleocr_model/` | Standard on-demand PAD pack for PaddleOCR PP-OCRv5 |

## Capability matrix

| Capability | API | Current gate |
|---|---|---|
| Chat / multimodal inference | `chat`, `chatWithImage`, `chatWithAudio`, `chatWithMedia` | Production surface; check stream/protocol features for optional behavior. |
| Deferred inference | `chatDeferred`, `deferredCompletions`, `fetchDeferredResult` | `ServiceCapabilities.FEATURE_DEFERRED_INFERENCE` |
| Embeddings | `embed`, `embedBatch`, `embedBatchShm`, deferred batch | `ServiceCapabilities.FEATURE_EMBEDDINGS`; depends on model extraction + integrity verification. |
| OCR | `ocrSession`, `OcrEvent`, `:sdk-camerax` | `ServiceCapabilities.FEATURE_OCR_SESSION`; currently hidden by `OcrFeatureFlags.IS_PRODUCTION_READY=false`. |
| Health check | `ping` / SDK liveness fallback | `ServiceCapabilities.FEATURE_HEALTH_CHECK` |

## Caller Authorization

Every AIDL entry point is gated by a default-deny user-approved allowlist. The first time a client app tries to bind, Mindlayer records it as pending and rejects the call; the user approves the app explicitly from the dashboard, pinning its signing-cert SHA-256 at approval time. See [`docs/architecture/AUTHORIZATION.md`](docs/architecture/AUTHORIZATION.md) for the full flow, failure modes, and threat model, and [`docs/sdk/SDK_INTEGRATION.md`](docs/sdk/SDK_INTEGRATION.md#first-run-user-approval) for the client-side API.

## Model Deployment

All model families use **standard Play Asset Delivery 2.3.0** on demand. Google
Play performs the one-time transfer; inference is on-device and Mindlayer has
no network permission. Gemma is application-split into two packs then verified
and reconstructed in private storage (not an official LiteRT-LM sharding
feature); downloads preflight ~6 GB free space. Internal-track or bundletool
local testing is required. Development remains sideload-only via `dev-install`.

## Releasing

Production builds for the Play Store are **signed locally**. See [`docs/project/RELEASE.md`](docs/project/RELEASE.md) for the full keystore setup, `:app:bundleRelease` flow, R8 troubleshooting, and Play Console upload steps.

## Roadmap

What's done, what's next, and what's gated on device validation lives in [`docs/project/ROADMAP.md`](docs/project/ROADMAP.md). It's the single source of truth for outstanding work — OCR `IS_PRODUCTION_READY` flip criteria, model artifact pipeline, the ICDAR2015 numeric validation harness, and the Phase 7/8 polish backlog.

## License

Copyright © 2026 adsamcik

Mindlayer is licensed under the **GNU Affero General Public License, version 3** (AGPL-3.0-only). See [`LICENSE`](LICENSE) for the full text.

In short: you are free to use, modify, and distribute this software, including over a network. **If you do, you must release the complete corresponding source of your modifications under the same AGPL-3.0 license, including when the software is used as a network service.** This is a deliberate choice to keep derivative works open; if AGPL-3.0 obligations are incompatible with your use case, please open an issue to discuss alternative licensing.

`SPDX-License-Identifier: AGPL-3.0-only`

### Deferred async inference

Mindlayer supports fire-and-fetch-later flows for long-running work. Submit with `mindlayer.chatDeferred(sessionId, text)`, subscribe to `mindlayer.deferredCompletions()` for push notification, then call `fetchDeferredResult(requestId)`. Results are SQLCipher-encrypted at rest, scoped per UID, quota-limited, and expire after 24 hours by default.
