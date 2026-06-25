# Releasing Mindlayer to Google Play

This document is the end-to-end flow for cutting a release build, signing it
locally or in CI, and uploading it to the Play Console. Local signing remains
the default for Play uploads; CI can build and attach a signed AAB when the
release model payloads, SHA variables, and Android signing secrets are present.

---

## 1. One-time setup

### 1.1 Generate a release keystore

Do this **once**, store the resulting `.jks` somewhere safe (encrypted backup,
password manager, hardware token), and **never commit it**.

```powershell
# Windows / PowerShell — run from the repo root.
keytool -genkey -v `
    -keystore ..\mindlayer-release.jks `
    -alias mindlayer `
    -keyalg RSA `
    -keysize 4096 `
    -validity 36500
```

```bash
# macOS / Linux
keytool -genkey -v \
    -keystore ../mindlayer-release.jks \
    -alias mindlayer \
    -keyalg RSA -keysize 4096 -validity 36500
```

Placing the `.jks` **outside the repo** avoids ever accidentally committing
it. A `.gitignore` rule still catches `*.jks` / `*.keystore` inside the repo
as belt-and-braces.

### 1.2 Wire `keystore.properties`

Copy the template and fill in the real values:

```powershell
Copy-Item keystore.properties.example keystore.properties
# Edit keystore.properties and set storeFile / passwords / alias.
```

`keystore.properties` is `.gitignore`d. The format:

```properties
storeFile=../mindlayer-release.jks
storePassword=<store-password>
keyAlias=mindlayer
keyPassword=<key-password>
```

`storeFile` is resolved relative to the **repo root**.

### 1.3 Verify the wiring

```powershell
./gradlew.bat :app:signingReport
```

Under `Variant: release`, confirm the SHA-1/SHA-256 fingerprints match the
key you just generated. If you see a warning that the `release` variant uses
the **debug** signing config, your `keystore.properties` is missing or its
`storeFile` path doesn't resolve — fix and re-run.

---

## 2. Cutting a release

### 2.1 Bump the version

Edit `app/build.gradle.kts`:

```kotlin
versionCode = 4       // monotonic; Play rejects re-uploads with the same code
versionName = "0.4.0" // semver; shown in the Play listing + Settings > App info
```

Update `CHANGELOG.md` — move `Unreleased` entries under a new `[0.4.0] — YYYY-MM-DD`
section.

Commit both changes with a message like:

```
release: 0.4.0
```

### 2.2 Build the signed AAB

Models for a local release live in a flat **cache directory** outside the repo —
the same one `tools/dev-models/push-models.*` uses. Point the build at it once
with `MINDLAYER_MODEL_CACHE` (or `-Pmindlayer.modelCache=<dir>`) and the build
copies the binaries into each AI-pack module and derives their pinned SHA-256
automatically. No manual copying, no hand-typed `-P*Sha256` flags.

```powershell
# One-time per machine: where your vetted release model binaries live (flat
# layout — see docs/models/DEV_MODELS.md § "Recommended cache layout").
$env:MINDLAYER_MODEL_CACHE = 'G:\mindlayer-models'

