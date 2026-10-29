package com.setdef.parakeet

import com.intellij.openapi.vfs.LocalFileSystem
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * Runs [block] with IntelliJ's background writes of saved files held back, as a busy machine can hold them: IntelliJ
 * 2026.2 returns from a save before the disk has it (`vfs.async-content-write`), with the document and the VFS's
 * cached content changed at once, and a background task writing the file. While held, a save reaches the disk only
 * when something waits for IntelliJ's writes (`ManagingFS.flushPendingUpdates`, which runs them itself), or once
 * [block] ends.
 *
 * It holds the local file system's one writer thread (a sequential executor) with a task of its own, by reflection:
 * none of it is API, so a test using it fails, saying so, if IntelliJ stops writing that way.
 */
fun <T> holdingWritesToDisk(block: () -> T): T {
    val fs = LocalFileSystem.getInstance()
    val writer = try {
        val tasks = fs.javaClass.getDeclaredField("ioTasksExecutor").apply { isAccessible = true }.get(fs)
        tasks.javaClass.getDeclaredField("pendingTasksExecutor").apply { isAccessible = true }.get(tasks) as ExecutorService
    } catch (e: ReflectiveOperationException) {
        throw AssertionError("IntelliJ's local file system (${fs.javaClass.name}) no longer writes saved files as holdingWritesToDisk expects", e)
    }
    val holding = CountDownLatch(1)
    val release = CountDownLatch(1)
    writer.execute {
        holding.countDown()
        release.await(5, TimeUnit.MINUTES)
    }
    check(holding.await(1, TimeUnit.MINUTES)) { "IntelliJ's writer thread didn't start the hold" }
    try {
        return block()
    } finally {
        release.countDown()
    }
}
