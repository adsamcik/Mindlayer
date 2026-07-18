# `:gemma_model` — Gemma 4 E2B fragment 1

Standard Play Asset Delivery on-demand pack carrying the first deterministic
fragment of Gemma 4 E2B. `:gemma_model_part_2` carries the second fragment; the
app verifies both and reconstructs the complete `.litertlm` in private storage.

## What ships here

- `src/main/assets/gemma-4-E2B-it.litertlm.part1` — generated first fragment (**not committed**)
- `src/main/assets/gemma_part_1_integrity.json` — fragment size and SHA-256
- `src/main/assets/model_integrity.json` — SHA-256 manifest produced by the
  root fragment task for release validation.

The complete `.litertlm` is **never packaged in this module**. Release builds
source it from the gitignored model cache, split it, and fail if the complete
container remains under either pack's `assets/` directory.

## Why the empty `assets/.gitkeep`

The `assets/` directory must exist at build time even when no model files are
present locally. Without it, AGP's `:app:assetPackReleasePreBundleTask` fails
with *"path does not exist"* and breaks the release AAB build in CI.

The `.gitkeep` file content is **intentionally identical across all four asset
pack modules**.
bundletool's `EntryClashValidator` runs across the assembled bundle and
rejects entries at the same relative path that differ in byte content. Keep
this file in sync with the other packs' `.gitkeep` or `:app:packageDebugBundle`
will fail.

## Do not commit

Do not commit any `.litertlm` files or generated fragments in `assets/`.
