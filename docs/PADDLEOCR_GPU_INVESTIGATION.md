# PaddleOCR GPU delegate investigation (2026-05-31)

## TL;DR

PaddleOCR PP-OCRv5 mobile **cannot currently run on the LiteRT GPU
delegate** in Mindlayer. Engine init attempts GPU, fails to compile,
falls back to CPU (~XNNPACK), and trips the 24h
`OcrAcceleratorFailureCache` cooldown. The user-visible impact is the
"GPU acceleration disabled" banner on the dashboard and slower OCR
than a GPU-capable device should produce.

This is **not** a Mindlayer bug — it is a version-skew between

- `onnx2tf 2.4.0` (our pinned conversion tool, targeting **TFLite
  Runtime 2.19.1** op coverage), and
- `LiteRT 2.1.5` (our pinned on-device runtime, with a smaller GPU
  delegate kernel set).

Fixing it requires either bumping LiteRT, downgrading onnx2tf to a
version that predates the offending canonicalisation passes, or doing
post-conversion `.tflite` surgery to rewrite the two blocker ops. All
three options have non-trivial trade-offs documented below.

## Symptom

Logcat from a clean engine init on emulator-5554 (with the GPU
accelerator cooldown cleared) — and a near-identical pattern on the
Samsung S928B (Snapdragon 8 Gen 3, LITERT_CL OpenCL):

```
E tflite : Following operations are not supported by GPU delegate:
E tflite : RELU_0_TO_1: Not supported op RELU_0_TO_1
E tflite : TRANSPOSE_CONV: Max version supported: 3. Requested version 4.
E tflite : 130 operations will run on the GPU, and the remaining 114
           operations will run on the CPU.
I tflite : Replacing 130 out of 244 node(s) with delegate
           (LITERT_OPENGL) node, yielding 2 partitions for subgraph 0.
W LiteRtPaddleOcrBackend: PaddleOCR GPU init failed
       (LiteRtException(Failed to compile model)), falling back to CPU
```

Only the detection model fails. Once the engine sees the failure on
det, the whole backend (det + rec + cls) is labelled CPU.

## Why each op blocks GPU

### `RELU_0_TO_1`

PP-OCRv5 detection's MobileNetV3-flavoured backbone uses HardSigmoid
inside SE attention blocks:

```
HardSigmoid(x) = MAX(0, MIN(1, 0.2*x + 0.5))
```

`onnx2tf 2.4.0` lowers HardSigmoid to `MUL + ADD + MAXIMUM + MINIMUM`,
then a post-processing canonicalisation pass collapses
`MAXIMUM(0)+MINIMUM(1)` into a single `RELU_0_TO_1` op (a TFLite
builtin added in TFLite Runtime ~2.16). Quote from onnx2tf's README:

> Added clamp canonicalization: `MAXIMUM(0.0) -> MINIMUM(1.0)` is
> rewritten to `RELU_0_TO_1` to reduce op count and improve
> downstream transpose/activation fusion opportunities.

