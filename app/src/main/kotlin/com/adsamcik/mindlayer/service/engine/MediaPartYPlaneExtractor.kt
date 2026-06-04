package com.adsamcik.mindlayer.service.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import androidx.annotation.VisibleForTesting
import com.adsamcik.mindlayer.MediaPart
import com.adsamcik.mindlayer.service.ipc.SharedMemoryPool
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.logging.safeLabel
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Service-side Y-plane extractor for the v0.8 multi-frame OCR API.
 *
 * Converts a wire-arriving [MediaPart] (raw RGBA/RGB-565 over
 * SharedMemory PFD, or encoded JPEG/PNG/WEBP/BMP over a file PFD)
 * into an 8-bit greyscale Y-plane consumed by [OcrFrameQualityPresort] and
 * [PaddleOcrEngine].
 *
 * # Strategy
 *
 *  1. Reuse [SharedMemoryPool.stageImage] for the heavy lifting —
 *     PFD reconstruction, SharedMemory mapping, security validation,
 *     FD cleanup. This avoids re-implementing the FD-safe staging
 *     path that already has comprehensive tests + the recent
 *     instrumented-SHM coverage (PR #61).
 *  2. Decode the staged image file via [BitmapFactory] — handles
 *     JPEG / PNG / WEBP / BMP uniformly. For raw RGBA pixels
 *     `stageImage` first re-encodes to PNG, which is wasteful but
 *     correct + uses tested code paths. A future PR can short-
 *     circuit the raw path with direct SharedMemory → Y conversion
 *     to skip the PNG roundtrip.
 *  3. Convert the decoded [Bitmap] to a greyscale Y-plane using the
 *     ITU-R BT.601 luminance coefficients (the same coefficients
 *     CameraX uses when emitting YUV_420_888 from RGBA pipelines).
 *  4. Always clean up the staged file via the
 *     [com.adsamcik.mindlayer.service.ipc.StagedMedia.cleanup]
 *     closure on every path — even errors. Per-frame staging
 *     would otherwise leak cache files at the rate of accepted
 *     frames * sessions.
 *
 * # Output
 *
 * [ExtractedYFrame] carries the Y-plane bytes + the **decoded**
 * dimensions, not the [MediaPart]-claimed dimensions. A malicious
 * client can lie about the dimensions in `MediaPart.width/height`;
 * the decoder gives us the truth.
 *
 * # No live SharedMemory under Robolectric
 *
 * The internal RGBA->Y helper [bitmapToYPlane] is the only pure-
 * JVM-testable part. The whole [extractY] entry point requires a
 * real [SharedMemoryPool] which in turn requires the
 * [com.adsamcik.mindlayer.service.ipc.SharedMemoryPool.stageImage]
 * pipeline; that path's real-SHM coverage lives in the
 * SharedMemoryPool's own instrumented tests (and the SHM blob
 * coverage in `EmbeddingShmLayoutInstrumentedTest.kt`).
 */
object MediaPartYPlaneExtractor {

    /** Hard cap on per-frame Y-plane size — guards against pixel-bomb decodes. */
    const val MAX_Y_PIXELS: Int = 24_000_000 // 24 megapixels

    /** Decoded Y-plane + truthy dimensions. */
    data class ExtractedYFrame(
        val yPlane: ByteArray,
        val width: Int,
        val height: Int,
    ) {
        init {
            require(width > 0 && height > 0)
            require(yPlane.size == width * height)
        }
        override fun toString(): String =
            "ExtractedYFrame(${width}x${height}, bytes=${yPlane.size})"

        override fun equals(other: Any?): Boolean = this === other ||
            (other is ExtractedYFrame && width == other.width && height == other.height &&
                yPlane.contentEquals(other.yPlane))

        override fun hashCode(): Int {
            var r = width
            r = 31 * r + height
            r = 31 * r + yPlane.contentHashCode()
            return r
        }
    }

