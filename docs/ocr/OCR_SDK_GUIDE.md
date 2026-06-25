# Mindlayer OCR SDK guide

> Single source of truth for Mindlayer SDK consumers using OCR. For
> the AIDL surface, wire format, and engine-side details see
> [`OCR_API.md`](OCR_API.md). For the SDK integration overview see
> [`SDK_INTEGRATION.md`](../sdk/SDK_INTEGRATION.md).
>
> All examples assume `compileSdk = 36`, Mindlayer SDK `0.10` or
> later, and that the host app has already been approved via the
> Mindlayer dashboard's allowlist.

## TL;DR — pick one of three entry points

| Want… | Use… | Module |
|---|---|---|
| "Scan this one already-captured image" | `Mindlayer.ocrAsync(bytes, mimeType, options)` | `:sdk` |
| "Stream live camera frames, fuse them" | `Mindlayer.ocrRealtime(profile) { … }` + `OcrImageAnalyzer` | `:sdk` + `:sdk-camerax` |
| "Just show a camera UI and give me the result" | `registerForActivityResult(OcrCaptureContract())` | `:sdk-camera-launcher` |

The three sit at different abstraction levels. Pick the highest one
that meets your needs — every step down adds code surface you have to
own.

## Decision rule

```
Do you already have one final image (gallery, sharesheet, screenshot)?
  ├─ Yes → ocrAsync(bytes, …)
  └─ No, you have a live camera and the user can re-aim:
       ├─ Do you want to own the camera UI (custom preview, overlays, etc.)?
       │    ├─ Yes → ocrRealtime() + OcrImageAnalyzer from :sdk-camerax
       │    └─ No  → OcrCaptureContract from :sdk-camera-launcher
       └─ Done.
```

The same `LiteRtPaddleOcrBackend` powers all three; the choice is
purely about **where the capture pipeline lives**, not about OCR
quality.

## Capability checks — do this first

Both `ocrAsync` and `ocrRealtime` are gated by a service capability
flag that flips together via the production-readiness gate. Always
check before calling.

```kotlin
val mindlayer = Mindlayer.connect(context)
mindlayer.awaitConnected()

val caps = mindlayer.getCapabilities()
val canAsync    = caps.supports(ServiceCapabilities.FEATURE_OCR_IMAGE_ONESHOT)
val canRealtime = caps.supports(ServiceCapabilities.FEATURE_OCR_SESSION)

if (!canAsync && !canRealtime) {
    // The connected service does not advertise OCR yet. Don't call —
    // either flag throws MindlayerException(code = FEATURE_NOT_SUPPORTED, 5017).
    return
}
```

The `:sdk-camera-launcher` makes the same check internally; if the
service doesn't advertise the relevant flag the activity finishes
with `OcrCaptureResult.Error(code = FEATURE_NOT_SUPPORTED, …)` —
consumers don't need to gate themselves.

## Async — single image

`Mindlayer.ocrAsync(bytes, mimeType, options)` is one binder round
trip that returns an `OcrImageResult`. Pass JPEG / PNG / WEBP bytes;
the SDK picks SharedMemory or a PFD pipe automatically.

### Raw recognition only

```kotlin
val result = mindlayer.ocrAsync(
    bytes = jpegBytes,
    mimeType = "image/jpeg",
)
result.lines.forEach { line ->
    Log.i("ocr", "${line.text} (conf=${line.confidence})")
}
```

### With LLM extraction (Gemma)

```kotlin
val structured = mindlayer.ocrAsync(
    bytes = jpegBytes,
    mimeType = "image/jpeg",
    options = OcrImageOptions(
        emitBoundingBoxes = true,
        runLlmExtraction = true,
        extractionSchemaJson = """
            {
              "type": "object",
              "properties": {
                "total":    { "type": "string" },
                "merchant": { "type": "string" },
                "date":     { "type": "string" }
              }
            }
        """.trimIndent(),
    ),
)
structured.extractionFields.forEach { (name, value) ->
    Log.i("ocr", "$name = $value")
}
structured.extractionJson?.let { Log.i("ocr", "json: $it") }
```

Adding extraction costs ~2-5s of Gemma decode on top of the ~1-2s
of raw OCR. Skip it for "just give me the text" flows.

## Realtime — multi-frame streaming

`Mindlayer.ocrRealtime(profile) { … }` opens an `OcrSession`. Push
frames via `session.pushFrame(meta, yPlane, w, h)`; collect
recognition + field events via `session.events`. Call
`session.finalize()` then `session.close()` (or use `use { }`).

### Bring-your-own preview (custom CameraX integration)

