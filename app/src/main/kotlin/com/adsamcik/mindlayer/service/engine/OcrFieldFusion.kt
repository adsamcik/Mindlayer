package com.adsamcik.mindlayer.service.engine

/**
 * Cross-frame field fusion via per-field weighted voting.
 *
 * Each observation of a structured-output field (e.g. ``total``,
 * ``mrz_line_1``, ``invoice_number``) arrives from a single frame's
 * Gemma extraction pass with a [Confidence] verbalized hint. This
 * fusion module aggregates observations across frames and exposes
 *
 *  - the current best value per field
 *  - whether that value is "locked" (K-consecutive matching reads)
 *  - the per-value evidence score so consumers can render uncertainty
 *
 * # Why verbalized confidence, not log-probabilities
 *
 * LiteRT-LM 0.10.x Kotlin does not expose per-token log-probabilities.
 * Frame-level extraction asks Gemma to emit `confidence: high|medium|low`
 * alongside each field; this module then maps those to numeric weights.
 * If/when LiteRT-LM surfaces logprobs (gated by capability flag
 * ``FEATURE_OCR_LOGPROB_FUSION``), [FusionConfig.weightOf] can switch
 * over to the logprob-derived path with no observer-visible change.
 *
 * # Quality-weighted observations
 *
 * Each observation also carries a `frameQuality` weight in [0..1] so
 * a clearly-blurry frame's vote contributes less than a sharp one
 * even if both report `high` confidence. The default sources are the
 * [OcrFrameQualityPresort.FrameQualityScore] outputs.
 *
 * # K-consecutive lock
 *
 * Once the same value has been the top vote for [FusionConfig.kLock]
 * consecutive [accept] calls (i.e. K successive frames that contained
 * that field), the field is marked ``locked`` — downstream emitters
 * (PR C3) surface an `ocr_field_locked` event and stop spending decode
 * tokens on that field. Locking is reversible if a higher-weighted
 * disagreeing observation arrives; the lock counter resets and the
 * field re-enters voting.
 *
 * # No Android dependencies
 *
 * Pure JVM. Fully unit-testable.
 */
class OcrFieldFusion(val config: FusionConfig = FusionConfig()) {

    /** Verbalized confidence emitted by Gemma alongside each field value. */
    enum class Confidence { LOW, MEDIUM, HIGH }

    /**
     * One frame's contribution for a single field.
     *
     * @property value the extracted string value (already trimmed by
     *   the calling pipeline; this module treats it as opaque).
     * @property confidence Gemma's verbalized confidence.
     * @property frameQuality 0..1 — typically derived from
     *   [OcrFrameQualityPresort.FrameQualityScore] (e.g.
     *   ``blurVariance / 1000`` clamped to [0, 1]). 1.0 = perfect frame;
     *   0.0 = useless.
     * @property frameId monotonic frame id (for tie-breaking + audit).
     */
    data class FieldObservation(
        val value: String,
        val confidence: Confidence,
        val frameQuality: Double,
        val frameId: Long,
    ) {
        init {
            require(frameQuality in 0.0..1.0) {
                "frameQuality $frameQuality must be in 0..1"
            }
            require(frameId >= 0L) { "frameId $frameId must be non-negative" }
        }
    }

    /**
     * The current state of one field across all frames seen.
     *
     * Immutable snapshot — [accept] returns a fresh [FieldState] each
     * call so callers can stream/diff.
     */
    data class FieldState(
        /** Per-value cumulative evidence; weights sum across all observations. */
        val evidenceByValue: Map<String, Double>,
        /** The current top value (highest evidence); null when no observations yet. */
        val topValue: String?,
        /** Number of consecutive [accept] calls where [topValue] won. */
        val consecutiveAgreement: Int,
        /** True once [consecutiveAgreement] >= [FusionConfig.kLock]. */
        val locked: Boolean,
        /** Fraction of cumulative evidence currently behind [topValue]. */
        val topConfidence: Double,
    ) {
        companion object {
            val EMPTY = FieldState(
                evidenceByValue = emptyMap(),
                topValue = null,
                consecutiveAgreement = 0,
                locked = false,
                topConfidence = 0.0,
            )
        }
    }

