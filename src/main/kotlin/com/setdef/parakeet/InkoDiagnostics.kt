package com.setdef.parakeet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import java.nio.file.Path

data class InkoDiagnostic(
    val level: String,
    val file: String,
    val lineStart: Int,
    val lineEnd: Int,
    val columnStart: Int,
    val columnEnd: Int,
    val message: String,
    /** The compiler's diagnostic ID, e.g. `invalid-symbol` or `unused-symbol`. */
    val id: String = "",
) {
    val isWarning: Boolean get() = level == "warning"
}

/** Runs `inko check` against the (possibly unsaved) text of a file. */
object InkoCheck {
    /**
     * Checks [text] as the contents of [file] inside an [InkoMirror] of its
     * project. Every import resolves within the mirror, so modules that import
     * [file] see the unsaved text too, and diagnostics land on the mirrored copy.
     */
    fun run(project: Project, file: Path, text: String): List<InkoDiagnostic> {
        val cli = InkoCli.getInstance(project)
        if (!cli.isAvailable) return emptyList()
        InkoMirror.create(Inko.projectRoot(file), file, text).use { mirror ->
            val copy = mirror.toMirror(file)
            val result = cli.run(listOf("check", "--format", "json", copy.toString()), mirror.directory) ?: return emptyList()
            if (result.interrupted) return emptyList()
            return parse(result.stderr).filter { Path.of(it.file) == copy }
        }
    }

    /**
     * Parses `inko check --format json` output; anything else yields no
     * diagnostics. Inko writes strings with Rust's `{:?}`
     * (compiler/src/presenters.rs), so escapes like `\u{1}` and `\0` are
     * turned into JSON first. Each diagnostic is read on its own, so one that
     * can't be read doesn't hide the others.
     */
    fun parse(output: String): List<InkoDiagnostic> {
        val json = jsonFromRust(output.trim())
        if (!json.startsWith("[")) return emptyList()
        return objects(json).mapNotNull { text ->
            try {
                diagnostic(JsonParser.parseString(text).asJsonObject)
            } catch (_: RuntimeException) {
                null
            }
        }
    }

    private fun diagnostic(obj: JsonObject): InkoDiagnostic? {
        val lines = obj.getAsJsonArray("lines") ?: return null
        val columns = obj.getAsJsonArray("columns") ?: return null
        return InkoDiagnostic(
            level = obj.get("level")?.asString ?: "error",
            file = obj.get("file")?.asString ?: return null,
            lineStart = lines[0].asInt,
            lineEnd = lines[1].asInt,
            columnStart = columns[0].asInt,
            columnEnd = columns[1].asInt,
            message = obj.get("message")?.asString ?: "",
            id = obj.get("id")?.asString ?: "",
        )
    }

    /** [text] with the escapes Rust's `{:?}` writes inside strings (`\u{X}`, `\0`, `\'`) rewritten as JSON ones. */
    internal fun jsonFromRust(text: String): String = buildString {
        var i = 0
        var inString = false
        while (i < text.length) {
            val c = text[i]
            when {
                !inString -> { if (c == '"') inString = true; append(c); i++ }
                c == '"' -> { inString = false; append(c); i++ }
                c != '\\' || i + 1 >= text.length -> { append(c); i++ }
                text[i + 1] == 'u' && text.getOrNull(i + 2) == '{' -> {
                    val close = text.indexOf('}', i + 3)
                    val code = if (close > 0) text.substring(i + 3, close).toIntOrNull(16) else null
                    if (code == null || code > 0x10FFFF) {
                        append("\\\\"); i++ // Not an escape after all: keep the backslash as text.
                    } else {
                        String(Character.toChars(code)).forEach { append("\\u%04x".format(it.code)) }
                        i = close + 1
                    }
                }
                text[i + 1] == '0' -> { append("\\u0000"); i += 2 }
                text[i + 1] == '\'' -> { append('\''); i += 2 }
                else -> { append(c).append(text[i + 1]); i += 2 }
            }
        }
    }

    /** The text of each top-level `{…}` in a JSON array, found by brackets outside strings. */
    private fun objects(json: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var start = -1
        var inString = false
        var i = 0
        while (i < json.length) {
            val c = json[i]
            if (inString) {
                if (c == '\\') i++ else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> if (depth > 0 && --depth == 0) result += json.substring(start, i + 1)
            }
            i++
        }
        return result
    }
}