```kotlin
val session = mindlayer.ocrRealtime(OcrProfile.Receipt) {
    languageHints = listOf("en", "de-DE")
    maxFrames = 30
    frameRateLimitFps = 5
}

val analyzer = OcrImageAnalyzer(session)  // from :sdk-camerax

val analysis = ImageAnalysis.Builder()
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
    .build()
    .also { it.setAnalyzer(executor, analyzer) }

cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)

launch {
    session.events.collect { event ->
        when (event) {
            is OcrEvent.FieldUpdate    -> ui.show(event.fieldName, event.topValue, event.confidence)
            is OcrEvent.FieldLocked    -> ui.lock(event.fieldName, event.topValue)
            is OcrEvent.ResultFinalized -> ui.commit(event.fullJson)
            is OcrEvent.Error          -> handle(event.code, event.message)
            else                        -> Unit
        }
    }
}

// When the user taps "Done":
session.finalize()
```

`OcrImageAnalyzer` runs a client-side presort that drops blurry /
duplicate frames before they cross the binder, on top of the
service-side presort. The combination keeps the engine busy on
genuinely-useful frames only.

### Turn-key — let the SDK own the camera

Add the launcher module:

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.adsamcik.mindlayer:sdk:0.10.0")
    implementation("com.adsamcik.mindlayer:sdk-camera-launcher:0.10.0")
}
```

Register the contract and launch it:

```kotlin
class ScanFragment : Fragment() {
    private val ocrLauncher = registerForActivityResult(OcrCaptureContract()) { result ->
        when (result) {
            is OcrCaptureResult.Async -> {
                // Single-image: result.result is an OcrImageResult.
                viewModel.onReceiptScanned(result.result)
            }
            is OcrCaptureResult.Realtime -> {
                // Multi-frame: result.finalJson is the fused output.
                viewModel.onLiveScanFinished(result.finalJson, result.framesPushed)
            }
            OcrCaptureResult.Cancelled -> {
                // User backed out — no-op or show a toast.
            }
            is OcrCaptureResult.Error -> {
                viewModel.onScanError(result.code, result.message)
            }
        }
    }

    private fun startReceiptScan() {
        ocrLauncher.launch(
            OcrCaptureRequest(
                mode = OcrCaptureMode.Async,
                profileId = OcrProfileId.Receipt,
                runLlmExtraction = true,
                emitBoundingBoxes = true,
            ),
        )
    }

