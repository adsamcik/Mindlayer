package com.adsamcik.mindlayer.service.ipc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.util.Log
import androidx.annotation.RequiresApi
import com.adsamcik.mindlayer.AudioTransfer
import com.adsamcik.mindlayer.ImageTransfer
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.security.IpcInputValidator
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Hard upper bound on a single media payload accepted from a client.
 *
 * Prevents a malicious or buggy client from requesting multi-gigabyte
 * allocations via SharedMemory or streaming an unbounded PFD into the
 * staging directory.
 */
internal const val MAX_MEDIA_BYTES: Int = 100 * 1024 * 1024

/**
 * Staged media ready for LiteRT-LM consumption.
 *
 * The [filePath] points to a cache file that LiteRT-LM can open directly
 * via `Content.ImageFile(path)` or `Content.AudioFile(path)`.
 * Call [cleanup] (or [SharedMemoryPool.cleanup]) when inference completes.
 */
data class StagedMedia(
    val scopedKey: String,
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
 *
 * **Security note**: every staging operation is keyed by a binder-supplied
 * **scopedKey** (`uid:publicRequestId`) — never the raw `requestId` field
 * from the AIDL Parcelable. This blocks the cross-UID staging-file deletion
 * surface (`F-007`) and the path-traversal-via-requestId surface (`F-004`).
 * The `requestId` from the Parcelable is never interpolated into the
 * filesystem path.
 */
class SharedMemoryPool(cacheDir: File) {

    companion object {
        private const val TAG = "SharedMemoryPool"
        private const val STAGING_DIR = "media_staging"
        private const val COPY_BUFFER_SIZE = 8192
    }

    private val stagingDir = File(cacheDir, STAGING_DIR).also { it.mkdirs() }
    private val stagingDirCanonical: String = stagingDir.canonicalPath
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
    fun stageImage(scopedKey: String, transfer: ImageTransfer): StagedMedia {
        // Defence-in-depth: re-run dimensional validation here even though the
        // binder ingress already called the same validator. This keeps the
        // pool safe against direct callers from tests and keeps the failure
        // path FD-leak-free (we own pfd close on every failure path below).
        try {
            IpcInputValidator.validateImageTransfer(transfer, MAX_MEDIA_BYTES)
        } catch (t: Throwable) {
            // F-011: PFDs that arrive over AIDL must be closed here even on
            // pre-staging validation failure or the FD pool drains.
            try { transfer.source.close() } catch (_: Throwable) { /* fine */ }
            throw t
        }

        val isRawPixels = transfer.isSharedMemory && transfer.mimeType == null
        val mime = transfer.mimeType ?: "image/png"
        val staged = createStagingFile("img", extensionForMime(mime))

        try {
            if (isRawPixels) {
                stageRawPixels(transfer, staged)
            } else {
                stageFromPfd(transfer.source, staged, transfer.payloadBytes.takeIf { transfer.isSharedMemory })
                // For encoded images, probe dimensions BEFORE handing the
                // file to LiteRT-LM. A small compressed image can declare
                // huge logical dimensions that blow up native decoding
                // (image-bomb, F-001 encoded path).
                probeAndRejectImageBomb(staged)
            }
        } catch (t: Throwable) {
            staged.delete()
            throw t
        }

        trackFile(scopedKey, staged)
        Log.d(TAG, "Staged image for scopedKey=$scopedKey → ${staged.name} ($mime)")

        return StagedMedia(
            scopedKey = scopedKey,
            filePath = staged.absolutePath,
            mimeType = mime,
            cleanup = { cleanup(scopedKey) },
        )
    }

    /**
     * Stage audio for LiteRT-LM.
     *
     * Both SharedMemory and PFD sources are copied to a cache file so that
     * LiteRT-LM can consume them via `Content.AudioFile(path)`.
     */
    fun stageAudio(scopedKey: String, transfer: AudioTransfer): StagedMedia {
        try {
            IpcInputValidator.validateAudioTransfer(transfer)
        } catch (t: Throwable) {
            try { transfer.source.close() } catch (_: Throwable) { /* fine */ }
            throw t
        }
        val staged = createStagingFile("aud", extensionForMime(transfer.mimeType))

        try {
            stageFromPfd(transfer.source, staged, knownSize = null)
        } catch (t: Throwable) {
            staged.delete()
            throw t
        }

        trackFile(scopedKey, staged)
        Log.d(TAG, "Staged audio for scopedKey=$scopedKey → ${staged.name} (${transfer.mimeType})")

        return StagedMedia(
            scopedKey = scopedKey,
            filePath = staged.absolutePath,
            mimeType = transfer.mimeType,
            cleanup = { cleanup(scopedKey) },
        )
    }

    /** Delete all staged files for [scopedKey]. Safe to call multiple times. */
    fun cleanup(scopedKey: String) {
        val files = stagedFiles.remove(scopedKey) ?: return
        var deleted = 0
        for (file in files) {
            if (file.delete()) deleted++
        }
        Log.d(TAG, "Cleaned up $deleted/${files.size} staged file(s) for scopedKey=$scopedKey")
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
            Log.w(TAG, "SharedMemory on API ${Build.VERSION.SDK_INT} — falling back to stream copy")
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
                // F-015 / F-021: verify region size accommodates the
                // declared payload, and seal it READ-only before mapping
                // to keep the buffer immutable for the duration of the
                // copy.
                require(shm.size >= payloadSize) {
                    "SharedMemory region (${shm.size}B) smaller than declared " +
                        "payloadBytes ($payloadSize B)"
                }
                try {
                    shm.setProtect(android.system.OsConstants.PROT_READ)
                } catch (t: Throwable) {
                    // setProtect may fail if the client retains a writable
                    // mapping; we proceed because we map READ-only and copy
                    // to a private buffer immediately, but we log so the
                    // failure is visible in diagnostics.
                    MindlayerLog.w(TAG, "setProtect(PROT_READ) failed; relying on private copy", throwable = t)
                }
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
            Log.w(TAG, "SharedMemory reconstruction failed, using stream fallback", t)
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
                val bytes = readExactly(pfd, knownSize)
                outFile.writeBytes(bytes)
            } else {
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                    FileOutputStream(outFile).use { output ->
                        copyBounded(input, output, MAX_MEDIA_BYTES)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to stage PFD to ${outFile.name}", t)
            throw t
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
                if (n == -1) break
                offset += n
            }
            // F-016: a truncated PFD must NOT silently zero-pad the buffer
            // — that would fabricate image content for raw pixels and pass
            // a malformed file to native decoders for encoded payloads.
            if (offset != size) {
                throw EOFException("PFD truncated: expected $size bytes, got $offset")
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
        // F-001 / F-058: dimensions and pixel format already validated at
        // AIDL ingress, but re-check here so direct callers (tests) can't
        // bypass the guard. Reject unknown formats explicitly rather than
        // falling through to ARGB_8888.
        require(width in 1..IpcInputValidator.MAX_IMG_DIMENSION) {
            "width out of bounds: $width"
        }
        require(height in 1..IpcInputValidator.MAX_IMG_DIMENSION) {
            "height out of bounds: $height"
        }
        require(pixelFormat in IpcInputValidator.ALLOWED_PIXEL_FORMATS) {
            "unsupported pixelFormat: $pixelFormat"
        }
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

    /**
     * Probe an encoded image file's logical dimensions without fully
     * decoding it. Reject if the declared pixel count exceeds the safe
     * cap. Defends against an encoded image-bomb where a small file
     * declares 100k×100k pixels.
     *
     * Files that fail to expose dimensions via `inJustDecodeBounds` are
     * **not** rejected here — they will fail later in the LiteRT-LM
     * decoder pipeline. The bomb attack relies on the file PARSING
     * successfully and reporting huge dimensions; a non-parsing file
     * cannot allocate native pixel memory.
     */
    private fun probeAndRejectImageBomb(file: File) {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (t: Throwable) {
            // Don't escalate decoder exceptions on the probe — they
            // indicate the file isn't a real image, not that it is a
            // bomb. Production rejection happens downstream.
            MindlayerLog.w(TAG, "image bounds probe raised; deferring to native decoder", throwable = t)
            return
        }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) {
            // Can't read bounds — same reasoning as above.
            return
        }
        require(
            opts.outWidth <= IpcInputValidator.MAX_IMG_DIMENSION &&
                opts.outHeight <= IpcInputValidator.MAX_IMG_DIMENSION,
        ) {
            "encoded image dimension out of bounds: " +
                "${opts.outWidth}×${opts.outHeight}"
        }
        val pixels: Long = try {
            Math.multiplyExact(opts.outWidth.toLong(), opts.outHeight.toLong())
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("encoded image dimensions overflow")
        }
        require(pixels <= IpcInputValidator.MAX_IMG_PIXELS) {
            "encoded image pixel count out of bounds: $pixels"
        }
    }

    // ---- Helpers ------------------------------------------------------------

    /**
     * Build a staging-file path that is guaranteed to live under
     * [stagingDir] regardless of caller-supplied input. We use
     * [UUID.randomUUID] only — the caller's `requestId` never reaches
     * the filesystem path. The canonical-path check is defence in depth
     * in case [File] on some filesystem normalises the random component.
     */
    private fun createStagingFile(prefix: String, extension: String): File {
        val uuid = UUID.randomUUID().toString()
        val staged = File(stagingDir, "${prefix}_$uuid.$extension")
        check(staged.canonicalPath.startsWith(stagingDirCanonical + File.separator)) {
            "staging path escapes staging directory"
        }
        return staged
    }

    private fun trackFile(scopedKey: String, file: File) {
        stagedFiles.getOrPut(scopedKey) { mutableListOf() }.add(file)
    }

    private fun pixelFormatToBitmapConfig(pixelFormat: Int): Bitmap.Config = when (pixelFormat) {
        PixelFormat.RGBA_8888 -> Bitmap.Config.ARGB_8888
        PixelFormat.RGB_565  -> Bitmap.Config.RGB_565
        else -> throw IllegalArgumentException("unsupported pixelFormat: $pixelFormat")
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
        // F-060: unknown MIME types are rejected outright. The validator
        // already enforces an allowlist at AIDL ingress; this check
        // protects direct in-process callers.
        else -> throw IllegalArgumentException("unsupported MIME type: $mimeType")
    }
}
