package com.adsamcik.mindlayer.service.ipc

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import androidx.annotation.RequiresApi
import com.adsamcik.mindlayer.AudioTransfer
import com.adsamcik.mindlayer.ImageTransfer
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import java.io.File
import java.io.FileOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Hard upper bound on a single media payload accepted from a client.
 *
 * Prevents a malicious or buggy client from requesting multi-gigabyte
 * allocations via SharedMemory or streaming an unbounded PFD into the
 * staging directory.
 */
private const val MAX_MEDIA_BYTES: Int = 100 * 1024 * 1024

/**
 * Staged media ready for LiteRT-LM consumption.
 *
 * The [filePath] points to a cache file that LiteRT-LM can open directly
 * via `Content.ImageFile(path)` or `Content.AudioFile(path)`.
 * Call [cleanup] (or [SharedMemoryPool.cleanup]) when inference completes.
 */
data class StagedMedia(
    val requestId: String,
    val filePath: String,
    val mimeType: String,
    val cleanup: () -> Unit,
)

/**
 * Service-side handler that receives [ImageTransfer] / [AudioTransfer] handles
 * from the client and stages media to cache files for LiteRT-LM.
 *
 * Supports two transport modes:
 *  - **SharedMemory** (API 27+): client-allocated `android.os.SharedMemory`
 *    wrapped in a [ParcelFileDescriptor] via `dup()`. The pool maps the region
 *    read-only, extracts the bytes, and writes them to a staging file.
 *  - **ParcelFileDescriptor** (all API levels): a regular file descriptor
 *    (open file or content URI). The pool copies bytes to a staging file.
 *
 * Thread-safety: individual public methods are safe to call from any thread.
 * The pool uses [ConcurrentHashMap] for bookkeeping and performs I/O on the
 * caller's thread (the orchestrator's coroutine dispatcher).
 */
class SharedMemoryPool(cacheDir: File) {

    companion object {
        private const val TAG = "SharedMemoryPool"
        private const val STAGING_DIR = "media_staging"
        private const val COPY_BUFFER_SIZE = 8192
        private val UnsafeFilenameChars = Regex("[^A-Za-z0-9-]")
    }

    private val stagingDir = File(cacheDir, STAGING_DIR).also { it.mkdirs() }
    private val stagedFiles = ConcurrentHashMap<String, MutableList<File>>()

    // ---- Public API --------------------------------------------------------

    /**
     * Stage an image for LiteRT-LM.
     *
     * - Raw pixel data (SharedMemory, no [ImageTransfer.mimeType]):
     *   pixels are decoded into a [Bitmap] and compressed to PNG.
     * - Encoded image (JPEG/PNG via PFD or SharedMemory with mimeType set):
     *   bytes are copied verbatim to a cache file.
     */
    fun stageImage(transfer: ImageTransfer): StagedMedia {
        val requestId = transfer.requestId
        val isRawPixels = transfer.isSharedMemory && transfer.mimeType == null
        val mime = transfer.mimeType ?: "image/png"
        // For SharedMemory transfers the client declares payloadBytes up-front;
        // reject anything outside (0, MAX_MEDIA_BYTES] before any allocation.
        if (transfer.isSharedMemory) {
            require(transfer.payloadBytes in 1..MAX_MEDIA_BYTES) {
                "Media payload size out of bounds: ${transfer.payloadBytes}"
            }
        }
        val staged = createStagingFile(requestId, "img", extensionForMime(mime))

        try {
            if (isRawPixels) {
                stageRawPixels(transfer, staged)
            } else {
                stageFromPfd(transfer.source, staged, transfer.payloadBytes.takeIf { transfer.isSharedMemory })
            }
        } catch (t: Throwable) {
            staged.delete()
            throw t
        }

        trackFile(requestId, staged)
        MindlayerLog.d(TAG, "Staged image → ${staged.name} ($mime)", requestId = requestId)

        return StagedMedia(
            requestId = requestId,
            filePath = staged.absolutePath,
            mimeType = mime,
            cleanup = { cleanup(requestId) },
        )
    }

