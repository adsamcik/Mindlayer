package com.adsamcik.mindlayer.service.security

import com.adsamcik.mindlayer.service.logging.MindlayerLog
import android.content.Context

/**
 * Release-variant no-op seam. The debug-variant override in
 * `app/src/debug/kotlin/.../DebugAutoAccept.kt` reads/writes a sentinel file
 * via `DebugAutoAcceptStore` (which is physically absent from the release
 * classpath). In release the toggle can never be enabled, so the gate's
 * `autoAcceptGate()` is always `false` and no caller is ever auto-accepted.
 */
@Suppress("UNUSED_PARAMETER")
internal fun debugAutoAcceptAllEnabled(context: Context): Boolean = false

@Suppress("UNUSED_PARAMETER")
internal fun debugSetAutoAcceptAll(context: Context, enabled: Boolean): Boolean {
    // Defensive: a release build should never attempt to flip the toggle. If
    // something does, log it and leave authorization untouched.
    MindlayerLog.w(
        "Mindlayer.DebugAutoAccept",
        "debugSetAutoAcceptAll called in a release build — ignored",
        throwable = null,
    )
    return false
}
