package com.adsamcik.mindlayer.service.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.adsamcik.mindlayer.service.R
import com.adsamcik.mindlayer.service.logging.MindlayerDiagnostics
import dev.tracebox.Tracebox
import dev.tracebox.ui.compose.TraceboxAdvancedControls
import dev.tracebox.ui.compose.TraceboxDiagnosticsScreen
import dev.tracebox.ui.compose.TraceboxDiagnosticsUiConfiguration
import dev.tracebox.ui.compose.TraceboxDiagnosticsUiStrings
import dev.tracebox.ui.compose.TraceboxPackageActions
import dev.tracebox.ui.compose.TraceboxPrimaryAction

/** Uses Tracebox's exact-package review and Android share/save workflow. */
@Composable
fun TroubleshootingScreen(onBack: () -> Unit = {}) {
    val handle = Tracebox.current()
    Scaffold(
        topBar = {
            MindlayerSecondaryTopBar(
                title = stringResource(R.string.report_title),
                subtitle = null,
                onBack = onBack,
            )
        },
    ) { padding ->
        if (handle == null) {
            MindlayerStatusPane(
                modifier = Modifier.padding(padding),
                title = stringResource(R.string.report_unavailable),
                message = stringResource(R.string.report_unavailable_message),
            )
        } else {
            TraceboxDiagnosticsScreen(
                handle = handle,
                modifier = Modifier.padding(padding),
                configuration = TraceboxDiagnosticsUiConfiguration(
                    showHeading = false,
                    primaryAction = TraceboxPrimaryAction.SHARE,
                    packageActions = TraceboxPackageActions(upload = false),
                    advancedControls = TraceboxAdvancedControls(captureKinds = MindlayerDiagnostics.captureKinds),
                    defaultPolicy = MindlayerDiagnostics.defaultPolicy,
                    strings = TraceboxDiagnosticsUiStrings(
                        supportTitle = R.string.report_details_title,
                        supportDescription = R.string.report_description,
                        privacyNotice = R.string.report_review_notice,
                        reviewAndShare = R.string.report_review_action,
                        diagnosticsNotReady = R.string.report_not_ready,
                        diagnosticsEnabled = R.string.report_recording,
                        policyPartiallyApplied = R.string.report_recording_partial,
                    ),
                ),
            )
        }
    }
}
