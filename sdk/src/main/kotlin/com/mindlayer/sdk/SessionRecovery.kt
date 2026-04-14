package com.mindlayer.sdk

import android.util.Log
import com.mindlayer.HistoryTurn
import com.mindlayer.sdk.db.TurnRole

/**
 * Orchestrates session recovery after an OOM kill or service crash.
 *
 * Flow:
 *  1. [ConnectionManager] reconnects and reaches [ConnectionState.CONNECTED].
 *  2. Caller invokes [recoverSession] with the session ID.
 *  3. We clean up incomplete turns, load replay data from Room, and create
 *     a fresh server session seeded with the history.
 *  4. If there was a pending user turn that was never acknowledged, we
 *     return it so the caller can re-send.
 */
class SessionRecovery internal constructor(
    private val mindlayer: Mindlayer,
    private val historyStore: HistoryStore,
) {

    companion object {
        private const val TAG = "SessionRecovery"

        /** Token budget for replay when app is in the foreground. */
        const val FOREGROUND_REPLAY_BUDGET = 6_000

        /** Smaller budget for emergency / background recovery. */
        const val EMERGENCY_REPLAY_BUDGET = 2_000
    }

    /**
     * Attempt to recover a session.
     *
     * @param sessionId the conversation ID from before the crash.
     * @param maxReplayTokens token budget for the replayed history.
     * @return a [RecoveryResult] describing what happened, or `null` if the
     *         session was not found in local history.
     */
    suspend fun recoverSession(
        sessionId: String,
        maxReplayTokens: Int = FOREGROUND_REPLAY_BUDGET,
    ): RecoveryResult? {
        Log.i(TAG, "Starting recovery for session $sessionId")

        // 1. Clean up streaming/interrupted assistant turns
        val cleaned = historyStore.cleanupInterruptedTurns(sessionId)

        // 2. Load replay data from Room
        val replay = historyStore.getReplayHistory(sessionId, maxReplayTokens)
        if (replay == null) {
            Log.w(TAG, "No local history found for $sessionId")
            return null
        }

        // 3. Build history turns for session creation
        val historyTurns = replay.turns.map { turn ->
            HistoryTurn(
                role = when (turn.role) {
                    TurnRole.USER -> "user"
                    TurnRole.ASSISTANT -> "model"
                    TurnRole.TOOL -> "tool"
                    else -> "user"
                },
                text = turn.textContent ?: "",
            )
        }

        // Destroy old session (best-effort) before creating replacement
        try { mindlayer.connection.awaitConnected().destroySession(sessionId) } catch (_: Exception) {}

        // 4. Create a fresh server session seeded with history via initialHistory
        val config = SessionConfigBuilder().apply {
            sessionId(sessionId)
            replay.config.systemPrompt?.let { systemPrompt(it) }
            maxTokens(replay.config.maxTokens)
            backend(replay.config.backend)
            topK(replay.config.samplerTopK)
            topP(replay.config.samplerTopP)
            temperature(replay.config.samplerTemperature)
            replay.config.toolsJson?.let { tools(it) }
            replay.config.extraContextJson?.let { extraContext(it) }
            if (historyTurns.isNotEmpty()) {
                initialHistory(historyTurns)
            }
        }.build()

        val newSessionId = mindlayer.connection.awaitConnected().createSession(config)

        Log.i(
            TAG,
            "Recovered session $sessionId → $newSessionId " +
                "(${replay.turns.size} turns replayed, $cleaned cleaned)",
        )

        return RecoveryResult(
            originalSessionId = sessionId,
            newSessionId = newSessionId,
            replayedTurnCount = replay.turns.size,
            cleanedTurnCount = cleaned,
            pendingUserText = replay.pendingUserTurn?.textContent,
        )
    }
}

/**
 * Outcome of a session recovery attempt.
 */
data class RecoveryResult(
    /** The conversation ID from before the crash. */
    val originalSessionId: String,
    /** The new server-side session ID after recovery. */
    val newSessionId: String,
    /** How many completed turns were replayed. */
    val replayedTurnCount: Int,
    /** How many unstable assistant turns were discarded. */
    val cleanedTurnCount: Int,
    /** Text of a pending user turn that should be re-sent, if any. */
    val pendingUserText: String?,
)
