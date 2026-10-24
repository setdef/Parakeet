package com.setdef.parakeet

import com.intellij.platform.lsp.api.customization.LspCustomization
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText

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

    /**
     * verification/ignored-problems.txt lets 2025.3's Plugin Verifier report miss the
     * classes of 2026.1's LSP features, because only [CuttleboneCustomization261] names
     * them, and it's never created in 2025.3. This keeps both true.
     */
    fun testOnlyTheNewerCustomizationNamesClassesThatAreMissingIn2025_3() {
        val ignored = Files.readAllLines(Path.of("verification/ignored-problems.txt")).filter { it.isNotBlank() && !it.startsWith("//") }
        val features = ignored.flatMap { line ->
            Regex("""Lsp\(([\w|]+)\)\(([\w|]+)\)""").findAll(line).flatMap { match ->
                val (names, suffixes) = match.destructured
                names.split('|').flatMap { name -> suffixes.split('|').map { "Lsp$name$it" } }
            }.toList()
        }
        assertTrue(ignored.toString(), features.contains("LspRenameDisabled"))
        val sources = Files.walk(Path.of("src/main")).use { paths -> paths.filter { it.extension in setOf("kt", "java", "xml") }.toList() }
        val outside = sources.flatMap { path ->
            var text = path.readText()
            // The class's own body, and the imports it needs, are where these names belong. A
            // string isn't a reference to the class (create() looks one up by name).
            val body = Regex("""(?m)^abstract class CuttleboneCustomization261\b.*?^}""", RegexOption.DOT_MATCHES_ALL)
            text = body.replace(text, "").lines().filterNot { it.startsWith("import ") }.joinToString("\n").replace(Regex(""""[^"\n]*""""), "\"\"")
            features.filter { Regex("""\b$it\b""").containsMatchIn(text) }.map { "$path: $it" }
        }
        assertEquals("2026.1-only LSP classes named outside CuttleboneCustomization261", emptyList<String>(), outside)
        // Where they exist (the IDE the tests run in), it's the one created.
        assertTrue(CuttleboneCustomization.create() is CuttleboneCustomization261)
    }
}
