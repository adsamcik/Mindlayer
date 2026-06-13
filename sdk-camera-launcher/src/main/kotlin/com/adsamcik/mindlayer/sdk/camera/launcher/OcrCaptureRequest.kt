package com.adsamcik.mindlayer.sdk.camera.launcher

import android.os.Parcelable
import com.adsamcik.mindlayer.sdk.OcrProfile
import kotlinx.parcelize.Parcelize

/**
 * Capture mode for [OcrCaptureRequest].
 *
 * Picks which of the two Mindlayer OCR surfaces the capture activity
 * drives under the hood:
 *
 * - [Async] — a single high-quality frame is captured when the user
 *   taps the capture button, then handed to
 *   [com.adsamcik.mindlayer.sdk.Mindlayer.ocr]. Result is an
 *   [OcrCaptureResult.Async] carrying JSON payloads + timing metadata.
 *   Use this for "scan this once" flows (receipts, ID cards, business
 *   cards). Lower throughput, higher quality per frame because the
 *   capture pipeline waits for AF lock before snapping.
 *
 * - [Realtime] — the activity opens an
 *   [com.adsamcik.mindlayer.sdk.OcrHandle.MultiFrame] via
 *   [com.adsamcik.mindlayer.sdk.Mindlayer.ocrSession] and streams
 *   CameraX preview frames through
 *   [com.adsamcik.mindlayer.sdk.camerax.OcrImageAnalyzer]. The
 *   service-side cross-frame fusion accumulates evidence; the
 *   activity finalises either on user tap (the on-screen "Done"
 *   button) or, in a follow-up, on convergence (currently always
 *   user-driven). Result is an [OcrCaptureResult.Realtime] carrying
 *   the final fused JSON string plus the per-event log.
 *
 * Wire-stable enum: ordinal values are NOT used in the parcel; the
 * name is, so reordering is safe.
 */
enum class OcrCaptureMode {
    /** Single best frame → [com.adsamcik.mindlayer.sdk.Mindlayer.ocr]. */
    Async,
    /** Multi-frame streaming → [com.adsamcik.mindlayer.sdk.Mindlayer.ocrSession]. */
    Realtime,
}

/**
 * Wire-stable identifier for a built-in [OcrProfile], suitable for
 * parcelling.
 *
 * The launcher contract crosses an Activity Result boundary — possibly
 * across a process restart on system pressure — so it cannot carry the
 * sealed-class [OcrProfile] instances directly. Callers identify the
 * profile by stable name; the activity resolves it back to the
 * [OcrProfile] singleton.
 *
 * @property displayName the [OcrProfile.displayName] value, used as the
 *   stable key. Resolved by [resolve].
 */
enum class OcrProfileId(internal val profile: OcrProfile) {
    GeneralDocument(OcrProfile.GeneralDocument),
    Receipt(OcrProfile.Receipt),
    IdCard(OcrProfile.IdCard),
    Whiteboard(OcrProfile.Whiteboard),
    ScreenCapture(OcrProfile.ScreenCapture);

    companion object {
        /** Inverse of [profile] — find the id for a sealed-class instance. */
        fun of(profile: OcrProfile): OcrProfileId = entries.first { it.profile === profile }
    }
}

/**
 * Input to [OcrCaptureContract].
 *
 * Crosses an Activity Result bundle so it is [Parcelable]. The
 * activity uses these fields to drive the appropriate Mindlayer OCR
 * surface ([OcrCaptureMode.Async] → [com.adsamcik.mindlayer.sdk.Mindlayer.ocr];
 * [OcrCaptureMode.Realtime] →
 * [com.adsamcik.mindlayer.sdk.Mindlayer.ocrSession]) and to surface a meaningful UI title.
 *
 * # Privacy
 *
 * [extractionSchemaJson] is forwarded verbatim to the service and never
 * persisted by the launcher. It MUST NOT contain user data; it is a
 * pure data-shape declaration.
 *
 * @property schemaVersion Wire-stable. Currently `1`. Bumping requires
 *   coordinating producers + consumers; do not add fields without
 *   bumping.
 * @property mode whether the activity drives the realtime or async
 *   surface.
 * @property profileId the [OcrProfile] used for [OcrCaptureMode.Realtime].
 *   For [OcrCaptureMode.Async] this drives only the LLM extraction
 *   schema default (when [extractionSchemaJson] is null) and the title.
 * @property extractionSchemaJson optional schema for the LLM extraction
 *   pass. Null means "use the profile's default schema" for realtime,
 *   or "skip LLM extraction" for async. Capped server-side by
 *   [com.adsamcik.mindlayer.OcrLimits.ocrSchemaJsonMaxLen].
 * @property runLlmExtraction async-mode only — when true, run the
 *   Gemma extraction pass and populate
 *   [OcrCaptureResult.Async.extractionJson]. Ignored for realtime,
 *   which uses the session builder's schema-driven fusion path.
 * @property emitBoundingBoxes async-mode only — when true, each
 *   one-shot OCR call requests quadrilateral metadata from the
 *   service. Callers that need typed per-line boxes should invoke the
 *   direct SDK surface instead of this launcher contract. Ignored for realtime.
 * @property maxFrames realtime-mode only — soft cap on the number of
 *   frames the analyzer will push before auto-finalising. `0` means
 *   "use service default" (typically 60). Ignored for async.
 * @property languageHints BCP-47 language hints forwarded to both
 *   surfaces. Currently advisory; PaddleOCR PP-OCRv5 mobile is
 *   multilingual.
 * @property titleOverride optional title to show in the activity's
 *   top-bar. Null uses the default localised "Scan" string plus the
 *   profile name. Stays in the activity UI only; never logged.
 */
@Parcelize
data class OcrCaptureRequest(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val mode: OcrCaptureMode,
    val profileId: OcrProfileId,
    val extractionSchemaJson: String? = null,
    val runLlmExtraction: Boolean = false,
    val emitBoundingBoxes: Boolean = false,
    val maxFrames: Int = 0,
    val languageHints: List<String> = emptyList(),
    val titleOverride: String? = null,
) : Parcelable {

    override fun toString(): String =
        "OcrCaptureRequest(mode=$mode, profile=$profileId, " +
            "runLlm=$runLlmExtraction, bbox=$emitBoundingBoxes, maxFrames=$maxFrames, " +
            "langs=${languageHints.size}, " +
            "schemaJson=${if (extractionSchemaJson == null) "null" else "<redacted:${extractionSchemaJson.length}>"}, " +
            "title=${if (titleOverride == null) "null" else "<set>"})"

    companion object {
        /** Current parcelable schema version. Wire-stable. */
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}
