package com.setdef.parakeet

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedInputStream
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.system.exitProcess

/**
 * A stand-in for `cuttlebone`, run as its own process by [FakeCuttleboneScript]:
 * a minimal LSP server over stdio that answers as its script says, and logs
 * every message it receives (one JSON object per line) for tests to inspect.
 */
object FakeCuttlebone {
    /** What the fake does, written as JSON by the test. */
    data class Script(
        val log: String,
        /** Answer `initialize` with this error instead (a version mismatch, say). */
        val initializeError: Map<String, Any?>? = null,
        /** Wait this long before each query's answer. */
        val delayMs: Long = 0,
        /** Never answer queries (until cancelled). */
        val hang: Boolean = false,
        /** Exit on the first query, in this many launches (counted in the log). */
        val crashLaunches: Int = 0,
        /** Answers by method and position; anything else is null with status `unavailable`. */
        val answers: List<Answer> = emptyList(),
        val statusNotifications: Boolean = true,
    )

    /** [repaired] is sent only when set: builds before milestone 5 never send it. */
    data class Answer(val method: String, val line: Int, val character: Int, val result: Any?, val status: String = "fresh", val reason: String? = null, val repaired: Boolean? = null)

    private val gson: Gson = GsonBuilder().serializeNulls().create()

    @JvmStatic
    fun main(args: Array<String>) {
        val script = gson.fromJson(Files.readString(Path.of(args[0])), Script::class.java)
        val log = Path.of(script.log)
        fun record(message: JsonObject) = synchronized(this) {
            Files.writeString(log, gson.toJson(message) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
        val launches = if (Files.exists(log)) Files.readAllLines(log).count { it.contains("\"fake\":\"launch\"") } else 0
        record(JsonObject().apply { addProperty("fake", "launch") })
        val crash = launches < script.crashLaunches

        val input = BufferedInputStream(System.`in`)
        val output = System.out
        val cancelled = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        var shutdown = false
        while (true) {
            val message = read(input) ?: exitProcess(if (shutdown) 0 else 1)
            record(message)
            val method = message.get("method")?.asString
            val id = message.get("id")
            when (method) {
                "initialize" -> if (script.initializeError != null) {
                    write(output, JsonObject().apply { add("id", id); add("error", gson.toJsonTree(script.initializeError)) })
                } else {
                    write(output, JsonObject().apply { add("id", id); add("result", gson.toJsonTree(initializeResult(script))) })
                }
                "shutdown" -> { shutdown = true; write(output, JsonObject().apply { add("id", id); add("result", JsonNull.INSTANCE) }) }
                "exit" -> exitProcess(if (shutdown) 0 else 1)
                "\$/cancelRequest" -> cancelled += message.getAsJsonObject("params").get("id").toString()
                null -> {}
                else -> if (id != null) {
                    if (crash) exitProcess(3)
                    // Answered on another thread, so cancellations can arrive meanwhile.
                    Thread {
                        val deadline = System.currentTimeMillis() + (if (script.hang) 60_000 else script.delayMs)
                        while (System.currentTimeMillis() < deadline && id.toString() !in cancelled) Thread.sleep(5)
                        if (id.toString() in cancelled) {
                            write(output, JsonObject().apply { add("id", id); add("error", JsonObject().apply { addProperty("code", -32800); addProperty("message", "cancelled") }) })
                            return@Thread
                        }
                        answer(script, method, message.getAsJsonObject("params"), id, output)
                    }.start()
                }
            }
        }
    }

    private fun initializeResult(script: Script) = mapOf(
        "capabilities" to mapOf(
            "positionEncoding" to "utf-16",
            "textDocumentSync" to mapOf("openClose" to true, "change" to 2),
            "hoverProvider" to true,
            "completionProvider" to mapOf("triggerCharacters" to listOf("."), "resolveProvider" to false),
            "definitionProvider" to true,
            "typeDefinitionProvider" to true,
            "referencesProvider" to true,
            "documentHighlightProvider" to true,
            "signatureHelpProvider" to mapOf("triggerCharacters" to listOf("(", ",")),
            "inlayHintProvider" to true,
            "experimental" to mapOf("cuttlebone" to mapOf("inko" to "0.21.1", "statusNotifications" to script.statusNotifications)),
        ),
        "serverInfo" to mapOf("name" to "cuttlebone", "version" to "0.0.0-fake"),
    )

    private fun answer(script: Script, method: String, params: JsonObject, id: JsonElement, output: OutputStream) {
        // A range request (inlay hints) is answered by its start.
        val position = params.getAsJsonObject("position") ?: params.getAsJsonObject("range")?.getAsJsonObject("start")
        val line = position?.get("line")?.asInt
        val character = position?.get("character")?.asInt
        val found = script.answers.firstOrNull { it.method == method && it.line == line && it.character == character }
        val uri = params.getAsJsonObject("textDocument")?.get("uri")?.asString
        if (script.statusNotifications) {
            write(output, JsonObject().apply {
                addProperty("method", "cuttlebone/status")
                add("params", gson.toJsonTree(mapOf(
                    "id" to id, "method" to method, "textDocument" to mapOf("uri" to uri),
                    "status" to (found?.status ?: "unavailable"),
                    "reason" to (found?.reason ?: if (found == null) "noHir" else null)?.let { mapOf("code" to it, "message" to it) },
                    "elapsedMs" to 1.0,
                ) + listOfNotNull(found?.repaired?.let { "repaired" to it })))
            })
        }
        write(output, JsonObject().apply { add("id", id); add("result", gson.toJsonTree(found?.result)) })
    }

    private fun read(input: InputStream): JsonObject? {
        var length = -1
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
        }
        val bytes = input.readNBytes(length)
        if (bytes.size < length) return null
        return JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return null
            if (c == '\n'.code) return buffer.toString(Charsets.US_ASCII).trimEnd('\r')
            buffer.write(c)
        }
    }

    private fun write(output: OutputStream, message: JsonObject) = synchronized(output) {
        message.addProperty("jsonrpc", "2.0")
        val bytes = gson.toJson(message).toByteArray(Charsets.UTF_8)
        output.write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }
}

/** Writes an executable `cuttlebone` that runs [FakeCuttlebone] with [script] (and answers `--version` itself). */
object FakeCuttleboneScript {
    fun write(directory: Path, script: FakeCuttlebone.Script, version: String = "cuttlebone 0.0.0-fake (Inko 0.21.1)"): Path {
        Files.createDirectories(directory)
        val json = directory.resolve("script.json")
        Files.writeString(json, Gson().toJson(script))
        val java = Path.of(System.getProperty("java.home"), "bin", "java")
        val classpath = listOf(FakeCuttlebone::class.java, Gson::class.java, Unit::class.java)
            .map { com.intellij.openapi.application.PathManager.getJarPathForClass(it)!! }
            .distinct().joinToString(File.pathSeparator)
        val executable = directory.resolve("cuttlebone")
        Files.writeString(executable, """
            |#!/bin/sh
            |if [ "${'$'}1" = "--version" ]; then echo '$version'; exit 0; fi
            |exec '$java' -Xshare:auto -XX:TieredStopAtLevel=1 -cp '$classpath' ${FakeCuttlebone::class.java.name} '$json'
            |""".trimMargin())
        executable.toFile().setExecutable(true)
        return executable
    }
}
