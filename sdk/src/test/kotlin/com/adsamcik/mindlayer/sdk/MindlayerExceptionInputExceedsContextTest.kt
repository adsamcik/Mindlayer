package com.adsamcik.mindlayer.sdk

import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * F-072: the wire-prefixed [SecurityException] for `INPUT_EXCEEDS_CONTEXT`
 * must round-trip into a typed [MindlayerException] with:
 *  - `code = INPUT_EXCEEDS_CONTEXT`
 *  - `codeName = "INPUT_EXCEEDS_CONTEXT"`
 *  - `category = Category.VALIDATION`
 *  - `remainingTokens` parsed out of the message body so callers can
 *    show the user how much input the session still accepts.
 *
 * Mirrors the existing [MindlayerExceptionCodesTest] pattern — both
 * surfaces (AIDL boundary + stream ERROR frame) need symmetric coverage.
 */
class MindlayerExceptionInputExceedsContextTest {

    @Test
    fun `wire SecurityException maps to typed code with remainingTokens parsed`() {
        // Wire format produced by the service:
        //   "MLERR:3006:input_exceeds_context (reserved=600, estimated_input=1700, max=2048, remaining=1448)"
        val wire = MindlayerErrorCode.wireMessage(
            MindlayerErrorCode.INPUT_EXCEEDS_CONTEXT,
            "input_exceeds_context (reserved=600, estimated_input=1700, max=2048, remaining=1448)",
        )
        val cause = SecurityException(wire)

        val mle = MindlayerException.fromAidlSecurityException(cause)!!

        assertEquals(MindlayerErrorCode.INPUT_EXCEEDS_CONTEXT, mle.code)
        assertEquals("INPUT_EXCEEDS_CONTEXT", mle.codeName)
        assertEquals(MindlayerErrorCode.Category.VALIDATION, mle.category)
        assertNotNull("remainingTokens should be parseable", mle.remainingTokens)
        assertEquals(1448, mle.remainingTokens)
    }

    @Test
    fun `remainingTokens is null when code is not INPUT_EXCEEDS_CONTEXT`() {
        // Even if the message contains `remaining=...`, the parser only
        // exposes it when the code is the INPUT_EXCEEDS_CONTEXT one —
        // prevents accidental parsing of unrelated wire payloads.
        val mle = MindlayerException(
            message = "engine_initializing remaining=999",
            code = MindlayerErrorCode.ENGINE_INITIALIZING,
        )
        assertNull(mle.remainingTokens)
    }

    @Test
    fun `remainingTokens is null when service did not include the marker`() {
        // Forward-compat: an old service binary that doesn't emit
        // `remaining=` in the message must not break the SDK. The SDK
        // surfaces the typed code regardless, and `remainingTokens`
        // returns null so the caller can fall back to a generic
        // "input too long" UX.
        val wire = MindlayerErrorCode.wireMessage(
            MindlayerErrorCode.INPUT_EXCEEDS_CONTEXT,
            "input_exceeds_context",
        )
        val mle = MindlayerException.fromAidlSecurityException(SecurityException(wire))!!

        assertEquals(MindlayerErrorCode.INPUT_EXCEEDS_CONTEXT, mle.code)
        assertNull(mle.remainingTokens)
    }

    @Test
    fun `remainingTokens parses 0 correctly when overhead exhausts the budget`() {
        // Edge case: service-owned overhead alone meets/exceeds the
        // ceiling. Wire payload encodes `remaining=0` and the SDK must
        // parse it as `0`, not null (which would imply "old service").
        val wire = MindlayerErrorCode.wireMessage(
            MindlayerErrorCode.INPUT_EXCEEDS_CONTEXT,
            "input_exceeds_context (reserved=2334, estimated_input=0, max=2048, remaining=0)",
        )
        val mle = MindlayerException.fromAidlSecurityException(SecurityException(wire))!!

        assertEquals(0, mle.remainingTokens)
    }

    @Test
    fun `INPUT_EXCEEDS_CONTEXT has a registered name and VALIDATION category`() {
        // Sanity: the new code is discoverable by name + category lookup
        // so the SDK never produces a "code: 3006" with no friendly name.
        assertEquals(
            "INPUT_EXCEEDS_CONTEXT",
            MindlayerErrorCode.nameOf(MindlayerErrorCode.INPUT_EXCEEDS_CONTEXT),
        )
        assertEquals(
            MindlayerErrorCode.Category.VALIDATION,
            MindlayerErrorCode.categoryOf(MindlayerErrorCode.INPUT_EXCEEDS_CONTEXT),
        )
    }
}
