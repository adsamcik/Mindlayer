package com.adsamcik.mindlayer.service.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adsamcik.mindlayer.service.logging.MindlayerLog

/**
 * Debug-only [BroadcastReceiver] that flips the "auto-accept all callers"
 * developer toggle from the command line, so **headless CI / instrumented
 * tests** can grant access without the interactive ConsentActivity flow.
 *
 * Declared only in `app/src/debug/AndroidManifest.xml` (physically absent from
 * release) and protected by `android:permission="android.permission.DUMP"` so
 * only the shell (`adb`) and system — which hold DUMP — can trigger it; an
 * ordinary app on the device cannot.
 *
 * Usage (debug build):
 * ```
 * # enable
 * adb shell am broadcast \
 *   -n com.adsamcik.mindlayer.service.debug/com.adsamcik.mindlayer.service.security.DebugAutoAcceptReceiver \
 *   -a com.adsamcik.mindlayer.debug.SET_AUTO_ACCEPT --ez enabled true
 * # disable
 * adb shell am broadcast \
 *   -n com.adsamcik.mindlayer.service.debug/com.adsamcik.mindlayer.service.security.DebugAutoAcceptReceiver \
 *   -a com.adsamcik.mindlayer.debug.SET_AUTO_ACCEPT --ez enabled false
 * ```
 * `am broadcast` prints `Broadcast completed: result=1, data="auto_accept=true"`
 * (result=1 enabled, result=0 disabled) so CI can assert the resulting state.
 */
class DebugAutoAcceptReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Default to enabling when the extra is omitted — the common CI case is
        // "turn it on". Pass `--ez enabled false` to turn it back off.
        val requested = intent.getBooleanExtra(EXTRA_ENABLED, true)
        val applied = DebugAutoAcceptStore(context).setEnabled(requested)
        MindlayerLog.w(
            TAG,
            "DEBUG auto-accept toggle set via broadcast: requested=$requested applied=$applied",
            throwable = null,
        )
        // setResultCode/Data only work while processing an *ordered* broadcast
        // (which `adb shell am broadcast` is, because `am` registers a result
        // receiver). When invoked any other way they throw IllegalStateException
        // — the flag is already applied above, so surfacing the result is
        // best-effort.
        runCatching {
            resultCode = if (applied) 1 else 0
            resultData = "auto_accept=$applied"
        }
    }

    private companion object {
        private const val TAG = "Mindlayer.DebugAutoAccept"
        private const val EXTRA_ENABLED = "enabled"
    }
}
