package com.adsamcik.mindlayer.service.modeldelivery

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.logging.safeLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

internal enum class RuntimeControlFailure {
    RELEASE_FAILED,
    ACTIVATION_FAILED,
}

internal sealed interface RuntimeReleaseResult {
    data object Released : RuntimeReleaseResult
    data object NotRunning : RuntimeReleaseResult
    data class Failed(val reason: RuntimeControlFailure) : RuntimeReleaseResult
}

internal sealed interface RuntimeActivationResult {
    data object Activated : RuntimeActivationResult
    data object NotRunning : RuntimeActivationResult
    data class Failed(val reason: RuntimeControlFailure) : RuntimeActivationResult
}

internal interface ModelRuntimeControl {
    suspend fun quiesce(family: ModelFamily): RuntimeReleaseResult

    suspend fun activate(family: ModelFamily): RuntimeActivationResult
}

internal sealed interface LiveRuntimeReleaseResult {
    data object Released : LiveRuntimeReleaseResult
    data class RequiresProcessExit(val pid: Int) : LiveRuntimeReleaseResult
}

internal interface LiveModelRuntimeController {
    suspend fun quiesce(family: ModelFamily): LiveRuntimeReleaseResult

    suspend fun activate(family: ModelFamily)
}

internal class DefaultLiveModelRuntimeController(
    private val quiesceAction: suspend (ModelFamily) -> LiveRuntimeReleaseResult,
    private val retryOcrActivation: suspend () -> Unit,
    private val recordCleanShutdownBeforeProcessExit: () -> Unit,
) : LiveModelRuntimeController {
    override suspend fun quiesce(family: ModelFamily): LiveRuntimeReleaseResult {
        val result = quiesceAction(family)
        if (result is LiveRuntimeReleaseResult.RequiresProcessExit) {
            try {
                recordCleanShutdownBeforeProcessExit()
            } catch (error: Throwable) {
                MindlayerLog.w(
                    TAG,
                    "Could not record clean shutdown before planned process exit: ${error.safeLabel()}",
                    throwable = null,
                )
                throw IllegalStateException(
                    "Clean shutdown could not be recorded before planned process exit",
                )
            }
        }
        return result
    }

    override suspend fun activate(family: ModelFamily) {
        if (family == ModelFamily.OCR) {
            retryOcrActivation()
        }
    }

    private companion object {
        const val TAG = "ModelRuntimeControl"
    }
}

internal object ModelRuntimeControlRegistry {
    private val controller = AtomicReference<LiveModelRuntimeController?>()

    fun install(value: LiveModelRuntimeController) {
        controller.set(value)
    }

    fun clear(value: LiveModelRuntimeController) {
        controller.compareAndSet(value, null)
    }

    fun current(): LiveModelRuntimeController? = controller.get()
}

/**
 * Main-process client for the private `:ml` runtime-control receiver.
 */
