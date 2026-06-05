package com.adsamcik.mindlayer.service.engine

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import com.adsamcik.mindlayer.CancelResult
import com.adsamcik.mindlayer.DeferredHandle
import com.adsamcik.mindlayer.DeferredResult
import com.adsamcik.mindlayer.EmbeddingBatchResult
import com.adsamcik.mindlayer.EmbeddingBatchTransfer
import com.adsamcik.mindlayer.EmbeddingItemMetadata
import com.adsamcik.mindlayer.EmbeddingRequest
import com.adsamcik.mindlayer.EmbeddingResult
import com.adsamcik.mindlayer.EmbeddingTask
import com.adsamcik.mindlayer.VectorBlobHandle
import com.adsamcik.mindlayer.service.MindlayerMlService
import com.adsamcik.mindlayer.service.ipc.SharedMemoryPool
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.logging.safeLabel
import com.adsamcik.mindlayer.service.security.EmbeddingBlobCrypto
import com.adsamcik.mindlayer.service.security.EvictionRegistry
import com.adsamcik.mindlayer.service.security.IpcInputValidator
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Coordinates embedding requests and transport selection.
 *
 * `embedBatchShm` uses real [android.os.SharedMemory] acquired through
 * [SharedMemoryPool] and writes `[int32 count][int32 dim]` followed by
 * `count * dim` little-endian float32 values. Deferred batches intentionally
 * persist an AES-256-GCM wrapped copy of that same layout to
 * `cacheDir/embedding-blobs/<uid>/<requestId>.bin` with atomic rename:
 * deferred results must survive engine completion, client death, service
 * process restart, and reconnect until fetch/ack/cancel cleanup.
 */
