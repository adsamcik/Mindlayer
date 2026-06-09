<!-- context-init:version:3.1.0 -->
<!-- context-init:managed -->

# Mindlayer Development Guide

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 17 (build) — but tests run on 21 in CI, see [Java 21 gotcha](#%EF%B8%8F-the-java-21-test-runtime-gotcha) | `compileOptions { sourceCompatibility/targetCompatibility = VERSION_17 }` and `kotlin { jvmTarget = JVM_17 }` |
| Android SDK | `compileSdk 37`, `minSdk 26`, `targetSdk 36` | `compileSdk 37` required by Compose BOM 2026.05.01 alpha (1.12.0-alpha03 / material3 1.5.0-alpha20) |
| Gradle | wrapper-managed (`gradlew`) | Plugin: AGP 9.2.1 |
| Kotlin | 2.3.21 (KSP 2.3.8) | `kotlin.code.style=official` |
| Compose | BOM 2026.04.01, Material3 1.5.0-alpha18 | |
| LiteRT-LM | 0.12.0 (`com.google.ai.edge.litertlm:litertlm-android`) + LiteRT 2.1.5 | |
| Model files | Gemma `.litertlm`, EmbeddingGemma `.tflite` + tokenizer, PaddleOCR PP-OCRv5 assets | **NOT in git.** Delivered via install-time Play AI Packs (`:gemma_model`, `:gemma_embed_model`, `:paddleocr_model`) or staged manually for dev/release. |
| Emulator/device | API 26+, GPU recommended (Vulkan/OpenCL); Robolectric covers `:test` | Native libs `libvndksupport.so`, `libOpenCL.so` declared `required="false"`. |

## Common Commands

```bash
# Build everything (debug)
./gradlew assembleDebug

# Build only the service APK
./gradlew :app:assembleDebug

# Unit tests across all modules
./gradlew :app:testDebugUnitTest :sdk:testDebugUnitTest :shared:testDebugUnitTest

# A single test class
./gradlew :app:testDebugUnitTest --tests "*AllowlistStoreTest*"

# Authorization stack tests
./gradlew :app:testDebugUnitTest --tests "*Allowlist*" --tests "*CallerVerifier*" --tests "*ServiceBinder*"

# Lint (matches CI)
./gradlew lintDebug

# Instrumented tests (needs an emulator/device on `adb`)
./gradlew :app:connectedDebugAndroidTest :sdk:connectedDebugAndroidTest

# Release AAB (signed if keystore.properties present, else unsigned)
./gradlew :app:bundleRelease
```

## Running on a device / emulator

The model is ~2.4 GB and is **not** committed. For development push it manually:

```bash
adb push gemma-4-E2B-it.litertlm /data/local/tmp/
adb shell "run-as com.adsamcik.mindlayer.service.debug cp /data/local/tmp/gemma-4-E2B-it.litertlm files/"
adb shell am start -n com.adsamcik.mindlayer.service.debug/com.adsamcik.mindlayer.service.ui.MainActivity
```

(Note the `.debug` `applicationIdSuffix` — debug builds end in `…service.debug`.)

For Play Store builds the model files are delivered via the `:gemma_model`,
`:gemma_embed_model`, and `:paddleocr_model` install-time AI packs — see
`RELEASE.md`.

## Environment / properties

| Property | Where | Purpose | Default |
|---|---|---|---|
| `GITHUB_OWNER` | `~/.gradle/gradle.properties` or env | GitHub Packages publish/consume identity | `OWNER` (sentinel — fails publishing) |
| `GITHUB_TOKEN` | env or gradle property | GitHub Packages auth (needs `read:packages` to consume, `write:packages` to publish) | empty |
| `GITHUB_REPO` | gradle property | Publish target repo | `Mindlayer` |
| `publishVersion` | `-PpublishVersion=X.Y.Z` or CI from `v*` tag | SDK/shared artifact version | `0.1.0` |
| `keystore.properties` | repo root (gitignored) | Local-only release signing — see `RELEASE.md` | absent → unsigned release |

## ⚠️ The Java 21 test-runtime gotcha

CI runs Gradle on **Java 21 (Temurin)** while compiling app bytecode to JVM 17:

```yaml
# .github/workflows/ci.yml
- name: Set up JDK 21 (compile target 17)
  uses: actions/setup-java@v5
  with:
    java-version: '21'        # Gradle/test runtime; app bytecode still targets 17
    distribution: 'temurin'
```

Reasons (do not "fix" this):

1. The Kotlin/Android **build** is compiled to JVM 17 bytecode (see `compileOptions` and `kotlin.compilerOptions.jvmTarget`).
2. **Tests inherit the Gradle JVM** — Robolectric on Temurin **17** would auto-provision Temurin **21.0.10**, which has a deterministic `SIGSEGV` in `G1SATBMarkQueueSet::filter` under Robolectric's classloading pattern. Running Gradle on 21 from the start avoids that auto-provision.
3. There's a comment in `app/build.gradle.kts` warning future readers about this.

If you change CI Java setup or local JDK, run the unit-test suite end-to-end before pushing — the SIGSEGV reproduces reliably on the wrong combination.

## CI overview (`.github/workflows/ci.yml`)

| Job | Trigger | What it does |
|---|---|---|
| `build-and-test` | every push/PR to `main` | `assembleDebug` + `testDebugUnitTest`, uploads HTML + JUnit XML reports. |
| `instrumented-tests` | every push/PR | Spins up `reactivecircus/android-emulator-runner` AVD api-33, runs `connectedDebugAndroidTest` for `:app` and `:sdk`. |
| `lint` | every push/PR | `./gradlew lintDebug`, uploads HTML reports. Lint fatal set: `NewApi`, `MissingTranslation`. `InlinedApi` is intentionally **not** fatal (always-safe behind `SDK_INT` checks). |
| `validate-release-shas` | every push/PR | Reports which of the seven release SHA repository variables are set; malformed set values fail fast. |
| `build-bundle` | push to `main` only, after `build-and-test` + `validate-release-shas` | Skips cleanly unless all seven SHA vars and the Gemma payload are present; then runs `:app:bundleRelease` and uploads the AAB (signed when CI signing secrets are configured). |

There is also `.github/workflows/publish.yml` for SDK/shared artifact publishing on `v*` tags.

Dependabot is enabled for Gradle and GitHub Actions; PRs land regularly.

## Releasing

Production builds can be signed locally or by CI when signing secrets are configured — see `RELEASE.md` for the keystore and model-SHA flow. Quick summary:

1. Drop `keystore.properties` (with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`) at the repo root.
2. Compute the Gemma, PaddleOCR, and EmbeddingGemma SHA-256 values and pass the seven `-P*Sha256` properties documented in `RELEASE.md`.
3. `./gradlew :app:bundleRelease` produces `app/build/outputs/bundle/release/app-release.aab`.
4. Upload to Play Console with the `:gemma_model`, `:gemma_embed_model`, and `:paddleocr_model` install-time asset packs.
5. Tag the commit `vX.Y.Z` to trigger SDK publishing and optional GitHub Release AAB attachment.

R8/proguard rules: `app/proguard-rules.pro`, `sdk/consumer-rules.pro`, `shared/consumer-rules.pro`.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `CONSENT_REQUIRED` / `MindlayerErrorCode.CONSENT_REQUIRED` (`6005`) | First real AIDL call from an app without an approved `(pkg, signingCertSha256)` consent row. | Launch the consent-Intent flow: call `requestConsentChallenge()` directly or use `MindlayerConsent.requestConsent(context)`, then have the user approve the Mindlayer prompt. There is no dashboard **Approve** button and no `seedIfEmpty`. |
| `BadParcelableException` / method-not-found at runtime when calling AIDL | A stale build, or an old SDK consumer compiled against a different `IMindlayerService` revision. | Rebuild against the current `:sdk` AIDL (the single source of truth in `sdk/src/main/aidl/`). `:app` has no AIDL of its own to drift. |
| Robolectric tests SIGSEGV (`G1SATBMarkQueueSet::filter`) | Wrong JDK on the Gradle test runtime. | Use Java 21 for Gradle (see Java 21 gotcha above). Don't auto-provision Temurin 17 toolchain. |
| `IllegalStateException: SQLCipher not loaded` | First boot after install, native lib missing. | Code already guards with `try { System.loadLibrary("sqlcipher") } catch (_: UnsatisfiedLinkError) { }`. Ensure `libs.sqlcipher.android` is on the classpath. |
| Engine init never returns | Backend-fallback chain is exhausted (NPU → GPU → CPU). | Check `EngineManager.lastGpuFailureReason`. Inspect `adb logcat -s "Mindlayer.EngineManager:D"`. |
| Model not found | `gemma-4-E2B-it.litertlm` missing in `filesDir/`, asset pack, or external/internal app dirs. | Run the `adb push` recipe above, or install the AI pack (Play Store). `ModelRegistry.discoverModels` lists every probed location. |
| `bindService` fails / returns null binding | Service package, action, or installation issue. The v0.10 service has no custom bind permission. | Verify Mindlayer is installed/enabled and the client uses the SDK's service intent. If binding succeeds but calls fail, handle `CONSENT_REQUIRED` via the consent-Intent flow. |
| `Rate limit exceeded` from an approved caller | Client UID is exceeding the shared 60 RPM budget. | Throttle the caller, or increase `RateLimiter` defaults if the product policy needs widening. |

## Useful logcat filters

```bash
# All Mindlayer logs (debug+)
adb logcat -s "Mindlayer.*:D"

# Just one component
adb logcat -s "Mindlayer.InferenceOrchestrator:D"

# Capture full structured trace for one request
adb logcat -s "Mindlayer.*:D" | grep "req=req-12345"
```

