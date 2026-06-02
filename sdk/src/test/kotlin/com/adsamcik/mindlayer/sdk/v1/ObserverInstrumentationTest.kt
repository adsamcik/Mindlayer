package com.adsamcik.mindlayer.sdk.v1

import com.adsamcik.mindlayer.sdk.CallOutcome
import com.adsamcik.mindlayer.sdk.MindlayerException
import com.adsamcik.mindlayer.sdk.MindlayerObserver
import com.adsamcik.mindlayer.sdk.Span
import com.adsamcik.mindlayer.sdk.instrumentCall
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the observer instrumentation chokepoint ([instrumentCall], Spike-E
 * §3 / §5 · §8.5). Verifies the start/end bracketing contract across the four
 * outcomes: unset observer (no-op), success, typed failure, and cancellation.
 */
class ObserverInstrumentationTest {

    /** Recording observer; same-module access to the internal [Span] constructor. */
    private class RecordingObserver : MindlayerObserver {
        val starts = mutableListOf<Pair<String, Map<String, String>>>()
        val ends = mutableListOf<CallOutcome>()

        override fun onCallStart(method: String, params: Map<String, String>): Span {
            starts += method to params
            return Span("span-${starts.size}", 0L)
        }

        override fun onCallEnd(span: Span, outcome: CallOutcome) {
            ends += outcome
        }
    }

    @Test
    fun `null observer runs block without bracketing`() = runTest {
        var ran = 0
        val result = instrumentCall(
            observer = null,
            method = "infer",
            params = emptyMap(),
            summarise = { "ignored" },
        ) {
            ran++
            "value"
        }

        assertEquals("value", result)
        assertEquals(1, ran)
    }

    @Test
    fun `success brackets with a Success outcome carrying the summary`() = runTest {
        val obs = RecordingObserver()

        val result = instrumentCall(
            observer = obs,
            method = "embed",
            params = mapOf("single" to "true"),
            summarise = { r -> "len=${r.length}" },
        ) {
            "hello"
        }

        assertEquals("hello", result)
        assertEquals(1, obs.starts.size)
        assertEquals("embed", obs.starts.single().first)
        assertEquals(mapOf("single" to "true"), obs.starts.single().second)
        val outcome = obs.ends.single()
        assertTrue(outcome is CallOutcome.Success)
        assertEquals("len=5", (outcome as CallOutcome.Success).resultSummary)
    }

    @Test
    fun `MindlayerException is reported as Failure and rethrown unchanged`() = runTest {
        val obs = RecordingObserver()
        val boom = MindlayerException(
            message = "no engine",
            code = MindlayerErrorCode.NOT_SUPPORTED,
        )

        val thrown = runCatching {
            instrumentCall(
                observer = obs,
                method = "ocr",
                params = emptyMap(),
                summarise = { null },
            ) {
                throw boom
            }
        }.exceptionOrNull()

        assertSame(boom, thrown)
        val outcome = obs.ends.single()
        assertTrue(outcome is CallOutcome.Failure)
        assertSame(boom, (outcome as CallOutcome.Failure).error)
    }

    @Test
    fun `cancellation is reported as CANCELLED Failure and rethrown`() = runTest {
        val obs = RecordingObserver()

        val thrown = runCatching {
            instrumentCall(
                observer = obs,
                method = "infer",
                params = emptyMap(),
                summarise = { null },
            ) {
                throw CancellationException("collector cancelled")
            }
        }.exceptionOrNull()

        assertTrue("cancellation must propagate", thrown is CancellationException)
        val outcome = obs.ends.single()
        assertTrue(outcome is CallOutcome.Failure)
        val error = (outcome as CallOutcome.Failure).error
        assertEquals(MindlayerErrorCode.UNKNOWN, error.code)
        assertEquals("CANCELLED", error.codeName)
    }

    @Test
    fun `block that never throws calls start before end exactly once`() = runTest {
        val order = mutableListOf<String>()

        instrumentCall(
            observer = object : MindlayerObserver {
                override fun onCallStart(method: String, params: Map<String, String>): Span {
                    order += "start"
                    return Span("s", 0L)
                }

                override fun onCallEnd(span: Span, outcome: CallOutcome) {
                    order += "end"
                }
            },
            method = "infer",
            params = emptyMap(),
            summarise = { null },
        ) {
            order += "block"
            Unit
        }

        assertEquals(listOf("start", "block", "end"), order)
    }
}