./gradlew.bat clean :app:bundleRelease
```

The cache must contain these seven files (the orientation classifier is the
only one the OCR engine can run without, but the release guard still expects
it):

```
gemma-4-E2B-it.litertlm
embedding-gemma-300m-v1.tflite
embedding-gemma-300m-v1.spm.model
paddleocr-ppocrv5-mobile-det.tflite
paddleocr-ppocrv5-mobile-rec.tflite
paddleocr-ppocrv5-mobile-cls.tflite
paddleocr-ppocrv5-mobile-dict.txt
```

Output:

```
app/build/outputs/bundle/release/app-release.aab
```

This is the file you upload to Play. It is:

* **Signed** with your release key (because `keystore.properties` is present).
* **Minified and shrunk** via R8 using `app/proguard-rules.pro`.
* **Packaged with install-time AI packs** declared in `app/build.gradle.kts`:
  `:gemma_model`, `:gemma_embed_model`, and `:paddleocr_model`, whose bytes were
  copied from the cache by each module's `provisionReleaseModelAssets` task.

> ⚠️ The Gemma `.litertlm`, PaddleOCR `.tflite`/dictionary files, and
> EmbeddingGemma artifacts are **not** checked into git. A release build fails
> fast with a clear message if `MINDLAYER_MODEL_CACHE` is unset or a required
> file is missing from both the cache and the AI-pack `src/main/assets/` dir.
> Debug builds keep advisory model-hash behavior and the sideload path for local
> development.
>
> If you swap a model's bytes **without** changing its filename, run the release
> with `--no-configuration-cache` so the new digest is recomputed (the digest is
> captured at configuration time).

### 2.2.1 Pre-release model artefact storage and SHA variables

Model binaries are stored outside git because they are large release artefacts,
not source files. Before a **local** release build, retrieve the vetted artefacts
from the project's private model artefact store and drop them flat into your
`MINDLAYER_MODEL_CACHE` directory; the build derives every SHA-256 from those
exact bytes, so there is nothing to compute by hand.

For **CI** release jobs the cache is not present, so the seven `-P*Sha256`
properties (sourced from the repository variables below) remain the way SHAs are
supplied — they always take precedence over the cache fallback. For PaddleOCR
refreshes, the manual `build-paddleocr-models.yml` workflow emits an
`expected_shas.txt` artifact whose values should match the hashes the build
derives. Set these repository variables for CI release jobs:

* `MODEL_SHA256`
* `PADDLEOCR_DET_SHA256`
* `PADDLEOCR_REC_SHA256`
* `PADDLEOCR_CLS_SHA256`
* `PADDLEOCR_DICT_SHA256`
* `EMBEDDING_MODEL_SHA256`
* `EMBEDDING_TOKENIZER_SHA256`

`.github/workflows/ci.yml` runs `validate-release-shas` first; if any variable
is missing it reports which ones are unset and skips `build-bundle` cleanly.
`publish.yml` uses the same seven variables for the tag-driven `release-aab`
job. Configure `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` as repository secrets only when
CI should attach a signed AAB; otherwise CI may produce unsigned smoke-test
artifacts that must not be uploaded to Play.


### 2.3 (Optional) Build a signed universal APK for side-loading

Play Store only accepts AAB, but you may want an APK for direct install:

```powershell
./gradlew.bat :app:assembleRelease
```

Output:

```
app/build/outputs/apk/release/app-release.apk
```

This APK is signed **only if** `keystore.properties` resolves. Without a
keystore, you'll get `app-release-unsigned.apk` instead (this is also what
CI produces — useful as a smoke-test artifact, not shippable).

### 2.4 Sanity checks before uploading

```powershell
# Confirm the AAB is signed with YOUR key.
bundletool dump manifest --bundle app\build\outputs\bundle\release\app-release.aab

