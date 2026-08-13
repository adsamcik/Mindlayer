package com.adsamcik.mindlayer.sdk.v1

import com.adsamcik.mindlayer.sdk.InferenceHandleImpl
import com.adsamcik.mindlayer.sdk.InferenceEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards explicit and cleanup-path cancellation. Both paths are idempotent and
 * share one state flag, so native cancellation can never be sent repeatedly.
 */
class HandleCancellationDeprecationTest {

    private val noEvents: Flow<InferenceEvent> = emptyFlow()

    @Test
    fun `sync cancel flips state and fires callback once`() = runTest {
        val handle = InferenceHandleImpl(requestId = "req-1", events = noEvents)
        var cancels = 0
        handle.setSyncCancelCallback { cancels++ }

        assertFalse(handle.isCancelled)

        handle.cancelSync()
        assertTrue(handle.isCancelled)
        assertEquals(1, cancels)

        // Idempotent: a second cancel must not re-fire the teardown.
        handle.cancelSync()
        assertEquals(1, cancels)
    }

    @Test
    fun `public cancel returns detailed result and fires once`() = runTest {
        val handle = InferenceHandleImpl(requestId = "req-2", events = noEvents)
        var cancels = 0
        handle.setCancelCallback {
            cancels++
            com.adsamcik.mindlayer.CancelResult(
                outcome = com.adsamcik.mindlayer.CancelResult.CANCELLED,
            )
        }

        val first = handle.cancel()
        val second = handle.cancel()

        assertEquals(com.adsamcik.mindlayer.CancelResult.CANCELLED, first.outcome)
        assertEquals(com.adsamcik.mindlayer.CancelResult.ALREADY_FINISHED, second.outcome)
        assertEquals(1, cancels)
    }
}
