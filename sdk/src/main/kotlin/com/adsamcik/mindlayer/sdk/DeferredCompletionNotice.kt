package com.adsamcik.mindlayer.sdk

import com.adsamcik.mindlayer.DeferredResult

data class DeferredCompletionNotice(
    val requestId: String,
    val statusCode: Int,
) {
    val isReady: Boolean
        get() = statusCode == DeferredResult.READY
}
