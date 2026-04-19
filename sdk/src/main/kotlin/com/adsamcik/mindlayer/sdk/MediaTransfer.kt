package com.adsamcik.mindlayer.sdk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.system.OsConstants
import android.util.Log
import androidx.annotation.RequiresApi
import com.adsamcik.mindlayer.AudioTransfer
import com.adsamcik.mindlayer.ImageTransfer
import java.io.File
import java.nio.ByteBuffer

/**
 * Client-side helpers for creating [ImageTransfer] and [AudioTransfer]
 * Parcelables that can be sent across the AIDL boundary to MindlayerMlService.
 *
 * Two transport strategies are used depending on the source data and API level:
 *
 *  - **SharedMemory** (API 27+): Zero-copy transport for in-memory data
 *    (raw Bitmap pixels, byte arrays). The memory region is created, filled,
 *    set to read-only via [SharedMemory.setProtect], and wrapped in a
 *    [ParcelFileDescriptor] for AIDL transport.
 *
 *  - **ParcelFileDescriptor** (all API levels): Used for on-disk files and
 *    content URIs. Also serves as the API 26 fallback for in-memory data
 *    (bytes are pumped through a pipe PFD).
 *
 * All methods are blocking and should be called from a background thread.
 */
object MediaTransfer {

    private const val TAG = "MediaTransfer"

    // ---- Image builders ----------------------------------------------------

