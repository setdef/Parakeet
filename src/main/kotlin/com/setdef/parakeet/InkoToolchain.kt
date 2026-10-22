package com.setdef.parakeet

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
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
        override fun executable(): Path? = PathEnvironmentVariableUtil.findInPath("inko")?.toPath() ?: IvmDefault.executable()
    }

    /** Whatever version `ivm default` points at. */
    data object IvmDefault : InkoToolchain() {
        override val id = "ivm:default"
        override val label = "ivm default"
        override fun executable(): Path? = Ivm.binDirectory()?.resolve("inko")?.takeIf(Files::isExecutable)
    }

    data class IvmVersion(val version: String) : InkoToolchain() {
        override val id = "ivm:$version"
        override val label = "ivm $version"
        override fun executable(): Path? = Ivm.executableOf(version)
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
    }
}

/** ivm's directory layout, mirroring ivm/src/config.rs. (Inko, and so ivm, doesn't support Windows.) */
object Ivm {
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

    /** The `ivm` executable: on PATH, or where `cargo install ivm` puts it. */
    fun executable(): Path? = PathEnvironmentVariableUtil.findInPath("ivm")?.toPath()
        ?: System.getProperty("user.home")?.let { Path.of(it, ".cargo", "bin", "ivm") }?.takeIf(Files::isExecutable)

    private fun installedDirectory(): Path? = dataDirectory()?.resolve("installed")

    fun executableOf(version: String): Path? =
        installedDirectory()?.resolve(version)?.resolve("bin")?.resolve("inko")?.takeIf(Files::isExecutable)

    /** Installed versions, newest first. */
    fun installedVersions(): List<String> {
        val dir = installedDirectory()?.takeIf { it.isDirectory() } ?: return emptyList()
        return newestFirst(dir.listDirectoryEntries().map { it.name }.filter { executableOf(it) != null })
    }

    /** Sorts version strings like "0.9.0" and "0.10.0" numerically, newest first. */
    fun newestFirst(versions: List<String>): List<String> = versions.sortedWith(VERSION_ORDER.reversed())

    private val VERSION_ORDER = Comparator<String> { a, b ->
        val left = a.split('.').map { it.toIntOrNull() ?: 0 }
        val right = b.split('.').map { it.toIntOrNull() ?: 0 }
        (0 until maxOf(left.size, right.size))
            .map { (left.getOrNull(it) ?: 0).compareTo(right.getOrNull(it) ?: 0) }
            .firstOrNull { it != 0 } ?: 0
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
