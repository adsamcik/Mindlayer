# Mindlayer documentation

Reference docs for the Mindlayer on-device LLM service, organized by topic.
For a high-level overview start with the [root README](../README.md); for
contributor setup see [CONTRIBUTING](../CONTRIBUTING.md).

## Architecture & security

- [CONSENT_ARCHITECTURE](architecture/CONSENT_ARCHITECTURE.md) — per-app user-consent trust model (current).
- [AUTHORIZATION](architecture/AUTHORIZATION.md) — legacy authorization reference.
- [AIDL_STABILITY](architecture/AIDL_STABILITY.md) — AIDL wire-contract stability rules.
- [LITERT_COEXISTENCE](architecture/LITERT_COEXISTENCE.md) — running LiteRT and LiteRT-LM side by side.

## SDK

- [SDK_INTEGRATION](sdk/SDK_INTEGRATION.md) — integrating the client SDK into a host app.
- [SDK_V1_MIGRATION](sdk/SDK_V1_MIGRATION.md) — migrating to the canonical v1 SDK surface.
- [INFERENCE_SDK_POLISH](sdk/INFERENCE_SDK_POLISH.md) — inference SDK design notes.
- [EMBEDDINGS_SDK_POLISH](sdk/EMBEDDINGS_SDK_POLISH.md) — embeddings SDK design notes.

## OCR

- [OCR_API](ocr/OCR_API.md) — OCR API surface.
- [OCR_SDK_GUIDE](ocr/OCR_SDK_GUIDE.md) — OCR usage guide.
- [OCR_BENCHMARK](ocr/OCR_BENCHMARK.md) — OCR benchmark fixtures and methodology.
- [PADDLEOCR_GPU_INVESTIGATION](ocr/PADDLEOCR_GPU_INVESTIGATION.md) — PaddleOCR GPU acceleration investigation.

## Engine & runtime

- [THERMAL_POLICY_ON_UNAVAILABLE](engine/THERMAL_POLICY_ON_UNAVAILABLE.md) — thermal policy when telemetry is unavailable.
- [THINKING](engine/THINKING.md) — thinking-mode behavior.
- [MEMORY_TIERS_EMPIRICS](engine/MEMORY_TIERS_EMPIRICS.md) — memory-tier empirical measurements.
- [AUDIO](engine/AUDIO.md) — audio input handling.

## Models & dev tooling

- [MODEL_SHAS](models/MODEL_SHAS.md) — pinned model SHA-256 digests.
- [DEV_MODELS](models/DEV_MODELS.md) — sideloading models for local development.

## Project

- [ROADMAP](project/ROADMAP.md) — product roadmap.
- [RELEASE](project/RELEASE.md) — release and signing flow.
- [THIRD_PARTY_FUTURE](project/THIRD_PARTY_FUTURE.md) — potential future third-party integrations.

## Archive

Point-in-time reports kept for historical reference (not living docs):

- [FULL_SURFACE_VALIDATION_REPORT](archive/FULL_SURFACE_VALIDATION_REPORT.md)
- [ocr-validation-report-2026-05-31.json](archive/ocr-validation-report-2026-05-31.json)
- [ocr-validation-report-2026-05-31-final.json](archive/ocr-validation-report-2026-05-31-final.json)
