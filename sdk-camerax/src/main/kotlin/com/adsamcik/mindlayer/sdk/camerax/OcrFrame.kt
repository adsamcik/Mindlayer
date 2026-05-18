package com.adsamcik.mindlayer.sdk.camerax

import com.adsamcik.mindlayer.OcrFrameMeta

/**
 * Immutable greyscale frame extracted from a CameraX [androidx.camera.core.ImageProxy]
 * (or constructed manually from a Y-plane byte array).
 *
 * The Mindlayer multi-frame OCR API consumes greyscale Y-plane data
 * (8-bit, row-major). CameraX delivers YUV_420_888 ImageProxy
 * instances whose plane 0 is the Y channel — this class peels off
 * just that plane and copies it into a [ByteArray] sized to
 * ``width * height`` (handling row-stride padding).
 *
 * # Lifetime
 *
 * The underlying [bytes] are an SDK-owned copy — once an
 * ``OcrFrame`` is created the caller may immediately close the
 * original [androidx.camera.core.ImageProxy]. This matches CameraX's
 * "drop the ImageProxy ASAP" recommendation.
 *
 * # Privacy
 *
 * [toString] does not log pixel data. The [bytes] are a defensive
 * copy of the source plane; consumers that hold the [OcrFrame] for
 * an extended period should be aware that this is a ``width *
 * height``-byte allocation per accepted frame.
 *
 * @property frameId monotonic-per-session frame id; the SDK assigns
 *   sequentially when [Companion.fromImageProxy] is called.
 * @property captureTimeMs ``System.currentTimeMillis()`` at capture.
 * @property width pixel width.
 * @property height pixel height.
 * @property rotationDegrees device rotation at capture (0/90/180/270).
 * @property qualityHint optional client-side presort hint
 *   ([com.adsamcik.mindlayer.OcrFrameMeta.QUALITY_*]). Default is
 *   ``QUALITY_UNKNOWN`` — set by [OcrFramePresort.score] in clients
 *   that do client-side presort.
 * @property bytes the Y-plane data. ``size == width * height``.
 */
data class OcrFrame(
    val frameId: Long,
    val captureTimeMs: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val qualityHint: Int,
    val bytes: ByteArray,
) {
    init {
        require(width > 0 && height > 0) { "width and height must be > 0" }
        require(rotationDegrees in setOf(0, 90, 180, 270)) {
            "rotationDegrees $rotationDegrees not in {0,90,180,270}"
        }
        require(bytes.size == width * height) {
            "bytes.size=${bytes.size} != width*height=${width * height}"
        }
    }

    /** Produce the [OcrFrameMeta] AIDL parcelable for this frame. */
    fun toFrameMeta(): OcrFrameMeta = OcrFrameMeta(
        frameId = frameId,
        captureTimeMs = captureTimeMs,
        rotationDegrees = rotationDegrees,
        qualityHint = qualityHint,
    )

    override fun toString(): String =
        "OcrFrame(id=$frameId, ${width}x$height, rot=$rotationDegrees, " +
            "qHint=$qualityHint, bytes=${bytes.size})"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OcrFrame) return false
        return frameId == other.frameId &&
            captureTimeMs == other.captureTimeMs &&
            width == other.width &&
            height == other.height &&
            rotationDegrees == other.rotationDegrees &&
            qualityHint == other.qualityHint &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = frameId.hashCode()
        result = 31 * result + captureTimeMs.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + rotationDegrees
        result = 31 * result + qualityHint
        result = 31 * result + bytes.contentHashCode()
        return result
    }

    companion object {
        /**
         * Build an [OcrFrame] directly from a Y-plane byte array.
         *
         * Use this entry point for tests, custom capture pipelines,
         * or non-CameraX consumers (e.g. MediaProjection screen
         * recording, [android.graphics.Bitmap] sources).
         */
        @JvmStatic
        fun fromYPlane(
            frameId: Long,
            captureTimeMs: Long,
            yPlane: ByteArray,
            width: Int,
            height: Int,
            rotationDegrees: Int = 0,
            qualityHint: Int = OcrFrameMeta.QUALITY_UNKNOWN,
        ): OcrFrame = OcrFrame(
            frameId = frameId,
            captureTimeMs = captureTimeMs,
            width = width,
            height = height,
            rotationDegrees = rotationDegrees,
            qualityHint = qualityHint,
            bytes = yPlane.copyOf(),
        )

        /**
         * Build an [OcrFrame] from a CameraX [androidx.camera.core.ImageProxy].
         *
         * Extracts plane 0 (Y), handling row-stride padding by
         * copying row-by-row when ``rowStride > width``. The
         * returned ``OcrFrame`` owns its own copy — the caller may
         * close the [androidx.camera.core.ImageProxy] immediately.
         *
         * **CameraX dependency is ``compileOnly`` on this module —**
         * this function is `inline` so the [androidx.camera.core.ImageProxy]
         * reference doesn't materialise on the class load of
         * ``OcrFrame.Companion`` itself. Consumers that don't link
         * CameraX won't trigger ``NoClassDefFoundError`` on touch.
         */
        @JvmStatic
        fun fromImageProxy(
            image: androidx.camera.core.ImageProxy,
            frameId: Long,
            qualityHint: Int = OcrFrameMeta.QUALITY_UNKNOWN,
        ): OcrFrame {
            val width = image.width
            val height = image.height
            val rotation = image.imageInfo.rotationDegrees.coerceIn(0, 270).let {
                // CameraX may emit values that are not exactly in {0,90,180,270}
                // when the host rotates between captures; round to the nearest
                // quadrant to satisfy OcrFrame's invariant.
                when {
                    it < 45 -> 0
                    it < 135 -> 90
                    it < 225 -> 180
                    it < 315 -> 270
                    else -> 0
                }
            }
            val plane = image.planes[0]
            val rowStride = plane.rowStride
            val buffer = plane.buffer
            val bytes = ByteArray(width * height)
            if (rowStride == width) {
                buffer.get(bytes, 0, bytes.size)
            } else {
                // Copy row by row to skip padding columns.
                val rowBuf = ByteArray(rowStride)
                for (row in 0 until height) {
                    buffer.position(row * rowStride)
                    val toRead = minOf(rowStride, buffer.remaining())
                    buffer.get(rowBuf, 0, toRead)
                    System.arraycopy(rowBuf, 0, bytes, row * width, width)
                }
            }
            return OcrFrame(
                frameId = frameId,
                captureTimeMs = image.imageInfo.timestamp,
                width = width,
                height = height,
                rotationDegrees = rotation,
                qualityHint = qualityHint,
                bytes = bytes,
            )
        }
    }
}
