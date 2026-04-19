# Changelog

All notable changes to Mindlayer are documented in this file.

The project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
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