    private fun startLiveScan() {
        ocrLauncher.launch(
            OcrCaptureRequest(
                mode = OcrCaptureMode.Realtime,
                profileId = OcrProfileId.GeneralDocument,
                maxFrames = 60,
            ),
        )
    }
}
```

The launcher does all of the following for you:

- Asks for the `CAMERA` runtime permission. You do not declare it in
  the host manifest — `:sdk-camera-launcher`'s manifest declares it
  and the manifest merger picks it up automatically.
- Calls `Mindlayer.connect(...)` and waits for the connection.
- Binds the CameraX preview + analysis pipeline.
- Drives either `ocrAsync` (single-shutter) or `ocrRealtime`
  (live-stream) under the hood.
- Returns the structured result through the Activity Result API.
- Tears down CameraX, the session, and the Mindlayer connection on
  finish.

If the user has not approved your app via the Mindlayer dashboard yet,
the underlying `Mindlayer.connect` resolves to
`ConnectionState.AUTHORIZATION_PENDING` and the launcher surfaces
`OcrCaptureResult.Error(code = PERMISSION_DENIED, …)`. The launcher
does **not** show its own authorisation UI — that lives in the
Mindlayer dashboard.

#### Customising the launcher

`OcrCaptureRequest` exposes only the knobs that make sense to vary
per launch. Anything outside this surface is intentionally not
customisable in v1 — file an issue if you need it.

| Field | Mode | Meaning |
|---|---|---|
| `mode` | both | `Async` (one shutter) vs `Realtime` (streaming + Done button). |
| `profileId` | both | Picks the [`OcrProfile`](../../sdk/src/main/kotlin/com/adsamcik/mindlayer/sdk/OcrProfile.kt) singleton. Async uses it for the default schema + title only. |
| `extractionSchemaJson` | both | Override the profile's default extraction schema. |
| `runLlmExtraction` | Async | Whether to run the Gemma extraction pass on the captured frame. Realtime always runs schema-driven fusion. |
| `emitBoundingBoxes` | Async | Each recognised line carries an 8-float quad in normalised coordinates. |
| `maxFrames` | Realtime | Soft cap on frames pushed before auto-finalising (0 = service default). |
| `languageHints` | both | BCP-47 hints forwarded to both surfaces; advisory. |
| `titleOverride` | both | Replaces the default in-app top-bar title. |

## Error handling

All three entry points throw / report a uniform error code from
[`MindlayerErrorCode`](../../shared/src/main/kotlin/com/adsamcik/mindlayer/shared/MindlayerErrorCode.kt).
For the launcher, errors arrive as `OcrCaptureResult.Error(code, message)`.

| Code | Constant | Typical cause | What to do |
|---|---|---|---|
| 5017 | `FEATURE_NOT_SUPPORTED` | Production-readiness gate is off, or service is older than v0.9 / v0.8. | Check capability flag first; show "not available" UI. |
| 5018 | `SERVICE_UNAVAILABLE` | Engine not ready (still loading model, or load failed). | Retry once after a short delay; if still failing, surface to user. |
| 4001 | `THERMAL_CRITICAL` | Device is throttling. | Back off and try later; engine-bound retries will just keep failing. |
| 4003 | `LOW_MEMORY` | Engine refused because of memory headroom. | Free retained bitmaps / drop the request; do not retry immediately. |
| 3001 | `INVALID_REQUEST` | Bytes empty, MIME type unsupported, or options malformed. | Programmer error — fix the call site. |
| 3007 | `OCR_SCHEMA_INVALID` | `extractionSchemaJson` failed validation. | Programmer error. |
| 2004 | `OCR_IDLE_TIMEOUT` | Realtime session received no frames for too long. | Tell the user to point the camera at the target. |
| 2005 | `OCR_MAX_DURATION` | Realtime session exceeded its wall-clock cap. | Auto-finalise and use whatever fused output is available. |
| -1001 | `Error.CAMERA_PERMISSION_DENIED` (launcher-local) | User denied the `CAMERA` permission. | Show a rationale; consumer may relaunch. |
| -1002 | `Error.CAMERA_INIT_FAILED` (launcher-local) | CameraX bind failed (no compatible camera, hardware fault). | Fallback to a non-camera capture path (gallery picker). |
| -1003 | `Error.SERVICE_CONNECT_TIMEOUT` (launcher-local) | `Mindlayer.connect` did not reach `CONNECTED` inside 10s. | The service may not be installed; surface install / install-pack UI. |

For direct calls (`ocrAsync` / `ocrRealtime`), the exception is
`MindlayerException`:

```kotlin
try {
    mindlayer.ocrAsync(bytes, "image/jpeg")
} catch (e: MindlayerException) {
    when (e.code) {
        MindlayerErrorCode.LOW_MEMORY    -> retryAfterFreeingBitmaps()
        MindlayerErrorCode.FEATURE_NOT_SUPPORTED -> showNotAvailable()
        else -> log.e("ocr", "Unexpected ${e.codeName}: ${e.message}")
    }
}
```

## Multi-page realtime (v0.9)

When a single `ocrRealtime` session captures **different content over time** — e.g. the user pans the camera from page 1 of a receipt to page 2 — the service can detect the page boundary and emit a fresh **per-page** event pair while still emitting the single session-end `OcrEvent.ResultFinalized` for backward compatibility.

The feature is **opt-in** via a `pageBoundaries { … }` block on the session builder. Without the block, the session behaves exactly as v0.8 (single finalize, no per-page events):

```kotlin
val session = mindlayer.ocrRealtime(OcrProfile.RECEIPT_FAST) {
    sourceHint = "camera"
    pageBoundaries {
        // enabled = true is implied by the block existing.
        // Every other knob has a sensible default; tune only as needed.
        jaccardThreshold = 0.3f
        spatialThreshold = 0.5f
        gyroThreshold    = 2.0f
        stabilityFrames  = 3      // N consecutive different frames → boundary
        llmExtractPerPage = false // run LLM extractor on the whole session, not per page
        llmExtractFinal   = true
    }
}

