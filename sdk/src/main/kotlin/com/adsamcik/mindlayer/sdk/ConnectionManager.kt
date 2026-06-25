package com.adsamcik.mindlayer.sdk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.adsamcik.mindlayer.IMindlayerService
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicReference

/**
 * Connection lifecycle for [ConnectionState].
 */
enum class ConnectionState {
    /** No binding exists. */
    DISCONNECTED,

    /** bindService() called, waiting for onServiceConnected. */
    CONNECTING,

    /** Binder is live and usable. */
    CONNECTED,

    /** Lost connection, attempting auto-reconnect. */
    RECOVERING,

    /**
     * Service rejected our `registerClient` call — the calling app is not on
     * the user-approved allowlist. The binding is torn down. We do NOT
     * auto-reconnect (that would poll the service and hit the rate limit),
     * but on the next consumer-initiated call to [ConnectionManager.awaitConnected]
     * we rebind once (subject to [REJECTION_RECHECK_COOLDOWN_MS]) and ask
     * the service again. This is what makes the "user approves in dashboard
     * → next API call works" flow seamless without requiring the consumer
     * app to be force-stopped to clear stale SDK state.
     */
    REJECTED_NOT_APPROVED,

    /**
     * Auto-reconnect exhausted [ConnectionManager.MAX_RECONNECT_ATTEMPTS]
     * consecutive failed rebind attempts. The service is assumed permanently
     * unavailable (e.g. uninstalled). No further reconnects are scheduled.
     * Call [ConnectionManager.connect] to reset the counter and try again.
     */
    BIND_GAVE_UP,
}

/**
 * Manages the AIDL service binding lifecycle with 3-signal death detection
 * and automatic reconnection with exponential backoff.
 *
 * ### Death detection signals
 *
 *  1. **[ServiceConnection.onServiceDisconnected]** — transient disconnect
 *     (e.g. service process crashed). The binding stays alive; the system
 *     will attempt to re-deliver the binder automatically.
 *
 *  2. **[ServiceConnection.onBindingDied]** — the binding itself is dead
 *     and will never recover. We must unbind, create a *fresh*
 *     [ServiceConnection], and rebind.
 *
 *  3. **[IBinder.DeathRecipient.binderDied]** — fastest signal from the
 *     kernel via `linkToDeath`. Used to eagerly invalidate the cached
 *     binder so no stale RPCs are attempted.
 */
class ConnectionManager {

