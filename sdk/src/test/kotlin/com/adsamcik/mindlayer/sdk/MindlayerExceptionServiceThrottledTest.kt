package com.adsamcik.mindlayer.sdk

import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * F-074: the wire-prefixed [SecurityException] for `SERVICE_THROTTLED`
 * must round-trip into a typed [MindlayerException] with:
 *  - `code = SERVICE_THROTTLED`
 *  - `codeName = "SERVICE_THROTTLED"`
 *  - `category = Category.RESOURCE`
 *  - `cooldownEndsAt` parsed out of the message body so the SDK's
 *    reconnect loop can defer until the throttle naturally lifts.
 *
 * Mirrors the F-072 [MindlayerExceptionInputExceedsContextTest] pattern.
 */
class MindlayerExceptionServiceThrottledTest {

    @Test
    fun `wire SecurityException maps to typed code with cooldownEndsAt parsed`() {
        // Wire format produced by the service:
        //   "MLERR:5003:service_throttled (cooldown=1700000000000)"
        val cooldown = 1_700_000_000_000L
        val wire = MindlayerErrorCode.wireMessage(
            MindlayerErrorCode.SERVICE_THROTTLED,
            "service_throttled (cooldown=$cooldown)",
        )
        val cause = SecurityException(wire)

        val mle = MindlayerException.fromAidlSecurityException(cause)!!

        assertEquals(MindlayerErrorCode.SERVICE_THROTTLED, mle.code)
        assertEquals("SERVICE_THROTTLED", mle.codeName)
        assertEquals(MindlayerErrorCode.Category.RESOURCE, mle.category)
        assertNotNull("cooldownEndsAt should be parseable", mle.cooldownEndsAt)
        assertEquals(cooldown, mle.cooldownEndsAt)
    }

    @Test
    fun `cooldownEndsAt is null when code is not SERVICE_THROTTLED`() {
        // Even if the message contains `cooldown=...`, the parser only
        // exposes it when the code is the SERVICE_THROTTLED one — prevents
        // accidental parsing of unrelated wire payloads.
        val mle = MindlayerException(
            message = "engine_initializing cooldown=999",
            code = MindlayerErrorCode.ENGINE_INITIALIZING,
        )
        assertNull(mle.cooldownEndsAt)
    }

    @Test
    fun `cooldownEndsAt is null when service did not include the marker`() {
        // Forward-compat: an older service binary that emits SERVICE_THROTTLED
        // without the cooldown marker must not break the SDK. The SDK
        // surfaces the typed code regardless and the caller falls back to
        // the local exponential-backoff cursor.
        val wire = MindlayerErrorCode.wireMessage(
            MindlayerErrorCode.SERVICE_THROTTLED,
            "service_throttled",
        )
        val mle = MindlayerException.fromAidlSecurityException(SecurityException(wire))!!

        assertEquals(MindlayerErrorCode.SERVICE_THROTTLED, mle.code)
        assertNull(mle.cooldownEndsAt)
    }

    @Test
    fun `SERVICE_THROTTLED has a registered name and RESOURCE category`() {
        // Sanity: the new code is discoverable by name + category lookup
        // so the SDK never produces a "code: 5003" with no friendly name.
        assertEquals(
            "SERVICE_THROTTLED",
            MindlayerErrorCode.nameOf(MindlayerErrorCode.SERVICE_THROTTLED),
        )
        assertEquals(
            MindlayerErrorCode.Category.RESOURCE,
            MindlayerErrorCode.categoryOf(MindlayerErrorCode.SERVICE_THROTTLED),
        )
    }

    @Test
    fun `cooldown=0 parses as 0L not null`() {
        // Edge case: service-side clock pathology emits cooldown=0. The
        // SDK must distinguish "missing marker" (null → fall back to
        // local backoff) from "explicit zero" (0L → reconnect immediately
        // capped by SDK minimum).
        val wire = MindlayerErrorCode.wireMessage(
            MindlayerErrorCode.SERVICE_THROTTLED,
            "service_throttled (cooldown=0)",
        )
        val mle = MindlayerException.fromAidlSecurityException(SecurityException(wire))!!
        assertEquals(0L, mle.cooldownEndsAt)
    }
}
