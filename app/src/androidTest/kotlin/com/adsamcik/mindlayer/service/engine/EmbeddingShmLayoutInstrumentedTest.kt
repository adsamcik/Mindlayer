package com.adsamcik.mindlayer.service.engine

import android.os.SharedMemory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.adsamcik.mindlayer.EmbeddingResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 27)
class EmbeddingShmLayoutInstrumentedTest {

    @Test
    fun inlinePath_writesAndReadsLayoutThroughRealSharedMemory() {
        val results = listOf(
            EmbeddingResult(
                tag = "a",
                vector = floatArrayOf(1f, 2f),
                dim = 2,
                modelId = "test-model",
                tokenCount = 3,
                truncated = false,
                backend = "GPU",
                durationMs = 4,
            ),
            EmbeddingResult(
                tag = "b",
                vector = floatArrayOf(3f, 4f),
                dim = 2,
                modelId = "test-model",
                tokenCount = 3,
                truncated = false,
                backend = "GPU",
                durationMs = 5,
            ),
        )
        val size = EmbeddingShmLayout.checkedBlobSize(results.size, 2)
        val shm = SharedMemory.create("embed-shm-layout-test-${System.nanoTime()}", size)
        var writeBuffer: ByteBuffer? = null
        var readBuffer: ByteBuffer? = null
        try {
            writeBuffer = shm.mapReadWrite()
            assertEquals("Mapping should expose the full requested size", size, writeBuffer.capacity())
            EmbeddingShmLayout.writeLayout(writeBuffer, results)
            readBuffer = shm.mapReadOnly().order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(2, readBuffer.int)
            assertEquals(2, readBuffer.int)
            assertEquals(1f, readBuffer.float, 0f)
            assertEquals(2f, readBuffer.float, 0f)
            assertEquals(3f, readBuffer.float, 0f)
            assertEquals(4f, readBuffer.float, 0f)
        } finally {
            writeBuffer?.let { SharedMemory.unmap(it) }
            readBuffer?.let { SharedMemory.unmap(it) }
            shm.close()
        }
    }

    @Test
    fun inlinePath_producesExpectedExactBytes() {
        val results = listOf(
            EmbeddingResult(
                tag = "x",
                vector = floatArrayOf(0f, 1f, -1f),
                dim = 3,
                modelId = "test-model",
                tokenCount = 0,
                truncated = false,
                backend = "CPU",
                durationMs = 0,
            ),
        )
        val size = EmbeddingShmLayout.checkedBlobSize(1, 3)
        val shm = SharedMemory.create("embed-shm-exact-test-${System.nanoTime()}", size)
        var buf: ByteBuffer? = null
        try {
            buf = shm.mapReadWrite()
            EmbeddingShmLayout.writeLayout(buf, results)
            val readBack = ByteArray(size)
            val readView = shm.mapReadOnly()
            try {
                readView.get(readBack)
            } finally {
                SharedMemory.unmap(readView)
            }
            val expected = byteArrayOf(
                0x01, 0x00, 0x00, 0x00,
                0x03, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x80.toByte(), 0x3F,
                0x00, 0x00, 0x80.toByte(), 0xBF.toByte(),
            )
            assertEquals(expected.toList(), readBack.toList())
        } finally {
            buf?.let { SharedMemory.unmap(it) }
            shm.close()
        }
    }

    @Test
    fun realSharedMemory_mapReadWrite_reportsRequestedCapacity() {
        val sizes = listOf(8, 64, 1024, 4096)
        for (size in sizes) {
            val shm = SharedMemory.create("embed-shm-capacity-$size-${System.nanoTime()}", size)
            var buf: ByteBuffer? = null
            try {
                buf = shm.mapReadWrite()
                assertNotNull(buf)
                assertEquals("size=$size", size, buf.capacity())
            } finally {
                buf?.let { SharedMemory.unmap(it) }
                shm.close()
            }
        }
    }
}
