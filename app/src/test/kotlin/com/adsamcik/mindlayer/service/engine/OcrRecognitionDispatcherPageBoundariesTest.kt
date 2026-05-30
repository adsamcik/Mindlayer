package com.adsamcik.mindlayer.service.engine

import android.util.Log
import com.adsamcik.mindlayer.OcrSessionConfig
import com.adsamcik.mindlayer.service.ipc.OcrTokenStreamWriter
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Integration tests for the v0.9 page-boundary detection wiring in
 * [OcrRecognitionDispatcher].
 *
 * Each test drives the dispatcher through a fake [PaddleOcrEngine] +
 * fake [OcrLlmExtractor] and asserts the emitted event sequence on the
 * captured byte stream. Where exact ordering matters (regression
 * guards) we compare verbatim; where the dispatcher has scheduling
 * freedom (page-aware path interleaves frame events + page events)
 * we assert on presence + relative order via [orderedIndices].
 */
class OcrRecognitionDispatcherPageBoundariesTest {

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After fun teardown() {
        unmockkAll()
    }

    // ── Fixtures ───────────────────────────────────────────────────────

    private fun line(text: String, confidence: OcrFieldFusion.Confidence = OcrFieldFusion.Confidence.HIGH) =
        OcrTextLine(text = text, confidence = confidence)

    private fun engineOutput(lines: List<OcrTextLine>) = OcrEngineOutput(
        lines = lines,
        backend = "CPU",
        detDurationMs = 0,
        recDurationMs = 0,
        clsDurationMs = 0,
        totalDurationMs = 0,
    )

    /** A scripted engine that returns the next entry of [script] on each [recognise] call. */
    private fun scriptedEngine(script: List<List<OcrTextLine>>): PaddleOcrEngine {
        val engine = mockk<PaddleOcrEngine>()
        var index = 0
        coEvery { engine.recognise(any(), any(), any(), any()) } coAnswers {
            val i = index.coerceAtMost(script.lastIndex)
            index++
            engineOutput(script[i])
        }
        return engine
    }

    /** An extractor that returns the same canned JSON for every call. */
    private class StubOcrLlmExtractor(
        private val rawJson: String? = """{"stub":true}""",
    ) : OcrLlmExtractor {
        var calls: Int = 0
            private set
        override suspend fun extract(evidence: OcrEvidencePackage): OcrExtractionResult {
            calls++
            return OcrExtractionResult(fields = emptyList(), rawJson = rawJson)
        }
    }

    private fun pageOptions(
        stabilityFrames: Int = 3,
        llmPerPage: Boolean = false,
        llmFinal: Boolean = true,
    ): String {
        return """
            {
              "pageBoundaries": {
                "enabled": true,
                "jaccardThreshold": 0.3,
                "spatialThreshold": 0.5,
                "gyroThreshold": 2.0,
                "stabilityFrames": $stabilityFrames,
                "llmExtractPerPage": $llmPerPage,
                "llmExtractFinal": $llmFinal
              }
            }
        """.trimIndent()
    }

