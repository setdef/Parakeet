package com.setdef.parakeet

import com.google.gson.Gson
import java.nio.file.Files
import java.nio.file.Path

/** Builds `inko doc` output for a [FakeInkoCli]. */
object FakeInkoDoc {
    private val gson = Gson()

    fun location(line: Int, column: Int = 1) =
        mapOf("lines" to mapOf("start" to line, "end" to line), "columns" to mapOf("start" to column, "end" to column))

    fun member(name: String, file: Path, line: Int, column: Int = 3, signature: String = "fn $name", docs: String = "") =
        mapOf("name" to name, "file" to file.toString(), "location" to location(line, column), "type" to signature, "documentation" to docs)

    fun type(
        name: String,
        line: Int,
        signature: String = "type $name",
        docs: String = "",
        fields: List<Map<String, Any>> = emptyList(),
        staticMethods: List<Map<String, Any>> = emptyList(),
        instanceMethods: List<Map<String, Any>> = emptyList(),
    ) = mapOf(
        "name" to name, "location" to location(line), "type" to signature, "documentation" to docs,
        "constructors" to emptyList<Any>(), "fields" to fields,
        "static_methods" to staticMethods, "instance_methods" to instanceMethods,
    )

    fun module(
        name: String,
        file: Path,
        methods: List<Map<String, Any>> = emptyList(),
        types: List<Map<String, Any>> = emptyList(),
        docs: String = "",
    ) = gson.toJson(
        mapOf("name" to name, "file" to file.toString(), "documentation" to docs, "constants" to emptyList<Any>(),
            "methods" to methods, "types" to types, "traits" to emptyList<Any>()),
    )

    /** A fake answering `inko doc` by writing the JSON [modules] returns (given the mirror's directory). */
    fun cli(modules: (mirror: Path) -> List<String>) = FakeInkoCli { call ->
        if (call.args.firstOrNull() == "doc") {
            val docs = Files.createDirectories(call.workingDirectory!!.resolve("build/docs"))
            modules(call.workingDirectory).forEachIndexed { index, json -> Files.writeString(docs.resolve("module_$index.json"), json) }
            Files.writeString(docs.resolve("\$meta.json"), """{"readme": ""}""")
        }
        FakeInkoCli.ok(stderr = "[]")
    }
}
