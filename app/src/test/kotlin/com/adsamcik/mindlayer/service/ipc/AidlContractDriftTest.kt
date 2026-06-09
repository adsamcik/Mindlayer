package com.adsamcik.mindlayer.service.ipc

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Cross-module AIDL ownership guard.
 *
 * The AIDL **interface** files (`IMindlayerService.aidl` +
 * `IClientCallback.aidl`) and ALL parcelable declarations live ONLY in
 * `:sdk/src/main/aidl/`. `:app` (the service module) consumes the generated
 * Binder classes (`IMindlayerService.Stub`, `IClientCallback`, parcelables)
 * via `implementation(project(":sdk"))` and does NOT compile its own copy.
 *
 * Why: `:app` previously mirrored the interface `.aidl` files. Once `:app`
 * started depending on `:sdk` (which already compiles the same interfaces),
 * the duplicated classes broke the **release** build — R8 fails the
 * full-program merge with "Type com.adsamcik.mindlayer.IClientCallback
 * `$Default` is defined multiple times" (present in both the app's javac
 * output and the `:sdk` runtime jar). Debug builds (non-minified D8) tolerated
 * it, and CI never runs a release dex, so it went undetected.
 *
 * This test fails closed if anyone re-introduces AIDL sources into `:app`,
 * which would re-break `:app:minifyReleaseWithR8`. See
 * `.github/instructions/aidl.instructions.md` for the policy.
 */
class AidlContractDriftTest {

    @Test
    fun `app does not compile its own AIDL — interfaces live only in sdk`() {
        val root = repoRoot()
        val appDir = root.resolve("app/src/main/aidl")
        val sdkDir = root.resolve("sdk/src/main/aidl/com/adsamcik/mindlayer")

        // (1) :app must not own any AIDL sources. A re-added interface or
        // parcelable here would be compiled into :app AND pulled in from the
        // :sdk dependency, breaking the release R8 merge.
        val appAidl = aidlFilesRecursive(appDir)
        assertTrue(
            "Found AIDL sources in :app ($appAidl). AIDL is defined only in " +
                ":sdk; :app consumes the generated classes via " +
                "implementation(project(\":sdk\")). Re-adding AIDL to :app breaks " +
                "the release build — see .github/instructions/aidl.instructions.md.",
            appAidl.isEmpty(),
        )

        // (2) :sdk remains the single source of truth: both interface files
        // plus the parcelable set it references.
        val sdkFiles = aidlFiles(sdkDir)
        val missingInSdk = INTERFACE_FILES - sdkFiles
        assertTrue(
            "Missing required interface AIDL files in :sdk: $missingInSdk",
            missingInSdk.isEmpty(),
        )
    }

    private fun aidlFiles(dir: Path): Set<String> {
        if (!Files.isDirectory(dir)) return emptySet()
        return Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".aidl") }
                .map { it.fileName.toString() }
                .toList()
                .toSet()
        }
    }

    private fun aidlFilesRecursive(dir: Path): Set<String> {
        if (!Files.exists(dir)) return emptySet()
        return Files.walk(dir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".aidl") }
                .map { dir.relativize(it).toString() }
                .toList()
                .toSet()
        }
    }

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
