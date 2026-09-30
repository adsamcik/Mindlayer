# Tracebox managed restart repair

This directory vendors one managed AAR, based on the owner's Apache-2.0
`v0.1.0-alpha.7` source (`b49e8fda94e0bd65859eb080cacd624d9b72a7f5`). Supporting
Tracebox modules remain pinned to their original alpha.7 releases. No native
capture library is included.

On restart, alpha.7 resizes the managed identity journal's quota reservation to
its physical size. Identity allocation then requires an exact 64 KiB reservation
and fails because the smaller reservation already owns that path. Tracebox
silently disables capture and reports degraded health. An unchanged service
policy also opens a fresh empty segment every second when native capture is
disabled, exhausting the 32-file limit in about half a minute. The patch keeps
the managed writer on unchanged policy, preserves the 64 KiB reservation for
both identity journals, and repairs already undersized reservations. Existing
exhausted storage is recovered by retiring only verified sealed zero-frame
segments. Nonempty, unsealed or invalid segments, identity journals and reports
are preserved.

`managed-identity-restart.patch` includes the production fixes and regression
tests for restart, policy polling and safe recovery. `provenance.json` records source commits,
the build command, toolchain, test result and artifact digest. `LICENSE` and
`NOTICE` are copied from the upstream source.

The Maven POM changes only its top-level version to
`0.1.0-alpha.7-mindlayer.1` and removes the original Gradle-metadata redirect.
Its dependencies remain alpha.7. Mindlayer's exclusive repository and strict
version prevent an unpatched runtime from replacing this repair.

To rebuild, create a fresh checkout of the recorded upstream commit, apply
`managed-identity-restart.patch`, configure Android SDK 37 and JDK 21, then run:

```powershell
.\gradlew.bat :android:tracebox:testDebugUnitTest :android:tracebox:assembleRelease :android:tracebox:generatePomFileForReleasePublication --no-daemon --console=plain
```

Compare the release AAR's SHA-256 with `provenance.json` before replacing the
vendored artifact. A replacement requires updating provenance and repeating
Mindlayer's restart/report checks. Retire this directory when a verified upstream
release includes both repairs.
