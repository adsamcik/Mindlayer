package com.adsamcik.mindlayer.service.modeldelivery

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch

class ModelDeliveryFileLockTest {
    @Test
    fun `suspending initialization lease blocks synchronous removal`() = runTest {
        val filesDir = Files.createTempDirectory("model-delivery-lock").toFile()
        val acquired = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        try {
            val initialization = async(Dispatchers.IO) {
                ModelDeliveryFileLock.withLockSuspending(filesDir, ModelFamily.CHAT) {
                    acquired.complete(Unit)
                    release.await()
                }
            }
            acquired.await()

            val removal = async(Dispatchers.IO) {
                ModelDeliveryFileLock.withLock(filesDir, ModelFamily.CHAT) {
                    true
                }
            }

            assertFalse(removal.isCompleted)
            release.complete(Unit)
            initialization.await()
            assertTrue(removal.await())
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun `cancellation while waiting does not leak the family lease`() = runTest {
        val filesDir = Files.createTempDirectory("model-delivery-lock-cancel").toFile()
        val acquired = CompletableDeferred<Unit>()
        val waitingStarted = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)

        try {
            val holder = async(Dispatchers.IO) {
                ModelDeliveryFileLock.withLock(filesDir, ModelFamily.CHAT) {
                    acquired.complete(Unit)
                    release.await()
                }
            }
            acquired.await()

            val waiting = async(Dispatchers.Default) {
                waitingStarted.complete(Unit)
                ModelDeliveryFileLock.withLockSuspending(filesDir, ModelFamily.CHAT) {
                    Unit
                }
            }
            waitingStarted.await()
            waiting.cancel()
            release.countDown()
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(5_000) {
                    holder.await()
                    waiting.cancelAndJoin()
                    runInterruptible(Dispatchers.IO) {
                        ModelDeliveryFileLock.withLock(filesDir, ModelFamily.CHAT) {
                            Unit
                        }
                    }
                }
            }
        } finally {
            release.countDown()
            filesDir.deleteRecursively()
        }
    }
}
