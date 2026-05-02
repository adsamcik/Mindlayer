---
applyTo: "**/src/test/**, **/src/androidTest/**"
description: "Test conventions: JUnit 4, MockK, Robolectric, Turbine"
---

<!-- context-init:managed -->

## Frameworks

- JUnit 4 (`junit:junit:4.13.2`). No JUnit 5.
- **MockK** for mocking (`mockk`, `mockk-android` for instrumented). Don't add Mockito.
- **Robolectric** 4.14.1 with `@Config(sdk = [33])` when a `Context`/Room is needed in unit tests.
- **Turbine** for `Flow` assertions on the SDK side.
- `kotlinx-coroutines-test` (`runTest`) for suspend tests.
- `androidx.room:room-testing` for in-memory DB tests.

## Layout

- Mirror the production package: `app/src/main/kotlin/.../engine/EngineManager.kt` ↔ `app/src/test/kotlin/.../engine/EngineManagerTest.kt`.
- Test class name: `<ClassUnderTest>Test`. One class under test per file.
- Helpers go under `service/testutil/` (e.g. `TestPipeHelper`) or sibling `support` files in the test source set.

## Robolectric pattern

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])                       // pin SDK; don't depend on host default
class FooTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // …
    }
}
```

> Robolectric must run on **Java 21** (CI does this — see `DEVELOPMENT.md` for the Temurin SIGSEGV gotcha). If you change CI Java config or local toolchain, run unit tests end-to-end before pushing.

## MockK patterns

- `mockk<Foo>(relaxed = true)` for collaborators we don't care about asserting on.
- `mockkObject(MyObject)` / `unmockkAll()` in `@After` for object/static mocks; always pair with cleanup.
- `coEvery { dao.insert(any()) } returns Unit` for suspend stubs.
- `slot<T>()` + `verify { … capture(slot) }` for argument capture.

## Suspend / Flow tests

```kotlin
@Test fun `something completes`() = runTest {
    val results = mutableListOf<Event>()
    flow.test {                // Turbine
        assertEquals(Event.Start, awaitItem())
        cancelAndIgnoreRemainingEvents()
    }
}
```

- Use `runTest`, not `runBlocking`. It controls the test scheduler.
- For deterministic time, use `TestCoroutineScheduler` via `runTest`'s `testScheduler`.
- Don't `Thread.sleep` — replace with `advanceTimeBy` / `runCurrent`.

## Privacy invariant in tests

- `LogRepository` tests assert that prompt text and model output **never** appear in `LogEntry.extraJson` or any column. If you change the logging surface, add or update an assertion.
- `LogEntitiesTest`, `LogRepositoryTest`, `DiagnosticExporterTest` are the relevant places.

## Stream contract

- Use `TestPipeHelper` (`app/src/test/.../testutil/TestPipeHelper.kt`) for any test that exercises framed-event sequences. Don't reimplement framing in tests — it would mask bugs in the writer/reader.
- Pipe tests on the JVM use `PipedInputStream`/`PipedOutputStream`; Android-only stream features (e.g. `PFD.AutoCloseOutputStream`) need Robolectric or `androidTest`.

## SharedMemory tests

- Are `@Ignore`'d on the JVM — `SharedMemory` requires real Android. Real coverage lives in `androidTest`.
- Don't try to "fix" the `@Ignore` with shadows; the platform behavior we're testing is below Robolectric's emulation.

## Authorization tests

- Don't mock `PackageManager` directly. `ServiceBinder` accepts a `CallerVerifierGate` interface for tests — pass a fake that returns canned `CallerIdentity` results.
- `AllowlistStoreTest` tests cross-process writes by using two `AllowlistStore` instances on the same temp directory. Preserve this pattern when extending the test.

## Instrumented (`androidTest/`)

- Runner: `androidx.test.runner.AndroidJUnitRunner`.
- API level matrix: 33 (one row in CI). When adding instrumented coverage, target 33 first.
- DB-encryption tests live here (`DbKeyProviderTest`, `EncryptedDbWiringTest`) — they need real AndroidKeystore.

## Don't

- Don't add JUnit 5, AssertJ, or Mockito.
- Don't write tests that assume a specific device tier — derive from `MemoryBudget` constants instead.
- Don't bake real Gemma model paths into tests; the engine factories accept fakes.
- Don't `Thread.sleep` to wait for coroutines.
