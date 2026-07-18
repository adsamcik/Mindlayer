package com.adsamcik.mindlayer.service.modeldelivery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * Serializes model publication, removal, and registry verification across the
 * dashboard and `:ml` processes.
 */
internal object ModelDeliveryFileLock {
    private val processLocks = ConcurrentHashMap<String, Semaphore>()

    fun familyDir(filesDir: File, family: ModelFamily): File =
        File(filesDir, "model_delivery/${family.name.lowercase()}")

    fun removalTombstone(filesDir: File, family: ModelFamily): File =
        File(filesDir, "model_delivery/.removed_${family.name.lowercase()}")

    fun pendingRemovalMarker(filesDir: File, family: ModelFamily): File =
        File(filesDir, "model_delivery/.pending_remove_${family.name.lowercase()}")

    fun intentFile(filesDir: File, family: ModelFamily): File =
        File(filesDir, "model_delivery/.intent_${family.name.lowercase()}.json")

    fun isRemovalAuthoritative(
        filesDir: File,
        family: ModelFamily,
        lockHeld: Boolean = false,
    ): Boolean {
        val readState = {
            when (ModelDeliveryIntentStore.canonicalIntent(filesDir, family)) {
                ModelDeliveryIntent.REMOVE -> true
                ModelDeliveryIntent.INSTALL -> false
                null -> removalTombstone(filesDir, family).exists() ||
                    pendingRemovalMarker(filesDir, family).exists()
            }
        }
        return if (lockHeld) readState() else withLock(filesDir, family, readState)
    }

    fun requireAvailable(
        filesDir: File,
        family: ModelFamily,
        lockHeld: Boolean = false,
    ) {
        check(!isRemovalAuthoritative(filesDir, family, lockHeld)) {
            "${family.name.lowercase()} model is unavailable while removal is pending"
        }
    }

    fun <T> withLock(filesDir: File, family: ModelFamily, block: () -> T): T {
        val lease = acquire(filesDir, family)
        return try {
            block()
        } finally {
            lease.close()
        }
    }

    suspend fun <T> withLockSuspending(
        filesDir: File,
        family: ModelFamily,
        block: suspend () -> T,
    ): T {
        var lease: Lease? = null
        try {
            runInterruptible(Dispatchers.IO) {
                lease = acquire(filesDir, family)
            }
            return block()
        } finally {
            withContext(Dispatchers.IO + NonCancellable) {
                lease?.close()
            }
        }
    }

    private fun acquire(filesDir: File, family: ModelFamily): Lease {
        val lockDir = File(filesDir, "model_delivery/.locks").apply { mkdirs() }
        val lockFile = File(lockDir, "${family.name.lowercase()}.lock")
        val semaphore = processLocks.computeIfAbsent(lockFile.absolutePath) { Semaphore(1, true) }
        semaphore.acquire()
        var file: RandomAccessFile? = null
        try {
            file = RandomAccessFile(lockFile, "rw")
            val channel = file.channel
            val fileLock = channel.lock()
            return Lease(semaphore, file, fileLock)
        } catch (error: Throwable) {
            runCatching { file?.close() }
            semaphore.release()
            throw error
        }
    }

    private class Lease(
        private val semaphore: Semaphore,
        private val file: RandomAccessFile,
        private val fileLock: java.nio.channels.FileLock,
    ) : AutoCloseable {
        override fun close() {
            try {
                fileLock.release()
            } finally {
                try {
                    file.close()
                } finally {
                    semaphore.release()
                }
            }
        }
    }
}
