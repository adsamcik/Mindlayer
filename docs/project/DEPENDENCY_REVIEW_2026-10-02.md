# Dependency updates — 2026-10-02

The reviewed catalog updates are applied, with KSP 2.3.12 added. Build-tool
security pins now cover JDOM 2.0.6.1, jose4j 0.9.6 and Commons Lang 3.18.0
across the root plugin classpath, included build and subproject buildscript
graphs. Actual AGP class origins were checked against those JAR versions.
The Gradle 9.6.1 wrapper now pins the distribution SHA-256 from
[Gradle's published checksum](https://downloads.gradle.org/distributions/gradle-9.6.1-bin.zip.sha256).

SDK and shared publication metadata now expose the coroutine and serialization
types in their public APIs. `:samples:sdk-consumer-smoke` compiles against only
`:sdk`, exercising Flow, StateFlow, JsonObject and StreamEvent. The normal CI
`assembleDebug` includes this consumer; both its debug and release compilations
passed locally.

## Verified

- Full unit suite: 3,117 tests, zero failures, 24 skipped across app, SDK,
  camera modules, shared and custom lint. An initial run had 66 failures; the
  affected classes passed both baseline and updated focused runs, followed by
  a successful full rerun. The initial cause was not conclusively identified.
- Debug APK assembly, app release Kotlin compilation and debug lint passed.
- Native ABI, duplicate-library and AAR namespace guards passed.
- SDK/shared release POM and Gradle publication metadata generated successfully.
- A fresh isolated wrapper download passed checksum verification and ran Gradle 9.6.1.
- OSV returned no matches for 344 resolved Maven package/version pairs across
  runtime, unit-test and build-tool graphs. This is a known-advisory snapshot,
  not a guarantee that every package is free of vulnerabilities.
- Compatible Python converter fixes, actual PaddleOCR detection/recognition/orientation
  exports and the remaining upstream dependency conflicts are documented in
  [the conversion dependency review](../../scripts/build-paddleocr-models/DEPENDENCIES.md).

## Deferred updates and next work

SQLCipher is pinned at 4.19.0 (published September 8). Maven Central published
4.19.1 on September 29 at 18:41 UTC, so it becomes eligible under the
repository's seven-day community soak rule on October 6 after 20:41 Prague
time. Robolectric 4.17 was released September 10 and is eligible.

LiteRT-LM 0.17.1 with base LiteRT 2.2.0 passed packaging checks. Its fresh CPU,
GPU and NPU coexistence/device inference matrix remains outstanding; prior
0.16.1 emulator evidence does not establish current runtime compatibility.
See [the coexistence investigation](../architecture/LITERT_COEXISTENCE.md).

The next substantial dependency work is the converter backport/migration needed
to remove the remaining ONNX, protobuf and pytest findings. Complete
model conversion and inference comparison are required before changing that
toolchain. Gradle dependency verification remains opt-in under the existing
release policy.
