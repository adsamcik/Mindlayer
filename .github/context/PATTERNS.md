<!-- context-init:version:3.1.0 -->
<!-- context-init:managed -->

# Mindlayer Coding Patterns

Verified conventions in this codebase. Each rule lists the file(s) where it is observed; if you change a rule here, also change the cited code or this doc lies.

## Naming

| Item | Convention | Examples |
|---|---|---|
| Packages | `com.adsamcik.mindlayer.{service,sdk,shared}.<area>` | `service.engine`, `sdk.db` |
| Classes | `PascalCase`, ends in role (`Manager`, `Repository`, `Store`, `Bridge`, `Orchestrator`, `Verifier`, `Monitor`) | `EngineManager`, `AllowlistStore` |
| Functions | `camelCase`, verbs | `authorizeCall`, `recordPending`, `tryAcquire` |
| Constants | `UPPER_SNAKE_CASE` in companion objects | `DEFAULT_RPM`, `MAX_FRAME_BYTES`, `MAX_TOOL_ROUNDS` |
| Logging tag | `private const val TAG = "<ClassName>"` in companion | every component |
| Test classes | `<ClassUnderTest>Test` in mirror package | `EngineManagerTest`, `AllowlistStoreTest` |

## Logging

**Prefer `MindlayerLog` for all new code in `:app` service.** Direct `android.util.Log` is in-flight tech debt, not a green-field choice.

```kotlin
// Good — files: MindlayerLog.kt, ServiceBinder.kt, InferenceOrchestrator.kt
MindlayerLog.i(TAG, "Inference complete", requestId = id, sessionId = sid)
MindlayerLog.e(TAG, "Init failed: ${e.safeLabel()}", throwable = null)

// Bad — bypasses correlation prefix and risks leaking content via stack traces
Log.i("Foo", "started inference for $userPrompt")
```

- Tag prefix `Mindlayer.` is added by `MindlayerLog.tag()`. Filter logcat with `adb logcat -s "Mindlayer.*:D"`.
- `requestId` and `sessionId` are wrapped as `[req=… sess=…]`. Always pass them when handling a request.
- For exceptions on the inference path, use `Throwable.safeLabel()` and pass `throwable = null` — LiteRT-LM exception messages and stack traces can embed prompt text.
- **Migration status:** `MindlayerLog.kt` itself wraps `android.util.Log` (expected). The following files **still call `android.util.Log` directly** and should be migrated when next touched: `MindlayerMlService.kt`, `EngineManager.kt`, `MemoryBudget.kt`, `ModelRegistry.kt`, `StructuredOutputHelper.kt`, `ThermalMonitor.kt`, `SharedMemoryPool.kt`. Don't *add* new `Log.x` calls in these files — convert what you touch.

## Privacy invariant

Prompt text and model output are **never** persisted by the service. The Room `LogEntry` schema has `tokenCount`, `durationMs`, `requestId`, `sessionId`, `backend` — but no content fields. `LogRepository.logUserMessage` and `logModelResponse` enforce this in code (and in their KDoc).

```kotlin
// Good — file: LogRepository.kt
fun logUserMessage(requestId: String, sessionId: String, tokenCount: Int) {
    log(LogEntry(...
        extraJson = """{"tokenCount":$tokenCount}""",   // metadata only
    ))
}

// Bad
log(LogEntry(extraJson = """{"prompt":"$userText"}"""))   // NEVER persist content
```

## Coroutines & threading

| Where | Pattern | Why |
|---|---|---|
| Service / orchestrator scopes | `CoroutineScope(SupervisorJob() + Dispatchers.Default)` | Failure of one job must not cancel siblings. |
| `LogRepository`, disk/Room, file I/O | `Dispatchers.IO` (or `withContext(Dispatchers.IO)`) | Blocking work off CPU dispatcher. |
| Engine init | `withContext(Dispatchers.IO)` inside a global `Mutex` | Native init can take up to ~15 s (observed ~14 s on a Gemma 4 E2B model) and must not run twice. |
| Per-session sends | `Mutex` per `SessionHandle` (`SessionManager.kt`) | Single-writer per session; concurrent sessions OK. |
| State exposure | `private val _x = MutableStateFlow(...)` + `val x: StateFlow<...> = _x.asStateFlow()` | UI/observers consume immutable view. |
| Cancellation | LiteRT-LM `Conversation.cancelProcess()` is **explicit** — Flow cancellation alone does NOT stop native work | See `InferenceOrchestrator.cancelInference` (`handle.conversation.cancelProcess()`). |

> Not every `suspend fun` runs on `IO` — `Dispatchers.Default` is correct for CPU-bound coordination. Pick by what the function actually does.

## Cross-process state

When state must be visible across `:ml` and the main process (allowlist, model registry, anything user-mutable), use the same recipe as `AllowlistStore`:

```kotlin
// Good — file: AllowlistStore.kt
private fun atomicWrite(target: File, content: String) {
    val tmp = File(target.parentFile, target.name + ".tmp")
    tmp.writeText(content)
    if (!tmp.renameTo(target)) { target.writeText(content); tmp.delete() }
}

private inline fun <T> withFileLock(block: () -> T): T {
    RandomAccessFile(lockFile, "rw").use { raf ->
        raf.channel.use { ch ->
            val lock = ch.lock()
            try { return block() } finally { runCatching { lock.release() } }
        }
    }
}

// Hot read path always re-reads disk:
fun isAllowed(pkg: String, sig: String): Boolean =
    readEntries().firstOrNull { it.packageName == pkg }?.signingCertSha256
        .equals(sig, ignoreCase = true)
```

