package com.adsamcik.mindlayer.service.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardViewModelTest {

    @Test
    fun `rejected test inference does not mark a completion timestamp`() {
        val viewModel = DashboardViewModel()

        viewModel.runTestInference()

        val state = viewModel.uiState.value
        assertFalse(state.isTestRunning)
        assertNull(state.lastTestCompletedAtMs)
    }
}
