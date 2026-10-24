package com.setdef.parakeet

import com.intellij.platform.lsp.api.customization.LspCustomization
import java.lang.reflect.Modifier

/**
 * IntelliJ turns on every LSP feature a server advertises unless the
 * descriptor turns it off, so each of [LspCustomization]'s customizers must be
 * a decision Parakeet made. An IDE that adds one fails here, rather than
 * quietly showing a feature twice.
 */
class CuttleboneOwnershipTest : InkoTestCase() {
    private fun customizers() = LspCustomization::class.java.methods
        .filter { it.declaringClass == LspCustomization::class.java && it.parameterCount == 0 && !Modifier.isStatic(it.modifiers) }
        .filter { it.name.startsWith("get") && it.name.endsWith("Customizer") }

    fun testEveryCustomizerIsSetExplicitly() {
        val customization = CuttleboneDescriptor(project).lspCustomization
        val getters = customizers()
        assertTrue("found no customizers in ${getters.map { it.name }}", getters.size >= 20)
        // Parakeet's classes between the descriptor's customization and IntelliJ's.
        val classes = generateSequence<Class<*>>(customization.javaClass) { it.superclass }.takeWhile { it != LspCustomization::class.java }.toList()
        val unset = getters.filter { getter ->
            classes.none { runCatching { it.getDeclaredMethod(getter.name) }.isSuccess }
        }.map { it.name.removePrefix("get").replaceFirstChar(Char::lowercaseChar) }
        assertEquals("customizers CuttleboneDescriptor leaves to IntelliJ's default (on)", emptyList<String>(), unset)
    }
}
