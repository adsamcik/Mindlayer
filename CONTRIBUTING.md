# Contributing to Mindlayer

Thanks for picking up a piece of the project. The bullets below are the
not-obvious things that catch first-time contributors; the rest is in
`README.md` and `docs/project/RELEASE.md`.

## JDK requirement

LiteRT-LM is compiled for Java 21 class-file format (version 65). Set
`JAVA_HOME` to a JDK 21+ install before invoking `./gradlew` — older JDKs
hit `UnsupportedClassVersionError` on every test class that touches the
engine. CI uses `setup-java@v4` with `java-version: 21`. Locally you can
point at any JDK 21 (Temurin, JetBrains Runtime, Microsoft Build of
OpenJDK).

## Privacy hygiene for debug captures

Don't commit captured runtime state. The repo's `.gitignore` filters
`logcat*.txt`, `*.logcat`, and `adb-*.log` so a routine `adb logcat >
debug.txt` doesn't sneak its way in, but the patterns are not
exhaustive — review your working tree before pushing.

Captures may contain:

- device serials and ADB endpoint
- package names of every running app
- session IDs and request IDs (cross-grep against the device's
  encrypted log DB after F-046's `take(8)` truncation)
- prompt fragments via native exception text on debug builds where the
  `Throwable.safeLabel()` redaction has not been applied
- AI Pack file paths under `/data/data/.../files/`

If you need to share a capture for triage, **redact first** or paste
into an internal channel — never attach it to a public PR or issue.

After triage, delete from your working tree:

```bash
rm logcat*.txt *.logcat adb-*.log
```

## Test suite

Run the full unit-test sweep with:

```bash
./gradlew :app:testDebugUnitTest :sdk:testDebugUnitTest :shared:testDebugUnitTest
```

Target on JDK 21 is **0 failures**. The single `@Ignore`'d test
(`SharedMemoryPoolSecurityTest > cleanup closes active blocking media
source`) is annotated with the rationale — Robolectric's pipe shim
doesn't faithfully emulate Linux read-blocking semantics, so the
behaviour is exercised by `InferenceOrchestratorBackpressureTest`
and on-device.

## Security

Security-sensitive changes (anything in
`app/src/main/kotlin/com/adsamcik/mindlayer/service/security/**`,
`app/src/main/aidl/**`, `DbKeyProvider`, `ServiceBinder` ingress paths,
or model/AI-pack handling) must include:

- a regression test that fails on the pre-fix code,
- a one-line note in the commit message referencing the canonical
  finding ID (`F-NNN`) from the consolidated security review when the
  change addresses one,
- no AIDL ABI break unless a new method is added (existing methods'
  signatures and Parcelable shapes are frozen).
