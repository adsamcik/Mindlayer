package com.mindlayer.service.ui

import kotlinx.coroutines.flow.StateFlow

/**
 * Polls a [StateFlow] every 25ms until [predicate] returns true, or [timeoutMs] elapses.
 *
 * The view models under test launch work in `viewModelScope.launch(Dispatchers.IO)`, which is
 * not controlled by the test's StandardTestDispatcher (`runTest` only drains the main
 * dispatcher). Polling with `Thread.sleep` follows the same approach used by
 * [com.mindlayer.service.logging.LogRepositoryTest].
 */
internal fun <T> StateFlow<T>.awaitState(
    timeoutMs: Long = 3_000L,
    pollMs: Long = 25L,
    predicate: (T) -> Boolean,
): T {
    val deadline = System.currentTimeMillis() + timeoutMs
    var current = value
    while (!predicate(current)) {
        if (System.currentTimeMillis() > deadline) {
            throw AssertionError(
                "Timed out after ${timeoutMs}ms waiting for state predicate. last=$current",
            )
        }
        Thread.sleep(pollMs)
        current = value
    }
    return current
}

internal fun awaitLoaded(
    viewModel: RecentLogsViewModel,
    timeoutMs: Long = 3_000L,
    predicate: (RecentLogsUiState) -> Boolean,
): RecentLogsUiState = viewModel.uiState.awaitState(timeoutMs = timeoutMs, predicate = predicate)

internal fun awaitLoaded(
    viewModel: SessionHistoryViewModel,
    timeoutMs: Long = 3_000L,
    predicate: (SessionHistoryUiState) -> Boolean,
): SessionHistoryUiState = viewModel.uiState.awaitState(timeoutMs = timeoutMs, predicate = predicate)

internal fun awaitLoaded(
    viewModel: SessionDetailViewModel,
    timeoutMs: Long = 3_000L,
    predicate: (SessionDetailUiState) -> Boolean,
): SessionDetailUiState = viewModel.uiState.awaitState(timeoutMs = timeoutMs, predicate = predicate)
