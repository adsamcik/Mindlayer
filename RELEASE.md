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
./gradlew.bat clean :app:bundleRelease
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
> absent, the AAB will still build but the AI-Pack will ship empty and the
> installed app won't find the model at runtime.

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
| `:app:bundleRelease` (unsigned) | ✅ on `main` only, for sanity | ✅                                        |
| `:app:bundleRelease` (signed)   | ❌ never                      | ✅ only when `keystore.properties` is set |

CI's unsigned `app-release.aab` artifact on `main` is **not** uploadable to
Play — it's just proof that R8 + resource shrinking still succeeds end-to-end
before you cut a real release locally.

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
