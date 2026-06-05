package com.adsamcik.mindlayer.service.engine

import com.adsamcik.mindlayer.service.security.IpcInputValidator
import java.security.SecureRandom

/**
 * F-035: Scrubs Gemma turn-tokens and wraps tool output in an unforgeable
 * envelope before re-injecting it into the conversation.
 *
 * The model can otherwise be jail-broken by a tool aggregator that emits
 * `<start_of_turn>user\n…<end_of_turn>` inside a tool result — the chat
 * template flattens [com.google.ai.edge.litertlm.Content.ToolResponse]
 * into prompt text where these markers flip role.
 *
 * Each call to [wrap] produces a fresh per-request 16-hex-char nonce
 * (8 bytes of [java.security.SecureRandom]) that is included in BOTH the
 * opening and closing tags. Because the model has never seen this nonce
 * before (it is sampled at scrub time), it cannot predictively forge the
 * closing tag inside the payload.
 *
 * Threading: pure / stateless. Safe to call from any coroutine.
 */
object ToolOutputSanitizer {

    /**
     * Gemma 3/4 special tokens that flip role/turn boundaries in the chat
     * template. Sourced from the tokenizer's special-token table.
     *
     * Includes the rubber-duck-added `<start_of_image>` / `<end_of_image>`
     * (image scaffolding for multimodal Gemma 4) on top of the canonical
     * turn / sentinel set.
     */
    private val GEMMA_CONTROL_TOKENS = listOf(
        "<start_of_turn>",
        "<end_of_turn>",
        "<bos>",
        "<eos>",
        "<pad>",
        "<unk>",
        "<mask>",
        "<image_soft_token>",
        "<audio_soft_token>",
        "<start_of_image>",
        "<end_of_image>",
    )

    /**
     * SentencePiece byte-level escape: the lower-one-eighth-block character
     * `▁` (U+2581) is what Gemma's tokenizer prepends for word-leading
     * variants. Models can sometimes pre-emit this prefix; strip it too so
     * the payload cannot smuggle a pre-tokenised marker past us.
     */
    private const val SP_PREFIX = "\u2581"

    private val ALL_TOKENS_TO_STRIP: List<String> =
        GEMMA_CONTROL_TOKENS + GEMMA_CONTROL_TOKENS.map { SP_PREFIX + it }

    private const val TRUNCATION_MARKER = "…[truncated]"

    /**
     * Wrap a tool [rawResult] into an envelope that the model has been told
     * (via [SessionManager]'s tool-safety preamble) to treat as untrusted
     * data. Returns a string of the form:
     *
     * ```
     * <tool_output id="abcdef0123456789" name="weather">
     * {scrubbedResult}
     * </tool_output id="abcdef0123456789">
     * ```
     *
     * - Gemma turn / sentinel / image tokens are stripped from
     *   [rawResult] (and their `▁`-prefixed SentencePiece variants).
     * - C0 controls except `\n` and `\t` are dropped.
     * - The literal string `</tool_output` (envelope-close prefix) inside
     *   the payload is escaped to `<\/tool_output` so the payload cannot
     *   prematurely terminate its own envelope.
     * - The 16-hex-char nonce is sampled per-call from a
     *   [java.security.SecureRandom] — unguessable by the model in-context.
     * - The result is capped to [IpcInputValidator.MAX_TOOL_RESULT_LEN]
     *   (post-scrub, post-escape).
     *
     * @param toolName client-supplied name; sanitised to letters/digits/
     *                 `_-.` and clipped to [IpcInputValidator.MAX_TOOL_NAME_LEN].
     * @param rawResult the unverified tool result string from the SDK.
     */
    fun wrap(toolName: String, rawResult: String): String {
        val sanitisedName = sanitiseName(toolName)
        val scrubbed = scrub(rawResult)
        val nonce = newNonce()
        return buildString(scrubbed.length + 96) {
            append("<tool_output id=\"").append(nonce).append("\" name=\"")
                .append(sanitisedName).append("\">\n")
            append(scrubbed)
            append("\n</tool_output id=\"").append(nonce).append("\">")
        }
    }

    /**
     * Visible for testing — exposes the scrub pipeline without the
     * envelope wrapping so individual transformation steps can be
     * asserted directly.
     */
    internal fun scrub(input: String): String {
        // 1. Strip Gemma control tokens (and their SentencePiece-prefixed
        //    variants). Naïve replace loop — Kotlin's `replace` is O(n*m)
        //    but `m` (token count) is ≤ ~25 and `n` is ≤ 64 KiB.
        var s = input
        for (token in ALL_TOKENS_TO_STRIP) {
            if (s.contains(token)) {
                s = s.replace(token, "")
            }
        }

        // 2. Drop C0 controls except `\n` (0x0A) and `\t` (0x09). Keep
        //    the rest of printable Unicode untouched.
        if (s.any { it.code in 0x00..0x1F && it != '\n' && it != '\t' }) {
            s = buildString(s.length) {
                for (c in s) {
                    if (c.code in 0x00..0x1F && c != '\n' && c != '\t') continue
                    append(c)
                }
            }
        }

        // 3. Escape BOTH envelope delimiters so a hostile tool result can
        //    neither prematurely terminate the wrapper nor forge a nested
        //    opening tag (security-review S-12). Escape the closing form
        //    first, then the opening literal; the two substrings are
        //    distinct so order is not strictly required, but closing-first
        //    keeps the intent obvious.
        if (s.contains("</tool_output")) {
            s = s.replace("</tool_output", "<\\/tool_output")
        }
        if (s.contains("<tool_output")) {
            s = s.replace("<tool_output", "<\\tool_output")
        }

        // 4. Cap to MAX_TOOL_RESULT_LEN. Truncation can only happen if
        //    step 3 expanded the payload past the cap (the original was
        //    already validated at the AIDL boundary).
        val cap = IpcInputValidator.MAX_TOOL_RESULT_LEN
        if (s.length > cap) {
            val keep = (cap - TRUNCATION_MARKER.length).coerceAtLeast(0)
            s = s.substring(0, keep) + TRUNCATION_MARKER
        }
        return s
    }

    private fun sanitiseName(name: String): String =
        name.filter { it.isLetterOrDigit() || it in "_-." }
            .take(IpcInputValidator.MAX_TOOL_NAME_LEN)

    /**
     * 16 hex characters from 8 bytes of [SecureRandom] (security-review
     * S-12). A cryptographically-strong, 64-bit nonce that the model has
     * never seen in-context, so it cannot predictively forge either the
     * opening or closing envelope tag inside a payload.
     */
    private fun newNonce(): String {
        val bytes = ByteArray(8)
        SECURE_RANDOM.nextBytes(bytes)
        val sb = StringBuilder(16)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val SECURE_RANDOM = SecureRandom()

    private val HEX = "0123456789abcdef".toCharArray()
}
