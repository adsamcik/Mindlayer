package com.adsamcik.mindlayer.service.engine

import kotlin.math.abs

/**
 * v0.9 multi-page realtime OCR — fused page-boundary detector.
 *
 * Combines four signals to decide when the camera has moved off the
 * page it was looking at:
 *
 *  1. **Jaccard similarity** on normalised token sets between the
 *     previous page accumulator and the new frame's recognised lines.
 *     Low Jaccard ⇒ different content. Token normalisation rules live
 *     in [PageAccumulator.normaliseTokens] (lowercase, whitespace-
 *     split, drop length-2-and-shorter tokens).
 *  2. **Spatial bbox-centroid displacement** between the previous
 *     frame's centroid and the new frame's centroid, both in
 *     normalised 0..1 frame coordinates. Large shift ⇒ camera pan or
 *     re-aim.
 *  3. **Gyro spike** from
 *     [com.adsamcik.mindlayer.OcrFrameMeta.extraJson.imu.gyro_max_rad_per_s].
 *     Above-threshold magnitude ⇒ physical motion.
 *  4. **N-frame stability window** — boundary only fires once
 *     [config].stabilityFrames consecutive frames all signal
 *     "different content". One-frame glitches (a focus-search frame, a
 *     hand briefly covering the page) never trip a boundary.
 *
 * The combined rule:
 *
 * ```
 * isDifferent     := (jaccard < jaccardThreshold)
 *                 OR (spatialShift > spatialThreshold)
 *                 OR (gyro > gyroThreshold)
 * isBoundary(N)   := isDifferent_N AND isDifferent_{N-1} AND … AND isDifferent_{N-stabilityFrames+1}
 * ```
 *
 * # Reset behaviour
 *
 * After the detector returns `true` (boundary fires), the consecutive-
 * different counter resets. The next boundary then needs another
 * `stabilityFrames` consecutive different frames — i.e. one boundary
 * fire per stretch of changed content, not one fire per frame after
 * the stretch begins.
 *
 * # Threading
 *
 * NOT thread-safe — the dispatcher owns one detector per session and
 * calls it from the per-session writer mutex. Pure JVM; no Android
 * imports.
 */
class PageBoundaryDetector(val config: PageBoundariesConfig) {

    /**
     * Number of consecutive frames that have signalled "different
     * content" without yet hitting the stability threshold. Reset to
     * zero on a "same content" frame and on every boundary fire.
     */
    var consecutiveDifferentFrames: Int = 0
        private set

    /**
     * Decide whether [newLines] / [imu] mark a page boundary against
     * the in-flight [prev] accumulator.
     *
     * Returns `true` exactly once per stretch of changed content: the
     * `stabilityFrames`-th consecutive different frame triggers the
     * boundary, and the internal counter resets so the next boundary
     * requires a fresh `stabilityFrames` streak.
     */
    fun isBoundary(
        prev: PageAccumulator,
        newLines: List<OcrTextLine>,
        imu: ImuFrameMetadata,
    ): Boolean {
        if (!config.enabled) return false

        // The very first frame after a fresh page is meaningless to
        // compare — let it seed the accumulator and never fire a
        // boundary on it. The dispatcher always calls extend() before
        // the next isBoundary(), so this guards the edge cleanly.
        if (prev.framesContributed == 0) {
            consecutiveDifferentFrames = 0
            return false
        }

        val different = isDifferent(prev, newLines, imu)
        if (different) {
            consecutiveDifferentFrames++
            if (consecutiveDifferentFrames >= config.stabilityFrames) {
                consecutiveDifferentFrames = 0
                return true
            }
        } else {
            consecutiveDifferentFrames = 0
        }
        return false
    }

    private fun isDifferent(
        prev: PageAccumulator,
        newLines: List<OcrTextLine>,
        imu: ImuFrameMetadata,
    ): Boolean {
        val prevTokens = prev.tokenSet()
        val newTokens = newLinesTokens(newLines)

        val jaccard = jaccard(prevTokens, newTokens)
        if (jaccard < config.jaccardThreshold) return true

        val newCentroid = frameCentroid(newLines)
        val prevCentroid = prev.lastFrameBboxCentroid
        if (newCentroid != null && prevCentroid != null) {
            val dx = abs(newCentroid.x - prevCentroid.x)
            val dy = abs(newCentroid.y - prevCentroid.y)
            if (dx + dy > config.spatialThreshold) return true
        }

        if (imu.gyroMaxRadPerS > config.gyroThreshold) return true

        return false
    }

    private fun newLinesTokens(lines: List<OcrTextLine>): Set<String> {
        if (lines.isEmpty()) return emptySet()
        val out = mutableSetOf<String>()
        for (line in lines) {
            out += PageAccumulator.normaliseTokens(line.text)
        }
        return out
    }

    private fun frameCentroid(lines: List<OcrTextLine>): PageAccumulator.Centroid? {
        var sumX = 0.0
        var sumY = 0.0
        var count = 0
        for (line in lines) {
            val centroid = PageAccumulator.bboxCentroid(line.boundingBox) ?: continue
            sumX += centroid.x
            sumY += centroid.y
            count++
        }
        if (count == 0) return null
        return PageAccumulator.Centroid(x = sumX / count, y = sumY / count)
    }

    companion object {
        /**
         * Standard set-Jaccard. Two empty sets return 1.0 ("identical
         * nothing") so a blank-frame-followed-by-blank-frame doesn't
         * spuriously trip a boundary on jaccard alone — the gyro /
         * spatial signals can still fire if the camera is actually
         * moving.
         */
        internal fun jaccard(a: Set<String>, b: Set<String>): Double {
            if (a.isEmpty() && b.isEmpty()) return 1.0
            if (a.isEmpty() || b.isEmpty()) return 0.0
            val intersection = a.count { it in b }
            val union = a.size + b.size - intersection
            return intersection.toDouble() / union.toDouble()
        }
    }
}
