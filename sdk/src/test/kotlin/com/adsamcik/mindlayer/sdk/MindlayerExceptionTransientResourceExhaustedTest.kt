package com.adsamcik.mindlayer.sdk

import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * F-076: the wire-prefixed [SecurityException] for
 * `TRANSIENT_RESOURCE_EXHAUSTED` must round-trip into a typed
 * [MindlayerException] with:
 *  - `code = TRANSIENT_RESOURCE_EXHAUSTED`
 *  - `codeName = "TRANSIENT_RESOURCE_EXHAUSTED"`
 *  - `category = Category.RESOURCE`
 *  - `retryAfterMs` parsed out of the message body so callers can apply
 *    a service-suggested backoff before retrying the staging request.
 *
 * Mirrors the existing [MindlayerExceptionInputExceedsContextTest]
 * pattern from F-072 — both surfaces (AIDL boundary + structured
 * payload) need symmetric coverage.
 */
class MindlayerExceptionTransientResourceExhaustedTest {

    @Test
    fun `wire SecurityException maps to typed code with retryAfterMs parsed`() {
        // Wire format produced by the service:
        //   "MLERR:5004:shm_pool_exhausted reason=global_active_pfds retryAfterMs=1000"
        val wire = MindlayerErrorCode.wireMessage(
            MindlayerErrorCode.TRANSIENT_RESOURCE_EXHAUSTED,
            "shm_pool_exhausted reason=global_active_pfds retryAfterMs=1000",
        )
        val cause = SecurityException(wire)

        val mle = MindlayerException.fromAidlSecurityException(cause)!!

        assertEquals(MindlayerErrorCode.TRANSIENT_RESOURCE_EXHAUSTED, mle.code)
        assertEquals("TRANSIENT_RESOURCE_EXHAUSTED", mle.codeName)
        assertEquals(MindlayerErrorCode.Category.RESOURCE, mle.category)
        assertNotNull("retryAfterMs should be parseable", mle.retryAfterMs)
        assertEquals(1000L, mle.retryAfterMs)
    }

    @Test
    fun `retryAfterMs is null when code is not TRANSIENT_RESOURCE_EXHAUSTED`() {
        // Even if the message contains `retryAfterMs=...`, the parser only
        // exposes it when the code is the TRANSIENT_RESOURCE_EXHAUSTED one
        // — prevents accidental parsing of unrelated wire payloads (like
        // a future ENGINE_INITIALIZING message that happens to embed a
        // delay hint).
        val mle = MindlayerException(
            message = "engine_initializing retryAfterMs=999",
            code = MindlayerErrorCode.ENGINE_INITIALIZING,
        )
        assertNull(mle.retryAfterMs)
    }

    @Test
    fun `retryAfterMs is null when service did not include the marker`() {
        // Forward-compat: an old service binary that doesn't emit
        // `retryAfterMs=` in the message must not break the SDK. The SDK
        // surfaces the typed code regardless, and `retryAfterMs` returns
        // null so the caller can fall back to a generic exponential
        // backoff strategy.
        val wire = MindlayerErrorCode.wireMessage(
            MindlayerErrorCode.TRANSIENT_RESOURCE_EXHAUSTED,
            "shm_pool_exhausted",
        )
        val mle = MindlayerException.fromAidlSecurityException(SecurityException(wire))!!

        assertEquals(MindlayerErrorCode.TRANSIENT_RESOURCE_EXHAUSTED, mle.code)
        assertNull(mle.retryAfterMs)
    }

    @Test
    fun `retryAfterMs parses 0 correctly when service signals immediate retry`() {
        // Edge case: the cap was momentarily exceeded but the resource
        // is already free again. Wire payload encodes `retryAfterMs=0`
        // and the SDK must parse it as `0L`, not null (which would
        // imply "old service binary").
        val wire = MindlayerErrorCode.wireMessage(
            MindlayerErrorCode.TRANSIENT_RESOURCE_EXHAUSTED,
            "shm_pool_exhausted reason=global_staged_bytes retryAfterMs=0",
        )
        val mle = MindlayerException.fromAidlSecurityException(SecurityException(wire))!!

        assertEquals(0L, mle.retryAfterMs)
    }

    @Test
    fun `TRANSIENT_RESOURCE_EXHAUSTED has a registered name and RESOURCE category`() {
        // Sanity: the new code is discoverable by name + category lookup
        // so the SDK never produces a "code: 5004" with no friendly name.
        assertEquals(
            "TRANSIENT_RESOURCE_EXHAUSTED",
            MindlayerErrorCode.nameOf(MindlayerErrorCode.TRANSIENT_RESOURCE_EXHAUSTED),
        )
        assertEquals(
            MindlayerErrorCode.Category.RESOURCE,
            MindlayerErrorCode.categoryOf(MindlayerErrorCode.TRANSIENT_RESOURCE_EXHAUSTED),
        )
    }

    @Test
    fun `retryAfterMs parses large values without overflow`() {
        // A future server may suggest a multi-second cooldown. Long
        // value means a 32-bit-int parse path would silently truncate
        // — verify we use Long throughout.
        val wire = MindlayerErrorCode.wireMessage(
            MindlayerErrorCode.TRANSIENT_RESOURCE_EXHAUSTED,
            "shm_pool_exhausted reason=global_active_pfds retryAfterMs=60000",
        )
        val mle = MindlayerException.fromAidlSecurityException(SecurityException(wire))!!
        assertEquals(60_000L, mle.retryAfterMs)
    }
}
