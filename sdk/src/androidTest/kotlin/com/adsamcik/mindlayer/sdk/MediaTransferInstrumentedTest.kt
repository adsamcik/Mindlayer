package com.adsamcik.mindlayer.sdk

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented regression coverage for [MediaTransfer]'s SharedMemory path.
 *
 * The SharedMemory transport relies on extracting a [ParcelFileDescriptor]
 * from a [SharedMemory] without invoking the hidden
 * `SharedMemory.getFileDescriptor()` API — a previous reflection workaround
 * was blocked by Android hidden-API enforcement on apps targeting SDK 30+
 * and silently broke vision/audio inference end-to-end.
 *
 * Robolectric does not faithfully model SharedMemory + Parcel FD semantics,
 * so this coverage **must** run on a real device / emulator.
 */
@RunWith(AndroidJUnit4::class)
class MediaTransferInstrumentedTest {

    @Test
    fun fromBitmap_producesUsableSharedMemoryPfd() {
        val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        try {
            val transfer = MediaTransfer.fromBitmap("test-bitmap", bitmap)
            try {
                assertTrue("Expected SharedMemory transport on API 27+", transfer.isSharedMemory)
                assertEquals(64, transfer.width)
                assertEquals(32, transfer.height)
                assertEquals(bitmap.allocationByteCount, transfer.payloadBytes)
                assertNotNull(transfer.source)
                assertTrue("PFD should be valid", transfer.source.fileDescriptor.valid())

                // The PFD must outlive the source SharedMemory: the SDK closes
                // the SharedMemory in its `finally`, so the returned PFD must
                // be a fully independent dup.
                assertTrue("FD remains valid after SDK finalisation", transfer.source.fd >= 0)

                // Round-trip: reconstruct a SharedMemory from the PFD using the
                // same parcel trick the service uses, then verify we can read
                // the bitmap pixels back. This is the contract that fixes
                // vision inference end-to-end.
                val reconstructed = reconstructSharedMemory(transfer.source, transfer.payloadBytes)
                assertNotNull("Receiver-side reconstruction must succeed", reconstructed)
                reconstructed!!.use {
                    val mapped = it.mapReadOnly()
                    try {
                        assertEquals(transfer.payloadBytes, mapped.remaining())
                    } finally {
                        SharedMemory.unmap(mapped)
                    }
                }
            } finally {
                transfer.source.close()
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun fromAudioBytes_producesUsableSharedMemoryPfd() {
        val payload = ByteArray(2048) { (it % 256).toByte() }
        val transfer = MediaTransfer.fromAudioBytes("test-audio", payload, "audio/wav")
        try {
            assertTrue("Expected SharedMemory transport on API 27+", transfer.isSharedMemory)
            assertEquals("audio/wav", transfer.mimeType)
            assertNotNull(transfer.source)
            assertTrue("PFD should be valid", transfer.source.fileDescriptor.valid())

            val reconstructed = reconstructSharedMemory(transfer.source, payload.size)
            assertNotNull("Receiver-side reconstruction must succeed", reconstructed)
            reconstructed!!.use {
                val mapped = it.mapReadOnly()
                try {
                    val readBack = ByteArray(payload.size)
                    mapped.get(readBack)
                    assertTrue("Bytes round-trip through SharedMemory", payload.contentEquals(readBack))
                } finally {
                    SharedMemory.unmap(mapped)
                }
            }
        } finally {
            transfer.source.close()
        }
    }

    /**
     * Mirror of `SharedMemoryPool.reconstructSharedMemory` on the service side:
     * write `[fd, size]` to a parcel and let `SharedMemory.CREATOR` rebuild
     * the region. Lives in the test so the regression test exercises both
     * sides of the contract without depending on the service module.
     */
    private fun reconstructSharedMemory(pfd: ParcelFileDescriptor, size: Int): SharedMemory? {
        val parcel = android.os.Parcel.obtain()
        return try {
            parcel.writeFileDescriptor(pfd.fileDescriptor)
            parcel.writeInt(size)
            parcel.setDataPosition(0)
            SharedMemory.CREATOR.createFromParcel(parcel)
        } catch (_: Throwable) {
            null
        } finally {
            parcel.recycle()
        }
    }
}
