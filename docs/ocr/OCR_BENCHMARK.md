# OCR engine benchmark

A connected-test harness that compares Mindlayer's production PaddleOCR
backend (`LiteRtPaddleOcrBackend`) against Tesseract 4.9 LSTM on a fixture
set of label photographs.

- **Implementation:** [`OcrEngineBenchmarkInstrumentedTest`](../../app/src/androidTest/kotlin/com/adsamcik/mindlayer/service/engine/OcrEngineBenchmarkInstrumentedTest.kt)
- **Tesseract version:** `cz.adaptech.tesseract4android:tesseract4android:4.9.0` (Apache 2.0). Pulled from JitPack — see the content-scoped repo declaration in [`settings.gradle.kts`](../../settings.gradle.kts).
- **Scope:** `androidTestImplementation` only. The Tesseract runtime is never linked into `:app` or `:sdk` release artifacts; the production OCR path remains PaddleOCR-only on top of LiteRT.

## Why this exists

When evaluating OCR engine choices the team needed a reproducible
on-device way to compare per-engine **quality** (what text comes out)
and **latency** (how long it takes) on real product imagery —
specifically the coffee-bag label domain that motivated v0.8's OCR API.
This harness lets any developer re-run that comparison in ~5 minutes on
an emulator or device.

## Running it locally

### 1. Stage the Tesseract trained data

The two `.traineddata` files are **gitignored** because they are 92 MB
combined and have their own canonical distribution. Pull from
[`tessdata_fast`](https://github.com/tesseract-ocr/tessdata_fast) (LSTM,
matched to Paddle PP-OCRv5 mobile's accuracy/speed band):

```powershell
$dest = "app\src\androidTest\assets\ocr-bench\tessdata"
New-Item -ItemType Directory -Force -Path $dest | Out-Null
$base = "https://github.com/tesseract-ocr/tessdata_fast/raw/main"
Invoke-WebRequest "$base/eng.traineddata"          -OutFile "$dest\eng.traineddata"
Invoke-WebRequest "$base/script/Latin.traineddata" -OutFile "$dest\Latin.traineddata"
```

bash equivalent:

```bash
mkdir -p app/src/androidTest/assets/ocr-bench/tessdata
base=https://github.com/tesseract-ocr/tessdata_fast/raw/main
curl -L "$base/eng.traineddata"          -o app/src/androidTest/assets/ocr-bench/tessdata/eng.traineddata
curl -L "$base/script/Latin.traineddata" -o app/src/androidTest/assets/ocr-bench/tessdata/Latin.traineddata
```

### 2. Stage the image set

Drop image fixtures (JPEG/PNG) into `app/src/androidTest/assets/ocr-bench/coffee-bags/`.
The benchmark iterates over every file the asset manager lists from that
directory, so swap in whatever target domain you want to evaluate
against (receipts, signage, ID cards, etc.).

The directory is gitignored, so contributor fixtures stay local. If the
directory is empty / absent the test self-skips via `assumeTrue` without
failing the wider `:app:connectedAndroidTest` run.

### 3. Sideload the PaddleOCR PP-OCRv5 mobile bundle

`LiteRtPaddleOcrBackend` requires the four PaddleOCR artifacts. Use the
dev sideload path:

```powershell
.\tools\dev-models\push-models.ps1 -Paddleocr -Cache D:\mindlayer-models
```

See [`docs/models/DEV_MODELS.md`](../models/DEV_MODELS.md) for how to populate the cache
from CI artifacts of `build-paddleocr-models.yml`.

### 4. Run

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain `
    "-Pandroid.testInstrumentationRunnerArguments.class=com.adsamcik.mindlayer.service.engine.OcrEngineBenchmarkInstrumentedTest" `
    "-Pmindlayer.bundleGemma=false" `
    "-Pmindlayer.bundleEmbeddings=false" `
    "-Pmindlayer.bundlePaddleocr=false"
```

Runtime: ~4 minutes for 13 images × 3 engines × (1 warmup + 3 iters) on
an x86_64 API 36 emulator.

### 5. Collect results

The harness writes to the app's `externalFilesDir`:

```powershell
adb pull /sdcard/Android/data/com.adsamcik.mindlayer.service.debug/files/ocr-bench ./bench-results
```

You get:

- `results.csv` — `image,engine,iter,lineCount,charCount,totalMs,detMs,recMs`
- `text/<image>.<engine>.txt` — verbatim recognized text from iteration 1
- `summary.txt` — p50/p95/mean per engine across all (image, iter) samples

## What the harness measures (and doesn't)

| Measured | Caveat |
|---|---|
| Wall-clock per `recognise()` call | Includes Y-plane → Tesseract Bitmap path for parity with how each engine ships |
| Token / character counts per engine | A token = `[A-Za-zÀ-ž0-9]{3,}` substring — proxy for "recognized words", resistant to whitespace noise |
| First-iteration text (saved verbatim) | For human eyeballing or character-error-rate scoring you add later |
| Latency p50, p95, mean | After 1 warmup iteration to absorb JIT + native init |

**Not measured:**

- **No accuracy score against ground truth.** This benchmark is quality-by-inspection plus token-volume proxies; add a `truth/<image>.txt` step + CER/WER calc if you need accuracy numbers.
- **Real-device latency.** Emulator x86_64 CPU OCR is *not* representative of physical-device performance. Treat the ordering of engines as the take-away, not the absolute milliseconds.
- **GPU / NPU.** `LiteRtPaddleOcrBackend` is currently CPU-locked pending the LiteRT coexistence checklist (see `OcrFeatureFlags.IS_PRODUCTION_READY`); the comparison runs against equivalent Tesseract CPU paths.

## Why Tesseract is androidTest-only

Mindlayer's privacy + offline invariants ([`.github/instructions/privacy-offline.instructions.md`](../../.github/instructions/privacy-offline.instructions.md)) constrain release builds to a single on-device inference runtime (LiteRT). Tesseract4Android is genuinely useful for evaluation but is **not** a production runtime in this codebase. Keeping the dependency in `androidTestImplementation` means:

- It never touches the release APK / AAB classpath.
- It never shows up in license aggregation, OSS attribution screens, or the SBOM.
- It never adds a competing native delegate in the same process as LiteRT-LM + LiteRT.

If you ever consider promoting Tesseract to a production engine, that's a runtime-policy decision that requires updating the privacy invariant doc and the lint check that pins it — not a quiet `implementation` line swap.
