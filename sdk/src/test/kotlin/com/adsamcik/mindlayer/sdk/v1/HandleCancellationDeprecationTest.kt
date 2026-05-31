package com.adsamcik.mindlayer.sdk.v1

import com.adsamcik.mindlayer.sdk.InferenceHandleImpl
import com.adsamcik.mindlayer.sdk.MindlayerEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the C2 cancellation deprecation (D2.3a). [InferenceHandle.cancel] and
 * [InferenceHandle.isCancelled] are now `@Deprecated(WARNING)` — Spike-E §8.4
 * routes cancellation through structured concurrency — but they remain
 * functional so [Conversation] and existing tests still compile and behave.
 * This test pins that the deprecated path still tears down exactly once.
 */
@Suppress("DEPRECATION")
class HandleCancellationDeprecationTest {

    private val noEvents: Flow<MindlayerEvent> = emptyFlow()

    @Test
    fun `deprecated cancel still flips state and fires callback once`() = runTest {
        val handle = InferenceHandleImpl(requestId = "req-1", events = noEvents)
        var cancels = 0
        handle.setCancelCallback { cancels++ }

        assertFalse(handle.isCancelled)

        handle.cancel()
        assertTrue(handle.isCancelled)
        assertEquals(1, cancels)

        // Idempotent: a second cancel must not re-fire the teardown.
        handle.cancel()
        assertEquals(1, cancels)
    }
}