    /**
     * Stage audio for LiteRT-LM.
     *
     * Both SharedMemory and PFD sources are copied to a cache file so that
     * LiteRT-LM can consume them via `Content.AudioFile(path)`.
     */
    fun stageAudio(transfer: AudioTransfer): StagedMedia {
        val requestId = transfer.requestId
        val knownSize = declaredPayloadSize(transfer.payloadBytes)
            ?: transfer.source.statSize
                .takeIf { transfer.isSharedMemory && it >= 0L }
                ?.also(::requireMediaSize)
                ?.toInt()
        val staged = createStagingFile(requestId, "aud", extensionForMime(transfer.mimeType))

        try {
            stageFromPfd(transfer.source, staged, knownSize = knownSize)
        } catch (t: Throwable) {
            staged.delete()
            throw t
        }

        trackFile(requestId, staged)
        MindlayerLog.d(TAG, "Staged audio → ${staged.name} (${transfer.mimeType})", requestId = requestId)

        return StagedMedia(
            requestId = requestId,
            filePath = staged.absolutePath,
            mimeType = transfer.mimeType,
            cleanup = { cleanup(requestId) },
        )
    }

    /** Delete all staged files for [requestId]. Safe to call multiple times. */
    fun cleanup(requestId: String) {
        val files = stagedFiles.remove(requestId) ?: return
        val snapshot = synchronized(files) { files.toList() }
        var deleted = 0
        for (file in snapshot) {
            if (file.delete()) deleted++
        }
        MindlayerLog.d(TAG, "Cleaned up $deleted/${snapshot.size} staged file(s)", requestId = requestId)
    }

    /** Delete every staged file. Called on service destroy. */
    fun cleanupAll() {
        for (key in stagedFiles.keys().toList()) cleanup(key)
        stagingDir.listFiles()?.forEach { it.delete() }
    }

    // ---- Raw pixel staging (SharedMemory, API 27+) -------------------------

    private fun stageRawPixels(transfer: ImageTransfer, outFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            stageRawPixelsApi27(transfer, outFile)
        } else {
            // API 26: isSharedMemory should never be true (SDK guards this),
            // but handle defensively by treating source as a regular fd.
            MindlayerLog.w(TAG, "SharedMemory on API ${Build.VERSION.SDK_INT} — falling back to stream copy")
            stageFromPfd(transfer.source, outFile, knownSize = null)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O_MR1)
    private fun stageRawPixelsApi27(transfer: ImageTransfer, outFile: File) {
        val pfd = transfer.source
        val payloadSize = transfer.payloadBytes
        var buffer: ByteBuffer? = null
        var shm: SharedMemory? = null
        try {
            shm = reconstructSharedMemory(pfd, payloadSize)
            if (shm != null) {
                buffer = shm.mapReadOnly()
                val pixels = ByteArray(payloadSize)
                buffer.get(pixels)
                compressPixelsToPng(pixels, transfer.width, transfer.height, transfer.pixelFormat, outFile)
            } else {
                // Reconstruction failed — fall back to stream read
                val pixels = readExactly(pfd, payloadSize)
                compressPixelsToPng(pixels, transfer.width, transfer.height, transfer.pixelFormat, outFile)
            }
        } finally {
            buffer?.let { SharedMemory.unmap(it) }
            shm?.close()
            try {
                pfd.close()
            } catch (t: Throwable) {
                MindlayerLog.w(TAG, "Failed to close ParcelFileDescriptor", throwable = t)
            }
        }
    }

    // ---- SharedMemory reconstruction via Parcel ----------------------------

    /**
     * Attempt to reconstruct a [SharedMemory] from a [ParcelFileDescriptor]
     * that wraps an ashmem fd. Returns `null` on failure (caller should
     * fall back to stream-based I/O).
     */
    @RequiresApi(Build.VERSION_CODES.O_MR1)
    private fun reconstructSharedMemory(pfd: ParcelFileDescriptor, size: Int): SharedMemory? {
        return try {
            val parcel = android.os.Parcel.obtain()
            try {
                parcel.writeFileDescriptor(pfd.fileDescriptor)
                parcel.writeInt(size)
                parcel.setDataPosition(0)
                SharedMemory.CREATOR.createFromParcel(parcel)
            } finally {
                parcel.recycle()
            }
        } catch (t: Throwable) {
            MindlayerLog.w(TAG, "SharedMemory reconstruction failed, using stream fallback", throwable = t)
            null
        }
    }

    // ---- Stream-based staging (PFD files & fallback) -----------------------

