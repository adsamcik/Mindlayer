# Releasing Mindlayer to Google Play

This document is the end-to-end flow for cutting a release build, signing it
locally, and uploading it to the Play Console. **All signing happens on your
local machine** — CI only produces an unsigned debug bundle and runs tests.

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

```powershell
$modelPath = Get-ChildItem gemma_model -Recurse -Filter *.litertlm | Select-Object -First 1
if ($null -eq $modelPath) { throw "Place the release .litertlm model under gemma_model before building." }
$modelSha256 = (Get-FileHash $modelPath.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
./gradlew.bat clean :app:bundleRelease -PmodelSha256=$modelSha256
```

Output:

```
app/build/outputs/bundle/release/app-release.aab
```

This is the file you upload to Play. It is:

* **Signed** with your release key (because `keystore.properties` is present).
* **Minified and shrunk** via R8 using `app/proguard-rules.pro`.
* **Packaged with the Gemma AI-Pack** (install-time delivery, declared in
  `app/build.gradle.kts` via `assetPacks += listOf(":gemma_model")`).

> ⚠️ The Gemma `.litertlm` model is **not** checked into git. For a Play
> Store release, place the real model binary at the path expected by the
> `:gemma_model` module before running `:app:bundleRelease`. If the model is
> absent, the release build should fail. Release builds also require
> `-PmodelSha256=<64 lowercase hex SHA-256>` for the exact `.litertlm` file
> being bundled; debug builds keep advisory model-hash behavior for local
> development.

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
| `:app:bundleRelease` (unsigned) | ✅ on `main` only with `MODEL_SHA256` repo variable | ✅ with `-PmodelSha256=...` |
| `:app:bundleRelease` (signed)   | ❌ never                      | ✅ only when `keystore.properties` is set |

CI's unsigned `app-release.aab` artifact on `main` is **not** uploadable to
Play — it's just proof that R8 + resource shrinking still succeeds end-to-end
with the same model hash manifest that a real release must provide.

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
