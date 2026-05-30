package com.adsamcik.mindlayer.service.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull

/**
 * v0.9 multi-page realtime OCR — per-frame IMU snapshot extracted from
 * the opaque [com.adsamcik.mindlayer.OcrFrameMeta.extraJson] envelope.
 *
 * The SDK-side CameraX adapter populates the `imu` sub-block on every
 * frame meta:
 *
 * ```json
 * { "imu": { "gyro_max_rad_per_s": 1.23 } }
 * ```
 *
 * Other potential fields (`accel_max_m_per_s2`, `capture_window_ms`) are
 * reserved for future patches; the parser tolerates them by ignoring
 * unknown keys.
 *
 * # Missing-data policy
 *
 * Missing block, missing field, malformed JSON, or unparseable type all
 * degrade to [NONE] (gyro = 0.0). The boundary detector then contributes
 * nothing from the gyro signal for that frame — Jaccard + spatial still
 * vote.
 *
 * # Threading
 *
 * Pure data, no Android imports. Safe to share across coroutines.
 *
 * @property gyroMaxRadPerS peak gyro magnitude (rad/s) observed during
 *   the frame's capture window.
 */
data class ImuFrameMetadata(
    val gyroMaxRadPerS: Float = 0f,
) {

    override fun toString(): String =
        "ImuFrameMetadata(gyroMaxRadPerS=$gyroMaxRadPerS)"

    companion object {
        private const val KEY_IMU: String = "imu"
        private const val KEY_GYRO: String = "gyro_max_rad_per_s"

        /** "No IMU data for this frame". gyro = 0.0. */
        val NONE: ImuFrameMetadata = ImuFrameMetadata(gyroMaxRadPerS = 0f)

        private val lenientJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /**
         * Parse an `ImuFrameMetadata` out of [OcrFrameMeta.extraJson].
         *
         * Returns [NONE] when:
         *  - [extraJson] is null / blank / not a JSON object;
         *  - the `imu` block is missing or not an object;
         *  - `imu.gyro_max_rad_per_s` is missing or not numeric.
         *
         * Unknown keys (e.g. future `accel_max_m_per_s2`,
         * `capture_window_ms`) are ignored. Never throws.
         */
        fun parse(extraJson: String?): ImuFrameMetadata {
            if (extraJson.isNullOrBlank()) return NONE
            val root = runCatching { lenientJson.parseToJsonElement(extraJson) }
                .getOrNull() as? JsonObject
                ?: return NONE
            val imu = root[KEY_IMU] as? JsonObject ?: return NONE
            val gyro = (imu[KEY_GYRO] as? JsonPrimitive)?.floatOrNull ?: return NONE
            // Negative values are non-physical; clamp to 0.
            val safe = if (gyro.isFinite() && gyro >= 0f) gyro else 0f
            return ImuFrameMetadata(gyroMaxRadPerS = safe)
        }
    }
}
