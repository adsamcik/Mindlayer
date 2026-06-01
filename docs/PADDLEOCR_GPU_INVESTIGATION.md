# PaddleOCR GPU delegate investigation (2026-05-31)

## TL;DR — current state (2026-05-31, post-surgery)

| Model | Pre-surgery | Post-surgery | Δ |
|---|---|---|---|
| det (640×640) | 130/244 GPU, compile fails | **322/322 GPU, compile succeeds** | ✅ fully GPU-compatible |
| rec (48×320) | 220/452 GPU, compile fails | 277/398 GPU, compile fails on 5D RESHAPE/TRANSPOSE | ⚠️ partially improved; structural blocker remains |
| cls (80×160) | (not reached) | not validated standalone (engine fails on rec first) | TBD |

**Surgery shipped** at `scripts/build-paddleocr-models/tflite_gpu_fixup.py`
+ wired into the Dockerised conversion pipeline. Verified
numerically equivalent on CPU (same 3-line output on the dashboard
fixture as the pre-surgery models).

**End-user-visible state is unchanged**: the engine's current
all-or-nothing GPU policy means a single model's GPU compile failure
forces the entire pipeline to CPU. To actually realise the det+cls
GPU speedup we additionally need either (a) per-model GPU/CPU
selection in `LiteRtPaddleOcrBackend` (Kotlin refactor, not in
scope for this session) or (b) rec-side surgery for the 5D RESHAPE
/ TRANSPOSE ops (structural model rewrite — also out of scope).

## The surgery — what it does today

`scripts/build-paddleocr-models/tflite_gpu_fixup.py` runs after
`onnx2tf` in the Dockerised conversion pipeline and walks the
flatbuffer in place:

1. **`RELU_0_TO_1` → `MAXIMUM(0) + MINIMUM(1)`** — the LiteRT 2.1.5
   GPU delegate has no kernel for `RELU_0_TO_1` (a TFLite Runtime
   2.16+ builtin added for clamp-canonicalisation efficiency).
   Rewriting it as `MAXIMUM + MINIMUM` is exact: `clamp(x, 0, 1) =
   min(1, max(0, x))`. Tested counts: det = 10, rec = 2, cls = 2.
2. **`TRANSPOSE_CONV` opcode version 4 → 3** — v4 added per-channel
   quant fields; float32 models don't use them, so the on-disk
   bytes stay valid for v3. Tested counts: det = 1.
3. **`STRIDED_SLICE` opcode version 4 → 2** — same story; v4 added
   optional ellipsis-axis-mask. Tested counts: rec = 1.

Backed by `tensorflow.lite.tools.flatbuffer_utils` (Google's
official mutable-flatbuffer helper, used internally by the TFLite
team) so we don't ship a hand-rolled flatbuffer codec.

## Why rec still fails GPU compile after surgery

Captured logcat from emulator-5554 (LITERT_OPENGL) — identical
pattern reproduces on Samsung S928B (LITERT_CL OpenCL Adreno):

```
E tflite : Following operations are not supported by GPU delegate:
E tflite : RESHAPE: Tensor "model_44/tf.reshape_11/Reshape" has bad input dims size: 5.
E tflite : RESHAPE: Tensor "model_44/tf.reshape_5/Reshape"  has bad input dims size: 5.
E tflite : RESHAPE: Tensor "model_44/tf.strided_slice/StridedSlice" has bad input dims size: 5.
... (6 strided_slice RESHAPEs)
E tflite : TRANSPOSE: Tensor "model_44/tf.reshape_11/Reshape" has bad input dims size: 5.
E tflite : TRANSPOSE: Tensor "model_44/tf.reshape_5/Reshape"  has bad input dims size: 5.
E tflite : 277 operations will run on the GPU, and the remaining 121 operations will run on the CPU.
W LiteRtPaddleOcrBackend: PaddleOCR GPU init failed
       (LiteRtException(Failed to compile model)), falling back to CPU
```

PP-OCRv5's rec head is SVTR-style (transformer over sequence-of-
patches): the QKV projection unpacks `[B, T, 3*H*D]` into
`[B, T, 3, H, D]` (5D), then strided-slices Q / K / V out. The
LiteRT GPU delegate's tensor-shape pipeline tops out at 4D. Even
with `-rtpo hardswish` and the surgery's STRIDED_SLICE downgrade,
the 5D reshape/transpose intermediates remain — they're
intrinsic to the model's attention block, not artefacts of layout
conversion.

