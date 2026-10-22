package com.setdef.parakeet

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import java.nio.file.Path

/** What a run configuration should do for a spot in an Inko file. */
data class InkoRunTarget(val command: InkoCommand, val target: String?, val name: String, val workingDirectory: Path)

object InkoRunTargets {
    private val MAIN_TYPE = Regex("""\btype\s+async\s+Main\b""")

    fun fromContext(context: ConfigurationContext): InkoRunTarget? {
        val element = context.psiLocation ?: return null
        val file = element as? PsiFile ?: element.containingFile ?: return null
        val leaf = if (element is PsiFile) null else PsiTreeUtil.getDeepestFirst(element)
        return forElement(file, leaf)
    }

    /** The target for a gutter marker on [leaf], if it marks a runnable spot. */
    fun forMarker(leaf: PsiElement): InkoRunTarget? {
        val file = leaf.containingFile ?: return null
        val previous = leaf.previousToken()
        val beforeThat = previous?.previousToken()
        val isMarker = when (leaf.text) {
            "main" -> previous.isKeyword("async") && beforeThat.isKeyword("fn")
            "tests" -> previous.isKeyword("pub") && beforeThat.isKeyword("fn")
            "test" -> testName(leaf) != null
            else -> false
        }
        return if (isMarker) forElement(file, leaf) else null
    }

    private fun forElement(file: PsiFile, leaf: PsiElement?): InkoRunTarget? {
        val path = Inko.pathOf(file.virtualFile) ?: return null
        val root = Inko.projectRoot(path)
        val fileName = path.fileName.toString()

        if (path.startsWith(root.resolve("test"))) {
            leaf?.let(::testName)?.let { return InkoRunTarget(InkoCommand.TEST, it, "Test '$it'", root) }
            return InkoRunTarget(InkoCommand.TEST, path.toString(), "Tests in $fileName", root)
        }
        if (MAIN_TYPE.containsMatchIn(file.text)) {
            return InkoRunTarget(InkoCommand.RUN, path.toString(), "Run $fileName", root)
        }
        return null
    }

    /** For the `test` in `t.test('name', ...)`, the test's name. */
    private fun testName(leaf: PsiElement): String? {
        if (leaf.text != "test" || !leaf.isToken(InkoTokens.IDENTIFIER)) return null
        if (!leaf.previousToken().isToken(InkoTokens.DOT)) return null
        val paren = leaf.nextToken()?.takeIf { it.isToken(InkoTokens.LPAREN) } ?: return null
        val string = paren.nextToken()?.takeIf { it.isToken(InkoTokens.STRING) } ?: return null
        return string.text.drop(1).dropLast(1).takeIf { it.isNotEmpty() && '$' !in it }
    }
}

class InkoRunConfigurationProducer : LazyRunConfigurationProducer<InkoRunConfiguration>() {
    override fun getConfigurationFactory(): ConfigurationFactory = InkoRunConfigurationType.getInstance().configurationFactories[0]

    override fun setupConfigurationFromContext(
        configuration: InkoRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val target = InkoRunTargets.fromContext(context) ?: return false
        configuration.options.command = target.command.cli
        configuration.options.target = target.target
        configuration.options.workingDirectory = target.workingDirectory.toString()
        configuration.name = target.name
        return true
    }

    override fun isConfigurationFromContext(configuration: InkoRunConfiguration, context: ConfigurationContext): Boolean {
        val target = InkoRunTargets.fromContext(context) ?: return false
        return configuration.command == target.command && configuration.options.target == target.target
    }
}

class InkoRunLineMarkerContributor : RunLineMarkerContributor() {
    override fun getInfo(element: PsiElement): Info? {
        if (!element.isToken(InkoTokens.IDENTIFIER)) return null
        InkoRunTargets.forMarker(element) ?: return null
        return withExecutorActions(AllIcons.RunConfigurations.TestState.Run)
    }
}
