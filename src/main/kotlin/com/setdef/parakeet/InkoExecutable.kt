package com.setdef.parakeet

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Files
import java.nio.file.Path

/** Locates the `inko` executable: the configured path, else PATH, else ivm's install directory. */
object InkoExecutable {
    private val name: String
        get() = if (SystemInfo.isWindows) "inko.exe" else "inko"

    fun find(): Path? {
        val configured = InkoSettings.getInstance().state.executablePath
        if (!configured.isNullOrBlank()) return Path.of(configured).takeIf(Files::isExecutable)
        return detect()
    }

    fun detect(): Path? {
        PathEnvironmentVariableUtil.findInPath(name)?.let { return it.toPath() }
        return ivmBinDirectory()?.resolve(name)?.takeIf(Files::isExecutable)
    }

    // Mirrors ivm's own lookup in ivm/src/config.rs.
    private fun ivmBinDirectory(): Path? {
        val home = System.getProperty("user.home") ?: return null
        val data = when {
            SystemInfo.isMac -> Path.of(home, "Library", "Application Support")
            else -> System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotEmpty() }?.let(Path::of)
                ?: Path.of(home, ".local", "share")
        }
        return data.resolve("ivm").resolve("bin")
    }
}
