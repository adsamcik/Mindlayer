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
            url = uri("https://maven.pkg.github.com/adsamcik/Mindlayer")
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
    implementation("com.adsamcik.mindlayer:sdk:1.0.0-alpha.10")
    // 'shared' types are included transitively via 'api' dependency
}
```

Use a published version from the
[GitHub Packages page](https://github.com/adsamcik/Mindlayer/packages). Keep
every optional Mindlayer module at the same version as `sdk`.

### Optional SDK modules

```kotlin
dependencies {
    // Bring-your-own CameraX ImageAnalysis.Analyzer integration.
    implementation("com.adsamcik.mindlayer:sdk-camerax:<version>")

    // Turn-key ActivityResult-based camera capture flow.
    implementation("com.adsamcik.mindlayer:sdk-camera-launcher:<version>")
}
```

`sdk-camerax` is `compileOnly` against CameraX, so its consumers choose their
own compatible CameraX version. `sdk-camera-launcher` supplies its own CameraX
capture UI. All modules are published from the same `v<semver>` tag and use that tag's
version.

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
import kotlin.time.Duration

// One shared client for the whole app process (LLM + OCR + embeddings).
val mindlayer = Mindlayer.shared(context)

// Wait until connected (optional — feature calls connect automatically)
mindlayer.awaitConnected(Duration.INFINITE)
```

- The shared client object lives for the **process**. Its service binding may be
  suspended after five minutes with no visible activity or active Mindlayer
  work; visibility or the next API call reconnects automatically. Tear the
  object down only at app shutdown or in tests via
  `Mindlayer.disconnectShared()` — do **not** call `disconnect()` on it. The
  `historyPolicy` is fixed on first use; a later `shared()` with a *different*
  policy throws (it is privacy-sensitive, never silently ignored).
- Use `Mindlayer.connect(context, historyPolicy, observer)` instead only when you
  genuinely need an **isolated** client (a distinct history policy / observer, or
  an independent disconnect lifetime). `connect()` opens a fresh binding on every
  call, so two `connect()`s = two bindings + two consent flows.
- "Shared" is per Android **process**, not per app package.

### First-run user consent

Mindlayer is default-deny: the first time your app calls the service, it is
rejected because the user has not yet granted your app access. The SDK surfaces
this as `ConnectionState.REJECTED_NOT_APPROVED` and `awaitConnected(Duration.INFINITE)` throws
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
        lifecycleScope.launch { mindlayer.awaitConnected(Duration.INFINITE) }
    }
    // RESULT_CANCELED => the user declined or dismissed; surface a message.
}