# Inspect the baseline module's ApplicationId + versionCode + permissions.
# Compare SHA-1/SHA-256 of the signing cert to the Play Console "App signing" page
# — they MUST match, or Play will reject the upload.
```

---

## 3. Uploading to the Play Console

1. **Play Console → Mindlayer → Production → Create new release**
2. Upload `app-release.aab`
3. Paste release notes from the `[x.y.z]` section of `CHANGELOG.md`
4. Declare **Data safety** (this app collects user prompts in local logs — those
   never leave the device; disclose accordingly)
5. Declare **special use** foreground service — subtype string is in
   `res/values/strings.xml` → `fgs_special_use_description`
6. Review and roll out (staged rollout is recommended for the first production
   release)

---

## 4. Ongoing signing hygiene

* **Never** commit `keystore.properties` or the `.jks` file. Both are
  `.gitignore`d, but double-check before every `git push`.
* If you enable Play App Signing ("upload key" model), the `.jks` above
  becomes your **upload key**. Play holds the real signing key. Losing the
  upload key is recoverable via Play's upload-key reset; losing the
  pre-app-signing legacy release key is **not** recoverable.
* Keep at least two offline backups of the keystore file. Store the
  passwords separately from the `.jks`.
* Rotate passwords (not the key itself) if you suspect compromise.

---

## 5. CI vs. local builds

| Task                            | CI                           | Local                                    |
|---------------------------------|------------------------------|------------------------------------------|
| `:app:assembleDebug`            | ✅ every push                 | ✅                                        |
| `:app:testDebugUnitTest`        | ✅ every push                 | ✅                                        |
| `:app:connectedDebugAndroidTest`| ✅ every push (API 33 AVD)    | ✅ with connected device                  |
| `lintDebug`                     | ✅ every push                 | ✅                                        |
| `:app:bundleRelease` (unsigned) | ✅ on `main` with all model SHA repo variables when CI signing secrets are absent | ✅ with all `-P*Sha256=...` properties |
| `:app:bundleRelease` (signed)   | ✅ on `main` when all model SHA repo variables and CI signing secrets are configured | ✅ only when `keystore.properties` is set |
| **`v*` tag → attached AAB on GitHub Release** | ✅ on every release tag — `publish.yml`'s `release-aab` job builds + attaches `app-release.aab` to the GitHub Release alongside the SDK pointer (signed when CI secrets are configured, unsigned otherwise). Gated on the same `gemma-4-E2B-it.litertlm` presence check as the `main`-branch flow. | — |
| **`v*` tag → attached debug APK on GitHub Release** | ✅ on every release tag — `publish.yml`'s `release-apk` job builds a code-only **debug** APK (AI packs excluded via `-Pmindlayer.bundle*=false`) and attaches `mindlayer-service-debug-<tag>.apk` (~78 MB) to the Release. Debug-signed by the in-repo keystore; needs no model artefacts or signing secrets. Testers sideload it and push models with `tools/dev-models/push-models.*`. A *release* code-only APK is deliberately **not** built: its integrity manifests ship inside the excluded AI-pack modules, so a non-debuggable build rejects sideloaded models. A full APK with models (~2.5 GB) exceeds GitHub's 2 GiB asset cap. | — |

CI can sign the release AAB when `ANDROID_KEYSTORE_BASE64`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`
are configured as repository secrets, in addition to the `MODEL_SHA256`,
`PADDLEOCR_*_SHA256`, and `EMBEDDING_*_SHA256` repository variables that all
release builds require. Without the signing secrets, CI still builds an
unsigned `app-release.aab` on `main`; that artifact is **not** uploadable to
Play and only proves that R8 + resource shrinking still succeeds end-to-end
with the same model-hash manifests a real release must provide.

On `v*` release tags, `publish.yml` mirrors that build and **also attaches
the resulting AAB to the GitHub Release** so SDK consumers can grab the
matching service-side bundle from the same release page that hosts the SDK
Maven coordinate. The Phase 3 `p3-signed-release` track wired this end of
the pipeline; before it, only the SDK AAR was visible on the release page
and the service-side AAB lived only in the `actions/upload-artifact` ZIP.

### 5.1 Dependency-integrity policy (F-067)

Mindlayer relies on **Maven Central's repository signing** (TUF-style) plus
`https`-only resolvers (`google()`, `mavenCentral()` via the locked
`dependencyResolutionManagement` block in `settings.gradle.kts`) for binary
integrity, **not** on a local `gradle/verification-metadata.xml`.

Why: the Android Gradle Plugin's auto-resolved `aapt2` distribution is
platform-specific (one artifact per `windows`/`linux`/`darwin`), and a
locally-generated `verification-metadata.xml` only captures the developer's
own platform. Either every contributor regenerates the file on every
gradle-version bump, or CI fails for half the team. The maintenance burden
turned out to materially exceed the marginal security benefit for a
pinned-dep, signature-verified Maven setup; this is the same trade-off the
Phase 2 rubber-duck review documented when first considering F-067.

If your threat model requires local hash verification, run:

```bash
./gradlew --write-verification-metadata sha256 help
```

and add a per-platform component for `com.android.tools.build:aapt2`
(plus any platform-conditional native artifacts). This is opt-in
per-fork; we accept the friction so the default contributor path stays
unblocked.

---

## 6. Troubleshooting

* **"Your upload key does not match the app signing key"** — the SHA-1 in the
  Play Console's *App signing* page must match what `:app:signingReport`
  prints for the `release` variant. You're either using a different `.jks`
  than Play has on file, or `keystore.properties` is resolving to the wrong
  `storeFile`.
