# Model SHA-256 reference

> Canonical hashes for every binary artifact shipped via Play Asset
> Delivery. Maintained by hand. Update whenever the underlying model
> file changes (new release, re-quantisation, re-conversion).

This file is the single source of truth that:

- **Source-pinned models** (Gemma, EmbeddingGemma) reuse as the default
  manifest value when no `-PmodelSha256` / `-PembeddingModelSha256` /
  `-PembeddingTokenizerSha256` is passed. See
  `gemma_model/build.gradle.kts` and
  `embeddinggemma_model/build.gradle.kts`'s
  `generate*ModelIntegrityManifest` tasks.
- **Build-time-injected models** (PaddleOCR PP-OCRv5 mobile) use as the
  reference values plugged into the
  `PADDLEOCR_{DET,REC,CLS,DICT}_SHA256` GitHub repository variables
  for CI builds. The committed
  `paddleocr_model/src/main/assets/paddleocr_model_integrity.json`
  intentionally carries all-zero placeholders for security review
  reasons — see
  `PaddleOcrAssetPackTest.committed integrity manifest carries all-zero placeholders`.

## Currently shipped artifacts

| Artifact | Role | SHA-256 |
|---|---|---|
| `gemma_model/src/main/assets/gemma-4-E2B-it.litertlm` | Gemma chat (LiteRT-LM 0.12.0) | `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` |
| `embeddinggemma_model/src/main/assets/embedding-gemma-300m-v1.tflite` | EmbeddingGemma weights | `d39b0bb3346bfb4f884f3bbce3196b261895aeece7e7ac02ffb44ed6e0f39381` |
| `embeddinggemma_model/src/main/assets/embedding-gemma-300m-v1.spm.model` | EmbeddingGemma SentencePiece tokenizer | `d6daa52d93d7aad10e8388bd526c4e501d914b47177398d1d9621f1fe48438c7` |
| `paddleocr-ppocrv5-mobile-det.tflite` *(from workflow run `26704498612`)* | PaddleOCR detection | `03a5b639ef85b30fe0b227fd06c99e23caa1772ac85081350cdb63a4fb5f252b` |
| `paddleocr-ppocrv5-mobile-rec.tflite` *(from workflow run `26704498612`)* | PaddleOCR recognition | `374ab36289ab5a2b798bb41d8b85641e28e2cb1e65298da65e4e0c2498194d2b` |
| `paddleocr-ppocrv5-mobile-cls.tflite` *(from workflow run `26704498612`)* | PaddleOCR orientation classifier | `76a292681fb774f5f89f6d50e1312757f6857cfaefab6953ef657040e8364093` |
| `paddleocr-ppocrv5-mobile-dict.txt` *(from workflow run `26704498612`)* | PaddleOCR character dictionary | `e5f8ca61ba03d3a247d06b013119982fa6de2bd48a846018b67bca57ffc56de1` |

## Verifying your local copy

```powershell
# PowerShell
$shas = [ordered]@{
  "gemma_model/src/main/assets/gemma-4-E2B-it.litertlm" =
    "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
  "embeddinggemma_model/src/main/assets/embedding-gemma-300m-v1.tflite" =
    "d39b0bb3346bfb4f884f3bbce3196b261895aeece7e7ac02ffb44ed6e0f39381"
  "embeddinggemma_model/src/main/assets/embedding-gemma-300m-v1.spm.model" =
    "d6daa52d93d7aad10e8388bd526c4e501d914b47177398d1d9621f1fe48438c7"
}
foreach ($p in $shas.Keys) {
  if (-not (Test-Path $p)) { Write-Warning "missing: $p"; continue }
  $local = (Get-FileHash $p -Algorithm SHA256).Hash.ToLower()
  $expected = $shas[$p]
  if ($local -eq $expected) { Write-Host "OK    $p" -ForegroundColor Green }
  else { Write-Host "FAIL  $p`n  local    = $local`n  expected = $expected" -ForegroundColor Red }
}
```

