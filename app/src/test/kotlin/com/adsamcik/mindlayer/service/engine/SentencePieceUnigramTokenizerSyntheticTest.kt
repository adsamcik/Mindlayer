package com.adsamcik.mindlayer.service.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Never-skipped synthetic-fixture tests for [SentencePieceUnigramTokenizer].
 *
 * Complements [SentencePieceUnigramTokenizerTest] (which gates every test
 * on the real Gemma `.spm.model` file being present locally via
 * [org.junit.Assume.assumeTrue]) with deterministic fixtures built in
 * memory from a minimal protobuf, so CI ALWAYS exercises:
 *
 *  - BOM-prefixed input (U+FEFF);
 *  - surrogate pairs / multi-byte UTF-8 (emoji);
 *  - combining chars + zero-width chars;
 *  - `maxTokens = 1` truncation (must still place BOS);
 *  - byte-fallback path on bytes that are not in any vocab piece;
 *  - empty-string input;
 *  - invalid UTF-16 lone-surrogate input (replaces with U+FFFD bytes);
 *  - a tiny ASCII vocab gets exact Viterbi matches without falling back.
 *
 * The protobuf is hand-built by [synthModel] — small enough to keep the
 * test self-contained and document the wire format the parser expects.
 */
class SentencePieceUnigramTokenizerSyntheticTest {

    // ── Protobuf wire helpers ───────────────────────────────────────────

