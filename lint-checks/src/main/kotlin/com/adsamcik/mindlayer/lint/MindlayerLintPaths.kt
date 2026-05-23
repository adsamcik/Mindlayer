package com.adsamcik.mindlayer.lint

import java.io.File

internal object MindlayerLintPaths {
    fun normalized(file: File): String = file.path.replace('\\', '/')

    fun isAppMainKotlin(file: File): Boolean =
        containsPath(normalized(file), "app/src/main/kotlin/")

    fun isMindlayerLog(file: File): Boolean = normalized(file).endsWith("/MindlayerLog.kt")

    fun isNativeLoggingPath(file: File): Boolean {
        val path = normalized(file)
        return containsPath(path, "app/src/main/kotlin/com/adsamcik/mindlayer/service/engine/") ||
            containsPath(path, "app/src/main/kotlin/com/adsamcik/mindlayer/service/ipc/") ||
            path.endsWith("/app/src/main/kotlin/com/adsamcik/mindlayer/service/ServiceBinder.kt") ||
            path == "app/src/main/kotlin/com/adsamcik/mindlayer/service/ServiceBinder.kt"
    }

    fun isAppOrSdkBuildFile(file: File): Boolean {
        val path = normalized(file)
        return path.endsWith("/app/build.gradle.kts") ||
            path == "app/build.gradle.kts" ||
            path.endsWith("/sdk/build.gradle.kts") ||
            path == "sdk/build.gradle.kts"
    }

    fun isAppOrSdkMainManifest(file: File): Boolean {
        val path = normalized(file)
        return path.endsWith("/app/src/main/AndroidManifest.xml") ||
            path == "app/src/main/AndroidManifest.xml" ||
            path.endsWith("/app/AndroidManifest.xml") ||
            path.endsWith("/sdk/src/main/AndroidManifest.xml") ||
            path == "sdk/src/main/AndroidManifest.xml" ||
            path.endsWith("/sdk/AndroidManifest.xml")
    }

    private fun containsPath(path: String, needle: String): Boolean =
        path.contains("/$needle") || path.startsWith(needle)
}
