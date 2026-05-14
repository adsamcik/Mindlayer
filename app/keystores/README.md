# knownCerts instrumented-test keystores

These are test-only keystores used by `KnownCertsDigestFormatTest`. They are committed so the Android
PackageManager permission grant test has stable certificate digests on every developer machine and CI runner.

They are not production signing material.

## Credentials

- Store password: `knowncertstest`
- Key password: `knowncertstest`
- Owner alias: `knowncerts-owner` (`knowncerts-owner.jks`)
- Requester alias: `knowncerts-requester` (`knowncerts-requester.jks`)

The debug target APK is signed with `knowncerts-owner`; the androidTest APK is signed with
`knowncerts-requester`. Keeping the signers different prevents the normal `signature` branch from masking
what `knownSigner` accepts.

Requester certificate SHA-256, in the canonical Android `knownCerts` format, is:

```text
3bb0a4da57f3230bf5c1d49da62cb320ca960839a93c71dc14c2eef1243f8588
```

## Regeneration recipe

```powershell
$keytool = "$env:JAVA_HOME\bin\keytool.exe"
& $keytool -genkeypair -v -keystore knowncerts-owner.jks -storepass knowncertstest -keypass knowncertstest `
  -alias knowncerts-owner -keyalg RSA -keysize 2048 -validity 10000 `
  -dname "CN=Mindlayer knownCerts test owner,O=Mindlayer,C=US"
& $keytool -genkeypair -v -keystore knowncerts-requester.jks -storepass knowncertstest -keypass knowncertstest `
  -alias knowncerts-requester -keyalg RSA -keysize 2048 -validity 10000 `
  -dname "CN=Mindlayer knownCerts test requester,O=Mindlayer,C=US"
& $keytool -list -v -keystore knowncerts-requester.jks -storepass knowncertstest -alias knowncerts-requester
```

Strip colons from the `SHA256:` fingerprint and lowercase it before updating
`app/src/debug/res/values/knowncerts_format_test.xml`.
