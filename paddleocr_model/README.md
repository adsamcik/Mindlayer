# `:paddleocr_model` — PaddleOCR PP-OCRv5 mobile AI Pack

Play Asset Delivery install-time pack carrying the four runtime artifacts
that the service-side OCR engine loads when handling the multi-frame OCR
session API.

## What ships here

- `src/main/assets/paddleocr-ppocrv5-mobile-det.tflite` — text-line detection
- `src/main/assets/paddleocr-ppocrv5-mobile-rec.tflite` — text recognition
- `src/main/assets/paddleocr-ppocrv5-mobile-cls.tflite` — text-orientation classifier
- `src/main/assets/paddleocr-ppocrv5-mobile-dict.txt` — character dictionary
- `src/main/assets/paddleocr_model_integrity.json` — SHA-256 manifest the
  service consumes to verify each artifact at load time (mirrors
  `gemma_model` and `gemma_embed_model`)

The four `.tflite` and `.txt` payload files are **deliberately not
committed** (see `/.gitignore`: `*.tflite`, `paddleocr-ppocrv5-mobile-*`).
They are produced by the build-time conversion pipeline in
`.github/workflows/build-paddleocr-models.yml`:

    PaddleOCR PP-OCRv5 mobile (Paddle format)
            v  paddle2onnx 2.1.0
    ONNX  (intermediate, never shipped)
            v  onnx2tf 2.4.0
    .tflite x 3  (det / rec / cls)
    + dict.txt   (verbatim from PaddleOCR repo)

All three conversion tools are pinned community releases that satisfy the
7-day soak rule (`/.github/instructions/privacy-offline.instructions.md`
section "Soak rule").

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

Do not commit any `.tflite` files here. Do not commit the raw
`paddleocr-ppocrv5-mobile-*.txt` dictionary either; the workflow
re-downloads it at publish time and the SHA-256 is the only thing pinned
in tree.
