package com.adsamcik.mindlayer.sdk.vision

import android.graphics.Bitmap
import android.util.Log
import com.adsamcik.mindlayer.sdk.InferenceHandle
import com.adsamcik.mindlayer.sdk.Mindlayer
import com.adsamcik.mindlayer.sdk.SessionScope

private const val TAG = "VisionTasks"

/** Soft cap (and prompt-side cap) on objects returned from [detectObjects]. */
private const val DEFAULT_MAX_OBJECTS = 32

/** Hard cap on [detectObjects]'s `maxObjects` parameter to prevent absurd budgets. */
private const val MAX_MAX_OBJECTS = 256

/**
 * Detect named objects in [image] using Gemma 4's native vision detection.
 *
 * Uses the canonical `"detect X, output only \`\`\`json"` prompt that
 * Gemma 4 is trained on, then parses the `box_2d` JSON array into typed
 * [DetectedObject]s with normalized coordinates. Returns an empty list
 * when the model produces non-JSON prose or the JSON is malformed — use
 * [detectObjectsResult] when you need to distinguish those failure modes
 * (e.g. for QA dashboards watching for prompt drift).
 *
 * # When to use this
 *
 * - Quickly localizing one or two known classes ("find the person and
 *   the dog in this photo") without bundling a separate detection model.
 * - **Open-vocabulary** detection where the label set varies per
 *   request — something a fixed-class detector like YOLO cannot do.
 * - Lightweight cropping for downstream steps (e.g. detect → crop →
 *   pass to OCR or a refinement prompt).
 *
 * # When *not* to use this
 *
 * - Very dense scenes (crowds, lots of small objects). Per the Gemma
 *   docs, the model under-counts in dense settings. Consider a dedicated
 *   detector for those.
 * - Pixel-perfect bounding boxes. The 0..1000 quantization grid limits
 *   resolution to ~1 px per 0.1% of image width, which is fine for
 *   thumbnails but coarser than a regression-head detector.
 *
 * # Limits
 *
 * - At most [VisionPrompts.MAX_LABELS] labels, each at most
 *   [VisionPrompts.MAX_LABEL_LENGTH] chars.
 * - At most [maxObjects] (default [DEFAULT_MAX_OBJECTS]; hard cap
 *   [MAX_MAX_OBJECTS]) results.
 *
 * # Cost
 *
 * Same as one [Mindlayer.describe] call: one image + one short prompt
 * through the existing vision-encoder + decode path. No additional
 * service round-trips or model loads.
 *
 * @param image bitmap to inspect. Sent through the existing
 *   `Mindlayer.infer { image(...) }` path — same encoding and same
 *   engine constraints as any other single-image inference.
 * @param labels classes to detect (e.g. `listOf("person", "car")`).
 *   Non-empty; passed to [VisionPrompts.detect].
 * @param maxObjects soft cap surfaced in the prompt; the parser also
 *   caps materialized output at this value.
 * @param configure optional session knobs (e.g. expiration). Most callers
 *   leave the default ephemeral session.
 * @return a list of [DetectedObject] in model-supplied order (typically
 *   confidence-descending, but the model does not commit to that).
 */
suspend fun Mindlayer.detectObjects(
    image: Bitmap,
    labels: List<String>,
    maxObjects: Int = DEFAULT_MAX_OBJECTS,
    configure: SessionScope.() -> Unit = {},
): List<DetectedObject> =
    detectObjectsResult(image, labels, maxObjects, configure)
        .objects
        .take(maxObjects.coerceIn(1, MAX_MAX_OBJECTS))

/**
 * Strict variant of [detectObjects] that returns a [DetectionResult] so
 * callers can distinguish:
 *
 * - [DetectionResult.Success] with `objects.isEmpty()` — model produced
 *   valid JSON `[]`, meaning "no objects of the requested classes are
 *   present in the image".
 * - [DetectionResult.NoStructuredOutput] — model responded with prose
 *   or a refusal. A sustained uptick here is an early warning that the
 *   prompt needs revisiting.
 * - [DetectionResult.ParseError] — JSON was found but malformed. Indicates
 *   a model regression or truncation (decode budget too small).
 *
 * Same inputs and same engine path as [detectObjects].
 */
