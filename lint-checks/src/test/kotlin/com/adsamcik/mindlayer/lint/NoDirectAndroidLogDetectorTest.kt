package com.adsamcik.mindlayer.lint

import com.android.tools.lint.checks.infrastructure.ProjectDescription
import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import org.junit.Test

class NoDirectAndroidLogDetectorTest {
    @Test
    fun `flags direct android util Log calls in app production code`() {
        lint()
            .projects(
                ProjectDescription().name("app").files(
                    LintTestStubs.androidLog,
                    kotlin(
                        "src/main/kotlin/com/adsamcik/mindlayer/service/BadLogger.kt",
                        """
                            package com.adsamcik.mindlayer.service

                            import android.util.Log

                            fun badLog() {
                                Log.d("Bad", "message")
                            }
                        """.trimIndent(),
                    ),
                ),
            )
            .issues(NoDirectAndroidLogDetector.ISSUE)
            .run()
            .expectContains("MindlayerDirectAndroidLog")
            .expectContains("use MindlayerLog.d instead")
    }

    @Test
    fun `allows MindlayerLog wrapper and test sources`() {
        lint()
            .projects(
                ProjectDescription().name("app").files(
                    LintTestStubs.androidLog,
                    kotlin(
                        "src/main/kotlin/com/adsamcik/mindlayer/service/logging/MindlayerLog.kt",
                        """
                            package com.adsamcik.mindlayer.service.logging

                            import android.util.Log

                            object MindlayerLog {
                                fun d(component: String, message: String) {
                                    Log.d(component, message)
                                }
                            }
                        """.trimIndent(),
                    ),
                    kotlin(
                        "src/test/kotlin/com/adsamcik/mindlayer/service/LogTest.kt",
                        """
                            package com.adsamcik.mindlayer.service

                            import android.util.Log

                            fun testLog() {
                                Log.e("Test", "message")
                            }
                        """.trimIndent(),
                    ),
                ),
            )
            .issues(NoDirectAndroidLogDetector.ISSUE)
            .run()
            .expectClean()
    }
}
