package com.adsamcik.mindlayer.service.engine.util

import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.LiteRtLmJniException
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Test double that mirrors LiteRT-LM 0.12.0's **"one Conversation per
 * Engine at a time"** native invariant. Loose `mockk<Engine>(relaxed = true)`
 * silently accepts unlimited `createConversation` calls — that gap let the
 * production code grow a `maxSessions > 1` design that crashes on the
 * second real `createConversation`. This fake enforces the invariant so
 * tests fail fast on the same precondition the native engine throws.
 *
 * The Engine and Conversation instances are mockk-relaxed under the hood,
 * so tests can stub further methods on them via the usual
 * `every { conv.someMethod() } returns ...` pattern. The only behaviour
 * this fake pre-wires is `createConversation` (one-active-at-a-time) and
 * `Conversation.close()` (releases the slot, counted by [closeCount]).
 *
 * # Usage
 *
 * ```kotlin
 * val fake = FakeEngine.create()
 * val conv = fake.engine.createConversation(ConversationConfig())
 * // ... use conv as if it were a real LiteRT-LM Conversation mock ...
 * conv.close()
 * assertEquals(1, fake.openCount)
 * assertEquals(1, fake.closeCount)
 * ```
 *
 * The observable counters ([openCount], [closeCount], [activeCount])
 * make it cheap for tests to assert lifecycle balance without spelunking
 * through mockk verification DSL.
 */
class FakeEngine private constructor() {

    private val openings = AtomicInteger(0)
    private val closes = AtomicInteger(0)
    private val active = AtomicReference<Conversation?>(null)

    /** Total `createConversation` calls that successfully returned. */
    val openCount: Int get() = openings.get()

    /** Total `Conversation.close()` calls that actually released the slot. */
    val closeCount: Int get() = closes.get()

    /** 1 if a Conversation is currently active; 0 otherwise. */
    val activeCount: Int get() = if (active.get() != null) 1 else 0

    /** The mockk-relaxed [Engine] handle for tests to pass into production code. */
    val engine: Engine = mockk(relaxed = true)

    private fun wire() {
        every { engine.createConversation(any()) } answers {
            // LiteRT-LM 0.12.0 throws FAILED_PRECONDITION on the second
            // createConversation against the same Engine without an
            // intervening Conversation.close(). Mirror the native error
            // verbatim so test code can match on .message.
            if (active.get() != null) {
                throw LiteRtLmJniException(
                    "Failed to create conversation: FAILED_PRECONDITION: " +
                        "A session already exists. Only one session is supported at a time. " +
                        "Please delete the existing session before creating a new one."
                )
            }
            val conv = mockk<Conversation>(relaxed = true)
            every { conv.close() } answers {
                // CAS so a redundant close() is a silent no-op, matching
                // native idempotence on already-closed handles.
                if (active.compareAndSet(conv, null)) {
                    closes.incrementAndGet()
                }
            }
            check(active.compareAndSet(null, conv)) {
                "FakeEngine race: another conversation claimed the slot mid-create"
            }
            openings.incrementAndGet()
            conv
        }
    }

    companion object {
        /** Construct a fresh FakeEngine with no active Conversation. */
        fun create(): FakeEngine = FakeEngine().apply { wire() }
    }
}
