package com.adsamcik.mindlayer.service.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ComposeStringResourceTest {
    @Test
    fun `Compose Text calls do not use hardcoded English literals`() {
        val uiDir = listOf(
            File("app/src/main/kotlin/com/adsamcik/mindlayer/service/ui"),
            File("src/main/kotlin/com/adsamcik/mindlayer/service/ui"),
        ).first { it.exists() }
        val violations = uiDir.listFiles { file -> file.extension == "kt" }.orEmpty()
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val trimmed = line.trim()
                    val textLiteral = Regex("Text\\(\\s*\\\"([^\\\"]*)\\\"").find(trimmed)
                        ?: Regex("text\\s*=\\s*\\\"([^\\\"]*)\\\"").find(trimmed)
                    val literal = textLiteral?.groupValues?.get(1) ?: return@mapIndexedNotNull null
                    if (!literal.contains("$") && !literal.startsWith("%") && literal.any { it.isLetter() }) "${file.name}:${index + 1}:$trimmed" else null
                }
            }
        assertTrue("Hardcoded English Text literals found:\n${violations.joinToString("\n")}", violations.isEmpty())
    }
}