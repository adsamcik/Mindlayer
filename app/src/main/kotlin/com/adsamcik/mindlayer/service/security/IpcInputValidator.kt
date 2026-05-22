package com.adsamcik.mindlayer.service.security

import com.adsamcik.mindlayer.AudioTransfer
import com.adsamcik.mindlayer.EmbeddingRequest
import com.adsamcik.mindlayer.EmbeddingTask
import com.adsamcik.mindlayer.HistoryTurn
import com.adsamcik.mindlayer.ImageTransfer
import com.adsamcik.mindlayer.OcrFrameMeta
import com.adsamcik.mindlayer.OcrLimits
import com.adsamcik.mindlayer.OcrSessionConfig
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
    const val MAX_EMBEDDING_REQUEST_ID_LEN = 160
    private val EMBEDDING_REQUEST_ID_PATTERN = Regex("^[A-Za-z0-9._-]{1,$MAX_EMBEDDING_REQUEST_ID_LEN}$")

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

    // ── Priority bounds ──────────────────────────────────────────────────
    // Legacy ServiceBinder.validateRequestMeta enforced -10..10; restored
    // here after the security-hardening pass migrated validation to
    // IpcInputValidator without porting the priority check (b15b656).
    const val MIN_PRIORITY = -10
    const val MAX_PRIORITY = 10

    // ── Image budgets ─────────────────────────────────────────────────────
    const val MAX_IMG_DIMENSION = 8192
    const val MAX_IMG_PIXELS = 64L * 1024L * 1024L // 64 megapixels
    const val MAX_OCR_RAW_Y_PLANE_PIXELS = 24_000_000L

    // ── MIME allowlists ───────────────────────────────────────────────────
    const val OCR_RAW_Y_PLANE_MIME: String = "image/raw-yuv;y-plane"
    val ALLOWED_IMAGE_MIME = setOf(
        "image/jpeg", "image/png", "image/webp", "image/bmp", OCR_RAW_Y_PLANE_MIME,
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

    fun validateEmbeddingRequestId(value: String) {
        require(value.isNotEmpty()) { "embedding requestId must not be empty" }
        require(value.length <= MAX_EMBEDDING_REQUEST_ID_LEN) {
            "embedding requestId too long (${value.length} > $MAX_EMBEDDING_REQUEST_ID_LEN)"
        }
        require(EMBEDDING_REQUEST_ID_PATTERN.matches(value) && !value.contains("..")) {
            "embedding requestId must match $EMBEDDING_REQUEST_ID_PATTERN and not contain '..' (got: <redacted:${value.length}>)"
        }
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
        // Priority is a small integer hint to the orchestrator's scheduler;
        // accept the same -10..10 window the legacy private validator used
        // (see ServiceBinder.kt git history pre-b15b656). Out-of-range values
        // signal a buggy or hostile caller and are rejected at ingress so
        // downstream code can treat priority as already-bounded.
        require(meta.priority in MIN_PRIORITY..MAX_PRIORITY) {
            "priority must be in $MIN_PRIORITY..$MAX_PRIORITY (got: ${meta.priority})"
        }
    }

    private val ALLOWED_ROLES = Role.ALL

    /**
     * v0.3 server-side toolsJson shape check. Beyond the size cap, parses
     * the JSON array and enforces:
     *
     * - top-level is an array
     * - each entry is a JSON object with a non-blank `name` (≤
     *   [MAX_TOOL_NAME_LEN])
     * - no `name` starts with [RESERVED_TOOL_PREFIX] (which the service
     *   reserves for the structured-output synthetic tool)
     * - each entry has a `parameters` object
     *
     * Mirrors the same checks the SDK's [com.adsamcik.mindlayer.sdk.ToolsBuilder]
     * runs at construction time — defense in depth so a hand-crafted JSON
     * blob from an SDK escape hatch still gets the same shape validation.
     * Throws [IllegalArgumentException] which `ServiceBinder.createSession`
     * translates to `INVALID_SESSION_CONFIG`.
     */
    private fun validateToolsJsonShape(json: String) {
        val parsed = try {
            kotlinx.serialization.json.Json.parseToJsonElement(json)
        } catch (t: Throwable) {
            throw IllegalArgumentException("toolsJson is not valid JSON")
        }
        require(parsed is kotlinx.serialization.json.JsonArray) {
            "toolsJson must be a JSON array"
        }
        require(parsed.size <= 64) {
            "too many tools (${parsed.size} > 64)"
        }
        val seenNames = mutableSetOf<String>()
        for ((i, tool) in parsed.withIndex()) {
            require(tool is kotlinx.serialization.json.JsonObject) {
                "tools[$i] must be a JSON object"
            }
            val nameElement =
                tool["name"] as? kotlinx.serialization.json.JsonPrimitive
            val name = nameElement?.content
            require(!name.isNullOrBlank()) { "tools[$i] missing 'name'" }
            require(name.length <= MAX_TOOL_NAME_LEN) {
                "tools[$i].name too long (${name.length} > $MAX_TOOL_NAME_LEN)"
            }
            require(!name.startsWith(RESERVED_TOOL_PREFIX)) {
                "tools[$i].name uses reserved prefix '$RESERVED_TOOL_PREFIX'"
            }
            require(seenNames.add(name)) {
                "tools[$i].name '$name' is duplicated"
            }
            // parameters is optional — service substitutes an empty
            // {"type":"object"} schema for missing/null. Validate the
            // type when present.
            tool["parameters"]?.let {
                require(it is kotlinx.serialization.json.JsonObject) {
                    "tools[$i].parameters must be a JSON object when present"
                }
            }
        }
    }

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
            validateToolsJsonShape(it)
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

    fun validateAudioTransfer(transfer: AudioTransfer, maxMediaBytes: Int = Int.MAX_VALUE) {
        validateId(transfer.requestId, "AudioTransfer.requestId")
        require(transfer.mimeType in ALLOWED_AUDIO_MIME) {
            "audio mimeType not supported: ${transfer.mimeType}"
        }
        if (transfer.payloadBytes != 0) {
            require(transfer.payloadBytes in 1..maxMediaBytes) {
                "payloadBytes out of bounds: ${transfer.payloadBytes}"
            }
        }
        transfer.durationMs?.let {
            require(it in 0..(60L * 60L * 1000L)) {
                "durationMs out of bounds: $it"
            }
        }
    }

    // ── MediaPart validation (v0.4 inferMulti) ────────────────────────────

    /** Maximum total media bytes summed across all parts in one request. */
    const val MAX_TOTAL_MEDIA_BYTES_PER_REQUEST: Long = 200L * 1024 * 1024

    /**
     * Validate an ordered [List] of [MediaPart] supplied via
     * `inferMulti`. Per-part rules mirror the legacy `validateImageTransfer`
     * / `validateAudioTransfer` semantics; aggregate caps protect against a
     * single request claiming more total bytes than [MAX_TOTAL_MEDIA_BYTES_PER_REQUEST].
     *
     * Engine-constraint bookkeeping (today: at most one image + one audio) is
     * enforced here too — multi-image rejects with a clear "not yet supported"
     * message until litert-lm #1874 lifts the restriction.
     */
    fun validateMediaParts(
        parts: List<com.adsamcik.mindlayer.MediaPart>,
        maxPerPartBytes: Int,
        maxParts: Int,
    ) {
        require(parts.size <= maxParts) {
            "too many media parts: ${parts.size} > $maxParts"
        }
        var totalBytes = 0L
        var imageCount = 0
        var audioCount = 0
        for ((index, part) in parts.withIndex()) {
            require(part.schemaVersion == com.adsamcik.mindlayer.MediaPart.CURRENT_SCHEMA_VERSION) {
                "media[$index].schemaVersion ${part.schemaVersion} unsupported " +
                    "(expected ${com.adsamcik.mindlayer.MediaPart.CURRENT_SCHEMA_VERSION})"
            }
            require(part.kind in com.adsamcik.mindlayer.MediaPart.ALL_KINDS) {
                "media[$index].kind unknown: ${part.kind}"
            }
            require(part.payloadBytes in 1L..maxPerPartBytes.toLong()) {
                "media[$index].payloadBytes out of bounds: ${part.payloadBytes}"
            }
            try {
                totalBytes = Math.addExact(totalBytes, part.payloadBytes)
            } catch (_: ArithmeticException) {
                throw IllegalArgumentException("aggregate payloadBytes overflow")
            }

            when (part.kind) {
                com.adsamcik.mindlayer.MediaPart.KIND_IMAGE -> {
                    imageCount++
                    validateImagePart(part, index, maxPerPartBytes)
                }
                com.adsamcik.mindlayer.MediaPart.KIND_AUDIO -> {
                    audioCount++
                    validateAudioPart(part, index)
                }
                com.adsamcik.mindlayer.MediaPart.KIND_VIDEO,
                com.adsamcik.mindlayer.MediaPart.KIND_DOCUMENT -> {
                    // Wire-reserved but engine doesn't serve them yet.
                    throw IllegalArgumentException(
                        "media[$index].kind ${part.kind} reserved for future use; " +
                            "this service version does not serve it"
                    )
                }
            }
        }
        require(totalBytes <= MAX_TOTAL_MEDIA_BYTES_PER_REQUEST) {
            "aggregate media bytes ($totalBytes) > $MAX_TOTAL_MEDIA_BYTES_PER_REQUEST"
        }
        // Today's engine constraint — see MediaPart KDoc.
        require(imageCount <= 1) {
            "multi-image not yet supported (got $imageCount images)"
        }
        require(audioCount <= 1) {
            "multi-audio not yet supported (got $audioCount audio parts)"
        }
    }


    /** Validate an OCR/session [MediaPart] carrying exactly one image frame. */
    fun validateImageTransfer(part: com.adsamcik.mindlayer.MediaPart, maxMediaBytes: Int) {
        require(part.schemaVersion == com.adsamcik.mindlayer.MediaPart.CURRENT_SCHEMA_VERSION) {
            "MediaPart.schemaVersion ${part.schemaVersion} unsupported " +
                "(expected ${com.adsamcik.mindlayer.MediaPart.CURRENT_SCHEMA_VERSION})"
        }
        validateId(part.requestId, "MediaPart.requestId")
        require(part.kind == com.adsamcik.mindlayer.MediaPart.KIND_IMAGE) {
            "MediaPart.kind ${part.kind} is not KIND_IMAGE"
        }
        require(part.payloadBytes in 1L..maxMediaBytes.toLong()) {
            "MediaPart.payloadBytes out of bounds: ${part.payloadBytes}"
        }
        validateImagePart(part, index = 0, maxMediaBytes = maxMediaBytes)
    }

    private fun validateImagePart(
        part: com.adsamcik.mindlayer.MediaPart,
        index: Int,
        maxMediaBytes: Int,
    ) {
        require(part.width in 0..MAX_IMG_DIMENSION) {
            "media[$index].width out of bounds: ${part.width}"
        }
        require(part.height in 0..MAX_IMG_DIMENSION) {
            "media[$index].height out of bounds: ${part.height}"
        }
        val isRawYPlane = part.mimeType == OCR_RAW_Y_PLANE_MIME
        if (isRawYPlane) {
            require(part.width > 0 && part.height > 0) {
                "media[$index] raw Y-plane requires positive width and height"
            }
            val pixels: Long = try {
                Math.multiplyExact(part.width.toLong(), part.height.toLong())
            } catch (_: ArithmeticException) {
                throw IllegalArgumentException("media[$index] width x height overflow")
            }
            require(pixels <= MAX_IMG_PIXELS) {
                "media[$index].pixel count out of bounds: $pixels"
            }
            require(pixels <= MAX_OCR_RAW_Y_PLANE_PIXELS) {
                "media[$index].raw Y-plane pixel count out of bounds: $pixels"
            }
            require(part.rowStride >= part.width) {
                "media[$index].rowStride (${part.rowStride}) < width (${part.width})"
            }
            val minimumBytes = if (part.height == 1) {
                part.width.toLong()
            } else {
                Math.addExact(Math.multiplyExact(part.rowStride.toLong(), (part.height - 1).toLong()), part.width.toLong())
            }
            require(part.payloadBytes >= minimumBytes) {
                "media[$index].payloadBytes (${part.payloadBytes}) smaller than raw Y-plane layout ($minimumBytes)"
            }
            return
        }
        val isRawPixels = part.isSharedMemory && part.mimeType == null
        if (isRawPixels) {
            require(part.width > 0 && part.height > 0) {
                "media[$index] raw pixels require positive width and height"
            }
            val pixels: Long = try {
                Math.multiplyExact(part.width.toLong(), part.height.toLong())
            } catch (_: ArithmeticException) {
                throw IllegalArgumentException("media[$index] width × height overflow")
            }
            require(pixels <= MAX_IMG_PIXELS) {
                "media[$index].pixel count out of bounds: $pixels"
            }
            require(part.pixelFormat in ALLOWED_PIXEL_FORMATS) {
                "media[$index].pixelFormat not supported: ${part.pixelFormat}"
            }
            val bpp = bytesPerPixel(part.pixelFormat)
            val expected: Long = try {
                Math.multiplyExact(pixels, bpp.toLong())
            } catch (_: ArithmeticException) {
                throw IllegalArgumentException("media[$index] payload size overflow")
            }
            require(expected == part.payloadBytes) {
                "media[$index].payloadBytes (${part.payloadBytes}) does not match " +
                    "width × height × bpp ($expected) for raw pixels"
            }
            require(part.rowStride >= part.width * bpp) {
                "media[$index].rowStride (${part.rowStride}) < width × bpp"
            }
        } else {
            val mime = requireNotNull(part.mimeType) {
                "media[$index] encoded image requires mimeType"
            }
            require(mime in ALLOWED_IMAGE_MIME) {
                "media[$index].image mimeType not supported: $mime"
            }
        }
    }

    private fun validateAudioPart(
        part: com.adsamcik.mindlayer.MediaPart,
        index: Int,
    ) {
        require(part.mimeType in ALLOWED_AUDIO_MIME) {
            "media[$index].audio mimeType not supported: ${part.mimeType}"
        }
        part.durationMs?.let {
            require(it in 0..(60L * 60L * 1000L)) {
                "media[$index].durationMs out of bounds: $it"
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

    // ─────────────────────────────────────────────────────────────────────
    //  v0.8 multi-frame OCR validators
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Maximum length (chars) of a caller-supplied JSON schema or options
     * envelope on the OCR API. Matches [OcrLimits.ocrSchemaJsonMaxLen]
     * default. Real ceiling is fetched per-service via `getOcrLimits()`;
     * this is the hard ingress cap (defense-in-depth — even if the live
     * limit is higher, we never accept payloads above this).
     */
    const val MAX_OCR_SCHEMA_JSON_LEN = 16 * 1024

    /** BCP-47 language hint cap: e.g. `"en"`, `"de-DE"`, `"zh-Hans-CN"`. */
    const val MAX_OCR_LANG_HINT_LEN = 32
    /** Max BCP-47 hints per session. Far above any realistic need. */
    const val MAX_OCR_LANG_HINT_COUNT = 16
    /** Upper bound on caller-requested fps; lower bound is service default. */
    const val MAX_OCR_FRAME_RATE_LIMIT_FPS = 60
    /** Upper bound on caller-requested per-session frame cap. */
    const val MAX_OCR_MAX_FRAMES = 1024

    /** BCP-47-shaped tag (subtag-of-subtags). Cheap regex; not full RFC. */
    private val BCP47_PATTERN =
        Regex("^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$")

    /**
     * Validate a caller-supplied [OcrSessionConfig] before the engine sees
     * it. Mirrors the pattern of [validateSessionConfig].
     *
     * The numeric caps fall back to compile-time ceilings so that the
     * validator is usable from unit tests without a running service /
     * `OcrLimits` parcelable in hand.
     */
    fun validateOcrSessionConfig(cfg: OcrSessionConfig) {
        require(cfg.schemaVersion == OcrSessionConfig.CURRENT_SCHEMA_VERSION) {
            "OcrSessionConfig.schemaVersion=${cfg.schemaVersion} unsupported " +
                "(expected ${OcrSessionConfig.CURRENT_SCHEMA_VERSION})"
        }
        require(cfg.mode in OcrSessionConfig.ALL_MODES) {
            "OcrSessionConfig.mode=${cfg.mode} not in ${OcrSessionConfig.ALL_MODES}"
        }
        require(cfg.outputSchemaJson.isNotEmpty()) {
            "OcrSessionConfig.outputSchemaJson must not be empty"
        }
        require(cfg.outputSchemaJson.length <= MAX_OCR_SCHEMA_JSON_LEN) {
            "OcrSessionConfig.outputSchemaJson too long " +
                "(${cfg.outputSchemaJson.length} > $MAX_OCR_SCHEMA_JSON_LEN)"
        }
        cfg.optionsJson?.let { opts ->
            require(opts.length <= MAX_OCR_SCHEMA_JSON_LEN) {
                "OcrSessionConfig.optionsJson too long " +
                    "(${opts.length} > $MAX_OCR_SCHEMA_JSON_LEN)"
            }
        }
        require(cfg.languageHints.size <= MAX_OCR_LANG_HINT_COUNT) {
            "OcrSessionConfig.languageHints has too many entries " +
                "(${cfg.languageHints.size} > $MAX_OCR_LANG_HINT_COUNT)"
        }
        for (hint in cfg.languageHints) {
            require(hint.length in 1..MAX_OCR_LANG_HINT_LEN) {
                "OcrSessionConfig.languageHints entry length out of range " +
                    "(got ${hint.length}, allowed 1..$MAX_OCR_LANG_HINT_LEN)"
            }
            require(BCP47_PATTERN.matches(hint)) {
                "OcrSessionConfig.languageHints entry is not BCP-47 shaped " +
                    "(<redacted:${hint.length}>)"
            }
        }
        require(cfg.maxFrames in 0..MAX_OCR_MAX_FRAMES) {
            "OcrSessionConfig.maxFrames=${cfg.maxFrames} out of range " +
                "(0..$MAX_OCR_MAX_FRAMES)"
        }
        require(cfg.frameRateLimitFps in 0..MAX_OCR_FRAME_RATE_LIMIT_FPS) {
            "OcrSessionConfig.frameRateLimitFps=${cfg.frameRateLimitFps} " +
                "out of range (0..$MAX_OCR_FRAME_RATE_LIMIT_FPS)"
        }
        // featureFlags is a reserved bitfield; we accept any value and ignore
        // unknown bits, matching the AIDL_STABILITY guidance.
    }

    /**
     * Validate per-frame metadata on `pushOcrFrame`. The caller-side
     * monotonicity of [OcrFrameMeta.frameId] is enforced by the
     * session manager (which holds the per-session last-seen id); here we
     * only validate the parcelable's intrinsic well-formedness.
     */
    fun validateOcrFrameMeta(meta: OcrFrameMeta) {
        require(meta.schemaVersion == OcrFrameMeta.CURRENT_SCHEMA_VERSION) {
            "OcrFrameMeta.schemaVersion=${meta.schemaVersion} unsupported " +
                "(expected ${OcrFrameMeta.CURRENT_SCHEMA_VERSION})"
        }
        require(meta.frameId >= 0L) {
            "OcrFrameMeta.frameId must be non-negative (got ${meta.frameId})"
        }
        require(meta.captureTimeMs >= 0L) {
            "OcrFrameMeta.captureTimeMs must be non-negative (got ${meta.captureTimeMs})"
        }
        require(meta.rotationDegrees in OcrFrameMeta.ALLOWED_ROTATIONS) {
            "OcrFrameMeta.rotationDegrees=${meta.rotationDegrees} not in " +
                "${OcrFrameMeta.ALLOWED_ROTATIONS}"
        }
        require(meta.qualityHint in OcrFrameMeta.ALL_QUALITY_HINTS) {
            "OcrFrameMeta.qualityHint=${meta.qualityHint} not in " +
                "${OcrFrameMeta.ALL_QUALITY_HINTS}"
        }
        meta.regionJson?.let { r ->
            require(r.length <= MAX_OCR_SCHEMA_JSON_LEN) {
                "OcrFrameMeta.regionJson too long (${r.length} > $MAX_OCR_SCHEMA_JSON_LEN)"
            }
        }
        meta.extraJson?.let { e ->
            require(e.length <= MAX_OCR_SCHEMA_JSON_LEN) {
                "OcrFrameMeta.extraJson too long (${e.length} > $MAX_OCR_SCHEMA_JSON_LEN)"
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Embedding validators
    // ─────────────────────────────────────────────────────────────────────

    /** Hard per-request and per-batch byte cap for embedding text inputs. */
    const val MAX_EMBEDDING_INPUT_BYTES: Long = 512L * 1024L
    /** Hard cap on the opaque [EmbeddingRequest.tag] echoed back in results. */
    const val MAX_EMBEDDING_TAG_LEN: Int = 256
    /** Hard cap on the optional [EmbeddingRequest.modelId] shape. */
    const val MAX_EMBEDDING_MODEL_ID_LEN: Int = 128
    /** Hard upper bound on [EmbeddingRequest.outputDim] (defense in depth). */
    const val MAX_EMBEDDING_OUTPUT_DIM: Int = 8192

    /**
     * Validate an [EmbeddingRequest] before it reaches the engine. Mirrors
     * the OCR / MediaPart validators: schema version pin, free-form text
     * byte cap, opaque-tag length cap, modelId length cap, task-type range,
     * and output-dimension non-negativity.
     *
     * Per-model dimension checks (e.g. "must be ∈ supportedDims") are NOT
     * done here — they require the engine's model catalog and live in
     * `EmbeddingCoordinator.validateSingle`. This validator is the
     * schema-shape gate at the AIDL boundary; the coordinator is the
     * semantic gate just above the engine.
     *
     * @throws IllegalArgumentException with a precise label; the
     *   `ServiceBinder` layer translates these to typed wire errors.
     */
    fun validateEmbeddingRequest(req: EmbeddingRequest) {
        require(req.schemaVersion in 1..EmbeddingRequest.CURRENT_SCHEMA_VERSION) {
            "EmbeddingRequest.schemaVersion=${req.schemaVersion} unsupported " +
                "(expected 1..${EmbeddingRequest.CURRENT_SCHEMA_VERSION})"
        }
        val textBytes = req.text.toByteArray(Charsets.UTF_8).size
        require(textBytes <= MAX_EMBEDDING_INPUT_BYTES) {
            "EmbeddingRequest.text too long ($textBytes > $MAX_EMBEDDING_INPUT_BYTES bytes)"
        }
        req.tag?.let {
            require(it.length <= MAX_EMBEDDING_TAG_LEN) {
                "EmbeddingRequest.tag too long (${it.length} > $MAX_EMBEDDING_TAG_LEN)"
            }
        }
        req.modelId?.let {
            require(it.isNotEmpty()) {
                "EmbeddingRequest.modelId must not be empty when supplied"
            }
            require(it.length <= MAX_EMBEDDING_MODEL_ID_LEN) {
                "EmbeddingRequest.modelId too long (${it.length} > $MAX_EMBEDDING_MODEL_ID_LEN)"
            }
        }
        require(EmbeddingTask.isValid(req.taskType)) {
            "EmbeddingRequest.taskType=${req.taskType} not in 0..7"
        }
        req.outputDim?.let {
            require(it in 1..MAX_EMBEDDING_OUTPUT_DIM) {
                "EmbeddingRequest.outputDim=$it out of range (1..$MAX_EMBEDDING_OUTPUT_DIM)"
            }
        }
    }

    /**
     * Validate a batch of [EmbeddingRequest]s. Enforces the batch-size cap
     * passed by the binder (different limits for inline vs SHM vs deferred
     * routes) plus per-item validation via [validateEmbeddingRequest], plus
     * an aggregate-bytes cap so a batch of N maximum-length items doesn't
     * slip past the per-item check.
     */
    fun validateEmbeddingRequests(
        requests: List<EmbeddingRequest>,
        maxBatchSize: Int,
    ) {
        require(requests.isNotEmpty()) { "embedding batch must not be empty" }
        require(requests.size <= maxBatchSize) {
            "embedding batch too large (${requests.size} > $maxBatchSize)"
        }
        var totalBytes = 0L
        for (req in requests) {
            validateEmbeddingRequest(req)
            try {
                totalBytes = Math.addExact(totalBytes, req.text.toByteArray(Charsets.UTF_8).size.toLong())
            } catch (_: ArithmeticException) {
                throw IllegalArgumentException("aggregate embedding text bytes overflow")
            }
        }
        require(totalBytes <= MAX_EMBEDDING_INPUT_BYTES) {
            "aggregate embedding text bytes ($totalBytes) > $MAX_EMBEDDING_INPUT_BYTES"
        }
    }
}
