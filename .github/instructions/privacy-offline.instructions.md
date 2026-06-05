---
applyTo: "app/**/*.kt,sdk/**/*.kt,shared/**/*.kt,app/src/**/AndroidManifest.xml,sdk/src/**/AndroidManifest.xml,app/build.gradle.kts,sdk/build.gradle.kts,shared/build.gradle.kts"
description: "Privacy, offline-first, and security invariants — applies to all production code, manifests, and build files"
---

<!-- context-init:managed -->

## Product invariant: privacy-first, fully offline, secure by default

Mindlayer is **fully on-device, network-free, and first-party-trust-gated**. These properties are product-level guarantees — every feature, OCR included, inherits them. Weakening any of the rules below is a release blocker, not a tradeoff.

## No network access — ever

| ✅ Do | ❌ Don't |
|---|---|
| Keep `:app` and `:sdk` `AndroidManifest.xml` free of `android.permission.INTERNET` | Add `<uses-permission android:name="android.permission.INTERNET"/>` to either manifest |
| Use bundled or AAB asset-module model artifacts (downloaded once at install, verified by hash) | Add Google Play Services dependencies (`com.google.android.gms:*`) that imply network calls |
| Treat GTINs and other identifiers as opaque values to emit in evidence packages | Call third-party catalog APIs (Open Food Facts, GS1 Registry, etc.) from `:app` or `:sdk` |
| Verify model integrity at first launch (SHA-256 against pinned hash) | Lazy-download models at runtime from arbitrary URLs |