internal class BroadcastModelRuntimeControl(
    context: Context,
) : ModelRuntimeControl {
    private val appContext = context.applicationContext

    override suspend fun quiesce(family: ModelFamily): RuntimeReleaseResult {
        val reply = send(COMMAND_QUIESCE, family)
        return when (reply.code) {
            RESULT_NOT_RUNNING -> RuntimeReleaseResult.NotRunning
            RESULT_OK -> {
                val pid = reply.extras?.getInt(EXTRA_EXIT_PID, 0) ?: 0
                if (pid > 0) {
                    awaitProcessExit(pid)
                }
                RuntimeReleaseResult.Released
            }
            else -> RuntimeReleaseResult.Failed(RuntimeControlFailure.RELEASE_FAILED)
        }
    }

    override suspend fun activate(family: ModelFamily): RuntimeActivationResult =
        when (send(COMMAND_ACTIVATE, family).code) {
            RESULT_NOT_RUNNING -> RuntimeActivationResult.NotRunning
            RESULT_OK -> RuntimeActivationResult.Activated
            else -> RuntimeActivationResult.Failed(RuntimeControlFailure.ACTIVATION_FAILED)
        }

    private suspend fun send(command: String, family: ModelFamily): RuntimeReply =
        suspendCancellableCoroutine { continuation ->
            val intent = Intent(appContext, ModelRuntimeControlReceiver::class.java).apply {
                action = ACTION_RUNTIME_CONTROL
                putExtra(EXTRA_COMMAND, command)
                putExtra(EXTRA_FAMILY, family.name)
            }
            val finalReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (continuation.isActive) {
                        continuation.resume(
                            RuntimeReply(
                                code = resultCode,
                                extras = getResultExtras(false),
                            ),
                        )
                    }
                }
            }
            runCatching {
                appContext.sendOrderedBroadcast(
                    intent,
                    null,
                    finalReceiver,
                    null,
                    RESULT_NOT_RUNNING,
                    null,
                    null,
                )
            }.onFailure {
                if (continuation.isActive) {
                    continuation.resume(RuntimeReply(RESULT_FAILED, null))
                }
            }
        }

    private suspend fun awaitProcessExit(pid: Int) {
        val activityManager = appContext.getSystemService(ActivityManager::class.java)
        while (
            File("/proc/$pid").exists() ||
            activityManager.runningAppProcesses?.any { process -> process.pid == pid } == true
        ) {
            delay(PROCESS_EXIT_POLL_MS)
        }
    }

    private data class RuntimeReply(
        val code: Int,
        val extras: Bundle?,
    )

    private companion object {
        const val PROCESS_EXIT_POLL_MS = 50L
    }
}

/**
 * Non-exported receiver hosted in `:ml`. Third-party UIDs cannot invoke it.
 */
class ModelRuntimeControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RUNTIME_CONTROL) return
        val command = intent.getStringExtra(EXTRA_COMMAND)
        val family = intent.getStringExtra(EXTRA_FAMILY)
            ?.let { name -> runCatching { ModelFamily.valueOf(name) }.getOrNull() }
        if (command == null || family == null) {
            resultCode = RESULT_FAILED
            return
        }

        val pending = goAsync()
        receiverScope.launch {
            try {
                val controller = ModelRuntimeControlRegistry.current()
                if (controller == null) {
                    pending.setResultCode(RESULT_NOT_RUNNING)
                } else {
                    when (command) {
                        COMMAND_QUIESCE -> {
                            when (val released = controller.quiesce(family)) {
                                LiveRuntimeReleaseResult.Released -> pending.setResultCode(RESULT_OK)
                                is LiveRuntimeReleaseResult.RequiresProcessExit -> {
                                    pending.setResultExtras(
                                        Bundle().apply { putInt(EXTRA_EXIT_PID, released.pid) },
                                    )
                                    pending.setResultCode(RESULT_OK)
                                    Handler(Looper.getMainLooper()).postDelayed(
                                        { Process.killProcess(released.pid) },
                                        PROCESS_EXIT_DELAY_MS,
                                    )
                                }
                            }
                        }
                        COMMAND_ACTIVATE -> {
                            controller.activate(family)
                            pending.setResultCode(RESULT_OK)
                        }
                        else -> pending.setResultCode(RESULT_FAILED)
                    }
                }
            } catch (error: Throwable) {
                MindlayerLog.w(
                    TAG,
                    "Model runtime control failed: ${error.safeLabel()}",
                    throwable = null,
                )
                pending.setResultCode(RESULT_FAILED)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "ModelRuntimeControl"
        const val PROCESS_EXIT_DELAY_MS = 150L
        val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

private const val ACTION_RUNTIME_CONTROL =
    "com.adsamcik.mindlayer.service.modeldelivery.RUNTIME_CONTROL"
private const val EXTRA_COMMAND = "command"
private const val EXTRA_FAMILY = "family"
private const val EXTRA_EXIT_PID = "exit_pid"
private const val COMMAND_QUIESCE = "quiesce"
private const val COMMAND_ACTIVATE = "activate"
private const val RESULT_OK = 1
private const val RESULT_NOT_RUNNING = 2
private const val RESULT_FAILED = 3
