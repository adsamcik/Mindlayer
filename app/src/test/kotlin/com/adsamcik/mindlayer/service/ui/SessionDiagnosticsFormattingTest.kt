package com.adsamcik.mindlayer.service.ui

import com.adsamcik.mindlayer.service.logging.LogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionDiagnosticsFormattingTest {

    @Test
    fun `detail route encodes and decodes reserved characters`() {
        val sessionId = "session/with spaces?trace=1#frag"

        val route = MindlayerNavigation.detailRoute(sessionId)

        assertEquals(
            "detail/session%2Fwith%20spaces%3Ftrace%3D1%23frag",
            route,
        )
        assertEquals(
            sessionId,
            MindlayerNavigation.decodeSessionId(route.substringAfter("detail/")),
        )
    }

    @Test
    fun `decode session id rejects blank arguments`() {
        assertNull(MindlayerNavigation.decodeSessionId(null))
        assertNull(MindlayerNavigation.decodeSessionId(""))
        assertNull(MindlayerNavigation.decodeSessionId("   "))
    }

    @Test
    fun `format session id keeps useful suffix for debugging`() {
        assertEquals("short-id", formatSessionIdForDisplay("short-id"))
        assertEquals(
            "12345678…cdef12",
            formatSessionIdForDisplay("1234567890abcdef12"),
        )
    }

    @Test
    fun `format relative time uses readable thresholds`() {
        val now = 1_000_000L

        assertEquals("just now", formatRelativeTime(now - 30_000L, now))
        assertEquals("5m ago", formatRelativeTime(now - 5 * 60_000L, now))
        assertEquals("2h ago", formatRelativeTime(now - 2 * 3_600_000L, now))
        assertEquals("3d ago", formatRelativeTime(now - 3 * 86_400_000L, now))
    }

    @Test
    fun `build event detail includes structured diagnostics and rejects arbitrary json`() {
        val entry = LogEntry(
            timestampMs = 1_234L,
            category = "INFERENCE",
            event = "request_complete",
            durationMs = 1_500L,
            tokensGenerated = 512,
            tokensPerSec = 64.5f,
            prefillTokensPerSec = 88.0f,
            backend = "gpu",
            memoryAvailableMb = 4_096L,
            errorMessage = "prefill slowed",
            extraJson = "{\"turn\":1}",
        )

        assertEquals(
            "1,500ms • 512 tokens • 64.5 tok/s • prefill 88.0 tok/s • GPU • prefill slowed • 4,096MB free",
            buildEventDetail(entry),
        )
    }
}
