# Mindlayer Roadmap

Last updated: 2026-10-15. This describes the current source and separates
remaining release qualification from optional work. Historical phase plans
and old validation totals are not current release evidence.

## Implemented

- Chat and multimodal inference use LiteRT-LM 0.17.1; EmbeddingGemma and
  PaddleOCR use base LiteRT 2.2.0. Native packaging guards check ABI,
  namespace and library collisions.
- The canonical SDK exposes streaming `infer`, `ocr`, multi-frame `ocrSession`,
  `embed`, and owned sessions. See [SDK migration](../sdk/SDK_V1_MIGRATION.md).
  Manual tool-result submission, explicit deferred embeddings, embedding/OCR
  deadlines and capability-gated sampler seeds are completed for `1.0.0-alpha.10`.
- OCR and embedding production feature flags are enabled. The
  [OCR driver](../../samples/ocr-driver) exists; callers still need the relevant
  model assets installed and their runtime capabilities advertised.
- User consent, signing-certificate pinning, ownership checks and per-app
  quotas protect service calls. Inference stays on device, without production
  network permission or cloud fallback.
- Completed deferred embedding results survive client disconnect until
  acknowledgement, expiry or quota eviction. Revocation purges results and
  retries interrupted cleanup before reapproval.
- On-demand Play Asset Delivery is implemented for all model families. Gemma's
  two verified fragments are reconstructed in private storage. Developer
  installation remains a separate sideload workflow.
- Configuration cache and custom lint are implemented. Current build versions
  live in `gradle/libs.versions.toml` and `gradle/wrapper/gradle-wrapper.properties`.

## Remaining release qualification

### Code-only alpha releases

Prepare a new version and dated changelog entry, run the relevant release gates,
and publish all SDK modules and the code-only APK from the same source tag when
publication is authorized. Do not reuse an existing version. See
[release instructions](RELEASE.md).

Compilation, unit tests, lint and emulator runs establish only their respective
coverage. Model-dependent tests can skip when assets are absent. A green CI
summary alone does not prove native model inference or Play delivery.

### Current native runtime

The recorded August CPU coexistence run used LiteRT-LM 0.16.1. It does not
qualify the current 0.17.1 runtime. Perform a focused ARM64 smoke of the enabled
native inference paths and changed lifecycle behavior, retaining requested and
observed backend identity. Missing GPU/NPU evidence remains explicit; broad
multi-device benchmark matrices are optional for this personal project.

See [runtime coexistence](../architecture/LITERT_COEXISTENCE.md). The archived
OCR report also includes successful error-handling and skipped-LLM scenarios;
its pass count is not proof that every model completed inference.

### Model-bearing public Play distribution

- Complete the [model provenance ledger](../models/model-artifact-provenance.json):
  immutable sources, conversion commands and versions, quantization/calibration,
  redistribution evidence, and embedded tokenizer/chat-template identity.
  `releaseReady` remains false; `validate-model-provenance.ps1 -RequireComplete`
  must stay blocked until the evidence is supplied. Its runtime identity tracks
  the selected dependency; that version alone does not qualify the artifacts.
- Provision the vetted local model cache, then build and verify the signed AAB
  following [RELEASE.md](RELEASE.md). Earlier signing/build success establishes
  packaging, not current Play delivery or model provenance.
- Validate on-demand installation, integrity, upgrade and recovery through
  Play internal testing or Internal App Sharing before public Play rollout.

These model-distribution gates are separate from a code-only SDK/debug APK
alpha release. Do not mark them complete from sideload or emulator evidence.

## Optional follow-ups

- Measure current native memory when it informs a device/support decision.
  The [Play memory harness](../engine/PLAY_MEMORY_BENCHMARK.md) exists; historical
  emulator PSS estimates do not establish current ARM64 accelerator memory.
- Expand numeric OCR evaluation with a representative held-out corpus and
  tighten thresholds only when measured results support it.
- Keep [model portfolio experiments](../models/MODEL_PORTFOLIO_AGENT_HANDOFF.md)
  isolated from production defaults. New models, embedding compression and
  process-isolation changes need evidence of a concrete workload benefit.

Update this file when a remaining item is completed; preserve actual validation
boundaries instead of carrying stale phase checklists forward.
