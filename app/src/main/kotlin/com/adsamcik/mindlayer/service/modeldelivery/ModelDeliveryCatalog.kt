package com.adsamcik.mindlayer.service.modeldelivery

/**
 * Immutable contract between the Play packs, local materializer, and dashboard.
 * Pack names are deliberately application-owned rather than inferred from paths.
 */
enum class ModelFamily {
    CHAT,
    EMBEDDINGS,
    OCR,
}

data class ModelArtifact(
    val filename: String,
)

data class GemmaFragment(
    val packName: String,
    val metadataFilename: String,
    val index: Int,
    val totalParts: Int,
)

data class ModelFamilySpec(
    val family: ModelFamily,
    val packNames: List<String>,
    val outputFileName: String?,
    val minimumFreeBytes: Long,
    val fragments: List<GemmaFragment> = emptyList(),
    val files: List<ModelArtifact> = emptyList(),
)

object ModelDeliveryCatalog {
    const val CHAT_FREE_SPACE_BYTES = 6_000_000_000L
    const val EMBEDDINGS_FREE_SPACE_BYTES = 1_000_000_000L
    const val OCR_FREE_SPACE_BYTES = 250_000_000L

    private val families = mapOf(
        ModelFamily.CHAT to ModelFamilySpec(
            family = ModelFamily.CHAT,
            packNames = listOf("gemma_model_part_1", "gemma_model_part_2"),
            outputFileName = "gemma-4-E2B-it.litertlm",
            minimumFreeBytes = CHAT_FREE_SPACE_BYTES,
            fragments = listOf(
                GemmaFragment(
                    "gemma_model_part_1",
                    metadataFilename = "gemma_part_1_integrity.json",
                    index = 1,
                    totalParts = 2,
                ),
                GemmaFragment(
                    "gemma_model_part_2",
                    metadataFilename = "gemma_part_2_integrity.json",
                    index = 2,
                    totalParts = 2,
                ),
            ),
        ),
        ModelFamily.EMBEDDINGS to ModelFamilySpec(
            family = ModelFamily.EMBEDDINGS,
            packNames = listOf("gemma_embed_model"),
            outputFileName = null,
            minimumFreeBytes = EMBEDDINGS_FREE_SPACE_BYTES,
            files = listOf(
                ModelArtifact("embedding-gemma-300m-v1.tflite"),
                ModelArtifact("embedding-gemma-300m-v1.spm.model"),
            ),
        ),
        ModelFamily.OCR to ModelFamilySpec(
            family = ModelFamily.OCR,
            packNames = listOf("paddleocr_model"),
            outputFileName = null,
            minimumFreeBytes = OCR_FREE_SPACE_BYTES,
            files = listOf(
                ModelArtifact("paddleocr-ppocrv5-mobile-det.tflite"),
                ModelArtifact("paddleocr-ppocrv5-mobile-rec.tflite"),
                ModelArtifact("paddleocr-ppocrv5-mobile-cls.tflite"),
                ModelArtifact("paddleocr-ppocrv5-mobile-dict.txt"),
            ),
        ),
    )

    fun family(family: ModelFamily): ModelFamilySpec = checkNotNull(families[family])
}
