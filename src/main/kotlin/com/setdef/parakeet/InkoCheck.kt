package com.setdef.parakeet

import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.io.NioFiles
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

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
    private const val MANIFEST = "inko.pkg"
    private val MIRRORED_DIRECTORIES = listOf("src", "test")

    /**
     * Mirrors the project into a temp directory (symlinking every source file,
     * the manifest, and `dep`) with [text] in place of [file], then checks it
     * there. Every import resolves within the mirror, so modules that import
     * [file] see the unsaved text too, and diagnostics land on the temp copy.
     */
    fun run(file: Path, text: String): List<InkoDiagnostic> {
        val inko = InkoExecutable.find() ?: return emptyList()
        val root = Inko.projectRoot(file)
        val tempDir = Files.createTempDirectory("inko-check")
        try {
            mirror(root, tempDir)
            val copy = tempDir.resolve(root.relativize(file))
            Files.createDirectories(copy.parent)
            Files.deleteIfExists(copy)
            Files.writeString(copy, text)
            val realCopy = copy.toRealPath()

            val command = GeneralCommandLine(inko.toString(), "check", "--format", "json", realCopy.toString())
                .withWorkDirectory(tempDir.toFile())
                .withCharset(StandardCharsets.UTF_8)
            val indicator = ProgressManager.getInstance().progressIndicator ?: EmptyProgressIndicator()
            val output = CapturingProcessHandler(command).runProcessWithProgressIndicator(indicator, TIMEOUT_MS)
            if (output.isCancelled || output.isTimeout) return emptyList()

            return parse(output.stderr).filter { Path.of(it.file) == realCopy }
        } finally {
            NioFiles.deleteRecursively(tempDir)
        }
    }

    private fun mirror(root: Path, target: Path) {
        for (name in MIRRORED_DIRECTORIES) {
            val dir = root.resolve(name)
            if (!Files.isDirectory(dir)) continue
            Files.walk(dir).use { paths ->
                paths.filter { it.isRegularFile() && it.extension == Inko.EXTENSION }.forEach { source ->
                    val link = target.resolve(root.relativize(source))
                    Files.createDirectories(link.parent)
                    linkOrCopy(source, link)
                }
            }
        }
        // inko check requires a src directory for test/ files to count as part of a project.
        Files.createDirectories(target.resolve("src"))
        root.resolve(MANIFEST).takeIf(Files::isRegularFile)?.let { linkOrCopy(it, target.resolve(MANIFEST)) }
        root.resolve("dep").takeIf(Files::isDirectory)?.let { linkOrCopy(it, target.resolve("dep")) }
    }

    // Symlinks can require extra privileges on Windows, so fall back to copying.
    private fun linkOrCopy(source: Path, link: Path) {
        try {
            Files.createSymbolicLink(link, source)
        } catch (_: IOException) {
            copyRecursively(source, link)
        } catch (_: UnsupportedOperationException) {
            copyRecursively(source, link)
        }
    }

    private fun copyRecursively(source: Path, target: Path) {
        Files.walk(source).use { paths ->
            paths.forEach { Files.copy(it, target.resolve(source.relativize(it).toString())) }
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
