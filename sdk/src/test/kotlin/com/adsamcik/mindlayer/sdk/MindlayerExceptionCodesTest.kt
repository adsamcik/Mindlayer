package com.adsamcik.mindlayer.sdk

import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Tests for v02-error-codes: verify [MindlayerException] correctly carries
 * wire codes produced by prefixed Binder SecurityExceptions from the AIDL
 * boundary, and that pipe-side stream-error frames map to the same exception
 * type via the string `codeName` channel.
 */
class MindlayerExceptionCodesTest {

    @Test
    fun `fromAidlSecurityException populates code, codeName, and category`() {
        val cause = SecurityException(
            MindlayerErrorCode.wireMessage(
                MindlayerErrorCode.ENGINE_INITIALIZING,
                "engine_initializing",
            ),
        )

        val mle = MindlayerException.fromAidlSecurityException(cause)!!

        assertEquals(MindlayerErrorCode.ENGINE_INITIALIZING, mle.code)
        assertEquals("ENGINE_INITIALIZING", mle.codeName)
        assertEquals(MindlayerErrorCode.Category.ENGINE, mle.category)
        assertEquals("engine_initializing", mle.message)
        assertSame(cause, mle.cause)
    }

    @Test
    fun `session-not-found-or-not-owned uses single anti-enumeration code`() {
        val cause = SecurityException(
            MindlayerErrorCode.wireMessage(
                MindlayerErrorCode.SESSION_NOT_FOUND_OR_NOT_OWNED,
                "Session not found or not owned by caller",
            ),
        )
        val mle = MindlayerException.fromAidlSecurityException(cause)!!

        assertEquals(MindlayerErrorCode.SESSION_NOT_FOUND_OR_NOT_OWNED, mle.code)
        assertEquals("SESSION_NOT_FOUND_OR_NOT_OWNED", mle.codeName)
        assertEquals(MindlayerErrorCode.Category.SESSION, mle.category)
    }

    @Test
    fun `unmapped code yields UNKNOWN category and null codeName`() {
        val cause = SecurityException(MindlayerErrorCode.wireMessage(8888, "future code"))
        val mle = MindlayerException.fromAidlSecurityException(cause)!!

        assertEquals(8888, mle.code)
        assertNull(mle.codeName)
        assertEquals(MindlayerErrorCode.Category.UNKNOWN, mle.category)
    }

    @Test
    fun `fromStreamError carries SDK-internal string codes through codeName`() {
        val mle = MindlayerException.fromStreamError(
            message = "tool call not supported",
            codeName = "UNSUPPORTED_TOOL_CALL",
            seq = 42L,
            tsMs = 1_700_000_000_000L,
            requestId = "req-1",
        )

        assertEquals(MindlayerErrorCode.UNKNOWN, mle.code)
        assertEquals("UNSUPPORTED_TOOL_CALL", mle.codeName)
        assertEquals(MindlayerErrorCode.Category.UNKNOWN, mle.category)
        assertEquals(42L, mle.seq)
        assertEquals(1_700_000_000_000L, mle.tsMs)
        assertEquals("req-1", mle.requestId)
    }

    @Test
    fun `fromStreamError uses numeric codeInt when present`() {
        val mle = MindlayerException.fromStreamError(
            message = "retryAfterMs=250",
            codeName = "TRANSIENT_RESOURCE_EXHAUSTED",
            codeInt = MindlayerErrorCode.TRANSIENT_RESOURCE_EXHAUSTED,
        )

        assertEquals(MindlayerErrorCode.TRANSIENT_RESOURCE_EXHAUSTED, mle.code)
        assertEquals("TRANSIENT_RESOURCE_EXHAUSTED", mle.codeName)
        assertEquals(MindlayerErrorCode.Category.RESOURCE, mle.category)
        assertEquals(250L, mle.retryAfterMs)
    }

    @Test
    fun `categoryOf maps every defined code to a non-UNKNOWN bucket`() {
        // Sanity: anything we've named in MindlayerErrorCode.nameOf except
        // INTERNAL/UNKNOWN should land in a real category.
        val coded = listOf(
            MindlayerErrorCode.ENGINE_INITIALIZING to MindlayerErrorCode.Category.ENGINE,
            MindlayerErrorCode.ENGINE_LOAD_FAILED to MindlayerErrorCode.Category.ENGINE,
            MindlayerErrorCode.SESSION_NOT_FOUND_OR_NOT_OWNED to MindlayerErrorCode.Category.SESSION,
            MindlayerErrorCode.SESSION_EVICTED to MindlayerErrorCode.Category.SESSION,
            MindlayerErrorCode.SESSION_EXPIRED to MindlayerErrorCode.Category.SESSION,
            MindlayerErrorCode.INVALID_REQUEST to MindlayerErrorCode.Category.VALIDATION,
            MindlayerErrorCode.INVALID_SESSION_CONFIG to MindlayerErrorCode.Category.VALIDATION,
            MindlayerErrorCode.INVALID_TOOL_RESULT to MindlayerErrorCode.Category.VALIDATION,
            MindlayerErrorCode.DUPLICATE_REQUEST to MindlayerErrorCode.Category.VALIDATION,
            MindlayerErrorCode.NO_ACTIVE_REQUEST to MindlayerErrorCode.Category.VALIDATION,
            MindlayerErrorCode.THERMAL_CRITICAL to MindlayerErrorCode.Category.RESOURCE,
            MindlayerErrorCode.MEMORY_PRESSURE to MindlayerErrorCode.Category.RESOURCE,
            MindlayerErrorCode.CONCURRENT_LIMIT to MindlayerErrorCode.Category.RESOURCE,
            MindlayerErrorCode.RATE_LIMITED to MindlayerErrorCode.Category.RESOURCE,
            MindlayerErrorCode.ALLOWLIST_PENDING to MindlayerErrorCode.Category.AUTH,
            MindlayerErrorCode.ALLOWLIST_REVOKED to MindlayerErrorCode.Category.AUTH,
            MindlayerErrorCode.IDENTITY_UNKNOWN to MindlayerErrorCode.Category.AUTH,
        )
        for ((code, expected) in coded) {
            assertEquals(
                "code $code (${MindlayerErrorCode.nameOf(code)})",
                expected,
                MindlayerErrorCode.categoryOf(code),
            )
            assertNotNull(
                "code $code should have a symbolic name",
                MindlayerErrorCode.nameOf(code),
            )
        }
    }

    @Test
    fun `category is always derived from code never trusted from caller`() {
        // Even if a caller passes nonsense fields, the category derives from `code`.
        val mle = MindlayerException(
            message = "test",
            code = MindlayerErrorCode.RATE_LIMITED,
            codeName = "WRONG_NAME_SUPPLIED",
            requestId = "req",
        )
        // codeName when explicitly supplied wins over derived (intentional —
        // pipe errors carry SDK-internal names that don't match int codes).
        assertEquals("WRONG_NAME_SUPPLIED", mle.codeName)
        // category is always derived from code, never overridable.
        assertEquals(MindlayerErrorCode.Category.RESOURCE, mle.category)
    }
}
