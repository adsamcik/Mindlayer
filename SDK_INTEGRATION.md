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

Add to your `~/.gradle/gradle.properties` (user-level only — never project-level):
```properties
GITHUB_OWNER=your-github-username
GITHUB_TOKEN=ghp_your_personal_access_token
```

> **Why user-level only?** `GITHUB_TOKEN` is a credential with `read:packages`
> scope. Project-level `gradle.properties` is committed to the repo, so a
> token written there leaks into git history (and any forks/clones). The
> user-level file lives outside the repo and is never tracked. A
> `gradle.properties.template` is provided at the repo root with the
> placeholders pre-filled — copy it, don't edit-in-place.

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

### 3. Permission

The Mindlayer SDK manifest already declares the signature-level service
permission, so it is inherited automatically by your app through manifest
merger:

```xml
<!-- Automatically inherited from the SDK manifest; you do not need to add this -->
<uses-permission android:name="com.adsamcik.mindlayer.permission.BIND_ML_SERVICE" />
```

The permission is `signature|knownSigner` and only effective when your app is
signed with one of the trusted first-party certs registered in Mindlayer; an
unrelated app gaining the `<uses-permission>` line via the SDK gains no actual
access to the service.

> **Important:** Cross-app first-party integration requires Android 12 (API 31)
> or later when the client is signed with a different Play app-signing key than
> Mindlayer. On older devices, `Mindlayer.connect()` / `awaitConnected()` fails
> with `MindlayerException` carrying
> `MindlayerErrorCode.UNSUPPORTED_ANDROID_VERSION`.

### Registering a new first-party app

A new first-party Android app must be registered in Mindlayer in two places in
the same PR:

1. Add its Play app-signing certificate SHA-256 (lowercase hex, no separators)
   to `app/src/main/res/values/arrays.xml` under
   `mindlayer_trusted_client_certs` for the OS `signature|knownSigner` gate.
2. Add the matching `(packageName, signingCertSha256)` entry to
   `MindlayerMlService.FIRST_PARTY_ALLOWLIST_SEEDS` for the AIDL allowlist gate.

