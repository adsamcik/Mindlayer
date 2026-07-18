package com.adsamcik.mindlayer.service.modeldelivery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ModelDeliveryIntentStoreTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        root = File(context.filesDir, "model-delivery-intent-store-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `explicit download advances generation and is the only action that clears removal`() {
        val firstProcess = ModelDeliveryIntentStore(root)

        assertEquals(1L, firstProcess.recordRemoval(ModelFamily.OCR))
        assertEquals(ModelDeliveryIntent.REMOVE, firstProcess.currentIntent(ModelFamily.OCR))
        assertFalse(firstProcess.provisioningAllowed(ModelFamily.OCR))
        assertTrue(ModelDeliveryFileLock.removalTombstone(root, ModelFamily.OCR).isFile)

        val restartedProcess = ModelDeliveryIntentStore(root)
        assertEquals(ModelDeliveryIntent.REMOVE, restartedProcess.currentIntent(ModelFamily.OCR))
        assertEquals(2L, restartedProcess.recordDownload(ModelFamily.OCR))

        assertEquals(ModelDeliveryIntent.INSTALL, restartedProcess.currentIntent(ModelFamily.OCR))
        assertTrue(restartedProcess.provisioningAllowed(ModelFamily.OCR))
        assertFalse(ModelDeliveryFileLock.removalTombstone(root, ModelFamily.OCR).exists())
        assertFalse(ModelDeliveryFileLock.pendingRemovalMarker(root, ModelFamily.OCR).exists())
    }

    @Test
    fun `remove record is authoritative before derivative markers exist`() {
        val interrupted = ModelDeliveryIntentStore(root) { _, intent, point ->
            if (
                intent == ModelDeliveryIntent.REMOVE &&
                point == ModelDeliveryIntentTransitionPoint.AFTER_CANONICAL_RECORD
            ) {
                throw SimulatedProcessDeath()
            }
        }

        assertThrows(SimulatedProcessDeath::class.java) {
            interrupted.recordRemoval(ModelFamily.OCR)
        }

        assertFalse(ModelDeliveryFileLock.pendingRemovalMarker(root, ModelFamily.OCR).exists())
        assertFalse(ModelDeliveryFileLock.removalTombstone(root, ModelFamily.OCR).exists())
        assertTrue(ModelDeliveryFileLock.isRemovalAuthoritative(root, ModelFamily.OCR))

        val restarted = ModelDeliveryIntentStore(root)
        assertEquals(ModelDeliveryIntent.REMOVE, restarted.reconcileMarkers(ModelFamily.OCR))
        assertEquals(ModelDeliveryIntent.REMOVE, restarted.reconcileMarkers(ModelFamily.OCR))
        assertTrue(ModelDeliveryFileLock.pendingRemovalMarker(root, ModelFamily.OCR).isFile)
        assertTrue(ModelDeliveryFileLock.removalTombstone(root, ModelFamily.OCR).isFile)
        assertFalse(restarted.provisioningAllowed(ModelFamily.OCR))
    }

    @Test
    fun `remove interrupted after pending marker converges on restart`() {
        val interrupted = ModelDeliveryIntentStore(root) { _, intent, point ->
            if (
                intent == ModelDeliveryIntent.REMOVE &&
                point == ModelDeliveryIntentTransitionPoint.AFTER_PENDING_MARKER
            ) {
                throw SimulatedProcessDeath()
            }
        }

        assertThrows(SimulatedProcessDeath::class.java) {
            interrupted.recordRemoval(ModelFamily.OCR)
        }

        assertTrue(ModelDeliveryFileLock.pendingRemovalMarker(root, ModelFamily.OCR).isFile)
        assertFalse(ModelDeliveryFileLock.removalTombstone(root, ModelFamily.OCR).exists())

        val restarted = ModelDeliveryIntentStore(root)
        restarted.reconcileMarkers(ModelFamily.OCR)
        assertTrue(ModelDeliveryFileLock.pendingRemovalMarker(root, ModelFamily.OCR).isFile)
        assertTrue(ModelDeliveryFileLock.removalTombstone(root, ModelFamily.OCR).isFile)
        assertTrue(ModelDeliveryFileLock.isRemovalAuthoritative(root, ModelFamily.OCR))
    }

    @Test
    fun `install record overrides stale removal markers before cleanup`() {
        ModelDeliveryIntentStore(root).recordRemoval(ModelFamily.OCR)
        val interrupted = ModelDeliveryIntentStore(root) { _, intent, point ->
            if (
                intent == ModelDeliveryIntent.INSTALL &&
                point == ModelDeliveryIntentTransitionPoint.AFTER_CANONICAL_RECORD
            ) {
                throw SimulatedProcessDeath()
            }
        }

        assertThrows(SimulatedProcessDeath::class.java) {
            interrupted.recordDownload(ModelFamily.OCR)
        }

        assertTrue(ModelDeliveryFileLock.pendingRemovalMarker(root, ModelFamily.OCR).isFile)
        assertTrue(ModelDeliveryFileLock.removalTombstone(root, ModelFamily.OCR).isFile)
        assertFalse(ModelDeliveryFileLock.isRemovalAuthoritative(root, ModelFamily.OCR))

        val restarted = ModelDeliveryIntentStore(root)
        assertEquals(ModelDeliveryIntent.INSTALL, restarted.reconcileMarkers(ModelFamily.OCR))
        assertFalse(ModelDeliveryFileLock.pendingRemovalMarker(root, ModelFamily.OCR).exists())
        assertFalse(ModelDeliveryFileLock.removalTombstone(root, ModelFamily.OCR).exists())
        assertTrue(restarted.provisioningAllowed(ModelFamily.OCR))
    }

    private class SimulatedProcessDeath : RuntimeException()
}
