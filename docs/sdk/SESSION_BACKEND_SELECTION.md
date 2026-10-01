# Selecting a backend for a bounded session

The proposed SDK `1.0.0-alpha.8` adds an optional typed `SessionScope.backend`.
It uses the existing `SessionConfig.backend` field, so a published alpha.7
service can honor a CPU preference during cold engine initialization:

```kotlin
mindlayer.infer {
    ephemeralSession {
        backend = InferenceBackend.CPU
        maxTokens = 8192
    }
    text(prompt)
}.awaitText()
```

The same configuration works with `openSession`, `withSession`, and helpers
that accept a `SessionScope`. Omitting the preference retains the existing
GPU default. The alpha.7-based release patch retains its 128–8192 token
validation, including input and output. Current unreleased main separately
permits 128–32768 tokens; this backend addition does not change that range.
The service may reduce the effective context under memory
pressure. The native engine is shared: a preference does not replace or resize
an already-loaded engine, and other clients may have initialized it first.

Do not call legacy `prewarm` to establish this context. Its published alpha.7
service implementation chooses the device's current maximum context instead
of the app's session budget. Creating the cold session sends the backend and
budget together through the existing wire contract.

## Isolated validation

This patch starts at the published `v1.0.0-alpha.7` tag; it does not include the
unreleased changes on the repository's current main branch. Its artifact
version is distinct from alpha.7. Build SDK/shared artifacts into an isolated
local Maven repository, then inject that repository into the consumer only for
validation. Never overwrite the published alpha.7 coordinates in `mavenLocal`.

SDK tests verify the actual Binder session configuration for explicit CPU and
the unchanged defaults, as well as compatibility of custom session scopes.
Passing these tests proves configuration propagation. It does not prove native
model allocation, CPU inference latency, or real-device behavior.

The isolated checkout includes the ignored local validation init script
`build/starlit-local-publish.init.gradle`. The commands used for verification
and local publication are:

```powershell
.\gradlew.bat :sdk:testDebugUnitTest --console=plain --no-daemon
.\gradlew.bat :shared:publishReleasePublicationToStarlitValidationRepository `
    :sdk:publishReleasePublicationToStarlitValidationRepository `
    -I build/starlit-local-publish.init.gradle --console=plain --no-daemon
```

The init script only adds a Maven repository named `StarlitValidation` whose
URL is `G:/Github/Mindlayer/build/starlit-sdk-validation-repo`. The publication
tasks named above write to that local directory. No GitHub Packages publication
task or `publishToMavenLocal` task is used. SDK POM and module metadata must point
to `shared:1.0.0-alpha.8`; retain both modules together for consumer validation.
