# Dev model sideload

Mindlayer ships three on-device AI models as Play install-time **AI
Asset Packs** (`:gemma_model`, `:embeddinggemma_model`,
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

```powershell
# One-time per machine: where your model cache lives.
$env:MINDLAYER_MODEL_CACHE = 'D:\mindlayer-models'

# Push all three model groups to a connected debuggable device.
.\tools\dev-models\push-models.ps1 -All

# Build + install a code-only APK (the -Pmindlayer.bundle* flags
# that strip the three AI Asset Packs are added by a sibling PR;
# without them you still get a working build, just a fatter APK).
.\gradlew.bat :app:installDebug `
    "-Pmindlayer.bundleGemma=false" `
    "-Pmindlayer.bundleEmbeddings=false" `
    "-Pmindlayer.bundlePaddleocr=false"
```

That's the loop. The service finds the sideloaded files in
`/data/local/tmp/` at startup (debuggable builds only) and loads
them with `Origin.SIDELOAD`.

## How sideload works

Each model registry under
`app/src/main/kotlin/com/adsamcik/mindlayer/service/engine/` scans
`/data/local/tmp/` on debuggable builds:

| Registry | Filename pattern | Canonical filenames |
|---|---|---|
| `ModelRegistry` (chat) | `^[A-Za-z0-9_.-]+\.litertlm$` | `gemma-4-E2B-it.litertlm` |
| `EmbeddingModelRegistry` | `^embedding-[A-Za-z0-9_.-]+\.tflite$` + matching `.spm.model` | `embedding-gemma-300m-v1.tflite`, `embedding-gemma-300m-v1.spm.model` |
| `PaddleOcrModelRegistry` | exact 4-file set | `paddleocr-ppocrv5-mobile-{det,rec,cls}.tflite`, `paddleocr-ppocrv5-mobile-dict.txt` |

The sideload path is gated by `BuildConfig.DEBUG`. On release `user`
builds the registries refuse `Origin.SIDELOAD` outright; there is no
runtime knob to override this.

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
```

### macOS / Linux

```bash
export MINDLAYER_MODEL_CACHE=/data/mindlayer-models

./tools/dev-models/push-models.sh --all
./tools/dev-models/push-models.sh --gemma --embeddings --dry-run
./tools/dev-models/push-models.sh --paddleocr --device emulator-5554
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

### Sample dry-run output

```text
Mindlayer dev model sideload
  repo:   G:\Github\Mindlayer
  cache:  D:\mindlayer-models
  device: (auto)
  dryRun: True
  (skipping adb/device checks in dry-run mode)

=== Gemma chat ===
- gemma-4-E2B-it.litertlm
  sha: manifest SHA not populated (dev placeholder), skipping verification.
  [dry-run] adb push D:\mindlayer-models\gemma-4-E2B-it.litertlm /data/local/tmp/gemma-4-E2B-it.litertlm
  [dry-run] adb shell ls -l /data/local/tmp/gemma-4-E2B-it.litertlm

Done. All requested files processed cleanly (dry-run).
```

## SHA verification policy

Each module's `*_integrity.json` manifest carries pinned SHA-256
hashes:

| Module | Manifest |
|---|---|
| `gemma_model` | [`gemma_model/src/main/assets/model_integrity.json`](../gemma_model/src/main/assets/model_integrity.json) |
| `embeddinggemma_model` | [`embeddinggemma_model/src/main/assets/embedding_model_integrity.json`](../embeddinggemma_model/src/main/assets/embedding_model_integrity.json) |
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
- **No release-artifact tampering.** It writes only to
  `/data/local/tmp/`, never to APK contents, asset packs, or any
  signed artifact.
- **No production-gate bypass.** Release builds (`user` build type)
  refuse `/data/local/tmp` sideload via `BuildConfig.DEBUG`. You
  cannot use this on a shipping build, by design.

## Security note

`/data/local/tmp/` is writable by the `shell` user (the user `adb`
acts as). On a non-debuggable `user` build, even if you push files
there, the runtime registries will not load them — the `Origin.SIDELOAD`
branch is compiled-out at the predicate level. This script enforces
the same constraint client-side by refusing to push unless the
target device reports `ro.debuggable=1` or `ro.build.type` ∈
{`userdebug`, `eng`}.

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
