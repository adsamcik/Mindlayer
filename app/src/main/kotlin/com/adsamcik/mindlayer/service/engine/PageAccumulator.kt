package com.adsamcik.mindlayer.service.engine

/**
 * v0.9 multi-page realtime OCR — per-page line accumulator.
 *
 * One [PageAccumulator] holds the fused state of the camera's view of
 * "one page" — a stretch of frames that the boundary detector decided
 * showed similar content. Within a page, the same physical text line is
 * usually observed across multiple frames at slightly different
 * confidences; the accumulator clusters those observations by
 * normalised text + bounding-box IoU and keeps the highest-confidence
 * reading per cluster.
 *
 * # Why clusters?
 *
 * A naive "keep every line from every frame" approach would emit `N x
 * (frames per page)` lines in [OCR_PAGE_FINALIZED] and force the
 * downstream LLM to dedupe. Clustering pushes that dedupe to the
 * service side where we already have spatial geometry available.
 *
 * # Cluster identity
 *
 * Two readings cluster together when **both** hold:
 *  - their `text` matches after normalisation (lowercase, whitespace-
 *    collapsed) — handles per-frame OCR noise like spacing jitter;
 *  - their bounding boxes overlap with IoU > [BBOX_IOU_THRESHOLD] —
 *    handles the same line drifting a few pixels frame-to-frame.
 *
 * When either condition fails, the reading starts a new cluster. Lines
 * without bounding boxes (the engine's `emitBoundingBoxes=false` path)
 * still cluster purely by normalised text.
 *
 * # Threading
 *
 * NOT thread-safe. The dispatcher owns one accumulator per page and
 * serialises all writes through the per-session writer mutex, which is
 * the same mutex serialising `recognise()` calls.
 *
 * # No Android imports
 *
 * Pure JVM. Fully unit-testable.
 *
 * @param pageIndex zero-based ordinal of this page within its session.
 *   Surfaced verbatim in [OCR_PAGE_STARTED.pageIndex] /
 *   [OCR_PAGE_FINALIZED.pageIndex].
 */
class PageAccumulator(val pageIndex: Int) {

    /** Frame id that opened this page (passed through to `triggerFrameId`). */
    var triggerFrameId: Long = 0L
        private set

    /** Number of frames whose lines were merged into this accumulator. */
    var framesContributed: Int = 0
        private set

    private val clusters: MutableList<LineCluster> = mutableListOf()

    /**
     * Cached normalised-token set for the boundary detector's Jaccard
     * pass. Invalidated on every [extend] call.
     */
    private var cachedTokenSet: Set<String>? = null

    /**
     * Bounding-box centroid of the last extended frame, in normalised
     * 0..1 frame coordinates. `null` until [extend] runs at least once
     * with at least one bounding-boxed line. The boundary detector
     * uses this as the "previous frame centroid" anchor for its
     * spatial-shift signal.
     */
    var lastFrameBboxCentroid: Centroid? = null
        private set

    /**
     * One [PageAccumulator] is born with at least one frame's worth of
     * data; the first [extend] call also records [triggerFrameId] so
     * the dispatcher can populate the `OCR_PAGE_STARTED` payload's
     * triggerFrameId after the boundary detector fires (the dispatcher
     * keeps an indirection for the implicit page-0 case — see
     * [OcrRecognitionDispatcher]).
     */
    fun extend(frameLines: List<OcrTextLine>, frameId: Long) {
        if (framesContributed == 0) triggerFrameId = frameId
        framesContributed++

        for (line in frameLines) {
            val match = clusters.firstOrNull { it.matches(line) }
            if (match != null) {
                match.add(line)
            } else {
                clusters.add(LineCluster(line))
            }
        }

        cachedTokenSet = null
        lastFrameBboxCentroid = computeFrameCentroid(frameLines) ?: lastFrameBboxCentroid
    }

    /**
     * For each cluster, return the highest-confidence reading. Ties
     * resolve to the most-recently-added reading (since later frames
     * usually carry the latest in-focus snapshot of that line).
     *
     * Cluster order is insertion order — the first time the cluster was
     * seen — so downstream consumers get a stable list across frames.
     */
    fun bestLines(): List<OcrTextLine> = clusters.map { it.bestReading() }

    /** Number of distinct line clusters this page has accumulated. */
    fun lineCount(): Int = clusters.size

    /**
     * Newline-joined text of [bestLines]. Used as the LLM extractor's
     * per-page text input and as a building block for the aggregate
     * session-level extraction text.
     */
    fun aggregateText(): String = bestLines().joinToString(separator = "\n") { it.text }

    /**
     * Normalised token set for the boundary detector's Jaccard pass.
     * Same normalisation rules as [normaliseTokens]; cached until the
     * next [extend] call.
     */
    fun tokenSet(): Set<String> {
        cachedTokenSet?.let { return it }
        val tokens = mutableSetOf<String>()
        for (cluster in clusters) {
            tokens += normaliseTokens(cluster.bestReading().text)
        }
        return tokens.also { cachedTokenSet = it }
    }

    /** Sum of (x,y) values divided by count; null when no bbox present. */
    private fun computeFrameCentroid(lines: List<OcrTextLine>): Centroid? {
        var sumX = 0.0
        var sumY = 0.0
        var count = 0
        for (line in lines) {
            val centroid = bboxCentroid(line.boundingBox) ?: continue
            sumX += centroid.x
            sumY += centroid.y
            count++
        }
        if (count == 0) return null
        return Centroid(x = sumX / count, y = sumY / count)
    }

