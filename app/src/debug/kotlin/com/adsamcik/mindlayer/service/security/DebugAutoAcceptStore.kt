package com.adsamcik.mindlayer.service.security

import android.content.Context
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import java.io.File

/**
 * Debug-only persistence for the "auto-accept all callers" developer toggle.
 *
 * # What it is
 *
 * A single cross-process boolean backed by the *existence* of a sentinel file
 * under `filesDir/debug_auto_accept/enabled`. The dashboard (main process) and
 * the [DebugAutoAcceptReceiver] (adb-triggered) flip it via [setEnabled]; the
 * AIDL gate in the `:ml` process reads it via [isEnabled] on the
 * unconsented-caller path. Both processes share the app's `filesDir`, so the
 * sentinel propagates without `SharedPreferences` (`MODE_MULTI_PROCESS` is
 * deprecated and racy — the same reason [AllowlistStore] uses files).
 *
 * # Why no HMAC / FileLock
 *
 * This class only exists in the **debug** variant (a `testRelease` absence test
 * asserts the class is not on the release classpath, and the seam in
 * `app/src/release/.../DebugAutoAccept.kt` is a hard-coded `false`). A
 * `filesDir`-write attacker on a debug build is already out of scope, and the
 * value is a single boolean whose worst-case torn read is a one-call-stale
 * toggle — so the tamper-resistant signing + cross-process locking that
 * [AllowlistStore] needs is unnecessary here. Existence-based reads keep the
 * semantics trivial.
 *
 * # Failure semantics
 *
 * [isEnabled] **fails closed**: any I/O error is treated as disabled, so a
 * broken filesystem can never silently widen authorization. [setEnabled]
 * returns the resulting on-disk state so a caller (UI / receiver) reflects what
 * the disk actually reached, never a state it only requested.
 */
internal class DebugAutoAcceptStore(context: Context) {
    private val baseDir: File = File(context.applicationContext.filesDir, DIR_NAME)
    private val flagFile: File = File(baseDir, FLAG_NAME)

    /** Fail-closed: any error reading the sentinel is treated as "disabled". */
    fun isEnabled(): Boolean = try {
        flagFile.exists()
    } catch (t: Throwable) {
        MindlayerLog.w(TAG, "isEnabled read failed, treating as disabled: ${t.javaClass.simpleName}", throwable = null)
        false
    }

    /**
     * Enables or disables the toggle by creating/deleting the sentinel file.
     * Returns the resulting on-disk state (`true` == enabled) so the caller can
     * surface the *actual* result rather than the requested one — a failed
     * write therefore never leaves a UI switch lying.
     */
    fun setEnabled(enabled: Boolean): Boolean = try {
        if (enabled) {
            if (!baseDir.exists()) baseDir.mkdirs()
            if (!flagFile.exists()) flagFile.createNewFile()
        } else {
            // delete() returns false when the file is already absent — rely on
            // the post-condition (exists()) below, not the return value.
            flagFile.delete()
        }
        flagFile.exists()
    } catch (t: Throwable) {
        MindlayerLog.w(TAG, "setEnabled($enabled) failed: ${t.javaClass.simpleName}", throwable = null)
        runCatching { flagFile.exists() }.getOrDefault(false)
    }

    private companion object {
        private const val TAG = "Mindlayer.DebugAutoAccept"
        private const val DIR_NAME = "debug_auto_accept"
        private const val FLAG_NAME = "enabled"
    }
}
