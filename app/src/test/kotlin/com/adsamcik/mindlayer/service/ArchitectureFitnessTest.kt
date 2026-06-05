package com.adsamcik.mindlayer.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Architecture + offline fitness functions.
 *
 * 1. The service CORE (trust boundary + engine + security + ipc + logging +
 *    health) must NOT depend on the client SDK (`com.adsamcik.mindlayer.sdk.*`).
 *    The dependency direction is app -> sdk -> shared; the service core must be
 *    expressible without the client library so the surface stays decomposable
 *    and the SDK can evolve independently. `service/ui` (the dashboard) is
 *    intentionally excluded — it runs SDK smoke tests against the live service.
 *
 * 2. The offline invariant: neither `:app` nor `:sdk` manifests may declare any
 *    network permission. (A custom lint detector also covers synthetic cases;
 *    this guards the real, committed manifests.)
 */
class ArchitectureFitnessTest {

    @Test
    fun `service core does not import the client SDK`() {
        val root = repoRoot()
        val coreDirs = listOf("engine", "security", "ipc", "logging", "health")
            .map { root.resolve("app/src/main/kotlin/com/adsamcik/mindlayer/service/$it") }
            .filter { Files.isDirectory(it) }
        val coreFiles = listOf(
            root.resolve("app/src/main/kotlin/com/adsamcik/mindlayer/service/ServiceBinder.kt"),
            root.resolve("app/src/main/kotlin/com/adsamcik/mindlayer/service/MindlayerMlService.kt"),
        ).filter { Files.exists(it) }

        val sdkImport = Regex("(?m)^import\\s+com\\.adsamcik\\.mindlayer\\.sdk\\.")
        val offenders = mutableListOf<String>()
        val files = coreDirs.flatMap { dir ->
            Files.walk(dir).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".kt") }.toList()
            }
        } + coreFiles
        for (f in files) {
            if (sdkImport.containsMatchIn(readText(f))) {
                offenders += root.relativize(f).toString()
            }
        }
        assertTrue(
            "Service-core code must not import the client SDK (com.adsamcik.mindlayer.sdk.*) — " +
                "keep the app -> sdk -> shared dependency direction. Offenders:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `app and sdk manifests declare no network permissions`() {
        val root = repoRoot()
        val manifests = listOf(
            "app/src/main/AndroidManifest.xml",
            "sdk/src/main/AndroidManifest.xml",
        ).map { root.resolve(it) }.filter { Files.exists(it) }
        assertTrue("Expected at least the :app manifest to exist", manifests.isNotEmpty())

        val networkPerms = listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
        )
        val violations = mutableListOf<String>()
        for (m in manifests) {
            val text = readText(m)
            for (perm in networkPerms) {
                if (text.contains(perm)) violations += "${root.relativize(m)} declares $perm"
            }
        }
        assertTrue(
            "Offline invariant: :app/:sdk manifests must declare no network permissions. " +
                "Violations:\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    private fun readText(path: Path): String = String(Files.readAllBytes(path), Charsets.UTF_8)

    private fun repoRoot(): Path {
        var current = Paths.get("").toAbsolutePath()
        while (!Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.parent ?: error("Could not find repository root")
        }
        return current
    }
}
