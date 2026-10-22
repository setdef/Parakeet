package com.setdef.parakeet

import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.nio.file.Path

data class InkoDiagnostic(
    val level: String,
    val file: String,
    val lineStart: Int,
    val lineEnd: Int,
    val columnStart: Int,
    val columnEnd: Int,
    val message: String,
)

/** Runs `inko check` against the (possibly unsaved) text of a file. */
object InkoCheck {
    private const val TIMEOUT_MS = 30_000

    /**
     * Checks [text] as the contents of [file] inside an [InkoMirror] of its
     * project. Every import resolves within the mirror, so modules that import
     * [file] see the unsaved text too, and diagnostics land on the mirrored copy.
     */
    fun run(project: Project?, file: Path, text: String): List<InkoDiagnostic> {
        val inko = InkoExecutable.find(project) ?: return emptyList()
        InkoMirror.create(Inko.projectRoot(file), file, text).use { mirror ->
            val copy = mirror.toMirror(file)
            val command = GeneralCommandLine(inko.toString(), "check", "--format", "json", copy.toString())
                .withWorkDirectory(mirror.directory.toFile())
                .withCharset(StandardCharsets.UTF_8)
            val indicator = ProgressManager.getInstance().progressIndicator ?: EmptyProgressIndicator()
            val output = CapturingProcessHandler(command).runProcessWithProgressIndicator(indicator, TIMEOUT_MS)
            if (output.isCancelled || output.isTimeout) return emptyList()

            return parse(output.stderr).filter { Path.of(it.file) == copy }
        }
    }

    fun parse(json: String): List<InkoDiagnostic> {
        val array = try {
            JsonParser.parseString(json.trim())
        } catch (_: JsonParseException) {
            return emptyList()
        }
        if (!array.isJsonArray) return emptyList()
        return array.asJsonArray.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val lines = obj.getAsJsonArray("lines") ?: return@mapNotNull null
            val columns = obj.getAsJsonArray("columns") ?: return@mapNotNull null
            InkoDiagnostic(
                level = obj.get("level")?.asString ?: "error",
                file = obj.get("file")?.asString ?: return@mapNotNull null,
                lineStart = lines[0].asInt,
                lineEnd = lines[1].asInt,
                columnStart = columns[0].asInt,
                columnEnd = columns[1].asInt,
                message = obj.get("message")?.asString ?: "",
            )
        }
    }
}
