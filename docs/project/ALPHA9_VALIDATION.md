# Alpha.9 main-based release validation

Prepared on 2026-10-02 from main baseline `20c63c2`. The release preparation
sets product/SDK version `1.0.0-alpha.9`; the release app configuration reports
version code `10000009`. The unfinished dependency-catalog changes in the
primary checkout are excluded and preserved. Validation uses committed
AGP 9.3.2, Kotlin 2.4.10, LiteRT-LM 0.16.1, LiteRT 2.2.0, and Gradle 9.6.1.

## Passed checks

- `assembleDebug` for the service, SDK/camera modules and sample.
- `testDebugUnitTest` and `:app:testReleaseUnitTest`.
- `lintDebug`, including the service, SDK/camera modules, shared and sample.
- `:app:validateAndroidAarNamespaces`, `:app:validateLitertlmAbis`,
  `:app:validateLitertAbis`, and `:app:validateNoLiteRtNativeLibCollision`.
- Version/changelog metadata alignment and four positive/negative release
  metadata fixtures. A tag with mismatched source version, absent release entry,
  or empty release notes is rejected.
- Actionlint 1.7.12; only the existing intentionally disabled AAB condition
  is excluded from its diagnostics.
- Existing Windows PowerShell ADB deployment regressions: native stderr does
  not hide exit codes, matching models are retained, and real failures propagate.

The local Gradle run completed successfully in 18m 18s. Detailed local logs and
JUnit totals are retained under `build/release-validation` in the isolated
main-based release checkout.
The reported debug/release suites contain 3,116 cases: 3,085 executed and 31
skipped, with zero failures or errors.

## Publication and qualification boundary

Integrate and push this release preparation to main, then tag that exact source
as `v1.0.0-alpha.9`. The tag workflow rechecks main ancestry, committed version,
dated changelog, debug/release tests and lint before Maven publication. All four
Maven modules and the code-only service APK must come from the same tag. Alpha.8
packages retain their original alpha.7-based source and are not overwritten.

This validation does not establish physical ARM64 inference, GPU/NPU memory or
coexistence, Play delivery, model provenance qualification, or production signed
bundle readiness. GitHub distributes the SDKs and a debug-signed code-only APK;
production model bundles and signing remain the documented local Play workflow.
