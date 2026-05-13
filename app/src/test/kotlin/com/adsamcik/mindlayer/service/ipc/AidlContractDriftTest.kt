package com.adsamcik.mindlayer.service.ipc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class AidlContractDriftTest {

    @Test
    fun `app and sdk AIDL contracts stay in sync`() {
        val root = repoRoot()
        val appDir = root.resolve("app/src/main/aidl/com/adsamcik/mindlayer")
        val sdkDir = root.resolve("sdk/src/main/aidl/com/adsamcik/mindlayer")
        val appFiles = Files.list(appDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".aidl") }
                .map { it.fileName.toString() }
                .sorted()
                .toList()
        }

        val sdkFiles = Files.list(sdkDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".aidl") }
                .map { it.fileName.toString() }
                .sorted()
                .toList()
        }
        assertEquals("App and SDK AIDL file sets differ", sdkFiles, appFiles)
        appFiles.forEach { fileName ->
            val appBytes = readNormalized(appDir.resolve(fileName))
            val sdkBytes = readNormalized(sdkDir.resolve(fileName))
            assertArrayEquals("AIDL drift in $fileName", appBytes, sdkBytes)
        }
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
}
