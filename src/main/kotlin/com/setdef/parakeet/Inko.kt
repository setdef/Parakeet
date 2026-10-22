package com.setdef.parakeet

import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path

object Inko {
    const val EXTENSION = "inko"

    /** Shown in Settings | Appearance & Behavior | Notifications. */
    const val NOTIFICATION_GROUP = "Parakeet for Inko"

    fun isInkoFile(file: VirtualFile?): Boolean = pathOf(file) != null

    /** The on-disk path of an Inko [file], or null for other files and ones not on disk. */
    fun pathOf(file: VirtualFile?): Path? =
        file?.takeIf { it.extension == EXTENSION }?.let { it.fileSystem.getNioPath(it) }

    /** The module name the compiler gives [file], e.g. `app.util` for `src/app/util.inko`. */
    fun moduleName(file: Path): String? {
        val root = projectRoot(file)
        for (dir in listOf("src", "test")) {
            val base = root.resolve(dir)
            if (file.startsWith(base)) return base.relativize(file).toString().removeSuffix(".$EXTENSION").replace(java.io.File.separatorChar, '.')
        }
        return null
    }

    /**
     * The directory `inko` should run in for [file], mirroring how the compiler
     * finds project roots: the parent of the nearest `src` directory, or of the
     * nearest `test` directory that has a sibling `src`, or the file's own
     * directory for standalone scripts.
     */
    fun projectRoot(file: Path): Path {
        var dir = file.parent
        while (dir != null) {
            val parent = dir.parent ?: break
            when (dir.fileName?.toString()) {
                "src" -> return parent
                "test" -> if (Files.isDirectory(parent.resolve("src"))) return parent
            }
            dir = parent
        }
        return file.parent
    }
}