* **"Version code X has already been used"** — bump `versionCode` in
  `app/build.gradle.kts` (monotonic integer; Play never accepts a
  re-upload of the same code, even after deletion).
* **R8 strips something legitimate and the app crashes at runtime** — add
  a narrow `-keep` rule to `app/proguard-rules.pro` (for app-owned code)
  or `sdk/consumer-rules.pro` / `shared/consumer-rules.pro` (for types
  downstream apps rely on). Never disable R8 globally as a workaround.
* **Lint fails on release with `MissingTranslation`** — if you add a new
  user-facing string without translations, add `translatable="false"` on
  the `<string>` element *or* provide translations. `MissingTranslation`
  is a fatal release-check (see `lint { fatal += … }` in
  `app/build.gradle.kts`).

---

## 7. Hardware-touching PR checklist

Emulator CI catches API-level regressions but cannot exercise the NPU/GPU
backends, real thermal throttling, true low-RAM device tiers, or the
foreground-service lifecycle that Android imposes on physical hardware. PRs
that touch any of the surfaces below **must** be verified on at least one
real device by the author before merge. The **npu-soc-list-expansion
(F-080)** work explicitly depends on this checklist as its real-device
validation gate.

### Trigger paths

A PR is "hardware-touching" if it modifies any of:

* `app/src/main/kotlin/com/adsamcik/mindlayer/service/engine/**` — engine,
  thermal, memory, NPU SoC list, session/inference orchestration
* `app/src/main/kotlin/com/adsamcik/mindlayer/service/MindlayerMlService.kt`
  — foreground-service lifecycle (`startForeground` / `stopForeground`,
  `specialUse` promotion/demotion, binder-death linkage)
* `app/src/main/AndroidManifest.xml` — `foregroundServiceType`,
  service `<intent-filter>`, signature-level permissions, process name
* `gemma_model/**` — the Play AI Pack module that delivers the
  `.litertlm` weights

### Verification steps

Copy this block into the PR description and tick every box that applies.

```markdown
- [ ] Built and installed on at least one real device per ABI we ship
      (`arm64-v8a` mandatory; `armeabi-v7a` only if the F-079
      `litertlm-aar-abi-inspection` allow-list confirms the AAR exposes it).
- [ ] Tested device-tier extremes: at least one **≤ 6 GB** device and one
      **≥ 12 GB** device, so `MemoryBudget` tier selection is exercised at
      both ends.
- [ ] Captured `adb logcat -s "Mindlayer.*:D"` for a 3-message inference
      run and attached the trimmed log to the PR description (no prompt or
      output text — log metadata only, per the no-PII invariant).
- [ ] Confirmed the **dashboard** renders the correct thermal band and
      memory tier *during* inference (not just at idle).
- [ ] **EngineManager change?** Exercised every backend (NPU / GPU / CPU)
      that the test devices support, OR documented in the PR why a backend
      could not be exercised on the available hardware.
- [ ] **FGS lifecycle change?** Backgrounded the app mid-inference (Home
      key, then a 30 s wait) and confirmed the token stream still
      completes without `ForegroundServiceDidNotStartInTimeException` or
      premature termination.
- [ ] **NPU SoC list change?** (any edit to `QUALCOMM_NPU_SOCS`,
      `MEDIATEK_NPU_SOCS`, `GOOGLE_TENSOR_NPU_SOCS`, or
      `SAMSUNG_NPU_SOCS`): tested on a **Pixel 6 or newer** *and* a
      **recent Samsung flagship** (Galaxy S22 or newer), since the
      Tensor and Snapdragon NPU paths diverge.
```

### What this list deliberately leaves out

* API-level matrix coverage (26 / 33 / 34) — handled by emulator CI.
* Unit-test regressions — handled by `:app:testDebugUnitTest` and
  `:sdk:testDebugUnitTest` on every push.
* Static lint and AIDL drift — handled by `lintDebug` and the AIDL
  byte-identity check.

Once F-078 (extended emulator matrix) and F-079
(litertlm-aar-abi-inspection) land, items in the checklist that those
gates fully cover should be removed. The list above is the **upper bound**
— too long means people skip it.

