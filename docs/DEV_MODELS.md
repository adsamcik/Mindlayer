# Dev model sideload

Mindlayer ships three on-device AI models as Play install-time **AI
Asset Packs** (`:gemma_model`, `:gemma_embed_model`,
`:paddleocr_model`). In production that's the right choice — packs
are integrity-verified and lifecycle-managed by the Play installer.
In development it's painful: every `installDebug` shoves multi-GB of
unchanged model bytes over USB.

This document describes the **dev-only** sideload path. Build a tiny
code-only APK, push the heavy model files separately via `adb`, and
iterate normally.

> **Scope.** This is a developer-iteration aid. It does not ship to
> users, does not alter production integrity gates, and does not
> bypass any privacy invariant. See [Security note](#security-note).

## TL;DR

If you don't want to think about it, use the wrapper:

```powershell
# Windows PowerShell — full loop in one command
$env:MINDLAYER_MODEL_CACHE = 'D:\mindlayer-models'
.\scripts\dev-install.ps1
```

```bash
# macOS / Linux
export MINDLAYER_MODEL_CACHE=/data/mindlayer-models
./scripts/dev-install.sh
```

`scripts/dev-install` builds a code-only APK (no AI packs), runs
`adb install -r` (which preserves the app's `externalFilesDir` and
the models you've already pushed), and then runs `push-models` for
any model file that's missing or size-mismatched. Subsequent runs
skip the multi-GB push when the device is already current. See
`.github/copilot-instructions.md` § "On-device install + AI Pack
delivery" for the **forbidden moves** (`adb install -r app-debug.apk`
without a model push; `adb uninstall com.adsamcik.mindlayer.service.debug`
which wipes ~3 GB of pushed models) and why they break dev iteration.

If you want to drive the steps manually:

```powershell
# One-time per machine: where your model cache lives.
$env:MINDLAYER_MODEL_CACHE = 'D:\mindlayer-models'

# Install a code-only debug APK FIRST so the script can detect the
# service package and target its externalFilesDir.
.\gradlew.bat :app:installDebug `
    "-Pmindlayer.bundleGemma=false" `
    "-Pmindlayer.bundleEmbeddings=false" `
    "-Pmindlayer.bundlePaddleocr=false"

# THEN push all three model groups to the service's externalFilesDir.
.\tools\dev-models\push-models.ps1 -All
```

That's the loop. The service finds the sideloaded files in
`/sdcard/Android/data/com.adsamcik.mindlayer.service.debug/files/`
(or `…/com.adsamcik.mindlayer.service/files/` for a release-flavour
build) at startup, on debuggable builds only, and loads them with
`Origin.EXTERNAL_FILES`.

> **Order matters.** Install the APK first. On Android 12+ the
> script's legacy `/data/local/tmp/` fallback path is effectively
> dead (see [Why externalFilesDir, not /data/local/tmp/](#why-externalfilesdir-not-datalocaltmp)
> below), so pushing before install no longer works.

## How sideload works

Each model registry under
`app/src/main/kotlin/com/adsamcik/mindlayer/service/engine/` scans
the service's `getExternalFilesDir(null)` on debuggable builds. The
push script targets the same directory:

| Registry | Filename pattern | Canonical filenames |
|---|---|---|
| `ModelRegistry` (chat) | `^[A-Za-z0-9_.-]+\.litertlm$` | `gemma-4-E2B-it.litertlm` |
| `EmbeddingModelRegistry` | `^embedding-[A-Za-z0-9_.-]+\.tflite$` + matching `.spm.model` | `embedding-gemma-300m-v1.tflite`, `embedding-gemma-300m-v1.spm.model` |
| `PaddleOcrModelRegistry` | exact 4-file set | `paddleocr-ppocrv5-mobile-{det,rec,cls}.tflite`, `paddleocr-ppocrv5-mobile-dict.txt` |

The sideload path is gated by `BuildConfig.DEBUG`. On release `user`
builds the registries refuse `Origin.EXTERNAL_FILES`/`Origin.SIDELOAD`
outright; there is no runtime knob to override this.

### Why externalFilesDir, not `/data/local/tmp/`

Historically this tooling pushed to `/data/local/tmp/`. On Android 12
(API 31) and later, **apps cannot list `/data/local/tmp/`** — the
directory itself is no longer readable by app UIDs even when
individual files inside are world-readable. Concretely:

```text
$ adb shell run-as com.adsamcik.mindlayer.service.debug \
    ls -la /data/local/tmp/
ls: /data/local/tmp/: Permission denied
```

`File("/data/local/tmp").listFiles()` then returns `null`, every
registry's `bundleFromDir` / `scanDir` short-circuits, and you get
`Discovered 0 PaddleOCR bundle(s)` with no obvious explanation.

The service's `getExternalFilesDir(null)` (returned by `Context`,
typically `/sdcard/Android/data/<pkg>/files/`) **is** readable by
the app's own UID without any permission song-and-dance, and `adb
push` to that path also works without root. That's now the default
sideload target. The `/data/local/tmp/` branch is still scanned on
debuggable builds for older Android versions, and remains the
script's loud-warned fallback when the service isn't yet installed
on the connected device.

## Sources

The script never downloads anything (offline-first invariant — `:app`
and `:sdk` carry no `INTERNET` permission, and this tooling honours
the same rule). Acquire the raw files yourself and drop them into
your cache directory.

### Gemma 4 E2B (chat)

- Filename: `gemma-4-E2B-it.litertlm`
- Source: Google's LiteRT-LM release artifacts —
  <https://github.com/google-ai-edge/LiteRT-LM/releases>.
- Pick the `.litertlm` build matching the runtime version this repo
  is pinned to (see `gradle/libs.versions.toml`'s `litert-lm` entry).

### EmbeddingGemma 300M (embeddings)

- Filenames: `embedding-gemma-300m-v1.tflite` (weights) **and**
  `embedding-gemma-300m-v1.spm.model` (SentencePiece tokenizer).
- Source: <https://huggingface.co/google/embeddinggemma-300m>.
- Both files are required and must sit in the same directory — the
  registry rejects a half-set.

### PaddleOCR PP-OCRv5 mobile (OCR)

- Filenames: `paddleocr-ppocrv5-mobile-det.tflite`,
  `paddleocr-ppocrv5-mobile-rec.tflite`,
  `paddleocr-ppocrv5-mobile-cls.tflite`,
  `paddleocr-ppocrv5-mobile-dict.txt`.
- Build with the in-repo workflow
  [`.github/workflows/build-paddleocr-models.yml`](../.github/workflows/build-paddleocr-models.yml).
  It runs PaddleOCR → ONNX → 4× `.tflite` + dict and publishes them
  as workflow artifacts. Download the artifact, unzip into your cache.
- All four files must coexist; the registry requires the complete set.

## Recommended cache layout

The script does **not** recurse. Drop everything flat:

```
$env:MINDLAYER_MODEL_CACHE\
  gemma-4-E2B-it.litertlm
  embedding-gemma-300m-v1.tflite
  embedding-gemma-300m-v1.spm.model
  paddleocr-ppocrv5-mobile-det.tflite
  paddleocr-ppocrv5-mobile-rec.tflite
  paddleocr-ppocrv5-mobile-cls.tflite
  paddleocr-ppocrv5-mobile-dict.txt
```

## Usage

### Windows PowerShell

```powershell
# Push everything.
.\tools\dev-models\push-models.ps1 -All -Cache D:\mindlayer-models

# Just the chat model.
.\tools\dev-models\push-models.ps1 -Gemma

# Two models, specific device, dry run first.
.\tools\dev-models\push-models.ps1 -Gemma -Embeddings `
    -Device emulator-5554 -DryRun

# Force the legacy /data/local/tmp/ target (API <= 30 devices,
# or for testing the fallback branch).
.\tools\dev-models\push-models.ps1 -Paddleocr -PreferLegacyTmp
```

### macOS / Linux

```bash
export MINDLAYER_MODEL_CACHE=/data/mindlayer-models

./tools/dev-models/push-models.sh --all
./tools/dev-models/push-models.sh --gemma --embeddings --dry-run
./tools/dev-models/push-models.sh --paddleocr --device emulator-5554
./tools/dev-models/push-models.sh --paddleocr --prefer-legacy-tmp
```

### Flag reference

| PowerShell | bash | Effect |
|---|---|---|
| `-Gemma` | `--gemma` | push chat model |
| `-Embeddings` | `--embeddings` | push embedding weights + tokenizer |
| `-Paddleocr` | `--paddleocr` | push the 4 OCR files |
| `-All` | `--all` | all three groups |
| `-Cache <path>` | `--cache <path>` | override `$MINDLAYER_MODEL_CACHE` |
| `-Device <serial>` | `--device <serial>` | pick a target when multiple devices attach |
| `-DryRun` | `--dry-run` | print actions without pushing |
| `-PreferLegacyTmp` | `--prefer-legacy-tmp` | force legacy `/data/local/tmp/` target |

### How the push target is resolved

1. **`-PreferLegacyTmp` / `--prefer-legacy-tmp`** → `/data/local/tmp/`,
   no device query.
2. **`-DryRun` / `--dry-run`** without `-PreferLegacyTmp` → assumes the
   debug service variant is installed (most common dev case) and prints
   `/sdcard/Android/data/com.adsamcik.mindlayer.service.debug/files/…`.
3. **Real run**: `adb shell pm list packages` is queried for the debug
   variant first, then the release variant. First match wins; the push
   target is `/sdcard/Android/data/<pkg>/files/` and the script `mkdir
   -p`s it before pushing in case the app hasn't run yet.
4. **Neither variant installed**: loud warning, fall back to
   `/data/local/tmp/`. This branch is effectively dead on API 31+ but
   preserves the legacy UX for users who push before install on older
   devices.

### Sample dry-run output

```text
Mindlayer dev model sideload
  repo:   G:\Github\Mindlayer
  cache:  D:\mindlayer-models
  device: (auto)
  dryRun: True
  (skipping adb/device checks in dry-run mode)
  remote: /sdcard/Android/data/com.adsamcik.mindlayer.service.debug/files  (service pkg: com.adsamcik.mindlayer.service.debug)

=== Gemma chat ===
- gemma-4-E2B-it.litertlm
  sha: manifest SHA not populated (dev placeholder), skipping verification.
  [dry-run] adb push D:\mindlayer-models\gemma-4-E2B-it.litertlm /sdcard/Android/data/com.adsamcik.mindlayer.service.debug/files/gemma-4-E2B-it.litertlm
  [dry-run] adb shell ls -l /sdcard/Android/data/com.adsamcik.mindlayer.service.debug/files/gemma-4-E2B-it.litertlm

Done. All requested files processed cleanly (dry-run).
```

## SHA verification policy

Each module's `*_integrity.json` manifest carries pinned SHA-256
hashes:

| Module | Manifest |
|---|---|
| `gemma_model` | [`gemma_model/src/main/assets/model_integrity.json`](../gemma_model/src/main/assets/model_integrity.json) |
| `gemma_embed_model` | [`gemma_embed_model/src/main/assets/embedding_model_integrity.json`](../gemma_embed_model/src/main/assets/embedding_model_integrity.json) |
| `paddleocr_model` | [`paddleocr_model/src/main/assets/paddleocr_model_integrity.json`](../paddleocr_model/src/main/assets/paddleocr_model_integrity.json) |

The script reads them and applies an advisory check:

- **Manifest SHA == `0000…0000`** (the dev placeholder you'll see
  on `main`): print
  `manifest SHA not populated (dev placeholder), skipping verification`
  and proceed.
- **Manifest SHA != zero, matches local**: print `sha: OK` and proceed.
- **Manifest SHA != zero, mismatches local**: refuse to push that
  file and add a failure to the summary. Either you have a stale
  cache or the manifest was bumped — re-fetch or rebuild.

Release builds populate the real SHAs via `-Pmodel*Sha256` Gradle
properties; that's where the strict check happens for shipping
binaries. The sideload script's check is a courtesy, not a security
boundary.

## What the script does **not** do

- **No downloads.** It never reaches the network. Mindlayer's
  privacy invariants forbid `INTERNET` in `:app`/`:sdk` and this
  tooling follows the same spirit.
- **No telemetry / third-party services.** Pure local-file + `adb`.
- **No release-artifact tampering.** It writes only to the service's
  externalFilesDir (or to `/data/local/tmp/` in the legacy fallback),
  never to APK contents, asset packs, or any signed artifact.
- **No production-gate bypass.** Release builds (`user` build type)
  refuse sideload via `BuildConfig.DEBUG`. You cannot use this on a
  shipping build, by design.

## Security note

`/sdcard/Android/data/<pkg>/files/` (the service's `externalFilesDir`)
is OS-managed app-private storage on modern Android — other apps
cannot read it without `MANAGE_EXTERNAL_STORAGE`, and the system
deletes it on uninstall. From the registry's point of view this is
the same trust tier (`Origin.EXTERNAL_FILES`) it has always treated
as a developer sideload location; there is **no security delta**
compared with the legacy `/data/local/tmp/` path.

The actual production gate is `BuildConfig.DEBUG`. On a non-debuggable
`user` build, even if you push files into the service's externalFilesDir
(or `/data/local/tmp/`), the runtime registries will not load them —
the developer-tier `Origin` branches are compiled-out at the
predicate level. The script enforces the same constraint client-side
by refusing to push unless the target device reports `ro.debuggable=1`
or `ro.build.type` ∈ {`userdebug`, `eng`}.

If you need to test integrity-gated behaviour, build the AI Asset
Pack flavour normally; sideload is for code-iteration ergonomics,
not for security testing.

## Related

- AI Pack bundling toggles (sibling PR): selective
  `-Pmindlayer.bundleGemma=false` / `bundleEmbeddings` /
  `bundlePaddleocr` flags on `app/build.gradle.kts`. Combine those
  with this script for the full "small APK + fast model push" loop.
- Engine code: `app/src/main/kotlin/com/adsamcik/mindlayer/service/engine/`.
- Privacy invariants:
  [`.github/instructions/privacy-offline.instructions.md`](../.github/instructions/privacy-offline.instructions.md).

