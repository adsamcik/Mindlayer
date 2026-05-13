package com.adsamcik.mindlayer.service.security

import android.os.IBinder
import com.adsamcik.mindlayer.IClientCallback
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Per-UID registry of client-supplied [IClientCallback]s used to push
 * eviction notices.
 *
 * Lifecycle:
 *  - [register] links a death recipient to the callback's binder so a
 *    crashed client doesn't keep stale entries alive.
 *  - [unregister] removes the entry and unlinks the death recipient.
 *  - [notifyEviction] snapshots the per-UID list and dispatches a
 *    `oneway` call to every registered callback for that UID. Failed
 *    transactions remove the offending callback opportunistically.
 *  - [clear] tears down everything during service teardown.
 *
 * Thread-safety:
 *  - The outer map is a [ConcurrentHashMap]. Per-UID lists use
 *    [CopyOnWriteArrayList], so iteration during dispatch is snapshot-
 *    consistent and lock-free.
 *  - Idempotency is keyed by the underlying [IBinder] (see
 *    [IClientCallback.asBinder]) — registering the same SDK callback
 *    instance twice is a no-op rather than producing duplicate calls.
 *  - A small per-UID cap ([MAX_CALLBACKS_PER_UID]) blocks runaway
 *    registration storms from a single hostile or buggy caller.
 */
internal class EvictionRegistry {

    /** Wraps a callback with its death recipient so we can unlink on removal. */
    private data class Entry(
        val uid: Int,
        val callback: IClientCallback,
        val binder: IBinder,
        val recipient: IBinder.DeathRecipient,
    )

    private val byUid = ConcurrentHashMap<Int, CopyOnWriteArrayList<Entry>>()

    /**
     * Register [callback] under [uid]. Returns `true` if newly registered,
     * `false` if a no-op (duplicate or capacity exhausted).
     *
     * Per-UID cap is intentionally small. Legitimate SDK clients register
     * once per process; multiple-instance use cases (e.g. test harnesses
     * spawning many [com.adsamcik.mindlayer.sdk.Mindlayer]) are still
     * accommodated up to [MAX_CALLBACKS_PER_UID].
     */
    fun register(uid: Int, callback: IClientCallback): Boolean {
        val binder = callback.asBinder()
        val list = byUid.computeIfAbsent(uid) { CopyOnWriteArrayList() }

        // Snapshot the current list to enforce idempotency + cap atomically
        // enough — concurrent registrations from the same caller may briefly
        // see stale state, but the binder-key check below is the canonical
        // dedup gate and survives any race.
        val existing = list.firstOrNull { it.binder === binder }
        if (existing != null) {
            return false
        }
        if (list.size >= MAX_CALLBACKS_PER_UID) {
            MindlayerLog.w(
                TAG,
                "register: per-UID callback cap reached (uid=$uid cap=$MAX_CALLBACKS_PER_UID)",
            )
            return false
        }

        val recipient = IBinder.DeathRecipient {
            MindlayerLog.i(TAG, "callback binderDied (uid=$uid)")
            removeByBinder(uid, binder, attemptUnlink = false)
        }
        try {
            binder.linkToDeath(recipient, 0)
        } catch (t: Throwable) {
            // Binder already dead — don't add a stale entry.
            MindlayerLog.w(TAG, "register: linkToDeath failed (uid=$uid): ${t.javaClass.simpleName}")
            return false
        }

        list.add(Entry(uid, callback, binder, recipient))
        return true
    }

    /**
     * Best-effort unregister. Returns `true` if an entry was removed.
     * Concurrent [notifyEviction] in flight may still produce one trailing
     * callback for the just-removed entry; this is documented as eventual.
     */
    fun unregister(uid: Int, callback: IClientCallback): Boolean {
        val binder = callback.asBinder()
        return removeByBinder(uid, binder, attemptUnlink = true)
    }

    private fun removeByBinder(uid: Int, binder: IBinder, attemptUnlink: Boolean): Boolean {
        val list = byUid[uid] ?: return false
        val entry = list.firstOrNull { it.binder === binder } ?: return false
        val removed = list.remove(entry)
        if (removed && attemptUnlink) {
            try {
                binder.unlinkToDeath(entry.recipient, 0)
            } catch (_: Throwable) {
                // Death recipient already fired or binder already gone — ignore.
            }
        }
        if (list.isEmpty()) {
            byUid.remove(uid, list)
        }
        return removed
    }

    /**
     * Push a `onSessionEvicted(sessionId, reasonCode)` notice to every
     * callback registered under [uid]. Silent no-op when [uid] has no
     * registered callbacks (the common case for unowned dashboard
     * sessions or callers that never subscribed).
     *
     * Callbacks that throw [android.os.RemoteException] are removed from
     * the registry — the SDK is gone or unreachable and no further
     * notifications are useful.
     */
    fun notifyEviction(uid: Int, sessionId: String, reasonCode: Int) {
        val list = byUid[uid] ?: return
        // CopyOnWriteArrayList iterator is snapshot-consistent — safe even
        // if register/unregister fires concurrently.
        for (entry in list) {
            try {
                entry.callback.onSessionEvicted(sessionId, reasonCode)
            } catch (t: android.os.RemoteException) {
                MindlayerLog.w(
                    TAG,
                    "callback dispatch failed (uid=$uid): ${t.javaClass.simpleName} — removing",
                )
                removeByBinder(uid, entry.binder, attemptUnlink = true)
            } catch (t: Throwable) {
                // Defensive: an oneway call shouldn't throw anything else,
                // but a broken stub on the client side could. Don't let a
                // rogue callback poison the iteration.
                MindlayerLog.w(
                    TAG,
                    "callback dispatch threw (uid=$uid): ${t.javaClass.simpleName}",
                )
            }
        }
    }

    fun notifyDeferredComplete(uid: Int, requestId: String, statusCode: Int) {
        val list = byUid[uid] ?: return
        for (entry in list) {
            try {
                entry.callback.onDeferredInferenceComplete(requestId, statusCode)
            } catch (t: android.os.RemoteException) {
                MindlayerLog.w(
                    TAG,
                    "deferred callback dispatch failed (uid=$uid): ${t.javaClass.simpleName} — removing",
                )
                removeByBinder(uid, entry.binder, attemptUnlink = true)
            } catch (t: Throwable) {
                MindlayerLog.w(
                    TAG,
                    "deferred callback dispatch threw (uid=$uid): ${t.javaClass.simpleName}",
                )
            }
        }
    }
    /**
     * Tear down all registrations. Called from
     * [com.adsamcik.mindlayer.service.MindlayerMlService.onDestroy].
     */
    fun clear() {
        for ((_, list) in byUid) {
            for (entry in list) {
                try {
                    entry.binder.unlinkToDeath(entry.recipient, 0)
                } catch (_: Throwable) {
                    // ignore
                }
            }
            list.clear()
        }
        byUid.clear()
    }

    /** Total registered callbacks across all UIDs. Test/diagnostics use only. */
    val size: Int
        get() = byUid.values.sumOf { it.size }

    companion object {
        private const val TAG = "EvictionRegistry"

        /**
         * Per-UID callback cap. Generous enough for test harnesses or
         * apps that spawn multiple SDK instances; tight enough that a
         * runaway reconnect loop cannot exhaust service heap.
         */
        const val MAX_CALLBACKS_PER_UID = 8
    }
}
