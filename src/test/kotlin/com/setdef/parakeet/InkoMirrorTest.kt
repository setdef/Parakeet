package com.setdef.parakeet

import com.intellij.openapi.util.io.NioFiles
import junit.framework.TestCase
import java.nio.file.Files
import java.nio.file.Path

class InkoMirrorTest : TestCase() {
    private lateinit var base: Path
    private lateinit var root: Path

    override fun setUp() {
        super.setUp()
        base = Files.createTempDirectory("mirror-test").toRealPath()
        root = Files.createDirectories(base.resolve("project"))
        Files.createDirectories(root.resolve("src/app"))
        Files.writeString(root.resolve("src/app/util.inko"), "fn pub a {}\n")
        Files.createDirectories(root.resolve("test"))
        Files.writeString(root.resolve("test/test_util.inko"), "")
        Files.writeString(root.resolve("inko.pkg"), "require inko 0.20.0\n")
        Files.createDirectories(root.resolve("dep/pkg"))
        Files.writeString(root.resolve("dep/pkg/precious.inko"), "keep me\n")
    }

    override fun tearDown() {
        NioFiles.deleteRecursively(base)
        super.tearDown()
    }

    fun testClosingATemporaryMirrorNeverDeletesTheProject() {
        val directory = InkoMirror.create(root).use { mirror ->
            // The project's packages are linked into the mirror's own dep/, never copied or moved.
            assertTrue(Files.isSymbolicLink(mirror.directory.resolve("dep/pkg")))
            mirror.directory
        }
        assertFalse(Files.exists(directory))
        assertEquals("keep me\n", Files.readString(root.resolve("dep/pkg/precious.inko")))
        assertEquals("fn pub a {}\n", Files.readString(root.resolve("src/app/util.inko")))
    }

    fun testPersistentMirrorsRefreshLinksAndKeepTheirBuild() {
        val directory = base.resolve("persistent")
        InkoMirror.persistent(root, directory).use { mirror ->
            Files.createDirectories(mirror.directory.resolve("build/debug"))
            Files.writeString(mirror.directory.resolve("build/debug/cache"), "cached")
        }
        Files.delete(root.resolve("src/app/util.inko"))
        Files.writeString(root.resolve("src/app/new.inko"), "")

        InkoMirror.persistent(root, directory).use { mirror ->
            assertEquals("cached", Files.readString(mirror.directory.resolve("build/debug/cache")))
            assertFalse(Files.exists(mirror.directory.resolve("src/app/util.inko"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            assertTrue(Files.isSymbolicLink(mirror.directory.resolve("src/app/new.inko")))
        }
        assertTrue(Files.exists(directory))
        assertEquals("keep me\n", Files.readString(root.resolve("dep/pkg/precious.inko")))
    }

    fun testSwapsInUnsavedText() {
        InkoMirror.create(root, root.resolve("src/app/util.inko"), "fn pub b {}\n").use { mirror ->
            val copy = mirror.directory.resolve("src/app/util.inko")
            assertFalse(Files.isSymbolicLink(copy))
            assertEquals("fn pub b {}\n", Files.readString(copy))
        }
        assertEquals("fn pub a {}\n", Files.readString(root.resolve("src/app/util.inko")))
    }
}
