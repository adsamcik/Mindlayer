package com.adsamcik.mindlayer.lint

import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin

internal object LintTestStubs {
    val androidLog: TestFile = kotlin(
        "src/main/kotlin/android/util/Log.kt",
        """
            package android.util

            object Log {
                @JvmStatic fun d(tag: String, message: String): Int = 0
                @JvmStatic fun i(tag: String, message: String): Int = 0
                @JvmStatic fun w(tag: String, message: String): Int = 0
                @JvmStatic fun w(tag: String, message: String, throwable: Throwable): Int = 0
                @JvmStatic fun e(tag: String, message: String): Int = 0
                @JvmStatic fun e(tag: String, message: String, throwable: Throwable): Int = 0
            }
        """.trimIndent(),
    )

    val mindlayerLog: TestFile = kotlin(
        "src/main/kotlin/com/adsamcik/mindlayer/service/logging/MindlayerLog.kt",
        """
            package com.adsamcik.mindlayer.service.logging

            object MindlayerLog {
                fun w(
                    component: String,
                    message: String,
                    requestId: String? = null,
                    sessionId: String? = null,
                    throwable: Throwable? = null,
                ) = Unit

                fun e(
                    component: String,
                    message: String,
                    requestId: String? = null,
                    sessionId: String? = null,
                    throwable: Throwable? = null,
                ) = Unit
            }

            fun Throwable.safeLabel(): String = this::class.simpleName ?: "Throwable"
        """.trimIndent(),
    )
}
