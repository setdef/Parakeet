package com.setdef.parakeet

import com.intellij.openapi.util.TextRange
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** A `require URL VERSION CHECKSUM` line of `inko.pkg`. */
data class InkoDependency(val url: String, val version: String, val checksum: String)

/**
 * `inko.pkg`, read the way the compiler reads it (compiler/src/pkg/manifest.rs),
 * with each invalid line reported rather than only the first.
 */
class InkoManifest(val inkoVersion: String?, val dependencies: List<InkoDependency>, val problems: List<Problem>) {
    data class Problem(val range: TextRange, val message: String)

    companion object {
        fun read(root: Path): InkoManifest? = root.resolve(Inko.MANIFEST).takeIf(Files::isRegularFile)?.let { parse(Files.readString(it)) }

        fun parse(text: CharSequence): InkoManifest {
            var inkoVersion: String? = null
            var inkoLine = 0
            val dependencies = mutableListOf<InkoDependency>()
            val problems = mutableListOf<Problem>()
            var lineStart = 0
            for ((index, line) in text.split('\n').withIndex()) {
                val number = index + 1
                val trimmed = line.trim()
                val start = lineStart + line.indexOf(trimmed.firstOrNull() ?: ' ').coerceAtLeast(0)
                val range = TextRange(start, start + trimmed.length)
                lineStart += line.length + 1
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

                fun problem(message: String) = problems.add(Problem(range, message))
                val chunks = trimmed.split(' ')
                when {
                    chunks[0] != "require" -> problem("Expected the line to start with 'require', not '${chunks[0]}'")
                    chunks.size == 3 && chunks[1] == "inko" && inkoLine != 0 ->
                        problem("The Inko version is already required on line $inkoLine")
                    chunks.size == 3 && chunks[1] == "inko" -> {
                        inkoLine = number
                        if (isVersion(chunks[2])) inkoVersion = chunks[2] else problem("The version '${chunks[2]}' is invalid (expected e.g. 1.2.3)")
                    }
                    chunks.size == 4 -> {
                        val (_, url, version, checksum) = chunks
                        when {
                            url.isEmpty() -> problem("The URL is invalid")
                            !isVersion(version) -> problem("The version '$version' is invalid (expected e.g. 1.2.3)")
                            checksum.length != 40 -> problem("The checksum is invalid (expected 40 characters)")
                            else -> dependencies += InkoDependency(url, version, checksum)
                        }
                    }
                    else -> problem("Expected 'require inko VERSION' or 'require URL VERSION CHECKSUM'")
                }
            }
            return InkoManifest(inkoVersion, dependencies, problems)
        }

        /** Three numbers (each 0–65535) without leading zeroes or signs, like the compiler's Version::parse. */
        private fun isVersion(text: String): Boolean {
            val parts = text.split('.')
            return parts.size == 3 && parts.all { part ->
                part.isNotEmpty() && part.all(Char::isDigit) && !(part.length > 1 && part.startsWith('0')) && part.toIntOrNull()?.let { it <= 65535 } == true
            }
        }
    }
}

/** Whether a project's dependencies are synced, the way `inko` decides: dep/hash is the BLAKE3 of inko.pkg. */
object InkoDependencySync {
    /**
     * Whether `inko pkg sync` would change anything, given [manifestText] (the
     * open editor's text, or the file's). Projects without dependencies (and no
     * `dep` directory) never need it.
     */
    fun needed(root: Path, manifestText: CharSequence? = null): Boolean {
        val text = manifestText?.toString() ?: root.resolve(Inko.MANIFEST).takeIf(Files::isRegularFile)?.let(Files::readString) ?: return false
        val dep = root.resolve("dep")
        if (InkoManifest.parse(text).dependencies.isEmpty() && !Files.isDirectory(dep)) return false
        val saved = dep.resolve("hash").takeIf(Files::isRegularFile)?.let(Files::readString)
        return saved != InkoBlake3.hex(text.toByteArray(StandardCharsets.UTF_8))
    }
}
