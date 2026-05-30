# LiteRT + LiteRT-LM same-process coexistence risk

> **Status: unverified prototype risk.** Last updated: 2026-05-18.
>
> The Mindlayer service loads **two distinct LiteRT-family runtimes**
> in the same Android process: ``com.google.ai.edge.litertlm:litertlm-android:0.12.0``
> for the Gemma chat path, and ``com.google.ai.edge.litert:litert:2.1.5``
> for the embedding (EmbeddingGemma) and OCR (PaddleOCR PP-OCRv5
> mobile) paths. No confirmed incompatibility is known, but
> **public LiteRT/LiteRT-LM issues show real failure modes around
> accelerator resources, native library loading, Android linker
> namespaces, and LiteRT symbol/version resolution** that make the
> coexistence claim untested rather than safe-by-construction.

## What we actually know

### Two distinct Android artifacts
- **LiteRT 2.1.5** (`com.google.ai.edge.litert:litert:2.1.5`,
  published 2026-05-15) — Maven dependencies: Android lifecycle,
  AI delivery, Guava, coroutines-guava.
- **LiteRT-LM 0.12.0** (`com.google.ai.edge.litertlm:litertlm-android:0.12.0`)
  — Maven dependencies: Gson, Kotlin
  reflection, coroutines-android.

Their dependency lists are disjoint, but that does **not** prove
runtime isolation — both ship native `.so` files that may overlap
in symbol space when loaded by the same Android linker.

### Base LiteRT V2 uses CompiledModel, not Interpreter, for GPU/NPU
Google's migration docs are explicit: GPU acceleration via the
legacy `Interpreter` API is limited to LiteRT Maven V1 packages.
V2 consumers (us) must use the `CompiledModel` API. The base
`Interpreter` is maintained for backward compatibility — model
loading still works, but GPU/NPU acceleration runs through
`CompiledModel`. Our `LiteRtEmbeddingBackend` and
`LiteRtPaddleOcrBackend` (the latter is currently a scaffold) must
both use `CompiledModel` for accelerated inference.

### LiteRT-LM has its own backend surface
`LiteRT-LM` 0.12.0 exposes `Backend.CPU()`, `Backend.GPU()`,
`Backend.NPU(...)` in its Kotlin API. It also requires Android
manifest entries declaring native libraries such as
`libvndksupport.so` and `libOpenCL.so`, and may require passing
an NPU native-library directory. **All of this is process-wide
state** — there is no documented partition between LiteRT-LM's
delegate handles and base LiteRT's `CompiledModel` delegate
handles.

## Public failure-mode evidence

These public issues describe real bugs in the same area we depend
on. None of them is a proven incompatibility between the two
stacks, but each is **directly relevant** to the validation we
have to do before relying on coexistence:

