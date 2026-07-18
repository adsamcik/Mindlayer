package com.adsamcik.mindlayer.service.modeldelivery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.mindlayer.service.health.MlHealthRecorder
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ModelRuntimeControlTest {
    @Test
    fun `planned chat process exit records clean shutdown before acknowledgement`() = runTest {
        val events = mutableListOf<String>()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val healthDir = File(context.filesDir, "planned-exit-health-test").apply {
            deleteRecursively()
            mkdirs()
        }
        var nowMs = 100L
        val healthRecorder = MlHealthRecorder(healthDir) { nowMs }
        healthRecorder.recordHealthyBoot()
        val controller = DefaultLiveModelRuntimeController(
            quiesceAction = {
                events += "drained"
                LiveRuntimeReleaseResult.RequiresProcessExit(pid = 42)
            },
            retryOcrActivation = {},
            recordCleanShutdownBeforeProcessExit = {
                events += "clean-shutdown"
                nowMs = 101L
                healthRecorder.recordCleanShutdown()
            },
        )

        try {
            val result = controller.quiesce(ModelFamily.CHAT)
            events += "acknowledged"
            nowMs = 200L
            val restartedRecorder = MlHealthRecorder(healthDir) { nowMs }
            restartedRecorder.recordHealthyBoot()

            assertEquals(LiveRuntimeReleaseResult.RequiresProcessExit(pid = 42), result)
            assertEquals(listOf("drained", "clean-shutdown", "acknowledged"), events)
            assertEquals(0, restartedRecorder.peek().deathCount)
        } finally {
            healthDir.deleteRecursively()
        }
    }

    @Test
    fun `clean shutdown recording failure blocks planned process exit acknowledgement`() = runTest {
        val events = mutableListOf<String>()
        val controller = DefaultLiveModelRuntimeController(
            quiesceAction = {
                events += "drained"
                LiveRuntimeReleaseResult.RequiresProcessExit(pid = 42)
            },
            retryOcrActivation = {},
            recordCleanShutdownBeforeProcessExit = {
                events += "clean-shutdown"
                throw IllegalStateException("private filesystem detail")
            },
        )

        val result = runCatching { controller.quiesce(ModelFamily.CHAT) }

        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals(listOf("drained", "clean-shutdown"), events)
    }
}
