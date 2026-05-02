package com.adsamcik.mindlayer.service.security

import com.adsamcik.mindlayer.AudioTransfer
import com.adsamcik.mindlayer.HistoryTurn
import com.adsamcik.mindlayer.ImageTransfer
import com.adsamcik.mindlayer.RequestMeta
import com.adsamcik.mindlayer.SessionConfig
import com.adsamcik.mindlayer.ToolResult
import com.adsamcik.mindlayer.shared.Role

/**
 * Centralised AIDL ingress validation.
 *
 * Every public AIDL method on `ServiceBinder` calls one of the `validate…`
 * helpers as its first action (after `authorizeCall`) so that:
 *
 *  1. **Identifiers** (`requestId`, `sessionId`) cannot embed path-traversal
 *     or log-injection characters, and have a hard length cap. This blocks
 *     the requestId-as-staging-filename traversal surface and the bare-id
 *     plumbing chain that would otherwise let an attacker reach internal
 *     maps with crafted control characters.
 *
 *  2. **Free-form strings** (`systemPrompt`, `toolsJson`, `extraContextJson`,
 *     `RequestMeta.textContent`, `ToolResult.resultJson`, history turns)
 *     have explicit byte budgets. The Binder transaction limit (1 MiB) is
 *     not a security boundary — multiple sessions × multiple rounds adds
 *     up to native-OOM territory before any single transaction trips it.
 *
 *  3. **Multimodal payloads** (`ImageTransfer`, `AudioTransfer`) cannot
 *     declare dimensions or sizes that imply a multi-GB allocation, and
 *     the declared `payloadBytes` is consistent with `width × height ×
 *     bytesPerPixel` for raw pixels.
 *
 * Failures throw [IllegalArgumentException]; callers translate to
 * [SecurityException] at the AIDL boundary so the wire-side error class
 * stays uniform.
 *
 * All limits are deliberately conservative — they are far above any
 * legitimate first-party use case but well below "kills the service".
 */
object IpcInputValidator {