| # | Issue | Failure mode |
|---|---|---|
| 1 | [LiteRT #5264](https://github.com/google-ai-edge/LiteRT/issues/5264) | Multiple GPU `CompiledModel` instances in the same Android process fail when the first model remains active. Workaround: close the first model, use CPU for one model, or restart the app process. |
| 2 | [LiteRT-LM #2211](https://github.com/google-ai-edge/LiteRT-LM/issues/2211) | GPU samplers `dlopen` fail for AAR consumers — unresolved `LiteRtCreateEnvironment`, attributed to Android classloader linker namespaces and `libLiteRt.so` not loaded into the expected namespace. |
| 3 | [LiteRT-LM #2292](https://github.com/google-ai-edge/LiteRT-LM/issues/2292) | LiteRT-LM `Backend.GPU()` initialisation failed on Samsung S24 Ultra (Adreno 750) with OpenCL discovery problems and OpenGL fallback hitting `CreateSharedMemoryManager is not implemented`. Treat as historical evidence that accelerator setup can vary by release/device. |

Issue #5264 is the closest analogue to what we're doing: it shows
the GPU delegate is sensitive to multiple-models-in-same-process
even within base LiteRT alone. Layering LiteRT-LM on top is
strictly more complex.

## What the Mindlayer service is doing today

| Component | Runtime | Backend usage | Status |
|---|---|---|---|
| Gemma chat | LiteRT-LM 0.12.0 | NPU → GPU → CPU chain in `EngineManager` | Production |
| EmbeddingGemma | Base LiteRT 2.1.5 | GPU/CPU via `CompiledModel` (`LiteRtEmbeddingBackend`) | Scaffold — verify-on-device markers |
| PaddleOCR PP-OCRv5 | Base LiteRT 2.1.5 | GPU default via `LiteRtAcceleratorResolver` (mirrors chat: `null` → GPU; explicit `NPU` probed + GPU-fallback; explicit `CPU`/`GPU` honored); three sequential `CompiledModel`s (det + rec + cls) | Prototype — same-process coexistence unverified |
| Chat + embeddings + OCR together | LiteRT-LM 0.12.0 + base LiteRT 2.1.5 (embeddings) + base LiteRT 2.1.5 (OCR) | Chat owns LiteRT-LM backend chain; embeddings and OCR resolve through the shared `LiteRtAcceleratorResolver` | Three-stack Phase 4 validation matrix |

All three stacks share the service process. The OCR path is the
**newest** and so the highest-risk for surfacing coexistence
issues; the embedding path has shipped with the same scaffold for
longer but hasn't been device-validated with all three runtimes
active simultaneously.

## Risk note (canonical wording)

Copy this into PR bodies + ADRs that touch the inline LiteRT path:

> **Unverified same-process coexistence risk:** Base LiteRT 2.1.5
> using GPU/NPU acceleration and LiteRT-LM 0.12.0 have not yet
> been validated together in one Android process. No confirmed
> incompatibility is known, but both stacks may interact through
> shared LiteRT runtime components, accelerator delegates, native
> library loading, Android linker namespaces, memory allocation,
> GPU/NPU resources, thread pools, and symbol/version resolution.
> Validate before relying on concurrent or sequential same-process
> execution.

## Validation checklist

Run these on a prototype APK that loads **all three** stacks
(LiteRT-LM Gemma + base LiteRT EmbeddingGemma + base LiteRT
PaddleOCR) in one process:

1. Base LiteRT GPU model active, then initialise + run LiteRT-LM
   GPU.
2. LiteRT-LM GPU active, then initialise + run base LiteRT GPU.
3. Repeat (1) and (2) with the NPU backend where the SoC supports
   it — Qualcomm (Snapdragon) and MediaTek (Dimensity) paths
   especially.
4. Repeat with one stack on CPU and the other on GPU/NPU to
   isolate accelerator-resource conflicts from generic LiteRT
   linker conflicts.
5. Verify teardown: close LiteRT model, close LiteRT-LM engine,
   then **reverse the initialisation order** and re-run. Watch
   for stale FD / stale delegate state.
6. Capture `logcat` for these tokens during all runs:
   - `dlopen`
   - `libLiteRt`
   - `OpenCL`, `OpenGL`
   - `QNN`, `mediatek`, `apuwareUtils`
   - `delegate`
   - `LiteRtException`
   - `unresolved symbol`, `undefined reference`
7. Inspect the packaged APK with `apkanalyzer` (or `unzip + ls`)
   for duplicate or conflicting `libLiteRt*.so` files. Confirm the
   APK resolves the expected versions. If two `.so` artifacts
   declare the same SONAME, the linker only loads one — the
   "loser" stack will fail at first native call.
8. Stress test:
   - Concurrent inference (one Gemma + one embedding + one OCR
     frame at once).
   - Sequential inference with no teardown between stacks.
   - Repeated initialise → run → close cycles.
   - Process recreation (rotation, kill+relaunch).
   - Low-memory pressure during active inference.

## What lives where in code

- ``app/src/main/kotlin/com/adsamcik/mindlayer/service/engine/LiteRtEmbeddingBackend.kt``
  — base LiteRT V2 embedding backend (scaffold).
- ``app/src/main/kotlin/com/adsamcik/mindlayer/service/engine/LiteRtPaddleOcrBackend.kt``
  — base LiteRT V2 PaddleOCR backend (scaffold).
- ``app/src/main/kotlin/com/adsamcik/mindlayer/service/engine/EngineManager.kt``
  — LiteRT-LM Gemma lifecycle.

Each of these files now references this doc from the verify-on-
device TODO comment so a grep for `LITERT_COEXISTENCE.md` surfaces
every site that's currently unverified.

## #5264 hazard surface

LiteRT issue #5264 is directly relevant to every site that calls `CompiledModel.create` while another LiteRT-family model can remain active in the same Android process:

- `app/src/main/kotlin/com/adsamcik/mindlayer/service/engine/RealLiteRtRunner.kt` creates the EmbeddingGemma base-LiteRT `CompiledModel`.
- `app/src/main/kotlin/com/adsamcik/mindlayer/service/engine/RealPaddleOcrLiteRtRunner.kt` creates PaddleOCR detection, recognition, and optional orientation-classifier `CompiledModel`s back-to-back.
- `app/src/main/kotlin/com/adsamcik/mindlayer/service/engine/EngineManager.kt` creates the LiteRT-LM Gemma `Engine` with its own CPU/GPU/NPU backend surface.

`LiteRtAcceleratorResolver` allows both embeddings and OCR to opt into GPU/NPU when supported. Embeddings default to NPU-when-allowlisted-and-libs-probed-else-CPU; OCR mirrors chat — `null` defaults to GPU, explicit `NPU` is probed against the SoC allowlist + native-library check and falls back to GPU on unsupported devices, explicit `CPU`/`GPU` is always honored. The three sequential `CompiledModel` instances (det + rec + cls) make OCR the highest-exposure site for issue #5264 — run the checklist above on target devices before relying on GPU/NPU OCR in production. Callers that need a conservative configuration must pass `preferredBackend = "CPU"` explicitly.

## Per-feature accelerator overrides

`app/src/main/kotlin/com/adsamcik/mindlayer/service/engine/LiteRtAcceleratorResolver.kt` is the single coordination point for per-feature LiteRT accelerator decisions. Callers pass `featureName` (`embeddings`, `ocr`, or `chat`) and receive the selected backend plus a downgrade reason chain suitable for logs and the dashboard. Add future GPU/NPU OCR opt-ins there first, not in individual backend files.

## References

- [`com.google.ai.edge.litert:litert:2.1.5` on Maven Central](https://mvnrepository.com/artifact/com.google.ai.edge.litert/litert/2.1.5)
- [`com.google.ai.edge.litertlm:litertlm-android:0.12.0` on Maven Central](https://mvnrepository.com/artifact/com.google.ai.edge.litertlm/litertlm-android/0.12.0)
- [LiteRT migration docs (Google AI for Developers)](https://ai.google.dev/edge/litert/migration) — V1 vs V2 + Interpreter vs CompiledModel split.
- [LiteRT-LM Kotlin getting-started](https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md) — Backend API + native-library manifest requirements.
- [LiteRT issue #5264](https://github.com/google-ai-edge/LiteRT/issues/5264) — same-process GPU model coexistence within base LiteRT.
- [LiteRT-LM issue #2211](https://github.com/google-ai-edge/LiteRT-LM/issues/2211) — GPU sampler `dlopen` failure / linker namespace.
- [LiteRT-LM issue #2292](https://github.com/google-ai-edge/LiteRT-LM/issues/2292) — Backend.GPU() Adreno 750 OpenCL/OpenGL fallback.