session.events.collect { event ->
    when (event) {
        is OcrEvent.PageStarted -> {
            // pageIndex (0-based), triggerFrameId (0 for the first page,
            // otherwise the frameId of the frame that closed the prior streak)
        }
        is OcrEvent.PageFinalized -> {
            // pageIndex, lineCount, framesContributed, lines: List<String>,
            // fullJson: String? (LLM extraction JSON when llmExtractPerPage=true)
        }
        is OcrEvent.ResultFinalized -> {
            // Fires once after all pages — same backward-compat shape
            // your v0.8 code already handles.
        }
        else -> { /* per-frame events you already handle */ }
    }
}
```

### Forwarding gyro from CameraX

For the gyro signal to contribute to boundary detection, each frame must carry `{"imu":{"gyro_max_rad_per_s": …}}` on `OcrFrameMeta.extraJson`. The `:sdk-camerax` `OcrImageAnalyzer` does this automatically when constructed with a `SensorManager`:

```kotlin
val analyzer = OcrImageAnalyzer(
    session = session,
    sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager,
)
// later, on activity stop / lifecycle teardown:
analyzer.close() // unregisters the gyro listener
```

The analyzer registers a `Sensor.TYPE_GYROSCOPE` listener at `SensorManager.SENSOR_DELAY_GAME`, tracks the peak `sqrt(x²+y²+z²)` magnitude observed between successive `analyze()` calls, and resets the peak after each frame. Without a `SensorManager`, the constructor is binary-identical to before — `extraJson` is left untouched and the boundary detector falls back to text + spatial signals only (still useful, just less robust against fast pans on glossy paper).

> **Backward compatibility.** Old service binaries that don't emit the new wire events are transparent to the SDK — the reader simply never sees them. Old callers using `ocrRealtime` without a `pageBoundaries { … }` block see byte-identical v0.8 behaviour. See [`OCR_API.md`](OCR_API.md#multi-page-realtime-v09--preview) for the full wire-level contract, JSON envelope, and event-sequence example.

## Migration — `ocrSession` / `ocrImage` → `ocrRealtime` / `ocrAsync`

The v0.10 SDK rename is a pure name change. The underlying AIDL
methods are unchanged. The old names remain as `@Deprecated` aliases
that delegate to the new ones:

```kotlin
// Old (deprecated, still works):
mindlayer.ocrSession(OcrProfile.Receipt) { … }
mindlayer.ocrImage(bytes, "image/jpeg")

// New (preferred):
mindlayer.ocrRealtime(OcrProfile.Receipt) { … }
mindlayer.ocrAsync(bytes, "image/jpeg")
```

`ReplaceWith` is wired on every deprecated overload, so Android
Studio's "Replace with…" quick fix produces the right call. There is
no behavioural change — the aliases delegate directly.

## Full sample — receipt scanner

A complete `Activity` that uses the launcher to scan a receipt
and shows the extracted total:

```kotlin
class ReceiptScannerActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(OcrCaptureContract()) { result ->
        when (result) {
            is OcrCaptureResult.Async -> {
                val total = result.result.extractionFields
                    .firstOrNull { it.name == "total" }?.value
                showResult(total ?: "(no total found)")
            }
            is OcrCaptureResult.Error -> showError("${result.code}: ${result.message}")
            OcrCaptureResult.Cancelled -> finish()
            // Realtime not used in this flow:
            is OcrCaptureResult.Realtime -> Unit
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launcher.launch(
            OcrCaptureRequest(
                mode = OcrCaptureMode.Async,
                profileId = OcrProfileId.Receipt,
                runLlmExtraction = true,
                titleOverride = "Scan receipt",
            ),
        )
    }
}
```

That's the entire client. No CameraX setup, no permission boilerplate,
no Mindlayer connection management. The launcher owns all of it; you
own only the result handling.

## Privacy guarantees

The launcher respects the same product invariants as the rest of
Mindlayer (see
[`.github/instructions/privacy-offline.instructions.md`](../../.github/instructions/privacy-offline.instructions.md)):

- No `INTERNET` permission is declared in `:sdk-camera-launcher`.
- Captured frames live in RAM only — never `filesDir`, `cacheDir`, or
  external storage. The launcher discards the bitmap after dispatch.
- The `OcrCaptureResult.Realtime.finalJson` and
  `OcrCaptureResult.Async.result` carry recognised text back to your
  app; what you do with them is your responsibility. The launcher
  never persists either.
- `toString()` on the parcelables redacts string lengths only — safe
  to put in logs.
- The runtime `CAMERA` permission is asked for inside the launcher
  activity itself, so the host app's permission posture is unchanged
  when the consumer hasn't actually invoked the contract.

## See also

- [`OCR_API.md`](OCR_API.md) — authoritative AIDL / wire / engine
  reference.
- [`SDK_INTEGRATION.md`](../sdk/SDK_INTEGRATION.md) — SDK setup,
  signing, dashboard approval.
- [`../sdk/src/main/kotlin/com/adsamcik/mindlayer/sdk/Mindlayer.kt`](../../sdk/src/main/kotlin/com/adsamcik/mindlayer/sdk/Mindlayer.kt) —
  KDoc on every method, with deprecated aliases marked.
- [`../sdk-camera-launcher/src/main/kotlin/com/adsamcik/mindlayer/sdk/camera/launcher/`](../../sdk-camera-launcher/src/main/kotlin/com/adsamcik/mindlayer/sdk/camera/launcher) —
  launcher source.
