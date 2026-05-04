package com.adsamcik.mindlayer.sdk

/**
 * Controls what the SDK persists in its local history database.
 *
 * The default is metadata-only: roles, token estimates, timestamps, and
 * sampler/backend metadata are kept, but prompt text, model output, system
 * prompts, tools, and extra context are not retained.
 */
data class HistoryPolicy(
    val persistContent: Boolean = false,
    val persistSystemPrompt: Boolean = false,
    val persistToolContext: Boolean = false,
) {
    companion object {
        val METADATA_ONLY = HistoryPolicy()
        val FULL_CONTENT = HistoryPolicy(
            persistContent = true,
            persistSystemPrompt = true,
            persistToolContext = true,
        )
    }
}
