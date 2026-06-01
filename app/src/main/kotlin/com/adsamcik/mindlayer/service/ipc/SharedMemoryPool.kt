package com.adsamcik.mindlayer.service.ipc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Build
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.annotation.RequiresApi
import com.adsamcik.mindlayer.AudioTransfer
import com.adsamcik.mindlayer.ImageTransfer
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.logging.loggable
import com.adsamcik.mindlayer.service.logging.safeLabel
import com.adsamcik.mindlayer.service.security.IpcInputValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Hard upper bound on a single media payload accepted from a client.
 *
 * Prevents a malicious or buggy client from requesting multi-gigabyte
 * allocations via SharedMemory or streaming an unbounded PFD into the
 * staging directory.
 */
internal const val MAX_MEDIA_BYTES: Int = 100 * 1024 * 1024

/**
 * F-076: thrown by [SharedMemoryPool] when staging a new payload would
 * exceed one of the per-request or global resource caps.
 *
 * The caller-facing wire-prefixed [SecurityException] uses
 * [com.adsamcik.mindlayer.shared.MindlayerErrorCode.TRANSIENT_RESOURCE_EXHAUSTED]
 * and embeds `retryAfterMs=N` in the message body so SDKs can apply
 * jittered backoff. The reason field is *internal* (not on the wire) so
 * the failure mode shows up in diagnostics without leaking
 * implementation-specific cap structure to callers — adding a new cap
 * later doesn't break the wire contract.
 *
 * Distinct from `IllegalArgumentException` (which the caller can never
 * fix) and `TimeoutException` from the stage watchdogs (which means the
 * client's PFD source stalled). This exception means "you, the caller,
 * are fine — but the service is full right now; try again in
 * [retryAfterMs] ms".
 */
class SharedMemoryPoolExhaustedException(
    val reason: String,
    val currentCount: Int,
    val currentBytes: Long,
    val retryAfterMs: Long,
) : RuntimeException(
    "shm_pool_exhausted reason=$reason count=$currentCount bytes=$currentBytes " +
        "retryAfterMs=$retryAfterMs"
)

/** Maximum accepted image dimension (width or height). 8192² × 4 bpp = 256 MB worst-case. */
private const val MAX_IMAGE_DIM: Int = 8192

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
 * Generic SharedMemory blob acquired by [SharedMemoryPool].
 *
 * The caller writes to [buffer], calls [finishReadOnlyPfd], then must call
 * [close] once the service-side lifetime is complete. Closing releases the
 * pool reservation and the service's SharedMemory handle; the duplicated PFD
 * returned to the client remains independently owned by Binder/consumer.
 *
 * Backed by [android.os.SharedMemory] (API 27+). Callers on lower API
 * levels must use the file-backed [StagedMedia] path instead.
 */
