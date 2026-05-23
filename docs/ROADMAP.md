# Mindlayer Roadmap

> What's complete, what's in progress, and what's gated on device validation.
>
> **Last updated:** 2026-05-23

This file is the single source of truth for outstanding Mindlayer work. It's
intended to survive across contributors and AI-assisted sessions, so anyone
landing in the repo can quickly answer "what's done, what's next, what's
blocked, and why."

---

## TL;DR

Mindlayer's full **architecture is production-ready** through Phase 5:

- **Chat** (LiteRT-LM Gemma 4 E2B-it) — fully wired, production-ready.
- **Embeddings** (EmbeddingGemma 300m via base LiteRT) — fully wired and
  flipped to `IS_PRODUCTION_READY = true` in 2026-05-20.
- **OCR** (PaddleOCR PP-OCRv5 mobile via base LiteRT) — fully wired, real
  postprocessor, full SDK transport, lifecycle, and finalization.
  **`OcrFeatureFlags.IS_PRODUCTION_READY` is still `false`** — Phase 6
  test harness + CI plumbing landed in PRs #106 + #107 (2026-05-23);
  flip is now gated on **provisioning model SHA repo vars + shipping a
  first-party OCR driver app**, not on a real device. See
  [§ Phase 6](#phase-6--asset-provisioning--product-gated) below.

What's left is mostly **operational** (provision model artefacts in CI
`vars.*`, run the AI-Pack-enabled instrumented lane, measure the
synthetic-corpus floors) plus a **product gate** (first-party OCR
driver app) and a small set of UX/product polish items. None of it
blocks the architectural claims of the SDK — it blocks the
public-release claim for OCR specifically.

If you want the full per-PR landing record of Phases 1-5, see
`CHANGELOG.md` plus the session-state plan files in the AI-assistant
workflow (not in the repo, by design).

---

## What's done (Phases 1-5, 2026-05-01 → 2026-05-22)

> Pointers are by feature, not by phase, so this stays useful as the phase
> labels age.

### Service architecture
- Single AIDL surface (`IMindlayerService`), byte-identical mirror between
  `:app` and `:sdk`. See `docs/AIDL_STABILITY.md`.
- Four-stage authorization gate on every entry point: identity →
  allowlist → rate limit → ownership. See `docs/AUTHORIZATION.md`.
- Signature-level `BIND_ML_SERVICE` permission, `signature|knownSigner` on
  API 31+, plus runtime allowlist with one-time user approval for
  first-party callers.
- Foreground-service lifecycle (`specialUse`) promoted only during active
  inference; refcounted across chat / embeddings / OCR.
- Cross-process state via `filesDir` + atomic rename + `FileLock` and a
  HMAC-SHA256-sealed allowlist envelope.

### Engine fleet
- **Chat (LiteRT-LM 0.12.0)** — full lifecycle, structured-output
  fail-closed, tool-call routing, thermal/memory-band policy, init queue
  with bounded timeout, EMERGENCY teardown ordering.
- **Embeddings (base LiteRT 2.1.5)** — `LiteRtEmbeddingBackend` with
  real `CompiledModel` wiring, pure-Kotlin SentencePiece Unigram
  tokenizer (no JNI), SHM batch transport, deferred fetch/cancel/ack,
  per-UID HKDF-derived AES-GCM encryption of deferred blobs.
- **OCR (base LiteRT 2.1.5)** — `LiteRtPaddleOcrBackend` with bilinear
  sampler, aspect-preserved rec preprocessing with right-pad, pure-Kotlin
  Suzuki-Abe DB postprocessor (`minAreaRect` + unclip + NMS), Y-plane and
  encoded JPEG/PNG/WEBP transport, per-session `Mutex`, finalization
  drain with `OCR_RESULT_FINALIZED` + `DONE`, `FRAME_DROPPED` guard.
- **Shared accelerator coordination** — `LiteRtAcceleratorResolver` with
  per-feature decisions (`featureName` ∈ `chat` / `embeddings` / `ocr`),
  SoC allowlist, native-library probe, API-level gate. OCR currently
  CPU-locked pending coexistence validation
  (`OCR_CPU_LOCK_UNTIL_COEXISTENCE_VALIDATED`).

### SDK
- One front door: `Mindlayer.connect()` + `MindlayerSession` for chat,
  `Mindlayer.embed*` for embeddings, `Mindlayer.ocr { … }` DSL for OCR.
- First request blocks on engine init or returns typed
  `ENGINE_INITIALIZING` (no silent hang).
- Async push/pull for deferred chat + deferred embeddings, with
  completion notifications via `IClientCallback` (push-primary, polling
  only as fallback).
- All AIDL calls funnel through `withTypedErrors { … }` and surface
  `MindlayerException(MindlayerErrorCode)` rather than raw exceptions.
