# Changelog

All notable changes to Mindlayer are documented in this file.

The project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0-alpha01] - 2026-05

### Changed (breaking)
- **SDK v1 API surface finalized.** Canonical builders `infer { }`, `ocr { }`,
  and `openSession { }` are now implemented (previously stubs that threw).
  Terminals `awaitText()` / `awaitJson()` / `awaitToolCalls()` on
  `InferenceHandle` are functional.
- **`InferenceEvent` merged/renamed.** The old `MindlayerEvent` stream type is
  removed in favour of the single `sealed class InferenceEvent`
  (`Started` / `TextDelta` / `ToolCall` / `Metrics` / `Error` / `Done`).
- **Deprecated cancellation API removed.** Public `InferenceHandle.cancel()` and
  `isCancelled` are gone; teardown is handled internally
  (`Conversation.close()` / session lifecycle).
- **`prewarm()` and `getEngineInfo()` promoted onto the `Mindlayer`
  interface** so they are reachable from the value returned by `connect()`.

### Notes
- See `docs/SDK_V1_MIGRATION.md` for the full migration guide and the list of
  intentional alpha deviations (eager `infer` bridge, dropped `seed`,
  no tool-calling via `infer { }`, OCR bbox denormalization).

## [Unreleased]

### Added
- **Gemma 4 audio contract surfaced.** New `GemmaAudioSpec` in `:shared`
  documents the audio frontend constants (16 kHz mono float32, 32 ms
  frames, 30 s per-clip cap, 25 tok/s budget) sourced directly from
  Google's [Gemma audio capabilities page](https://ai.google.dev/gemma/docs/capabilities/audio).
  Cross-references in `app/.../engine/ContextBudget.kt` now forward to
  the shared constants so token math has one source of truth.
- **Canonical ASR helper.** `Mindlayer.transcribe(audio, language)`
  (no caller-supplied prompt) uses Google's recommended ASR prompt
  via the new `com.adsamcik.mindlayer.sdk.GemmaAudioPrompts` builder.
  Existing `transcribe(prompt, audio)` is unchanged for callers who
  want general-purpose audio understanding.
- **`FEATURE_AUDIO_INPUT` capability flag** (`"audio_input"`)
  advertised by `MindlayerMlService`. SDKs can probe it via
  `ServiceCapabilities.supports(FEATURE_AUDIO_INPUT)` before issuing
  audio inferences. Documented in `docs/AIDL_STABILITY.md` and
  `docs/AUDIO.md`.
- **`docs/AUDIO.md`** — single-page reference for the audio surface:
  supported MIME types, limits, quick-start code, and the explicit
  "not yet supported" list (multi-audio prompts, ≥30 s clips,
  specialized translation helper).
- **`docs/ROADMAP.md`** — single source of truth for outstanding work
  across Phases 6-8 (device-gated `IS_PRODUCTION_READY` flip criteria,
  real model artifact pipeline, ICDAR2015 numeric validation harness,
  Phase 7 product polish backlog, Phase 8 speculative items).
  Cross-linked from `README.md`.

### Changed
- **`IpcInputValidator` audio duration cap tightened** from 60 minutes
  to `GemmaAudioSpec.MAX_DURATION_MS` (30 s) — matches Gemma 4's
  documented per-clip maximum. Applies to both
  `validateAudioTransfer(AudioTransfer)` and the multi-part
  `validateAudioPart(MediaPart)` path. Callers with longer recordings
  must chunk client-side; the engine would have silently truncated
  above 30 s anyway.

### Fixed
- **`MediaTransfer` SharedMemory path on targetSdk 30+** — `fromBitmap` /
  `fromAudioBytes` previously used reflection to call the hidden non-SDK
  method `SharedMemory.getFileDescriptor()`, which Android's hidden-API
  enforcement denies for apps targeting SDK 30 or later. The denial threw
  silently inside the SDK and aborted every vision/audio inference call.
  Replaced the reflection with a public Parcelable round-trip
  (`shm.writeToParcel` → `parcel.readFileDescriptor`) that produces an
  independent `ParcelFileDescriptor` with no hidden-API access. Wire format
  on the AIDL boundary is unchanged; `SharedMemoryPool.reconstructSharedMemory`
  on the service side continues to work as-is. Added an instrumented
  regression test (`MediaTransferInstrumentedTest`) covering both image and
  audio paths with a full round-trip through SharedMemory reconstruction.

### Added
- **Structured output DSL** — `SessionConfigBuilder.jsonOutput { ... }` wraps
  the existing `extraContextJson` structured-output contract with a typed,
  discoverable API. Supports `PromptAndValidate` (default) and `ToolRouting`
  strategies. Zero-risk opt-in: services that predate the structured-output
  feature ignore the envelope and degrade to unstructured generation. See
  `JsonOutputBuilder` KDoc for usage.
- **Launcher icon** — adaptive icon (foreground + background + monochrome)
  with a stylized "M" glyph on the Indigo40 brand background.
- **Release signing wiring** — `app/build.gradle.kts` reads a local
  `keystore.properties` to drive the `release` signingConfig; releases are
  produced unsigned when the keystore is absent (CI fallback).
