package com.adsamcik.mindlayer.service.engine.mock

/**
 * Neutral seam for the DEBUG-only "CI mock engines" LLM path.
 *
 * Lives in `src/main` so [com.adsamcik.mindlayer.service.engine.InferenceOrchestrator]
 * can reference it unconditionally, while the concrete implementation
 * ([com.adsamcik.mindlayer.service.engine.mock.MockLlmGenerator]) lives only in
 * the debug source set. When non-null on the orchestrator, the interactive LLM
 * (chat / vision / audio) path streams a synthetic, `[mock]`-tagged reply over
 * the real pipe + SharedMemory wire **without** loading the ~2.4 GB Gemma model
 * or opening a native LiteRT-LM `Conversation`.
 *
 * The orchestrator owns the streaming lifecycle (media staging, per-session
 * mutex, foreground transitions, header → token deltas → done frame,
 * cancellation, cleanup); this seam only produces the reply text, which the
 * orchestrator splits into token deltas.
 */
fun interface LlmMockGenerator {
    /**
     * Produce the full assistant reply for one inference turn. The
     * orchestrator chunks the returned text into streamed token deltas.
     *
     * Implementations MUST NOT log or persist [textContent] (it is user
     * content). The default mock echoes a short, length-bounded prefix only
     * over the wire, never to logs.
     *
     * @param textContent the caller's prompt text, or null for a media-only turn.
     * @param hasImage whether an image was attached (staged over SharedMemory).
     * @param hasAudio whether audio was attached (staged over SharedMemory).
     */
    fun reply(textContent: String?, hasImage: Boolean, hasAudio: Boolean): String
}
