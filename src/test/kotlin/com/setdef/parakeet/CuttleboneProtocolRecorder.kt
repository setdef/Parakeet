package com.setdef.parakeet

import java.nio.file.Files
import java.nio.file.Path

/**
 * A script standing in for [executable] that keeps every byte IntelliJ sends it (`sent.jsonrpc`) and every byte
 * it answers (`received.jsonrpc`) in [directory]: a reproduction at the protocol's level, for Cuttlebone's own tests.
 */
object CuttleboneProtocolRecorder {
    fun script(executable: String, directory: Path): Path {
        Files.createDirectories(directory)
        val script = directory.resolve("cuttlebone")
        Files.writeString(script, """
            |#!/bin/sh
            |case "${'$'}{1-}" in --version) exec '$executable' "${'$'}@" ;; esac
            |tee -a '${directory.resolve("sent.jsonrpc")}' | '$executable' "${'$'}@" | tee -a '${directory.resolve("received.jsonrpc")}'
            |""".trimMargin())
        script.toFile().setExecutable(true)
        return script
    }
}
