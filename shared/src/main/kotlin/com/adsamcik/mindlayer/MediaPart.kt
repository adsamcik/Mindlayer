package com.adsamcik.mindlayer

import android.os.Parcelable
import android.os.ParcelFileDescriptor
import kotlinx.parcelize.Parcelize

/**
 * One media attachment delivered to `IMindlayerService.inferMulti(...)`.
 *
 * `MediaPart` is the v0.4 successor to the rigid `(image: ImageTransfer?,
 * audio: AudioTransfer?)` shape on `infer(...)`. Callers pass an ordered
 * `List<MediaPart>` so future engines that consume multiple images, video,
 * or documents can do so without another wire-break. **Order is meaningful**:
 * the service preserves caller-supplied order when constructing the
 * multimodal prompt for engines that respect it.
 *
 * # Today's engine constraints
 *
 * - At most one [KIND_IMAGE] per request (multi-image is blocked on
 *   litert-lm #1874).
 * - At most one [KIND_AUDIO] per request.
 * - [KIND_VIDEO] and [KIND_DOCUMENT] are wire-reserved but **not yet served**;
 *   the validator rejects them with `INVALID_REQUEST` for now. Reserving the
 *   constants keeps the wire numerically stable when the engine catches up.
 *
 * Aggregate caps are advertised via
 * [ServiceCapabilities.maxMediaPartsPerRequest] and
 * [ServiceCapabilities.maxTotalMediaBytesPerRequest].
 *
 * # Schema evolution
 *
 * [schemaVersion] is the **first** field per `docs/AIDL_STABILITY.md` —
 * adding fields to this Parcelable later breaks wire layout and would
 * require a new `MediaPartV2` + new method, not an in-place extension.
 *
 * # Field semantics by kind
 *
 * | Field | KIND_IMAGE | KIND_AUDIO | KIND_VIDEO/DOCUMENT |
 * |---|---|---|---|
 * | [source] | required PFD | required PFD | required PFD |
 * | [mimeType] | required for encoded; null for raw pixels | required (e.g. `audio/wav`) | required |
 * | [payloadBytes] | bytes in source | bytes in source | bytes in source |
 * | [width] / [height] / [pixelFormat] / [rowStride] | required for raw pixels (when [isSharedMemory]=true and mimeType=null); zero otherwise | zero | zero |
 * | [durationMs] | null | optional duration hint | null |
 *
 * @property schemaVersion Parcelable schema version. Wire-stable.
 * @property requestId Must equal `RequestMeta.requestId` for the enclosing
 *   `inferMulti` call — defends against staging-cleanup keying mismatches.
 * @property kind One of [KIND_IMAGE], [KIND_AUDIO], [KIND_VIDEO],
 *   [KIND_DOCUMENT]. Unknown kinds are rejected.
 * @property mimeType Media MIME type. Required for encoded images and audio;
 *   `null` for raw pixel images.
 * @property source PFD streaming the bytes. May be SharedMemory-backed
 *   (preferred for raw pixels — see [isSharedMemory]) or a regular file PFD.
 * @property isSharedMemory `true` when [source] wraps an ashmem region.
 *   Required for raw-pixel images; optional for encoded media.
 * @property payloadBytes Total bytes in the [source]. `Long` rather than
 *   `Int` so a single video clip > 2 GB stays representable on the wire,
 *   even if today's caps reject it.
 * @property width Pixel width for raw images, otherwise zero.
 * @property height Pixel height for raw images, otherwise zero.
 * @property pixelFormat Pixel format constant for raw images
 *   (1=RGBA_8888, 4=RGB_565). Zero for non-raw.
 * @property rowStride Bytes per pixel row in raw images. Zero otherwise.
 * @property durationMs Optional duration hint for time-domain media.
 *   Service does not validate it as authoritative.
 * @property featureFlags Forward-compatibility hint bitfield. Reserved
 *   for `MediaPartV2`-style extensions (e.g. "encryption marker", "scrub
 *   policy"); the v1 service ignores all bits.
 */
@Parcelize
data class MediaPart(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val requestId: String,
    val kind: Int,
    val mimeType: String?,
    val source: ParcelFileDescriptor,
    val isSharedMemory: Boolean,
    val payloadBytes: Long,
    val width: Int = 0,
    val height: Int = 0,
    val pixelFormat: Int = 0,
    val rowStride: Int = 0,
    val durationMs: Long? = null,
    val featureFlags: Int = 0,
) : Parcelable {

    override fun toString(): String =
        "MediaPart(kind=$kind, mime=$mimeType, bytes=$payloadBytes, " +
            "shm=$isSharedMemory, w=$width, h=$height)"

    companion object {
        /** Current parcelable schema version. */
        const val CURRENT_SCHEMA_VERSION: Int = 1

        /** Bitmap or encoded image. Today: at most one per request. */
        const val KIND_IMAGE: Int = 1

        /** Audio clip. Today: at most one per request. */
        const val KIND_AUDIO: Int = 2

        /**
         * Video clip. **Wire-reserved; not yet served.** The validator
         * rejects this kind today; advertising it via
         * [ServiceCapabilities.supportedFeatures] will be the signal.
         */
        const val KIND_VIDEO: Int = 3

        /**
         * Text document (PDF, plain text, …). **Wire-reserved; not yet served.**
         * Same status as [KIND_VIDEO].
         */
        const val KIND_DOCUMENT: Int = 4

        /** All currently-known kinds. */
        val ALL_KINDS: Set<Int> = setOf(KIND_IMAGE, KIND_AUDIO, KIND_VIDEO, KIND_DOCUMENT)
    }
}
