package com.adsamcik.mindlayer.sdk.vision

/**
 * One object localized by Gemma 4 in an image.
 *
 * Produced by [Mindlayer.detectObjects][com.adsamcik.mindlayer.sdk.detectObjects]
 * and [Mindlayer.locateObject][com.adsamcik.mindlayer.sdk.locateObject] from
 * Gemma's native `{"box_2d": [...], "label": "..."}` JSON detection format.
 *
 * @property label the class string Gemma assigned to the region. Trimmed
 *   non-empty; emitted verbatim from the model — callers that requested a
 *   strict label set should post-filter, since open-vocabulary models
 *   occasionally synonymize ("car" → "automobile").
 * @property box normalized bounding box in 0..1 image coordinates. Use
 *   [VisionBoundingBox.toPixelRect] to project onto a specific image size.
 */
data class DetectedObject(
    val label: String,
    val box: VisionBoundingBox,
)