The LiteRT 2.1.5 GPU delegate kernel registry does not include
`RELU_0_TO_1` (TFLite Runtime added GPU coverage for this op in a
later release). So even though onnx2tf's own `-cgdc` GPU
compatibility report says the model is fine (it targets TFLite
Runtime 2.19.1's op table), the actual on-device delegate rejects
the op at compile time.

### `TRANSPOSE_CONV` version 4

TFLite bumped the `TRANSPOSE_CONV` schema to v4 to carry per-channel
quantisation params. Float32 models don't use the new fields, but
the on-disk flatbuffer declares "schema version 4" anyway.
LiteRT 2.1.5's GPU delegate is pinned at v3.

## What we tried (and what didn't work)

| Attempt | Result |
|---|---|
| `-ofgd` (`--optimization_for_gpu_delegate`) | onnx2tf docs claim it replaces GPU-incompatible ops; in 2.4.0 it does NOT rewrite the two blockers. |
| `-rtpo hardswish` (decompose HardSwish into primitives) | Node count grew 244→360 (so HardSwish IS being decomposed), but `RELU_0_TO_1` still appears because the blockers come from HardSigmoid + the post-pass canonicalisation, not HardSwish. |
| `-rtpo hardsigmoid` | Not supported — onnx2tf 2.4.0's `-rtpo` only accepts `{abs,acos,asin,atan,erf,gathernd,gelu,hardswish,inverse,leakyrelu,matmulinteger,neg,pow,power,prelu}`. |
| Pre-conversion ONNX surgery (rewrite HardSigmoid -> MAX(0)+MIN(1) chain manually) | Untried — onnx2tf would still canonicalise after the rewrite. |

## What would actually work

1. **Bump LiteRT to ≥ 2.3.x** (Q1 2026 release that added the
   missing kernels). Cost: re-validate every existing model against
   the new runtime, AGP/Gradle dep refresh, re-soak. Out of scope for
   the current OCR sprint.
2. **Downgrade onnx2tf to a version before the clamp canonicalisation
   pass landed** (likely ≤ 2.2.x). Risk: loses other recent fixes the
   current rec/det workflows rely on (LayerNorm handling, fixed-shape
   simplification). Worth a sandbox test if option 1 doesn't land
   soon.
3. **Post-conversion `.tflite` surgery**: parse the flatbuffer,
   rewrite `RELU_0_TO_1` ops as `MAXIMUM(0)+MINIMUM(1)` pairs and
   force `TRANSPOSE_CONV` opcode version down to 3. Doable with the
   `flatbuffers` Python library + the TFLite schema. Surgical fix
   that leaves the conversion toolchain untouched.
4. **Live with CPU on PP-OCRv5 for now.** XNNPACK on Snapdragon 8
   Gen 3 already runs the full PP-OCRv5 pipeline in ~1 second for the
   sample fixture. The real cost is real-time camera OCR throughput
   where multi-frame-per-second is desired; for single-image
   async API this is acceptable.

We are currently on option 4.

## Why the conversion workflow still uses `-ofgd` and `-cgdc`

* `-ofgd` is a no-op for the two known blockers in onnx2tf 2.4.0 but
  may rewrite additional ops in future onnx2tf releases. Cheap to
  keep enabled.
* `-cgdc` writes a GPU compatibility report into the workflow summary
  on every conversion. Any future regression that introduces an op
  unknown to TFLite Runtime 2.19.1's GPU table will be loudly visible
  in the workflow log — earlier signal than waiting for an on-device
  failure.

Both are kept on the conversion pipeline as **forward-looking guards**,
not as the fix for this issue.

## Reproducing the failure locally

```powershell
# Clear GPU cooldown so the engine actually tries the GPU delegate
adb -s emulator-5554 shell `
  "run-as com.adsamcik.mindlayer.service.debug rm -rf files/ocr_accelerator"
adb -s emulator-5554 shell am force-stop com.adsamcik.mindlayer.service.debug
adb -s emulator-5554 logcat -c
adb -s emulator-5554 shell monkey -p com.adsamcik.mindlayer.service.debug `
  -c android.intent.category.LAUNCHER 1

# After ~15 seconds, capture the GPU init attempt
adb -s emulator-5554 logcat -d | Select-String `
  "RELU_0_TO_1|TRANSPOSE_CONV|Not supported|GPU init failed|backend ready"
```

## Reproducing the conversion locally

The reproducible conversion environment now lives at
`scripts/build-paddleocr-models/`:

```powershell
docker build -t mindlayer-paddleocr-convert `
  ./scripts/build-paddleocr-models
docker run --rm -v ${PWD}/build/paddle-convert:/work/out `
  -e PADDLEOCR_REF=v3.5.0 mindlayer-paddleocr-convert
```

Output `.tflite` artefacts land in `./build/paddle-convert/`. The
image pins the exact same conversion toolchain (Python 3.12,
paddle2onnx 2.1.0, onnx2tf 2.4.0, tensorflow 2.19.1, tf_keras
2.19.0, onnxsim 0.6.3) the GitHub Actions workflow uses, so local
dry-runs are byte-identical to CI.

## Open questions / future work

* Validate that LiteRT 2.3.x kernels include `RELU_0_TO_1` + `TRANSPOSE_CONV v4`
  before scheduling the runtime bump.
* Sandbox `onnx2tf 2.2.x` to confirm whether disabling the
  canonicalisation alone unlocks GPU on PP-OCRv5.
* Prototype option 3 (post-conversion `.tflite` surgery) as a
  middle path that doesn't move either pinned version.
* Same investigation likely applies to EmbeddingGemma — its
  `LiteRtAcceleratorResolver.resolveEmbeddings` does not currently
  try GPU at all (it goes NPU-or-CPU). Whether GPU compile would
  succeed on Snapdragon's GPU delegate is unknown until the resolver
  is fixed to attempt it.
