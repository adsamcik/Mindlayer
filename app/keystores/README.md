# knownCerts instrumented-test keystore (pinned debug signer)

`knowncerts-owner.jks` is a test-only keystore committed to the repo so the debug variant has a stable
signing certificate on every developer machine and CI runner. It is wired as the debug build type's
`signingConfig` in `app/build.gradle.kts`. The androidTest variant inherits this same config, which keeps
Android's instrumentation framework happy (test APK and target APK must share a signing certificate).

This is not production signing material.

## Credentials

- Store password: `knowncertstest`
- Key password: `knowncertstest`
- Owner alias: `knowncerts-owner` (`knowncerts-owner.jks`)

Certificate SHA-256 (canonical lowercase 64-hex form):

```text
664735c79928241a813a556fa41a03762c568189096949c7c2cfb533f26a7f52
```

## History

PR #44 also committed a second keystore (`knowncerts-requester.jks`) and configured the androidTest
variant to sign with it, so a `signature|knownSigner` permission could only be granted via the
`knownCerts` branch. That design was reverted in the follow-up hotfix because Android's instrumentation
framework refuses to start a test APK whose signing cert does not match the target APK's — CI broke
on every PR with `Permission Denial: ... does NOT have a signature matching the target`. The
`KnownCertsDigestFormatTest` is currently `@Ignore`d pending a redesign (see the test class comment).

## Regeneration recipe

```powershell
$keytool = "$env:JAVA_HOME\bin\keytool.exe"
& $keytool -genkeypair -v -keystore knowncerts-owner.jks -storepass knowncertstest -keypass knowncertstest `
  -alias knowncerts-owner -keyalg RSA -keysize 2048 -validity 10000 `
  -dname "CN=Mindlayer knownCerts test owner,O=Mindlayer,C=US"
& $keytool -list -v -keystore knowncerts-owner.jks -storepass knowncertstest -alias knowncerts-owner
```

Strip colons from the `SHA256:` fingerprint and lowercase it before updating this README.