    /**
     * Extract the Y-plane from [part].
     *
     * @throws SecurityException wire-prefixed with
     *   [MindlayerErrorCode.INVALID_REQUEST] when the part is not
     *   an image, the decoded bitmap is null, or the dimensions
     *   exceed [MAX_Y_PIXELS].
     */
    fun extractY(
        part: MediaPart,
        sharedMemoryPool: SharedMemoryPool,
        scopedKey: String,
    ): ExtractedYFrame {
        if (part.kind != MediaPart.KIND_IMAGE) {
            throw wireError("MediaPart kind ${part.kind} is not KIND_IMAGE")
        }


        if (part.mimeType == com.adsamcik.mindlayer.service.security.IpcInputValidator.OCR_RAW_Y_PLANE_MIME) {
            return extractRawYPlane(part)
        }

        // Translate MediaPart to the ImageTransfer surface stageImage
        // accepts. This re-uses the existing security validators,
        // SHM reconstruction, FD cleanup, and per-request reservation
        // accounting in SharedMemoryPool.
        val imageTransfer = com.adsamcik.mindlayer.ImageTransfer(
            requestId = part.requestId,
            mimeType = part.mimeType,
            source = part.source,
            isSharedMemory = part.isSharedMemory,
            payloadBytes = part.payloadBytes.toInt().coerceAtLeast(0),
            width = part.width,
            height = part.height,
            pixelFormat = part.pixelFormat,
            rowStride = part.rowStride,
        )

        val staged = sharedMemoryPool.stageImage(scopedKey, imageTransfer)
        try {
            val bitmap = BitmapFactory.decodeFile(staged.filePath)
                ?: throw wireError("Failed to decode staged image at ${staged.filePath}")
            try {
                val pixels = bitmap.width.toLong() * bitmap.height.toLong()
                if (pixels > MAX_Y_PIXELS) {
                    throw wireError(
                        "Decoded image $pixels px > MAX_Y_PIXELS $MAX_Y_PIXELS — possible pixel bomb",
                    )
                }
                val yPlane = bitmapToYPlane(bitmap)
                return ExtractedYFrame(yPlane, bitmap.width, bitmap.height)
            } finally {
                bitmap.recycle()
            }
        } finally {
            try {
                staged.cleanup.invoke()
            } catch (t: Throwable) {
                // cleanup is best-effort — log but never propagate
                // because the caller already has the decoded Y bytes
                // (success path) or a more interesting throwable
                // (failure path).
                MindlayerLog.w(
                    "MediaPartYPlaneExtractor",
                    "Staged-image cleanup failed: ${t.safeLabel()}",
                    throwable = null,
                )
            }
        }
    }


    private fun extractRawYPlane(part: MediaPart): ExtractedYFrame {
        val width = part.width
        val height = part.height
        val rowStride = part.rowStride
        if (width <= 0 || height <= 0 || rowStride < width) {
            try { part.source.close() } catch (_: Throwable) {}
            throw wireError("Invalid raw Y-plane layout")
        }
        val pixels = try {
            Math.multiplyExact(width.toLong(), height.toLong())
        } catch (_: ArithmeticException) {
            try { part.source.close() } catch (_: Throwable) {}
            throw wireError("Raw Y-plane dimensions overflow")
        }
        if (pixels > MAX_Y_PIXELS) {
            try { part.source.close() } catch (_: Throwable) {}
            throw wireError("Raw Y-plane $pixels px > MAX_Y_PIXELS $MAX_Y_PIXELS")
        }
        val minimumBytes = if (height == 1) width.toLong() else rowStride.toLong() * (height - 1).toLong() + width.toLong()
        if (part.payloadBytes < minimumBytes || part.payloadBytes > Int.MAX_VALUE) {
            try { part.source.close() } catch (_: Throwable) {}
            throw wireError("Invalid raw Y-plane payload size")
        }
        val bytes = readExactlyAndClose(part.source, part.payloadBytes.toInt())
        val tight = if (rowStride == width && bytes.size == width * height) {
            bytes
        } else {
            ByteArray(width * height).also { dst ->
                for (row in 0 until height) {
                    System.arraycopy(bytes, row * rowStride, dst, row * width, width)
                }
            }
        }
        return ExtractedYFrame(tight, width, height)
    }

