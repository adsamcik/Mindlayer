package com.adsamcik.mindlayer.service.ui.consent

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.mindlayer.ConsentDecision
import com.adsamcik.mindlayer.ConsentIdentity
import com.adsamcik.mindlayer.IMindlayerService
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** UI state for [ConsentActivity]. */
sealed interface ConsentUiState {
    /** Binding to `:ml` and resolving the challenge nonce. */
    data object Loading : ConsentUiState

    /** Challenge resolved — render the consent card for [identity]. */
    data class Prompt(val identity: ConsentIdentity) : ConsentUiState

    /** A decision is being submitted to `:ml`. */
    data object Submitting : ConsentUiState

    /**
     * Terminal state — the activity should finish with [granted] mapped to
     * `RESULT_OK` / `RESULT_CANCELED`. [reason] is for logging only.
     */
    data class Finished(val granted: Boolean, val reason: String) : ConsentUiState

    /** The challenge could not be resolved (expired / consumed / unknown). */
    data class Error(val expired: Boolean) : ConsentUiState
}

/**
 * Drives the consent screen: binds to the in-process `:ml` service, resolves
 * the challenge nonce to a [ConsentIdentity] via `lookupChallenge`, and
 * submits the user's decision via `completeConsent`.
 *
 * Both AIDL methods are self-UID gated server-side; this ViewModel runs in
 * Mindlayer's main process, so the calls are same-UID and authorized.
 *
 * Survives configuration changes (the activity declares `configChanges`, so
 * recreation is rare), keeping the resolved identity and avoiding a second
 * `lookupChallenge` round-trip on rotation.
 */
class ConsentViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<ConsentUiState>(ConsentUiState.Loading)
    val state: StateFlow<ConsentUiState> = _state.asStateFlow()

    private var service: IMindlayerService? = null
    private var bound = false
    private var nonce: String? = null
    private var decisionSubmitted = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IMindlayerService.Stub.asInterface(binder)
            resolveChallenge()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    /** Bind to `:ml` and begin resolving [challengeNonce]. */
    fun start(context: Context, challengeNonce: String?) {
        if (bound) return
        nonce = challengeNonce
        if (challengeNonce.isNullOrEmpty()) {
            _state.value = ConsentUiState.Error(expired = false)
            return
        }
        val intent = Intent().apply {
            component = ComponentName(
                context.packageName,
                "com.adsamcik.mindlayer.service.MindlayerMlService",
            )
        }
        bound = try {
            context.applicationContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (t: Throwable) {
            MindlayerLog.w(TAG, "Consent bind failed: ${t.javaClass.simpleName}")
            false
        }
        if (!bound) {
            _state.value = ConsentUiState.Error(expired = false)
        }
    }

    private fun resolveChallenge() {
        val svc = service ?: return
        val n = nonce ?: return
        viewModelScope.launch {
            val identity = withContext(Dispatchers.IO) {
                try {
                    svc.lookupChallenge(n)
                } catch (t: Throwable) {
                    MindlayerLog.w(TAG, "lookupChallenge failed: ${t.javaClass.simpleName}")
                    null
                }
            }
            _state.value = if (identity == null) {
                ConsentUiState.Error(expired = true)
            } else {
                ConsentUiState.Prompt(identity)
            }
        }
    }

    /** Submit [kind] (one of `ConsentDecision.KIND_*`). */
    fun submit(kind: Int) {
        val svc = service
        val n = nonce
        if (svc == null || n == null || decisionSubmitted) return
        decisionSubmitted = true
        _state.value = ConsentUiState.Submitting
        viewModelScope.launch {
            val granted = withContext(Dispatchers.IO) {
                try {
                    svc.completeConsent(n, ConsentDecision(kind = kind))
                    kind == ConsentDecision.KIND_GRANT
                } catch (t: Throwable) {
                    MindlayerLog.w(TAG, "completeConsent failed: ${t.javaClass.simpleName}")
                    false
                }
            }
            _state.value = ConsentUiState.Finished(
                granted = granted,
                reason = "decision_${ConsentDecision.nameOfKind(kind)}",
            )
        }
    }

    /**
     * Best-effort dismiss when the user leaves without an explicit decision
     * (back button, swipe-away). Records a `DENY_ONCE` so the per-(pkg,sig)
     * dismiss escalation still advances. No-op if a decision was already
     * submitted.
     */
    fun dismissIfUndecided() {
        if (decisionSubmitted) return
        val svc = service ?: return
        val n = nonce ?: return
        decisionSubmitted = true
        // Fire-and-forget on a background thread; the activity is finishing.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                svc.completeConsent(n, ConsentDecision(kind = ConsentDecision.KIND_DENY_ONCE))
            } catch (_: Throwable) {
                // Activity is going away; nothing to surface.
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (bound) {
            try {
                getApplication<Application>().unbindService(connection)
            } catch (_: Throwable) {
                // Already unbound.
            }
            bound = false
        }
    }

    private companion object {
        private const val TAG = "ConsentViewModel"
    }
}
