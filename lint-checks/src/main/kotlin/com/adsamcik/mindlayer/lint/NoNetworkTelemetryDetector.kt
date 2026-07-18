package com.adsamcik.mindlayer.lint

import com.android.SdkConstants.ANDROID_URI
import com.android.SdkConstants.TOOLS_URI
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.GradleContext
import com.android.tools.lint.detector.api.GradleScanner
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.Location
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.XmlContext
import com.android.tools.lint.detector.api.XmlScanner
import org.w3c.dom.Element
import java.util.EnumSet

class NoNetworkTelemetryDetector : Detector(), GradleScanner, XmlScanner {
    override val customVisitor: Boolean = true

    override fun visitBuildScript(context: GradleContext) {
        if (!MindlayerLintPaths.isAppOrSdkBuildFile(context.file)) return
        val contents = context.getContents() ?: return
        val text = contents.toString()
        var offset = 0
        var inDependencyCall = false
        var parenDepth = 0
        text.lineSequence().forEach { line ->
            val uncommented = line.substringBefore("//")
            val startsDependencyCall = DEPENDENCY_CALL.matches(uncommented)
            if (startsDependencyCall && !inDependencyCall) {
                inDependencyCall = true
                parenDepth = 0
            }

            if (inDependencyCall) {
                val lower = uncommented.lowercase()
                BLOCKED_DEPENDENCY_TOKENS.firstOrNull { token -> lower.contains(token) }?.let { token ->
                    val tokenIndex = lower.indexOf(token)
                    val start = offset + tokenIndex.coerceAtLeast(0)
                    val end = (start + token.length).coerceAtMost(offset + line.length)
                    context.report(
                        ISSUE,
                        Location.create(context.file, contents, start, end),
                        "Do not add network, telemetry, or cloud-fallback dependency '$token' to :app or :sdk.",
                    )
                }

                parenDepth += uncommented.count { it == '(' } - uncommented.count { it == ')' }
                if (parenDepth <= 0) {
                    inDependencyCall = false
                }
            }
            offset += line.length + 1
        }
    }

    override fun getApplicableElements(): Collection<String> = listOf("uses-permission")

    override fun visitElement(context: XmlContext, element: Element) {
        if (!MindlayerLintPaths.isAppOrSdkMainManifest(context.file)) return
        val permissionName = element.getAttributeNS(ANDROID_URI, "name")
        if (permissionName !in BLOCKED_PERMISSIONS) return
        if (
            permissionName == "android.permission.ACCESS_NETWORK_STATE" &&
            element.getAttributeNS(TOOLS_URI, "node") == "remove" &&
            element.getAttributeNS(TOOLS_URI, "selector").isBlank()
        ) {
            return
        }

        val nameAttribute = element.getAttributeNodeNS(ANDROID_URI, "name")
        context.report(
            ISSUE,
            nameAttribute ?: element,
            nameAttribute?.let(context::getValueLocation) ?: context.getLocation(element),
            "Do not declare $permissionName in :app or :sdk; Mindlayer must remain fully offline.",
        )
    }

    companion object {
        private val DEPENDENCY_CALL = Regex("""^\s*(implementation|api)\s*(\(|\s).*$""")

        private val BLOCKED_DEPENDENCY_TOKENS = listOf(
            ":internet:",
            "com.google.firebase:",
            "com.google.android.gms:play-services-analytics",
            "com.google.android.gms:play-services-measurement",
            "com.google.mlkit:",
            "io.sentry:",
            "com.bugsnag:",
            "com.datadoghq:",
            "com.amplitude:",
            "com.mixpanel:",
            "com.segment.analytics",
            "com.amazonaws:",
            "okhttp3:",
            "retrofit2:",
        )

        private val BLOCKED_PERMISSIONS = setOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
        )

        val ISSUE: Issue = Issue.create(
            "MindlayerNoNetworkTelemetry",
            "Network, telemetry, and cloud-fallback APIs are forbidden",
            ":app and :sdk must remain fully offline and free of telemetry. Do not add INTERNET or " +
                "ACCESS_NETWORK_STATE permissions, Firebase, Play Services analytics/measurement, ML Kit, " +
                "Sentry, Bugsnag, Datadog, Amplitude, Mixpanel, Segment, AWS, OkHttp, Retrofit, or :internet: artifacts.",
            Category.SECURITY,
            10,
            Severity.FATAL,
            Implementation(
                NoNetworkTelemetryDetector::class.java,
                EnumSet.of(Scope.GRADLE_FILE, Scope.MANIFEST),
                Scope.GRADLE_SCOPE,
                Scope.MANIFEST_SCOPE,
            ),
        )
    }
}
