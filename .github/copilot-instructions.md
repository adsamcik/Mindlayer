<!-- context-init:version:3.1.0 -->
<!-- context-init:managed -->

# Mindlayer — Copilot Instructions

> Action-oriented summary. For detail see `.github/context/ARCHITECTURE.md`,
> `.github/context/PATTERNS.md`, `.github/context/DEVELOPMENT.md`,
> `docs/AUTHORIZATION.md`, and `SDK_INTEGRATION.md`.

## What this is

Android service app (`com.adsamcik.mindlayer.service`) that loads a single LLM (Gemma 4 E2B via LiteRT-LM) and serves inference to **trusted first-party client apps** over IPC. It is **not** a public SDK; co-signed clients are the audience. Architecture must remain extensible to allow third-party callers later, gated by user approval.

## Tech stack

- Kotlin 2.3.21 / JDK 17 bytecode (Gradle tests on JDK 21) / AGP 9.2.1 / `compileSdk 37`, `minSdk 26`, `targetSdk 36`
- Modules: `:app` (service+dashboard), `:sdk` (client SDK), `:sdk-camerax` (optional CameraX adapter), `:shared` (wire types), `:gemma_model`, `:gemma_embed_model`, `:paddleocr_model` (install-time AI packs)
- LiteRT-LM 0.12.0 + base LiteRT 2.1.5 for EmbeddingGemma, Jetpack Compose (BOM 2026.04.01), Room 2.8.4 + SQLCipher 4.15.0
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
| Mirror AIDL **interface** files (`IMindlayerService.aidl` + `IClientCallback.aidl`) byte-identical between `app/src/main/aidl/` and `sdk/src/main/aidl/` | Edit only one interface side — guaranteed `BadParcelableException`. **Parcelables live in `:sdk` only** — do NOT copy them into `:app`. |
| Promote service to FGS `specialUse` only during active inference | Stay foreground when idle |
| Use `Throwable.safeLabel()` for inference-path exceptions, pass `throwable = null` | Log full stack traces from native LiteRT-LM errors — they can embed prompt text |
| Keep `:app` + `:sdk` manifests free of `android.permission.INTERNET` | Add network permissions, Play Services deps, telemetry SDKs, or cloud fallback paths |
| Use Apache-2.0 / MIT / BSD-3-licensed on-device runtimes (PaddleOCR models, LiteRT, LiteRT-LM, ZXing) | Adopt closed-source SDKs whose terms allow vendor telemetry (e.g. ML Kit) |
| Use LiteRT (already in repo at `libs.litert`) as the single on-device inference runtime | Add a second inference runtime (e.g. ONNX Runtime Android, Paddle Lite) that competes with LiteRT for CPU / GPU / memory in the Mindlayer process |
| Install on device with `scripts/dev-install.{ps1,sh}` (builds code-only APK, preserves on-device models) | `adb install -r app-debug.apk` blindly (debug APK does **not** bundle the AI packs; you'll get `MLERR:1003:Model file missing` at runtime) or `adb uninstall …mindlayer.service.debug` (wipes the ~3 GB of pushed models in externalFilesDir — re-pushing takes minutes) |

## Privacy / offline / security — product invariants

Mindlayer is **fully on-device, network-free, and first-party-trust-gated**. These are product-level guarantees; weakening any of them is a release blocker. New features must:

- Add no `INTERNET` / network permission to `:app` or `:sdk` manifests.
- Add no third-party telemetry, analytics, or cloud-fallback paths.
- Keep camera frames, recognized text, and structured model output in RAM only — never `filesDir` / `cacheDir` / external storage. Caller may persist via the existing SQLCipher-backed history.
- Verify model file integrity (pinned SHA-256) at first load.
- Validate every new parcelable field in `IpcInputValidator` with explicit bounds before the engine sees it.

See `.github/instructions/privacy-offline.instructions.md` for the full ruleset and the 10-item pre-merge checklist.

## Trust model (read this before touching auth)

Today, every AIDL entry point runs a 4-stage gate: **identity → allowlist → rate limit → ownership**. Default-deny: even co-signed first-party apps are rejected on first connect and need a one-time dashboard approval. The signature-level `BIND_ML_SERVICE` permission is the *first* line; the user-approved allowlist runs underneath as defense-in-depth. Self-UID bypasses the allowlist+rate gate so the dashboard can poll `:ml` over its own AIDL.

The product **intent** is for first-party (co-signed) apps to skip user approval (via a future `AllowlistStore.seedIfEmpty(...)` from `MindlayerMlService.onCreate`) while keeping the approval flow intact for any future third-party support. Implement that hook when needed; do **not** remove `recordPending`, `CallerVerifier`, `RateLimiter`, ownership checks, or binder-death linkage.

See `docs/AUTHORIZATION.md` for the full data flow, failure modes, and threat model. Path-specific rules live in `.github/instructions/security.instructions.md`.

## Cross-module contract synchronization

Whenever you change any of these, update **all** producers, consumers, and tests in the same change:

- AIDL **interface** files (`app/src/main/aidl/com/adsamcik/mindlayer/{IMindlayerService,IClientCallback}.aidl` ↔ `sdk/src/main/aidl/com/adsamcik/mindlayer/{IMindlayerService,IClientCallback}.aidl`). **Parcelable AIDL files live only in `:sdk`** — `:app` pulls them in transitively via `implementation(project(":sdk"))`.
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

⚠️ CI's `Set up JDK 21 (compile target 17)` steps install **Java 21** by design — see `.github/context/DEVELOPMENT.md#%EF%B8%8F-the-java-21-test-runtime-gotcha`. Don't "fix" it.

**Commits use Conventional Commits**: `feat(scope):`, `fix:`, `ci:`, `test:`, `docs:`, `refactor:`, `chore(release):`. Keep them atomic, run tests before committing, and include this trailer for AI-authored commits:

```
Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
```

## On-device install + AI Pack delivery — read this before touching adb

The three AI models (`gemma-4-E2B-it.litertlm` ~2.4 GB, EmbeddingGemma ~250 MB, PaddleOCR PP-OCRv5 ~12 MB) are **deliberately not in `:app`**. They ship to end-users as Play [install-time AI Asset Packs](https://developer.android.com/google/play/on-device-ai/asset-delivery) (`:gemma_model`, `:gemma_embed_model`, `:paddleocr_model`), and to devs via a sideload script. **Don't try to bundle them into the debug APK** — the AAB-only delivery is the design.

Concretely this means:

- `./gradlew :app:assembleDebug` produces a ~75 MB code-only APK with **no model assets**. Installing that APK on a fresh device and running an inference returns `MLERR:1003:Model file missing`.
- Models live on device under `/sdcard/Android/data/com.adsamcik.mindlayer.service{,.debug}/files/` (the service's `externalFilesDir`). The runtime registries scan it on debuggable builds via `Origin.EXTERNAL_FILES`.
- `adb install -r app-debug.apk` **does** preserve `externalFilesDir`.
- `adb uninstall com.adsamcik.mindlayer.service.debug` **wipes** `externalFilesDir` along with the APK. Re-pushing the 2.4 GB Gemma model is 30+ seconds on USB-3 and several minutes over an emulator's qemu pipe. **Don't do this casually.**
- The dashboard's "Chat (LLM) → Run Test" button is the quickest on-device confirmation that the model is present and the engine loads — it fails fast with a clear status string when models are missing.

### Canonical dev install loop

Use the wrapper. It builds the code-only APK, `adb install -r`'s it, and pushes only the missing model files (size-checked):

```powershell
# Windows PowerShell
$env:MINDLAYER_MODEL_CACHE = 'D:\mindlayer-models'   # one-time per machine
.\scripts\dev-install.ps1                            # full loop
.\scripts\dev-install.ps1 -SkipBuild -SkipInstall    # just (re-)push models
.\scripts\dev-install.ps1 -DryRun                    # preview without touching adb
```

```bash
# macOS / Linux
export MINDLAYER_MODEL_CACHE=/data/mindlayer-models
./scripts/dev-install.sh
./scripts/dev-install.sh --skip-build --skip-install
./scripts/dev-install.sh --dry-run
```

For direct model pushes (skipping the build+install phases), use the underlying `tools/dev-models/push-models.{ps1,sh}` script — see `docs/DEV_MODELS.md` for cache layout, where to source the raw model files, and the `/data/local/tmp/` fallback.

### Forbidden moves (you will lose model bytes)

- `adb install -r app-debug.apk` **without** previously running `dev-install` → model-less install, every inference fails.
- `adb uninstall com.adsamcik.mindlayer.service.debug` → wipes 2.4 GB. Re-pushing is slow. If you need a clean slate, `adb shell pm clear` keeps externalFilesDir and is much cheaper.
- Hand-rolling a 4 GB `assembleDebug` that bundles all three packs to "make it just work" → release CI then takes 30 minutes to bundle and the dev iteration loop is destroyed for everyone else.

### When models are missing on a CI / fresh emulator

The `:app:androidTest` job in `.github/workflows/ci.yml` provisions the PaddleOCR pack via `${{ secrets.PADDLEOCR_MODELS_ARCHIVE_B64 }}`. Tests that need Gemma or EmbeddingGemma on hardware skip with `assumeTrue` (or fail loudly with `MLERR:1003`) — wrap any new on-device test that depends on real models the same way so the suite stays green on a model-less runner.

## Worktree + PR workflow

All non-trivial work — features, fixes, refactors, dep bumps, even docs of a certain size — runs in a **dedicated git worktree on its own feature branch**, lands on origin as a **PR**, and is reviewed before merge. Direct commits to local `main` are never the destination; they are at most a stop on the way to a PR. The pattern lets multiple agents work simultaneously without colliding on the working tree, keeps `main` in a known-clean state for diagnostics, and makes "abandon this attempt" cheap (just remove the worktree).

### Recipe

```powershell
# 1. Branch off the upstream you're targeting (origin/main for clean PRs;
#    an in-flight feature branch on origin if you're stacking).
git -C C:\Users\adam-\GitHub\Mindlayer worktree add `
  C:\Users\adam-\GitHub\Mindlayer-<short-name> -b <branch-name> origin/main
Copy-Item C:\Users\adam-\GitHub\Mindlayer\local.properties `
  C:\Users\adam-\GitHub\Mindlayer-<short-name>\

# 2. Put the JDK 21 toolchain on PATH for tests (LiteRT-LM `Backend` is
#    class-file v65; tests fail on JDK 17). The Gradle wrapper just runs
#    `java.exe` from PATH and is JDK-agnostic — see DEVELOPMENT.md.
$env:PATH = "C:\Users\adam-\.gradle\jdks\jetbrains_s_r_o_-21-amd64-windows.2\bin;$env:PATH"

# 3. Work, test, commit in the worktree.
cd C:\Users\adam-\GitHub\Mindlayer-<short-name>
.\gradlew.bat :app:testDebugUnitTest --console=plain --no-daemon
git add <files> ; git commit -m "..."

# 4. Push the feature branch (NEVER push directly to origin/main).
git push origin <branch-name>

# 5. Open a PR (via `gh pr create` or the URL printed by `git push`).

# 6. After the PR is merged on GitHub, clean up locally.
git -C C:\Users\adam-\GitHub\Mindlayer worktree remove `
  C:\Users\adam-\GitHub\Mindlayer-<short-name>
git -C C:\Users\adam-\GitHub\Mindlayer branch -D <branch-name>
git -C C:\Users\adam-\GitHub\Mindlayer fetch --prune origin   # remove the deleted remote ref
```

### Branch + worktree naming

| Kind | Branch | Worktree dir |
|---|---|---|
| Feature | `feat/<scope>-<summary>` | `Mindlayer-<summary>` |
| Fix | `fix/<scope>-<summary>` | `Mindlayer-<summary>` |
| Test-only | `test/<scope>-<summary>` | `Mindlayer-<summary>` |
| Documentation | `docs/<summary>` | `Mindlayer-<summary>` |
| Chore / deps | `chore/<summary>` | `Mindlayer-<summary>` |
| Reliability invariants (F-NNN) | `reliability/<summary>` | `Mindlayer-<summary>` |

Keep the worktree dir name aligned with the branch's short summary so parallel worktrees stay legible.

### Coordinating multiple agents in parallel

When several agents work concurrently:

- Each claims its target files via the **agent-messages MCP** `claim_resources` before editing shared files.
- Whichever agent merges first wins; later ones rebase onto the new tip and re-run tests.
- For tightly-coupled changes (e.g. AGP + Gradle wrapper bumps, both touching `gradle/`), combine into ONE PR rather than fight over the same file in two PRs.
- If a sub-agent abandons mid-flight (claim TTL expires, no recent activity), a later agent can claim the worktree and continue.

### Cleanup is part of "done"

A task is not complete until ALL of these are true:

1. Branch exists on origin (`git push origin <branch>`).
2. PR is open (or a URL has been handed to the user to open it).
3. After the PR merges:
   - Worktree removed (`git worktree remove`).
   - Local branch deleted (`git branch -D`).
   - `git fetch --prune origin` confirms the remote branch ref is gone.

A worktree left behind after merge is a bug, not a memento. Sub-agents must clean up before signing off.

### Exceptions

Read-only operations (`git status`, `git log`, `git blame`) and zero-file investigations don't need a worktree — just operate in the main repo. The worktree+PR rule applies whenever you're going to **change files**.

## Common tasks

| Task | Where to look |
|---|---|
| Add an AIDL method | Edit `IMindlayerService.aidl` in **both** `app/src/main/aidl/.../` AND `sdk/src/main/aidl/.../` (byte-identical interface); implement in `ServiceBinder.kt` (with `authorizeCall()` first); expose in `Mindlayer.kt`. |
| Add a new AIDL parcelable | Edit ONLY `sdk/src/main/aidl/com/adsamcik/mindlayer/NewType.aidl`; reference it from both interface copies (the imports also need to mirror); add `@Parcelize` companion in `:shared` (or `:sdk` if SDK-only). |
| Add a stream event type | `shared/.../Protocol.kt::StreamEventType` constant; emit in `TokenStreamWriter`; handle in `TokenStreamReader`; update tests in both `:app` and `:sdk`. |
| Add a Room column on the SDK history DB | Bump `MindlayerDatabase.version`, add a `Migration`, update `Entities.kt`. SQLCipher cross-install backup is unreadable by design. |
| Add a logged event | Add `LogEvent`/`LogCategory` enum value; add a builder on `LogRepository`; never include prompt or output text. |
| Add a security/auth gate | Read `docs/AUTHORIZATION.md` first. Don't bypass `authorizeCall()`. |

## Path-specific guidance

Loaded automatically by Copilot via `applyTo` frontmatter:

- `.github/instructions/privacy-offline.instructions.md` — privacy, offline-first, no-network, license, data-retention invariants (applies repo-wide)
- `.github/instructions/aidl.instructions.md` — AIDL drift, mirroring, Java syntax
- `.github/instructions/security.instructions.md` — auth invariants in `service/` + `security/`
- `.github/instructions/engine.instructions.md` — LiteRT-LM lifecycle, thermal/memory bands
- `.github/instructions/embeddings.instructions.md` — EmbeddingGemma, tokenizer, SHM/deferred transport rules
- `.github/instructions/ipc.instructions.md` — pipe framing, SharedMemory, wire protocol
- `.github/instructions/tests.instructions.md` — Robolectric, MockK, Turbine patterns