A CI lint check fails the build if `android.permission.INTERNET` appears in any `:app` or `:sdk` manifest. If a feature truly requires network (it shouldn't), it must move to a separate optional artifact and ship behind an explicit capability flag with user-visible consent — not into the core service or SDK.

## No third-party telemetry / data egress

| ✅ Do | ❌ Don't |
|---|---|
| Use Apache-2.0 / MIT / BSD-licensed engines (PaddleOCR, LiteRT, LiteRT-LM, ZXing, ncnn) | Adopt ML Kit (closed-source ML Kit ToS allows Google to receive usage metrics) |
| Use ZXing for barcode scanning | Use ML Kit Barcode Scanning |
| Use LiteRT (already in repo at `libs.litert`) as the single on-device inference runtime | Add a second inference runtime that competes with LiteRT for CPU threads, GPU context, or memory arena (e.g. ONNX Runtime Android) |
| Confirm new dependencies are Apache-2.0 / MIT / BSD AND fully offline before adding | Add Firebase, Crashlytics, Google Analytics, Sentry, or any analytics SDK |
| Route logs through `MindlayerLog` + `LogRepository` (SQLCipher, local only) | Add any code path that POSTs telemetry to a remote endpoint |

License posture rejects: ML Kit ToS, Surya (GPL-3 + OpenRAIL-M non-commercial), MNNKit (separate SDK agreement), Google Document AI / Azure / AWS Textract (cloud-only). Apache-2.0 / MIT / BSD-3 are the only acceptable licenses for new on-device runtime dependencies.

**Single-runtime rule**: Mindlayer ships exactly one on-device inference runtime — **LiteRT** (`com.google.ai.edge.litert:litert`) — used by Gemma (via LiteRT-LM), EmbeddingGemma, and the OCR feature. Build-time model conversion may pass through intermediate file formats like ONNX, but no second inference runtime is loaded into the Mindlayer process. ONNX Runtime, Paddle Lite, ncnn, MediaPipe-Tasks-as-runtime are excluded from on-device use — they remain license-acceptable but architecturally rejected.

## Dependency soak rule

| ✅ Do | ❌ Don't |
|---|---|
| Pin community-project dependency versions that are **at least 7 days old** at the time of first commit | Adopt a community-project version released < 7 days ago, even if it's "the latest" |
| Prefer one minor back if a community-project latest is borderline (e.g. < 14 days) | Bump a major or minor version without re-running the full test suite + replay harness |
| Document the released-on date in the PR description for any new community-project dep | Skip the soak window for "urgent fixes" — backport the fix to the older pinned version instead |
| Treat Google-published libraries (`com.google.*`, `androidx.*`, `com.android.*`) AND JetBrains/Kotlin libraries (`org.jetbrains.kotlin*`, `org.jetbrains.kotlinx.*`) as **exempt** from the soak rule | Apply the soak rule to first-party Google/Kotlin/JetBrains releases — they have professional release engineering + automated QA across Android versions |

**Exemption rationale**: Google and JetBrains/Kotlin libraries (LiteRT, LiteRT-LM, AndroidX, Jetpack Compose, kotlinx.coroutines, kotlinx.serialization, Kotlin language, etc.) ship through release pipelines with multi-stage CI, alpha/beta/rc rollouts, and professional QA. Community projects with single-maintainer risk, ad-hoc release cadence, or thin CI need the ≥7-day soak window.

A CI step warns (does not fail) if a PR introduces any *community-project* dep version < 7 days old; reviewer must explicitly accept the risk in the PR thread. Discontinued artifacts (e.g. `onnxruntime-mobile` after 1.18.0) are tracked in `docs/DEPRECATED_DEPENDENCIES.md`.

Subject to the rule (examples): PaddleOCR, paddle2onnx, onnx2tf, ZXing, Robolectric, MockK, Turbine, SQLCipher, OpenCV-Android, Apache Commons Text, Halide, ncnn.

Exempt from the rule (examples): LiteRT (`com.google.ai.edge.litert:*`), LiteRT-LM (`com.google.ai.edge.litertlm:*`), Jetpack Compose, AndroidX, Kotlin Standard Library, kotlinx-coroutines, kotlinx-serialization, Android Gradle Plugin.

## Data retention rules (extend, never relax)

The existing log policy ("persist metadata only; never prompt text or model output") applies to every new feature. Specifics for media, recognized text, and structured output:

| Data | Allowed storage | Retention |
|---|---|---|
| Camera frames, audio buffers | Staged **encrypted at rest** (AES-256-GCM, process-ephemeral in-memory key) under `cacheDir/media_staging`; plaintext is only materialized on demand for the native LiteRT-LM decoder (which reads by path) and removed with the request; staging dir purged on process startup | Released on session end / binder-death / ack timeout; leftover ciphertext is undecryptable after process death (key died with the process) and purged on next startup |
| Recognized OCR text + bounding boxes | RAM only | Released on session end; never `filesDir` / `cacheDir` / external |
| Structured JSON output (OCR or chat) | Returned over the pipe | Not persisted by service; caller may store in their own SQLCipher-backed DB |
| Per-frame quality scores | RAM only | Released on session end |
| Logs | SQLCipher-backed `LogRepository` | Existing retention; **metadata only** — never `text`, `value`, `evidence`, `fields`, schema content, or raw model output |
| FGS notification text | Visible to user | Generic strings only — never recognized text, GTIN, field values, or schema content |
| Telemetry events | Local (`LogRepository`) | tokenCount, frameId, sessionId, timing, `safeLabel(throwable)` |

`Throwable.safeLabel()` is mandatory for surfacing native errors — full stack traces can embed prompt text. This is reiterated in the main `copilot-instructions.md` hard-rules table.

## Security tightenings on every AIDL entry point

Every external AIDL method runs the 4-stage gate (`authorizeCall`: identity → allowlist → rate-limit → ownership) — see `.github/instructions/security.instructions.md` for the canonical pattern. New methods inherit these additional invariants:

| ✅ Do | ❌ Don't |
|---|---|
| Validate every new parcelable field in `IpcInputValidator` before reaching the engine | Trust parcelable input — caller is untrusted at the AIDL boundary |
| Bound all caller-supplied JSON (schema, options, regions) to a documented max length | Accept unbounded JSON / let parsers exhaust memory |
| Order binder-death teardown: cancel engine → close pipe FD → release SHM → free FGS refcount → remove DeathRecipient | Skip any step — orphaned native memory or FD leaks compound across sessions |
| Pin model file SHA-256; refuse to load on mismatch | Load model files without integrity verification |
| Use the existing `SharedMemoryPool` for media | Place >~1 MB into Binder parcels (1 MB limit) |
| Use `safeLabel()` for all exception surfacing | Log full native stack traces (can embed prompt text) |

Cross-UID enumeration defense: every "not found" / "not owned" error MUST return the same `SESSION_NOT_FOUND_OR_NOT_OWNED` (2001) code — never leak which session IDs exist for which UIDs.

## Offline operation guarantees

- **Cold start**: all required model files ship in the APK (bundled) or as an AAB on-demand asset module downloaded once at install. After first launch, **zero network access** is required for any production functionality.
- **No first-use download delays** on user-facing features; if a feature can't work offline, it doesn't ship.
- **No external lookups** (catalogs, knowledge bases, registries) from the service or SDK by default. If a caller wants enrichment, they implement it in their own app with their own permissions and user disclosure.
- **Permission posture**: neither `:app` nor `:sdk` declare `INTERNET`, `ACCESS_NETWORK_STATE`, or any Play Services-required permissions. The service requires no network.

## Threat model boundaries (don't blur these)

- **Untrusted**: every external AIDL caller until `authorizeCall` returns a `CallerIdentity`. Default-deny posture is intentional.
- **Untrusted**: every byte arriving over AIDL parcelables — validate in `IpcInputValidator` first.
- **Untrusted**: native model output text — never log it, never store it without explicit caller opt-in via the existing SQLCipher-backed history.
- **Trusted (after authorization)**: the calling app's UID for the duration of one method invocation; ownership is re-verified on the next call.
- **Trusted (always)**: self-UID (dashboard) per existing convention.
- **Out of scope**: physical device compromise, OS root, Android keystore bypass — Mindlayer is not a TEE.

## When adding a new feature

Before merging any feature PR, verify:

1. No new permissions added to `:app` or `:sdk` `AndroidManifest.xml` (especially `INTERNET`).
2. No new dependencies that imply network access, telemetry, or cloud fallback.
3. New dependency licenses are Apache-2.0 / MIT / BSD-3 (or already-allowlisted SQLCipher).
4. No new code path that persists prompt text, OCR output, or any model-generated content outside the existing SQLCipher-backed history.
5. `MindlayerLog` calls in the new code reference `requestId` / `sessionId` / `tokenCount` / timing only — never content.
6. `IpcInputValidator` covers every new parcelable's fields with explicit bounds.
7. Every new AIDL entry point calls `authorizeCall()` first; ownership-scoped methods also call `requireOwnership()`.
8. `Throwable.safeLabel()` used wherever native errors are surfaced.
9. Binder-death teardown handles every new resource the feature owns.
10. Model files (if any) are integrity-checked at load time.

The PR template asks for explicit confirmation of items 1, 2, and 6 because they are the most common regressions.
