package com.adsamcik.mindlayer.service.logging

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ServiceLoggingPolicyTest {

    @Test
    fun `service production code logs only through MindlayerLog`() {
        val serviceRoot = repoRoot()
            .resolve("app")
            .resolve("src")
            .resolve("main")
            .resolve("kotlin")
            .resolve("com")
            .resolve("adsamcik")
            .resolve("mindlayer")
            .resolve("service")
        val violations = serviceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.invariantSeparatorsPath.endsWith("/logging/MindlayerLog.kt") }
            .filter { it.readText().contains("import android.util.Log") }
            .map { it.relativeTo(serviceRoot).invariantSeparatorsPath }
            .toList()

        assertTrue(
            "Production service files must use MindlayerLog, not android.util.Log: $violations",
            violations.isEmpty(),
        )
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).canonicalFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
        }
        return checkNotNull(dir) { "Could not locate repository root from ${System.getProperty("user.dir")}" }
    }
}
