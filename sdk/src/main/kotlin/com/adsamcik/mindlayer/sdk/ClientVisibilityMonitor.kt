package com.adsamcik.mindlayer.sdk

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/** Process-level visibility source used to coordinate service idle release. */
internal interface ClientVisibilityMonitor : AutoCloseable {
    val visible: StateFlow<Boolean>
}

/**
 * Reports whether this SDK client's Android process has at least one started
 * activity. [ProcessLifecycleOwner] supplies the configuration-change debounce
 * and aggregates every activity in the process, which is more reliable than
 * trying to infer visibility from a long-lived application-context binding.
 */
internal class ProcessClientVisibilityMonitor(
    private val lifecycle: Lifecycle = ProcessLifecycleOwner.get().lifecycle,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) : ClientVisibilityMonitor, DefaultLifecycleObserver {

    private val closed = AtomicBoolean(false)
    private val registered = AtomicBoolean(false)
    private val _visible = MutableStateFlow(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))

    override val visible: StateFlow<Boolean> = _visible.asStateFlow()

    init {
        runOnMain {
            if (!closed.get()) {
                lifecycle.addObserver(this)
                registered.set(true)
                _visible.value = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        _visible.value = true
    }

    override fun onStop(owner: LifecycleOwner) {
        _visible.value = false
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runOnMain {
            if (registered.compareAndSet(true, false)) {
                lifecycle.removeObserver(this)
            }
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
}
