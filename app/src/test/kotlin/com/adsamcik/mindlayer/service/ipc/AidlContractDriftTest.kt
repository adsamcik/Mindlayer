package com.adsamcik.mindlayer.service.ipc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Cross-module contract drift guard.
 *
 * Parcelable AIDL declarations live ONLY in `:sdk/src/main/aidl/` —
 * `:app` (the service module) pulls them in via the `implementation
 * (project(":sdk"))` dependency and the AIDL compiler resolves them
 * transitively. The two AIDL **interface** files
 * (`IMindlayerService.aidl` + `IClientCallback.aidl`) remain mirrored
 * in both modules because they are the wire contract: the service-
 * generated `Stub` is in `:app`'s `R.aidl` namespace, the client-
 * generated `Proxy` is in `:sdk`'s — both must agree byte-for-byte
 * on method signatures or the Binder transactions silently fail.
 *
 * This test verifies:
 *   1. `:app/src/main/aidl/` contains ONLY the two interface files,
 *      not a single stray parcelable copy (catches accidental
 *      re-additions during refactors).
 *   2. Each interface file is byte-identical between the two modules
 *      after CRLF normalisation.
 *   3. `:sdk` contains a superset that includes the two interfaces
 *      (so the interface->parcelable references resolve at AIDL
 *      compile time).
 *
 * See `.github/instructions/aidl.instructions.md` for the policy.
 */
class AidlContractDriftTest {

    @Test
    fun `app and sdk AIDL interface contracts stay in sync`() {
        val root = repoRoot()
        val appDir = root.resolve("app/src/main/aidl/com/adsamcik/mindlayer")
        val sdkDir = root.resolve("sdk/src/main/aidl/com/adsamcik/mindlayer")

        val appFiles = aidlFiles(appDir)
        val sdkFiles = aidlFiles(sdkDir)

        // (1) app side must contain ONLY the two interface files. Any
        // parcelable copy here is a refactor regression.
        val unexpectedInApp = appFiles - INTERFACE_FILES
        assertTrue(
            "Unexpected non-interface AIDL files in :app — parcelables now " +
                "live only in :sdk (see .github/instructions/aidl.instructions.md): $unexpectedInApp",
            unexpectedInApp.isEmpty(),
        )
        val missingInApp = INTERFACE_FILES - appFiles
        assertTrue(
            "Missing required interface AIDL files in :app: $missingInApp",
            missingInApp.isEmpty(),
        )

        // (2) sdk side must contain the two interface files too. The
        // parcelables it ALSO contains are the source-of-truth set.
        val missingInSdk = INTERFACE_FILES - sdkFiles
        assertTrue(
            "Missing required interface AIDL files in :sdk: $missingInSdk",
            missingInSdk.isEmpty(),
        )

        // (3) byte-identical interface contracts after CRLF normalisation.
        INTERFACE_FILES.forEach { fileName ->
            val appBytes = readNormalized(appDir.resolve(fileName))
            val sdkBytes = readNormalized(sdkDir.resolve(fileName))
            assertArrayEquals("AIDL drift in $fileName", appBytes, sdkBytes)
        }
    }

    private fun aidlFiles(dir: Path): Set<String> =
        Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".aidl") }
                .map { it.fileName.toString() }
                .toList()
                .toSet()
        }

    private fun readNormalized(path: Path): ByteArray =
        String(Files.readAllBytes(path), Charsets.UTF_8)
            .replace("\r\n", "\n")
            .trim()
            .toByteArray(Charsets.UTF_8)

    private fun repoRoot(): Path {
        var current = Paths.get("").toAbsolutePath()
        while (!Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.parent
                ?: error("Could not find repository root from ${Paths.get("").toAbsolutePath()}")
        }
        return current
    }

    private companion object {
        private val INTERFACE_FILES = setOf(
            "IMindlayerService.aidl",
            "IClientCallback.aidl",
        )
    }
}
