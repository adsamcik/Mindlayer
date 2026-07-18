package com.adsamcik.mindlayer.service.modeldelivery

import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackStates
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class PlayAssetPackClientTest {
    @Test
    fun `cancel delegates to PAD without exposing Play types`() = runTest {
        val manager = mockk<AssetPackManager>(relaxed = true)
        every { manager.cancel(listOf("paddleocr_model")) } returns mockk<AssetPackStates>(relaxed = true)
        val client = PlayAssetPackClient(manager)

        try {
            client.cancel(listOf("paddleocr_model"))

            verify(exactly = 1) { manager.cancel(listOf("paddleocr_model")) }
        } finally {
            client.close()
        }
    }

    @Test
    fun `concurrent pack callbacks retain every pack snapshot`() {
        val client = PlayAssetPackClient(mockk<AssetPackManager>(relaxed = true))
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val iterations = 1_000

        try {
            val first = executor.submit {
                ready.countDown()
                start.await()
                repeat(iterations) { index ->
                    client.update(
                        AssetPackSnapshot(
                            packName = "gemma_part_1_$index",
                            phase = AssetPackPhase.DOWNLOADING,
                        ),
                    )
                }
            }
            val second = executor.submit {
                ready.countDown()
                start.await()
                repeat(iterations) { index ->
                    client.update(
                        AssetPackSnapshot(
                            packName = "gemma_part_2_$index",
                            phase = AssetPackPhase.DOWNLOADING,
                        ),
                    )
                }
            }

            ready.await()
            start.countDown()
            first.get()
            second.get()

            assertEquals(iterations * 2, client.states.value.size)
        } finally {
            client.close()
            executor.shutdownNow()
        }
    }
}
