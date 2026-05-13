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
val mindlayer = Mindlayer.connect(context)
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

# Build AAB with AI pack (for Play Store)
./gradlew :app:bundleRelease

# Run tests
./gradlew :app:testDebugUnitTest :sdk:testDebugUnitTest :shared:testDebugUnitTest
```

### Testing on emulator

```bash
# Push model to device
adb push gemma-4-E2B-it.litertlm /data/local/tmp/
adb shell "run-as com.adsamcik.mindlayer.service cp /data/local/tmp/gemma-4-E2B-it.litertlm files/"

# Launch
adb shell am start -n com.adsamcik.mindlayer.service/.ui.MainActivity
```

## Project Structure

| Module | Purpose |
|--------|---------|
| `app/` | Service app — engine, IPC, logging, dashboard UI |
| `sdk/` | Client SDK — connect, chat, media transfer, history, recovery |
| `shared/` | Shared Parcelable types + streaming protocol |
| `gemma_model/` | Play for On-device AI pack (install-time delivery) |

## Caller Authorization

Every AIDL entry point is gated by a default-deny user-approved allowlist. The first time a client app tries to bind, Mindlayer records it as pending and rejects the call; the user approves the app explicitly from the dashboard, pinning its signing-cert SHA-256 at approval time. See [`docs/AUTHORIZATION.md`](docs/AUTHORIZATION.md) for the full flow, failure modes, and threat model, and [`SDK_INTEGRATION.md`](SDK_INTEGRATION.md#first-run-user-approval) for the client-side API.

## Model Deployment

The Gemma 4 E2B model (~2.4GB) is delivered via **Play for On-device AI** as an install-time AI pack. For development, push manually via `adb`.

## Releasing

Production builds for the Play Store are **signed locally**. See [`RELEASE.md`](RELEASE.md) for the full keystore setup, `:app:bundleRelease` flow, R8 troubleshooting, and Play Console upload steps.

## License

Copyright © 2026 adsamcik

Mindlayer is licensed under the **GNU Affero General Public License, version 3** (AGPL-3.0-only). See [`LICENSE`](LICENSE) for the full text.

In short: you are free to use, modify, and distribute this software, including over a network. **If you do, you must release the complete corresponding source of your modifications under the same AGPL-3.0 license, including when the software is used as a network service.** This is a deliberate choice to keep derivative works open; if AGPL-3.0 obligations are incompatible with your use case, please open an issue to discuss alternative licensing.

`SPDX-License-Identifier: AGPL-3.0-only`

### Deferred async inference

Mindlayer supports fire-and-fetch-later flows for long-running work. Submit with `mindlayer.chatDeferred(sessionId, text)`, subscribe to `mindlayer.deferredCompletions()` for push notification, then call `fetchDeferredResult(requestId)`. Results are SQLCipher-encrypted at rest, scoped per UID, quota-limited, and expire after 24 hours by default.
