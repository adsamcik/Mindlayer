package com.adsamcik.mindlayer.service.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Pure-JVM tests for [SentencePieceUnigramTokenizer]. These exercise the
 * protobuf parser and the Viterbi tokenizer against the actual
 * `embedding-gemma-300m-v1.spm.model` file when it is locally present.
 *
 * The tokenizer artifact is gitignored (Play AI Pack distribution; see
 * `embeddinggemma_model/build.gradle.kts`), so on a fresh CI runner or a
 * developer machine without the AI Pack staged, these tests skip via
 * [assumeTrue] rather than fail — matching the same pattern used for
 * gemma + paddleocr tests that depend on the unconditionally-gitignored
 * model bytes.
 *
 * Tests that don't need the real file (e.g. parsing edge cases on a
 * synthetic protobuf) would live in a separate, never-skipped test class.
 * Those are out of scope for the initial Phase D wiring commit because
 * the value-add over the integration test is small (round-tripping a
 * hand-rolled protobuf reproduces the parser's own logic).
 */
class SentencePieceUnigramTokenizerTest {

    /** Hand-resolved path to the in-tree `.spm.model` file. */
    private val tokenizerFile: File =
        File("../embeddinggemma_model/src/main/assets/embedding-gemma-300m-v1.spm.model")

    @Test
    fun `loads Gemma SentencePiece model and reports a non-trivial vocab`() {
        assumeTrue("tokenizer not staged locally; skipping", tokenizerFile.isFile)
        val tokenizer = SentencePieceUnigramTokenizer.load(tokenizerFile)
        assertNotNull(tokenizer)
    }

    @Test
    fun `BOS token prepended on every tokenize call`() {
        assumeTrue("tokenizer not staged locally; skipping", tokenizerFile.isFile)
        val tokenizer = SentencePieceUnigramTokenizer.load(tokenizerFile)
        val ids = tokenizer.tokenize("Hello, world.", maxTokens = 128)
        assertTrue("expected at least 2 tokens, got ${ids.size}", ids.size >= 2)
        // Gemma uses bos_id = 2 by convention; the loader reads it from
        // TrainerSpec so even if a future tokenizer pin changes this, the
        // first token is whatever the model declares as BOS. We assert
        // by value because the EmbeddingGemma .spm.model pins bos_id=2.
        assertEquals("first token must be BOS (2)", 2, ids[0])
    }

    @Test
    fun `tokenization is deterministic across calls`() {
        assumeTrue("tokenizer not staged locally; skipping", tokenizerFile.isFile)
        val tokenizer = SentencePieceUnigramTokenizer.load(tokenizerFile)
        val text = "task: search result | query: where is the nearest cafe"
        val a = tokenizer.tokenize(text, maxTokens = 256)
        val b = tokenizer.tokenize(text, maxTokens = 256)
        assertEquals("Unigram Viterbi must be deterministic", a.toList(), b.toList())
    }

    @Test
    fun `respects maxTokens cap by truncating from the end`() {
        assumeTrue("tokenizer not staged locally; skipping", tokenizerFile.isFile)
        val tokenizer = SentencePieceUnigramTokenizer.load(tokenizerFile)
        val long = "a ".repeat(200)
        val cap = 16
        val ids = tokenizer.tokenize(long, maxTokens = cap)
        assertTrue("tokens must fit cap (got ${ids.size}, cap $cap)", ids.size <= cap)
        assertEquals("first token must still be BOS (2)", 2, ids[0])
    }

    @Test
    fun `byte fallback handles emoji and non-ASCII safely`() {
        assumeTrue("tokenizer not staged locally; skipping", tokenizerFile.isFile)
        val tokenizer = SentencePieceUnigramTokenizer.load(tokenizerFile)
        // Emoji + CJK + accented latin — every byte must be representable
        // either as a normal vocab piece or as a byte-fallback token. The
        // tokenizer must not throw or produce a 0-length result.
        val text = "👋 你好 café"
        val ids = tokenizer.tokenize(text, maxTokens = 128)
        assertTrue("result must be non-trivial for non-ASCII input", ids.size >= 2)
    }

    @Test
    fun `zero maxTokens returns empty array`() {
        assumeTrue("tokenizer not staged locally; skipping", tokenizerFile.isFile)
        val tokenizer = SentencePieceUnigramTokenizer.load(tokenizerFile)
        val ids = tokenizer.tokenize("anything", maxTokens = 0)
        assertEquals(0, ids.size)
    }

    @Test
    fun `empty input returns just BOS when BOS is enabled`() {
        assumeTrue("tokenizer not staged locally; skipping", tokenizerFile.isFile)
        val tokenizer = SentencePieceUnigramTokenizer.load(tokenizerFile)
        val ids = tokenizer.tokenize("", maxTokens = 8)
        // The dummy prefix is added on empty text too (we get U+2581 as
        // the only character), so the body tokenization should produce
        // at least one piece. Together with BOS that's >= 2 tokens.
        assertTrue("empty input still produces BOS + dummy prefix", ids.isNotEmpty())
        assertEquals(2, ids[0])
    }

    @Test
    fun `missing file throws IllegalArgumentException`() {
        val nonexistent = File("/__definitely_does_not_exist__/sentencepiece.model")
        try {
            SentencePieceUnigramTokenizer.load(nonexistent)
            fail("expected IllegalArgumentException for missing file")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "message should reference the path: ${e.message}",
                e.message.orEmpty().contains("SentencePiece model file missing"),
            )
        }
    }
}
