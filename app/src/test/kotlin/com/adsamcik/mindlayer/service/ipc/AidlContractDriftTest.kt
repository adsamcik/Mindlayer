package com.adsamcik.mindlayer.service.ipc

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class AidlContractDriftTest {

    @Test
    fun `app and sdk service AIDL contracts stay in sync`() {
        val root = repoRoot()
        val appContract = readNormalized(root.resolve(
            "app/src/main/aidl/com/adsamcik/mindlayer/IMindlayerService.aidl",
        ))
        val sdkContract = readNormalized(root.resolve(
            "sdk/src/main/aidl/com/adsamcik/mindlayer/IMindlayerService.aidl",
        ))

        assertEquals(appContract, sdkContract)
    }

    private fun readNormalized(path: Path): String =
        String(Files.readAllBytes(path), Charsets.UTF_8)
            .replace("\r\n", "\n")
            .trim()

    private fun repoRoot(): Path {
        var current = Paths.get("").toAbsolutePath()
        while (!Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.parent
                ?: error("Could not find repository root from ${Paths.get("").toAbsolutePath()}")
        }
        return current
    }
}
