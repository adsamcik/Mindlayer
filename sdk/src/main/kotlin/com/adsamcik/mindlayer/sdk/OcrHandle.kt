package com.adsamcik.mindlayer.sdk

import com.adsamcik.mindlayer.OcrFrameAck
import com.adsamcik.mindlayer.OcrFrameMeta
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/**
 * A single recognised text line from an OCR pass.
 *
 * [boundingBox] is populated only when the request opted into bounding boxes
 * (see [OcrRequest.Builder.emitBoundingBoxes]); it is `[left, top, right, bottom]`
 * in source-image pixels. [confidence] is in `0f..1f` when the engine reports it.
 */
data class OcrLine(
    val text: String,
    val boundingBox: List<Int>? = null,
    val confidence: Float? = null,
)

/**
 * Typed result of an OCR call.
 *
 * [fullJson] is always a parsed [JsonObject] (never a raw string).
 * [extractionJson] is present only when LLM extraction was requested via
 * [OcrRequest.Builder.extractWithLlm] / [OcrSessionRequest.Builder.extractWithLlm].
 */
data class OcrResult(
    val lines: List<OcrLine>,
    val fullJson: JsonObject,
    val extractionJson: JsonObject?,
    val metrics: Metrics,
)

/**
 * Cold handle for an OCR call. The canonical [Mindlayer.ocr] returns a
 * [OneShot]; [Mindlayer.ocrSession] returns a [MultiFrame].
 *
 * Behavioural wiring lands in C2/C3; in C1 this is the public type skeleton and
 * the existing concrete `OcrSession` does not yet conform to [MultiFrame].
 */
sealed interface OcrHandle {
    /** Full OCR event stream. */
    val events: Flow<OcrEvent>

    /** One image in, one [OcrResult] out. */
    interface OneShot : OcrHandle {
        suspend fun awaitResult(): OcrResult
    }

    /** Streaming multi-frame OCR session (e.g. live camera). */
    interface MultiFrame : OcrHandle, AutoCloseable {
        val sessionId: String

        /** Push a frame. The returned ack carries back-pressure the caller MAY throttle on. */
        suspend fun pushFrame(meta: OcrFrameMeta, image: ImageInput): OcrFrameAck

        /** Drain the session, await the finalized result, and return it typed. */
        suspend fun finalize(): OcrResult

        /** Suspending close — awaits the AIDL ack. */
        suspend fun closeAsync()
    }
}
