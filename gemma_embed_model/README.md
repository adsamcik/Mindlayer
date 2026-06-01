# `:gemma_embed_model` — EmbeddingGemma-300M AI Pack

Play Asset Delivery install-time pack carrying EmbeddingGemma `.tflite`
weights plus the SentencePiece tokenizer. The service-side embedding engine
loads both at first use.

## Why the name is `gemma_embed_model` and not `embeddinggemma_model`

The module was originally `:embeddinggemma_model`. It was renamed because
AGP 9.x's `AssetPackPreBundleTask` filters per-pack asset directories with a
**substring** check on the pack name (see `AssetPackPreBundleTask.kt:73`):

```kotlin
assetPack.absolutePath.contains(
    assetPackName + File.separator + "src" + File.separator + "main" + File.separator + "assets"
)
```

`embeddinggemma_model\src\main\assets` *contains* the substring
`gemma_model\src\main\assets`, so when AGP iterated for the `gemma_model`
pack the filter matched BOTH packs. The two paths got joined with
`File.pathSeparator` (`;` on Windows) and passed to `java.nio.file.Paths.get()`,
which fails on Windows because `;` is illegal in a single path and the second
path's drive-letter `:` is at an illegal index:

    java.nio.file.InvalidPathException: Illegal char <:> at index 49:
    G:\...\gemma_model\src\main\assets;G:\...\embeddinggemma_model\src\main\assets

The rename to `gemma_embed_model` breaks the suffix collision —
`gemma_embed_model` no longer contains `gemma_model` as a substring (the `e`
of `embed` interrupts the match).

Worth filing upstream against AGP: the filter should compare the parent
directory name exactly, not do a substring `contains` on the full path.

## What ships here

- `src/main/assets/embedding-gemma-300m-v1.tflite` — EmbeddingGemma weights (**not committed**)
- `src/main/assets/embedding-gemma-300m-v1.spm.model` — SentencePiece tokenizer (**not committed**)
- `src/main/assets/embedding_model_integrity.json` — SHA-256 manifest produced
  by the `generateEmbeddingModelIntegrityManifest` task; the service uses it
  to verify both files at load time.

The two model files are **deliberately not committed** (see `/.gitignore`).
They are delivered via Play Asset Delivery (install-time pack) or sideloaded
with `tools/dev-models/push-models.ps1`.

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

Do not commit any `.tflite`, `.spm.model`, or other model artifacts in `assets/`.
