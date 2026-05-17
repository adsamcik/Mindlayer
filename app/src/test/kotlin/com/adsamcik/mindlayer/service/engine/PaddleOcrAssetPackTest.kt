package com.adsamcik.mindlayer.service.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * v0.8 OCR (PR B): wire-stability + integrity tests for the
 * `:paddleocr_model` AAB asset pack.
 *
 * These tests pin the structural invariants the service-side OCR engine
 * (PR C) relies on:
 *
 *  1. The module is registered in `settings.gradle.kts` and listed in
 *     the root `app/build.gradle.kts` `assetPacks` declaration.
 *  2. The conversion-pipeline workflow is checked in, satisfies the
 *     7-day soak rule, and pins the exact tool versions the integrity
 *     manifest claims its artifacts were produced with.
 *  3. The integrity manifest carries the schema the service-side
 *     registry reads (mirroring `EmbeddingModelRegistry` parsing).
 *  4. The default committed manifest uses all-zero SHA-256 placeholders
 *     so that an accidental release build without `-PpaddleOcr*Sha256`
 *     fails fast in `:paddleocr_model:generatePaddleOcrModelIntegrityManifest`.
 *
 * No Robolectric: pure JVM, reads files off the repository root.
 */
class PaddleOcrAssetPackTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val sha256Pattern = Regex("^[0-9a-f]{64}$")

    private fun repoRoot(): Path {
        var current = Paths.get("").toAbsolutePath()
        while (!Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.parent
                ?: error("Could not find repository root from ${Paths.get("").toAbsolutePath()}")
        }
        return current
    }

    // ── module registration ──────────────────────────────────────────────

    @Test fun `paddleocr_model module is registered in settings`() {
        val settings = repoRoot().resolve("settings.gradle.kts")
        val text = Files.readString(settings)
        assertTrue(
            "settings.gradle.kts must include the :paddleocr_model module so the asset pack is built.",
            text.contains("include(\":paddleocr_model\")"),
        )
    }

    @Test fun `paddleocr_model is listed in app assetPacks`() {
        val appBuild = repoRoot().resolve("app/build.gradle.kts")
        val text = Files.readString(appBuild)
        val assetPacksLine = text.lineSequence()
            .firstOrNull { it.contains("assetPacks += listOf(") }
            ?: error("app/build.gradle.kts does not declare any assetPacks list.")
        assertTrue(
            "app/build.gradle.kts assetPacks must include :paddleocr_model so the pack ships in the AAB.",
            assetPacksLine.contains(":paddleocr_model"),
        )
    }

    @Test fun `app build script wires release SHA validator for paddleocr_model`() {
        val appBuild = repoRoot().resolve("app/build.gradle.kts")
        val text = Files.readString(appBuild)
        assertTrue(
            "app/build.gradle.kts must register validateReleasePaddleOcrSha256.",
            text.contains("validateReleasePaddleOcrSha256"),
        )
        assertTrue(
            "Release packaging tasks must depend on validateReleasePaddleOcrSha256.",
            text.contains("validateReleaseModelSha256, validateReleasePaddleOcrSha256"),
        )
    }

    // ── integrity manifest schema ────────────────────────────────────────

    @Test fun `integrity manifest is valid JSON with required top-level fields`() {
        val manifest = readManifest()
        assertEquals(1, manifest["schema"]?.jsonPrimitive?.contentOrNull?.toIntOrNull())
        assertNotNull("'version' field is required", manifest["version"]?.jsonPrimitive?.contentOrNull)
        assertEquals(
            "paddleocr-ppocrv5-mobile",
            manifest["engine"]?.jsonPrimitive?.contentOrNull,
        )
    }

    @Test fun `integrity manifest pins conversion tool versions`() {
        val manifest = readManifest()
        val converted = manifest["converted_by"]?.jsonObject
            ?: error("'converted_by' object is required")
        assertEquals(
            "paddle2onnx version drift",
            "2.1.0",
            converted["paddle2onnx"]?.jsonPrimitive?.contentOrNull,
        )
        assertEquals(
            "onnx2tf version drift",
            "2.4.0",
            converted["onnx2tf"]?.jsonPrimitive?.contentOrNull,
        )
    }

    @Test fun `integrity manifest carries all four roles`() {
        val models = readManifest()["models"]?.jsonArray
            ?: error("'models' array is required")
        val rolesPresent = models.map {
            (it.jsonObject["role"] as? JsonPrimitive)?.contentOrNull
        }.toSet()
        assertEquals(
            "Manifest must list det/rec/cls/dict roles",
            setOf("detection", "recognition", "orientation", "dictionary"),
            rolesPresent,
        )
    }

    @Test fun `integrity manifest filenames follow the safe naming pattern`() {
        val models = readManifest()["models"]?.jsonArray ?: error("'models' missing")
        val filenamePattern = Regex(
            "^paddleocr-ppocrv5-mobile-(det|rec|cls)\\.tflite$|" +
                "^paddleocr-ppocrv5-mobile-dict\\.txt$",
        )
        for (entry in models) {
            val filename = (entry.jsonObject["filename"] as? JsonPrimitive)?.contentOrNull
                ?: error("Each model entry needs a 'filename'")
            assertTrue(
                "Filename '$filename' does not match the safe pattern. " +
                    "PaddleOcrModelRegistry will reject it.",
                filenamePattern.matches(filename),
            )
        }
    }

    @Test fun `integrity manifest sha256 fields are valid 64-hex strings`() {
        val models = readManifest()["models"]?.jsonArray ?: error("'models' missing")
        for (entry in models) {
            val sha = (entry.jsonObject["sha256"] as? JsonPrimitive)?.contentOrNull
                ?: error("Each model entry needs a 'sha256'")
            assertTrue(
                "SHA-256 '$sha' must be 64 lowercase hex chars (use all zeros for the " +
                    "committed placeholder).",
                sha256Pattern.matches(sha),
            )
        }
    }

    @Test fun `committed integrity manifest carries all-zero placeholders`() {
        val models = readManifest()["models"]?.jsonArray ?: error("'models' missing")
        val zero = "0".repeat(64)
        for (entry in models) {
            val sha = entry.jsonObject["sha256"]?.jsonPrimitive?.contentOrNull
            assertEquals(
                "Committed paddleocr_model_integrity.json must use all-zero placeholders. " +
                    "Real SHA-256 values are injected at release-build time via " +
                    "-PpaddleOcrDetSha256/-PpaddleOcrRecSha256/-PpaddleOcrClsSha256/" +
                    "-PpaddleOcrDictSha256 — committing real hashes leaks the conversion " +
                    "output to source control.",
                zero,
                sha,
            )
        }
    }

    // ── CI conversion pipeline ───────────────────────────────────────────

    @Test fun `conversion workflow is checked in`() {
        val workflow = repoRoot()
            .resolve(".github/workflows/build-paddleocr-models.yml")
        assertTrue(
            "Phase 1 PR B requires the conversion workflow at " +
                ".github/workflows/build-paddleocr-models.yml.",
            Files.isRegularFile(workflow),
        )
    }

    @Test fun `conversion workflow pins matching tool versions`() {
        val workflow = repoRoot()
            .resolve(".github/workflows/build-paddleocr-models.yml")
        val text = Files.readString(workflow)
        // Versions must agree with the integrity manifest's converted_by
        // block — otherwise the manifest will lie about what produced the
        // artifacts.
        assertTrue(
            "Workflow must default paddle2onnx to 2.1.0 (matches manifest converted_by).",
            text.contains("default: \"2.1.0\""),
        )
        assertTrue(
            "Workflow must default onnx2tf to 2.4.0 (matches manifest converted_by).",
            text.contains("default: \"2.4.0\""),
        )
        assertTrue(
            "Workflow must default the PaddleOCR ref to v3.5.0 (latest soaked).",
            text.contains("default: \"v3.5.0\""),
        )
    }

    @Test fun `conversion workflow is manually triggered only`() {
        val workflow = repoRoot()
            .resolve(".github/workflows/build-paddleocr-models.yml")
        val text = Files.readString(workflow)
        assertTrue(
            "Conversion workflow must be workflow_dispatch only (not on push/PR).",
            text.contains("workflow_dispatch"),
        )
        // Defense in depth: the workflow downloads upstream PaddleOCR
        // sources at run time, so it must not be triggered by random PRs.
        // Explicit absence of `on: push` and `on: pull_request` blocks.
        assertTrue(
            "Conversion workflow must not trigger on push events.",
            !text.contains(Regex("^\\s+push:", RegexOption.MULTILINE)),
        )
        assertTrue(
            "Conversion workflow must not trigger on pull_request events.",
            !text.contains(Regex("^\\s+pull_request:", RegexOption.MULTILINE)),
        )
    }

    // ── gitignore wires the new artifacts ────────────────────────────────

    @Test fun `gitignore excludes paddleocr-ppocrv5-mobile artifacts`() {
        val gitignore = repoRoot().resolve(".gitignore")
        val text = Files.readString(gitignore)
        assertTrue(
            ".gitignore must exclude *.tflite so the converted models never land in source control.",
            text.contains(Regex("(?m)^\\*\\.tflite$")),
        )
        assertTrue(
            ".gitignore must exclude *.onnx so the intermediate ONNX files never land in source control.",
            text.contains(Regex("(?m)^\\*\\.onnx$")),
        )
        assertTrue(
            ".gitignore must exclude the dictionary text artifact too.",
            text.contains("paddleocr_model/src/main/assets/paddleocr-ppocrv5-mobile-"),
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun readManifest(): JsonObject {
        val path = repoRoot()
            .resolve("paddleocr_model/src/main/assets/paddleocr_model_integrity.json")
        assertTrue(
            "paddleocr_model_integrity.json must be checked in even when artifacts are not.",
            Files.isRegularFile(path),
        )
        return json.parseToJsonElement(Files.readString(path)).jsonObject
    }
}
