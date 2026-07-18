import org.gradle.api.JavaVersion

/**
 * Single source of truth for the compile/runtime targets shared by every
 * Mindlayer module, so the numbers live in exactly one place instead of being
 * copy-pasted into each `build.gradle.kts`.
 */
object Mindlayer {
    const val COMPILE_SDK = 37
    const val MIN_SDK = 26
    const val TARGET_SDK = 37

    const val GROUP_ID = "com.adsamcik.mindlayer"

    val JAVA_VERSION: JavaVersion = JavaVersion.VERSION_17

    /**
     * Duplicate license metadata pulled in transitively by test/runtime deps
     * (mockk, junit, coroutines). Excluded from packaged artifacts to avoid
     * merge collisions.
     */
    val RESOURCE_EXCLUDES: Set<String> = setOf(
        "META-INF/LICENSE.md",
        "META-INF/LICENSE-notice.md",
        "META-INF/AL2.0",
        "META-INF/LGPL2.1",
    )
}