- OCR `requireOcrCapability()` gate on every OCR entry point; SDK
  refuses calls when the feature is not advertised.
- CameraX integration (`:sdk-camerax`) with typed `OcrAnalyzerEvent`
  (Error/Busy/Dropped) instead of swallow-all-as-busy.
- `OcrEvent.Error` on the stream + DONE-terminal contract.

### Observability
- `DiagnosticsSnapshot` schemaVersion 2 covering OCR backend lifecycle,
  embedding model + backend, per-feature `AcceleratorDecision` reason
  chains, deferred chat + embedding counters.
- `LogEvent` enum with stable string keys (DB-compatible), `LogCategory`
  extended with `OCR` / `EMBEDDING` / `IPC` / `AUTH`.
- Dashboard surfaces per-feature backend decisions and filters stale
  init-failure banners after recovery.
- `MindlayerLog` discipline: metadata-only, never prompts or output,
  `safeLabel()` for native-failure paths, no raw stack traces.

### Build / CI / release
- Gradle 9.5 with AGP 9.2.1, Kotlin 2.3.21, JDK 17 source / JBR 21
  runtime (CI's "Set up JDK 17" intentionally installs Java 21).
- Parallel + caching + Kotlin incremental enabled; 4 GiB heap.
- Five-gate CI: `:shared:testDebugUnitTest`, `:sdk:testDebugUnitTest`,
  `:app:testDebugUnitTest`, `:app:testReleaseUnitTest`, `lintDebug`,
  plus instrumented `(33)` + `(34)`, plus a `validate-release-shas`
  job.
- `build-bundle` job ingests Gemma + PaddleOCR (det/cls/rec) +
  EmbeddingGemma model + tokenizer SHAs; skips cleanly when payload is
  absent.
- Signed release AAB attached on `v*` tags via `publish.yml`.
- Dependabot grouped (`androidx-and-compose`, `test-deps`,
  `kotlin-stack`, `github-actions`); LiteRT-LM minor/major bumps are
  ignored because they require device validation.

---

## Phase 6 — Asset-provisioning + product gated

Phase 6 items #3 and #4 are now **emulator-CI ready** (harness + plumbing
landed in PRs #106 + #107, 2026-05-23). The remaining work to flip
`OcrFeatureFlags.IS_PRODUCTION_READY = true` is:

1. **Provision model SHA repository variables in GitHub Actions**
   (`vars.MODEL_SHA256`, `vars.PADDLEOCR_*_SHA256`,
   `vars.EMBEDDING_*_SHA256`) and the actual `.tflite` payloads on the
   build runner.
2. **Ship a first-party OCR driver app** in production (product/release
   decision; not a CI gate).

Both are out of scope for an automated agent fleet — they need
operational sign-off and an external model-hosting decision.

### 1. OCR `IS_PRODUCTION_READY = true` flip

**Location:** `app/src/main/kotlin/com/adsamcik/mindlayer/service/engine/OcrFeatureFlags.kt`

The flag is a one-line atomic commit. The gating criteria are:

- **3-stack coexistence validated on emulator** *(harness landed,
  PR #106 — provision assets to actually run)*. CI's `instrumented-tests
  (33)/(34)` lanes mirror the `paddleocr_model` AI Pack assets into the
  `:app` androidTest APK when the model SHA repo vars are set, so
  `EngineCoexistenceInstrumentedTest.paddleocr_production_backend_loads_real_ai_pack_assets`
  exercises the production `LiteRtPaddleOcrBackend` on the API 33/34
  emulator matrix. The 8-step checklist in `docs/LITERT_COEXISTENCE.md`
  remains the canonical reference; emulator (x86_64 + swiftshader) is
  sufficient for the CPU-only shipping configuration because OCR is
  CPU-locked via `LiteRtAcceleratorResolver`
  (`OCR_CPU_LOCK_UNTIL_COEXISTENCE_VALIDATED`). GPU/NPU coexistence
  remains real-device-gated and is deferred to Phase 8.
- **Numeric OCR validation on emulator** *(harness landed, PR #107 —
  provision assets to actually run)*.
  `OcrNumericValidationInstrumentedTest` drives the production backend
  against a deterministic 20-image synthetic Canvas corpus and asserts
  recall/precision/F1 ≥ 0.70 (lenient v1 floor; tune up after the first
  real-asset green CI measurement, ICDAR2015 corpus is a follow-up).
- **First-party OCR driver app exists.** Same posture embeddings
  shipped under in Phase D #4 — flip the production-ready flag only
  when a first-party caller is in production (so we own the end-to-end
  user experience before opening it up). **Still required.**

When all three are green, flip:

```kotlin
const val IS_PRODUCTION_READY: Boolean = true
```

…in `OcrFeatureFlags.kt`, run all 5 CI gates, ship behind a Play
internal-testing track first.

### 2. Real model artifact pipeline

Pre-release artifacts are intentionally **not in git** (size + license):

| Artifact | Module | SHA gate property |
|---|---|---|
| `gemma-4-E2B-it.litertlm` | `:gemma_model` | `-PmodelSha256=<hex>` (Gemma chat) |
| `embedding-gemma-300m-v1.tflite` | `:embeddinggemma_model` | `-PembeddingModelSha256=<hex>` |
| `embedding-gemma-300m-v1.spm.model` | `:embeddinggemma_model` | `-PembeddingTokenizerSha256=<hex>` |
| `paddleocr-ppocrv5-mobile-det.tflite` | `:paddleocr_model` | `-PpaddleocrDetSha256=<hex>` |
| `paddleocr-ppocrv5-mobile-rec.tflite` | `:paddleocr_model` | `-PpaddleocrRecSha256=<hex>` |
| `paddleocr-ppocrv5-mobile-cls.tflite` (optional) | `:paddleocr_model` | `-PpaddleocrClsSha256=<hex>` |
| `paddleocr-ppocrv5-mobile-dict.txt` | `:paddleocr_model` | `-PpaddleocrDictSha256=<hex>` |

CI's `build-bundle` job skips cleanly when any SHA var is unset. The
`instrumented-tests` job's "Provision AI Pack assets" step (added in
PR #106) does the same: when the `.tflite` files and integrity manifest
are present on the runner, the production coexistence + numeric
validation tests run end-to-end; otherwise both `assumeTrue`-skip.

Release process is documented in `RELEASE.md`. Outstanding work:

- Set the seven SHA variables in GitHub Actions `vars.*` for the
  `release-aab` job to actually attach a signed AAB on `v*` tags.
- Provision the model payloads themselves (where they're hosted,
  where the build pipeline fetches them from) — currently out of repo
  by design, but the fetch mechanism is undocumented.

### 3. Numeric OCR validation harness — *delivered, awaiting assets*

**Status:** Test harness landed in PR #107 (2026-05-23).
`OcrNumericValidationInstrumentedTest` ships a deterministic synthetic
corpus (20 Canvas-drawn images, no binary assets in git), runs on the
API 33/34 emulator matrix via `@SdkSuppress(minSdkVersion = 33)`, and
`assumeTrue`-skips when the AI Pack manifest is absent.

Remaining work — none of this blocks the flag flip on a CI lane with
assets provisioned, but is worth doing afterwards:

- Measure recall/precision/F1 on the synthetic corpus once the AI Pack
  is provisioned, bump the floors from the lenient v1 0.70 to the
  ROADMAP target (recall ≥ 0.85, precision ≥ 0.80, F1 ≥ 0.82) if the
  measurements support it.
- Add an opt-in ICDAR2015 mini-corpus (~50-100 representative images),
  gated behind a separate asset bundle so the synthetic corpus stays
  the always-on gate and ICDAR is the strict-mode gate.

### 4. Three-stack coexistence smoke with real AI Pack assets — *delivered, awaiting assets*

**Status:** Plumbing landed in PR #106 (2026-05-23). The
`instrumented-tests` job now provisions the `paddleocr_model` AI Pack
assets into the `:app` androidTest APK when the model SHA repo vars
are set; otherwise the existing `assumeTrue` skip stays in effect, so
CI without assets is unaffected. `:paddleocr_model:assembleDebug`
runs before tests to refresh the integrity manifest.

Remaining work:

- Once assets are provisioned in CI, extend the coexistence smoke
  beyond "loads back-to-back without state bleed" to include actual
  `recognise()` of a fixture frame against the real detector
  (`OcrNumericValidationInstrumentedTest` partially covers this on the
  synthetic-corpus side).

---

## Phase 7 — Product polish (next-up after Phase 6)

Things the audits surfaced as nice-to-haves but didn't block release.

### Concurrent EMERGENCY testing
- PR #99 added a `Mutex` single-flight guard on
  `MindlayerMlService.applyMemoryPressure`. Cluster F (PR #103) covered
  resolver and OCR races but did not add concurrent-EMERGENCY assertion
  tests — combine `rapidConcurrentEmergencyEmissions` + partial-failure
  during drain (OCR drain throws, PaddleOCR unload throws, embedding
  unload throws). Recommended as a single 100-150 LOC test PR.

### `MlHealthRecorder` deferred counters
- The deferred submit/completion counters in `MlHealthRecorder` aren't
  persisted, exported, or displayed. PR #100 added them to the
  diagnostics snapshot but the recorder still keeps them in-memory.
  Either persist them (consistent with the rest of the snapshot) or
  remove the unused fields.

### Configuration cache trial
- PR #93 enabled parallel + caching + Kotlin incremental. The
  configuration-cache was intentionally **not** trialed in that PR.
  Phase 7 work: trial `org.gradle.configuration-cache=true` on
  `help`, `assembleDebug`, `testDebugUnitTest`, `lintDebug`. Fix
  incompatibilities incrementally. Expected to cut cold-build time
  significantly.

### Stress-test the OCR pipeline
- `OcrSessionLifecycleRaceTest` (PR #103) covers 4 lifecycle races.
  Missing:
  - SHM pool exhaustion translation to typed `RESOURCE_EXHAUSTED`.
  - 1000-frame stress in a single session (catches accumulated state
    leaks).
  - Rotation/ROI under stress (PR #92 added the surface but only one
    rotation value is exercised).

### Hardcoded UI strings
- PR #100 + #92 reviews surfaced that several user-facing strings live
  in Kotlin source rather than `res/values/strings.xml`. Low priority,
  but matters for i18n later.

### Compose UI preview content
- Preview data in `SessionDetailScreen.kt:417-451` contains
  prompt-and-output-like strings. Conflicts with the metadata-only
  privacy norm even though it's preview-only. Replace with obvious
  Lorem-ipsum-flavored placeholders.

### Code-level lint rules
- Custom lint rules to enforce the conventions the audits found us
  manually checking:
  1. No direct `android.util.Log` in `:app` production code outside
     `MindlayerLog.kt`.
  2. Engine/IPC/service exception logs on native paths must call
     `safeLabel()` and pass `throwable = null`.
  3. Static guard against `INTERNET` / telemetry / cloud-fallback
     dependency additions.
  4. AIDL byte-diff task alongside `AidlContractDriftTest`.

### Cross-repo perm-name typo
- First-party callers `StarlitCoffee` and `Ledgit` have been updated
  (StarlitCoffee#2, Ledgit#3 merged in earlier sessions). Verify on
  next first-party release that the bind actually succeeds on API 31+.

---

## Phase 8 — Speculative

Items the synthesis acknowledged but didn't dispatch.

### Per-call GPU/NPU opt-in for OCR
After Phase 6's coexistence checklist is real-device-validated, allow
per-call opt-in to GPU/NPU for OCR by passing
`acceleratorIntent = "GPU"` (or `"NPU"`) to `OcrSession`. Phase 6 builds
the foundation; Phase 8 turns the knob.

### Multimodal embedding inputs
LiteRT-LM 0.12 added vision/audio backend configuration to `EngineConfig`.
Mindlayer stages multimodal media via SHM today but doesn't configure
these backends. Phase 8: route multimodal through accelerator/thermal
policy properly.

### Coverage gate in CI
PR #103 (Cluster F) added 63 tests; coverage tooling (`jacocoTestReport`
or Kover) is wired *informationally* — there's no CI gate. Phase 8 work:
add a coverage gate at, say, 75% line coverage on `:app` source
(excluding generated AIDL + Compose + build config). Trial it as
non-blocking first, then promote.

### Crashlytics-like crash reporting (privacy-preserving)
Today crashes surface only via `MlHealthRecorder` death counters. A
privacy-preserving crash reporter (metadata + safe-labeled exception
class, no prompt/output) would close a real diagnostic gap. Mindlayer's
no-INTERNET invariant means this would need to ship as a local-only
ring buffer with explicit user-initiated export (similar to
`DiagnosticExporter`).

---

## How this list stays current

- **New entries:** anyone who finds a non-trivial outstanding item adds
  it here in the same PR that surfaces it.
- **Closed entries:** when a Phase 6/7/8 item ships, the PR that
  finishes it deletes the entry (with a `CHANGELOG.md` line).
- **Audit-derived items:** see the audit reports in the session-state
  `files/exploration-*-gpt55.md` for the original signal — keep the
  citation alive when porting items here.
- **Doc-only updates:** also acceptable. The Roadmap is not gated on
  code changes.

---

## Reading map

| Want to understand … | Read … |
|---|---|
| The trust model (who can call what) | `docs/AUTHORIZATION.md` |
| The three-stack coexistence risk | `docs/LITERT_COEXISTENCE.md` |
| The OCR SDK surface | `docs/OCR_API.md` and `SDK_INTEGRATION.md` § OCR |
| The AIDL capability registry | `docs/AIDL_STABILITY.md` |
| The release process | `RELEASE.md` |
| The repo conventions (logging, AIDL mirror, native loads) | `.github/copilot-instructions.md` |
| The architectural rationale per subsystem | `.github/context/ARCHITECTURE.md` |
| The dev environment + JDK gotcha | `.github/context/DEVELOPMENT.md` |
| Code-of-conduct + contributing | `CONTRIBUTING.md` |
| What's changed and when | `CHANGELOG.md` (this file is the *next-up* list, not the changelog) |