    // ── Helpers / data ───────────────────────────────────────────────────

    /**
     * Single text-line cluster — all the readings of "one physical
     * line" observed across a page's frames. Keyed by normalised text
     * + bounding-box IoU on the seed reading.
     */
    internal class LineCluster(seed: OcrTextLine) {
        private val readings: MutableList<OcrTextLine> = mutableListOf(seed)
        private val normalisedSeed: String = normaliseText(seed.text)

        fun matches(line: OcrTextLine): Boolean {
            if (normaliseText(line.text) != normalisedSeed) return false
            val seedBox = readings.first().boundingBox
            val candidateBox = line.boundingBox
            if (seedBox == null || candidateBox == null) {
                // Without geometry, normalised-text match is sufficient.
                return true
            }
            return bboxIoU(seedBox, candidateBox) > BBOX_IOU_THRESHOLD
        }

        fun add(line: OcrTextLine) {
            readings += line
        }

        /**
         * Highest-confidence reading. Ties pick the latest (highest
         * index) — most-recent in-focus snapshot wins.
         */
        fun bestReading(): OcrTextLine {
            var best = readings[0]
            for (i in 1 until readings.size) {
                val candidate = readings[i]
                if (candidate.confidence.ordinal >= best.confidence.ordinal) {
                    best = candidate
                }
            }
            return best
        }
    }

    /** Centroid of a normalised 0..1 frame coordinate. */
    data class Centroid(val x: Double, val y: Double)

    companion object {
        /**
         * Bounding-box IoU above this threshold groups two readings of
         * "the same line" into one cluster. 0.5 is the standard object-
         * detection convention; it accommodates a few-pixel jitter
         * between frames without merging distinct lines that just
         * happen to share a normalised text (e.g. two "0.00" totals on
         * different rows).
         */
        const val BBOX_IOU_THRESHOLD: Double = 0.5

        /**
         * Tokens shorter than this length are dropped from the
         * Jaccard-comparison set. Two-character connectives carry
         * almost no document identity and bias short pages toward
         * "looks the same as everything".
         */
        const val MIN_TOKEN_LENGTH: Int = 3

        /**
         * Normalise text for cluster identity: lowercase, collapse
         * whitespace runs, trim. Keeps unicode characters intact so
         * non-Latin scripts cluster correctly.
         */
        internal fun normaliseText(text: String): String {
            val sb = StringBuilder(text.length)
            var lastWasSpace = false
            for (ch in text) {
                if (ch.isWhitespace()) {
                    if (!lastWasSpace && sb.isNotEmpty()) sb.append(' ')
                    lastWasSpace = true
                } else {
                    sb.append(ch.lowercaseChar())
                    lastWasSpace = false
                }
            }
            // Trim trailing space if any.
            if (sb.isNotEmpty() && sb.last() == ' ') sb.deleteCharAt(sb.length - 1)
            return sb.toString()
        }

        /**
         * Tokenise + filter for Jaccard. Lowercase, split on whitespace,
         * drop tokens shorter than [MIN_TOKEN_LENGTH].
         */
        internal fun normaliseTokens(text: String): Set<String> {
            if (text.isBlank()) return emptySet()
            val out = mutableSetOf<String>()
            for (raw in text.split(WHITESPACE_REGEX)) {
                if (raw.length < MIN_TOKEN_LENGTH) continue
                out += raw.lowercase()
            }
            return out
        }

        private val WHITESPACE_REGEX = Regex("\\s+")

        /**
         * Centroid of an 8-float clockwise quad in normalised 0..1
         * frame coordinates. Returns null when the input is null or
         * malformed.
         */
        internal fun bboxCentroid(bbox: FloatArray?): Centroid? {
            if (bbox == null || bbox.size != 8) return null
            var sumX = 0.0
            var sumY = 0.0
            for (i in 0 until 4) {
                sumX += bbox[i * 2]
                sumY += bbox[i * 2 + 1]
            }
            return Centroid(x = sumX / 4.0, y = sumY / 4.0)
        }

        /**
         * Axis-aligned IoU on the bounding boxes of two clockwise
         * quads. Conservative: takes the AABB rather than computing
         * polygon intersection — adequate for cluster identity.
         */
        internal fun bboxIoU(a: FloatArray, b: FloatArray): Double {
            if (a.size != 8 || b.size != 8) return 0.0
            val ax1 = minOf(a[0], a[2], a[4], a[6])
            val ax2 = maxOf(a[0], a[2], a[4], a[6])
            val ay1 = minOf(a[1], a[3], a[5], a[7])
            val ay2 = maxOf(a[1], a[3], a[5], a[7])
            val bx1 = minOf(b[0], b[2], b[4], b[6])
            val bx2 = maxOf(b[0], b[2], b[4], b[6])
            val by1 = minOf(b[1], b[3], b[5], b[7])
            val by2 = maxOf(b[1], b[3], b[5], b[7])

            val ix1 = maxOf(ax1, bx1)
            val iy1 = maxOf(ay1, by1)
            val ix2 = minOf(ax2, bx2)
            val iy2 = minOf(ay2, by2)
            if (ix1 >= ix2 || iy1 >= iy2) return 0.0

            val interArea = (ix2 - ix1).toDouble() * (iy2 - iy1).toDouble()
            val areaA = (ax2 - ax1).toDouble() * (ay2 - ay1).toDouble()
            val areaB = (bx2 - bx1).toDouble() * (by2 - by1).toDouble()
            val unionArea = areaA + areaB - interArea
            if (unionArea <= 0.0) return 0.0
            return interArea / unionArea
        }
    }
}