@RequiresApi(Build.VERSION_CODES.O_MR1)
class SharedMemoryBlobAcquisition internal constructor(
    private val pool: SharedMemoryPool,
    private val scopedKey: String,
    private val reservedBytes: Long,
    private val sharedMemory: SharedMemory,
    val buffer: ByteBuffer,
) : AutoCloseable {
    private var unmapped = false
    private var closed = false

    fun finishReadOnlyPfd(): ParcelFileDescriptor {
        check(!closed) { "SharedMemory blob already closed" }
        if (!unmapped) {
            SharedMemory.unmap(buffer)
            unmapped = true
        }
        sharedMemory.setProtect(OsConstants.PROT_READ)
        val parcel = Parcel.obtain()
        return try {
            sharedMemory.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            parcel.readFileDescriptor()
        } finally {
            parcel.recycle()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        if (!unmapped) {
            runCatching { SharedMemory.unmap(buffer) }
            unmapped = true
        }
        runCatching { sharedMemory.close() }
        pool.releaseReservation(scopedKey, count = 1, bytes = reservedBytes)
    }
}
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

        /**
         * F-010: maximum wall time the audio-staging copy may run before
         * the watchdog forcibly closes the source PFD. Audio uses
         * `knownSize = null` and reads-until-EOF; without this watchdog a
         * caller can wedge the worker by holding the pipe write end open
         * forever. Adjustable per-call via [stageAudioWithTimeout].
         */
        const val AUDIO_STAGE_TIMEOUT_MS: Long = 20_000L

        /**
         * Image staging has the same read-until-EOF surface for non-shared
         * encoded image PFDs, so it needs the same forced-close watchdog.
         */
        const val IMAGE_STAGE_TIMEOUT_MS: Long = 20_000L

        /**
         * F-076: max ParcelFileDescriptors a single inference request may
         * hold staged simultaneously.
         *
         * The `infer()` / `inferMulti()` AIDL surface accepts at most ONE
         * image and ONE audio per request — multi-image, multi-audio,
         * video, and document parts are all rejected upstream by
         * [com.adsamcik.mindlayer.service.security.IpcInputValidator]
         * (see `MAX_MEDIA_PARTS_PER_REQUEST` / per-kind caps). Two PFDs
         * is therefore the *physical* per-request ceiling, not the
         * 20-PFD figure floated in earlier audit drafts. Enforcing it
         * here at the pool gate is defence-in-depth: a direct in-process
         * caller (test / future internal feature) that bypasses the
         * binder validator still cannot exceed the design contract.
         */
        const val MAX_PFDS_PER_REQUEST: Int = 2

        /**
         * F-076: global cap on simultaneously-staged PFDs across ALL
         * callers and requests.
         *
         * Each staging operation holds an open ParcelFileDescriptor for
         * the duration of the I/O copy. With ~16 concurrent PFDs we stay
         * comfortably below typical per-process FD ulimits (~1024 on
         * Android) while leaving headroom for pipes, log fds, and Binder
         * transaction descriptors. A misbehaving client launching many
         * concurrent `infer()` calls trips this gate before it can drain
         * the FD table — failing fast at the binder thread is much
         * cheaper than letting the per-stage watchdog timeout fire on
         * each one.
         */
        const val MAX_GLOBAL_ACTIVE_PFDS: Int = 16

        /**
         * F-076: global cap on staged-bytes-in-flight (across ALL
         * callers and requests).
         *
         * Protects the cache partition on low-storage devices: at
         * [MAX_MEDIA_BYTES] = 100 MiB per item with [MAX_GLOBAL_ACTIVE_PFDS]
         * = 16, naive accounting would allow up to ~1.6 GiB in-flight on
         * disk. The byte cap kicks in well before that. Reservation is
         * pessimistic for audio (size unknown until copy completes — we
         * reserve [MAX_MEDIA_BYTES] worst-case at staging start), so
         * tight concurrent-audio scenarios may see this cap before the
         * count cap. That's the intended ordering: bytes-on-disk is the
         * scarcer resource on small devices.
         */
        const val MAX_GLOBAL_STAGED_BYTES: Long = 200L * 1024L * 1024L

        /**
         * F-076: default `retryAfterMs` payload value embedded in the
         * wire-prefixed [SecurityException] when a request is rejected
         * at the pool gate. Calibrated so that 99% of in-flight stagings
         * complete within this window under typical workloads (audio
         * staging caps at [AUDIO_STAGE_TIMEOUT_MS] = 20s, but most
         * payloads finish in <500 ms). The SDK is free to apply jitter
         * on top of this hint.
         */
        const val DEFAULT_RETRY_AFTER_MS: Long = 1_000L
    }

    private val stagingDir = File(cacheDir, STAGING_DIR).also { it.mkdirs() }
    private val stagingDirCanonical: String = stagingDir.canonicalPath
    private val stagedFiles = ConcurrentHashMap<String, MutableList<File>>()
    private val activePfds = ConcurrentHashMap<String, MutableSet<ParcelFileDescriptor>>()

    /**
     * F-076: live count of staged PFDs across all callers. Mutated only
     * via [tryReserve] / [releaseReservation] / [cleanup] / [cleanupAll].
     */
    private val activeCount = AtomicInteger(0)

    /**
     * F-076: live tally of reserved staging bytes across all callers.
     * Reservations are pessimistic: image uses `payloadBytes`, audio
     * uses [MAX_MEDIA_BYTES] (size unknown until copy completes).
     */
    private val activeBytes = AtomicLong(0L)

    /**
     * F-076: per-scopedKey reservation ledger. Each entry tracks the
     * number of PFDs currently reserved for that request and the bytes
     * sum so [cleanup] knows exactly how much to release. The stored
     * [Reservation] instance is the synchronization monitor for its own
     * fields; we never mutate it without holding `synchronized(res)`.
     */
    private val reservations = ConcurrentHashMap<String, Reservation>()

    private class Reservation {
        var count: Int = 0
        var bytes: Long = 0L
    }

    private fun requestLabel(scopedKey: String): String =
        scopedKey.substringAfter(':', scopedKey).loggable()

    // ---- Public API --------------------------------------------------------

    /**
     * Acquire a generic SharedMemory region for non-media binary blobs.
     *
     * This uses the same reservation accounting as media staging but does not
     * create cache files. It is intentionally generic so embedding vectors can
     * use real SharedMemory rather than pipe/file-backed PFDs.
     *
     * Requires API 27 ([Build.VERSION_CODES.O_MR1]); callers on API 26 must
     * use the file-backed [stageImage] / [stageAudio] paths or fall back to
     * pipe-based transfer.
     */
    @RequiresApi(Build.VERSION_CODES.O_MR1)
    fun acquireBlob(scopedKey: String, sizeBytes: Int): SharedMemoryBlobAcquisition {
        require(sizeBytes > 0) { "sizeBytes must be > 0" }
        val reservedBytes = sizeBytes.toLong()
        tryReserve(scopedKey, reservedBytes)
        try {
            val shm = SharedMemory.create("mindlayer-${requestLabel(scopedKey)}-${UUID.randomUUID()}", sizeBytes)
            val buffer = shm.mapReadWrite()
            return SharedMemoryBlobAcquisition(this, scopedKey, reservedBytes, shm, buffer)
        } catch (t: Throwable) {
            releaseReservation(scopedKey, count = 1, bytes = reservedBytes)
            throw t
        }
    }
    /**
     * Stage an image for LiteRT-LM.
     *
     * - Raw pixel data (SharedMemory, no [ImageTransfer.mimeType]):
     *   pixels are decoded into a [Bitmap] and compressed to PNG.
     * - Encoded image (JPEG/PNG via PFD or SharedMemory with mimeType set):
     *   bytes are copied verbatim to a cache file.
     */
    fun stageImage(scopedKey: String, transfer: ImageTransfer): StagedMedia {
        // F-076: account for this PFD against the per-request and global
        // caps BEFORE we run any expensive validation or filesystem work.
        // Failure here is much cheaper than letting the per-stage
        // watchdog fire after we've already opened the staging file.
        val reservedBytes = transfer.payloadBytes.toLong().coerceAtLeast(1L)
        try {
            tryReserve(scopedKey, reservedBytes)
        } catch (t: SharedMemoryPoolExhaustedException) {
            // F-011: PFDs that arrive over AIDL must be closed even on
            // pre-staging rejection or the FD pool drains.
            try { transfer.source.close() } catch (_: Throwable) { /* fine */ }
            throw t
        }
        try {
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

            trackActivePfd(scopedKey, transfer.source)
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
            } finally {
                untrackActivePfd(scopedKey, transfer.source)
            }

            trackFile(scopedKey, staged)
            MindlayerLog.d(
                TAG,
                "Staged image for request ${requestLabel(scopedKey)} -> ${staged.name} ($mime)",
            )

            return StagedMedia(
                scopedKey = scopedKey,
                filePath = staged.absolutePath,
                mimeType = mime,
                cleanup = { cleanup(scopedKey) },
            )
        } catch (t: Throwable) {
            // Staging failed after we successfully reserved a slot —
            // release the reservation here so cleanup() (which the
            // orchestrator calls on the failure path) doesn't need to
            // distinguish "succeeded then cleaned up" from "failed
            // mid-stage". cleanup() on an already-released scopedKey is
            // a safe no-op for reservation accounting.
            releaseReservation(scopedKey, count = 1, bytes = reservedBytes)
            throw t
        }
    }

    suspend fun stageImageWithTimeout(
        scopedKey: String,
        transfer: ImageTransfer,
        timeoutMs: Long = IMAGE_STAGE_TIMEOUT_MS,
    ): StagedMedia = withContext(Dispatchers.IO) {
        coroutineScope {
            val watchdog = launch {
                delay(timeoutMs)
                try { transfer.source.close() } catch (_: Throwable) { /* best-effort */ }
                MindlayerLog.w(
                    TAG,
                    "Image staging watchdog fired for request ${requestLabel(scopedKey)} after ${timeoutMs}ms",
                    throwable = null,
                )
            }
            try {
                stageImage(scopedKey, transfer)
            } catch (e: java.io.IOException) {
                if (watchdog.isCompleted) {
                    throw java.util.concurrent.TimeoutException(
                        "image_staging_timeout after ${timeoutMs}ms"
                    ).apply { initCause(e) }
                }
                throw e
            } finally {
                watchdog.cancel()
            }
        }
    }

    /**
     * Stage audio for LiteRT-LM.
     *
     * Both SharedMemory and PFD sources are copied to a cache file so that
     * LiteRT-LM can consume them via `Content.AudioFile(path)`.
     */
    fun stageAudio(scopedKey: String, transfer: AudioTransfer): StagedMedia {
        // F-076: same upfront accounting as [stageImage]. Audio size is
        // unknown at this point (read-until-EOF semantics) so we reserve
        // [MAX_MEDIA_BYTES] pessimistically — that's the absolute cap
        // the per-payload guard will enforce later via copyBounded.
        val reservedBytes = MAX_MEDIA_BYTES.toLong()
        try {
            tryReserve(scopedKey, reservedBytes)
        } catch (t: SharedMemoryPoolExhaustedException) {
            try { transfer.source.close() } catch (_: Throwable) { /* fine */ }
            throw t
        }
        try {
            try {
                IpcInputValidator.validateAudioTransfer(transfer, MAX_MEDIA_BYTES)
            } catch (t: Throwable) {
                try { transfer.source.close() } catch (_: Throwable) { /* fine */ }
                throw t
            }
            val staged = createStagingFile("aud", extensionForMime(transfer.mimeType))

            trackActivePfd(scopedKey, transfer.source)
            try {
                stageFromPfd(transfer.source, staged, knownSize = null)
            } catch (t: Throwable) {
                staged.delete()
                throw t
            } finally {
                untrackActivePfd(scopedKey, transfer.source)
            }

            trackFile(scopedKey, staged)
            MindlayerLog.d(
                TAG,
                "Staged audio for request ${requestLabel(scopedKey)} -> ${staged.name} (${transfer.mimeType})",
            )

            return StagedMedia(
                scopedKey = scopedKey,
                filePath = staged.absolutePath,
                mimeType = transfer.mimeType,
                cleanup = { cleanup(scopedKey) },
            )
        } catch (t: Throwable) {
            releaseReservation(scopedKey, count = 1, bytes = reservedBytes)
            throw t
        }
    }

    /**
     * F-010: suspend variant with a watchdog that closes
     * [AudioTransfer.source] if the copy stalls more than [timeoutMs].
     * Required because audio uses `knownSize = null` and reads until EOF —
     * a caller-controlled pipe FD whose writer never closes would otherwise
     * pin a worker thread indefinitely. Closing the FD is the only reliable
     * way to break a blocked `read()` syscall on Android; coroutine
     * cancellation alone cannot interrupt it.
     */
    suspend fun stageAudioWithTimeout(
        scopedKey: String,
        transfer: AudioTransfer,
        timeoutMs: Long = AUDIO_STAGE_TIMEOUT_MS,
    ): StagedMedia = withContext(Dispatchers.IO) {
        coroutineScope {
            val watchdog = launch {
                delay(timeoutMs)
                try { transfer.source.close() } catch (_: Throwable) { /* best-effort */ }
                MindlayerLog.w(
                    TAG,
                    "Audio staging watchdog fired for request ${requestLabel(scopedKey)} after ${timeoutMs}ms",
                    throwable = null,
                )
            }
            try {
                stageAudio(scopedKey, transfer)
            } catch (e: java.io.IOException) {
                if (watchdog.isCompleted) {
                    throw java.util.concurrent.TimeoutException(
                        "audio_staging_timeout after ${timeoutMs}ms"
                    ).apply { initCause(e) }
                }
                throw e
            } finally {
                watchdog.cancel()
            }
        }
    }

    /** Delete all staged files for [scopedKey]. Safe to call multiple times. */
    fun cleanup(scopedKey: String) {
        // F-076: release pool reservations FIRST so a slot frees up the
        // moment a request is torn down — concurrent staging requests
        // racing for the cap should not have to wait for filesystem
        // delete to complete.
        releaseAllReservations(scopedKey)
        val closedPfds = closeActivePfds(scopedKey)
        val files = stagedFiles.remove(scopedKey) ?: run {
            if (closedPfds > 0) {
                MindlayerLog.d(
                    TAG,
                    "Closed $closedPfds active media PFD(s) for request ${requestLabel(scopedKey)}",
                )
            }
            return
        }
        val snapshot = synchronized(files) { files.toList() }
        var deleted = 0
        for (file in snapshot) {
            if (file.delete()) deleted++
        }
        MindlayerLog.d(
            TAG,
            "Cleaned up $deleted/${snapshot.size} staged file(s) and closed " +
                "$closedPfds active PFD(s) for request ${requestLabel(scopedKey)}",
        )
    }

    /** Delete every staged file. Called on service destroy. */
    fun cleanupAll() {
        val keys = (stagedFiles.keys().toList() + activePfds.keys().toList() +
            reservations.keys.toList()).toSet()
        for (key in keys) cleanup(key)
        stagingDir.listFiles()?.forEach { it.delete() }
        // F-076: defensive reset — after cleanup() of every known
        // scopedKey the counters should already be at zero, but we
        // hard-reset here so any leak from a defective release path
        // doesn't permanently degrade pool capacity until process
        // restart.
        activeCount.set(0)
        activeBytes.set(0L)
        reservations.clear()
    }

    // ---- F-076 reservation accounting --------------------------------------

    /**
     * Read-only snapshot check — does NOT mutate counters. Used by the
     * orchestrator BEFORE `scope.launch { runInference }` so a synchronous
     * binder thread can throw the typed
     * [SharedMemoryPoolExhaustedException] up to the AIDL surface (no
     * pipe round-trip) on the fast-fail path.
     *
     * Returns the would-be exception when any cap would be exceeded, or
     * `null` when the request is admissible. Snapshot may TOCTOU race
     * with concurrent stagings; the [tryReserve] inside [stageImage] /
     * [stageAudio] is the authoritative atomic gate.
     */
    fun precheckBounds(numImages: Int, numAudios: Int, expectedBytes: Long): SharedMemoryPoolExhaustedException? {
        require(numImages >= 0 && numAudios >= 0 && expectedBytes >= 0)
        val want = numImages + numAudios
        if (want > MAX_PFDS_PER_REQUEST) {
            return SharedMemoryPoolExhaustedException(
                reason = "per_request_pfds",
                currentCount = activeCount.get(),
                currentBytes = activeBytes.get(),
                retryAfterMs = DEFAULT_RETRY_AFTER_MS,
            )
        }
        val curCount = activeCount.get()
        if (curCount + want > MAX_GLOBAL_ACTIVE_PFDS) {
            return SharedMemoryPoolExhaustedException(
                reason = "global_active_pfds",
                currentCount = curCount,
                currentBytes = activeBytes.get(),
                retryAfterMs = DEFAULT_RETRY_AFTER_MS,
            )
        }
        val curBytes = activeBytes.get()
        if (curBytes + expectedBytes > MAX_GLOBAL_STAGED_BYTES) {
            return SharedMemoryPoolExhaustedException(
                reason = "global_staged_bytes",
                currentCount = curCount,
                currentBytes = curBytes,
                retryAfterMs = DEFAULT_RETRY_AFTER_MS,
            )
        }
        return null
    }

    /**
     * Atomically reserve a single PFD slot for [scopedKey], adding
     * [addBytes] to the global byte tally. Throws
     * [SharedMemoryPoolExhaustedException] if any cap would be exceeded;
     * partial-progress increments are always rolled back on failure so
     * the caller never observes a half-reserved state.
     *
     * Race protocol — the order of checks (count → bytes → per-request)
     * matters: each [java.util.concurrent.atomic.AtomicInteger.incrementAndGet]
     * call returns a unique post-increment value to its caller, so two
     * concurrent reservations near the limit cannot both pass the check.
     * The thread that observes `newCount > MAX_GLOBAL_ACTIVE_PFDS`
     * decrements before throwing, leaving the counter consistent for the
     * thread that observed `newCount = MAX_GLOBAL_ACTIVE_PFDS`. The same
     * pattern protects [activeBytes] (via [AtomicLong.addAndGet] and
     * symmetric subtract on failure).
     *
     * Visible to tests so the reservation accounting can be verified
     * without exercising the full Bitmap-compress pipeline (which needs
     * a real Android image encoder, not Robolectric's shadow).
     */
    @androidx.annotation.VisibleForTesting
    internal fun tryReserve(scopedKey: String, addBytes: Long) {
        require(addBytes >= 0L) { "addBytes must be >= 0 (got $addBytes)" }

        val newCount = activeCount.incrementAndGet()
        if (newCount > MAX_GLOBAL_ACTIVE_PFDS) {
            activeCount.decrementAndGet()
            throw SharedMemoryPoolExhaustedException(
                reason = "global_active_pfds",
                currentCount = newCount - 1,
                currentBytes = activeBytes.get(),
                retryAfterMs = DEFAULT_RETRY_AFTER_MS,
            )
        }

        val newBytes = activeBytes.addAndGet(addBytes)
        if (newBytes > MAX_GLOBAL_STAGED_BYTES) {
            activeBytes.addAndGet(-addBytes)
            activeCount.decrementAndGet()
            throw SharedMemoryPoolExhaustedException(
                reason = "global_staged_bytes",
                currentCount = newCount - 1,
                currentBytes = newBytes - addBytes,
                retryAfterMs = DEFAULT_RETRY_AFTER_MS,
            )
        }

        val res = reservations.computeIfAbsent(scopedKey) { Reservation() }
        val ok = synchronized(res) {
            if (res.count + 1 > MAX_PFDS_PER_REQUEST) {
                false
            } else {
                res.count += 1
                res.bytes += addBytes
                true
            }
        }
        if (!ok) {
            activeBytes.addAndGet(-addBytes)
            activeCount.decrementAndGet()
            // Don't remove the reservation entry here — it may have been
            // populated by an earlier successful reserve for this same
            // scopedKey (the binder enforces 1+1, but defence-in-depth).
            throw SharedMemoryPoolExhaustedException(
                reason = "per_request_pfds",
                currentCount = activeCount.get(),
                currentBytes = activeBytes.get(),
                retryAfterMs = DEFAULT_RETRY_AFTER_MS,
            )
        }
    }

    /**
     * Release a partial reservation. Called by [stageImage] / [stageAudio]
     * when staging fails AFTER a successful [tryReserve] but BEFORE the
     * staged file is tracked. cleanup-on-success uses the bulk
     * [releaseAllReservations] path instead.
     *
     * Visible to tests for symmetry with [tryReserve] — same rationale.
     */
    @androidx.annotation.VisibleForTesting
    internal fun releaseReservation(scopedKey: String, count: Int, bytes: Long) {
        if (count == 0 && bytes == 0L) return
        activeCount.addAndGet(-count)
        activeBytes.addAndGet(-bytes)
        val res = reservations[scopedKey] ?: return
        val drained = synchronized(res) {
            res.count -= count
            res.bytes -= bytes
            res.count <= 0 && res.bytes <= 0L
        }
        if (drained) {
            // computeIfPresent-equivalent: only remove when still drained
            // by the time we're holding the map slot, otherwise a
            // subsequent reserve that re-allocated the entry stays.
            reservations.remove(scopedKey, res)
        }
    }

    /** Release the entire per-scopedKey reservation. Called from [cleanup]. */
    private fun releaseAllReservations(scopedKey: String) {
        val res = reservations.remove(scopedKey) ?: return
        synchronized(res) {
            if (res.count != 0) activeCount.addAndGet(-res.count)
            if (res.bytes != 0L) activeBytes.addAndGet(-res.bytes)
            res.count = 0
            res.bytes = 0L
        }
    }

    /**
     * F-076 test hook — exposes the live reservation counters for
     * Robolectric assertions. NOT called from production code.
     */
    @androidx.annotation.VisibleForTesting
    internal fun reservationSnapshot(): Pair<Int, Long> = activeCount.get() to activeBytes.get()

    private fun trackActivePfd(scopedKey: String, pfd: ParcelFileDescriptor) {
        val set = activePfds.computeIfAbsent(scopedKey) {
            ConcurrentHashMap.newKeySet()
        }
        set.add(pfd)
    }

    private fun untrackActivePfd(scopedKey: String, pfd: ParcelFileDescriptor) {
        val set = activePfds[scopedKey] ?: return
        set.remove(pfd)
        if (set.isEmpty()) {
            activePfds.remove(scopedKey, set)
        }
    }

    private fun closeActivePfds(scopedKey: String): Int {
        val pfds = activePfds.remove(scopedKey) ?: return 0
        var closed = 0
        for (pfd in pfds) {
            try {
                pfd.close()
                closed++
            } catch (t: Throwable) {
                MindlayerLog.w(TAG, "Failed to close active media PFD: ${t.safeLabel()}")
            }
        }
        return closed
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
                    MindlayerLog.w(TAG, "setProtect(PROT_READ) failed; relying on private copy: ${t.safeLabel()}")
                }
                buffer = shm.mapReadOnly()
                // M21 — feed the mapped SharedMemory buffer to the bitmap directly
                // instead of allocating a fresh ByteArray(payloadSize). The repack
                // helper falls back to a row-by-row copy only when the client used
                // a non-tight rowStride, bounding peak heap regardless.
                compressPixelsFromBufferToPng(
                    buffer, transfer.width, transfer.height,
                    transfer.pixelFormat, transfer.rowStride, payloadSize, outFile,
                )
            } else {
                // Reconstruction failed — fall back to stream read
                val pixels = readExactly(pfd, payloadSize)
                compressPixelsToPng(pixels, transfer.width, transfer.height, transfer.pixelFormat, transfer.rowStride, outFile)
            }
        } finally {
            buffer?.let { SharedMemory.unmap(it) }
            shm?.close()
            try {
                pfd.close()
            } catch (t: Throwable) {
                MindlayerLog.w(TAG, "Failed to close ParcelFileDescriptor: ${t.safeLabel()}")
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
            MindlayerLog.w(TAG, "SharedMemory reconstruction failed, using stream fallback: ${t.safeLabel()}")
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
        // H5 — reject FIFOs / sockets that would block the staging thread
        // indefinitely. The original concern (per the assertSafePfdType
        // comment) is that read-until-EOF on a hostile FIFO never returns,
        // pinning the orchestrator coroutine.
        //
        // The bounded path (knownSize != null, i.e. SharedMemory-backed
        // encoded images) reads exactly knownSize bytes via readExactly, so
        // even a character-device ashmem FD can't hang the staging thread.
        // Raw-pixel SharedMemory transfers go through reconstructSharedMemory
        // (see stageImage), but encoded SharedMemory transfers fall here —
        // those legitimately come over as S_IFCHR ashmem FDs. Skipping the
        // type check on the bounded path lets the OCR `ocrAsync(bytes, mime)`
        // call work for images > OCR_INLINE_PIPE_THRESHOLD_BYTES (64 KB), the
        // SDK's default transport for non-trivial OCR payloads.
        if (knownSize == null) {
            assertSafePfdType(pfd)
        }
        try {
            if (knownSize != null) {
                require(knownSize in 1..MAX_MEDIA_BYTES) {
                    "Media payload size out of bounds: $knownSize"
                }
                requireFdSizeAtLeast(pfd, knownSize)
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
            MindlayerLog.e(TAG, "Failed to stage PFD to ${outFile.name}: ${t.safeLabel()}")
            throw t
        }
    }

    /**
     * Reject [pfd] handles that point at non-regular files (FIFOs, sockets,
     * character/block devices). Such fds can block [java.io.InputStream.read]
     * forever, pinning the orchestrator coroutine and per-session mutex.
     *
     * Visible-for-testing so JVM unit tests can drive the helper directly.
     *
     * Robolectric/JVM note: [Os.fstat] is a real JNI call. When the runtime
     * is missing the native shim (e.g. a stripped Robolectric image), or
     * [android.system.OsConstants.S_IFMT] resolves to 0 (uninitialised stub),
     * we cannot make a confident determination — in that case we permit the
     * fd. The check is defense-in-depth on top of AIDL parcelable validation;
     * the production runtime always has a working fstat.
     */
    internal fun assertSafePfdType(pfd: ParcelFileDescriptor) {
        val st = try {
            Os.fstat(pfd.fileDescriptor)
        } catch (_: UnsatisfiedLinkError) {
            return
        } catch (_: NoClassDefFoundError) {
            return
        } catch (t: Throwable) {
            // ErrnoException (and any other native-bridge failure) under JVM/
            // Robolectric — fall through to permissive behaviour. On a real
            // device a working fd never raises here.
            MindlayerLog.d(TAG, "fstat unavailable; skipping PFD-type guard: ${t.javaClass.simpleName}")
            return
        }
        val mask = OsConstants.S_IFMT
        val regular = OsConstants.S_IFREG
        // Stubbed constants → cannot enforce; allow.
        if (mask == 0 || regular == 0) return
        val type = st.st_mode and mask
        // Robolectric returns st_mode=0 for tempfiles → cannot enforce; allow.
        if (type == 0) return
        if (type != regular) {
            throw IllegalArgumentException(
                "Unsupported source PFD type: 0x${"%x".format(type)}",
            )
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

    private fun requireFdSizeAtLeast(pfd: ParcelFileDescriptor, expectedBytes: Int) {
        val st = try {
            Os.fstat(pfd.fileDescriptor)
        } catch (e: ErrnoException) {
            throw IllegalArgumentException("Unable to stat media source", e)
        }
        // SharedMemory ashmem FDs report st_size=0 regardless of the actual
        // mapped region size — the kernel doesn't expose ashmem region size via
        // fstat. We must trust the SDK-declared payloadBytes and let readExactly
        // catch any truncation via EOF. Same reasoning as the type check in
        // stageFromPfd: SharedMemory transfers come in as character devices.
        val isCharDevice = OsConstants.S_IFMT != 0 &&
            OsConstants.S_IFCHR != 0 &&
            (st.st_mode and OsConstants.S_IFMT) == OsConstants.S_IFCHR
        if (isCharDevice) return
        require(st.st_size >= expectedBytes) {
            "Media source size (${st.st_size} B) smaller than declared payloadBytes ($expectedBytes B)"
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
        rowStride: Int,
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
        val bpp = bytesPerPixel(config)
        val tightRowBytes = width * bpp
        // M4: validate dimensions and buffer size before any Bitmap allocation.
        validatePixelBufferLayout(width, height, pixelFormat, rowStride, pixels.size)
        // C2: repack rows when the source had padding (sub-bitmap, hardware-backed, etc.).
        val packed: ByteArray = if (rowStride <= 0 || rowStride == tightRowBytes) {
            pixels
        } else {
            ByteArray(tightRowBytes * height).also { dst ->
                for (y in 0 until height) {
                    System.arraycopy(pixels, y * rowStride, dst, y * tightRowBytes, tightRowBytes)
                }
            }
        }
        val bitmap = Bitmap.createBitmap(width, height, config)
        try {
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(packed))
            // F-063: cap PNG output at MAX_MEDIA_BYTES. A small input bitmap
            // can still produce a multi-GB PNG when the encoder hits a
            // pathological palette or filter sequence; without a streaming
            // counter we'd OOM the staging filesystem before the AIDL caller
            // ever sees a failure. Throws partway through compression so
            // the orchestrator's existing `media_staging_failed` cleanup
            // path deletes the partial output.
            FileOutputStream(outFile).use { fos ->
                val capped = CountingOutputStream(fos, MAX_MEDIA_BYTES.toLong(), "png_too_large")
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, capped)) {
                    throw java.io.IOException("png_compress_failed")
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * M21 — buffer-backed variant of [compressPixelsToPng]. When the client uses
     * a tight rowStride (no padding), the mapped SharedMemory ByteBuffer is fed
     * directly into the Bitmap with zero intermediate heap allocation. When the
     * stride is padded we still need to repack into a tight buffer; the ByteArray
     * sized at `tightRowBytes * height` is bounded by the M4 validation pass.
     */
    private fun compressPixelsFromBufferToPng(
        source: ByteBuffer,
        width: Int,
        height: Int,
        pixelFormat: Int,
        rowStride: Int,
        sourceSize: Int,
        outFile: File,
    ) {
        val config = pixelFormatToBitmapConfig(pixelFormat)
        val bpp = bytesPerPixel(config)
        val tightRowBytes = width * bpp
        validatePixelBufferLayout(width, height, pixelFormat, rowStride, sourceSize)
        val isTight = rowStride <= 0 || rowStride == tightRowBytes

        val bitmap = Bitmap.createBitmap(width, height, config)
        try {
            if (isTight) {
                // Slice to the exact pixel range and reset position so the bitmap
                // consumes from the start of the mapped region.
                val view = source.duplicate().apply {
                    order(source.order())
                    position(0)
                    limit(sourceSize)
                }
                bitmap.copyPixelsFromBuffer(view)
            } else {
                val packed = ByteArray(tightRowBytes * height)
                val rowBuf = ByteArray(rowStride)
                val view = source.duplicate().apply { position(0); limit(sourceSize) }
                for (y in 0 until height) {
                    view.position(y * rowStride)
                    view.get(rowBuf, 0, rowStride)
                    System.arraycopy(rowBuf, 0, packed, y * tightRowBytes, tightRowBytes)
                }
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(packed))
            }
            // F-063: cap PNG output at MAX_MEDIA_BYTES — same rationale as
            // [compressPixelsToPng]; a pathological encoder run on a small
            // input bitmap can still fill the staging filesystem.
            FileOutputStream(outFile).use { fos ->
                val capped = CountingOutputStream(fos, MAX_MEDIA_BYTES.toLong(), "png_too_large")
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, capped)) {
                    throw java.io.IOException("png_compress_failed")
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * F-063: byte-counting output stream that throws once it has streamed
     * more than [maxBytes] total. Used to cap [Bitmap.compress] so a
     * pathological encoder run cannot fill the staging filesystem before
     * the caller sees an error.
     */
    @androidx.annotation.VisibleForTesting
    internal class CountingOutputStream(
        private val delegate: java.io.OutputStream,
        private val maxBytes: Long,
        private val label: String,
    ) : java.io.OutputStream() {
        private var count: Long = 0L

        private fun checkAndAdd(n: Int) {
            count += n
            if (count > maxBytes) throw java.io.IOException(label)
        }

        override fun write(b: Int) {
            checkAndAdd(1)
            delegate.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            checkAndAdd(len)
            delegate.write(b, off, len)
        }

        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }

    /**
     * Probe an encoded image file's logical dimensions without fully
     * decoding it. Reject if the declared pixel count exceeds the safe
     * cap. Defends against an encoded image-bomb where a small file
     * declares 100k×100k pixels.
     *
     * Files that fail to expose dimensions via `inJustDecodeBounds` are
     * rejected before LiteRT-LM/native decoders see them. MIME metadata is
     * caller-controlled, so parseability is the real native-bound contract.
     */
    private fun probeAndRejectImageBomb(file: File) {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (t: Throwable) {
            MindlayerLog.w(TAG, "image bounds probe raised: ${t.safeLabel()}")
            throw IllegalArgumentException("encoded image could not be parsed")
        }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) {
            throw IllegalArgumentException("encoded image could not be parsed")
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
     * the filesystem path (F-004 path-traversal-via-requestId / F-007
     * cross-UID staging-file deletion). The canonical-path check is
     * defence in depth in case [File] on some filesystem normalises the
     * random component.
     *
     * Defensively re-creates [stagingDir] on every call. The pool only
     * runs `mkdirs()` once at construction (line 244), but Android's
     * cache-trimming policy is allowed to delete anything under
     * [Context.getCacheDir] at any time — and aggressively does so under
     * disk pressure. Without this re-create, the next [stageFromPfd]
     * write throws `FileNotFoundException(open failed: ENOENT)`. The
     * binder now classifies that as `MLERR:5004:ocrImage stage failed`
     * (TRANSIENT_RESOURCE_EXHAUSTED) — see ServiceBinder.ocrImage. Prior
     * to the disambiguation work this surfaced as the misleading
     * `MLERR:3001:ocrImage decode failed` (INVALID_REQUEST).
     * `mkdirs()` is idempotent and effectively a no-op when the
     * directory already exists, so the cost on the hot path is one
     * `stat` syscall.
     */
    private fun createStagingFile(prefix: String, extension: String): File {
        stagingDir.mkdirs()
        val uuid = UUID.randomUUID().toString()
        val staged = File(stagingDir, "${prefix}_$uuid.$extension")
        check(staged.canonicalPath.startsWith(stagingDirCanonical + File.separator)) {
            "staging path escapes staging directory"
        }
        return staged
    }

    private fun trackFile(scopedKey: String, file: File) {
        val files = stagedFiles.computeIfAbsent(scopedKey) {
            Collections.synchronizedList(mutableListOf())
        }
        files.add(file)
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

// ---- Pixel-layout helpers (internal for unit testing via Robolectric) ------

/** Allowed raw pixel formats — anything else is rejected (H1). */
private val ALLOWED_PIXEL_FORMATS = setOf(PixelFormat.RGBA_8888, PixelFormat.RGB_565)

private fun pixelFormatToBitmapConfig(pixelFormat: Int): Bitmap.Config = when (pixelFormat) {
    PixelFormat.RGBA_8888 -> Bitmap.Config.ARGB_8888
    PixelFormat.RGB_565   -> Bitmap.Config.RGB_565
    // H1: caller-supplied unknown formats must NOT silently coerce to ARGB_8888 —
    // that would let an attacker disagree with the buffer's actual layout and
    // trigger an over-read in Bitmap.copyPixelsFromBuffer.
    else                  -> throw IllegalArgumentException("Unsupported pixelFormat: $pixelFormat")
}

internal fun bytesPerPixel(config: Bitmap.Config): Int = when (config) {
    Bitmap.Config.RGB_565 -> 2
    else -> 4 // ARGB_8888 and all other configs handled by the pool
}

/**
 * Validates pixel buffer dimensions and layout. Contains no Bitmap allocation so it
 * can be unit-tested directly on JVM/Robolectric without side-effects.
 *
 * @return tight buffer size (width × bpp × height) — the size after row repacking
 */
internal fun validatePixelBufferLayout(
    width: Int,
    height: Int,
    pixelFormat: Int,
    rowStride: Int,
    bufferSize: Int,
): Long {
    require(width in 1..MAX_IMAGE_DIM) { "Image width out of range: $width" }
    require(height in 1..MAX_IMAGE_DIM) { "Image height out of range: $height" }
    require(pixelFormat in ALLOWED_PIXEL_FORMATS) {
        "Unsupported pixelFormat: $pixelFormat"
    }
    // H1 — guard the megapixel product itself in Long arithmetic, independent
    // of the per-dimension cap, so a future MAX_IMAGE_DIM bump can't accidentally
    // re-open a 4-billion-pixel allocation window.
    val pixels = width.toLong() * height.toLong()
    require(pixels <= MAX_IMAGE_DIM.toLong() * MAX_IMAGE_DIM.toLong()) {
        "Image pixel count out of range: $pixels"
    }
    val bpp = bytesPerPixel(pixelFormatToBitmapConfig(pixelFormat))
    val tightRowBytes = width.toLong() * bpp.toLong()
    val expected = if (rowStride > 0 && rowStride.toLong() != tightRowBytes) {
        require(rowStride.toLong() >= tightRowBytes) {
            "rowStride $rowStride < width*bpp $tightRowBytes"
        }
        rowStride.toLong() * height.toLong()
    } else {
        tightRowBytes * height.toLong()
    }
    require(bufferSize.toLong() == expected) {
        "Pixel buffer size $bufferSize doesn't match expected $expected"
    }
    return tightRowBytes * height.toLong()
}