// 2. When a call fails with CONSENT_REQUIRED (REJECTED_NOT_APPROVED), ask:
lifecycleScope.launch {
    when (val r = MindlayerConsent.requestConsent(context)) {
        is ConsentRequestResult.Available ->
            consentLauncher.launch(IntentSenderRequest.Builder(r.intentSender).build())
        ConsentRequestResult.AlreadyApproved ->
            mindlayer.awaitConnected(Duration.INFINITE) // already granted; just (re)connect
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

- On **Approve**, the next `connect()` / `awaitConnected(Duration.INFINITE)` succeeds.
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

See [`docs/architecture/AUTHORIZATION.md`](../architecture/AUTHORIZATION.md) and
[`docs/architecture/CONSENT_ARCHITECTURE.md`](../architecture/CONSENT_ARCHITECTURE.md) for the full
authorization model, including signing-cert rotation semantics, the consent-Intent
handshake, rate limiting, session ownership, and denial semantics.

### Encrypted on-device storage

Both the service's log DB and the SDK's conversation-history DB are encrypted
with SQLCipher. The passphrases are random 32-byte blobs wrapped in an
`AndroidKeystore` AES/GCM key. **Cross-install backup/restore produces an
unreadable DB** — the Keystore key doesn't move with a system backup. If
you ship a restore flow, treat the conversation history as ephemeral.

### Inference and sessions

```kotlin
import com.adsamcik.mindlayer.sdk.InferenceEvent

// A fresh session is owned and cleaned up by the inference handle.
val reply = mindlayer.ask("Summarize this paragraph")

// Keep an explicit session when several turns share context.
val session = mindlayer.openSession {
    systemPrompt = "Answer briefly."
    maxTokens = 2048
}
try {
    mindlayer.infer {
        session(session.id)
        text("Hello!")
    }.events.collect { event ->
        when (event) {
            is InferenceEvent.TextDelta -> print(event.text)
            is InferenceEvent.Error -> showError(event.message)
            else -> Unit
        }
    }
} finally {
    session.closeAsync()
}
```

`infer { }` streams real service deltas. Collect its events once; cancelling
that coroutine cancels unfinished native inference. Use `handle.cancel()` for
an explicit Stop action. The `awaitText()` and `awaitJson()` extensions consume
the same stream, so do not also collect it separately.

Add `image(bitmap)` or `audio(file)` inside `infer` for multimodal input, or use
`describe(prompt, bitmap)` / `transcribe(audioFile)` for common one-shot tasks.
Media uses the SDK transport; keep large payloads out of AIDL parcels.

For JSON, use `infer { text(prompt); outputJson(schema) }.awaitJson()` or
`extractJson(prompt, schema)`. The ephemeral session configures service-side
schema enforcement. A named session must configure its schema up front through
`openSession { jsonOutput { schema(schemaText) } }`.

From `1.0.0-alpha.10`, `sampling { seed = 42 }` configures the native sampler
for an ephemeral session. It requires `FEATURE_SAMPLER_SEED`; older services
reject it explicitly. Non-null sampling settings on a named session fail with
`INVALID_REQUEST`, since the session already owns its configuration. An empty
or all-null block is allowed. Seeds do not guarantee identical output across
backends.

### Function calling (tools)

```kotlin
import com.adsamcik.mindlayer.sdk.InferenceHandle

// tools: List<ToolSpec>; executeTool returns JSON for the observed call.
val handle = mindlayer.infer {
    text(prompt)
    outputTools(tools)
} as InferenceHandle.Tools
handle.events.collect { event ->
    when (event) {
        is InferenceEvent.ToolCall ->
            handle.submitToolResult(event.callId, executeTool(event.toolName, event.arguments))
        is InferenceEvent.TextDelta -> print(event.text)
        is InferenceEvent.Error -> showError(event.message)
        else -> Unit
    }
}
```

Manual submission is available from `1.0.0-alpha.10`. Submit each observed
call once; unknown, duplicate or stale IDs are rejected. Alternatively register
`onToolCall { call -> executeTool(call.name, call.argsJson) }` inside `infer`
to run the response loop automatically. Choose one response owner. A throwing
automatic handler cancels inference and emits `TOOL_HANDLER_FAILED`.

### Capabilities and prewarming

Use `getCapabilities()` before optional features. Model availability and
integrity still determine whether OCR and embeddings are advertised; enabling
the production feature flags alone does not load a model.

`prewarmForContext(maxTokens, backend)` requests a known total context
(128–32,768 input plus output tokens). It is capability-gated and a safe no-op
on older services. A larger context may require a service-process restart.
`prewarm(backend)` uses a conservative speculative memory budget.

`priority(InferencePriority.INTERACTIVE)` inside `infer` orders queued work;
it does not preempt an active request or bypass quotas.

### Lifecycle, history and cleanup

Reuse the shared client. After five hidden idle minutes its binding may be
released; visibility or another API call reconnects lazily. Owned sessions
should still be closed when their task ends.

`HistoryPolicy.METADATA_ONLY` is the default. Explicitly choose
`HistoryPolicy.FULL_CONTENT` when conversation replay/persistence is wanted;
this stores prompt and response content in the encrypted SDK history database.
Do not log prompts, model output or embedding vectors.

## Diagnostics

```kotlin
val snapshot = mindlayer.getDiagnosticsTyped() // null on unsupported/refused calls
val status = mindlayer.getStatus()
```

These expose operational metadata. Debug transport checks do not establish
physical-device inference, accelerator coexistence or Play delivery. See the
[remaining qualification work](../project/ROADMAP.md).

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
adb shell am force-stop com.adsamcik.mindlayer.debug
```

With the flag set:

- `getCapabilities()` advertises `FEATURE_EMBEDDINGS`, `FEATURE_OCR_SESSION`,
  and `FEATURE_OCR_IMAGE_ONESHOT` (the LLM streaming features are always
  advertised).
- `vector(text)` returns a deterministic 768-d unit vector: the **same** text
  yields the **same** vector (cosine 1.0) and **different** text yields a
  near-orthogonal one (cosine well below 0.99) — so cache/idempotency and
  "distinct inputs → distinct embeddings" assertions hold. Matryoshka
  `outputDim` (768/512/256/128) is honoured.
- OCR returns `[mock]`-prefixed lines (honouring `maxLines` and the
  bounding-box capability); the single-image OCR→LLM extraction returns
  `[mock]` fields.
- **LLM chat / vision / audio** (`openSession` + `infer`)
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
  -n com.adsamcik.mindlayer.debug/com.adsamcik.mindlayer.service.security.DebugAutoAcceptReceiver \
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
adb shell am force-stop com.adsamcik.mindlayer.debug
```

---

## Publishing (for Mindlayer maintainers)

```bash
# Manually publish all SDK modules to GitHub Packages
export GITHUB_OWNER=your-username
export GITHUB_TOKEN=ghp_your_token
./gradlew :shared:publishReleasePublicationToGitHubPackagesRepository
./gradlew :sdk:publishReleasePublicationToGitHubPackagesRepository
./gradlew :sdk-camerax:publishReleasePublicationToGitHubPackagesRepository
./gradlew :sdk-camera-launcher:publishReleasePublicationToGitHubPackagesRepository

# Publish to local Maven (for testing)
./gradlew :shared:publishToMavenLocal :sdk:publishToMavenLocal \
    :sdk-camerax:publishToMavenLocal :sdk-camera-launcher:publishToMavenLocal
```

Release tags publish these same modules automatically. Set the root
`publishVersion`, merge the release PR, then push its matching `v<semver>` tag.

---

## Requirements

- **Mindlayer service app** must be installed on the device
- **User consent**: the user must approve your app once via the Mindlayer
  consent screen (any signing key works — no first-party registration)
- **Android 8.0+** (minSdk 26)
- **Model files** must be downloaded from Mindlayer's Models screen or staged for development

## OCR

```kotlin
val result = mindlayer.ocr {
    image(imageBytes, "image/jpeg")
    emitBoundingBoxes()
}.awaitResult()
```

`ocrSession { profile(OcrProfile.Receipt) }` opens a multi-frame
`OcrHandle.MultiFrame`. Push encoded or raw Y-plane frames, collect its events,
and `finalize()` to obtain the accumulated result; close the session afterwards.
`OcrImageAnalyzer` in `sdk-camerax` adapts CameraX frames to that handle.

The axis-aligned `boundingBox` is in source pixels; `boundingBoxQuad` retains
the normalized rotated quad. Optional LLM extraction uses
`extractWithLlm(schema)` and requires the generative model too. Check
`FEATURE_OCR_SESSION` / `FEATURE_OCR_IMAGE_ONESHOT` before the relevant path.

From `1.0.0-alpha.10`, `deadline(timeout)` accepts a positive duration or
`Duration.INFINITE`. A finite OCR deadline covers image loading, submission and
result mapping. Timeout or caller cancellation promptly stops SDK waiting;
an active synchronous Binder call completes independently and releases its
staged descriptors when it returns.

For a complete capture activity, use the `sdk-camera-launcher` module. Its
Activity Result contract returns result data to your caller; see
[capture contract](../../sdk-camera-launcher/src/main/kotlin/com/adsamcik/mindlayer/sdk/camera/launcher/OcrCaptureContract.kt).

## Embeddings

```kotlin
import com.adsamcik.mindlayer.sdk.EmbeddingHandle
import kotlin.time.Duration.Companion.seconds

val vector = mindlayer.vector("Search text", EmbeddingTask.RetrievalQuery)
val batch = mindlayer.embed { items(embeddingItems) }.awaitVectors()

// From 1.0.0-alpha.10: durable submission with an end-to-end time bound.
val pending = mindlayer.embed {
    items(embeddingItems)
    deferred()
    deadline(30.seconds)
} as EmbeddingHandle.Deferred
try {
    val results = pending.awaitReady()
    saveResults(results)
    pending.acknowledge()
} finally {
    pending.close()
}
```

Ordinary requests choose inline, SharedMemory or deferred fallback transport
automatically. `deferred()` explicitly returns a durable request handle after
submission. `awaitReady()` preserves item tags and available model/token/backend
metadata. Successful results survive a disconnect until acknowledgement, expiry
or quota eviction; revoking app access purges them.
Cancelling `awaitReady()` stops the wait without discarding the durable request;
call `close()` explicitly when cancelling the operation itself.

`deadline` accepts a positive duration or `Duration.INFINITE`. A finite bound
starts at `embed` invocation and covers submission plus waiting, including time
before `awaitReady()`. Finite bounds use deferred transport to support native
cancellation. `close()` performs best-effort cancellation and acknowledgement;
use `acknowledge()` when durable release must be awaited.

Check `FEATURE_EMBEDDINGS`; installed weights and tokenizer must pass integrity
verification. Supported output dimensions are 768, 512, 256 and 128. Keep query
and document task types paired for asymmetric retrieval. Treat vectors and
indexes as sensitive application data.