suspend fun Mindlayer.detectObjectsResult(
    image: Bitmap,
    labels: List<String>,
    maxObjects: Int = DEFAULT_MAX_OBJECTS,
    configure: SessionScope.() -> Unit = {},
): DetectionResult {
    require(maxObjects in 1..MAX_MAX_OBJECTS) {
        "maxObjects must be in 1..$MAX_MAX_OBJECTS (got $maxObjects)"
    }
    // VisionPrompts.detect validates labels (non-empty, bounded count and length).
    val prompt = VisionPrompts.detect(labels, maxObjects)
    val response = infer {
        ephemeralSession(configure)
        text(prompt)
        image(image)
    }.let { (it as InferenceHandle.Text).awaitText() }

    val result = BoxParser.parse(response)
    logFailureMetadata(result, response.length)
    return result
}

/**
 * Locate the single best match for [description] in [image].
 *
 * Returns the first-emitted (Gemma's "best") [DetectedObject], or `null`
 * if the model did not produce a structured result. For multi-match or
 * strict failure detection use [detectObjects] / [detectObjectsResult].
 *
 * @throws IllegalArgumentException via [VisionPrompts.locate] if
 *   [description] is blank or too long.
 */
suspend fun Mindlayer.locateObject(
    image: Bitmap,
    description: String,
    configure: SessionScope.() -> Unit = {},
): DetectedObject? {
    val prompt = VisionPrompts.locate(description)
    val response = infer {
        ephemeralSession(configure)
        text(prompt)
        image(image)
    }.let { (it as InferenceHandle.Text).awaitText() }

    val result = BoxParser.parse(response)
    logFailureMetadata(result, response.length)
    return result.objects.firstOrNull()
}

/**
 * Caption [image] in one of the supported [CaptionStyle]s.
 *
 * Thin convenience over [Mindlayer.describe] with the canonical
 * [VisionPrompts.caption] template. Encodes the docs' best-practice
 * guidance (be specific, give constraints) for each style.
 */
suspend fun Mindlayer.captionImage(
    image: Bitmap,
    style: CaptionStyle = CaptionStyle.Short,
    configure: SessionScope.() -> Unit = {},
): String = describe(VisionPrompts.caption(style), image, configure)

/**
 * Describe [image] at the requested level of [detail], optionally focusing
 * on a named aspect.
 *
 * Thin convenience over [Mindlayer.describe] with the
 * [VisionPrompts.describe] template.
 *
 * @throws IllegalArgumentException via [VisionPrompts.describe] if
 *   [focus] is too long.
 */
suspend fun Mindlayer.describeImage(
    image: Bitmap,
    detail: DescribeDetail = DescribeDetail.Medium,
    focus: String? = null,
    configure: SessionScope.() -> Unit = {},
): String = describe(VisionPrompts.describe(detail, focus), image, configure)

/**
 * Count visible items matching [itemDescription] in [image].
 *
 * Returns the parsed integer from the first line of Gemma's response, or
 * `null` if the model failed to emit a leading integer. The Gemma vision
 * docs warn that counts are *approximate* for dense scenes — callers
 * surfacing the result to users should disclose that limitation. The
 * underlying prompt asks the model to follow up with an
 * "exact / approximate" line; advanced callers wanting both pieces should
 * call [describe] / [VisionPrompts.count] directly.
 */
suspend fun Mindlayer.countItems(
    image: Bitmap,
    itemDescription: String,
    configure: SessionScope.() -> Unit = {},
): Int? {
    val response = describe(VisionPrompts.count(itemDescription), image, configure)
    val firstLine = response.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return null
    return firstLine.toIntOrNull()
        ?: COUNT_INTEGER.find(firstLine)?.value?.toIntOrNull()
}

private val COUNT_INTEGER = Regex("""\d+""")

/**
 * Log only structural metadata for parse failures. **Never** log the raw
 * model output or any prompt content — those are caller-controlled and
 * the repo's privacy rules forbid logging prompt or model output.
 */
private fun logFailureMetadata(result: DetectionResult, responseLength: Int) {
    when (result) {
        is DetectionResult.Success -> Unit
        is DetectionResult.NoStructuredOutput ->
            Log.i(TAG, "vision detection: model produced no structured JSON in $responseLength chars")
        is DetectionResult.ParseError ->
            Log.w(
                TAG,
                "vision detection: parse error in $responseLength chars; " +
                    "recovered=${result.parsed.size}; reason=${result.message}",
            )
    }
}
