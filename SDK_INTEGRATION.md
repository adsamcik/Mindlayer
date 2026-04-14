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
    implementation("com.mindlayer:sdk:0.1.0")
    // 'shared' types are included transitively via 'api' dependency
}
```

### 3. Add the service permission

In your client app's `AndroidManifest.xml`:

```xml
<!-- Required to bind to Mindlayer service (signature-level) -->
<uses-permission android:name="com.mindlayer.permission.BIND_ML_SERVICE" />
```

> **Important:** Your client app must be signed with the **same signing key** as the
> Mindlayer service app. This is enforced by the `signature` protection level.

---

## Usage

### Connect to the service

```kotlin
import com.mindlayer.sdk.Mindlayer
import com.mindlayer.sdk.MindlayerEvent

// Connect (binds to the Mindlayer service in the background)
val mindlayer = Mindlayer.connect(context)

// Wait until connected (optional — chat() does this automatically)
mindlayer.awaitConnected()
```

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

mindlayer.chat(sessionId, "What's the weather in Prague?").collect { event ->
    when (event) {
        is MindlayerEvent.ToolCall -> {
            val result = fetchWeather(event.arguments) // your tool implementation
            mindlayer.submitToolResult(event.callId, event.toolName, result)
        }
        is MindlayerEvent.TextDelta -> print(event.text)
        is MindlayerEvent.Done -> println()
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