- **Never** `SharedPreferences` for cross-process state. `MODE_MULTI_PROCESS` is deprecated and racy.
- Always atomic-write via `tmp -> rename`.
- Always serialise writes with a `FileLock` on a sidecar `.lock` file.
- Hot read paths always re-read; rely on rate-limiting upstream to make disk cost tolerable.
- Per-process `MutableStateFlow` projections are fine for UI but are **not** the source of truth.

## AIDL surface

| Rule | Why |
|---|---|
| ALL AIDL files (interfaces `IMindlayerService.aidl` + `IClientCallback.aidl` and parcelables) live ONLY in `sdk/src/main/aidl/com/adsamcik/mindlayer/`; `:app` consumes the generated Binder classes via `implementation(project(":sdk"))` and adds no AIDL of its own (`AidlContractDriftTest` enforces). | `:sdk` is the published library that owns the contract; `:app` re-compiling the same AIDL would duplicate the classes and break the release R8 merge. Single-source = no drift. |
| AIDL Parcelables (`SessionConfig`, `RequestMeta`, `ImageTransfer`, …) are **Java** files. | AIDL syntax — not Kotlin. |
| Every AIDL entry point in `ServiceBinder` calls `authorizeCall()` first. | Defense in depth. |
| Media (`ImageTransfer`, `AudioTransfer`) goes through `SharedMemoryPool`, not as raw bytes in the parcel. | Binder transaction limit is 1 MB. |
| `oneway` is used only where the caller truly does not care about completion (`prewarm`). | Otherwise back-pressure and errors are lost. |

## Pipe streaming

```kotlin
// Frame: 4 bytes little-endian u32 length + UTF-8 JSON payload
// MAX_FRAME_BYTES = 1_048_576  (duplicated in writer + reader, on purpose)
val len = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.size).array()
output.write(len); output.write(payload); output.flush()
```

- `TokenStreamWriter` is **not** thread-safe; the orchestrator's per-session mutex serialises writes.
- All payloads are `kotlinx.serialization` JSON of `StreamEvent { seq, type, tsMs, payload }` (`shared/.../Protocol.kt`).
- The first frame is a `StreamHeader { protocol = "mindlayer.stream.v1", requestId }`; subsequent frames are `StreamEvent`s.

## JSON serialization

| Library | When | Where |
|---|---|---|
| `kotlinx.serialization` | All wire/protocol JSON, persisted JSON, structured output | `shared/.../Protocol.kt`, `TokenStreamWriter`, SDK |
| `org.json` | Allowlist/pending file format only | `AllowlistStore.kt` (it's local to that file; don't spread it) |
| `Gson` | LiteRT-LM tool-call interop — the LiteRT-LM Kotlin API takes `Gson` for some payloads | `InferenceOrchestrator.kt` |

> Default to `kotlinx.serialization`. Use Gson only at LiteRT-LM API boundaries; don't introduce it elsewhere.

## State flow exposure

```kotlin
// Good — files: AllowlistStore.kt, ConnectionManager.kt, ThermalMonitor.kt
private val _entries = MutableStateFlow(readEntries())
val entries: StateFlow<List<AllowlistEntry>> = _entries.asStateFlow()

// Bad
val entries = mutableStateListOf<AllowlistEntry>()   // Compose-only, leaks UI state model
```

## Error handling

- Throw `SecurityException` from AIDL entry points for any authorization failure (unknown caller, not allowed, rate-limited, ownership violation). The SDK's `ConnectionManager` translates to `ConnectionState.REJECTED_NOT_APPROVED` for the auth case.
- Throw `MindlayerException` from SDK one-shot convenience methods (`Mindlayer.kt`) — carries `code` and `requestId`.
- Inside coroutine scopes, propagate `CancellationException` — never swallow it. `Throwable.safeLabel()` is the standard way to log other exceptions.

## Tests

| Type | Tool | Where | Notes |
|---|---|---|---|
| Unit | JUnit 4 + MockK + `kotlinx-coroutines-test` | `<module>/src/test/kotlin/...` | Mirror package layout. |
| Robolectric | JUnit 4 + `@RunWith(RobolectricTestRunner::class)` + `@Config(sdk = [33])` | Anywhere needing `Context`/Room | Java 21 runtime, see `DEVELOPMENT.md` for the Temurin gotcha. |
| Stream contracts | `TestPipeHelper` (`app/src/test/.../testutil/`) | IPC tests | Asserts framed-event sequences. |
| Pipe protocol | `Turbine` for Flow assertions | SDK tests | |
| SharedMemory | `@Ignore` on JVM | — | Needs real Android runtime; covered by `androidTest`. |
| Instrumented | AndroidX Test runner on AVD api-level 33 | `<module>/src/androidTest/kotlin/...` | DB encryption, signing-cert paths. |

Run all unit tests:

```bash
./gradlew :app:testDebugUnitTest :sdk:testDebugUnitTest :shared:testDebugUnitTest
```

## File / class layout

- `companion object` block at the top of the class for constants and factory methods, followed by fields, then methods grouped by `// ---- <Section> ---------` comment dividers (see `ServiceBinder.kt`, `InferenceOrchestrator.kt`).
- Public KDoc on every class and every public function. Document thread-safety expectations in the KDoc — many components are explicitly *not* thread-safe (e.g. `TokenStreamWriter`).
- Imports: ktlint/IntelliJ ordering, no wildcards (verified across files).
- Indent: 4 spaces (Kotlin/XML), 2 spaces (json/toml/yml). Enforced by `.editorconfig`.

## Git / commits

- Default branch: `main`.
- **Conventional Commits** style: `feat(scope):`, `fix:`, `ci:`, `test:`, `docs:`, `refactor:`, `chore(release):` (verified in `git log`).
- AIDL changes must be mirrored in both `:app` and `:sdk` (PR template enforces).
- Co-author trailer (when an AI agent makes a commit):
  ```
  Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
  ```
