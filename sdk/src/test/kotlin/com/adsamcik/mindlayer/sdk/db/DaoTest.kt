package com.adsamcik.mindlayer.sdk.db

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DaoTest {

    private lateinit var db: MindlayerDatabase
    private lateinit var conversationDao: ConversationDao
    private lateinit var turnDao: TurnDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MindlayerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        conversationDao = db.conversationDao()
        turnDao = db.turnDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun makeConversation(
        id: String = "conv-1",
        systemPrompt: String? = "You are helpful.",
        backend: String = "GPU",
        maxTokens: Int = 4096,
    ) = ConversationEntity(
        conversationId = id,
        systemPrompt = systemPrompt,
        backend = backend,
        maxTokens = maxTokens,
        samplerConfigJson = null,
        toolsJson = null,
        extraContextJson = null,
        tokenEstimateTotal = 0,
        lastStableSeq = 0,
        createdAtMs = 1000L,
        updatedAtMs = 1000L,
    )

    private fun makeTurn(
        turnId: String = "turn-1",
        conversationId: String = "conv-1",
        seq: Int = 0,
        role: String = TurnRole.USER,
        state: String = TurnState.PENDING,
        text: String? = "Hello",
        tokens: Int = 5,
        startedAtMs: Long = 2000L,
        completedAtMs: Long? = null,
    ) = TurnEntity(
        turnId = turnId,
        conversationId = conversationId,
        seq = seq,
        role = role,
        state = state,
        textContent = text,
        tokenEstimate = tokens,
        startedAtMs = startedAtMs,
        completedAtMs = completedAtMs,
    )

    private fun makePart(
        partId: String = "part-1",
        turnId: String = "turn-1",
        ordinal: Int = 0,
        kind: String = PartKind.TEXT,
        text: String? = "chunk",
    ) = TurnPartEntity(
        partId = partId,
        turnId = turnId,
        ordinal = ordinal,
        kind = kind,
        text = text,
        uriString = null,
        mimeType = null,
        metadataJson = null,
    )

    // ═══════════════════════════════════════════════════════════════════
    //  ConversationDao tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `insert and retrieve conversation`() = runTest {
        val conv = makeConversation()
        conversationDao.upsert(conv)

        val loaded = conversationDao.get("conv-1")
        assertNotNull(loaded)
        assertEquals("conv-1", loaded!!.conversationId)
        assertEquals("You are helpful.", loaded.systemPrompt)
        assertEquals("GPU", loaded.backend)
        assertEquals(4096, loaded.maxTokens)
    }

    @Test
    fun `upsert updates existing conversation fields`() = runTest {
        conversationDao.upsert(makeConversation())

        val updated = makeConversation().copy(
            systemPrompt = "New prompt",
            maxTokens = 8192,
            updatedAtMs = 5000L,
        )
        conversationDao.upsert(updated)

        val loaded = conversationDao.get("conv-1")!!
        assertEquals("New prompt", loaded.systemPrompt)
        assertEquals(8192, loaded.maxTokens)
        assertEquals(5000L, loaded.updatedAtMs)
    }

    @Test
    fun `get returns null for nonexistent conversation`() = runTest {
        assertNull(conversationDao.get("does-not-exist"))
    }

    @Test
    fun `delete conversation removes it`() = runTest {
        conversationDao.upsert(makeConversation())
        conversationDao.delete("conv-1")
        assertNull(conversationDao.get("conv-1"))
    }

    @Test
    fun `delete conversation cascades to turns`() = runTest {
        conversationDao.upsert(makeConversation())
        turnDao.upsert(makeTurn(turnId = "t1", seq = 0))
        turnDao.upsert(makeTurn(turnId = "t2", seq = 1))

        conversationDao.delete("conv-1")

        assertNull(turnDao.get("t1"))
        assertNull(turnDao.get("t2"))
    }

    @Test
    fun `updateTokenEstimate sets tokens and updatedAtMs`() = runTest {
        conversationDao.upsert(makeConversation())

        conversationDao.updateTokenEstimate("conv-1", 999, nowMs = 7777L)

        val loaded = conversationDao.get("conv-1")!!
        assertEquals(999, loaded.tokenEstimateTotal)
        assertEquals(7777L, loaded.updatedAtMs)
    }

    @Test
    fun `updateLastStableSeq sets seq and updatedAtMs`() = runTest {
        conversationDao.upsert(makeConversation())

        conversationDao.updateLastStableSeq("conv-1", 5, nowMs = 8888L)

        val loaded = conversationDao.get("conv-1")!!
        assertEquals(5, loaded.lastStableSeq)
        assertEquals(8888L, loaded.updatedAtMs)
    }

    @Test
    fun `touch updates only updatedAtMs`() = runTest {
        conversationDao.upsert(makeConversation())

        conversationDao.touch("conv-1", nowMs = 9999L)

        val loaded = conversationDao.get("conv-1")!!
        assertEquals(9999L, loaded.updatedAtMs)
        assertEquals("You are helpful.", loaded.systemPrompt) // unchanged
    }

    @Test
    fun `conversation with null systemPrompt is stored correctly`() = runTest {
        conversationDao.upsert(makeConversation(systemPrompt = null))

        val loaded = conversationDao.get("conv-1")!!
        assertNull(loaded.systemPrompt)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  TurnDao tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `insert turn with all fields`() = runTest {
        conversationDao.upsert(makeConversation())

        val turn = makeTurn(
            turnId = "t1",
            seq = 0,
            role = TurnRole.USER,
            state = TurnState.COMPLETED,
            text = "Hello world",
            tokens = 3,
            startedAtMs = 1000L,
            completedAtMs = 2000L,
        )
        turnDao.upsert(turn)

        val loaded = turnDao.get("t1")!!
        assertEquals("t1", loaded.turnId)
        assertEquals("conv-1", loaded.conversationId)
        assertEquals(0, loaded.seq)
        assertEquals(TurnRole.USER, loaded.role)
        assertEquals(TurnState.COMPLETED, loaded.state)
        assertEquals("Hello world", loaded.textContent)
        assertEquals(3, loaded.tokenEstimate)
        assertEquals(1000L, loaded.startedAtMs)
        assertEquals(2000L, loaded.completedAtMs)
    }

    @Test
    fun `allForConversation returns turns ordered by seq`() = runTest {
        conversationDao.upsert(makeConversation())

        turnDao.upsert(makeTurn(turnId = "t2", seq = 2))
        turnDao.upsert(makeTurn(turnId = "t0", seq = 0))
        turnDao.upsert(makeTurn(turnId = "t1", seq = 1))

        val turns = turnDao.allForConversation("conv-1")
        assertEquals(3, turns.size)
        assertEquals(listOf(0, 1, 2), turns.map { it.seq })
    }

    @Test
    fun `completedForConversation returns only completed turns`() = runTest {
        conversationDao.upsert(makeConversation())

        turnDao.upsert(makeTurn("t0", seq = 0, state = TurnState.COMPLETED))
        turnDao.upsert(makeTurn("t1", seq = 1, state = TurnState.PENDING))
        turnDao.upsert(makeTurn("t2", seq = 2, state = TurnState.STREAMING))
        turnDao.upsert(makeTurn("t3", seq = 3, state = TurnState.COMPLETED))
        turnDao.upsert(makeTurn("t4", seq = 4, state = TurnState.INTERRUPTED))

        val completed = turnDao.completedForConversation("conv-1")
        assertEquals(2, completed.size)
        assertEquals(listOf("t0", "t3"), completed.map { it.turnId })
    }

    @Test
    fun `completedAfterSeq filters by sequence number`() = runTest {
        conversationDao.upsert(makeConversation())

        turnDao.upsert(makeTurn("t0", seq = 0, state = TurnState.COMPLETED))
        turnDao.upsert(makeTurn("t1", seq = 1, state = TurnState.COMPLETED))
        turnDao.upsert(makeTurn("t2", seq = 2, state = TurnState.COMPLETED))
        turnDao.upsert(makeTurn("t3", seq = 3, state = TurnState.INTERRUPTED))

        val after1 = turnDao.completedAfterSeq("conv-1", 1)
        assertEquals(1, after1.size)
        assertEquals("t2", after1[0].turnId)
    }

    @Test
    fun `updateState changes turn state`() = runTest {
        conversationDao.upsert(makeConversation())
        turnDao.upsert(makeTurn("t1", seq = 0, state = TurnState.PENDING))

        turnDao.updateState("t1", TurnState.COMPLETED, nowMs = 5000L)

        val loaded = turnDao.get("t1")!!
        assertEquals(TurnState.COMPLETED, loaded.state)
        assertEquals(5000L, loaded.completedAtMs)
    }

    @Test
    fun `completeWithText sets text tokens and state`() = runTest {
        conversationDao.upsert(makeConversation())
        turnDao.upsert(
            makeTurn("t1", seq = 0, role = TurnRole.ASSISTANT, state = TurnState.STREAMING, text = null, tokens = 0),
        )

        turnDao.completeWithText(
            turnId = "t1",
            text = "Full response",
            tokens = 42,
            nowMs = 6000L,
        )

        val loaded = turnDao.get("t1")!!
        assertEquals(TurnState.COMPLETED, loaded.state)
        assertEquals("Full response", loaded.textContent)
        assertEquals(42, loaded.tokenEstimate)
        assertEquals(6000L, loaded.completedAtMs)
    }

    @Test
    fun `markTurnInterrupted sets state to INTERRUPTED`() = runTest {
        conversationDao.upsert(makeConversation())
        turnDao.upsert(
            makeTurn("t1", seq = 0, role = TurnRole.ASSISTANT, state = TurnState.STREAMING),
        )

        turnDao.updateState("t1", TurnState.INTERRUPTED, nowMs = 7000L)

        val loaded = turnDao.get("t1")!!
        assertEquals(TurnState.INTERRUPTED, loaded.state)
        assertEquals(7000L, loaded.completedAtMs)
    }

    @Test
    fun `deleteUnstableAssistantTurns removes streaming and interrupted assistant turns`() = runTest {
        conversationDao.upsert(makeConversation())

        turnDao.upsert(makeTurn("t0", seq = 0, role = TurnRole.USER, state = TurnState.COMPLETED))
        turnDao.upsert(makeTurn("t1", seq = 1, role = TurnRole.ASSISTANT, state = TurnState.STREAMING))
        turnDao.upsert(makeTurn("t2", seq = 2, role = TurnRole.USER, state = TurnState.COMPLETED))
        turnDao.upsert(makeTurn("t3", seq = 3, role = TurnRole.ASSISTANT, state = TurnState.INTERRUPTED))
        turnDao.upsert(makeTurn("t4", seq = 4, role = TurnRole.ASSISTANT, state = TurnState.COMPLETED))
        // Interrupted user turn should NOT be deleted
        turnDao.upsert(makeTurn("t5", seq = 5, role = TurnRole.USER, state = TurnState.INTERRUPTED))

        val deleted = turnDao.deleteUnstableAssistantTurns("conv-1")
        assertEquals(2, deleted)

        // Streaming and interrupted assistant turns removed
        assertNull(turnDao.get("t1"))
        assertNull(turnDao.get("t3"))

        // Others remain
        assertNotNull(turnDao.get("t0"))
        assertNotNull(turnDao.get("t2"))
        assertNotNull(turnDao.get("t4"))
        assertNotNull(turnDao.get("t5"))
    }

    @Test
    fun `deleteUnstableAssistantTurns returns zero when nothing to delete`() = runTest {
        conversationDao.upsert(makeConversation())
        turnDao.upsert(makeTurn("t0", seq = 0, state = TurnState.COMPLETED))

        assertEquals(0, turnDao.deleteUnstableAssistantTurns("conv-1"))
    }

    @Test
    fun `completedDescending returns turns newest first`() = runTest {
        conversationDao.upsert(makeConversation())

        turnDao.upsert(makeTurn("t0", seq = 0, state = TurnState.COMPLETED))
        turnDao.upsert(makeTurn("t1", seq = 1, state = TurnState.COMPLETED))
        turnDao.upsert(makeTurn("t2", seq = 2, state = TurnState.COMPLETED))

        val desc = turnDao.completedDescending("conv-1")
        assertEquals(listOf(2, 1, 0), desc.map { it.seq })
    }

    @Test
    fun `firstPendingUserTurn returns oldest pending user turn`() = runTest {
        conversationDao.upsert(makeConversation())

        turnDao.upsert(makeTurn("t0", seq = 0, role = TurnRole.USER, state = TurnState.COMPLETED))
        turnDao.upsert(makeTurn("t1", seq = 1, role = TurnRole.USER, state = TurnState.PENDING))
        turnDao.upsert(makeTurn("t2", seq = 2, role = TurnRole.USER, state = TurnState.PENDING))

        val pending = turnDao.firstPendingUserTurn("conv-1")
        assertNotNull(pending)
        assertEquals("t1", pending!!.turnId)
    }

    @Test
    fun `firstPendingUserTurn returns null when none pending`() = runTest {
        conversationDao.upsert(makeConversation())
        turnDao.upsert(makeTurn("t0", seq = 0, role = TurnRole.USER, state = TurnState.COMPLETED))

        assertNull(turnDao.firstPendingUserTurn("conv-1"))
    }

    @Test
    fun `firstPendingUserTurn ignores pending assistant turns`() = runTest {
        conversationDao.upsert(makeConversation())
        turnDao.upsert(makeTurn("t0", seq = 0, role = TurnRole.ASSISTANT, state = TurnState.PENDING))

        assertNull(turnDao.firstPendingUserTurn("conv-1"))
    }

    @Test
    fun `nextSeq returns 0 for empty conversation`() = runTest {
        conversationDao.upsert(makeConversation())
        assertEquals(0, turnDao.nextSeq("conv-1"))
    }

    @Test
    fun `nextSeq returns max+1`() = runTest {
        conversationDao.upsert(makeConversation())
        turnDao.upsert(makeTurn("t0", seq = 0))
        turnDao.upsert(makeTurn("t1", seq = 1))
        turnDao.upsert(makeTurn("t2", seq = 2))

        assertEquals(3, turnDao.nextSeq("conv-1"))
    }

    @Test
    fun `insertWithAutoSeq assigns sequential seq numbers`() = runTest {
        conversationDao.upsert(makeConversation())

        val turn1 = turnDao.insertWithAutoSeq(makeTurn(turnId = "t1", seq = 0))
        val turn2 = turnDao.insertWithAutoSeq(makeTurn(turnId = "t2", seq = 0))
        val turn3 = turnDao.insertWithAutoSeq(makeTurn(turnId = "t3", seq = 0))

        assertEquals(0, turn1.seq)
        assertEquals(1, turn2.seq)
        assertEquals(2, turn3.seq)

        // Verify persisted values match
        val all = turnDao.allForConversation("conv-1")
        assertEquals(3, all.size)
        assertEquals(listOf(0, 1, 2), all.map { it.seq })
    }

    @Test
    fun `insertWithAutoSeq ignores placeholder seq value`() = runTest {
        conversationDao.upsert(makeConversation())

        // Even with a non-zero placeholder, the auto-assigned seq should be correct
        val turn = turnDao.insertWithAutoSeq(makeTurn(turnId = "t1", seq = 99))
        assertEquals(0, turn.seq)
    }

    @Test
    fun `totalTokenEstimate sums completed turns only`() = runTest {
        conversationDao.upsert(makeConversation())

        turnDao.upsert(makeTurn("t0", seq = 0, state = TurnState.COMPLETED, tokens = 10))
        turnDao.upsert(makeTurn("t1", seq = 1, state = TurnState.PENDING, tokens = 20))
        turnDao.upsert(makeTurn("t2", seq = 2, state = TurnState.COMPLETED, tokens = 30))
        turnDao.upsert(makeTurn("t3", seq = 3, state = TurnState.INTERRUPTED, tokens = 40))

        assertEquals(40, turnDao.totalTokenEstimate("conv-1"))
    }

    @Test
    fun `totalTokenEstimate returns 0 for no completed turns`() = runTest {
        conversationDao.upsert(makeConversation())
        assertEquals(0, turnDao.totalTokenEstimate("conv-1"))
    }

    @Test
    fun `unique constraint on conversationId plus seq prevents duplicates`() = runTest {
        conversationDao.upsert(makeConversation())
        turnDao.upsert(makeTurn("t1", seq = 0))

        // Upserting a different turnId with the same (conversationId, seq) should
        // succeed because upsert uses REPLACE — but it replaces the old row.
        turnDao.upsert(makeTurn("t2", seq = 0))

        // The old row is replaced
        val all = turnDao.allForConversation("conv-1")
        assertEquals(1, all.size)
        assertEquals("t2", all[0].turnId)
    }

    @Test
    fun `delete turn by id`() = runTest {
        conversationDao.upsert(makeConversation())
        turnDao.upsert(makeTurn("t1", seq = 0))

        turnDao.delete("t1")
        assertNull(turnDao.get("t1"))
    }

    @Test
    fun `turns for different conversations are isolated`() = runTest {
        conversationDao.upsert(makeConversation("conv-1"))
        conversationDao.upsert(makeConversation("conv-2"))

        turnDao.upsert(makeTurn("t1", conversationId = "conv-1", seq = 0))
        turnDao.upsert(makeTurn("t2", conversationId = "conv-2", seq = 0))
        turnDao.upsert(makeTurn("t3", conversationId = "conv-2", seq = 1))

        assertEquals(1, turnDao.allForConversation("conv-1").size)
        assertEquals(2, turnDao.allForConversation("conv-2").size)
    }

    @Test
    fun `allForConversation returns empty for unknown conversation`() = runTest {
        assertTrue(turnDao.allForConversation("nope").isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════════
    //  TurnPartEntity tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `insert and retrieve turn parts`() = runTest {
        conversationDao.upsert(makeConversation())
        turnDao.upsert(makeTurn("t1", seq = 0))

        turnDao.insertPart(makePart("p1", turnId = "t1", ordinal = 0, text = "part-0"))
        turnDao.insertPart(makePart("p2", turnId = "t1", ordinal = 1, text = "part-1"))

        val parts = turnDao.partsForTurn("t1")
        assertEquals(2, parts.size)
        assertEquals("part-0", parts[0].text)
        assertEquals("part-1", parts[1].text)
    }

    @Test
    fun `parts ordered by ordinal`() = runTest {
        conversationDao.upsert(makeConversation())
        turnDao.upsert(makeTurn("t1", seq = 0))

        // Insert out of order
        turnDao.insertPart(makePart("p2", turnId = "t1", ordinal = 2))
        turnDao.insertPart(makePart("p0", turnId = "t1", ordinal = 0))
        turnDao.insertPart(makePart("p1", turnId = "t1", ordinal = 1))

        val parts = turnDao.partsForTurn("t1")
        assertEquals(listOf(0, 1, 2), parts.map { it.ordinal })
    }

    @Test
    fun `turn parts cascade delete when turn deleted`() = runTest {
        conversationDao.upsert(makeConversation())
        turnDao.upsert(makeTurn("t1", seq = 0))
        turnDao.insertPart(makePart("p1", turnId = "t1", ordinal = 0))
        turnDao.insertPart(makePart("p2", turnId = "t1", ordinal = 1))

        turnDao.delete("t1")

        assertTrue(turnDao.partsForTurn("t1").isEmpty())
    }

    @Test
    fun `turn parts cascade delete when conversation deleted`() = runTest {
        conversationDao.upsert(makeConversation())
        turnDao.upsert(makeTurn("t1", seq = 0))
        turnDao.insertPart(makePart("p1", turnId = "t1", ordinal = 0))

        conversationDao.delete("conv-1")

        assertTrue(turnDao.partsForTurn("t1").isEmpty())
    }

    @Test
    fun `parts with various kinds stored correctly`() = runTest {
        conversationDao.upsert(makeConversation())
        turnDao.upsert(makeTurn("t1", seq = 0))

        val imagePart = TurnPartEntity(
            partId = "img-1",
            turnId = "t1",
            ordinal = 0,
            kind = PartKind.IMAGE_REF,
            text = null,
            uriString = "content://media/photo1.jpg",
            mimeType = "image/jpeg",
            metadataJson = """{"width":800,"height":600}""",
        )
        turnDao.insertPart(imagePart)

        val parts = turnDao.partsForTurn("t1")
        assertEquals(1, parts.size)
        val loaded = parts[0]
        assertEquals(PartKind.IMAGE_REF, loaded.kind)
        assertEquals("content://media/photo1.jpg", loaded.uriString)
        assertEquals("image/jpeg", loaded.mimeType)
        assertEquals("""{"width":800,"height":600}""", loaded.metadataJson)
        assertNull(loaded.text)
    }

    @Test
    fun `partsForTurn returns empty for nonexistent turn`() = runTest {
        assertTrue(turnDao.partsForTurn("nope").isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════════
    //  ConversationState tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `conversation defaults to READY state`() = runTest {
        conversationDao.upsert(makeConversation())

        val loaded = conversationDao.get("conv-1")!!
        assertEquals(ConversationState.READY, loaded.state)
    }

    @Test
    fun `updateState transitions CREATING to READY`() = runTest {
        conversationDao.upsert(
            makeConversation().copy(state = ConversationState.CREATING),
        )

        conversationDao.updateState("conv-1", ConversationState.READY, nowMs = 5000L)

        val loaded = conversationDao.get("conv-1")!!
        assertEquals(ConversationState.READY, loaded.state)
        assertEquals(5000L, loaded.updatedAtMs)
    }

    @Test
    fun `deleteOrphaned removes only CREATING conversations`() = runTest {
        conversationDao.upsert(
            makeConversation("creating-1").copy(state = ConversationState.CREATING),
        )
        conversationDao.upsert(
            makeConversation("creating-2").copy(state = ConversationState.CREATING),
        )
        conversationDao.upsert(
            makeConversation("ready-1").copy(state = ConversationState.READY),
        )

        val deleted = conversationDao.deleteOrphaned()
        assertEquals(2, deleted)

        assertNull(conversationDao.get("creating-1"))
        assertNull(conversationDao.get("creating-2"))
        assertNotNull(conversationDao.get("ready-1"))
    }

    @Test
    fun `deleteOrphaned returns zero when no orphans`() = runTest {
        conversationDao.upsert(makeConversation())
        assertEquals(0, conversationDao.deleteOrphaned())
    }

    @Test
    fun `deleteOrphaned cascades to turns`() = runTest {
        conversationDao.upsert(
            makeConversation("orphan").copy(state = ConversationState.CREATING),
        )
        turnDao.upsert(makeTurn("t1", conversationId = "orphan", seq = 0))

        conversationDao.deleteOrphaned()

        assertNull(conversationDao.get("orphan"))
        assertNull(turnDao.get("t1"))
    }
}