    companion object {
        private const val TAG = "ConnectionManager"

        private const val SERVICE_PKG = "com.adsamcik.mindlayer"
        private const val SERVICE_CLS = "com.adsamcik.mindlayer.service.MindlayerMlService"

        /**
         * Debug-build suffix the service APK gets when built with `assembleDebug`
         * (`applicationIdSuffix = ".debug"` in `app/build.gradle.kts`). The cross-app
         * SDK transparently retries the bind against the suffixed package when
         * the canonical one is missing, but **only when the consuming app is
         * itself debuggable** (`ApplicationInfo.FLAG_DEBUGGABLE`). Release
         * client APKs cannot opt into this fallback — preventing a production
         * app from silently binding to an attacker-installed `.debug` service.
         */
        private const val SERVICE_PKG_DEBUG_SUFFIX = ".debug"

        private const val INITIAL_BACKOFF_MS = 250L
        private const val MAX_BACKOFF_MS = 5_000L
        private const val BACKOFF_MULTIPLIER = 2.0

        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000L

        /** Maximum consecutive failed rebind attempts before giving up. */
        const val MAX_RECONNECT_ATTEMPTS = 10

        /**
         * Minimum interval between two on-demand rejection re-checks triggered by
         * [awaitConnected]. When the service rejected the consumer (state
         * [ConnectionState.REJECTED_NOT_APPROVED]) the most likely cause is that
         * the user hasn't yet approved the caller in the Mindlayer dashboard.
         * The user typically approves seconds after the first rejection, so on
         * the **next** consumer call we re-bind once and ask the service again,
         * which surfaces the new approval automatically — no force-stop required.
         *
         * The floor prevents back-to-back caller code (e.g. an ensureCapability()
         * helper that retries internally on every failure) from spinning fresh
         * binds at the per-UID rate-limit on the service side. One second is
         * comfortably above the rate-limit window (per-UID throttle is measured
         * in seconds) and well below human approve-then-retry latency, so the
         * happy path always re-checks while a misbehaving client cannot
         * weaponise the recheck into a poll.
         */
        internal const val REJECTION_RECHECK_COOLDOWN_MS = 1_000L

        private val BIND_FLAGS: Int = run {
            // Base flags supported back to the SDK's minSdk:
            // - BIND_AUTO_CREATE: start the service if it isn't running.
            // - BIND_IMPORTANT: bound service should be brought to foreground
            //   procstate / adj when this client is foreground (kill-safety).
            // - BIND_ADJUST_WITH_ACTIVITY: binding tracks the calling
            //   activity's lifecycle so the OS scales the service down when
            //   the activity is gone.
            var flags = Context.BIND_AUTO_CREATE or
                Context.BIND_IMPORTANT or
                Context.BIND_ADJUST_WITH_ACTIVITY
            // BIND_INCLUDE_CAPABILITIES (API 31+, Android 12): the missing
            // piece that prevents the Android 12+ cached-app freezer from
            // freezing the Mindlayer service process while a foreground
            // client is bound. Without it, `OomAdjuster.computeServiceHost-
            // OomAdjLSP` propagates the client's procstate (BIND_IMPORTANT
            // path) but explicitly skips capability inheritance — the
            // freezer checks the capability bits, sees no foreground tie on
            // the bound side, and freezes the process between AIDL calls.
            // Once frozen, the next call surfaces as `NativeError` because
            // the in-flight inference partial-state was suspended mid-flush.
            // First-party cross-app integration already requires API 31+
            // (the SecurityException-translation path below makes this an
            // explicit terminal error on older devices), so we can add this
            // flag unconditionally on supported runtimes.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags = flags or Context.BIND_INCLUDE_CAPABILITIES
            }
            flags
        }
    }

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)

    /** Observable connection state. */
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val binderRef = AtomicReference<IMindlayerService?>(null)
    private var boundContext: Context? = null
    private var currentConnection: ServiceConnection? = null
    private var deathRecipient: IBinder.DeathRecipient? = null
    private var terminalBindFailure: MindlayerException? = null

    /**
     * Stable Binder token passed to [IMindlayerService.registerClient] so the
     * service can [linkToDeath] on it and tear down our sessions if this
     * process dies. Lives for the lifetime of this ConnectionManager.
     */
    private val livenessToken: IBinder = Binder()

    /**
     * Reconnect-scheduling scope. Runs on [Dispatchers.Default] so a service
     * crash → reconnect storm (e.g. while a heavy multimodal model is being
     * loaded by the service and the OS LMK kills it under memory pressure)
     * does **not** churn the consuming app's main thread. The two scope users
     * — [scheduleReconnect] and the inline reconnect launch — only do
     * `delay(backoffMs)` + `doBind()`, neither of which has a Main affinity:
     *  - `bindService()` can be called from any thread; the Android framework
     *    delivers [ServiceConnection] callbacks on the consumer-supplied
     *    Handler (Main by default) regardless of where bindService was called.
     *  - `_state` is a [MutableStateFlow], thread-safe under arbitrary writers.
     *
     * Previously this used `Dispatchers.Main.immediate`. That choice predated
     * the realisation that the reconnect loop runs frequently in real-world
     * use (every service kill + binding-died + relink storm), and each
     * `delay()`/`doBind()` hop charged the main thread, producing visible
     * Choreographer frame skips and contributing to ANRs in the consuming
     * app whenever the service crashed mid-inference.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var backoffMs = INITIAL_BACKOFF_MS
    private var consecutiveFailures = 0

    /**
     * R-19: the single in-flight reconnect coroutine. Every
     * [scheduleReconnect] cancels the prior job before launching a new one,
     * so a reconnect storm (repeated onBindingDied / SERVICE_THROTTLED)
     * cannot stack multiple delayed `doBind()`s that each create a live
     * binding. Cleared on a successful connect and on [disconnect].
     */
    @Volatile
    private var reconnectJob: Job? = null

    /**
     * Wall-clock millis of the last [awaitConnected]-triggered re-bind that
     * was launched in response to a [ConnectionState.REJECTED_NOT_APPROVED]
     * state. Used to floor consecutive on-demand re-checks at
     * [REJECTION_RECHECK_COOLDOWN_MS] so a tight retry loop in the consumer
     * can't weaponise the recheck into a poll. `0L` means no recheck has
     * been launched yet, so the next [awaitConnected] is free to recheck.
     */
    @Volatile
    private var lastRejectionRecheckAt: Long = 0L

    /**
     * Test seam: returns the current monotonic millis. Overridden in tests so
     * we can drive the cooldown without sleeping. Default is wall-clock
     * because the cooldown only matters between user-driven calls and the
     * elapsed time we want to gate on is the same notion the human user
     * perceives — clock drift would only loosen, not tighten, the guard.
     */
    internal var clockMillis: () -> Long = System::currentTimeMillis

    /**
     * True once [MAX_RECONNECT_ATTEMPTS] consecutive rebind attempts have
     * failed without a successful [onServiceConnected]. No further reconnects
     * are scheduled. Call [connect] to reset and retry.
     */
    @Volatile
    var bindGaveUp = false
        private set

    // -- Public API -----------------------------------------------------------

    /**
     * Bind to MindlayerMlService. Safe to call multiple times; redundant
     * calls on an already-connected manager are ignored.
     */
    fun connect(context: Context) {
        if (_state.value == ConnectionState.CONNECTED || _state.value == ConnectionState.CONNECTING) return
        if (bindGaveUp) {
            bindGaveUp = false
            terminalBindFailure = null
            consecutiveFailures = 0
            backoffMs = INITIAL_BACKOFF_MS
        }
        // Explicit connect() is the caller's "reset everything" signal — drop
        // any prior rejection-recheck timestamp so the next bind is treated
        // as a fresh attempt rather than a debounced recheck.
        lastRejectionRecheckAt = 0L
        boundContext = context.applicationContext
        doBind()
    }

    /** Unbind and release all resources. */
    fun disconnect() {
        _state.value = ConnectionState.DISCONNECTED
        reconnectJob?.cancel()
        reconnectJob = null
        doUnbind()
        scope.cancel()
    }

    /** Returns the live binder or `null` if not currently connected. */
    fun getService(): IMindlayerService? = binderRef.get()

    /**
     * Returns the application [Context] the SDK was bound with, or `null`
     * if [connect] hasn't been called yet. Internal because the SDK
     * sometimes needs scratch storage (e.g. for the encoded-OCR-image
     * transport's regular-file PFD path, see Bug #7) and pulling the
     * context off the connection avoids threading it through every
     * single AIDL entry point.
     */
    internal fun getContext(): Context? = boundContext

    /**
     * Returns the live binder or throws [IllegalStateException].
     */
    fun requireService(): IMindlayerService =
        binderRef.get() ?: throw MindlayerException(
            message = "MindlayerService is not connected (state=${_state.value})",
            code = MindlayerErrorCode.SERVICE_UNAVAILABLE,
        )

    /**
     * Report that an in-flight AIDL transaction observed a dead binder
     * (an [android.os.DeadObjectException] or other [android.os.RemoteException]).
     *
     * The async death signals ([ServiceConnection.onServiceDisconnected],
     * [ServiceConnection.onBindingDied], `linkToDeath`) may not have fired yet
     * at the moment a transaction throws, so the cached binder can still point
     * at the just-died process. Calling this from the SDK's AIDL chokepoint
     * invalidates that stale binder and moves the state machine to
     * [ConnectionState.RECOVERING], guaranteeing the next [awaitConnected]
     * blocks for a freshly re-delivered binder instead of handing back the
     * dead one (which would burn the caller's retry on an instant repeat
     * failure).
     *
     * Race-safe: it only invalidates when [dead] is still the cached binder,
     * so a concurrent reconnect that already installed a fresh binder is left
     * untouched. Passing `null` forces invalidation unconditionally.
     */
    internal fun reportBinderDeath(dead: IMindlayerService? = null) {
        if (dead != null && binderRef.get() !== dead) {
            // A reconnect already swapped in a fresh binder — nothing to do.
            return
        }
        onBinderDied()
    }

    /**
     * Suspends until the connection reaches [ConnectionState.CONNECTED] and
     * returns a validated binder reference, or throws
     * [kotlinx.coroutines.TimeoutCancellationException] after [timeoutMs].
     *
     * Handles the TOCTOU race where the binder can die between the state
     * transition and the binder read by retrying — the state machine
     * guarantees that binder death moves state to RECOVERING.
     */
    suspend fun awaitConnected(
        timeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    ): IMindlayerService = try {
        withTimeout(timeoutMs) {
            // Tracks whether we've already retried the bind once for the
            // REJECTED_NOT_APPROVED path in this single call. After one
            // rebind, a second rejection is treated as authoritative — we
            // don't loop indefinitely waiting for approval (the caller is
            // already inside their own timeout budget).
            var rejectionRebindAttempted = false

            while (true) {
                _state.first {
                    it == ConnectionState.CONNECTED ||
                    it == ConnectionState.REJECTED_NOT_APPROVED ||
                    it == ConnectionState.BIND_GAVE_UP
                }
                if (_state.value == ConnectionState.REJECTED_NOT_APPROVED) {
                    // The most common reason we land here is that the user
                    // approved the caller in the Mindlayer dashboard between
                    // the first bind (which got rejected) and this call.
                    // Re-bind once and ask the service again rather than
                    // surfacing a stale rejection that would force the
                    // consumer app to be force-stopped to clear SDK state.
                    //
                    // Gated by REJECTION_RECHECK_COOLDOWN_MS so a tight
                    // retry loop in the consumer can't poll the service.
                    // Once we've rebound once inside this awaitConnected
                    // call, a second REJECTED is authoritative and we
                    // throw — the user clearly hasn't approved yet.
                    val now = clockMillis()
                    val canRecheck = !rejectionRebindAttempted &&
                        boundContext != null &&
                        (now - lastRejectionRecheckAt) >= REJECTION_RECHECK_COOLDOWN_MS
                    if (canRecheck) {
                        lastRejectionRecheckAt = now
                        rejectionRebindAttempted = true
                        Log.i(
                            TAG,
                            "awaitConnected: REJECTED_NOT_APPROVED — rebinding once " +
                                "in case the user just approved (cooldown ${REJECTION_RECHECK_COOLDOWN_MS}ms)",
                        )
                        // Clean up the rejected binding state then rebind.
                        // doUnbind is a no-op if we're already unbound (the
                        // rejected-onServiceConnected path already calls it).
                        doUnbind()
                        _state.value = ConnectionState.CONNECTING
                        doBind()
                        // Loop and re-await the next terminal state. The
                        // rebound bind will land in CONNECTED (approved),
                        // REJECTED_NOT_APPROVED (still not approved →
                        // throws below on next iteration), or BIND_GAVE_UP
                        // (also caught below).
                        continue
                    }
                    throw MindlayerException(
                        message = "Mindlayer rejected this app — user approval required in the Mindlayer dashboard",
                        code = MindlayerErrorCode.PERMISSION_DENIED,
                    )
                }
                if (_state.value == ConnectionState.BIND_GAVE_UP) {
                    terminalBindFailure?.let { throw it }
                    throw MindlayerException(
                        message = "Mindlayer service permanently unavailable after $MAX_RECONNECT_ATTEMPTS attempts; call connect() to retry",
                        code = MindlayerErrorCode.SERVICE_UNAVAILABLE,
                    )
                }
                val service = binderRef.get()
                if (service != null) return@withTimeout service
                // Binder invalidated between state transition and our read.
                // State should now be RECOVERING — loop to wait for reconnect.
                Log.w(TAG, "awaitConnected: binder invalidated after CONNECTED; retrying")
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }
    } catch (e: TimeoutCancellationException) {
        throw MindlayerException(
            message = "Timed out after ${timeoutMs}ms waiting for Mindlayer service connection",
            code = MindlayerErrorCode.CONNECT_TIMEOUT,
            cause = e,
        )
    }

    // -- Binding internals ----------------------------------------------------

    private fun doBind() {
        val ctx = boundContext ?: return
        // R-19: never leak a prior binding. If a previous ServiceConnection
        // is still registered (e.g. a reconnect raced an existing bind),
        // unbind it before creating a new one so we can't end up with two
        // live ServiceConnections fighting over binderRef / currentConnection.
        if (currentConnection != null) {
            doUnbind()
        }
        _state.value = ConnectionState.CONNECTING

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder == null) {
                    Log.w(TAG, "onServiceConnected with null binder")
                    return
                }
                Log.i(TAG, "onServiceConnected")
                val service = IMindlayerService.Stub.asInterface(binder)
                binderRef.set(service)
                backoffMs = INITIAL_BACKOFF_MS
                consecutiveFailures = 0
                bindGaveUp = false

                // Signal 3: linkToDeath for fast kernel-level death notification
                val recipient = IBinder.DeathRecipient { onBinderDied() }
                try {
                    binder.linkToDeath(recipient, 0)
                } catch (_: Exception) {
                    // Binder already dead
                    onBinderDied()
                    return
                }
                deathRecipient = recipient

                // Register a liveness token so the service can tear down our
                // sessions if this process dies (service-side linkToDeath).
                // If the service rejects us (caller not on allowlist), do NOT
                // publish CONNECTED — that would lie about the binding being
                // usable and every subsequent RPC would throw SecurityException.
                try {
                    service.registerClient(livenessToken)
                } catch (e: SecurityException) {
                    // F-074: special-case the crash-loop watchdog throttle.
                    // The service is alive but is rejecting binds while it
                    // recovers — back off until cooldownEndsAt and retry
                    // instead of spinning REJECTED_NOT_APPROVED → reconnect
                    // → REJECTED again on the next bind, which would
                    // re-trigger the OOM-killer that caused the throttle in
                    // the first place.
                    val typed = MindlayerException.fromAidlSecurityException(e)
                    if (typed?.code == MindlayerErrorCode.SERVICE_THROTTLED) {
                        val now = System.currentTimeMillis()
                        val cooldownEndsAt = typed.cooldownEndsAt
                        // Coerce to a sane minimum so a clock-skew or
                        // already-expired cooldown doesn't degenerate into
                        // a hot retry; cap at MAX_BACKOFF_MS so a
                        // pathological future timestamp doesn't pin us
                        // forever.
                        val rawWait = if (cooldownEndsAt != null) {
                            cooldownEndsAt - now
                        } else {
                            MAX_BACKOFF_MS
                        }
                        val waitMs = rawWait
                            .coerceAtLeast(INITIAL_BACKOFF_MS)
                            .coerceAtMost(MAX_BACKOFF_MS)
                        Log.w(
                            TAG,
                            "registerClient SERVICE_THROTTLED — retrying in ${waitMs}ms" +
                                " (cooldownEndsAt=$cooldownEndsAt)",
                        )
                        try { binder.unlinkToDeath(recipient, 0) } catch (_: Throwable) { }
                        deathRecipient = null
                        invalidateBinder()
                        _state.value = ConnectionState.RECOVERING
                        doUnbind()
                        scheduleReconnect(waitMs)
                        return
                    }
                    Log.w(TAG, "registerClient rejected — caller not approved", e)
                    try { binder.unlinkToDeath(recipient, 0) } catch (_: Throwable) { }
                    deathRecipient = null
                    invalidateBinder()
                    _state.value = ConnectionState.REJECTED_NOT_APPROVED
                    doUnbind()
                    return
                } catch (e: Exception) {
                    // R-19: a non-security failure (e.g. RemoteException) means
                    // the service never registered our liveness token, so the
                    // service-side linkToDeath teardown of our sessions won't
                    // fire. Pre-fix we published CONNECTED anyway, handing out a
                    // binder the service isn't tracking. Treat it as a
                    // recoverable bind failure instead: unlink, invalidate,
                    // unbind, and reconnect so a fresh registerClient is
                    // attempted.
                    Log.w(TAG, "registerClient failed (transient) — recovering", e)
                    try { binder.unlinkToDeath(recipient, 0) } catch (_: Throwable) { }
                    deathRecipient = null
                    invalidateBinder()
                    _state.value = ConnectionState.RECOVERING
                    doUnbind()
                    scheduleReconnect()
                    return
                }

                // R-19: registration succeeded — cancel any pending reconnect
                // so a stale delayed doBind() can't tear down this live binding.
                reconnectJob?.cancel()
                reconnectJob = null
                _state.value = ConnectionState.CONNECTED
                // Successful registration — reset the rejection-recheck floor
                // so a future REJECTED_NOT_APPROVED (e.g. user revokes approval
                // after a hot restart) gets a fresh recheck window rather than
                // inheriting a stale timestamp from a previous failed bind.
                lastRejectionRecheckAt = 0L
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                // Signal 1: transient disconnect — system may reconnect automatically
                Log.w(TAG, "onServiceDisconnected (transient)")
                invalidateBinder()
                _state.value = ConnectionState.RECOVERING
            }

            override fun onBindingDied(name: ComponentName?) {
                // Signal 2: binding dead forever — must unbind + fresh rebind
                Log.w(TAG, "onBindingDied — scheduling fresh rebind")
                invalidateBinder()
                _state.value = ConnectionState.RECOVERING
                doUnbind()
                scheduleReconnect()
            }
        }

        currentConnection = conn

        // Resolve which service-package to bind to. Prefer the canonical
        // release package; fall back to the .debug-suffixed variant when
        // it's the only one installed AND the consuming app is itself a
        // debug build (FLAG_DEBUGGABLE). The flag is set by the build
        // system for `debug` build types and cannot be turned on for a
        // release-signed APK, so a production client can never silently
        // bind to a `.debug` service that an attacker installed.
        val pm = ctx.packageManager
        val callerIsDebuggable =
            (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val debugPkg = SERVICE_PKG + SERVICE_PKG_DEBUG_SUFFIX
        val resolvedPkg = when {
            pm.isPackageInstalled(SERVICE_PKG) -> SERVICE_PKG
            callerIsDebuggable && pm.isPackageInstalled(debugPkg) -> {
                Log.i(TAG, "Resolved Mindlayer service to debug fallback $debugPkg (caller is debuggable)")
                debugPkg
            }
            else -> SERVICE_PKG // last resort — bind will fail with the canonical name
        }
        val intent = Intent().apply {
            component = ComponentName(resolvedPkg, SERVICE_CLS)
        }

        val bound = try {
            ctx.bindService(intent, conn, BIND_FLAGS)
        } catch (e: SecurityException) {
            Log.e(TAG, "bindService denied", e)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                terminalBindFailure = MindlayerException(
                    message = "Mindlayer first-party cross-app integration requires Android 12 (API 31) or later. This device is running Android ${Build.VERSION.SDK_INT}.",
                    code = MindlayerErrorCode.UNSUPPORTED_ANDROID_VERSION,
                    cause = e,
                )
                bindGaveUp = true
                _state.value = ConnectionState.BIND_GAVE_UP
                return
            }
            terminalBindFailure = MindlayerException(
                message = "Android denied binding to Mindlayer service",
                code = MindlayerErrorCode.PERMISSION_DENIED,
                cause = e,
            )
            bindGaveUp = true
            _state.value = ConnectionState.BIND_GAVE_UP
            return
        }

        if (!bound) {
            Log.e(TAG, "bindService returned false — service not found?")
            // M-T1: On API < 31, `bindService` can ALSO return false (rather
            // than throwing SecurityException) when the system refuses the
            // bind for permission/visibility reasons. Surface the same typed
            // terminal error as the SecurityException branch above so callers
            // get a consistent UNSUPPORTED_ANDROID_VERSION signal instead of
            // a silent DISCONNECTED.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                terminalBindFailure = MindlayerException(
                    message = "Mindlayer first-party cross-app integration requires Android 12 (API 31) or later. This device is running Android ${Build.VERSION.SDK_INT}.",
                    code = MindlayerErrorCode.UNSUPPORTED_ANDROID_VERSION,
                    cause = null,
                )
                bindGaveUp = true
                _state.value = ConnectionState.BIND_GAVE_UP
                return
            }
            terminalBindFailure = MindlayerException(
                message = "Mindlayer service unavailable; bindService returned false",
                code = MindlayerErrorCode.SERVICE_UNAVAILABLE,
            )
            bindGaveUp = true
            _state.value = ConnectionState.BIND_GAVE_UP
        }
    }

    private fun doUnbind() {
        val ctx = boundContext ?: return
        val conn = currentConnection ?: return
        try {
            ctx.unbindService(conn)
        } catch (_: IllegalArgumentException) {
            // Not bound — fine
        }
        invalidateBinder()
        currentConnection = null
    }

    /** Invoked by linkToDeath (signal 3). */
    private fun onBinderDied() {
        Log.w(TAG, "binderDied — binder invalidated")
        invalidateBinder()
        if (_state.value != ConnectionState.DISCONNECTED) {
            _state.value = ConnectionState.RECOVERING
        }
    }

    private fun invalidateBinder() {
        val old = binderRef.getAndSet(null)
        if (old != null) {
            val recipient = deathRecipient
            if (recipient != null) {
                try {
                    old.asBinder().unlinkToDeath(recipient, 0)
                } catch (_: Exception) { /* already unlinked */ }
                deathRecipient = null
            }
        }
    }

    private fun scheduleReconnect() {
        if (bindGaveUp) {
            _state.value = ConnectionState.BIND_GAVE_UP
            return
        }
        consecutiveFailures++
        if (consecutiveFailures >= MAX_RECONNECT_ATTEMPTS) {
            bindGaveUp = true
            _state.value = ConnectionState.BIND_GAVE_UP
            Log.w(TAG, "Bind gave up after $MAX_RECONNECT_ATTEMPTS attempts; explicit reconnect required")
            return
        }
        // R-19: single in-flight reconnect — cancel any prior scheduled
        // rebind so a storm of triggers can't stack multiple doBind()s.
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val wait = backoffMs
            Log.i(TAG, "Reconnecting in ${wait}ms")
            delay(wait)
            backoffMs = (backoffMs * BACKOFF_MULTIPLIER).toLong().coerceAtMost(MAX_BACKOFF_MS)
            doBind()
        }
    }

    /**
     * F-074: deferred reconnect with a caller-supplied delay (used by the
     * `SERVICE_THROTTLED` retry path so we wait for the service-published
     * `cooldownEndsAt` instead of the local exponential-backoff cursor).
     * Does **not** advance [backoffMs] — once the cooldown elapses, a
     * fresh `registerClient` either succeeds (back to CONNECTED) or
     * fails again (cycle repeats with the same caller-supplied wait).
     */
    private fun scheduleReconnect(delayMs: Long) {
        // R-19: single in-flight reconnect — cancel any prior scheduled rebind.
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            Log.i(TAG, "Reconnecting in ${delayMs}ms (deferred)")
            delay(delayMs)
            doBind()
        }
    }

    private fun PackageManager.isPackageInstalled(packageName: String): Boolean = try {
        @Suppress("DEPRECATION")
        getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
