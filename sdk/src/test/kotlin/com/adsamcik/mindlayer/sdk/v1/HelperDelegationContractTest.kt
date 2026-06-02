package com.adsamcik.mindlayer.sdk.v1

import android.graphics.Bitmap
import com.adsamcik.mindlayer.sdk.EmbeddingHandle
import com.adsamcik.mindlayer.sdk.EmbeddingItem
import com.adsamcik.mindlayer.sdk.EmbeddingRequest
import com.adsamcik.mindlayer.sdk.EmbeddingResultItem
import com.adsamcik.mindlayer.sdk.EmbeddingTask
import com.adsamcik.mindlayer.sdk.EmbeddingVector
import com.adsamcik.mindlayer.sdk.InferenceHandle
import com.adsamcik.mindlayer.sdk.InferenceRequest
import com.adsamcik.mindlayer.sdk.JsonSchema
import com.adsamcik.mindlayer.sdk.Metrics
import com.adsamcik.mindlayer.sdk.MindlayerHelpers
import com.adsamcik.mindlayer.sdk.MindlayerSession
import com.adsamcik.mindlayer.sdk.OcrHandle
import com.adsamcik.mindlayer.sdk.OcrLine
import com.adsamcik.mindlayer.sdk.OcrProfile
import com.adsamcik.mindlayer.sdk.OcrRequest
import com.adsamcik.mindlayer.sdk.OcrResult
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Contract tests for the [MindlayerHelpers] convenience delegations (Spike-E
 * §2). Each test proves a helper is a *pure delegate*: it invokes the canonical
 * builder with exactly the recorded intent and routes through the matching
 * terminal, adding no behaviour of its own.
 *
 * Strategy: `mockk` the abstract base, stub only the abstract canonical method
 * (`infer` / `ocr` / `embed` / `openSession`) capturing its builder lambda, and
 * run the real `final` helper via `callOriginal()`. Replaying the captured
 * lambda onto a fresh builder lets us assert the recorded request fields.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HelperDelegationContractTest {

    private fun helpers(): MindlayerHelpers = mockk(relaxed = false)

    private fun textHandle(result: String): InferenceHandle.Text =
        mockk<InferenceHandle.Text>().also { coEvery { it.awaitText() } returns result }

    private fun ocrHandle(result: OcrResult): OcrHandle.OneShot =
        mockk<OcrHandle.OneShot>().also { coEvery { it.awaitResult() } returns result }

    @Test
    fun `ask delegates to infer with ephemeral session and text`() = runTest {
        val sut = helpers()
        val build = slot<InferenceRequest.Builder.() -> Unit>()
        coEvery { sut.infer(capture(build)) } returns textHandle("answer")
        coEvery { sut.ask(any(), any()) } coAnswers { callOriginal() }

        val result = sut.ask("why is the sky blue?")

        assertEquals("answer", result)
        val b = InferenceRequest.Builder().apply(build.captured)
        assertEquals("why is the sky blue?", b.promptText)
        assertNull("ephemeral session must not pin an id", b.sessionId)
        assertTrue("ephemeral session config must be recorded", b.sessionConfigure != null)
        assertTrue(b.imageInputs.isEmpty())
    }

    @Test
    fun `describe delegates to infer with image input`() = runTest {
        val sut = helpers()
        val bitmap = mockk<Bitmap>()
        val build = slot<InferenceRequest.Builder.() -> Unit>()
        coEvery { sut.infer(capture(build)) } returns textHandle("a cat")
        coEvery { sut.describe(any(), any(), any()) } coAnswers { callOriginal() }

        val result = sut.describe("what is this?", bitmap)

        assertEquals("a cat", result)
        val b = InferenceRequest.Builder().apply(build.captured)
        assertEquals("what is this?", b.promptText)
        assertEquals(1, b.imageInputs.size)
    }

    @Test
    fun `transcribe delegates to infer with audio input`() = runTest {
        val sut = helpers()
        val audio = File("clip.wav")
        val build = slot<InferenceRequest.Builder.() -> Unit>()
        coEvery { sut.infer(capture(build)) } returns textHandle("hello world")
        coEvery { sut.transcribe(any<String>(), any(), any()) } coAnswers { callOriginal() }

        val result = sut.transcribe("transcribe this", audio)

        assertEquals("hello world", result)
        val b = InferenceRequest.Builder().apply(build.captured)
        assertEquals("transcribe this", b.promptText)
        assertSame(audio, b.audioFile)
    }

    @Test
    fun `transcribe(audio, language) delegates with canonical Gemma ASR prompt`() = runTest {
        val sut = helpers()
        val audio = File("clip.wav")
        val build = slot<InferenceRequest.Builder.() -> Unit>()
        coEvery { sut.infer(capture(build)) } returns textHandle("transcribed")
        // Both overloads must callOriginal: the (audio, language) overload
        // delegates to (prompt, audio, configure), which delegates to infer.
        coEvery { sut.transcribe(any<String>(), any(), any()) } coAnswers { callOriginal() }
        coEvery {
            sut.transcribe(any<File>(), any<String>(), any())
        } coAnswers { callOriginal() }

        val result = sut.transcribe(audio = audio, language = "English")

        assertEquals("transcribed", result)
        val b = InferenceRequest.Builder().apply(build.captured)
        val expectedPrompt =
            com.adsamcik.mindlayer.sdk.GemmaAudioPrompts.transcriptionPrompt("English")
        assertEquals(expectedPrompt, b.promptText)
        assertSame(audio, b.audioFile)
    }

    @Test
    fun `transcribe(audio) without language uses original-language ASR prompt`() = runTest {
        val sut = helpers()
        val audio = File("clip.wav")
        val build = slot<InferenceRequest.Builder.() -> Unit>()
        coEvery { sut.infer(capture(build)) } returns textHandle("transcribed")
        coEvery { sut.transcribe(any<String>(), any(), any()) } coAnswers { callOriginal() }
        // any<String>() matches the nullable language parameter, including
        // the null we pass here — mockk's runtime matcher accepts null
        // values for nullable Kotlin parameters even with a non-null type
        // argument (which is required at compile time because reified type
        // parameters must be subtype of Any).
        coEvery {
            sut.transcribe(any<File>(), any<String>(), any())
        } coAnswers { callOriginal() }

        sut.transcribe(audio)

        val b = InferenceRequest.Builder().apply(build.captured)
        val expectedPrompt =
            com.adsamcik.mindlayer.sdk.GemmaAudioPrompts.transcriptionPrompt(null)
        assertEquals(expectedPrompt, b.promptText)
        assertSame(audio, b.audioFile)
    }

    @Test
    fun `extractJson delegates to infer with json output and optional image`() = runTest {
        val sut = helpers()
        val bitmap = mockk<Bitmap>()
        val schema = mockk<JsonSchema>()
        val expected = JsonObject(mapOf("k" to JsonPrimitive("v")))
        val handle = mockk<InferenceHandle.Structured>()
        coEvery { handle.awaitJson() } returns expected
        val build = slot<InferenceRequest.Builder.() -> Unit>()
        coEvery { sut.infer(capture(build)) } returns handle
        coEvery { sut.extractJson(any(), any(), any(), any(), any()) } coAnswers { callOriginal() }

        val result = sut.extractJson("pull fields", schema, image = bitmap)

        assertEquals(expected, result)
        val b = InferenceRequest.Builder().apply(build.captured)
        assertEquals("pull fields", b.promptText)
        assertEquals(1, b.imageInputs.size)
        assertTrue(b.outputMode is InferenceRequest.OutputMode.Json)
    }

    @Test
    fun `vector delegates to embed single`() = runTest {
        val sut = helpers()
        val vec = floatArrayOf(0.1f, 0.2f)
        val handle = mockk<EmbeddingHandle.Single>()
        coEvery { handle.awaitVector() } returns vec
        val build = slot<EmbeddingRequest.Builder.() -> Unit>()
        coEvery { sut.embed(capture(build)) } returns handle
        coEvery { sut.vector(any(), any()) } coAnswers { callOriginal() }

        val result = sut.vector("hello", EmbeddingTask.RetrievalQuery)

        assertSame(vec, result)
        val b = EmbeddingRequest.Builder().apply(build.captured)
        assertEquals("hello", b.singleItem?.text)
        assertEquals(EmbeddingTask.RetrievalQuery, b.singleItem?.task)
        assertNull(b.items)
    }

    @Test
    fun `vectors delegates to embed batch`() = runTest {
        val sut = helpers()
        val items = listOf(EmbeddingItem("a"), EmbeddingItem("b"))
        val out = listOf(EmbeddingResultItem(null, EmbeddingVector(floatArrayOf(1f))))
        val handle = mockk<EmbeddingHandle.Batch>()
        coEvery { handle.awaitVectors() } returns out
        val build = slot<EmbeddingRequest.Builder.() -> Unit>()
        coEvery { sut.embed(capture(build)) } returns handle
        coEvery { sut.vectors(any()) } coAnswers { callOriginal() }

        val result = sut.vectors(items)

        assertSame(out, result)
        val b = EmbeddingRequest.Builder().apply(build.captured)
        assertEquals(items, b.items)
        assertNull(b.singleItem)
    }

    @Test
    fun `readText bitmap delegates to ocr and joins lines`() = runTest {
        val sut = helpers()
        val bitmap = mockk<Bitmap>()
        val handle = ocrHandle(
            OcrResult(
                lines = listOf(OcrLine("line one"), OcrLine("line two")),
                fullJson = JsonObject(emptyMap()),
                extractionJson = null,
                metrics = Metrics.EMPTY,
            ),
        )
        val build = slot<OcrRequest.Builder.() -> Unit>()
        coEvery { sut.ocr(capture(build)) } returns handle
        coEvery { sut.readText(any<Bitmap>(), any()) } coAnswers { callOriginal() }

        val result = sut.readText(bitmap, OcrProfile.GeneralDocument)

        assertEquals("line one\nline two", result)
        val b = OcrRequest.Builder().apply(build.captured)
        assertTrue(b.image != null)
        assertEquals(OcrProfile.GeneralDocument, b.profile)
    }

    @Test
    fun `readText bytes delegates to ocr with bytes image`() = runTest {
        val sut = helpers()
        val handle = ocrHandle(
            OcrResult(
                lines = listOf(OcrLine("scanned")),
                fullJson = JsonObject(emptyMap()),
                extractionJson = null,
                metrics = Metrics.EMPTY,
            ),
        )
        val build = slot<OcrRequest.Builder.() -> Unit>()
        coEvery { sut.ocr(capture(build)) } returns handle
        coEvery { sut.readText(any<ByteArray>(), any(), any()) } coAnswers { callOriginal() }

        val result = sut.readText(byteArrayOf(1, 2, 3), "image/png")

        assertEquals("scanned", result)
        val b = OcrRequest.Builder().apply(build.captured)
        assertTrue(b.image != null)
    }

    @Test
    fun `readStructuredJson delegates to ocr with llm extraction`() = runTest {
        val sut = helpers()
        val bitmap = mockk<Bitmap>()
        val schema = mockk<JsonSchema>()
        val extraction = JsonObject(mapOf("total" to JsonPrimitive(42)))
        val handle = ocrHandle(
            OcrResult(
                lines = emptyList(),
                fullJson = JsonObject(emptyMap()),
                extractionJson = extraction,
                metrics = Metrics.EMPTY,
            ),
        )
        val build = slot<OcrRequest.Builder.() -> Unit>()
        coEvery { sut.ocr(capture(build)) } returns handle
        coEvery { sut.readStructuredJson(any(), any(), any()) } coAnswers { callOriginal() }

        val result = sut.readStructuredJson(bitmap, schema)

        assertEquals(extraction, result)
        val b = OcrRequest.Builder().apply(build.captured)
        assertTrue("extraction schema must be recorded", b.extractionSchema != null)
    }

    @Test
    fun `withSession opens a session and closes it`() = runTest {
        val sut = helpers()
        val session = mockk<MindlayerSession>(relaxed = true)
        coEvery { sut.openSession(any()) } returns session
        coEvery { sut.withSession<String>(any(), any()) } coAnswers { callOriginal() }

        val result = sut.withSession(configure = {}) { "ran in session" }

        assertEquals("ran in session", result)
        verify { session.close() }
    }
}