    /**
     * Create an [ImageTransfer] from a [Bitmap]'s raw pixels.
     *
     * API 27+: pixels are copied into a [SharedMemory] region (RGBA).
     * API 26:  the bitmap is compressed to PNG and pumped through a pipe PFD.
     */
    fun fromBitmap(requestId: String, bitmap: Bitmap): ImageTransfer {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            fromBitmapSharedMemory(requestId, bitmap)
        } else {
            fromBitmapPipe(requestId, bitmap)
        }
    }

    /**
     * Create an [ImageTransfer] from an encoded image file on disk (JPEG, PNG, …).
     *
     * The file is opened read-only as a [ParcelFileDescriptor]. No decoding
     * occurs — the service stages the encoded bytes to its cache.
     */
    fun fromImageFile(requestId: String, file: File): ImageTransfer {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val mime = mimeForExtension(file.extension)
        return ImageTransfer(
            requestId = requestId,
            width = 0,
            height = 0,
            pixelFormat = 0,
            rowStride = 0,
            payloadBytes = file.length().toInt(),
            source = pfd,
            isSharedMemory = false,
            mimeType = mime,
        )
    }

    /**
     * Create an [ImageTransfer] from a `content://` URI (gallery pick, camera capture, …).
     *
     * The URI is opened read-only via [android.content.ContentResolver].
     */
    fun fromImageUri(requestId: String, context: Context, uri: Uri): ImageTransfer {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalArgumentException("Cannot open URI: $uri")
        val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
        return ImageTransfer(
            requestId = requestId,
            width = 0,
            height = 0,
            pixelFormat = 0,
            rowStride = 0,
            payloadBytes = 0,
            source = pfd,
            isSharedMemory = false,
            mimeType = mime,
        )
    }

    // ---- Audio builders ----------------------------------------------------

    /**
     * Create an [AudioTransfer] from a WAV / MP3 / OGG file on disk.
     *
     * The file is opened read-only as a [ParcelFileDescriptor].
     */
    fun fromAudioFile(requestId: String, file: File): AudioTransfer {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val mime = mimeForExtension(file.extension)
        return AudioTransfer(
            requestId = requestId,
            mimeType = mime,
            source = pfd,
            isSharedMemory = false,
        )
    }

    /**
     * Create an [AudioTransfer] from a raw byte array already in memory.
     *
     * API 27+: bytes are copied into a [SharedMemory] region.
     * API 26:  bytes are pumped through a pipe PFD.
     */
    fun fromAudioBytes(requestId: String, bytes: ByteArray, mimeType: String): AudioTransfer {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            fromAudioBytesSharedMemory(requestId, bytes, mimeType)
        } else {
            fromAudioBytesPipe(requestId, bytes, mimeType)
        }
    }

    // ---- SharedMemory builders (API 27+) ------------------------------------

    @RequiresApi(Build.VERSION_CODES.O_MR1)
    private fun fromBitmapSharedMemory(requestId: String, bitmap: Bitmap): ImageTransfer {
        val byteCount = bitmap.allocationByteCount
        val shm = SharedMemory.create("img-$requestId", byteCount)
        try {
            val buffer = shm.mapReadWrite()
            try {
                bitmap.copyPixelsToBuffer(buffer)
            } finally {
                SharedMemory.unmap(buffer)
            }
            shm.setProtect(OsConstants.PROT_READ)
            // getFileDescriptor() removed from compileSdk 36 stubs; use reflection
            val fd = SharedMemory::class.java.getMethod("getFileDescriptor")
                .invoke(shm) as java.io.FileDescriptor
            val pfd = ParcelFileDescriptor.dup(fd)

            return ImageTransfer(
                requestId = requestId,
                width = bitmap.width,
                height = bitmap.height,
                pixelFormat = bitmapConfigToPixelFormat(bitmap.config),
                rowStride = bitmap.rowBytes,
                payloadBytes = byteCount,
                source = pfd,
                isSharedMemory = true,
                mimeType = null, // raw pixels
            )
        } finally {
            shm.close()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O_MR1)
    private fun fromAudioBytesSharedMemory(
        requestId: String,
        bytes: ByteArray,
        mimeType: String,
    ): AudioTransfer {
        val shm = SharedMemory.create("aud-$requestId", bytes.size)
        try {
            val buffer = shm.mapReadWrite()
            try {
                buffer.put(bytes)
            } finally {
                SharedMemory.unmap(buffer)
            }
            shm.setProtect(OsConstants.PROT_READ)
            // getFileDescriptor() removed from compileSdk 36 stubs; use reflection
            val fd = SharedMemory::class.java.getMethod("getFileDescriptor")
                .invoke(shm) as java.io.FileDescriptor
            val pfd = ParcelFileDescriptor.dup(fd)

            return AudioTransfer(
                requestId = requestId,
                mimeType = mimeType,
                source = pfd,
                isSharedMemory = true,
            )
        } finally {
            shm.close()
        }
    }

    // ---- Pipe-based fallback (API 26) --------------------------------------

    private fun fromBitmapPipe(requestId: String, bitmap: Bitmap): ImageTransfer {
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]

        Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Pipe write failed for bitmap $requestId", t)
                try { writeEnd.closeWithError(t.message ?: "pipe_write_failed") } catch (_: Throwable) {}
            }
        }, "MediaTransfer-img-$requestId").start()

        return ImageTransfer(
            requestId = requestId,
            width = bitmap.width,
            height = bitmap.height,
            pixelFormat = bitmapConfigToPixelFormat(bitmap.config),
            rowStride = bitmap.rowBytes,
            payloadBytes = 0, // unknown until fully compressed
            source = readEnd,
            isSharedMemory = false,
            mimeType = "image/png",
        )
    }

    private fun fromAudioBytesPipe(
        requestId: String,
        bytes: ByteArray,
        mimeType: String,
    ): AudioTransfer {
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]

        Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { out ->
                    out.write(bytes)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Pipe write failed for audio $requestId", t)
                try { writeEnd.closeWithError(t.message ?: "pipe_write_failed") } catch (_: Throwable) {}
            }
        }, "MediaTransfer-aud-$requestId").start()

        return AudioTransfer(
            requestId = requestId,
            mimeType = mimeType,
            source = readEnd,
            isSharedMemory = false,
        )
    }

    // ---- Helpers ------------------------------------------------------------

    private fun bitmapConfigToPixelFormat(config: Bitmap.Config?): Int = when (config) {
        Bitmap.Config.ARGB_8888 -> PixelFormat.RGBA_8888
        Bitmap.Config.RGB_565   -> PixelFormat.RGB_565
        else                    -> PixelFormat.RGBA_8888
    }

    private fun mimeForExtension(ext: String): String = when (ext.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png"         -> "image/png"
        "webp"        -> "image/webp"
        "bmp"         -> "image/bmp"
        "wav"         -> "audio/wav"
        "mp3"         -> "audio/mpeg"
        "ogg"         -> "audio/ogg"
        "flac"        -> "audio/flac"
        "aac"         -> "audio/aac"
        "m4a"         -> "audio/mp4"
        else          -> "application/octet-stream"
    }
}
