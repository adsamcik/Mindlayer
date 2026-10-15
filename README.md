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
val handle = mindlayer.infer { text("Hello!") }
handle.events.collect { event ->
    when (event) {
        is InferenceEvent.TextDelta -> print(event.text)
        is InferenceEvent.Error -> showError(event.message)
        else -> Unit
    }
}
```

> **First-run user consent.** Mindlayer is default-deny: the first call lands the
> client in `REJECTED_NOT_APPROVED` (`MindlayerErrorCode.CONSENT_REQUIRED`). Use
> `MindlayerConsent.requestConsent(context)` to show the consent screen, then
> retry via `awaitConnected(Duration.INFINITE)`. Reuse one client (`Mindlayer.shared(context)`) so
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

# Build a signed AAB with on-demand Play Asset Delivery packs (for Play Store).
# Use Android Studio's Generate Signed App Bundle wizard, or configure
# keystore.properties for this direct Gradle command. Both need the model cache.
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
| `sdk-camera-launcher/` | Activity Result camera capture flow |
| `shared/` | Shared Parcelable types + streaming protocol |
| `gemma_model/`, `gemma_model_part_2/` | Standard on-demand PAD fragments for Gemma 4 E2B |
| `gemma_embed_model/` | Standard on-demand PAD pack for EmbeddingGemma |
| `paddleocr_model/` | Standard on-demand PAD pack for PaddleOCR PP-OCRv5 |

## Capability matrix

| Capability | API | Current gate |
|---|---|---|
| Chat / multimodal inference | `infer`, `ask`, `describe`, `transcribe` | Production surface; check stream/protocol features for optional behavior. |
| Embeddings | `embed`, `vector`, `vectors`; `embed { deferred() }` from 1.0.0-alpha.10 | `ServiceCapabilities.FEATURE_EMBEDDINGS`; depends on model extraction + integrity verification. |
| OCR | `ocr`, `ocrSession`, `:sdk-camerax`, `:sdk-camera-launcher` | Production flag enabled; the corresponding OCR capability requires verified, available model assets. |
| Health check | `ping` / SDK liveness fallback | `ServiceCapabilities.FEATURE_HEALTH_CHECK` |

## Caller Authorization

Service calls are default-deny until the user approves the client through Mindlayer's consent screen, pinning its signing-certificate SHA-256. The dashboard lets the user revoke or unblock apps. See [`CONSENT_ARCHITECTURE.md`](docs/architecture/CONSENT_ARCHITECTURE.md) for the trust model and [`SDK_INTEGRATION.md`](docs/sdk/SDK_INTEGRATION.md#first-run-user-consent) for the client flow.

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

Implemented features, remaining native/model/Play qualification, and optional research live in [`docs/project/ROADMAP.md`](docs/project/ROADMAP.md). Code-only alpha publication and model-bearing Play distribution have separate evidence requirements.

## License

Copyright © 2026 adsamcik

Mindlayer is licensed under the **GNU Affero General Public License, version 3** (AGPL-3.0-only). See [`LICENSE`](LICENSE) for the full text.

In short: you are free to use, modify, and distribute this software, including over a network. **If you do, you must release the complete corresponding source of your modifications under the same AGPL-3.0 license, including when the software is used as a network service.** This is a deliberate choice to keep derivative works open; if AGPL-3.0 obligations are incompatible with your use case, please open an issue to discuss alternative licensing.

`SPDX-License-Identifier: AGPL-3.0-only`

### Deferred embeddings (1.0.0-alpha.10)

`embed { items(batch); deferred() }` returns a durable request handle. Call
`awaitReady()` for vectors and metadata, then `acknowledge()` to release the
stored result. An optional positive `deadline` bounds submission and waiting.
See [SDK integration](docs/sdk/SDK_INTEGRATION.md#embeddings) for cleanup and
timeout behavior.
