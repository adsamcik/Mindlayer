package com.adsamcik.mindlayer.service.engine

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig

/**
 * Package-private LiteRT-LM-touching runner for the production
 * Gemma OCR extractor — Phase 3 #4.
 *
 * This file is the ONLY place in `:app/src/main` where v65-compiled
 * LiteRT-LM symbols (`Engine`, `Conversation`, `ConversationConfig`,
 * `SamplerConfig`, `Message`, `Content`, `Contents`) appear inside
 * bytecode reachable from `MindlayerMlService`. Splitting them out
 * lets pure-JVM unit tests running on JDK 17 mock
 * `MindlayerMlService` without triggering
 * `UnsupportedClassVersionError` (LiteRT-LM 0.11.0 requires JDK 21
 * at class-load time).
 *
 * The function is referenced by
 * [LiteRtLmGemmaOcrExtractorProduction.create] which calls it lazily;
 * the file itself is only loaded the first time `create()` is invoked
 * at runtime (production = JDK 21 service startup; never on the
 * JDK 17 test JVM).
 */

/**
 * Low-temperature deterministic sampler for the extraction pass.
 * Structured-output JSON does not benefit from temperature; we pin
 * to a low value + greedy top-K to maximise schema compliance.
 *
 * `by lazy` so the [SamplerConfig] constructor (a v65 type) does
 * not run during class load — only when `create()` is first called.
 */
private val EXTRACTION_SAMPLER by lazy {
    SamplerConfig(
        topK = 1,
        topP = 1.0,
        temperature = 0.0,
    )
}

/**
 * Production conversation runner: open a fresh
 * [com.google.ai.edge.litertlm.Conversation], send a single user
 * message, return the response text, close.
 *
 * Resource discipline: the `finally` block closes the conversation
 * even when [com.google.ai.edge.litertlm.Conversation.sendMessage]
 * throws, so native KV-cache memory is released on every error path.
 */
internal fun productionConversationRunner():
    suspend (engine: Any, prompt: String) -> String? = { engineAny, prompt ->
    // Unchecked cast: the production caller always supplies a real
    // LiteRT-LM Engine. The `Any?` type leak in the extractor's
    // public API exists only to keep JDK-17-targeted unit tests away
    // from the v65 Engine class file. See the extractor's class KDoc
    // for the full rationale.
    val engine = engineAny as Engine
    val conversation = engine.createConversation(
        ConversationConfig(
            samplerConfig = EXTRACTION_SAMPLER,
            tools = emptyList(),
            automaticToolCalling = false,
        ),
    )
    try {
        val response: Message = conversation.sendMessage(
            Message.user(Contents.of(prompt)),
        )
        response.text()
    } finally {
        try {
            conversation.close()
        } catch (_: Throwable) {
            // close() raising mid-error is non-fatal; the caller
            // already has the response text (or the pending
            // Throwable, which the outer catch in the extractor
            // converts to OcrExtractionResult.EMPTY).
        }
    }
}

/**
 * Extract the concatenated text content of a [Message]. Mirrors
 * the equivalent private helper inside `InferenceOrchestrator`.
 * Returns null when the message has no text parts (e.g. the model
 * emitted only tool calls).
 */
private fun Message.text(): String? {
    val parts = contents.contents.filterIsInstance<Content.Text>()
    return if (parts.isEmpty()) null else parts.joinToString("") { it.text }
}