Onnx2tf has no flag that rewrites 5D into chained 4D ops; that
class of rewrite needs either upstream tooling support or
hand-written model surgery.

## What we tried (and what didn't work)

| Attempt | Result |
|---|---|
| `-ofgd` (`--optimization_for_gpu_delegate`) | No-op for `RELU_0_TO_1`; does downgrade some other ops. |
| `-rtpo hardswish` | Decomposed HardSwish (244→360 nodes for det) but blockers remained. |
| `-rtpo hardsigmoid` | Not in onnx2tf 2.4.0's `-rtpo` whitelist. |
| Post-surgery RELU_0_TO_1 → MAX+MIN rewrite | ✅ Fixed all RELU_0_TO_1 blockers. |
| Post-surgery TRANSPOSE_CONV v4 → v3 | ✅ Fixed det. |
| Post-surgery STRIDED_SLICE v4 → v2 | ✅ Improved rec (220 → 277 GPU ops) but 5D RESHAPE/TRANSPOSE remain. |
| `--keep_nwc_or_nhwc_or_ndhwc_input_names` | Doesn't apply — the 5D shapes are internal to attention, not layout-conversion artefacts. |

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

## What would actually unlock end-user-visible GPU

| Option | Viability today | Notes |
|---|---|---|
| 1. Bump LiteRT to a release with the missing kernels (`RELU_0_TO_1`, `TRANSPOSE_CONV v4`, `STRIDED_SLICE v4`, **5D RESHAPE/TRANSPOSE**) | ❌ NOT VIABLE | `com.google.ai.edge.litert:litert` latest GA on Google Maven is **2.1.5** (the version we already use). The companion `litert-gpu` artifact is stuck at 1.4.2 (legacy delegate path superseded by the GPU support baked into `litert:2.x`). Until Google publishes ≥ 2.2.x there is no newer LiteRT to bump to. Periodically re-check `https://dl.google.com/android/maven2/com/google/ai/edge/litert/litert/maven-metadata.xml`. |
| 2. Downgrade onnx2tf to a version before the clamp canonicalisation pass landed | ✅ Now redundant | Post-conversion surgery (option 3) fixes the same problem without a version move. |
| 3. Post-conversion `.tflite` surgery | ✅ **SHIPPED for the opcode-level blockers** | See `scripts/build-paddleocr-models/tflite_gpu_fixup.py`. det fully fixed; rec partially (5D blockers remain). |
| 4. Live with CPU | ✅ Current effective state | Engine still routes to CPU because of (5). |
| **5. Allow per-model GPU/CPU in `LiteRtPaddleOcrBackend`** | ⚠️ **THE NEXT STEP** | Today the backend uses a single `runner` for det+rec+cls and a single `backendLabel`; a per-model split is a medium-sized Kotlin refactor. Would let det+cls compile on GPU while rec stays CPU — det is where most wall-clock time is spent, so this is the big speedup. |
| 6. rec 5D model surgery | ⚠️ Risky | Rewrite the QKV unpack `[B, T, 3*H*D] → [B, T, 3, H, D]` into a chain of 4D ops. Needs careful numerical equivalence testing. |
| 7. File upstream LiteRT issue requesting 5D RESHAPE/TRANSPOSE support | ✅ Free | Doesn't block anything; helps a future LiteRT bump cover this without per-model surgery. |

**Recommended next steps**: option 5 (per-model GPU/CPU in the
backend) for the actual speedup, plus option 7 (file the upstream
issue) so a future LiteRT bump can pick up the rest naturally.

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

* When Google publishes LiteRT ≥ 2.2.x, validate whether its GPU
  delegate kernel registry includes `RELU_0_TO_1` + `TRANSPOSE_CONV v4`
  before scheduling the runtime bump. Until then, no bump is possible.
* Sandbox `onnx2tf 2.2.x` to confirm whether disabling the
  canonicalisation alone unlocks GPU on PP-OCRv5 (fallback to option 2
  if option 3 hits unexpected blockers).
* Same investigation likely applies to EmbeddingGemma — its
  `LiteRtAcceleratorResolver.resolveEmbeddings` does not currently
  try GPU at all (it goes NPU-or-CPU). Whether GPU compile would
  succeed on Snapdragon's GPU delegate is unknown until the resolver
  is fixed to attempt it.
* Optional: file an upstream LiteRT issue requesting GPU kernels for
  `RELU_0_TO_1` + `TRANSPOSE_CONV v4` so a future runtime bump
  unblocks this without conversion surgery.
