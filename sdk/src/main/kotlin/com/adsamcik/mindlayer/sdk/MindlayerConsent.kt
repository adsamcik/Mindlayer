package com.adsamcik.mindlayer.sdk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.ServiceConnection
import android.os.IBinder
import com.adsamcik.mindlayer.IMindlayerService
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Result of [MindlayerConsent.requestConsent].
 *
 * The consent-Intent flow (v0.10) is how a client app obtains the user's
 * permission to use Mindlayer's on-device AI. When an AIDL call fails with
 * [MindlayerErrorCode.CONSENT_REQUIRED], call [MindlayerConsent.requestConsent]
 * and, if [Available], launch the returned [IntentSender] with
 * `ActivityResultContracts.StartIntentSenderForResult`. `RESULT_OK` means the
 * user approved and the next bind/call will succeed.
 *
 * See `docs/CONSENT_ARCHITECTURE.md` and `SDK_INTEGRATION.md`.
 */
sealed interface ConsentRequestResult {
    /** Launch [intentSender] to show the Mindlayer consent screen. */
    data class Available(val intentSender: IntentSender) : ConsentRequestResult

    /** This app is already approved — no consent screen needed. */
    data object AlreadyApproved : ConsentRequestResult

    /**
     * The user previously denied this app. [untilEpochMs] is the ms-since-epoch
     * at which a temporary (24h) denial or escalation cooldown lifts, or `null`
     * for a permanent block (the user must unblock from the Mindlayer dashboard).
     */
    data class Denied(val untilEpochMs: Long?) : ConsentRequestResult

    /** The Mindlayer service is not installed or could not be bound. */
    data object ServiceUnavailable : ConsentRequestResult

    /** Any other failure. [code] is a [MindlayerErrorCode]; [message] is best-effort. */
    data class Failed(val code: Int, val message: String?) : ConsentRequestResult
}

/**
 * Entry point for the v0.10 consent-Intent flow.
 *
 * This is additive to the existing [Mindlayer] client surface: a host app
 * calls [requestConsent] when it needs the user's permission (typically after
 * a `CONSENT_REQUIRED` failure), launches the returned `IntentSender`, and
 * retries its Mindlayer calls on `RESULT_OK`.
 */
object MindlayerConsent {

    private const val SERVICE_PKG = "com.adsamcik.mindlayer.service"
    private const val SERVICE_PKG_DEBUG = "com.adsamcik.mindlayer.service.debug"
    private const val SERVICE_CLS = "com.adsamcik.mindlayer.service.MindlayerMlService"
    private const val BIND_TIMEOUT_MS = 5_000L

    /**
     * Request user consent for this app to use Mindlayer.
     *
     * Performs a transient bind to the Mindlayer service, asks it for a consent
     * challenge (which captures this app's identity server-side via Binder),
     * and returns an [IntentSender] the host should launch. The bind is dropped
     * before returning.
     *
     * Safe to call off the main thread; it suspends on the bind + AIDL call.
     */
    suspend fun requestConsent(context: Context): ConsentRequestResult {
        val appContext = context.applicationContext
        val (service, connection) = bind(appContext)
            ?: return ConsentRequestResult.ServiceUnavailable
        try {
            return withContext(Dispatchers.IO) {
                try {
                    val challenge = service.requestConsentChallenge()
                    ConsentRequestResult.Available(challenge.consentIntent.intentSender)
                } catch (t: Throwable) {
                    classifyError(t)
                }
            }
        } finally {
            try { appContext.unbindService(connection) } catch (_: Throwable) { }
        }
    }

    private fun classifyError(t: Throwable): ConsentRequestResult {
        return classifyWireMessage(t.message)
    }

    @androidx.annotation.VisibleForTesting
    internal fun classifyWireMessage(wireMessage: String?): ConsentRequestResult {
        val code = MindlayerErrorCode.codeFromWireMessage(wireMessage)
        val msg = MindlayerErrorCode.messageFromWireMessage(wireMessage)
        return when (code) {
            MindlayerErrorCode.INVALID_REQUEST ->
                // requestConsentChallenge throws INVALID_REQUEST when the
                // caller is already approved.
                ConsentRequestResult.AlreadyApproved
            MindlayerErrorCode.CONSENT_DENIED ->
                ConsentRequestResult.Denied(parseUntil(msg))
            null -> ConsentRequestResult.Failed(MindlayerErrorCode.UNKNOWN, wireMessage)
            else -> ConsentRequestResult.Failed(code, msg)
        }
    }

    /** Parse `until=<epochMs>` or `until=permanent` from a CONSENT_DENIED message. */
    @androidx.annotation.VisibleForTesting
    internal fun parseUntil(message: String?): Long? {
        if (message == null) return null
        val token = message.split(' ', ',')
            .firstOrNull { it.startsWith("until=") }
            ?.removePrefix("until=")
            ?: return null
        return token.toLongOrNull() // null for "permanent"
    }

    private suspend fun bind(context: Context): Pair<IMindlayerService, ServiceConnection>? {
        val resolvedPkg = resolveServicePackage(context) ?: return null
        val intent = Intent().apply {
            component = ComponentName(resolvedPkg, SERVICE_CLS)
        }
        var conn: ServiceConnection? = null
        val service = withTimeoutOrNull(BIND_TIMEOUT_MS) {
            suspendCoroutine<IMindlayerService?> { cont ->
                val c = object : ServiceConnection {
                    private var resumed = false
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        if (resumed) return
                        resumed = true
                        cont.resume(IMindlayerService.Stub.asInterface(binder))
                    }
                    override fun onServiceDisconnected(name: ComponentName?) {}
                    override fun onNullBinding(name: ComponentName?) {
                        if (resumed) return
                        resumed = true
                        cont.resume(null)
                    }
                }
                conn = c
                val ok = try {
                    context.bindService(intent, c, Context.BIND_AUTO_CREATE)
                } catch (_: Throwable) {
                    false
                }
                if (!ok) {
                    cont.resume(null)
                }
            }
        }
        val c = conn
        if (service == null || c == null) {
            if (c != null) try { context.unbindService(c) } catch (_: Throwable) {}
            return null
        }
        return service to c
    }

    private fun resolveServicePackage(context: Context): String? {
        val pm = context.packageManager
        if (isInstalled(pm, SERVICE_PKG)) return SERVICE_PKG
        val debuggable = (context.applicationInfo.flags and
            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable && isInstalled(pm, SERVICE_PKG_DEBUG)) return SERVICE_PKG_DEBUG
        return null
    }

    private fun isInstalled(pm: android.content.pm.PackageManager, pkg: String): Boolean = try {
        pm.getPackageInfo(pkg, 0)
        true
    } catch (_: Throwable) {
        false
    }
}
