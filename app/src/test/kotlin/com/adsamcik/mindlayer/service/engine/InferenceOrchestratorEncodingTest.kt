package com.adsamcik.mindlayer.service.engine

import com.adsamcik.mindlayer.service.MindlayerMlService
import com.adsamcik.mindlayer.service.ipc.SharedMemoryPool
import io.mockk.mockk
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class InferenceOrchestratorEncodingTest {

    private val orchestrator = InferenceOrchestrator(
        service = mockk<MindlayerMlService>(relaxed = true),
        sessionManager = mockk<SessionManager>(relaxed = true),
        sharedMemoryPool = mockk<SharedMemoryPool>(relaxed = true),
    )

    @Test
    fun `encodeToolArguments returns empty object for null and blank strings`() {
        assertEquals("{}", encodeToolArguments(null))
        assertEquals("{}", encodeToolArguments("   \n\t  "))
    }

    @Test
    fun `encodeToolArguments preserves already encoded json strings`() {
        assertEquals("""{"x":1}""", encodeToolArguments("  {\"x\":1}  "))
        assertEquals("""[1,2,3]""", encodeToolArguments("\n[1,2,3]\t"))
    }

    @Test
    fun `encodeToolArguments serializes json objects and maps`() {
        assertEquals(
            "test",
            JSONObject(encodeToolArguments(JSONObject().put("q", "test"))).getString("q"),
        )
        assertEquals(
            2,
            JSONObject(encodeToolArguments(mapOf("count" to 2))).getInt("count"),
        )
    }

    @Test
    fun `encodeToolArguments falls back to empty object for opaque non-json objects`() {
        val opaque = object {
            override fun toString(): String = "not-json"
        }

        assertEquals("{}", encodeToolArguments(opaque))
    }

    @Test
    fun `encodeToolArguments handles json primitive values without throwing`() {
        assertEquals("42", encodeToolArguments(42))
        assertEquals("true", encodeToolArguments(true))
    }

    private fun encodeToolArguments(arguments: Any?): String {
        val method = InferenceOrchestrator::class.java.getDeclaredMethod(
            "encodeToolArguments",
            Any::class.java,
        )
        method.isAccessible = true
        return method.invoke(orchestrator, arguments) as String
    }
}
