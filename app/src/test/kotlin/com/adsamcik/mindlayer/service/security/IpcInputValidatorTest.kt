package com.adsamcik.mindlayer.service.security

import com.adsamcik.mindlayer.AudioTransfer
import com.adsamcik.mindlayer.HistoryTurn
import com.adsamcik.mindlayer.ImageTransfer
import com.adsamcik.mindlayer.RequestMeta
import com.adsamcik.mindlayer.SessionConfig
import com.adsamcik.mindlayer.ToolResult
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the AIDL ingress validator. Each test names the
 * SECURITY_REVIEW finding it pins so that future regressions surface a
 * recognisable failure.
 *
 * Note: ImageTransfer/AudioTransfer source PFDs are mocked here because
 * the validator never reads from them — it only inspects metadata.
 */
class IpcInputValidatorTest {

    private fun pfd(): android.os.ParcelFileDescriptor = mockk(relaxed = true)

    // ── F-004: requestId / sessionId regex ──────────────────────────────

    @Test
    fun `validateId rejects path traversal`() {
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateId("../etc/passwd", "x")
        }
    }

    @Test
    fun `validateId rejects forward slashes`() {
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateId("a/b/c", "x")
        }
    }

    @Test
    fun `validateId rejects backslashes`() {
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateId("a\\b\\c", "x")
        }
    }

    @Test
    fun `validateId rejects newlines and control chars (log injection)`() {
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateId("abc\nDEF", "x")
        }
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateId("abc\u0000DEF", "x")
        }
    }

    @Test
    fun `validateId rejects empty`() {
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateId("", "x")
        }
    }

    @Test
    fun `validateId rejects oversize`() {
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateId("a".repeat(65), "x")
        }
    }

    @Test
    fun `validateId accepts standard UUIDs and short ids`() {
        IpcInputValidator.validateId("abc-123_ID", "x")
        IpcInputValidator.validateId("550e8400-e29b-41d4-a716-446655440000", "x")
    }

    // ── F-001 / F-015: image dimension + payload consistency ────────────

    @Test
    fun `validateImageTransfer rejects oversize raw dimensions (image bomb)`() {
        val xfer = ImageTransfer(
            requestId = "abc",
            width = 50_000, height = 50_000,
            pixelFormat = 1, rowStride = 50_000 * 4,
            payloadBytes = 64,
            source = pfd(), isSharedMemory = true, mimeType = null,
        )
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateImageTransfer(xfer, 100 * 1024 * 1024)
        }
    }

    @Test
    fun `validateImageTransfer rejects payloadBytes mismatching w x h x bpp`() {
        // 1x1 RGBA_8888 should be 4 bytes; 16 declared → reject.
        val xfer = ImageTransfer(
            requestId = "abc",
            width = 1, height = 1,
            pixelFormat = 1, rowStride = 4,
            payloadBytes = 16,
            source = pfd(), isSharedMemory = true, mimeType = null,
        )
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateImageTransfer(xfer, 100 * 1024 * 1024)
        }
    }

    @Test
    fun `validateImageTransfer rejects unsupported pixelFormat`() {
        val xfer = ImageTransfer(
            requestId = "abc",
            width = 1, height = 1,
            pixelFormat = 999, rowStride = 4,
            payloadBytes = 4,
            source = pfd(), isSharedMemory = true, mimeType = null,
        )
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateImageTransfer(xfer, 100 * 1024 * 1024)
        }
    }

    @Test
    fun `validateImageTransfer rejects rowStride smaller than width x bpp`() {
        // 4x4 RGBA_8888 needs 16 bytes per row, declare 8 → reject.
        val xfer = ImageTransfer(
            requestId = "abc",
            width = 4, height = 4,
            pixelFormat = 1, rowStride = 8,
            payloadBytes = 64,
            source = pfd(), isSharedMemory = true, mimeType = null,
        )
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateImageTransfer(xfer, 100 * 1024 * 1024)
        }
    }

    @Test
    fun `validateImageTransfer accepts well-formed raw pixels`() {
        val xfer = ImageTransfer(
            requestId = "abc",
            width = 2, height = 2,
            pixelFormat = 1, rowStride = 8,
            payloadBytes = 16,
            source = pfd(), isSharedMemory = true, mimeType = null,
        )
        IpcInputValidator.validateImageTransfer(xfer, 100 * 1024 * 1024)
    }

    @Test
    fun `validateImageTransfer rejects unsupported encoded mimeType`() {
        val xfer = ImageTransfer(
            requestId = "abc",
            width = 0, height = 0,
            pixelFormat = 0, rowStride = 0,
            payloadBytes = 1024,
            source = pfd(), isSharedMemory = false,
            mimeType = "image/svg+xml",
        )
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateImageTransfer(xfer, 100 * 1024 * 1024)
        }
    }

    @Test
    fun `validateImageTransfer rejects encoded image without mimeType`() {
        val xfer = ImageTransfer(
            requestId = "abc",
            width = 0, height = 0,
            pixelFormat = 0, rowStride = 0,
            payloadBytes = 1024,
            source = pfd(), isSharedMemory = false,
            mimeType = null,
        )
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateImageTransfer(xfer, 100 * 1024 * 1024)
        }
    }

    @Test
    fun `validateImageTransfer accepts encoded image with zero dims`() {
        val xfer = ImageTransfer(
            requestId = "abc",
            width = 0, height = 0,
            pixelFormat = 0, rowStride = 0,
            payloadBytes = 1024,
            source = pfd(), isSharedMemory = false,
            mimeType = "image/jpeg",
        )
        IpcInputValidator.validateImageTransfer(xfer, 100 * 1024 * 1024)
    }

    // ── F-014: audio MIME allowlist ─────────────────────────────────────

    @Test
    fun `validateAudioTransfer rejects unsupported mimeType`() {
        val xfer = AudioTransfer(
            requestId = "abc",
            mimeType = "audio/x-malicious",
            source = pfd(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateAudioTransfer(xfer)
        }
    }

    @Test
    fun `validateAudioTransfer rejects insane duration`() {
        val xfer = AudioTransfer(
            requestId = "abc",
            mimeType = "audio/wav",
            source = pfd(),
            durationMs = 99L * 60L * 60L * 1000L,
        )
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateAudioTransfer(xfer)
        }
    }

    @Test
    fun `validateAudioTransfer rejects duration over Gemma 30s cap`() {
        // 1 ms over Gemma 4's documented per-clip maximum. Pre-tightening
        // (60-minute cap), this would silently pass and the engine would
        // truncate; the validator now fails closed.
        val overCap = com.adsamcik.mindlayer.GemmaAudioSpec.MAX_DURATION_MS + 1
        val xfer = AudioTransfer(
            requestId = "abc",
            mimeType = "audio/wav",
            source = pfd(),
            durationMs = overCap,
        )
        val ex = assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateAudioTransfer(xfer)
        }
        assertTrue(
            "error mentions the Gemma cap: ${ex.message}",
            ex.message!!.contains("Gemma"),
        )
    }

    @Test
    fun `validateAudioTransfer accepts duration exactly at Gemma cap`() {
        val xfer = AudioTransfer(
            requestId = "abc",
            mimeType = "audio/wav",
            source = pfd(),
            durationMs = com.adsamcik.mindlayer.GemmaAudioSpec.MAX_DURATION_MS,
        )
        IpcInputValidator.validateAudioTransfer(xfer)
    }

    @Test
    fun `validateAudioTransfer accepts null duration`() {
        // Null duration is intentionally allowed; the service-side budget
        // (estimateTokensForAudio) bills the full 30 s ceiling so callers
        // cannot bypass the input-tokens gate by omitting metadata.
        val xfer = AudioTransfer(
            requestId = "abc",
            mimeType = "audio/wav",
            source = pfd(),
            durationMs = null,
        )
        IpcInputValidator.validateAudioTransfer(xfer)
    }

    @Test
    fun `validateAudioTransfer rejects payloadBytes over media cap`() {
        val xfer = AudioTransfer(
            requestId = "abc",
            mimeType = "audio/wav",
            source = pfd(),
            payloadBytes = 101,
        )
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateAudioTransfer(xfer, maxMediaBytes = 100)
        }
    }

    @Test
    fun `validateAudioTransfer accepts valid wav`() {
        val xfer = AudioTransfer(
            requestId = "abc",
            mimeType = "audio/wav",
            source = pfd(),
        )
        IpcInputValidator.validateAudioTransfer(xfer)
    }

    // ── F-017: SessionConfig string budgets ─────────────────────────────

    @Test
    fun `validateSessionConfig rejects oversized systemPrompt`() {
        val cfg = SessionConfig(
            systemPrompt = "x".repeat(IpcInputValidator.MAX_SYSTEM_PROMPT_LEN + 1),
        )
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateSessionConfig(cfg)
        }
    }

    @Test
    fun `validateSessionConfig rejects oversized toolsJson`() {
        val cfg = SessionConfig(
            toolsJson = "x".repeat(IpcInputValidator.MAX_TOOLS_JSON_LEN + 1),
        )
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateSessionConfig(cfg)
        }
    }

    @Test
    fun `validateSessionConfig rejects too many history turns`() {
        val cfg = SessionConfig(
            initialHistory = List(IpcInputValidator.MAX_HISTORY_TURNS + 1) {
                HistoryTurn(role = "user", text = "x")
            },
        )
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateSessionConfig(cfg)
        }
    }

    @Test
    fun `validateSessionConfig rejects oversized history turn text`() {
        val cfg = SessionConfig(
            initialHistory = listOf(
                HistoryTurn(role = "user", text = "x".repeat(IpcInputValidator.MAX_HISTORY_TURN_LEN + 1)),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateSessionConfig(cfg)
        }
    }

    @Test
    fun `validateSessionConfig rejects unsupported backend`() {
        val cfg = SessionConfig(backend = "TPU")
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateSessionConfig(cfg)
        }
    }

    @Test
    fun `validateSessionConfig accepts well-formed config`() {
        IpcInputValidator.validateSessionConfig(SessionConfig())
    }

    @Test
    fun `validateSessionConfig rejects non-positive expiration`() {
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateSessionConfig(SessionConfig(expirationMs = 0))
        }
    }

    @Test
    fun `validateSessionConfig rejects excessive expiration`() {
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateSessionConfig(
                SessionConfig(expirationMs = IpcInputValidator.MAX_SESSION_EXPIRATION_MS + 1),
            )
        }
    }

    // ── F-012: ToolResult byte cap ──────────────────────────────────────

    @Test
    fun `validateToolResult rejects oversize resultJson`() {
        val r = ToolResult(
            requestId = "abc",
            callId = "call-1",
            toolName = "weather",
            resultJson = "x".repeat(IpcInputValidator.MAX_TOOL_RESULT_LEN + 1),
        )
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateToolResult(r)
        }
    }

    @Test
    fun `validateToolResult accepts within cap`() {
        IpcInputValidator.validateToolResult(
            ToolResult("abc", "call-1", "weather", """{"answer":"ok"}"""),
        )
    }

    @Test
    fun `validateToolResult rejects invalid callId`() {
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateToolResult(
                ToolResult("abc", "bad call id", "weather", """{"answer":"ok"}"""),
            )
        }
    }

    // ── F-017: RequestMeta textContent ──────────────────────────────────

    @Test
    fun `validateRequestMeta rejects oversized textContent`() {
        val meta = RequestMeta(
            requestId = "abc",
            sessionId = "sid",
            textContent = "x".repeat(IpcInputValidator.MAX_TEXT_CONTENT_LEN + 1),
        )
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateRequestMeta(meta)
        }
    }

    @Test
    fun `validateRequestMeta rejects unknown role`() {
        val meta = RequestMeta(requestId = "abc", sessionId = "sid", role = "admin")
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateRequestMeta(meta)
        }
    }

    // ── F-057: prewarm backend allowlist ────────────────────────────────

    @Test
    fun `validateBackendName rejects unknown backend`() {
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateBackendName("MAGIC_BACKEND")
        }
    }

    @Test
    fun `validateBackendName defaults null to GPU`() {
        assertEquals("GPU", IpcInputValidator.validateBackendName(null))
    }

    @Test
    fun `validateBackendName accepts known backends`() {
        for (b in listOf("GPU", "CPU", "NPU")) {
            assertEquals(b, IpcInputValidator.validateBackendName(b))
        }
    }
}