The app parity test fails if these cert hash lists drift.

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
    // Mindlayer picks the best backend automatically.
    topK(40)
    topP(0.95f)
    temperature(0.7f)
}
```

### Text chat (streaming)

```kotlin
val handle = mindlayer.chat(sessionId, "Explain Kotlin coroutines briefly")
handle.events.collect { event ->
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
val handle = mindlayer.chatWithImage(sessionId, "What's in this image?", bitmap)
handle.events.collect { event ->
    // same event handling as above
}
```

### Audio + text

```kotlin
val audioFile: File = // your WAV/MP3 file
val handle = mindlayer.chatWithAudio(sessionId, "Transcribe this audio", audioFile)
handle.events.collect { event ->
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

### Structured JSON output

```kotlin
val sessionId = mindlayer.createSession {
    jsonOutput {
        schema("""{
            "type": "object",
            "required": ["name"],
            "properties": {
                "name": { "type": "string" },
                "age": { "type": "integer" }
            }
        }""")
        strategy(JsonOutputStrategy.PromptAndValidate)
    }
}
```

Structured output validation is intentionally **shallow**. The service checks
that the response is valid JSON, that top-level required fields exist, and that
top-level property types match the schema (`string`, `number`, `integer`,
`boolean`, `array`, `object`). It does **not** recursively enforce nested JSON
Schema rules such as nested `required`, `enum`, `format`, numeric ranges,
`pattern`, or `additionalProperties`. If a client treats structured output as a
security or business-rule boundary, validate the returned JSON again in the
client with a full schema validator.

### Session convenience wrapper

```kotlin
val session = mindlayer.session(sessionId)
val handle = session.chat("Hello!")
handle.events.collect { ... }
session.delete()
```


### Capabilities and prewarming

```kotlin
val caps = mindlayer.getCapabilities()
if (caps.supports(ServiceCapabilities.FEATURE_PREWARM_AWAIT)) {
    val backend = mindlayer.prewarmAndAwait(timeoutMs = 15_000)
}
```

Use `prewarm()` for fire-and-forget warmup; use `prewarmAndAwait()` when UI needs a ready/failure signal before enabling chat.

### Eviction notices

```kotlin
mindlayer.evictionNotices().collect { notice ->
    // Session was evicted or revoked; update UI and recover if appropriate.
}
```

### Detailed control responses

`cancelInferenceDetailed(requestId)` and `submitToolResultDetailed(result)` return structured outcomes for callers that need to distinguish success from `NO_ACTIVE_REQUEST` or validation failures. The simpler `cancelInference()` / `submitToolResult()` helpers remain available.

### Multimodal media

For one or more media parts, prefer `chatWithMedia(sessionId, text, mediaParts)` over chaining image/audio-specific calls. Use `chatWithImage` and `chatWithAudio` for simple single-media cases.

### History policy and recovery

The SDK defaults to `HistoryPolicy.METADATA_ONLY` for privacy. Transparent OOM/crash replay requires:

```kotlin
val mindlayer = Mindlayer.connect(context, historyPolicy = HistoryPolicy.FULL_CONTENT)
```

`FULL_CONTENT` stores prompts and model outputs in the SQLCipher-backed Room history database so `SessionRecovery` can recreate the service session. Cross-install backup/restore is intentionally unrecoverable because the SQLCipher key is wrapped by Android Keystore and does not move with backups.

If `recoverSession()` returns `pendingUserText`, resolve the returned pending turn before re-sending it:

```kotlin
val recovered = mindlayer.recovery?.recoverSession(sessionId)
recovered?.markPendingUserResolved(mindlayer.recovery!!)
recovered?.pendingUserText?.let { text ->
    mindlayer.chat(recovered.newSessionId, text)
}
```

### Conversation vs MindlayerSession

`Conversation` is the SDK-side convenience type for local lifecycle/history ergonomics. `MindlayerSession` is a lightweight wrapper around a live service `sessionId`. Server-side live sessions can disappear due to eviction or expiration; local history can outlive them.

### One-shot convenience family

Use `chatOnce`, `chatWithImageOnce`, `chatWithAudioOnce`, `generate`, `generateWithImage`, and `generateWithAudio` when you want a single complete `String` instead of streaming events. These methods convert stream `ERROR` frames into typed `MindlayerException`s.

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

## Deferred async inference (push + pull)

Release criterion #7 is supported through the deferred API. Use `chatDeferred(...)` to submit work, keep the returned `DeferredHandle.requestId`, and either listen to `deferredCompletions()` or poll `fetchDeferredResult(requestId)` later.

```kotlin
val handle = mindlayer.chatDeferred(sessionId, "Summarize this later")
mindlayer.deferredCompletions().collect { notice ->
    if (notice.requestId == handle.requestId) {
        val result = mindlayer.fetchDeferredResult(notice.requestId)
        if (result.status == DeferredResult.READY) println(result.text)
        mindlayer.acknowledgeDeferred(notice.requestId)
    }
}
```

Results are stored by the service until acknowledged or expired. Defaults: 16 in-flight deferred requests per UID, 64 completed/pending-fetch results per UID, 1 MiB accumulated result text per UID, and 24 hour TTL. Prompt text is never persisted in the deferred store; model result text is persisted intentionally for retrieval and is encrypted at rest with SQLCipher.

Failure modes are returned in `DeferredResult.status`: `STILL_RUNNING`, `NOT_FOUND_OR_NOT_OWNED` (also used for cross-UID anti-enumeration), `EXPIRED`, `FAILED`, or `CANCELLED`. New SDKs check `ServiceCapabilities.FEATURE_DEFERRED_INFERENCE`; if an older service does not advertise it, deferred calls throw `MindlayerErrorCode.NOT_SUPPORTED`.