    private fun readAllFrames(bytes: ByteArray): List<String> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val frames = mutableListOf<String>()
        while (buffer.remaining() >= Int.SIZE_BYTES) {
            val len = buffer.int
            val payload = ByteArray(len)
            buffer.get(payload)
            frames += payload.toString(Charsets.UTF_8)
        }
        return frames
    }

    private fun eventTypes(frames: List<String>): List<String> =
        frames.mapNotNull { f ->
            EVENT_TYPE_REGEX.find(f)?.groupValues?.get(1)
        }

    private fun orderedIndices(types: List<String>, vararg expectedOrder: String): List<Int> {
        val out = mutableListOf<Int>()
        var searchFrom = 0
        for (target in expectedOrder) {
            val idx = (searchFrom until types.size).firstOrNull { types[it] == target }
                ?: error("Event '$target' not found after index $searchFrom; types=$types")
            out += idx
            searchFrom = idx + 1
        }
        return out
    }

    // ── Test 1: regression guard — disabled path identical to v0.8 ─────

    @Test
    fun `disabled path emits no page events and matches v0_8 sequence`() = runTest {
        val engine = scriptedEngine(listOf(listOf(line("Receipt total"))))
        val extractor = StubOcrLlmExtractor()
        val out = ByteArrayOutputStream()
        val writer = OcrTokenStreamWriter.forTesting(out)
        val dispatcher = OcrRecognitionDispatcher(
            engine = engine,
            scope = this,
            barcodeDetector = null,
            llmExtractor = extractor,
        )
        dispatcher.registerSession(
            sessionId = "sess",
            context = OcrExtractionContext(
                mode = OcrSessionConfig.MODE_RECEIPT,
                outputSchemaJson = """{"type":"object"}""",
            ),
        )
        // Explicitly DO NOT attach page config — default DISABLED.

        dispatcher.submit("sess", 1L, ByteArray(4), 2, 2, OcrEngineConfig(), writer).join()
        dispatcher.finalize("sess", writer)
        writer.close()

        val types = eventTypes(readAllFrames(out.toByteArray()))
        assertFalse("no PAGE_STARTED on disabled path", types.contains("ocr_page_started"))
        assertFalse("no PAGE_FINALIZED on disabled path", types.contains("ocr_page_finalized"))
        assertTrue("must finalize", types.contains("ocr_result_finalized"))
        assertTrue("must terminate", types.contains("done"))
        // The final two events must be RESULT_FINALIZED → DONE, in that order.
        val finalIdx = types.indexOf("ocr_result_finalized")
        assertEquals("done", types[finalIdx + 1])
    }

    // ── Test 2: enabled + single frame → one page ──────────────────────

    @Test
    fun `enabled with single frame emits one page and one finalize`() = runTest {
        val engine = scriptedEngine(listOf(listOf(line("Welcome to page 1"))))
        val extractor = StubOcrLlmExtractor(rawJson = """{"page1":"data"}""")
        val out = ByteArrayOutputStream()
        val writer = OcrTokenStreamWriter.forTesting(out)
        val dispatcher = OcrRecognitionDispatcher(
            engine = engine, scope = this, barcodeDetector = null, llmExtractor = extractor,
        )
        dispatcher.registerSession(
            "sess",
            OcrExtractionContext(OcrSessionConfig.MODE_RECEIPT, """{"type":"object"}"""),
        )
        dispatcher.attachPageBoundariesConfig(
            "sess",
            PageBoundariesConfig.parse(pageOptions(stabilityFrames = 3)),
        )

        dispatcher.submit("sess", 1L, ByteArray(4), 2, 2, OcrEngineConfig(), writer).join()
        dispatcher.finalize("sess", writer)
        writer.close()

        val frames = readAllFrames(out.toByteArray())
        val types = eventTypes(frames)
        // Must see PAGE_STARTED(0), then later PAGE_FINALIZED, then RESULT_FINALIZED, then DONE.
        orderedIndices(types, "ocr_page_started", "ocr_page_finalized", "ocr_result_finalized", "done")
        // Exactly one PAGE_STARTED and one PAGE_FINALIZED.
        assertEquals(1, types.count { it == "ocr_page_started" })
        assertEquals(1, types.count { it == "ocr_page_finalized" })

        val pageStarted = frames.first { it.contains("\"type\":\"ocr_page_started\"") }
        assertTrue("pageIndex 0", pageStarted.contains("\"pageIndex\":0"))
        assertTrue("first page triggerFrameId 0", pageStarted.contains("\"triggerFrameId\":0"))
        val pageFinalized = frames.first { it.contains("\"type\":\"ocr_page_finalized\"") }
        assertTrue("framesContributed 1", pageFinalized.contains("\"framesContributed\":1"))
        assertTrue("lineCount 1", pageFinalized.contains("\"lineCount\":1"))
    }

    // ── Test 3: two pages, stabilityFrames=3, no per-page LLM ──────────

    @Test
    fun `two pages with stabilityFrames 3 emit two pages with eager close`() = runTest {
        // Page A repeats 4 frames; page B differs across 3 frames so the
        // boundary fires inside page B's stretch (at the 3rd different frame).
        val a = listOf(line("foreword chapter prologue alpha"))
        val b = listOf(line("dragon castle wizard inventory"))
        val engine = scriptedEngine(listOf(a, a, a, a, b, b, b))
        val extractor = StubOcrLlmExtractor(rawJson = """{"agg":1}""")
        val out = ByteArrayOutputStream()
        val writer = OcrTokenStreamWriter.forTesting(out)
        val dispatcher = OcrRecognitionDispatcher(
            engine = engine, scope = this, barcodeDetector = null, llmExtractor = extractor,
        )
        dispatcher.registerSession(
            "sess",
            OcrExtractionContext(OcrSessionConfig.MODE_RECEIPT, """{"type":"object"}"""),
        )
        dispatcher.attachPageBoundariesConfig(
            "sess",
            PageBoundariesConfig.parse(pageOptions(stabilityFrames = 3, llmPerPage = false, llmFinal = false)),
        )

        for (frameId in 1L..7L) {
            dispatcher.submit("sess", frameId, ByteArray(4), 2, 2, OcrEngineConfig(), writer).join()
        }
        dispatcher.finalize("sess", writer)
        writer.close()

        val frames = readAllFrames(out.toByteArray())
        val types = eventTypes(frames)

        // Expect two PAGE_STARTED + two PAGE_FINALIZED.
        assertEquals("two PAGE_STARTED", 2, types.count { it == "ocr_page_started" })
        assertEquals("two PAGE_FINALIZED", 2, types.count { it == "ocr_page_finalized" })

        // Order: PAGE_STARTED(0) → ... → PAGE_FINALIZED(0) → PAGE_STARTED(1) → ... → PAGE_FINALIZED(1) → RESULT_FINALIZED → DONE.
        orderedIndices(
            types,
            "ocr_page_started",
            "ocr_page_finalized",
            "ocr_page_started",
            "ocr_page_finalized",
            "ocr_result_finalized",
            "done",
        )

        // First PAGE_STARTED has pageIndex 0 + triggerFrameId 0; second has pageIndex 1 + non-zero triggerFrameId.
        val pageStarteds = frames.filter { it.contains("\"type\":\"ocr_page_started\"") }
        assertEquals(2, pageStarteds.size)
        assertTrue("page 0", pageStarteds[0].contains("\"pageIndex\":0"))
        assertTrue("first trigger frame 0", pageStarteds[0].contains("\"triggerFrameId\":0"))
        assertTrue("page 1", pageStarteds[1].contains("\"pageIndex\":1"))
        // The second PAGE_STARTED's triggerFrameId is the frame that fired the boundary —
        // with stabilityFrames=3 the boundary fires on the 3rd different frame i.e. frameId=7.
        assertTrue(
            "second page trigger frame 7, got=${pageStarteds[1]}",
            pageStarteds[1].contains("\"triggerFrameId\":7"),
        )
    }

    // ── Test 4: per-page LLM extraction → every PAGE_FINALIZED has fullJson ─

    @Test
    fun `llmExtractPerPage true populates fullJson on every PAGE_FINALIZED`() = runTest {
        val a = listOf(line("foreword chapter prologue alpha"))
        val b = listOf(line("dragon castle wizard inventory"))
        val engine = scriptedEngine(listOf(a, a, a, b, b, b))
        val extractor = StubOcrLlmExtractor(rawJson = """{"perpage":true}""")
        val out = ByteArrayOutputStream()
        val writer = OcrTokenStreamWriter.forTesting(out)
        val dispatcher = OcrRecognitionDispatcher(
            engine = engine, scope = this, barcodeDetector = null, llmExtractor = extractor,
        )
        dispatcher.registerSession(
            "sess",
            OcrExtractionContext(OcrSessionConfig.MODE_RECEIPT, """{"type":"object"}"""),
        )
        dispatcher.attachPageBoundariesConfig(
            "sess",
            PageBoundariesConfig.parse(pageOptions(stabilityFrames = 3, llmPerPage = true, llmFinal = true)),
        )

        for (frameId in 1L..6L) {
            dispatcher.submit("sess", frameId, ByteArray(4), 2, 2, OcrEngineConfig(), writer).join()
        }
        dispatcher.finalize("sess", writer)
        writer.close()

        val frames = readAllFrames(out.toByteArray())
        val pageFinalizeds = frames.filter { it.contains("\"type\":\"ocr_page_finalized\"") }
        assertEquals("two PAGE_FINALIZED events", 2, pageFinalizeds.size)
        for (event in pageFinalizeds) {
            assertTrue("each PAGE_FINALIZED must carry non-null fullJson, got: $event",
                event.contains("\"fullJson\""))
            assertTrue("fullJson must be the stub payload, got: $event",
                event.contains("\"perpage\":true"))
        }

        // Extractor must have been called at least once per page + once for aggregate.
        // (2 per-page + 1 aggregate = 3 minimum.)
        assertTrue("extractor called at least 3 times, was ${extractor.calls}", extractor.calls >= 3)
    }

    // ── Test 5: llmExtractFinal=false → RESULT_FINALIZED carries rollup ─

    @Test
    fun `llmExtractFinal false makes RESULT_FINALIZED carry rollup shape`() = runTest {
        val a = listOf(line("foreword chapter prologue alpha"))
        val b = listOf(line("dragon castle wizard inventory"))
        val engine = scriptedEngine(listOf(a, a, a, b, b, b))
        val extractor = StubOcrLlmExtractor(rawJson = """{"agg":1}""")
        val out = ByteArrayOutputStream()
        val writer = OcrTokenStreamWriter.forTesting(out)
        val dispatcher = OcrRecognitionDispatcher(
            engine = engine, scope = this, barcodeDetector = null, llmExtractor = extractor,
        )
        dispatcher.registerSession(
            "sess",
            OcrExtractionContext(OcrSessionConfig.MODE_RECEIPT, """{"type":"object"}"""),
        )
        dispatcher.attachPageBoundariesConfig(
            "sess",
            PageBoundariesConfig.parse(pageOptions(stabilityFrames = 3, llmPerPage = false, llmFinal = false)),
        )

        for (frameId in 1L..6L) {
            dispatcher.submit("sess", frameId, ByteArray(4), 2, 2, OcrEngineConfig(), writer).join()
        }
        dispatcher.finalize("sess", writer)
        writer.close()

        val frames = readAllFrames(out.toByteArray())
        val resultFinal = frames.first { it.contains("\"type\":\"ocr_result_finalized\"") }
        // Rollup shape: "pages":[{"index":0,...},{"index":1,...}]
        assertTrue("rollup includes pages array, was: $resultFinal",
            resultFinal.contains("\\\"pages\\\":["))
        assertTrue("rollup includes page index 0", resultFinal.contains("\\\"index\\\":0"))
        assertTrue("rollup includes page index 1", resultFinal.contains("\\\"index\\\":1"))
        assertTrue("rollup includes lineCount", resultFinal.contains("\\\"lineCount\\\""))
        assertTrue("rollup includes framesContributed", resultFinal.contains("\\\"framesContributed\\\""))
        // No aggregate LLM call was made for the final result.
        assertEquals("no aggregate LLM call when llmExtractFinal=false and llmExtractPerPage=false",
            0, extractor.calls)
    }

    // ── Test 6: single-frame glitch does NOT fire a boundary ───────────

    @Test
    fun `single different frame glitch does not fire boundary`() = runTest {
        val a = listOf(line("foreword chapter prologue alpha"))
        val b = listOf(line("dragon castle wizard inventory"))
        // A,A,A,B,A,A — one B in the middle, only one consecutive different.
        val engine = scriptedEngine(listOf(a, a, a, b, a, a))
        val extractor = StubOcrLlmExtractor(rawJson = """{"agg":1}""")
        val out = ByteArrayOutputStream()
        val writer = OcrTokenStreamWriter.forTesting(out)
        val dispatcher = OcrRecognitionDispatcher(
            engine = engine, scope = this, barcodeDetector = null, llmExtractor = extractor,
        )
        dispatcher.registerSession(
            "sess",
            OcrExtractionContext(OcrSessionConfig.MODE_RECEIPT, """{"type":"object"}"""),
        )
        dispatcher.attachPageBoundariesConfig(
            "sess",
            PageBoundariesConfig.parse(pageOptions(stabilityFrames = 3, llmPerPage = false, llmFinal = false)),
        )

        for (frameId in 1L..6L) {
            dispatcher.submit("sess", frameId, ByteArray(4), 2, 2, OcrEngineConfig(), writer).join()
        }
        dispatcher.finalize("sess", writer)
        writer.close()

        val types = eventTypes(readAllFrames(out.toByteArray()))
        assertEquals("only one PAGE_STARTED — glitch should not split pages",
            1, types.count { it == "ocr_page_started" })
        assertEquals("only one PAGE_FINALIZED", 1, types.count { it == "ocr_page_finalized" })
    }

    companion object {
        private val EVENT_TYPE_REGEX = Regex("\"type\":\"([^\"]+)\"")
    }
}
