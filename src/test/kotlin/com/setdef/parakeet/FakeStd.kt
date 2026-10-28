package com.setdef.parakeet

import java.nio.file.Files
import java.nio.file.Path

/** A standard library on disk, as `inko print std` names it, for the definition index to read. */
object FakeStd {
    /** Writes [modules] (`std.string` to its source) into [directory], and returns it. */
    fun write(directory: Path, modules: Map<String, String>): Path {
        for ((module, source) in modules) {
            val file = directory.resolve(module.replace('.', '/') + ".${Inko.EXTENSION}")
            Files.createDirectories(file.parent)
            Files.writeString(file, source)
        }
        return directory
    }

    /** A fake `inko` that names [std] for `inko print std`, and answers anything else with [respond]. */
    fun cli(std: Path, respond: (FakeInkoCli.Call) -> InkoCliResult = { FakeInkoCli.ok(stderr = "[]") }) = FakeInkoCli { call ->
        if (call.args == listOf("print", "std")) FakeInkoCli.ok(stdout = "$std\n") else respond(call)
    }
}