    // ── Identifier budgets ────────────────────────────────────────────────
    const val MAX_ID_LEN = 64
    private val ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,$MAX_ID_LEN}$")

    // ── String budgets (UTF-16 code units, i.e. .length) ──────────────────
    const val MAX_SYSTEM_PROMPT_LEN = 32 * 1024
    const val MAX_TOOLS_JSON_LEN = 256 * 1024
    const val MAX_EXTRA_CONTEXT_JSON_LEN = 64 * 1024
    const val MAX_TEXT_CONTENT_LEN = 256 * 1024
    const val MAX_TOOL_RESULT_LEN = 64 * 1024
    const val MAX_TOOL_NAME_LEN = 128
    const val MAX_HISTORY_TURNS = 64
    const val MAX_HISTORY_TURN_LEN = 16 * 1024
    const val MAX_BACKEND_NAME_LEN = 16
    const val MAX_SESSION_EXPIRATION_MS = 90L * 24L * 60L * 60L * 1000L

    // ── Image budgets ─────────────────────────────────────────────────────
    const val MAX_IMG_DIMENSION = 8192
    const val MAX_IMG_PIXELS = 64L * 1024L * 1024L // 64 megapixels

    // ── MIME allowlists ───────────────────────────────────────────────────
    val ALLOWED_IMAGE_MIME = setOf(
        "image/jpeg", "image/png", "image/webp", "image/bmp",
    )
    val ALLOWED_AUDIO_MIME = setOf(
        "audio/wav", "audio/x-wav",
        "audio/mp3", "audio/mpeg",
        "audio/ogg",
        "audio/flac",
        "audio/aac",
        "audio/mp4",
    )

    // ── Pixel-format allowlist ────────────────────────────────────────────
    /**
     * Subset of [android.graphics.PixelFormat] constants that we accept as
     * raw pixel inputs. Listed as raw ints so this object stays unit-test-
     * able on a plain JVM (no Android framework dependency).
     *  - RGBA_8888 = 1, RGB_565 = 4
     */
    val ALLOWED_PIXEL_FORMATS = setOf(1, 4)

    // ── Backend allowlist (also used by prewarm) ──────────────────────────
    val ALLOWED_BACKENDS = setOf("GPU", "CPU", "NPU")

    // ── Reserved tool-name prefixes ───────────────────────────────────────
    /** Client-supplied tool names beginning with this prefix are rejected. */
    const val RESERVED_TOOL_PREFIX = "__"

    // ─────────────────────────────────────────────────────────────────────
    //  Validators
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Validate an opaque identifier supplied by a client. Used for
     * [RequestMeta.requestId], [RequestMeta.sessionId], `ToolResult.requestId`,
     * etc. Rejects any character class that could break logs, JSON, or
     * file-paths downstream.
     */
    fun validateId(value: String, label: String) {
        require(value.isNotEmpty()) { "$label must not be empty" }
        require(value.length <= MAX_ID_LEN) {
            "$label too long (${value.length} > $MAX_ID_LEN)"
        }
        require(ID_PATTERN.matches(value)) {
            "$label must match $ID_PATTERN (got: <redacted:${value.length}>)"
        }
    }

    /** Validate an optional id; null is allowed. */
    fun validateOptionalId(value: String?, label: String) {
        if (value == null) return
        validateId(value, label)
    }

    fun validateRequestMeta(meta: RequestMeta) {
        validateId(meta.requestId, "requestId")
        validateId(meta.sessionId, "sessionId")
        meta.textContent?.let {
            require(it.length <= MAX_TEXT_CONTENT_LEN) {
                "textContent too long (${it.length} > $MAX_TEXT_CONTENT_LEN)"
            }
        }
        require(meta.role.length <= 16) { "role too long" }
        require(Role.isValid(meta.role)) { "role must be one of ${Role.ALL}" }
    }

    private val ALLOWED_ROLES = Role.ALL

    fun validateSessionConfig(config: SessionConfig) {
        validateOptionalId(config.sessionId, "sessionId")
        config.systemPrompt?.let {
            require(it.length <= MAX_SYSTEM_PROMPT_LEN) {
                "systemPrompt too long (${it.length} > $MAX_SYSTEM_PROMPT_LEN)"
            }
        }
        config.toolsJson?.let {
            require(it.length <= MAX_TOOLS_JSON_LEN) {
                "toolsJson too long (${it.length} > $MAX_TOOLS_JSON_LEN)"
            }
        }
        config.extraContextJson?.let {
            require(it.length <= MAX_EXTRA_CONTEXT_JSON_LEN) {
                "extraContextJson too long (${it.length} > $MAX_EXTRA_CONTEXT_JSON_LEN)"
            }
        }
        require(config.backend.length <= MAX_BACKEND_NAME_LEN) {
            "backend name too long"
        }
        require(config.backend in ALLOWED_BACKENDS) {
            "backend must be one of $ALLOWED_BACKENDS"
        }
        config.initialHistory?.let { history ->
            require(history.size <= MAX_HISTORY_TURNS) {
                "initialHistory has too many turns (${history.size} > $MAX_HISTORY_TURNS)"
            }
            for ((i, turn) in history.withIndex()) {
                validateHistoryTurn(turn, i)
            }
        }
        require(config.samplerTopK > 0) { "samplerTopK must be > 0" }
        require(config.samplerTopP in 0.0f..1.0f) {
            "samplerTopP must be in [0.0, 1.0]"
        }
        require(config.samplerTemperature in 0.0f..5.0f) {
            "samplerTemperature out of range"
        }
        require(config.maxTokens in 1..32_768) { "maxTokens out of range" }
        require(config.expirationMs in 1..MAX_SESSION_EXPIRATION_MS) {
            "expirationMs out of range"
        }
    }

    private fun validateHistoryTurn(turn: HistoryTurn, index: Int) {
        require(Role.isValid(turn.role)) {
            "initialHistory[$index].role must be one of ${Role.ALL}"
        }
        require(turn.text.length <= MAX_HISTORY_TURN_LEN) {
            "initialHistory[$index].text too long " +
                "(${turn.text.length} > $MAX_HISTORY_TURN_LEN)"
        }
    }

    fun validateToolResult(result: ToolResult) {
        validateId(result.requestId, "ToolResult.requestId")
        validateId(result.callId, "ToolResult.callId")
        require(result.toolName.isNotEmpty()) { "toolName must not be empty" }
        require(result.toolName.length <= MAX_TOOL_NAME_LEN) {
            "toolName too long"
        }
        require(result.resultJson.length <= MAX_TOOL_RESULT_LEN) {
            "resultJson too long (${result.resultJson.length} > $MAX_TOOL_RESULT_LEN)"
        }
    }

    /**
     * Validate an [ImageTransfer] declared by the client. Specifically:
     *  - `payloadBytes ∈ (0, MAX_MEDIA_BYTES]`
     *  - For raw pixels (SharedMemory + no mimeType):
     *    - dimensions are positive and ≤ [MAX_IMG_DIMENSION]
     *    - pixel count ≤ [MAX_IMG_PIXELS]
     *    - `width × height × bytesPerPixel == payloadBytes`
     *    - `pixelFormat` is on the allowlist
     *    - `rowStride ≥ width × bytesPerPixel`
     *  - For encoded images:
     *    - dimensions are advisory only; we re-probe via the file
     *      header at staging time. We DO check that any caller-supplied
     *      width/height stay below the dim cap so we never honour
     *      blatantly wrong values.
     *    - `mimeType` is on the allowlist
     */
    fun validateImageTransfer(transfer: ImageTransfer, maxMediaBytes: Int) {
        validateId(transfer.requestId, "ImageTransfer.requestId")
        require(transfer.payloadBytes in 1..maxMediaBytes) {
            "payloadBytes out of bounds: ${transfer.payloadBytes}"
        }
        require(transfer.width in 0..MAX_IMG_DIMENSION) {
            "width out of bounds: ${transfer.width}"
        }
        require(transfer.height in 0..MAX_IMG_DIMENSION) {
            "height out of bounds: ${transfer.height}"
        }
        val isRawPixels = transfer.isSharedMemory && transfer.mimeType == null
        if (isRawPixels) {
            require(transfer.width > 0 && transfer.height > 0) {
                "raw pixels require positive width and height"
            }
            val pixels: Long = try {
                Math.multiplyExact(transfer.width.toLong(), transfer.height.toLong())
            } catch (_: ArithmeticException) {
                throw IllegalArgumentException("width × height overflow")
            }
            require(pixels <= MAX_IMG_PIXELS) {
                "pixel count out of bounds: $pixels"
            }
            require(transfer.pixelFormat in ALLOWED_PIXEL_FORMATS) {
                "pixelFormat not supported: ${transfer.pixelFormat}"
            }
            val bpp = bytesPerPixel(transfer.pixelFormat)
            val expected: Long = try {
                Math.multiplyExact(pixels, bpp.toLong())
            } catch (_: ArithmeticException) {
                throw IllegalArgumentException("payload size overflow")
            }
            require(expected == transfer.payloadBytes.toLong()) {
                "payloadBytes (${transfer.payloadBytes}) does not match " +
                    "width × height × bpp ($expected) for raw pixels"
            }
            // rowStride must accommodate at least width * bpp bytes
            require(transfer.rowStride >= transfer.width * bpp) {
                "rowStride (${transfer.rowStride}) < width × bpp " +
                    "(${transfer.width * bpp})"
            }
        } else {
            val mime = requireNotNull(transfer.mimeType) {
                "encoded image requires mimeType"
            }
            require(mime in ALLOWED_IMAGE_MIME) {
                "image mimeType not supported: $mime"
            }
        }
    }

    private fun bytesPerPixel(pixelFormat: Int): Int = when (pixelFormat) {
        1 -> 4   // RGBA_8888
        4 -> 2   // RGB_565
        else -> throw IllegalArgumentException("unsupported pixelFormat: $pixelFormat")
    }

    fun validateAudioTransfer(transfer: AudioTransfer) {
        validateId(transfer.requestId, "AudioTransfer.requestId")
        require(transfer.mimeType in ALLOWED_AUDIO_MIME) {
            "audio mimeType not supported: ${transfer.mimeType}"
        }
        transfer.durationMs?.let {
            require(it in 0..(60L * 60L * 1000L)) {
                "durationMs out of bounds: $it"
            }
        }
    }

    /**
     * Validate the `backend` argument to `prewarm`. Returns the validated
     * value (or `"GPU"` when the input is null).
     */
    fun validateBackendName(backend: String?): String {
        val value = backend ?: "GPU"
        require(value.length <= MAX_BACKEND_NAME_LEN) { "backend name too long" }
        require(value in ALLOWED_BACKENDS) {
            "backend must be one of $ALLOWED_BACKENDS (got '$value')"
        }
        return value
    }
}