class EmbeddingCoordinator(
    private val engine: EmbeddingEngine,
    private val deferredStore: DeferredStore,
    private val context: Context,
    private val scope: CoroutineScope,
    private val callbackBroker: EvictionRegistry,
    private val sharedMemoryPool: SharedMemoryPool,
    val maxBatchInline: Int = 64,
    val maxBatchShm: Int = 4096,
    val maxBatchTotal: Int = 4096,
    val maxInputBytes: Long = 512L * 1024,
    /**
     * Whether the underlying LiteRT embedding backend actually produces real
     * vectors. Defaults to [EmbeddingFeatureFlags.IS_PRODUCTION_READY]; tests
     * override to exercise the capability-gated branches without forcing the
     * compile-time flag.
     */
    val isProductionReady: Boolean = EmbeddingFeatureFlags.IS_PRODUCTION_READY,
    private val blobCipher: EmbeddingBlobCipher = object : EmbeddingBlobCipher {
        override fun encrypt(uid: Int, plaintext: ByteArray): ByteArray =
            EmbeddingBlobCrypto.encrypt(context, uid, plaintext)

        override fun decrypt(uid: Int, ciphertext: ByteArray): ByteArray =
            EmbeddingBlobCrypto.decrypt(context, uid, ciphertext)
    },
) {
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeCount = AtomicInteger(0)

    val activeEmbeddingBatchCount: Int get() = activeCount.get()

    fun installedModels(): List<EmbeddingModelInfo> = EmbeddingModelRegistry.discoverModels(context)
    fun defaultModelOrNull(): EmbeddingModelInfo? = EmbeddingModelRegistry.getDefaultModel(installedModels())

    /**
     * One-shot startup sweep of `cacheDir/embedding-blobs/`. Removes:
     *
     *  - All stale temp files (`*.tmp-*`) left behind by an interrupted
     *    [writeBlobFile] (process kill between `RandomAccessFile.close` and
     *    `Files.move`).
     *  - Final `<requestId>.bin` blobs that have no matching DeferredEntity
     *    row in [deferredStore] — these are orphaned because the row was
     *    deleted (ack, expire, prune) but the file wasn't reachable from
     *    that code path, *or* because the process died between
     *    [writeBlobFile] and [DeferredStore.completeEmbeddingBatch] (an
     *    ack/expire path can't clean up a row that never got created).
     *
     * Safe to call from `MindlayerMlService.onCreate` before clients can
     * bind — it walks one directory per UID with a stat-only filter, deletes
     * any matches, and never throws (errors are best-effort logged).
     */
    suspend fun cleanupOrphanBlobsOnStartup() {
        runCatching {
            val root = File(context.cacheDir, "embedding-blobs")
            if (!root.isDirectory) return@runCatching
            val uidDirs = root.listFiles()?.filter { it.isDirectory } ?: return@runCatching
            var orphanedTmp = 0
            var orphanedBin = 0
            for (dir in uidDirs) {
                val files = dir.listFiles() ?: continue
                for (file in files) {
                    if (!file.isFile) continue
                    val name = file.name
                    if (name.contains(".tmp-")) {
                        // Stale temp file from a crashed atomic-move write.
                        if (file.delete()) orphanedTmp++
                    } else if (name.endsWith(".bin")) {
                        val requestId = name.removeSuffix(".bin")
                        val row = deferredStore.entityByRequestIdOrNull(requestId)
                        if (row == null || row.blobPath != file.absolutePath) {
                            if (file.delete()) orphanedBin++
                        }
                    }
                }
            }
            if (orphanedTmp > 0 || orphanedBin > 0) {
                MindlayerLog.i(
                    TAG,
                    "Embedding blob startup sweep: removed $orphanedTmp temp + $orphanedBin orphan .bin files",
                )
            }
        }.onFailure {
            MindlayerLog.w(TAG, "Embedding blob startup sweep error: ${it.safeLabel()}", throwable = null)
        }
    }

    suspend fun embed(uid: Int, req: EmbeddingRequest, requestId: String): EmbeddingResult = withTracked(uid, requestId) {
        validateSingle(req)
        val out = engine.embed(req.text, req.taskType, req.outputDim, req.normalize)
        out.toResult(req.tag)
    }

    suspend fun embedBatch(uid: Int, reqs: List<EmbeddingRequest>, requestId: String): EmbeddingBatchResult = withTracked(uid, requestId) {
        validateBatch(reqs, maxBatchInline)
        val started = System.nanoTime()
        val results = reqs.map { req ->
            val out = engine.embed(req.text, req.taskType, req.outputDim, req.normalize)
            out.toResult(req.tag)
        }
        EmbeddingBatchResult(
            results = results,
            totalDurationMs = (System.nanoTime() - started) / 1_000_000L,
            backend = results.firstOrNull()?.backend ?: "CPU",
        )
    }

    /**
     * SHM-fast-path batch embed. Uses [android.os.SharedMemory] (API 27+).
     * Callers on API 26 must use [embedBatch] or [embedBatchDeferred]
     * instead; the AIDL-boundary code in `ServiceBinder.embedBatchShm`
     * runtime-checks this and surfaces `NOT_SUPPORTED` for older devices.
     */
    @RequiresApi(Build.VERSION_CODES.O_MR1)
    suspend fun embedBatchShm(uid: Int, reqs: List<EmbeddingRequest>, requestId: String): EmbeddingBatchTransfer = withTracked(uid, requestId) {
        validateBatch(reqs, maxBatchShm)
        val started = System.nanoTime()
        val results = reqs.map { req ->
            val out = engine.embed(req.text, req.taskType, req.outputDim, req.normalize)
            out.toResult(req.tag)
        }
        writeTransfer(uid, requestId, results, (System.nanoTime() - started) / 1_000_000L)
    }

    suspend fun embedBatchDeferred(uid: Int, reqs: List<EmbeddingRequest>): DeferredHandle {
        validateBatch(reqs, maxBatchTotal)
        val requestId = "emb-$uid-${UUID.randomUUID()}"
        val handle = deferredStore.createEmbeddingBatch(uid, requestId, reqs.size)
            ?: throw typed(MindlayerErrorCode.DEFERRED_QUOTA_EXHAUSTED, "deferred quota exhausted")
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            activeCount.incrementAndGet()
            enterForeground()
            // Track the blob file separately from the local `val file` so the
            // catch / finally cleanup paths can delete an orphaned blob that
            // was atomically renamed to its final path BEFORE the deferred
            // entity was marked READY. Without this, a cancellation or
            // throwable between `writeBlobFile` and `completeEmbeddingBatch`
            // leaks `cacheDir/embedding-blobs/<uid>/<requestId>.bin` until
            // pruneExpired() removes it via TTL (24h by default).
            var blobFile: File? = null
            try {
                val started = System.nanoTime()
                val results = reqs.map { req ->
                    val out = engine.embed(req.text, req.taskType, req.outputDim, req.normalize)
                    out.toResult(req.tag)
                }
                val total = (System.nanoTime() - started) / 1_000_000L
                currentCoroutineContext().ensureActive()
                val file = writeBlobFile(uid, requestId, results)
                blobFile = file
                currentCoroutineContext().ensureActive()
                val metrics = Bundle().apply {
                    putInt("count", results.size)
                    putInt("dim", results.firstOrNull()?.dim ?: 0)
                    putString("modelId", results.firstOrNull()?.modelId.orEmpty())
                    putString("backend", results.firstOrNull()?.backend ?: "CPU")
                    putLong("totalDurationMs", total)
                }
                deferredStore.completeEmbeddingBatch(
                    requestId = requestId,
                    uid = uid,
                    blobPath = file.absolutePath,
                    blobBytes = file.length(),
                    metrics = metrics,
                    metadata = results.map { EmbeddingItemMetadata(it.tag, it.tokenCount, it.truncated) },
                )
                // Successful completion — DeferredStore now owns the blob's
                // lifetime via ack/expire. Null out so the finally cleanup
                // doesn't delete it.
                blobFile = null
            } catch (ce: CancellationException) {
                deleteOrphanBlobQuietly(blobFile)
                blobFile = null
                deferredStore.completeEmbeddingCancelled(requestId, uid)
                throw ce
            } catch (t: Throwable) {
                MindlayerLog.w(TAG, "Deferred embedding failed: ${t.safeLabel()}", requestId = requestId, throwable = null)
                deleteOrphanBlobQuietly(blobFile)
                blobFile = null
                deferredStore.failEmbeddingBatch(
                    requestId,
                    uid,
                    MindlayerErrorCode.INTERNAL,
                    MindlayerErrorCode.nameOf(MindlayerErrorCode.INTERNAL),
                )
            } finally {
                deleteOrphanBlobQuietly(blobFile)
                activeJobs.remove(key(uid, requestId))
                exitForeground()
                activeCount.decrementAndGet()
                callbackBroker.notifyEmbeddingBatchComplete(uid, requestId)
            }
        }
        activeJobs[key(uid, requestId)] = job
        job.start()
        return handle
    }

    suspend fun fetchEmbeddingBatchResult(uid: Int, requestId: String): VectorBlobHandle {
        validateRequestId(requestId)
        return when (val outcome = deferredStore.fetchEmbeddingBatch(uid, requestId)) {
            EmbeddingFetchOutcome.StillRunning -> VectorBlobHandle(status = DeferredResult.STILL_RUNNING)
            is EmbeddingFetchOutcome.Ready -> VectorBlobHandle(
                status = DeferredResult.READY,
                transfer = transferFromBlob(uid, outcome.blobPath, outcome.metrics, outcome.metadata),
            )
            EmbeddingFetchOutcome.Cancelled -> VectorBlobHandle(status = DeferredResult.CANCELLED)
            is EmbeddingFetchOutcome.Failed -> VectorBlobHandle(
                status = DeferredResult.FAILED,
                errorCodeInt = outcome.errorCodeInt,
                errorCodeName = outcome.errorCodeName,
            )
            EmbeddingFetchOutcome.Expired -> VectorBlobHandle(
                status = DeferredResult.EXPIRED,
                errorCodeInt = MindlayerErrorCode.DEFERRED_EXPIRED,
                errorCodeName = MindlayerErrorCode.nameOf(MindlayerErrorCode.DEFERRED_EXPIRED),
            )
            EmbeddingFetchOutcome.NotFoundOrNotOwned -> VectorBlobHandle(status = DeferredResult.NOT_FOUND_OR_NOT_OWNED)
        }
    }

    suspend fun cancelEmbeddingBatch(uid: Int, requestId: String): Int {
        validateRequestId(requestId)
        activeJobs.remove(key(uid, requestId))?.cancel()
        return deferredStore.cancelEmbeddingBatch(requestId, uid)
    }

    suspend fun acknowledgeEmbeddingBatchResult(uid: Int, requestId: String): Boolean {
        validateRequestId(requestId)
        return deferredStore.acknowledgeEmbeddingBatch(uid, requestId)
    }

    fun cancelEmbed(uid: Int, requestId: String): Int {
        validateRequestId(requestId)
        val job = activeJobs.remove(key(uid, requestId)) ?: return CancelResult.UNKNOWN
        job.cancel()
        return CancelResult.CANCELLED
    }

    fun cancelAllForUid(uid: Int) {
        val prefix = "$uid:"
        val keys = activeJobs.keys.filter { it.startsWith(prefix) }
        keys.forEach { activeJobs.remove(it)?.cancel() }
        blobDir(uid).listFiles()?.forEach { file ->
            if (file.isFile && (file.name.endsWith(".bin") || file.name.contains(".tmp-"))) {
                runCatching { file.delete() }
            }
        }
        if (keys.isNotEmpty()) {
            MindlayerLog.i(TAG, "Cancelled ${keys.size} embedding job(s) for uid=$uid")
        }
    }

    /**
     * R-9: cancel every in-flight embedding job across all UIDs. Used by the
     * service's ACTION_STOP and FGS onTimeout paths so embedding work does
     * not keep running (foreground-counted) after the notification is
     * dropped. Each cancelled job's `withTracked` finally releases its
     * foreground refcount.
     */
    fun cancelAll() {
        val keys = activeJobs.keys.toList()
        keys.forEach { activeJobs.remove(it)?.cancel() }
        if (keys.isNotEmpty()) {
            MindlayerLog.i(TAG, "Cancelled ${keys.size} embedding job(s) (service stop/timeout)")
        }
    }

    private suspend fun <T> withTracked(uid: Int, requestId: String, block: suspend () -> T): T {
        validateRequestId(requestId)
        val scoped = key(uid, requestId)
        val job = currentCoroutineContext()[Job] ?: error("missing coroutine job")
        if (activeJobs.putIfAbsent(scoped, job) != null) {
            throw typed(MindlayerErrorCode.DUPLICATE_REQUEST, "duplicate requestId")
        }
        activeCount.incrementAndGet()
        enterForeground()
        return try {
            block()
        } finally {
            activeJobs.remove(scoped, job)
            exitForeground()
            activeCount.decrementAndGet()
        }
    }

    private fun validateBatch(reqs: List<EmbeddingRequest>, max: Int) {
        requireUsableModel()
        if (reqs.isEmpty() || reqs.size > max) throw typed(MindlayerErrorCode.EMBEDDING_BATCH_TOO_LARGE, "embedding batch too large")
        val bytes = reqs.sumOf { it.text.toByteArray(Charsets.UTF_8).size.toLong() }
        if (bytes <= 0L || bytes > maxInputBytes) throw typed(MindlayerErrorCode.EMBEDDING_INPUT_TOO_LONG, "embedding input too long")
        reqs.forEach { validateSingle(it, featureChecked = true) }
    }

    private fun validateSingle(req: EmbeddingRequest, featureChecked: Boolean = false) {
        val model = if (featureChecked) defaultModelOrNull() ?: throw typed(MindlayerErrorCode.EMBEDDING_DISABLED, "embeddings unavailable") else requireUsableModel()
        if (req.text.isBlank()) throw typed(MindlayerErrorCode.EMBEDDING_INPUT_TOO_LONG, "embedding input empty")
        if (req.text.toByteArray(Charsets.UTF_8).size.toLong() > maxInputBytes) {
            throw typed(MindlayerErrorCode.EMBEDDING_INPUT_TOO_LONG, "embedding input too long")
        }
        if (!EmbeddingTask.isValid(req.taskType)) throw typed(MindlayerErrorCode.INVALID_REQUEST, "invalid embedding task")
        if (req.modelId != null && req.modelId != model.id) throw typed(MindlayerErrorCode.EMBEDDING_MODEL_UNAVAILABLE, "embedding model unavailable")
        if (req.outputDim != null && req.outputDim !in model.supportedDims) throw typed(MindlayerErrorCode.INVALID_REQUEST, "invalid embedding dimension")
    }

    private fun requireUsableModel(): EmbeddingModelInfo = defaultModelOrNull()
        ?: throw typed(MindlayerErrorCode.EMBEDDING_DISABLED, "embeddings unavailable")

    private fun EmbeddingOutput.toResult(tag: String?): EmbeddingResult = EmbeddingResult(
        tag = tag,
        vector = vector,
        dim = dim,
        modelId = modelId,
        tokenCount = tokenCount,
        truncated = truncated,
        backend = backend,
        durationMs = durationMs,
    )

    @RequiresApi(Build.VERSION_CODES.O_MR1)
    private suspend fun writeTransfer(uid: Int, requestId: String, results: List<EmbeddingResult>, totalMs: Long): EmbeddingBatchTransfer =
        withContext(Dispatchers.IO) {
            val count = results.size
            val dim = results.firstOrNull()?.dim ?: 0
            val size = checkedBlobSize(count, dim)
            val acquisition = sharedMemoryPool.acquireBlob(key(uid, requestId), size)
            try {
                writeLayout(acquisition.buffer.order(ByteOrder.LITTLE_ENDIAN), results)
                val pfd = acquisition.finishReadOnlyPfd()
                EmbeddingBatchTransfer(
                    pfd = pfd,
                    count = count,
                    dim = dim,
                    modelId = results.firstOrNull()?.modelId.orEmpty(),
                    perItemMetadata = results.map { EmbeddingItemMetadata(it.tag, it.tokenCount, it.truncated) },
                    totalDurationMs = totalMs,
                    backend = results.firstOrNull()?.backend ?: "CPU",
                )
            } finally {
                acquisition.close()
            }
        }

    private suspend fun writeBlobFile(uid: Int, requestId: String, results: List<EmbeddingResult>): File = withContext(Dispatchers.IO) {
        val dir = blobDir(uid)
        dir.mkdirs()
        val target = File(dir, "$requestId.bin").canonicalFile
        val parent = dir.canonicalFile
        if (target.parentFile?.canonicalPath != parent.canonicalPath) throw typed(MindlayerErrorCode.INVALID_REQUEST, "invalid requestId")
        val temp = File(dir, "$requestId.tmp-${UUID.randomUUID()}").canonicalFile
        val dim = results.firstOrNull()?.dim ?: 0
        val count = results.size
        val size = checkedBlobSize(count, dim)
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        writeLayout(buffer, results)
        val encrypted = blobCipher.encrypt(uid, buffer.array())
        buffer.array().fill(0)
        RandomAccessFile(temp, "rw").use { raf ->
            raf.channel.use { channel ->
                channel.lock().use {
                    channel.write(ByteBuffer.wrap(encrypted))
                    channel.force(true)
                }
            }
        }
        java.nio.file.Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        target
    }

    private fun transferFromBlob(uid: Int, path: String, metrics: Bundle?, metadata: List<EmbeddingItemMetadata>): EmbeddingBatchTransfer {
        val file = File(path).canonicalFile
        if (!file.isFile) throw typed(MindlayerErrorCode.DEFERRED_EXPIRED, "embedding blob missing")
        val plaintext = blobCipher.decrypt(uid, file.readBytes())
        val pfd = try {
            pipePlaintext(plaintext)
        } finally {
            plaintext.fill(0)
        }
        val count = metrics?.getInt("count") ?: 0
        val dim = metrics?.getInt("dim") ?: 0
        val modelId = metrics?.getString("modelId").orEmpty()
        val backend = metrics?.getString("backend") ?: "CPU"
        val totalMs = metrics?.getLong("totalDurationMs") ?: 0L
        return EmbeddingBatchTransfer(
            pfd = pfd,
            count = count,
            dim = dim,
            modelId = modelId,
            perItemMetadata = metadata,
            totalDurationMs = totalMs,
            backend = backend,
        )
    }

    private fun checkedBlobSize(count: Int, dim: Int): Int = try {
        EmbeddingShmLayout.checkedBlobSize(count, dim)
    } catch (e: SecurityException) {
        // Re-throw as the coordinator's typed exception so existing
        // callers + tests still see the same INVALID_REQUEST wire
        // code. EmbeddingShmLayout uses the wire-prefixed
        // SecurityException convention already, so this is just
        // re-labeling.
        throw e
    }

    private fun writeLayout(buffer: ByteBuffer, results: List<EmbeddingResult>) {
        EmbeddingShmLayout.writeLayout(buffer, results)
    }
    private fun blobDir(uid: Int): File = File(File(context.cacheDir, "embedding-blobs"), uid.toString())

    private fun validateRequestId(requestId: String) {
        try {
            IpcInputValidator.validateEmbeddingRequestId(requestId)
        } catch (_: IllegalArgumentException) {
            throw typed(MindlayerErrorCode.INVALID_REQUEST, "invalid requestId")
        }
    }

    private fun pipePlaintext(bytes: ByteArray): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        val pipeBytes = bytes.copyOf()
        try {
            thread(name = "MindlayerEmbeddingBlobPipe", isDaemon = true) {
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { out ->
                        out.write(pipeBytes)
                    }
                } finally {
                    pipeBytes.fill(0)
                }
            }
        } catch (t: Throwable) {
            pipeBytes.fill(0)
            runCatching { readEnd.close() }
            runCatching { writeEnd.close() }
            throw t
        }
        return readEnd
    }

    private fun deleteOrphanBlobQuietly(file: File?) {
        if (file == null) return
        runCatching { if (file.exists()) file.delete() }
    }

    private fun key(uid: Int, requestId: String): String = "$uid:$requestId"

    private fun enterForeground() { (context as? MindlayerMlService)?.enterForeground() }
    private fun exitForeground() { (context as? MindlayerMlService)?.exitForeground() }

    private fun typed(code: Int, message: String): SecurityException =
        SecurityException(MindlayerErrorCode.wireMessage(code, message))

    private companion object {
        private const val TAG = "EmbeddingCoordinator"
    }
}

interface EmbeddingBlobCipher {
    fun encrypt(uid: Int, plaintext: ByteArray): ByteArray
    fun decrypt(uid: Int, ciphertext: ByteArray): ByteArray
}
