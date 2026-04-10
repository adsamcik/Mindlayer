package com.mindlayer.service.testutil

import com.mindlayer.shared.StreamEvent
import com.mindlayer.shared.StreamEventType
import com.mindlayer.shared.StreamHeader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Shared test utilities for integration tests.
 *
 * Provides:
 * - [ParsedEvent] — typed event wrapper for assertion convenience
 * - [readFrames] — read length-prefixed JSON frames from a pipe
 * - [parseFrames] / [parseOneFrame] — convert raw JSON → typed events
 * - Contract assertion helpers for terminal events and cleanup
 */
object TestPipeHelper {

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ---- Parsed event type --------------------------------------------------

    data class ParsedEvent(
        val kind: String,
        val text: String? = null,
        val finishReason: String? = null,
        val requestId: String? = null,
        val errorMessage: String? = null,
        val errorCode: String? = null,
        val toolName: String? = null,
        val toolArgs: String? = null,
        val callId: String? = null,
        val seq: Long? = null,
    ) {
        val isTerminal: Boolean get() = kind == "done" || kind == "error"
    }

    // ---- Frame reading ------------------------------------------------------

    fun readFrames(input: InputStream): List<String> {
        val frames = mutableListOf<String>()
        val dis = DataInputStream(BufferedInputStream(input))
        try {
            while (true) {
                val len = try {
                    Integer.reverseBytes(dis.readInt())
                } catch (_: EOFException) {
                    break
                }
                if (len < 0 || len > 1_048_576) break
                val bytes = ByteArray(len)
                dis.readFully(bytes)
                frames.add(bytes.decodeToString())
            }
        } catch (_: IOException) { }
        return frames
    }

    fun parseFrames(rawFrames: List<String>): List<ParsedEvent> =
        rawFrames.map(::parseOneFrame)

    fun parseOneFrame(raw: String): ParsedEvent {
        try {
            val header = json.decodeFromString<StreamHeader>(raw)
            if (header.protocol.isNotEmpty()) {
                return ParsedEvent(kind = "header", requestId = header.requestId)
            }
        } catch (_: Exception) { }

        val event = json.decodeFromString<StreamEvent>(raw)
        return when (event.type) {
            StreamEventType.TOKEN_DELTA -> ParsedEvent(
                kind = "token_delta",
                text = event.payload["text"]?.jsonPrimitive?.contentOrNull,
                seq = event.seq,
            )
            StreamEventType.DONE -> ParsedEvent(
                kind = "done",
                finishReason = event.payload["finish_reason"]?.jsonPrimitive?.contentOrNull,
                seq = event.seq,
            )
            StreamEventType.ERROR -> ParsedEvent(
                kind = "error",
                errorMessage = event.payload["message"]?.jsonPrimitive?.contentOrNull,
                errorCode = event.payload["code"]?.jsonPrimitive?.contentOrNull,
                seq = event.seq,
            )
            StreamEventType.TOOL_CALL -> ParsedEvent(
                kind = "tool_call",
                toolName = event.payload["name"]?.jsonPrimitive?.contentOrNull,
                toolArgs = event.payload["args"]?.jsonPrimitive?.contentOrNull,
                callId = event.payload["callId"]?.jsonPrimitive?.contentOrNull,
                seq = event.seq,
            )
            else -> ParsedEvent(kind = event.type, seq = event.seq)
        }
    }

    // ---- Contract assertion helpers -----------------------------------------

    /** Assert exactly one terminal event (done or error) exists and it is the last event. */
    fun assertSingleTerminalEvent(events: List<ParsedEvent>, message: String = "") {
        val terminals = events.filter { it.isTerminal }
        assertEquals(
            "${message}Expected exactly 1 terminal event, got ${terminals.size}",
            1, terminals.size,
        )
        assertTrue(
            "${message}Terminal event must be the last event",
            events.last().isTerminal,
        )
    }

    /** Assert no events appear after the first terminal event. */
    fun assertNoEventsAfterTerminal(events: List<ParsedEvent>, message: String = "") {
        val terminalIdx = events.indexOfFirst { it.isTerminal }
        if (terminalIdx == -1) return
        assertEquals(
            "${message}Events found after terminal at index $terminalIdx",
            events.lastIndex, terminalIdx,
        )
    }

    /** Assert event ordering: all events have strictly increasing seq. */
    fun assertMonotonicSeq(events: List<ParsedEvent>, message: String = "") {
        val seqs = events.filter { it.seq != null && it.kind != "header" }.map { it.seq!! }
        for (i in 1 until seqs.size) {
            assertTrue(
                "${message}Seq not monotonic: ${seqs[i - 1]} -> ${seqs[i]}",
                seqs[i] > seqs[i - 1],
            )
        }
    }

    /** Assert the full event contract: monotonic seq, single terminal, nothing after terminal. */
    fun assertEventContract(events: List<ParsedEvent>, message: String = "") {
        assertMonotonicSeq(events, message)
        assertSingleTerminalEvent(events, message)
        assertNoEventsAfterTerminal(events, message)
    }
}
