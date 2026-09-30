package com.adsamcik.mindlayer.service

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import com.adsamcik.mindlayer.service.logging.MindlayerDiagnostics

/** Installs local diagnostics before dashboard or inference-service startup. */
class MindlayerApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            base.getSystemService(ActivityManager::class.java)?.runningAppProcesses
                ?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName
        }
        MindlayerDiagnostics.processRole(processName, base.packageName)?.let { role ->
            MindlayerDiagnostics.install(this, role)
        }
    }
}
