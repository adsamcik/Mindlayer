package com.adsamcik.mindlayer.sdk.vision

import android.graphics.Rect
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Axis-aligned bounding box in normalized image coordinates (0..1).
 *
 * # Why normalized 0..1
 *
 * Gemma 4 vision emits detection outputs in a **fixed 0..1000 grid** using
 * the order `[y1, x1, y2, x2]` (top, left, bottom, right) regardless of the
 * source image's actual resolution. See
 * https://ai.google.dev/gemma/docs/capabilities/vision/image .
 *
 * Storing the parsed result as 0..1 fractions decouples the box from any
 * particular pixel dimension, lets callers project the same box onto a
 * thumbnail and a full-resolution copy, and matches how
 * [com.adsamcik.mindlayer.OcrImageLine.boundingBox] already represents
 * OCR quadrilaterals. (The two types stay parallel rather than sharing a
 * base because OCR quads are 8-float oriented rectangles while detection
 * boxes are 4-float axis-aligned rectangles — a shared base would be too
 * weak to be useful.)
 *
 * # Invariants
 *
 * All four components are finite and in `[0..1]`, with `left <= right` and
 * `top <= bottom`. The constructor validates and throws
 * [IllegalArgumentException] otherwise; the [fromBox2d] factory returns
 * `null` instead so a single malformed entry in a multi-object detection
 * does not drop every other valid entry.
 *
 * Callers do not normally construct this type directly — it is produced by
 * [BoxParser] from a Gemma vision response.
 */
data class VisionBoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) {
            "VisionBoundingBox components must be finite (left=$left top=$top right=$right bottom=$bottom)"
        }
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f) {
            "VisionBoundingBox components must be in [0..1] (left=$left top=$top right=$right bottom=$bottom)"
        }
        require(left <= right) { "left ($left) must be <= right ($right)" }
        require(top <= bottom) { "top ($top) must be <= bottom ($bottom)" }
    }

    /** Width as a fraction of image width. */
    val widthFraction: Float get() = right - left

    /** Height as a fraction of image height. */
    val heightFraction: Float get() = bottom - top

    /**
     * Project this normalized box onto an image of the given pixel
     * dimensions.
     *
     * Rounding strategy: `floor` on left/top and `ceil` on right/bottom so
     * the resulting [Rect] strictly contains the model's region (no edge
     * pixels lost to rounding). The result is clamped to
     * `[0, imageWidth] x [0, imageHeight]` — Android's [Rect] uses
     * right- and bottom-exclusive coordinates, so a box flush against the
     * image edge produces `right = imageWidth` and `bottom = imageHeight`.
     *
     * @throws IllegalArgumentException if either dimension is non-positive.
     */
    fun toPixelRect(imageWidth: Int, imageHeight: Int): Rect {
        require(imageWidth > 0 && imageHeight > 0) {
            "image dimensions must be positive (got ${imageWidth}x$imageHeight)"
        }
        val leftPx = floor(left * imageWidth).toInt().coerceIn(0, imageWidth)
        val topPx = floor(top * imageHeight).toInt().coerceIn(0, imageHeight)
        val rightPx = ceil(right * imageWidth).toInt().coerceIn(leftPx, imageWidth)
        val bottomPx = ceil(bottom * imageHeight).toInt().coerceIn(topPx, imageHeight)
        return Rect(leftPx, topPx, rightPx, bottomPx)
    }

    companion object {
        /**
         * Build a normalized box from Gemma's `[y1, x1, y2, x2]` 0..1000-grid
         * coordinate array. Returns `null` for any of:
         *
         * - wrong length (not exactly 4 entries)
         * - non-finite values (`NaN` / `Infinity`)
         * - values outside `0..1000`
         * - `y2 < y1` or `x2 < x1` (degenerate or inverted)
         *
         * Returning `null` rather than throwing lets the parser drop a single
         * bad entry without losing the rest of a multi-object detection.
         */
        internal fun fromBox2d(box2d: List<Float>): VisionBoundingBox? {
            if (box2d.size != 4) return null
            if (box2d.any { !it.isFinite() }) return null
            if (box2d.any { it < 0f || it > 1000f }) return null
            val y1 = box2d[0]
            val x1 = box2d[1]
            val y2 = box2d[2]
            val x2 = box2d[3]
            if (y2 < y1 || x2 < x1) return null
            return VisionBoundingBox(
                left = x1 / 1000f,
                top = y1 / 1000f,
                right = x2 / 1000f,
                bottom = y2 / 1000f,
            )
        }
    }
}
