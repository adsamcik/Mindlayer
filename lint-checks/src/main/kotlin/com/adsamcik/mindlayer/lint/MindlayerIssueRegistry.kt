package com.adsamcik.mindlayer.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API

class MindlayerIssueRegistry : IssueRegistry() {
    override val issues = listOf(
        NoDirectAndroidLogDetector.ISSUE,
        NativeSafeThrowableLogDetector.ISSUE,
        NoNetworkTelemetryDetector.ISSUE,
    )

    override val api: Int = CURRENT_API
    override val minApi: Int = CURRENT_API
    override val vendor: Vendor = Vendor(
        "Mindlayer",
        "mindlayer",
        "https://github.com/adsamcik/Mindlayer/issues",
    )
}
