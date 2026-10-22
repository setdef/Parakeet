package com.setdef.parakeet

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Which `inko` a project uses, stored as a string in `.idea/parakeet.xml`:
 * empty (auto-detect), `ivm:default`, `ivm:<version>`, or `path:<executable>`.
 */
sealed class InkoToolchain {
    abstract val id: String
    abstract val label: String
    abstract fun executable(): Path?

    /** `inko` on PATH, then ivm's default version. */
    data object Auto : InkoToolchain() {
        override val id = ""
        override val label = "Auto-detect"
        override fun executable(): Path? =
            PathEnvironmentVariableUtil.findInPath(Ivm.EXECUTABLE)?.toPath() ?: IvmDefault.executable()
    }

    /** Whatever version `ivm default` points at. */
    data object IvmDefault : InkoToolchain() {
        override val id = "ivm:default"
        override val label = "ivm default"
        override fun executable(): Path? = Ivm.binDirectory()?.resolve(Ivm.EXECUTABLE)?.takeIf(Files::isExecutable)
    }

    data class IvmVersion(val version: String) : InkoToolchain() {
        override val id = "ivm:$version"
        override val label = "ivm $version"
        override fun executable(): Path? =
            Ivm.installedDirectory()?.resolve(version)?.resolve("bin")?.resolve(Ivm.EXECUTABLE)?.takeIf(Files::isExecutable)
    }

    data class Custom(val path: String) : InkoToolchain() {
        override val id = "path:$path"
        override val label = "Custom"
        override fun executable(): Path? = path.takeIf { it.isNotBlank() }?.let { Path.of(it) }?.takeIf(Files::isExecutable)
    }

    companion object {
        fun fromId(id: String?): InkoToolchain = when {
            id.isNullOrEmpty() -> Auto
            id == IvmDefault.id -> IvmDefault
            id.startsWith("ivm:") -> IvmVersion(id.removePrefix("ivm:"))
            id.startsWith("path:") -> Custom(id.removePrefix("path:"))
            else -> Auto
        }

        /** Every toolchain worth offering: auto, ivm's default, and each version ivm has installed. */
        fun available(): List<InkoToolchain> = buildList {
            add(Auto)
            if (IvmDefault.executable() != null) add(IvmDefault)
            Ivm.installedVersions().forEach { add(IvmVersion(it)) }
        }

        private val versions = ConcurrentHashMap<Path, String>()

        /** `inko --version` output (e.g. "inko 0.21.1"), cached per executable. Runs a process. */
        fun versionOf(executable: Path): String? = versions[executable] ?: run {
            val output = CapturingProcessHandler(GeneralCommandLine(executable.toString(), "--version")).runProcess(5_000)
            output.stdout.trim().takeIf { output.exitCode == 0 && it.isNotEmpty() }?.also { versions[executable] = it }
        }
    }
}

/** Mirrors ivm's directory layout from ivm/src/config.rs. */
object Ivm {
    val EXECUTABLE: String
        get() = if (SystemInfo.isWindows) "inko.exe" else "inko"

    private fun dataDirectory(): Path? {
        val home = System.getProperty("user.home") ?: return null
        val base = when {
            SystemInfo.isMac -> Path.of(home, "Library", "Application Support")
            else -> System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotEmpty() }?.let(Path::of)
                ?: Path.of(home, ".local", "share")
        }
        return base.resolve("ivm")
    }

    fun binDirectory(): Path? = dataDirectory()?.resolve("bin")

    fun installedDirectory(): Path? = dataDirectory()?.resolve("installed")

    fun installedVersions(): List<String> {
        val dir = installedDirectory()?.takeIf { it.isDirectory() } ?: return emptyList()
        return dir.listDirectoryEntries()
            .filter { Files.isExecutable(it.resolve("bin").resolve(EXECUTABLE)) }
            .map { it.name }
            .sortedWith(compareByDescending<String> { v -> v.split('.').map { it.toIntOrNull() ?: 0 }.let { it + List(3) { 0 } }.let { (a, b, c) -> a * 1_000_000L + b * 1_000L + c } })
    }
}

@Service(Service.Level.PROJECT)
@State(name = "ParakeetSettings", storages = [Storage("parakeet.xml")])
class InkoProjectSettings : SimplePersistentStateComponent<InkoProjectSettings.State>(State()) {
    class State : BaseState() {
        var toolchain by string()
    }

    var toolchain: InkoToolchain
        get() = InkoToolchain.fromId(state.toolchain)
        set(value) {
            state.toolchain = value.id.ifEmpty { null }
        }

    companion object {
        fun getInstance(project: Project): InkoProjectSettings = project.service()
    }
}

object InkoExecutable {
    /** The `inko` executable for [project], or auto-detected when there's no project. */
    fun find(project: Project?): Path? =
        (project?.let { InkoProjectSettings.getInstance(it).toolchain } ?: InkoToolchain.Auto).executable()
}
