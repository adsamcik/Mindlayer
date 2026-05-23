package com.adsamcik.mindlayer.lint

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
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.ULiteralExpression

class NativeSafeThrowableLogDetector : Detector(), SourceCodeScanner {
    override fun getApplicableMethodNames(): List<String> = listOf("w", "e")

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        if (!MindlayerLintPaths.isNativeLoggingPath(context.file)) return
        if (!context.evaluator.isMemberInClass(method, MINDLAYER_LOG_CLASS)) return

        val throwableArgument = findThrowableArgument(context, node, method) ?: return
        if (throwableArgument.isNullLiteral()) return

        context.report(
            ISSUE,
            throwableArgument,
            context.getLocation(throwableArgument),
            "Raw stack traces from native paths can embed prompt text — use safeLabel() and pass throwable=null.",
        )
    }

    private fun findThrowableArgument(
        context: JavaContext,
        node: UCallExpression,
        method: PsiMethod,
    ): UExpression? {
        val mapping = context.evaluator.computeArgumentMapping(node, method)
        mapping.entries.firstOrNull { (_, parameter) -> parameter.name == "throwable" }?.let { return it.key }

        return node.valueArguments.firstOrNull { argument ->
            argument.asSourceString().trimStart().startsWith("throwable")
        } ?: node.valueArguments.getOrNull(4)
    }

    private fun UExpression.isNullLiteral(): Boolean {
        val source = asSourceString().trim()
        return (this is ULiteralExpression && value == null) ||
            source == "null" ||
            NAMED_THROWABLE_NULL.matches(source)
    }

    companion object {
        private val NAMED_THROWABLE_NULL = Regex("""^throwable\s*=\s*null$""")
        private const val MINDLAYER_LOG_CLASS = "com.adsamcik.mindlayer.service.logging.MindlayerLog"

        val ISSUE: Issue = Issue.create(
            "MindlayerNativeRawThrowableLog",
            "Raw Throwable logged from native call path",
            "Engine, IPC, and ServiceBinder exception logs on LiteRT-LM, LiteRT, or PaddleOCR paths " +
                "must surface Throwable.safeLabel() in the message and pass throwable=null. Raw stack traces " +
                "from native paths can embed prompt text.",
            Category.SECURITY,
            9,
            Severity.ERROR,
            Implementation(NativeSafeThrowableLogDetector::class.java, Scope.JAVA_FILE_SCOPE),
        )
    }
}