    /**
     * Copy bytes from [pfd] to [outFile].
     *
     * If [knownSize] is non-null, reads exactly that many bytes (for SharedMemory
     * fds where EOF semantics are unreliable). Otherwise reads until EOF.
     */
    private fun stageFromPfd(pfd: ParcelFileDescriptor, outFile: File, knownSize: Int?) {
        try {
            if (knownSize != null) {
                require(knownSize in 1..MAX_MEDIA_BYTES) {
                    "Media payload size out of bounds: $knownSize"
                }
                writeExactly(pfd, knownSize, outFile)
            } else {
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                    FileOutputStream(outFile).use { output ->
                        copyBounded(input, output, MAX_MEDIA_BYTES)
                    }
                }
            }
        } catch (t: Throwable) {
            MindlayerLog.e(TAG, "Failed to stage PFD to ${outFile.name}", throwable = t)
            throw t
        }
    }

    /** Stream exactly [size] bytes from [pfd] to [outFile], closing the PFD when done. */
    private fun writeExactly(pfd: ParcelFileDescriptor, size: Int, outFile: File) {
        require(size in 1..MAX_MEDIA_BYTES) {
            "Media payload size out of bounds: $size"
        }
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
            FileOutputStream(outFile).use { output ->
                copyExactly(input, output, size)
            }
        }
    }

    /**
     * Copy bytes from [input] to [output], aborting if the total exceeds
     * [maxBytes]. Throws [IllegalArgumentException] on overflow so callers
     * treat it uniformly with the up-front `require(...)` bounds.
     */
    private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, maxBytes: Int) {
        val buf = ByteArray(COPY_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n == -1) break
            total += n
            require(total <= maxBytes) {
                "Media payload size out of bounds: >$maxBytes"
            }
            output.write(buf, 0, n)
        }
    }

    private fun copyExactly(input: java.io.InputStream, output: java.io.OutputStream, expectedBytes: Int) {
        val buf = ByteArray(COPY_BUFFER_SIZE)
        var remaining = expectedBytes
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size, remaining))
            if (n == -1) {
                throw EOFException("Expected $expectedBytes bytes, missing $remaining")
            }
            output.write(buf, 0, n)
            remaining -= n
        }
    }

    private fun declaredPayloadSize(payloadBytes: Int): Int? {
        if (payloadBytes == 0) return null
        requireMediaSize(payloadBytes.toLong())
        return payloadBytes
    }

    private fun requireMediaSize(size: Long) {
        require(size in 1..MAX_MEDIA_BYTES.toLong()) {
            "Media payload size out of bounds: $size"
        }
    }

    /** Read exactly [size] bytes from [pfd], closing it when done. */
    private fun readExactly(pfd: ParcelFileDescriptor, size: Int): ByteArray {
        require(size in 1..MAX_MEDIA_BYTES) {
            "Media payload size out of bounds: $size"
        }
        val bytes = ByteArray(size)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
            var offset = 0
            while (offset < size) {
                val n = input.read(bytes, offset, size - offset)
                if (n == -1) {
                    throw EOFException("Expected $size bytes, missing ${size - offset}")
                }
                offset += n
            }
        }
        return bytes
    }

    // ---- Pixel → PNG encoding ----------------------------------------------

    private fun compressPixelsToPng(
        pixels: ByteArray,
        width: Int,
        height: Int,
        pixelFormat: Int,
        outFile: File,
    ) {
        val config = pixelFormatToBitmapConfig(pixelFormat)
        val bitmap = Bitmap.createBitmap(width, height, config)
        try {
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
            FileOutputStream(outFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
        } finally {
            bitmap.recycle()
        }
    }

    // ---- Helpers ------------------------------------------------------------

    private fun createStagingFile(requestId: String, prefix: String, extension: String): File {
        val short = UUID.randomUUID().toString().take(8)
        val safeRequestId = sanitizeRequestIdForFilename(requestId)
        return File(stagingDir, "${prefix}_${safeRequestId}_$short.$extension")
    }

    private fun trackFile(requestId: String, file: File) {
        val files = stagedFiles.computeIfAbsent(requestId) {
            Collections.synchronizedList(mutableListOf())
        }
        files.add(file)
    }

    private fun sanitizeRequestIdForFilename(requestId: String): String =
        requestId.replace(UnsafeFilenameChars, "_").ifBlank { "request" }

    private fun pixelFormatToBitmapConfig(pixelFormat: Int): Bitmap.Config = when (pixelFormat) {
        PixelFormat.RGBA_8888 -> Bitmap.Config.ARGB_8888
        PixelFormat.RGB_565  -> Bitmap.Config.RGB_565
        else                 -> Bitmap.Config.ARGB_8888
    }

    private fun extensionForMime(mimeType: String): String = when (mimeType) {
        "image/jpeg"              -> "jpg"
        "image/png"               -> "png"
        "image/webp"              -> "webp"
        "image/bmp"               -> "bmp"
        "audio/wav", "audio/x-wav" -> "wav"
        "audio/mp3", "audio/mpeg" -> "mp3"
        "audio/ogg"               -> "ogg"
        "audio/flac"              -> "flac"
        "audio/aac"               -> "aac"
        "audio/mp4"               -> "m4a"
        else                      -> "bin"
    }
}
