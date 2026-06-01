# `:gemma_model` — Gemma 4 E2B chat AI Pack

Play Asset Delivery install-time pack carrying the Gemma 4 E2B `.litertlm`
model file (~2.4 GB). The service-side LLM engine loads this at first init.

## What ships here

- `src/main/assets/gemma-4-E2B-it.litertlm` — Gemma 4 weights (**not committed**)
- `src/main/assets/model_integrity.json` — SHA-256 manifest produced by the
  `generateModelIntegrityManifest` task; the service uses it to verify the
  weights file at load time.

The `.litertlm` model file is **deliberately not committed** (see
`/.gitignore`). It is delivered to devices via Play Asset Delivery
(install-time pack) or sideloaded with `tools/dev-models/push-models.ps1`.

## Why the empty `assets/.gitkeep`

The `assets/` directory must exist at build time even when no model files are
present locally. Without it, AGP's `:app:assetPackReleasePreBundleTask` fails
with *"path does not exist"* and breaks the release AAB build in CI.

The `.gitkeep` file content is **intentionally identical across all three AI
Pack modules** (`gemma_model`, `gemma_embed_model`, `paddleocr_model`).
bundletool's `EntryClashValidator` runs across the assembled bundle and
rejects entries at the same relative path that differ in byte content. Keep
this file in sync with the other packs' `.gitkeep` or `:app:packageDebugBundle`
will fail.

## Do not commit

Do not commit any `.litertlm` files in `assets/`.