    /** Tunables for fusion. */
    data class FusionConfig(
        /** Consecutive-agreement count required to lock a field. */
        val kLock: Int = 3,
        /** Weight when [Confidence.LOW]. */
        val lowWeight: Double = 0.3,
        /** Weight when [Confidence.MEDIUM]. */
        val mediumWeight: Double = 0.7,
        /** Weight when [Confidence.HIGH]. */
        val highWeight: Double = 1.0,
        /**
         * Floor for frame-quality multiplier so a single bad-quality
         * frame can still contribute. 0.1 means a poor frame counts
         * 10% as much as a perfect frame at the same confidence.
         */
        val frameQualityFloor: Double = 0.1,
    ) {
        init {
            require(kLock >= 1) { "kLock must be >= 1" }
            require(lowWeight > 0.0 && mediumWeight >= lowWeight && highWeight >= mediumWeight) {
                "weights must be monotone non-decreasing and positive"
            }
            require(frameQualityFloor in 0.0..1.0) {
                "frameQualityFloor must be in 0..1"
            }
        }

        /**
         * Resolve the per-observation weight from confidence and frame
         * quality. Public so tests can pin the formula.
         */
        fun weightOf(confidence: Confidence, frameQuality: Double): Double {
            val base = when (confidence) {
                Confidence.LOW -> lowWeight
                Confidence.MEDIUM -> mediumWeight
                Confidence.HIGH -> highWeight
            }
            val qualityMultiplier = frameQuality.coerceAtLeast(frameQualityFloor)
            return base * qualityMultiplier
        }
    }

    private val states: MutableMap<String, FieldState> = LinkedHashMap()

    /** Snapshot of all known fields. Iteration order matches insertion order. */
    fun snapshot(): Map<String, FieldState> = LinkedHashMap(states)

    /** Current state for one field, or [FieldState.EMPTY] if unseen. */
    fun stateOf(fieldName: String): FieldState = states[fieldName] ?: FieldState.EMPTY

    /**
     * Accept one observation for one field. Returns the **updated**
     * post-observation [FieldState]. The returned object is an
     * immutable snapshot — re-reads via [stateOf] return equal data
     * until the next [accept] for the same field.
     *
     * @throws IllegalArgumentException if [fieldName] is blank.
     */
    fun accept(fieldName: String, observation: FieldObservation): FieldState {
        require(fieldName.isNotBlank()) { "fieldName must not be blank" }

        val previous = states[fieldName] ?: FieldState.EMPTY
        val weight = config.weightOf(observation.confidence, observation.frameQuality)

        val newEvidence = previous.evidenceByValue.toMutableMap()
        newEvidence[observation.value] = (newEvidence[observation.value] ?: 0.0) + weight

        val (topValue, topEvidence) = newEvidence.entries
            // Tie-break: prefer the value last observed at the higher
            // frame id (i.e. more recent stable reading wins ties).
            // Since this is an aggregate map we don't have per-value
            // most-recent frameId — break ties on string lex order for
            // determinism. Ties are rare; ordering is stable.
            .maxWithOrNull(compareBy({ it.value }, { it.key }))!!
            .let { it.key to it.value }

        val totalEvidence = newEvidence.values.sum()
        val topConfidence = if (totalEvidence > 0.0) topEvidence / totalEvidence else 0.0

        val consecutive = when {
            previous.topValue == null -> 1
            previous.topValue == topValue -> previous.consecutiveAgreement + 1
            else -> 1
        }
        val locked = consecutive >= config.kLock

        val next = FieldState(
            evidenceByValue = newEvidence.toMap(),
            topValue = topValue,
            consecutiveAgreement = consecutive,
            locked = locked,
            topConfidence = topConfidence,
        )
        states[fieldName] = next
        return next
    }

    /** Reset all state — used when a session is closed and reopened. */
    fun reset() {
        states.clear()
    }
}
