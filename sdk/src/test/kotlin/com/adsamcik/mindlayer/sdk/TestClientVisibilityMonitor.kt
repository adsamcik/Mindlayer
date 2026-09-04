package com.adsamcik.mindlayer.sdk

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Deterministic process-visibility source for connection lifecycle tests. */
internal class TestClientVisibilityMonitor(
    initialVisible: Boolean = true,
) : ClientVisibilityMonitor {
    private val mutableVisible = MutableStateFlow(initialVisible)

    override val visible: StateFlow<Boolean> = mutableVisible

    fun setVisible(visible: Boolean) {
        mutableVisible.value = visible
    }

    override fun close() = Unit
}
