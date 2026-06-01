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
- [ ] AIDL **interface** changes (`IMindlayerService.aidl` / `IClientCallback.aidl`) mirrored byte-identical in both `app/src/main/aidl/` and `sdk/src/main/aidl/`. New / changed **parcelables** go only in `sdk/src/main/aidl/`.

## Hardware-sensitive change?

- [ ] My change touches `app/src/main/kotlin/.../service/engine/`, `MindlayerMlService.kt` FGS code, the NPU SoC list, `AndroidManifest.xml`, or `:gemma_model`.
- [ ] If yes, I completed the [Hardware-touching PR checklist](../RELEASE.md#7-hardware-touching-pr-checklist).
- [ ] If yes, real-device logs (trimmed, metadata only) are attached to this PR description.
