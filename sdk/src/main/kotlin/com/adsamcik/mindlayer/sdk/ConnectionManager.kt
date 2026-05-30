package com.adsamcik.mindlayer.sdk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.adsamcik.mindlayer.IMindlayerService
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
     * the user-approved allowlist. The binding is torn down. The client must
     * wait for user approval in the Mindlayer dashboard and call [connect]
     * again. This is a terminal state until the caller explicitly retries;
     * we do not auto-reconnect because that would poll the service and hit
     * the rate limit.
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

        private const val SERVICE_PKG = "com.adsamcik.mindlayer.service"
        private const val SERVICE_CLS = "com.adsamcik.mindlayer.service.MindlayerMlService"

        /**
         * Debug-build suffix the service APK gets when built with `assembleDebug`
         * (`applicationIdSuffix = ".debug"` in `app/build.gradle.kts`). The cross-app
         * SDK transparently retries the bind against the suffixed package when
         * the canonical one is missing, so first-party developer / driver apps
         * can iterate against a locally-installed debug service without a
         * separate signing keystore.
         */
        private const val SERVICE_PKG_DEBUG_SUFFIX = ".debug"

        private const val INITIAL_BACKOFF_MS = 250L
        private const val MAX_BACKOFF_MS = 5_000L
        private const val BACKOFF_MULTIPLIER = 2.0

        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000L

        /** Maximum consecutive failed rebind attempts before giving up. */
        const val MAX_RECONNECT_ATTEMPTS = 10

        private const val BIND_FLAGS =
            Context.BIND_AUTO_CREATE or
            Context.BIND_IMPORTANT or
            Context.BIND_ADJUST_WITH_ACTIVITY
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var backoffMs = INITIAL_BACKOFF_MS
    private var consecutiveFailures = 0

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
        boundContext = context.applicationContext
        doBind()
    }

    /** Unbind and release all resources. */
    fun disconnect() {
        _state.value = ConnectionState.DISCONNECTED
        doUnbind()
        scope.cancel()
    }

    /** Returns the live binder or `null` if not currently connected. */
    fun getService(): IMindlayerService? = binderRef.get()

    /**
     * Returns the live binder or throws [IllegalStateException].
     */
    fun requireService(): IMindlayerService =
        binderRef.get() ?: throw MindlayerException(
            message = "MindlayerService is not connected (state=${_state.value})",
            code = MindlayerErrorCode.SERVICE_UNAVAILABLE,
        )

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
            while (true) {
                _state.first {
                    it == ConnectionState.CONNECTED ||
                    it == ConnectionState.REJECTED_NOT_APPROVED ||
                    it == ConnectionState.BIND_GAVE_UP
                }
                if (_state.value == ConnectionState.REJECTED_NOT_APPROVED) {
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
                    Log.w(TAG, "registerClient failed (transient)", e)
                    // Non-security failure (e.g. RemoteException mid-bind) — fall
                    // through to CONNECTED; the next RPC will surface any issue.
                }

                _state.value = ConnectionState.CONNECTED
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
        // it's the only one installed (developer iteration). This makes
        // the SDK usable against a locally-built debug service APK
        // without forcing every dev to provision a release keystore.
        val pm = ctx.packageManager
        val resolvedPkg = when {
            pm.isPackageInstalled(SERVICE_PKG) -> SERVICE_PKG
            pm.isPackageInstalled(SERVICE_PKG + SERVICE_PKG_DEBUG_SUFFIX) ->
                SERVICE_PKG + SERVICE_PKG_DEBUG_SUFFIX
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
        scope.launch {
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
        scope.launch {
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
