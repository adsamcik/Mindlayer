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
 * Guards the C3 cancellation cleanup (D3.2). The deprecated public
 * `InferenceHandle.cancel()` / `isCancelled` were removed — cancellation now
 * flows through structured concurrency. The SDK still carries an internal
 * non-suspend teardown ([InferenceHandleImpl.cancelSync]) used by cleanup
 * paths such as [com.adsamcik.mindlayer.sdk.Conversation.close]. This test
 * pins that the surviving sync path flips state and fires its callback once.
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
}