```bash
# bash
declare -A SHAS=(
  ["gemma_model/src/main/assets/gemma-4-E2B-it.litertlm"]="181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
  ["embeddinggemma_model/src/main/assets/embedding-gemma-300m-v1.tflite"]="d39b0bb3346bfb4f884f3bbce3196b261895aeece7e7ac02ffb44ed6e0f39381"
  ["embeddinggemma_model/src/main/assets/embedding-gemma-300m-v1.spm.model"]="d6daa52d93d7aad10e8388bd526c4e501d914b47177398d1d9621f1fe48438c7"
)
for p in "${!SHAS[@]}"; do
  [[ -f "$p" ]] || { echo "missing: $p"; continue; }
  local_=$(sha256sum "$p" | cut -d' ' -f1)
  exp="${SHAS[$p]}"
  if [[ "$local_" == "$exp" ]]; then
    echo "OK    $p"
  else
    echo "FAIL  $p"
    echo "  local    = $local_"
    echo "  expected = $exp"
  fi
done
```

## GitHub repository variable matrix

For CI (`./gradlew :app:assembleRelease`) and `publish.yml` to inject
the real SHAs into the integrity manifests, the maintainer must set
these repository variables (Settings → Secrets and variables →
Actions → Variables tab):

| Variable | Value |
|---|---|
| `MODEL_SHA256` | `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` |
| `EMBEDDING_MODEL_SHA256` | `d39b0bb3346bfb4f884f3bbce3196b261895aeece7e7ac02ffb44ed6e0f39381` |
| `EMBEDDING_TOKENIZER_SHA256` | `d6daa52d93d7aad10e8388bd526c4e501d914b47177398d1d9621f1fe48438c7` |
| `PADDLEOCR_DET_SHA256` | `03a5b639ef85b30fe0b227fd06c99e23caa1772ac85081350cdb63a4fb5f252b` |
| `PADDLEOCR_REC_SHA256` | `374ab36289ab5a2b798bb41d8b85641e28e2cb1e65298da65e4e0c2498194d2b` |
| `PADDLEOCR_CLS_SHA256` | `76a292681fb774f5f89f6d50e1312757f6857cfaefab6953ef657040e8364093` |
| `PADDLEOCR_DICT_SHA256` | `e5f8ca61ba03d3a247d06b013119982fa6de2bd48a846018b67bca57ffc56de1` |

## Dev-local override

When iterating locally and you don't want to set repository variables,
pass the SHAs as Gradle properties (see `RELEASE.md` for the full
PowerShell snippet):

```bash
./gradlew :app:assembleRelease \
  -PmodelSha256="181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c" \
  -PembeddingModelSha256="d39b0bb3346bfb4f884f3bbce3196b261895aeece7e7ac02ffb44ed6e0f39381" \
  -PembeddingTokenizerSha256="d6daa52d93d7aad10e8388bd526c4e501d914b47177398d1d9621f1fe48438c7" \
  -PpaddleOcrDetSha256="03a5b639ef85b30fe0b227fd06c99e23caa1772ac85081350cdb63a4fb5f252b" \
  -PpaddleOcrRecSha256="374ab36289ab5a2b798bb41d8b85641e28e2cb1e65298da65e4e0c2498194d2b" \
  -PpaddleOcrClsSha256="76a292681fb774f5f89f6d50e1312757f6857cfaefab6953ef657040e8364093" \
  -PpaddleOcrDictSha256="e5f8ca61ba03d3a247d06b013119982fa6de2bd48a846018b67bca57ffc56de1"
```

## Refreshing this table

Whenever a model is replaced (new Gemma release, re-quantised
EmbeddingGemma, re-converted PaddleOCR):

1. Compute the new SHA with `Get-FileHash <path> -Algorithm SHA256`
   (PowerShell) or `sha256sum <path>` (bash). The value MUST be 64
   lowercase hex chars.
2. Update the table above + the per-module `*_integrity.json`
   (Gemma + EmbedGemma only — PaddleOCR's manifest stays all-zeros
   per `PaddleOcrAssetPackTest`).
3. Update the corresponding GitHub repository variable so CI uses
   the new value.
4. Update the Play Asset Delivery bundle with the new artifact.

The integrity-manifest tests in `:app:testDebugUnitTest` validate
that the committed values match a 64-hex pattern (no length / case
drift) but do NOT pin the SHA value itself — so step 2 above can
land in a single commit without test churn.