    private fun varint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while ((v and 0x7FL.inv()) != 0L) {
            out.write(((v and 0x7FL) or 0x80L).toInt())
            v = v ushr 7
        }
        out.write((v and 0x7FL).toInt())
    }

    private fun tag(field: Int, wireType: Int): Long =
        ((field.toLong() shl 3) or wireType.toLong())

    private fun writeVarintField(out: ByteArrayOutputStream, field: Int, v: Long) {
        varint(out, tag(field, 0)); varint(out, v)
    }

    private fun writeLengthDelimited(out: ByteArrayOutputStream, field: Int, payload: ByteArray) {
        varint(out, tag(field, 2)); varint(out, payload.size.toLong()); out.write(payload)
    }

    private fun writeFloatField(out: ByteArrayOutputStream, field: Int, value: Float) {
        varint(out, tag(field, 5))
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array()
        out.write(buf)
    }

    private fun piece(text: String, score: Float, type: Int): ByteArray {
        val out = ByteArrayOutputStream()
        writeLengthDelimited(out, 1, text.toByteArray(Charsets.UTF_8))
        writeFloatField(out, 2, score)
        writeVarintField(out, 3, type.toLong())
        return out.toByteArray()
    }

    private fun trainerSpec(unk: Int, bos: Int, eos: Int, pad: Int, byteFallback: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarintField(out, 35, if (byteFallback) 1 else 0)
        writeVarintField(out, 40, unk.toLong())
        writeVarintField(out, 41, bos.toLong())
        writeVarintField(out, 42, eos.toLong())
        writeVarintField(out, 43, pad.toLong())
        return out.toByteArray()
    }

    private fun normalizerSpec(addDummyPrefix: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarintField(out, 3, if (addDummyPrefix) 1 else 0)
        return out.toByteArray()
    }

    private val TYPE_NORMAL = 1
    private val TYPE_UNKNOWN = 2
    private val TYPE_CONTROL = 3
    private val TYPE_BYTE = 6

    /**
     * Build a self-contained ModelProto byte array with:
     *  - ids 0..3 = `<unk>` / `<s>` / `</s>` / `<pad>` control pieces.
     *  - ids 4..259 = byte fallback tokens `<0x00>` .. `<0xFF>`.
     *  - id 260 = the `▁` metaspace piece.
     *  - id 261..264 = a handful of normal pieces.
     * BOS=1, EOS=2, byte_fallback=true, add_dummy_prefix=true.
     */
    private fun synthModel(): ByteArray {
        val out = ByteArrayOutputStream()
        // Order matters because the id we declare for `unk_id` etc. is
        // simply the position in this pieces list (see the parser).
        val pieces = mutableListOf<ByteArray>()
        pieces += piece("<unk>", -1e6f, TYPE_UNKNOWN)        // id 0
        pieces += piece("<s>", 0f, TYPE_CONTROL)             // id 1 (BOS)
        pieces += piece("</s>", 0f, TYPE_CONTROL)            // id 2 (EOS)
        pieces += piece("<pad>", 0f, TYPE_CONTROL)           // id 3
        // 256 byte-fallback pieces 4..259, name "<0xHH>".
        for (b in 0..255) {
            pieces += piece("<0x%02X>".format(b), -10f, TYPE_BYTE)
        }
        // Normal pieces.
        pieces += piece("\u2581", -2f, TYPE_NORMAL)          // id 260: metaspace alone
        pieces += piece("\u2581a", -0.5f, TYPE_NORMAL)       // id 261
        pieces += piece("a", -1f, TYPE_NORMAL)               // id 262
        pieces += piece("\u2581hello", -0.3f, TYPE_NORMAL)   // id 263
        pieces += piece("\u2581world", -0.4f, TYPE_NORMAL)   // id 264

        for (p in pieces) writeLengthDelimited(out, 1, p)
        writeLengthDelimited(
            out, 2,
            trainerSpec(unk = 0, bos = 1, eos = 2, pad = 3, byteFallback = true),
        )
        writeLengthDelimited(out, 3, normalizerSpec(addDummyPrefix = true))
        return out.toByteArray()
    }

    // ── Tests ───────────────────────────────────────────────────────────

    @Test fun `parse succeeds on synthetic protobuf and tokenizes ASCII via vocab pieces`() {
        val tk = SentencePieceUnigramTokenizer.parse(synthModel())
        val ids = tk.tokenize("hello", maxTokens = 16)
        // BOS at position 0; then vocab piece "▁hello" (id 263).
        assertEquals(1, ids[0].toInt())  // BOS
        assertTrue(
            "expected ▁hello (id 263) in output, got ${ids.toList()}",
            ids.toList().contains(263),
        )
    }

    @Test fun `empty string returns BOS plus dummy-prefix metaspace token`() {
        val tk = SentencePieceUnigramTokenizer.parse(synthModel())
        val ids = tk.tokenize("", maxTokens = 16)
        // addDummyPrefix=true means the normaliser always prepends "▁",
        // so empty input still produces the metaspace piece after BOS.
        assertEquals("empty string should produce [BOS, ▁]", listOf(1, 260), ids.toList())
    }

    @Test fun `maxTokens=1 truncates from the end and keeps BOS`() {
        val tk = SentencePieceUnigramTokenizer.parse(synthModel())
        val ids = tk.tokenize("hello world", maxTokens = 1)
        assertEquals("must fit within cap", 1, ids.size)
        assertEquals("BOS must survive truncation", 1, ids[0])
    }

    @Test fun `maxTokens=0 returns empty array`() {
        val tk = SentencePieceUnigramTokenizer.parse(synthModel())
        val ids = tk.tokenize("hello", maxTokens = 0)
        assertEquals(0, ids.size)
    }

    @Test fun `BOM prefix routes through byte fallback without crashing`() {
        val tk = SentencePieceUnigramTokenizer.parse(synthModel())
        // U+FEFF (BOM) → UTF-8 bytes EF BB BF. None of these match a
        // vocab piece, so they take the byte-fallback path.
        val ids = tk.tokenize("\uFEFFa", maxTokens = 64)
        assertTrue("output must include BOS at start, got ${ids.toList()}", ids[0] == 1)
        // Byte fallback ids live in 4..259 — at least one of those must
        // appear because the BOM bytes are unreachable via vocab pieces.
        assertTrue(
            "expected at least one byte-fallback id in 4..259, got ${ids.toList()}",
            ids.drop(1).any { it in 4..259 },
        )
    }

    @Test fun `emoji surrogate pair routes through byte fallback`() {
        val tk = SentencePieceUnigramTokenizer.parse(synthModel())
        // 😀 = U+1F600 → UTF-8 bytes F0 9F 98 80. All 4 bytes must map
        // to byte-fallback tokens (ids 4 + 0xF0, 4 + 0x9F, etc.).
        val ids = tk.tokenize("\uD83D\uDE00", maxTokens = 32)
        assertTrue("BOS at index 0", ids[0] == 1)
        val expectedBytes = byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x98.toByte(), 0x80.toByte())
        for (b in expectedBytes) {
            val expectedId = 4 + (b.toInt() and 0xFF)
            assertTrue(
                "byte 0x%02X (id $expectedId) must appear in output ${ids.toList()}".format(b.toInt() and 0xFF),
                ids.toList().contains(expectedId),
            )
        }
    }

    @Test fun `combining and zero-width chars do not crash the tokenizer`() {
        val tk = SentencePieceUnigramTokenizer.parse(synthModel())
        // U+0301 (combining acute), U+200B (zero-width space).
        // Both lack a dedicated vocab piece in our synth model; they
        // route through the multi-byte fallback path. Contract under
        // test: tokenize() returns non-empty without throwing.
        val ids = tk.tokenize("a\u0301\u200Bb", maxTokens = 64)
        assertTrue("expected at least BOS + some output, got ${ids.toList()}", ids.size >= 2)
        assertEquals(1, ids[0])
    }

    @Test fun `oversized input falling through to byte fallback truncates to maxTokens`() {
        val tk = SentencePieceUnigramTokenizer.parse(synthModel())
        // Lots of non-vocab bytes — each becomes a 1-byte fallback token.
        // With maxTokens = 8 the tokenizer must truncate.
        val input = "\u0001\u0002\u0003\u0004\u0005\u0006\u0007\u0008\u0009\u000A\u000B"
        val ids = tk.tokenize(input, maxTokens = 8)
        assertTrue("must not exceed cap, got ${ids.size}", ids.size <= 8)
        assertEquals("BOS preserved", 1, ids[0])
    }

    @Test fun `tokenizer handles invalid UTF-16 (lone surrogate) without crashing`() {
        val tk = SentencePieceUnigramTokenizer.parse(synthModel())
        // Lone high surrogate. Kotlin's String.toByteArray(UTF-8) emits
        // the Unicode replacement char (EF BF BD) for unpaired surrogates,
        // which the byte-fallback table covers.
        val lone = "a\uD83Db"  // missing low surrogate after \uD83D
        val ids = tk.tokenize(lone, maxTokens = 32)
        assertTrue("expected non-empty output, got ${ids.toList()}", ids.size >= 2)
        assertEquals(1, ids[0])
    }

    @Test fun `tokenize is deterministic across repeated calls`() {
        val tk = SentencePieceUnigramTokenizer.parse(synthModel())
        val first = tk.tokenize("hello world", maxTokens = 64).toList()
        val second = tk.tokenize("hello world", maxTokens = 64).toList()
        assertEquals(first, second)
        // Spot-check: BOS is always first.
        assertEquals(1, first[0])
    }

    @Test fun `negative maxTokens is rejected by tokenize`() {
        val tk = SentencePieceUnigramTokenizer.parse(synthModel())
        try {
            tk.tokenize("hi", maxTokens = -1)
            error("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test fun `tokenize does not leak any zero-byte fallback that should be unreachable`() {
        // Sanity: with addDummyPrefix=true and "a" input, the optimal
        // Viterbi path is BOS + "▁a" (id 261, score -0.5). The
        // tokenizer should NOT pick byte-fallback for the ASCII 'a'.
        val tk = SentencePieceUnigramTokenizer.parse(synthModel())
        val ids = tk.tokenize("a", maxTokens = 16)
        assertEquals(listOf(1, 261), ids.toList())
        // Negative sanity: byte-fallback for 'a' would be 4 + 0x61 = 101.
        assertFalse(ids.toList().contains(101))
    }
}
