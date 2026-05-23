package com.adsamcik.mindlayer.lint

import com.android.tools.lint.client.api.JavaEvaluator
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UCallExpression

class NoDirectAndroidLogDetector : Detector(), SourceCodeScanner {
    override fun getApplicableMethodNames(): List<String> = LOG_METHODS

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        if (!MindlayerLintPaths.isAppMainKotlin(context.file) || MindlayerLintPaths.isMindlayerLog(context.file)) {
            return
        }
        if (!context.evaluator.isAndroidLogMethod(method)) return

        val replacement = node.methodName?.takeIf { it in setOf("d", "i", "w", "e") }
            ?.let { "MindlayerLog.$it" }
            ?: "MindlayerLog.{d,i,w,e}"
        context.report(
            ISSUE,
            node,
            context.getCallLocation(node, includeReceiver = true, includeArguments = false),
            "Do not call android.util.Log directly in :app production code; use $replacement instead.",
        )
    }

    private fun JavaEvaluator.isAndroidLogMethod(method: PsiMethod): Boolean =
        isMemberInClass(method, ANDROID_LOG_CLASS)

    companion object {
        private const val ANDROID_LOG_CLASS = "android.util.Log"
        private val LOG_METHODS = listOf("d", "i", "w", "e", "v", "wtf", "println", "isLoggable")

        val ISSUE: Issue = Issue.create(
            "MindlayerDirectAndroidLog",
            "Direct android.util.Log use in app production code",
            "Mindlayer service code must route logs through MindlayerLog so correlation IDs are " +
                "sanitized and prompt/model output is not accidentally emitted. Use MindlayerLog.{d,i,w,e} " +
                "instead of android.util.Log. Test sources and MindlayerLog.kt itself are exempt.",
            Category.SECURITY,
            8,
            Severity.ERROR,
            Implementation(NoDirectAndroidLogDetector::class.java, Scope.JAVA_FILE_SCOPE),
        )
    }
}
