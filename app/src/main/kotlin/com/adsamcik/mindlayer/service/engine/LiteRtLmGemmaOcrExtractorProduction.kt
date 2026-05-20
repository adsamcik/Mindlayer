package com.adsamcik.mindlayer.service.engine

/**
 * Production wiring for [LiteRtLmGemmaOcrExtractor].
 *
 * This file deliberately lives **outside** [LiteRtLmGemmaOcrExtractor]
 * itself so the extractor's bytecode never transitively loads any
 * LiteRT-LM class. LiteRT-LM 0.11.0 is compiled to JVM class-file
 * v65 (JDK 21); pure-JVM unit tests running on JDK 17 would raise
 * `UnsupportedClassVersionError` at class-init time if the
 * extractor's class file referenced any LiteRT-LM symbol.
 *
 * # JDK-17-safe class layout
 *
 * Keeping the v65 types confined to this companion file is not
 * enough on its own — Robolectric tests that mock
 * [com.adsamcik.mindlayer.service.MindlayerMlService] still trigger
 * resolution of every method body in that service, which in turn
 * resolves the `LiteRtLmGemmaOcrExtractorProduction` companion if it
 * is referenced via a typed call. We protect against that two ways:
 *
 *   1. `create()` is the ONLY public entry point and takes a
 *      type-erased `engineProvider: () -> Any?` so its bytecode
 *      signature has no LiteRT-LM symbol; calling sites never load
 *      v65 classes through this path's method signature.
 *   2. All LiteRT-LM symbols are confined to
 *      [productionConversationRunner] which lives in a separate
 *      package-private file
 *      (`LiteRtLmGemmaOcrExtractorRunner.kt`). That file is the only
 *      class whose load triggers v65 resolution — and it is only
 *      loaded the first time `create()` is called at runtime (which
 *      on JDK 17 happens never; on JDK 21 production it happens at
 *      service start-up).
 *
 * Call [create] from `MindlayerMlService.onCreate` to build the
 * production-mode extractor.
 */
object LiteRtLmGemmaOcrExtractorProduction {

    /**
     * Assemble a production-mode [LiteRtLmGemmaOcrExtractor].
     *
     * @param engineProvider returns the loaded LiteRT-LM `Engine` (as
     *   `Any?` — type-erased to keep this method's signature free of
     *   v65 LiteRT-LM symbols) when one is available; `null` otherwise.
     */
    fun create(
        engineProvider: () -> Any?,
        extractionTimeoutMs: Long = LiteRtLmGemmaOcrExtractor.DEFAULT_EXTRACTION_TIMEOUT_MS,
    ): LiteRtLmGemmaOcrExtractor = LiteRtLmGemmaOcrExtractor(
        engineProvider = engineProvider,
        conversationRunner = productionConversationRunner(),
        extractionTimeoutMs = extractionTimeoutMs,
    )
}

