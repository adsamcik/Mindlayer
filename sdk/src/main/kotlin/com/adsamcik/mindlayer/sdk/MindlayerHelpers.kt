package com.adsamcik.mindlayer.sdk

import android.graphics.Bitmap
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Canonical-builder delegations for the high-level [Mindlayer] convenience
 * helpers (Spike-E §2).
 *
 * # Why a base class
 *
 * Spike-E requires every helper to be a *zero-logic* delegate to the canonical
 * builder ([Mindlayer.infer] / [Mindlayer.ocr] / [Mindlayer.embed] /
 * [Mindlayer.openSession]) so that drift-by-construction is impossible: a helper
 * cannot bypass a capability gate the canonical path enforces, and it can be
 * unit-tested against the canonical path rather than a service mock.
 *
 * Hosting the bodies on an `internal abstract class` (rather than inlining them
 * into [MindlayerImpl]) is what makes that test possible. A contract test does
 * `mockk<MindlayerHelpers>()`, stubs only the abstract canonical methods, and
 * uses `callOriginal()` on the `final` helper under test — so the assertion is
 * "the helper invoked the canonical builder with exactly this setup, no extras"
 * without dragging the whole AIDL pipeline (a `ConnectionManager`, a binder, a
 * `HistoryStore`) into the test. See
 * `sdk/src/test/kotlin/com/adsamcik/mindlayer/sdk/v1/HelperDelegationContractTest.kt`.
 *
 * Every body below is a single expression: one canonical-builder call plus the
 * statically-discriminated terminal (`(it as InferenceHandle.Text).awaitText()`
 * et al). No local control flow, no defaults that diverge from the builder.
 */
internal abstract class MindlayerHelpers : Mindlayer {

    final override suspend fun ask(prompt: String, configure: SessionScope.() -> Unit): String =
        infer {
            ephemeralSession(configure)
            text(prompt)
        }.let { (it as InferenceHandle.Text).awaitText() }

    final override suspend fun describe(
        prompt: String,
        image: Bitmap,
        configure: SessionScope.() -> Unit,
    ): String =
        infer {
            ephemeralSession(configure)
            text(prompt)
            image(image)
        }.let { (it as InferenceHandle.Text).awaitText() }

    final override suspend fun transcribe(
        prompt: String,
        audio: File,
        configure: SessionScope.() -> Unit,
    ): String =
        infer {
            ephemeralSession(configure)
            text(prompt)
            audio(audio)
        }.let { (it as InferenceHandle.Text).awaitText() }

    final override suspend fun transcribe(
        audio: File,
        language: String?,
        configure: SessionScope.() -> Unit,
    ): String = transcribe(
        prompt = GemmaAudioPrompts.transcriptionPrompt(language),
        audio = audio,
        configure = configure,
    )

    final override suspend fun extractJson(
        prompt: String,
        schema: JsonSchema,
        image: Bitmap?,
        audio: File?,
        configure: SessionScope.() -> Unit,
    ): JsonObject = infer {
        ephemeralSession(configure)
        text(prompt)
        if (image != null) image(image)
        if (audio != null) audio(audio)
        outputJson(schema)
    }.let { (it as InferenceHandle.Structured).awaitJson() }

    final override suspend fun vector(text: String, task: EmbeddingTask): FloatArray =
        embed {
            text(text, task = task)
        }.let { (it as EmbeddingHandle.Single).awaitVector() }

    final override suspend fun vectors(items: List<EmbeddingItem>): List<EmbeddingResultItem> =
        embed {
            items(items)
        }.let { (it as EmbeddingHandle.Batch).awaitVectors() }

    final override suspend fun readText(image: Bitmap, profile: OcrProfile): String =
        ocr {
            image(image)
            profile(profile)
        }.awaitResult().lines.joinToString("\n") { it.text }

    final override suspend fun readText(bytes: ByteArray, mimeType: String, profile: OcrProfile): String =
        ocr {
            image(bytes, mimeType)
            profile(profile)
        }.awaitResult().lines.joinToString("\n") { it.text }

    final override suspend fun readStructuredJson(image: Bitmap, schema: JsonSchema, profile: OcrProfile): JsonObject =
        ocr {
            image(image)
            profile(profile)
            extractWithLlm(schema)
        }.awaitResult().extractionJson
            ?: error("OCR produced no extractionJson; check schema and image quality")

    final override suspend fun <R> withSession(
        configure: SessionScope.() -> Unit,
        block: suspend MindlayerSession.() -> R,
    ): R = openSession(configure).use { session -> session.block() }
}
