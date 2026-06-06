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

### 3. No permission required

Mindlayer no longer declares a signature-level bind permission. **Any installed
app can bind to the service** — there is nothing to add to your manifest and no
signing-key requirement. Access is not gated at the OS bind layer; it is gated
per-app by **explicit user consent** (see [First-run user consent](#first-run-user-consent)
below).

> **Migrating from an older SDK?** Delete any
> `<uses-permission android:name="com.adsamcik.mindlayer.permission.BIND_ML_SERVICE" />`
> line you previously added — that permission has been removed. There is also no
> longer any first-party cert registration step; the old
> `mindlayer_trusted_client_certs` array and `FIRST_PARTY_ALLOWLIST_SEEDS` are
> gone.

---

## Usage

### Connect to the service

Reuse **one client per process** and route every feature (LLM, OCR, embeddings)
through it. `Mindlayer.shared(context)` returns a process-wide singleton, so your
ViewModels / coordinators can't accidentally open a *separate* binding — and a
separate consent/resume flow — per feature:

```kotlin
import com.adsamcik.mindlayer.sdk.Mindlayer
import com.adsamcik.mindlayer.sdk.MindlayerEvent

// One shared client for the whole app process (LLM + OCR + embeddings).
val mindlayer = Mindlayer.shared(context)

// Wait until connected (optional — chat() does this automatically)
mindlayer.awaitConnected()
```

- The shared client lives for the **process**. Tear it down only at app shutdown
  or in tests via `Mindlayer.disconnectShared()` — do **not** call `disconnect()`
  on it. The `historyPolicy` is fixed on first use; a later `shared()` with a
  *different* policy throws (it is privacy-sensitive, never silently ignored).
- Use `Mindlayer.connect(context, historyPolicy, observer)` instead only when you
  genuinely need an **isolated** client (a distinct history policy / observer, or
  an independent disconnect lifetime). `connect()` opens a fresh binding on every
  call, so two `connect()`s = two bindings + two consent flows.
- "Shared" is per Android **process**, not per app package.

### First-run user consent

Mindlayer is default-deny: the first time your app calls the service, it is
rejected because the user has not yet granted your app access. The SDK surfaces
this as `ConnectionState.REJECTED_NOT_APPROVED` and `awaitConnected()` throws
(the service returned `MindlayerErrorCode.CONSENT_REQUIRED`). This is a
**terminal state** — the SDK will not silently retry (that would poll the
service and burn the rate limit).

To obtain consent, launch the Mindlayer consent screen with
`MindlayerConsent.requestConsent(...)` and retry your call when the user
approves:

```kotlin
import androidx.activity.result.ActivityResultContracts.StartIntentSenderForResult
import androidx.activity.result.IntentSenderRequest
import com.adsamcik.mindlayer.sdk.ConsentRequestResult
import com.adsamcik.mindlayer.sdk.MindlayerConsent

// 1. Register a launcher (Activity / Fragment scope).
private val consentLauncher = registerForActivityResult(
    StartIntentSenderForResult(),
) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
        // Approved — retry connect()/your call. It will now succeed.
        lifecycleScope.launch { mindlayer.awaitConnected() }
    }
    // RESULT_CANCELED => the user declined or dismissed; surface a message.
}

// 2. When a call fails with CONSENT_REQUIRED (REJECTED_NOT_APPROVED), ask:
lifecycleScope.launch {
    when (val r = MindlayerConsent.requestConsent(context)) {
        is ConsentRequestResult.Available ->
            consentLauncher.launch(IntentSenderRequest.Builder(r.intentSender).build())
        ConsentRequestResult.AlreadyApproved ->
            mindlayer.awaitConnected() // already granted; just (re)connect
        is ConsentRequestResult.Denied -> {
            // r.untilEpochMs == null  => permanently blocked (user unblocks in dashboard)
            // r.untilEpochMs != null  => temporary block; retry after that time
        }
        ConsentRequestResult.ServiceUnavailable -> { /* service not installed */ }
        is ConsentRequestResult.Failed -> { /* r.code / r.message */ }
    }
}
```

What the user sees: a branded, full-screen Mindlayer consent prompt showing your
app's label, signing certificate, and install source, with **Approve** / **Deny**
options. Approving runs a biometric confirmation, then pins the SHA-256 of your
**current** signing certificate — a later re-signed APK is treated as a new app
and must be approved again.

- On **Approve**, the next `connect()` / `awaitConnected()` succeeds.
- On **Deny**, the user can choose "Not now" (you may ask again later),
  "Deny for 24 hours", or "Block permanently". A denied app cannot re-trigger
  the prompt until the denial lapses; `requestConsent()` returns
  `Denied(untilEpochMs)` in that window instead of showing the screen again.
- The user can revoke access at any time from the Mindlayer dashboard
  ("Approved apps"), and unblock a permanently-blocked app from "Blocked apps".

> **Background services:** `requestConsent()` returns an `IntentSender` that must
> be launched from an `Activity`/UI context. A pure background worker has no way
> to show the consent screen — fail loud and defer the work until your app next
> has UI. Consent is expected to be granted before headless inference runs.

See [`docs/AUTHORIZATION.md`](docs/AUTHORIZATION.md) and
[`docs/CONSENT_ARCHITECTURE.md`](docs/CONSENT_ARCHITECTURE.md) for the full
authorization model, including signing-cert rotation semantics, the consent-Intent
handshake, rate limiting, session ownership, and denial semantics.

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

### Vision tasks (object detection, captioning, locating)

For higher-level vision workflows the SDK ships typed helpers in
`com.adsamcik.mindlayer.sdk.vision` that wrap Gemma 4's native
[vision capabilities](https://ai.google.dev/gemma/docs/capabilities/vision):

```kotlin
import com.adsamcik.mindlayer.sdk.vision.*

// Object detection — Gemma emits {"box_2d": [y1, x1, y2, x2], "label": ...} JSON
// in a 0..1000 grid; the helper parses it into typed DetectedObjects with
// normalized 0..1 coordinates you can project onto any image size.
val people = mindlayer.detectObjects(bitmap, labels = listOf("person", "car"))
for (obj in people) {
    val rect = obj.box.toPixelRect(bitmap.width, bitmap.height)
    canvas.drawRect(rect, paint)
}

// Strict variant — distinguish "no objects" from "model didn't comply"
when (val r = mindlayer.detectObjectsResult(bitmap, listOf("dog"))) {
    is DetectionResult.Success           -> useObjects(r.objects)
    DetectionResult.NoStructuredOutput   -> logPromptDrift()
    is DetectionResult.ParseError        -> logParseFailure(r.message)
}

// Locate a single thing by free-form description
val redCar = mindlayer.locateObject(bitmap, "the red car on the left")

// Caption / describe with documented best-practice prompts
val caption = mindlayer.captionImage(bitmap, CaptionStyle.Short)
val long = mindlayer.describeImage(bitmap, DescribeDetail.Long, focus = "the lighting")

// Approximate counting (per Gemma docs, dense scenes are not exact)
val n = mindlayer.countItems(bitmap, "people")
```

The helpers use the existing `infer { image(...) }` path and add no service,
wire, or model requirements. They work today against any model that
`ModelInfo.supportsVision = true` advertises (Gemma 4 family). Heavy-weight
OCR for documents is still served by the separate PaddleOCR pipeline via
[`mindlayer.ocr { ... }`](#ocr); detection-shaped tasks should use the
helpers above.

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

## Testing your integration in CI (mock engines)

Your app's CI can verify its Mindlayer integration **end-to-end** — bind →
consent → `getCapabilities()` → `ocr` / `embed` (and the OCR→LLM extraction
path) over the real AIDL + pipe + SharedMemory wire — **without** the ~3 GB of
on-device models. A **debug build of the Mindlayer service** ships a hidden
"mock engines" mode that serves deterministic, plausible, `[mock]`-tagged data.

> Mock mode is **DEBUG-only**. The mock backends are physically absent from the
> release classpath and the toggle can never be armed in a production build.

### Arm it

On the emulator/device, with a **debug** build of the Mindlayer service
installed (no models required):

```bash
# turn mock engines on
adb shell setprop debug.mindlayer.mock_engines 1
# restart the service so it re-reads the flag at onCreate
adb shell am force-stop com.adsamcik.mindlayer.service.debug
```

With the flag set:

- `getCapabilities()` advertises `FEATURE_EMBEDDINGS`, `FEATURE_OCR_SESSION`,
  and `FEATURE_OCR_IMAGE_ONESHOT` (the LLM streaming features are always
  advertised).
- `embed(text)` returns a deterministic 768-d unit vector: the **same** text
  yields the **same** vector (cosine 1.0) and **different** text yields a
  near-orthogonal one (cosine well below 0.99) — so cache/idempotency and
  "distinct inputs → distinct embeddings" assertions hold. Matryoshka
  `outputDim` (768/512/256/128) is honoured.
- OCR returns `[mock]`-prefixed lines (honouring `maxLines` and the
  bounding-box capability); the single-image OCR→LLM extraction returns
  `[mock]` fields.
- **LLM chat / vision / audio** (`createSession` + `infer` / `inferStream`)
  streams a synthetic `[mock]` reply over the real pipe — header → token
  deltas → `done(finish=stop)` — that reflects the request modality (e.g.
  "Received an image request…") and echoes a short prefix of your prompt. No
  ~2.4 GB Gemma model is loaded, so the turn returns instantly. Image / audio
  attachments are still staged over real SharedMemory, so the SHM transport is
  exercised end to end.

Every mock payload is `[mock]`-tagged so your tests can assert they are talking
to the mock and never mistake synthetic output for a real result.

### Pair with auto-accept so the consent gate doesn't block CI

A headless run can't tap the consent dialog. Combine mock mode with the
debug-only auto-accept toggle (also DEBUG-only, DUMP-guarded):

```bash
adb shell am broadcast \
  -n com.adsamcik.mindlayer.service.debug/com.adsamcik.mindlayer.service.security.DebugAutoAcceptReceiver \
  -a com.adsamcik.mindlayer.debug.SET_AUTO_ACCEPT --ez enabled true
```

### Scope

Mock mode covers every Mindlayer engine — embeddings, OCR, OCR→LLM extraction,
and the interactive LLM (chat / vision / audio). It verifies the full wire
contract (bind → consent → capabilities → AIDL → pipe → SharedMemory → typed
errors); it does **not** assert anything about real model *quality*, since no
real model runs.

### Disarm

```bash
adb shell setprop debug.mindlayer.mock_engines 0
adb shell am force-stop com.adsamcik.mindlayer.service.debug
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
- **User consent**: the user must approve your app once via the Mindlayer
  consent screen (any signing key works — no first-party registration)
- **Android 8.0+** (minSdk 26)
- **Model files** must be deployed (via Play AI Packs or manual staging)

## Deferred async inference (push primary, polling fallback)

Release criterion #7 is supported through the deferred API. Use
`chatDeferred(...)` to submit work, keep the returned
`DeferredHandle.requestId`, then prefer the push completion stream
`deferredCompletions()`. Poll `fetchDeferredResult(requestId)` only as a
fallback for old service versions, recovered processes, or UIs that cannot keep
a collector active.

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

## OCR

Mindlayer OCR is a multi-frame session API for receipts, documents, ID cards,
whiteboards, and screen captures. The API and event stream are wired, but the
service currently keeps `FEATURE_OCR_SESSION` dark because
`OcrFeatureFlags.IS_PRODUCTION_READY=false`; production callers must degrade
when the capability is absent.

### Quick start

```kotlin
val session = mindlayer.ocrSession(OcrProfile.Receipt) { maxFrames = 30 }
val ack = session.pushFrame(meta, yPlaneBytes, width, height)
session.finalize()
```

### Capability check

The SDK's OCR methods call an internal `requireOcrCapability()` guard and throw
a typed `MindlayerException` with `FEATURE_NOT_SUPPORTED` when the connected
service does not advertise OCR. Check explicitly when enabling UI:

```kotlin
val caps = mindlayer.getCapabilities()
if (!caps.supports(ServiceCapabilities.FEATURE_OCR_SESSION)) {
    showOcrUnavailable()
    return
}
```

### Inputs: Y-plane vs encoded bytes

- `session.pushFrame(meta, yPlane, width, height, rowStride, pixelStride)` is
  the preferred camera path. It sends a defensive copy of the luminance plane
  and preserves stride metadata.
- `session.pushEncodedFrame(meta, bytes, mimeType)` accepts pre-encoded
  JPEG/PNG/WEBP bytes from non-CameraX pipelines.
- Payloads at or below the Binder-sized `MAX_FRAME_BYTES` guard are sent inline;
  larger frames should be downscaled or routed through the SharedMemory-backed
  media helpers.

### Event stream

Attach `session.events` before pushing frames so no events are lost. If a frame
arrives first, the service returns
`OcrFrameAck.STATUS_REJECTED_STREAM_NOT_ATTACHED`.

```kotlin
session.events.collect { event ->
    when (event) {
        is OcrEvent.FrameProcessing -> showProgress(event.frameId)
        is OcrEvent.FrameDroppedBusy -> throttle(event.retryAfterMs)
        is OcrEvent.ResultFinalized -> renderJson(event.fullJson)
        is OcrEvent.Error -> showError(event.code, event.message)
        else -> Unit
    }
}
```

### Error handling and finalize semantics

Synchronous intake failures are returned as `OcrFrameAck` statuses. AIDL
failures become typed `MindlayerException`s through the SDK chokepoint. Stream
failures are surfaced as `OcrEvent.Error` before the flow terminates.

`session.finalize()` transitions the service session into a drain state:
accepted in-flight frames complete, later pushes are rejected with
`STATUS_REJECTED_FINALIZED`, and the event stream emits
`OcrEvent.ResultFinalized` followed by `DONE`/pipe close.

### CameraX helper

Add `com.adsamcik.mindlayer:sdk-camerax:<version>` for
`OcrImageAnalyzer`, an `ImageAnalysis.Analyzer` that copies the Y-plane,
runs optional client-side presort, pushes accepted frames, and closes each
`ImageProxy` immediately. The adapter is `compileOnly` against CameraX; the
host app supplies its CameraX version.

## Embeddings

Mindlayer embeddings compute semantic vectors for short text on-device with EmbeddingGemma-300M. Use them for RAG, semantic search, clustering, and classification when `ServiceCapabilities.FEATURE_EMBEDDINGS` is advertised; clients should degrade gracefully while the install-time Asset Pack is still extracting.

### Capability check

```kotlin
if (!mindlayer.getCapabilities().supports(ServiceCapabilities.FEATURE_EMBEDDINGS)) {
    // Show "Indexing unavailable" UI; Asset Pack may still be downloading.
}
```

### Quick start

Single embed:

```kotlin
val vector: FloatArray = mindlayer.embed("the cat sat on the mat")
```

Inline batch (≤ 64 items):

```kotlin
val results = mindlayer.embedBatch(
    listOf(
        EmbeddingConfig(text = "doc1", tag = "doc1"),
        EmbeddingConfig(text = "doc2", tag = "doc2"),
    ),
)
```

Deferred batch with push completion:

```kotlin
val handle = mindlayer.embedBatchDeferred(documents.map { (id, text) ->
    EmbeddingConfig(text = text, tag = id)
})

mindlayer.embeddingBatchCompletions().collect { requestId ->
    if (requestId == handle.requestId) {
        when (val outcome = mindlayer.fetchEmbeddingBatch(handle)) {
            is EmbeddingBatchOutcome.Ready -> {
                // Store outcome.results in your app-owned index.
                mindlayer.acknowledgeEmbeddingBatch(handle)
            }
            else -> Unit
        }
    }
}
```

### RAG cookbook

```kotlin
val mindlayer = Mindlayer.connect(context)
mindlayer.awaitConnected()

val caps = mindlayer.getCapabilities()
if (!caps.supports(ServiceCapabilities.FEATURE_EMBEDDINGS)) {
    showIndexingUnavailable()
    return
}

val documents = listOf(
    "doc1" to "the cat sat on the mat",
    "doc2" to "a small dog slept by the door",
    "doc3" to "semantic search returns nearby meaning",
)

val index = InMemoryVectorIndex()
val embeddedDocs = mindlayer.embedBatch(
    documents.map { (id, text) ->
        EmbeddingConfig(
            text = text,
            task = EmbeddingTask.RetrievalDocument,
            tag = id,
        )
    },
)

embeddedDocs.forEach { result ->
    val id = result.tag ?: return@forEach
    val source = documents.first { it.first == id }.second
    index.put(id = id, vector = result.vector, payload = source)
}

val query = "find notes about cats"
val queryResult = mindlayer.embed(
    EmbeddingConfig(
        text = query,
        task = EmbeddingTask.RetrievalQuery,
    ),
)

val contextText = index.search(queryResult.vector, k = 3)
    .joinToString("\n") { hit -> "- ${hit.payload as String}" }

val sessionId = mindlayer.createSession {
    systemPrompt("Answer using only the supplied context when possible.")
}

mindlayer.chat(
    sessionId,
    "Context:\n$contextText\n\nQuestion: $query",
).events.collect { event ->
    if (event is MindlayerEvent.TextDelta) print(event.text)
}
```

### Threat model

Embeddings are derived from source text and can leak content via inversion or similarity-oracle attacks. Treat vectors as sensitive: never log text inputs or vectors, keep indexes per app/UID, and rely on Mindlayer's per-UID service isolation for cross-caller separation.

### Performance ballpark

S25 Ultra spike measurements for EmbeddingGemma-300M:

| Backend | 256 tokens | 512 tokens |
|---|---:|---:|
| NPU | 8-18 ms | 18 ms |
| GPU | 64-119 ms | 119 ms |
| CPU | 66-169 ms | 169 ms |

### Asset Pack delivery

The `:gemma_embed_model` Play AI Pack is install-time delivery and carries both `embedding-gemma-300m-v1.tflite` and `embedding-gemma-300m-v1.spm.model`. The service advertises `FEATURE_EMBEDDINGS` only after extraction and SHA-256 verification succeed.