    private fun readExactlyAndClose(source: ParcelFileDescriptor, size: Int): ByteArray {
        val input = ParcelFileDescriptor.AutoCloseInputStream(source)
        return readExactlyWithWatchdog(input, size) {
            // Force-closing the PFD makes a read() that is blocked on a
            // never-fed pipe/socket throw, unwinding the call.
            try { source.close() } catch (_: Throwable) {}
        }
    }

    /**
     * SECURITY (DoS, V-B): read exactly [size] bytes from [input], but
     * bound the total time with a watchdog so a caller that hands us a
     * pipe/socket FD, declares a large `payloadBytes`, and never writes
     * the bytes cannot block the binder thread forever inside `read()`.
     *
     * `readExactlyAndClose` previously looped on a blocking `read()` with
     * no timeout and no FD-type guard; this watchdog force-closes the
     * underlying FD (via [forceClose]) after [rawYReadTimeoutMs], which
     * makes the blocked read throw and the call unwind. Legitimate
     * frames populate the FD in well under the timeout.
     *
     * Exposed for unit testing with an in-memory blocking stream.
     */
    @VisibleForTesting
    internal fun readExactlyWithWatchdog(
        input: InputStream,
        size: Int,
        forceClose: () -> Unit,
    ): ByteArray {
        val out = ByteArray(size)
        val watchdog = readWatchdog.schedule({
            forceClose()
            try { input.close() } catch (_: Throwable) {}
        }, rawYReadTimeoutMs, TimeUnit.MILLISECONDS)
        try {
            input.use { ins ->
                var offset = 0
                while (offset < size) {
                    val read = ins.read(out, offset, size - offset)
                    if (read == -1) {
                        throw wireError("Raw Y-plane PFD ended early or timed out")
                    }
                    offset += read
                }
            }
        } finally {
            watchdog.cancel(false)
        }
        return out
    }

    /** Daemon scheduler backing the raw-Y read watchdog. */
    private val readWatchdog: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "mindlayer-rawY-read-watchdog").apply { isDaemon = true }
        }

    /**
     * Wall-clock budget for reading a single raw Y-plane frame off its
     * PFD. Far above any legitimate near-instant SHM/file copy, well
     * below "binder thread is wedged". [VisibleForTesting] so tests can
     * shrink it.
     */
    @VisibleForTesting
    @JvmField
    var rawYReadTimeoutMs: Long = 5_000L

    /**
     * Convert an ARGB / RGBA decoded [Bitmap] to an 8-bit greyscale
     * Y-plane using the ITU-R BT.601 luminance coefficients
     * (Y = 0.299*R + 0.587*G + 0.114*B), via integer arithmetic:
     *
     *     Y = (77*R + 150*G + 29*B + 128) >>> 8
     *
     * Returns a `width * height`-byte array, row-major.
     *
     * # Why not pull the Y plane from the bitmap directly?
     *
     * Android's Bitmap has no Y-plane accessor; the underlying
     * pixel data is RGBA whether we like it or not. We do the
     * conversion in Kotlin so the implementation is testable on
     * the JVM (with Robolectric for the Bitmap construction) and
     * so a future PR can drop in a NEON / native loop without
     * changing the API surface.
     */
    internal fun bitmapToYPlane(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val y = ByteArray(width * height)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            // ITU-R BT.601 luminance, integer approximation.
            val luma = (77 * r + 150 * g + 29 * b + 128) ushr 8
            y[i] = luma.toByte()
        }
        return y
    }

    private fun wireError(message: String): SecurityException = SecurityException(
        MindlayerErrorCode.wireMessage(MindlayerErrorCode.INVALID_REQUEST, message),
    )
}
