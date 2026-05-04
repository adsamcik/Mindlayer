<!-- context-init:version:3.1.0 -->
<!-- context-init:managed -->

# Mindlayer — Copilot Instructions

> Action-oriented summary. For detail see `.github/context/ARCHITECTURE.md`,
> `.github/context/PATTERNS.md`, `.github/context/DEVELOPMENT.md`,
> `docs/AUTHORIZATION.md`, and `SDK_INTEGRATION.md`.

## What this is

Android service app (`com.adsamcik.mindlayer.service`) that loads a single LLM (Gemma 4 E2B via LiteRT-LM) and serves inference to **trusted first-party client apps** over IPC. It is **not** a public SDK; co-signed clients are the audience. Architecture must remain extensible to allow third-party callers later, gated by user approval.

## Tech stack

- Kotlin 2.3.0 / JDK 17 / AGP 8.9.3 / `compileSdk 36`, `minSdk 26`
- Modules: `:app` (service+dashboard), `:sdk` (client SDK), `:shared` (wire types), `:gemma_model` (Play AI Pack)
- LiteRT-LM 0.10.0, Jetpack Compose (BOM 2025.04.01), Room 2.7.1 + SQLCipher 4.6.1
- Tests: JUnit 4, MockK, Robolectric (sdk=33), Turbine, kotlinx-coroutines-test

## Hard rules

| ✅ Do | ❌ Don't |
|---|---|
| Log via `MindlayerLog.{d,i,w,e}(TAG, msg, requestId, sessionId)` | `android.util.Log` directly in service code |
| Persist log metadata only (`tokenCount`, ids, timing) | Log/store prompt text or model output — ever |
| Use `kotlinx.serialization` for JSON | Add new `Gson`/`org.json` callers (Gson is allowed only at LiteRT-LM tool-call boundaries) |
| Per-session `Mutex` on sends; concurrent sessions OK | Share a single `Conversation` across coroutines without a mutex |
| `Conversation.cancelProcess()` (LiteRT-LM) to stop inference | Rely on Flow cancellation alone — native work continues |
| Cross-process state via `filesDir` + atomic-rename + `FileLock` (see `AllowlistStore.kt`) | `SharedPreferences` for cross-process state |
| Send media via `SharedMemoryPool` (`ImageTransfer`/`AudioTransfer`) | Pack >~1 MB into AIDL parcels — Binder limit is 1 MB |
| Mirror AIDL files byte-identical between `app/src/main/aidl/` and `sdk/src/main/aidl/` | Edit only one side — guaranteed `BadParcelableException` |
| Promote service to FGS `specialUse` only during active inference | Stay foreground when idle |
| Use `Throwable.safeLabel()` for inference-path exceptions, pass `throwable = null` | Log full stack traces from native LiteRT-LM errors — they can embed prompt text |

## Trust model (read this before touching auth)

Today, every AIDL entry point runs a 4-stage gate: **identity → allowlist → rate limit → ownership**. Default-deny: even co-signed first-party apps are rejected on first connect and need a one-time dashboard approval. The signature-level `BIND_ML_SERVICE` permission is the *first* line; the user-approved allowlist runs underneath as defense-in-depth. Self-UID bypasses the allowlist+rate gate so the dashboard can poll `:ml` over its own AIDL.

The product **intent** is for first-party (co-signed) apps to skip user approval (via a future `AllowlistStore.seedIfEmpty(...)` from `MindlayerMlService.onCreate`) while keeping the approval flow intact for any future third-party support. Implement that hook when needed; do **not** remove `recordPending`, `CallerVerifier`, `RateLimiter`, ownership checks, or binder-death linkage.

See `docs/AUTHORIZATION.md` for the full data flow, failure modes, and threat model. Path-specific rules live in `.github/instructions/security.instructions.md`.

## Cross-module contract synchronization

Whenever you change any of these, update **all** producers, consumers, and tests in the same change:

- AIDL files (`app/src/main/aidl/com/adsamcik/mindlayer/*.aidl` ↔ `sdk/src/main/aidl/com/adsamcik/mindlayer/*.aidl`)
- `StreamEvent` / `StreamEventType` / `StreamHeader` (`shared/.../Protocol.kt`) and the writer/reader pair (`TokenStreamWriter` / `TokenStreamReader`)
- Frame format / `MAX_FRAME_BYTES` (duplicated in writer + reader on purpose)
- `BIND_ML_SERVICE` permission name, service `<intent-filter>`, or process name `:ml`

The PR template asks for explicit confirmation that AIDL changes are mirrored. Don't merge half-changes.

## Build / test / commit

```bash
# Unit tests across all modules (run before submitting)
./gradlew :app:testDebugUnitTest :sdk:testDebugUnitTest :shared:testDebugUnitTest

# Lint (matches CI; release builds break on `NewApi` / `MissingTranslation`)
./gradlew lintDebug

# Build a debug APK
./gradlew :app:assembleDebug
```

⚠️ CI's "Set up JDK 17" step actually installs **Java 21** by design — see `.github/context/DEVELOPMENT.md#%EF%B8%8F-the-java-21-test-runtime-gotcha`. Don't "fix" it.

**Commits use Conventional Commits**: `feat(scope):`, `fix:`, `ci:`, `test:`, `docs:`, `refactor:`, `chore(release):`. Keep them atomic, run tests before committing, and include this trailer for AI-authored commits:

```
Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
```

## Common tasks

| Task | Where to look |
|---|---|
| Add an AIDL method | `app/src/main/aidl/.../IMindlayerService.aidl` AND `sdk/src/main/aidl/.../IMindlayerService.aidl`; implement in `ServiceBinder.kt` (with `authorizeCall()` first); expose in `Mindlayer.kt`. |
| Add a stream event type | `shared/.../Protocol.kt::StreamEventType` constant; emit in `TokenStreamWriter`; handle in `TokenStreamReader`; update tests in both `:app` and `:sdk`. |
| Add a Room column on the SDK history DB | Bump `MindlayerDatabase.version`, add a `Migration`, update `Entities.kt`. SQLCipher cross-install backup is unreadable by design. |
| Add a logged event | Add `LogEvent`/`LogCategory` enum value; add a builder on `LogRepository`; never include prompt or output text. |
| Add a security/auth gate | Read `docs/AUTHORIZATION.md` first. Don't bypass `authorizeCall()`. |

## Path-specific guidance

Loaded automatically by Copilot via `applyTo` frontmatter:

- `.github/instructions/aidl.instructions.md` — AIDL drift, mirroring, Java syntax
- `.github/instructions/security.instructions.md` — auth invariants in `service/` + `security/`
- `.github/instructions/engine.instructions.md` — LiteRT-LM lifecycle, thermal/memory bands
- `.github/instructions/ipc.instructions.md` — pipe framing, SharedMemory, wire protocol
- `.github/instructions/tests.instructions.md` — Robolectric, MockK, Turbine patterns
