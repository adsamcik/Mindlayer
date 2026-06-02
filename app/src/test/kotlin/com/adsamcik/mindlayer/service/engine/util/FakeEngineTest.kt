package com.adsamcik.mindlayer.service.engine.util

import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.LiteRtLmJniException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeEngineTest {

    @Test
    fun `first createConversation succeeds`() {
        val fake = FakeEngine.create()
        fake.engine.createConversation(ConversationConfig())
        assertEquals(1, fake.openCount)
        assertEquals(1, fake.activeCount)
        assertEquals(0, fake.closeCount)
    }

    @Test
    fun `second createConversation without close throws LiteRtLmJniException`() {
        val fake = FakeEngine.create()
        fake.engine.createConversation(ConversationConfig())
        val ex = assertThrows(LiteRtLmJniException::class.java) {
            fake.engine.createConversation(ConversationConfig())
        }
        assertTrue(
            "expected the FAILED_PRECONDITION message verbatim, got: ${ex.message}",
            ex.message?.contains("FAILED_PRECONDITION") == true &&
                ex.message?.contains("Only one session is supported") == true,
        )
        assertEquals(1, fake.openCount)
        assertEquals(1, fake.activeCount)
    }

    @Test
    fun `close releases the slot, allowing another createConversation`() {
        val fake = FakeEngine.create()
        val first = fake.engine.createConversation(ConversationConfig())
        first.close()
        assertEquals(1, fake.closeCount)
        assertEquals(0, fake.activeCount)
        val second = fake.engine.createConversation(ConversationConfig())
        assertNotSame(first, second)
        assertEquals(2, fake.openCount)
        assertEquals(1, fake.activeCount)
    }

    @Test
    fun `redundant close is a no-op`() {
        val fake = FakeEngine.create()
        val conv = fake.engine.createConversation(ConversationConfig())
        conv.close()
        conv.close()
        assertEquals(1, fake.closeCount)
        assertEquals(0, fake.activeCount)
    }

    @Test
    fun `many sequential create-close cycles each get fresh slot`() {
        val fake = FakeEngine.create()
        for (i in 1..10) {
            val c = fake.engine.createConversation(ConversationConfig())
            c.close()
        }
        assertEquals(10, fake.openCount)
        assertEquals(10, fake.closeCount)
        assertEquals(0, fake.activeCount)
    }
}
