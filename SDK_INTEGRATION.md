# Mindlayer SDK Integration Guide

## Quick Setup

### 1. Add the GitHub Packages repository

In your client app's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/YOUR_GITHUB_USER/Mindlayer")
            credentials {
                username = providers.gradleProperty("GITHUB_OWNER").getOrElse("")
                password = providers.gradleProperty("GITHUB_TOKEN").getOrElse("")
            }
        }
    }
}
```

Add to your `~/.gradle/gradle.properties` (or project-level):
```properties
GITHUB_OWNER=your-github-username
GITHUB_TOKEN=ghp_your_personal_access_token
```

> **Token permissions:** The token needs `read:packages` scope. Create one at
> [GitHub Settings → Developer settings → Personal access tokens](https://github.com/settings/tokens).

### 2. Add the dependency

In your client app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.adsamcik.mindlayer:sdk:0.1.0")
    // 'shared' types are included transitively via 'api' dependency
}
```

### 3. Add the service permission

In your client app's `AndroidManifest.xml`:

```xml
<!-- Required to bind to Mindlayer service (signature-level) -->
<uses-permission android:name="com.adsamcik.mindlayer.permission.BIND_ML_SERVICE" />
```

> **Important:** Your client app must be signed with the **same signing key** as the
> Mindlayer service app. This is enforced by the `signature` protection level.

---

## Usage

### Connect to the service

```kotlin
import com.adsamcik.mindlayer.sdk.Mindlayer
import com.adsamcik.mindlayer.sdk.MindlayerEvent

// Connect (binds to the Mindlayer service in the background)
val mindlayer = Mindlayer.connect(context)

// Wait until connected (optional — chat() does this automatically)
mindlayer.awaitConnected()
```

### First-run user approval

Mindlayer is default-deny: the first time your app binds, the service records
a **pending approval** for your package + signing-cert SHA and rejects the
call with `SecurityException`. The user must then open the Mindlayer dashboard
and approve your app explicitly — Mindlayer pins the SHA-256 of your current
signing certificate at approval time, so a re-signed APK is rejected.

- During the pending window, `ConnectionManager.state` transitions to
  `REJECTED_NOT_APPROVED` and `awaitConnected()` throws `SecurityException`.
  This is a **terminal state** — the SDK will not auto-reconnect (that would
  poll the service and tip the rate limit). Your app should surface a "please
  approve Mindlayer access" prompt and retry `connect()` when the user asks.
- Once approved, the next `connect()` succeeds.
- The user can revoke access at any time from the dashboard.

See [`docs/AUTHORIZATION.md`](docs/AUTHORIZATION.md) for the full authorization model, including signing-cert rotation semantics, rate limiting, session ownership, and rejection-path failure modes.

### Encrypted on-device storage

Both the service's log DB and the SDK's conversation-history DB are encrypted
with SQLCipher. The passphrases are random 32-byte blobs wrapped in an
`AndroidKeystore` AES/GCM key. **Cross-install backup/restore produces an
unreadable DB** — the Keystore key doesn't move with a system backup. If
you ship a restore flow, treat the conversation history as ephemeral.

### Create a session

```kotlin
val sessionId = mindlayer.createSession {
    systemPrompt("You are a helpful coding assistant")
    maxTokens(4096)      // KV cache budget (input + output)
    backend("GPU")       // or "CPU", "NPU"
    topK(40)
    topP(0.95f)
    temperature(0.7f)
}
```

### Text chat (streaming)

```kotlin
mindlayer.chat(sessionId, "Explain Kotlin coroutines briefly").collect { event ->
    when (event) {
        is MindlayerEvent.TextDelta -> print(event.text)        // incremental token
        is MindlayerEvent.Done     -> println("\n[done]")       // generation complete
        is MindlayerEvent.Error    -> println("Error: ${event.message}")
        is MindlayerEvent.Started  -> { /* stream opened */ }
        is MindlayerEvent.Metrics  -> { /* optional perf data */ }
        is MindlayerEvent.ToolCall -> { /* function calling */ }
    }
}
```

### Image + text

```kotlin
val bitmap: Bitmap = // your image
mindlayer.chatWithImage(sessionId, "What's in this image?", bitmap).collect { event ->
    // same event handling as above
}
```

### Audio + text

```kotlin
val audioFile: File = // your WAV/MP3 file
mindlayer.chatWithAudio(sessionId, "Transcribe this audio", audioFile).collect { event ->
    // same event handling as above
}
```

### Function calling (tools)

```kotlin
val sessionId = mindlayer.createSession {
    systemPrompt("You have access to tools")
    tools("""[{
        "name": "get_weather",
        "description": "Get current weather",
        "parameters": {
            "type": "object",
            "properties": { "city": { "type": "string" } },
            "required": ["city"]
        }
    }]""")
}

val handle = mindlayer.chat(sessionId, "What's the weather in Prague?")
handle.events.collect { event ->
    when (event) {
        is MindlayerEvent.ToolCall -> {
            val result = fetchWeather(event.arguments) // your tool implementation
            mindlayer.submitToolResult(
                requestId = handle.requestId,
                callId = event.callId,
                toolName = event.toolName,
                resultJson = result,
            )
        }
        is MindlayerEvent.TextDelta -> print(event.text)
        is MindlayerEvent.Done -> println()
        else -> {}
    }
}
```

### Session convenience wrapper

```kotlin
val session = mindlayer.session(sessionId)
session.chat("Hello!").collect { ... }
session.delete()
```

### Cleanup

```kotlin
mindlayer.destroySession(sessionId)
mindlayer.disconnect()
```

---

## Diagnostics

```kotlin
// Get a full JSON diagnostic dump (engine, thermal, memory, sessions, logs)
val diagnostics: String = mindlayer.getDiagnostics()
println(diagnostics) // paste into bug reports
```

---

## Publishing (for Mindlayer maintainers)

```bash
# Publish to GitHub Packages
export GITHUB_OWNER=your-username
export GITHUB_TOKEN=ghp_your_token
./gradlew :shared:publishReleasePublicationToGitHubPackagesRepository
./gradlew :sdk:publishReleasePublicationToGitHubPackagesRepository

# Publish to local Maven (for testing)
./gradlew :shared:publishToMavenLocal :sdk:publishToMavenLocal
```

Bump version in `build.gradle.kts` root `publishVersion` before releasing.

---

## Requirements

- **Mindlayer service app** must be installed on the device
- **Same signing key** for service and client apps (signature permission)
- **Android 8.0+** (minSdk 26)
- **Model file** must be deployed (via Play AI Pack or manual push)
