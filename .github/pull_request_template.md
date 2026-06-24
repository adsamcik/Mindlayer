## Description

Brief description of what this PR does.

## Type of change

- [ ] Bug fix
- [ ] New feature
- [ ] Performance improvement
- [ ] Refactoring
- [ ] Test coverage
- [ ] Documentation

## Testing

- [ ] Unit tests pass (`./gradlew :app:testDebugUnitTest :sdk:testDebugUnitTest`)
- [ ] Tested on emulator
- [ ] Tested on physical device
- [ ] Diagnostic dump verified

## Checklist

- [ ] Code follows project conventions (MindlayerLog, coroutine dispatchers, etc.)
- [ ] No secrets or model files committed
- [ ] AIDL changes (`IMindlayerService.aidl` / `IClientCallback.aidl` / parcelables) made ONLY in `sdk/src/main/aidl/`. `:app` has no AIDL of its own (adding any breaks the release R8 merge).

## Hardware-sensitive change?

- [ ] My change touches `app/src/main/kotlin/.../service/engine/`, `MindlayerMlService.kt` FGS code, the NPU SoC list, `AndroidManifest.xml`, or `:gemma_model`.
- [ ] If yes, I completed the [Hardware-touching PR checklist](../docs/project/RELEASE.md#7-hardware-touching-pr-checklist).
- [ ] If yes, real-device logs (trimmed, metadata only) are attached to this PR description.
