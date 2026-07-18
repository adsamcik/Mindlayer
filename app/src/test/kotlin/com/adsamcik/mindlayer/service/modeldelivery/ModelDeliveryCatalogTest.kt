package com.adsamcik.mindlayer.service.modeldelivery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDeliveryCatalogTest {

    @Test
    fun `chat is reconstructed from two ordered on-demand packs with six gigabyte preflight`() {
        val chat = ModelDeliveryCatalog.family(ModelFamily.CHAT)

        assertEquals(
            listOf("gemma_model_part_1", "gemma_model_part_2"),
            chat.packNames,
        )
        assertEquals("gemma-4-E2B-it.litertlm", chat.outputFileName)
        assertEquals(6_000_000_000L, chat.minimumFreeBytes)
        assertEquals(2, chat.fragments.size)
        assertEquals(listOf(1, 2), chat.fragments.map { it.index })
        assertTrue(chat.fragments.all { it.totalParts == 2 })
    }

    @Test
    fun `embedding and OCR each use one independently removable pack`() {
        val embeddings = ModelDeliveryCatalog.family(ModelFamily.EMBEDDINGS)
        val ocr = ModelDeliveryCatalog.family(ModelFamily.OCR)

        assertEquals(listOf("gemma_embed_model"), embeddings.packNames)
        assertEquals(2, embeddings.files.size)
        assertEquals(listOf("paddleocr_model"), ocr.packNames)
        assertEquals(4, ocr.files.size)
        assertTrue(embeddings.minimumFreeBytes > 0L)
        assertTrue(ocr.minimumFreeBytes > 0L)
    }
}
