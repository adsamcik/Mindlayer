package com.adsamcik.mindlayer.service.engine

import app.cash.turbine.test
import com.adsamcik.mindlayer.EmbeddingTask
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EmbeddingEngineTest {

    private lateinit var context: android.content.Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        clean(context.filesDir)
        clean(context.cacheDir)
        ShadowLog.clear()
    }

    @Test
    fun `first embed call blocks until backend initialize completes`() = runTest {
        installModel()
        val gate = CompletableDeferred<Unit>()
        val backend = configuredBackend(initGate = gate)
        val engine = EmbeddingEngine(context, backendFactory = { backend })

        val result = async { engine.embed("hello") }
        runCurrent()

        assertFalse(result.isCompleted)
        gate.complete(Unit)
        runCurrent()

        assertEquals("embedding-test", result.await().modelId)
        coVerify(exactly = 1) { backend.initialize(any(), any()) }
    }

    @Test
    fun `cached init failure rethrows without reinitializing`() = runTest {
        installModel()
        val backend = mockk<EmbeddingBackend>()
        every { backend.isInitialized } returns false
        coEvery { backend.initialize(any(), any()) } throws IllegalStateException("native failed with user text")
        val engine = EmbeddingEngine(context, backendFactory = { backend })

        val first = runCatching { engine.embed("hello") }
        val second = runCatching { engine.embed("hello") }

        assertTrue(first.exceptionOrNull() is IllegalStateException)
        assertTrue(second.exceptionOrNull() is IllegalStateException)
        coVerify(exactly = 1) { backend.initialize(any(), any()) }
    }

    @Test
    fun `mutex serializes parallel embed calls`() = runTest {
        installModel()
        val firstEmbedStarted = CompletableDeferred<Unit>()
        val firstEmbedRelease = CompletableDeferred<Unit>()
        var embedCalls = 0
        val backend = configuredBackend()
        coEvery { backend.embed(any(), any(), any()) } coAnswers {
            embedCalls += 1
            if (embedCalls == 1) {
                firstEmbedStarted.complete(Unit)
                firstEmbedRelease.await()
            }
            floatArrayOf(1f, 0f)
        }
        val engine = EmbeddingEngine(context, backendFactory = { backend })

        val first = async { engine.embed("one") }
        val second = async { engine.embed("two") }
        firstEmbedStarted.await()
        runCurrent()

        assertEquals(1, embedCalls)
        firstEmbedRelease.complete(Unit)
        first.await()
        second.await()

        assertEquals(2, embedCalls)
    }

    @Test
    fun `state flow transitions Idle Initializing Ready`() = runTest {
        installModel()
        val gate = CompletableDeferred<Unit>()
        val backend = configuredBackend(initGate = gate)
        val engine = EmbeddingEngine(context, backendFactory = { backend })

        engine.state.test {
            assertTrue(awaitItem() is EmbeddingEngineState.Idle)
            val job = launch { engine.initialize() }
            assertTrue(awaitItem() is EmbeddingEngineState.Initializing)
            gate.complete(Unit)
            assertTrue(awaitItem() is EmbeddingEngineState.Ready)
            job.join()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `state flow transitions Idle Initializing Failed on init throw`() = runTest {
        installModel()
        val backend = mockk<EmbeddingBackend>()
        every { backend.isInitialized } returns false
        coEvery { backend.initialize(any(), any()) } throws IllegalStateException("native prompt leak")
        val engine = EmbeddingEngine(context, backendFactory = { backend })

        engine.state.test {
            assertTrue(awaitItem() is EmbeddingEngineState.Idle)
            val job = launch { runCatching { engine.initialize() } }
            assertTrue(awaitItem() is EmbeddingEngineState.Initializing)
            assertTrue(awaitItem() is EmbeddingEngineState.Failed)
            job.join()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `EmbeddingOutput string equality and hash redact vector contents`() {
        val a = EmbeddingOutput(floatArrayOf(0.12345f, 9.8765f), 2, "m", 3, false, "CPU", 10)
        val b = EmbeddingOutput(floatArrayOf(42f, 99f), 2, "m", 3, false, "CPU", 10)

        assertFalse(a.toString().contains("0.12345"))
        assertFalse(a.toString().contains("9.8765"))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `embedBatch returns results in submission order`() = runTest {
        installModel()
        val backend = configuredBackend()
        val texts = mutableListOf<String>()
        every { backend.tokenize(capture(texts), any()) } returns intArrayOf(1)
        coEvery { backend.embed(any(), any(), any()) } returnsMany listOf(
            floatArrayOf(1f, 0f),
            floatArrayOf(0f, 1f),
        )
        val engine = EmbeddingEngine(context, backendFactory = { backend })

        val results = engine.embedBatch(listOf("first", "second"))

        assertEquals(2, results.size)
        assertEquals(1f, results[0].vector[0], 0.0001f)
        assertEquals(0f, results[1].vector[0], 0.0001f)
        assertTrue(texts[0].endsWith("first"))
        assertTrue(texts[1].endsWith("second"))
    }

    @Test
    fun `unloadForMemoryPressure shuts backend down and flips state to Idle`() = runTest {
        installModel()
        val backend = configuredBackend()
        val engine = EmbeddingEngine(context, backendFactory = { backend })
        engine.initialize()

        engine.unloadForMemoryPressure()

        assertTrue(engine.state.value is EmbeddingEngineState.Idle)
        coVerify(exactly = 1) { backend.shutdown() }
    }

    @Test
    fun `task parameter prepends correct prefix before tokenize`() = runTest {
        installModel()
        val backend = configuredBackend()
        val captured = slot<String>()
        every { backend.tokenize(capture(captured), any()) } returns intArrayOf(1)
        val engine = EmbeddingEngine(context, backendFactory = { backend })

        engine.embed("where is it", task = EmbeddingTask.RETRIEVAL_QUERY)

        assertEquals("task: search result | query: where is it", captured.captured)
    }

    @Test
    fun `empty string input is rejected before backend work`() = runTest {
        val backend = mockk<EmbeddingBackend>(relaxed = true)
        val engine = EmbeddingEngine(context, backendFactory = { backend })

        val result = runCatching { engine.embed("") }

        assertEquals("Empty text not supported for embedding", result.exceptionOrNull()?.message)
        coVerify(exactly = 0) { backend.initialize(any(), any()) }
    }

    @Test
    fun `vector contents never appear in MindlayerLog output`() = runTest {
        installModel()
        val backend = configuredBackend()
        coEvery { backend.embed(any(), any(), any()) } returns floatArrayOf(0.12345f, 0.98765f)
        val engine = EmbeddingEngine(context, backendFactory = { backend })

        engine.embed("private")

        val logs = ShadowLog.getLogs().joinToString("\n") { it.msg }
        assertFalse(logs.contains("0.12345"))
        assertFalse(logs.contains("0.98765"))
        assertFalse(logs.contains("private"))
    }

    private fun configuredBackend(initGate: CompletableDeferred<Unit>? = null): EmbeddingBackend {
        val backend = mockk<EmbeddingBackend>()
        var current: EmbeddingModelInfo? = null
        every { backend.isInitialized } answers { current != null }
        every { backend.currentModel } answers { current }
        every { backend.activeBackend } returns "CPU"
        coEvery { backend.initialize(any(), any()) } coAnswers {
            initGate?.await()
            current = firstArg()
        }
        every { backend.tokenize(any(), any()) } returns intArrayOf(1, 2)
        coEvery { backend.embed(any(), any(), any()) } returns floatArrayOf(3f, 4f)
        coEvery { backend.shutdown() } coAnswers { current = null }
        return backend
    }

    private fun installModel() {
        File(context.filesDir, "embedding-test.tflite").writeText("model")
        File(context.filesDir, "sentencepiece.model").writeText("tok")
    }

    private fun clean(dir: File) {
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }
}
