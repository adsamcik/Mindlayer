package com.adsamcik.mindlayer.sdk

import android.graphics.Bitmap
import android.os.Build
import android.os.Parcel
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

/**
 * Instrumented regression coverage for [MediaTransfer]'s SharedMemory path.
 *
 * The SharedMemory transport relies on extracting a [ParcelFileDescriptor]
 * from a SharedMemory region without invoking the hidden
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
        assumeSharedMemoryAvailable()
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
                reconstructed!!.closeAfter {
                    val mapped = mapReadOnly()
                    try {
                        assertEquals(transfer.payloadBytes, mapped.remaining())
                    } finally {
                        unmapSharedMemory(mapped)
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
        assumeSharedMemoryAvailable()
        val payload = ByteArray(2048) { (it % 256).toByte() }
        val transfer = MediaTransfer.fromAudioBytes("test-audio", payload, "audio/wav")
        try {
            assertTrue("Expected SharedMemory transport on API 27+", transfer.isSharedMemory)
            assertEquals("audio/wav", transfer.mimeType)
            assertNotNull(transfer.source)
            assertTrue("PFD should be valid", transfer.source.fileDescriptor.valid())

            val reconstructed = reconstructSharedMemory(transfer.source, payload.size)
            assertNotNull("Receiver-side reconstruction must succeed", reconstructed)
            reconstructed!!.closeAfter {
                val mapped = mapReadOnly()
                try {
                    val readBack = ByteArray(payload.size)
                    mapped.get(readBack)
                    assertTrue("Bytes round-trip through SharedMemory", payload.contentEquals(readBack))
                } finally {
                    unmapSharedMemory(mapped)
                }
            }
        } finally {
            transfer.source.close()
        }
    }

    /**
     * Mirror of `SharedMemoryPool.reconstructSharedMemory` on the service side:
     * write `[fd, size]` to a parcel and let `SharedMemory.CREATOR` rebuild
     * the region. Uses reflection so API 26 can discover and skip this test
     * class without trying to resolve the API 27-only `android.os.SharedMemory`.
     */
    private fun reconstructSharedMemory(pfd: ParcelFileDescriptor, size: Int): Any? {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeFileDescriptor(pfd.fileDescriptor)
            parcel.writeInt(size)
            parcel.setDataPosition(0)
            val creator = sharedMemoryClass
                .getField("CREATOR")
                .get(null)
            creator.javaClass
                .getMethod("createFromParcel", Parcel::class.java)
                .invoke(creator, parcel)
        } catch (_: Throwable) {
            null
        } finally {
            parcel.recycle()
        }
    }

    private fun assumeSharedMemoryAvailable() {
        assumeTrue(
            "SharedMemory is available on API 27+ only",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1,
        )
    }

    private fun Any.mapReadOnly(): ByteBuffer =
        sharedMemoryClass.getMethod("mapReadOnly").invoke(this) as ByteBuffer

    private fun Any.closeAfter(block: Any.() -> Unit) {
        try {
            block()
        } finally {
            sharedMemoryClass.getMethod("close").invoke(this)
        }
    }

    private fun unmapSharedMemory(buffer: ByteBuffer) {
        sharedMemoryClass.getMethod("unmap", ByteBuffer::class.java).invoke(null, buffer)
    }

    private val sharedMemoryClass: Class<*>
        get() = Class.forName("android.os.SharedMemory")
}