- **R8 / resource shrinking** on release builds, with comprehensive keep
  rules in `app/proguard-rules.pro` (AIDL, Room, SQLCipher, kotlinx.serialization,
  Parcelables, LiteRT-LM JNI) and consumer rules in `sdk/consumer-rules.pro`
  and `shared/consumer-rules.pro` for downstream apps.
- **Backup / data-extraction rules** — `allowBackup=false` with XML fallback
  policies that exclude the encrypted logs DB, shared prefs, and files from
  cloud backup + device transfer.
- **App theme** — `Theme.Mindlayer` declared in `res/values/themes.xml`,
  replacing the platform theme override on `MainActivity`.
- `RELEASE.md` — complete local build + sign + upload flow for Play.
- `keystore.properties.example` template (gitignored real file).

### CI
- `.gitignore` now excludes `keystore.properties`.

### Tests
- Added instrumented androidTest coverage for DbKeyProvider (:app, :sdk) and
  EncryptedDbWiring (:sdk). Closes #1.
- Added `JsonOutputBuilderTest` covering schema parsing, strategy wire
  format, validation failures, and `extraContext` merge semantics.

### CI
- Added emulator-backed `instrumented-tests` job running
  `connectedDebugAndroidTest` on API 33.

## [0.3.0] — 2026-04-18

### Added
- **Multi-model registry** (`ModelRegistry`, `ModelInfo`) — discovers `.litertlm`
  models from `filesDir`, external storage, cache, `/data/local/tmp`, and Play
  AI-Pack assets; auto-selects the largest as default. Per-session model
  override supported via `SessionConfig`.
- **Caller security layer** — `AllowlistStore` (cross-process JSON + `FileLock`),
  `CallerVerifier` (signing-cert SHA-256 pinning), `RateLimiter` (token-bucket,
  default 60 RPM per UID).
- **Encrypted log database** — SQLCipher-backed Room DB protected by an
  AndroidKeystore-wrapped 32-byte passphrase via `DbKeyProvider` (app + sdk).
  Fail-closed if Keystore is unavailable.
- **Conversation-first SDK API** — `Conversation`, `ConversationConfig`,
  `ConversationSummary`, `HistoryStore`. 14-day default expiration with
  override; conversation history viewing and pruning.
- **Dashboard expansion** — Recent Logs screen, Session History screen,
  Session Detail screen with chat-bubble user/model rendering, Allowed Apps
  card, branded Material 3 theme, system bar insets, pull-to-refresh, thermal
  / memory / status / test cards, GPU failure observability.
- **Thermal → backend switch feedback loop** wired end-to-end.
- **AIDL changes** — `IMindlayerService` extended for multi-model selection
  and security/allowlist surface.

### Changed
- `EngineManager`, `SessionManager`, `InferenceOrchestrator`, `ServiceBinder`
  significantly reworked for multi-model + security pipeline.
- Logging tooling now logs user prompts and model responses for session
  detail playback.

### Fixed
- Room migration conflict that crashed SDK consumers.
- `ServiceBinder` handling of blank `modelId` / zero `sizeBytes`.
- Memory pre-flight check restored; backend removed from consumer API
  surface.

### Tests / Quality
- Test count: **825 → 850** (+25 unit tests).
- New: `ModelRegistryTest`, `RecentLogsViewModelTest`,
  `SessionHistoryViewModelTest`, `SessionDetailViewModelTest`,
  `AllowlistStoreTest`, `CallerVerifierTest`, `RateLimiterTest`,
  `ConversationTest`, `EncryptedDbWiringTest`,
  `SessionDiagnosticsFormattingTest`.
- Compiler warnings cleared across `:app` main and test sources, and `:sdk`
  test sources.

### Known gaps
- `DbKeyProvider` (app + sdk) and SQLCipher wiring rely on AndroidKeystore /
  native libraries unavailable on JVM/Robolectric. Instrumented (`androidTest`)
  coverage tracked as a follow-up.

## [0.2.0] — 2026-03

### Added
- `prewarm()` on the SDK + AIDL interface.
- One-shot convenience methods on `Mindlayer`.
- `InferenceHandle` for chat methods.
- Multi-model design-review fixes (P0–P5).

### Fixed
- `TurnDao` atomic `nextSeq` race condition.

## [0.1.1] — 2026-02

### Fixed
- Flaky CI tests: increased timeouts, added cleanup delay.
- Remaining flaky cleanup-verify failure in CI.

### Added
- Unix `gradlew` script for CI.
- GitHub Actions CI/CD pipelines.
- Automated SDK publishing to GitHub Packages from git tags.

## [0.1.0] — 2026-02

Initial release.
- Mindlayer on-device LLM service (Gemma 4 E2B via LiteRT-LM).
- 3-plane hybrid IPC: AIDL/Binder + SharedMemory + ParcelFileDescriptor pipe.
- Foreground service with `specialUse`; `START_NOT_STICKY`.
- 4-band thermal controller (COOL → WARM → HOT → CRITICAL).
- Backend fallback: GPU → CPU; NPU when available.
- Structured `MindlayerLog` wrapper with request/session ID prefixing.
- Initial test suite (757 tests).
