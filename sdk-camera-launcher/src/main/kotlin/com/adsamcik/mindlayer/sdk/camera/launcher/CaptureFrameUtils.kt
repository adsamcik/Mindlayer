package com.adsamcik.mindlayer.sdk.camera.launcher

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/**
 * Internal helpers for the capture activity.
 *
 * `:sdk-camera-launcher` does NOT ship a public utility surface; these
 * are deliberately `internal` so the only public contract is the
 * Activity-result API.
 */
internal object CaptureFrameUtils {

    /**
     * JPEG-encode a single CameraX [ImageProxy] for handoff to
     * [com.adsamcik.mindlayer.sdk.Mindlayer.ocrAsync].
     *
     * Supports the three image formats CameraX's `ImageAnalysis`
     * surface can deliver:
     *
     *  - **JPEG** — already encoded; rotated via in-memory Bitmap if
     *    the imageInfo rotation is non-zero, then re-encoded.
     *  - **YUV_420_888** — copied into a [YuvImage] and asked to encode
     *    its own JPEG, then rotated as above.
     *  - Anything else throws — the activity will surface
     *    [OcrCaptureResult.Error.CAMERA_INIT_FAILED] in that case.
     *
     * Quality is fixed at 90 — high enough for OCR accuracy, low
     * enough to keep the binder payload under ~1 MB for typical
     * 1080p captures.
     *
     * # RAM discipline
     *
     * The returned [ByteArray] is the only persistent reference to
     * the captured pixels — the [ImageProxy] is NOT closed by this
     * function (callers must do so). Intermediate bitmaps are
     * recycled before return. Per privacy invariant, no part of the
     * image is written to disk.
     */
    fun encodeImageProxyToJpeg(image: ImageProxy): ByteArray {
        val rotation = image.imageInfo.rotationDegrees
        val rawJpeg = when (image.format) {
            ImageFormat.JPEG -> readJpegPlane(image)
            ImageFormat.YUV_420_888 -> encodeYuv420ToJpeg(image)
            else -> throw IllegalArgumentException(
                "Unsupported image format ${image.format} for OCR capture",
            )
        }
        if (rotation == 0) return rawJpeg
        return rotateJpeg(rawJpeg, rotation)
    }

    private fun readJpegPlane(image: ImageProxy): ByteArray {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }

    private fun encodeYuv420ToJpeg(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val nv21 = yuv420ToNv21(image)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream(width * height / 4)
        yuvImage.compressToJpeg(Rect(0, 0, width, height), JPEG_QUALITY, out)
        return out.toByteArray()
    }

    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        // Standard YUV_420_888 → NV21 conversion. CameraX's planes are
        // not guaranteed to be tightly packed (rowStride may exceed
        // width), so honour the strides.
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = ySize / 2
        val nv21 = ByteArray(ySize + uvSize)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        // Y plane — fast path when rowStride == width.
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        if (yRowStride == width) {
            yBuffer.get(nv21, 0, ySize)
        } else {
            var pos = 0
            val row = ByteArray(yRowStride)
            for (i in 0 until height) {
                yBuffer.position(i * yRowStride)
                yBuffer.get(row, 0, yRowStride)
                System.arraycopy(row, 0, nv21, pos, width)
                pos += width
            }
        }

        // Interleave VU into the NV21 chroma plane. CameraX YUV planes
        // are u-first/v-first with pixelStride 1 or 2; NV21 wants V
        // then U interleaved at half-resolution.
        val vBuffer = vPlane.buffer
        val uBuffer = uPlane.buffer
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride
        val chromaHeight = height / 2
        val chromaWidth = width / 2
        var nvPos = ySize
        val vRow = ByteArray(uvRowStride)
        val uRow = ByteArray(uvRowStride)
        for (row in 0 until chromaHeight) {
            val base = row * uvRowStride
            vBuffer.position(base)
            uBuffer.position(base)
            val vAvail = minOf(uvRowStride, vBuffer.remaining())
            val uAvail = minOf(uvRowStride, uBuffer.remaining())
            vBuffer.get(vRow, 0, vAvail)
            uBuffer.get(uRow, 0, uAvail)
            var col = 0
            var src = 0
            while (col < chromaWidth) {
                nv21[nvPos++] = vRow[src]
                nv21[nvPos++] = uRow[src]
                src += uvPixelStride
                col++
            }
        }
        return nv21
    }

    private fun rotateJpeg(jpeg: ByteArray, rotation: Int): ByteArray {
        val source = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            ?: return jpeg
        return try {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(
                source,
                0,
                0,
                source.width,
                source.height,
                matrix,
                /* filter = */ true,
            )
            val out = ByteArrayOutputStream(jpeg.size)
            rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            if (rotated !== source) rotated.recycle()
            out.toByteArray()
        } finally {
            source.recycle()
        }
    }

    /** Tuned for OCR: 90 keeps small fonts crisp without blowing up the payload. */
    private const val JPEG_QUALITY: Int = 90
}
