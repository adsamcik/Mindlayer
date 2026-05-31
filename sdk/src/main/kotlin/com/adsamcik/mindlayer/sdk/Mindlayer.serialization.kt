@file:Suppress("unused")

package com.adsamcik.mindlayer.sdk

import android.graphics.Bitmap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.File

/**
 * Reified `kotlinx.serialization` conveniences for structured extraction
 * (Spike-E §8.2).
 *
 * These helpers live in `:sdk` rather than a separate artifact: a consumer that
 * already has `kotlinx-serialization-json` on its classpath gets typed decoding
 * for free, while a consumer that does not can stay on the [JsonObject][
 * kotlinx.serialization.json.JsonObject]-returning primitives
 * ([Mindlayer.extractJson], [InferenceHandle.Structured.awaitJson]) and never
 * load the serializer.
 *
 * Because they are `inline`, the body is compiled into the call site, so the
 * `kotlinx.serialization.json` reference is resolved against the *consumer's*
 * classpath. The end-state SDK declares the serializer as `compileOnly`; once
 * that lands, calling one of these helpers from a module that omits the runtime
 * dependency throws [NoClassDefFoundError]. The reified type [T] must be
 * `@Serializable`.
 *
 * ## C2 deviation status — kept per LOCKED decision (Spike-E §8.2)
 *
 * The `:sdk` module keeps `kotlinx-serialization-json` as `implementation`
 * (C1 deviation #2), not `compileOnly`. Flipping to `compileOnly` is a build-
 * graph change that affects every consumer's runtime classpath and is sequenced
 * with the consumer migration in C3; doing it now would break the in-tree
 * `:app` consumer that has not yet migrated. The inline-at-call-site design
 * above is already in place, so the eventual flip is dependency-only and
 * source-compatible. `@file:Suppress("unused")` covers the public reified
 * helpers that have no in-module caller yet.
 *
 * Behaviour (the underlying `extractJson` / `awaitJson` calls) lands in C2; in
 * C1 these helpers compile but the primitives they delegate to throw.
 */
suspend inline fun <reified T> Mindlayer.extract(
    prompt: String,
    schema: JsonSchema,
    image: Bitmap? = null,
    audio: File? = null,
    json: Json = MindlayerJson,
    noinline configure: SessionScope.() -> Unit = {},
): T = json.decodeFromJsonElement(extractJson(prompt, schema, image, audio, configure))

/**
 * Await a [InferenceHandle.Structured] result and decode it into [T].
 *
 * See [extract] for the `compileOnly` / [NoClassDefFoundError] caveat. [T] must
 * be `@Serializable`. Behaviour lands in C2.
 */
suspend inline fun <reified T> InferenceHandle.Structured.await(
    json: Json = MindlayerJson,
): T = json.decodeFromJsonElement(awaitJson())

/**
 * Lenient [Json] used by the reified [extract] / [await] helpers when the
 * caller does not supply one. Tolerates unknown keys so a schema superset on
 * the model side does not break decoding.
 */
@PublishedApi
internal val MindlayerJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}
