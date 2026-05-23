package com.adsamcik.mindlayer.lint

import com.android.tools.lint.checks.infrastructure.ProjectDescription
import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import org.junit.Test

class NativeSafeThrowableLogDetectorTest {
    @Test
    fun `flags non-null throwable passed to MindlayerLog on native paths`() {
        lint()
            .projects(
                ProjectDescription().name("app").files(
                    LintTestStubs.mindlayerLog,
                    kotlin(
                        "src/main/kotlin/com/adsamcik/mindlayer/service/engine/BadNativeLog.kt",
                        """
                            package com.adsamcik.mindlayer.service.engine

                            import com.adsamcik.mindlayer.service.logging.MindlayerLog

                            fun bad(t: Throwable) {
                                MindlayerLog.e("Engine", "Native failure", throwable = t)
                            }
                        """.trimIndent(),
                    ),
                ),
            )
            .issues(NativeSafeThrowableLogDetector.ISSUE)
            .run()
            .expectContains("MindlayerNativeRawThrowableLog")
            .expectContains("use safeLabel() and pass throwable=null")
    }

    @Test
    fun `allows safeLabel message with throwable null on native paths`() {
        lint()
            .projects(
                ProjectDescription().name("app").files(
                    LintTestStubs.mindlayerLog,
                    kotlin(
                        "src/main/kotlin/com/adsamcik/mindlayer/service/ipc/SafeNativeLog.kt",
                        """
                            package com.adsamcik.mindlayer.service.ipc

                            import com.adsamcik.mindlayer.service.logging.MindlayerLog
                            import com.adsamcik.mindlayer.service.logging.safeLabel

                            fun safe(t: Throwable) {
                                MindlayerLog.w("IPC", "Native failure: ${'$'}{t.safeLabel()}", throwable = null)
                            }
                        """.trimIndent(),
                    ),
                ),
            )
            .issues(NativeSafeThrowableLogDetector.ISSUE)
            .run()
            .expectClean()
    }
}
