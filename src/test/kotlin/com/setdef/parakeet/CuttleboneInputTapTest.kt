package com.setdef.parakeet

import junit.framework.TestCase
import java.io.ByteArrayOutputStream

/** Reading document versions from what IntelliJ writes to the server, however the writes are split. */
class CuttleboneInputTapTest : TestCase() {
    private fun frame(json: String): ByteArray {
        val body = json.toByteArray(Charsets.UTF_8)
        return "Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII) + body
    }

    private fun sync(method: String, uri: String, version: Int?) =
        frame("""{"jsonrpc":"2.0","method":"$method","params":{"textDocument":{"uri":"$uri"${version?.let { ""","version":$it""" } ?: ""}}}}""")

    fun testRecordsVersionsAndPassesEveryByteOn() {
        val out = ByteArrayOutputStream()
        val sent = mutableMapOf<String, Int>()
        val tap = CuttleboneInputTap(out, sent)
        val bytes = frame("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""") +
            sync("textDocument/didOpen", "file:///a.inko", 3) + sync("textDocument/didOpen", "file:///b.inko", 1) +
            // Not ASCII: Content-Length counts bytes.
            frame("""{"jsonrpc":"2.0","method":"textDocument/didChange","params":{"textDocument":{"uri":"file:///a.inko","version":4},"contentChanges":[{"text":"日本 👩‍👩‍👦"}]}}""") +
            sync("textDocument/didClose", "file:///b.inko", null)
        // A byte at a time, then in odd-sized pieces: messages split anywhere.
        for (size in listOf(1, 7, bytes.size)) {
            out.reset()
            sent.clear()
            bytes.toList().chunked(size).forEach { tap.write(it.toByteArray()) }
            assertEquals(mapOf("file:///a.inko" to 4), sent)
            assertTrue(out.toByteArray().contentEquals(bytes))
        }
    }

    fun testAMessageCountsOnlyOnceItsLastByteIsWritten() {
        val sent = mutableMapOf<String, Int>()
        val tap = CuttleboneInputTap(ByteArrayOutputStream(), sent)
        val bytes = sync("textDocument/didOpen", "file:///a.inko", 1)
        tap.write(bytes, 0, bytes.size - 1)
        assertEquals(emptyMap<String, Int>(), sent)
        tap.write(bytes, bytes.size - 1, 1)
        assertEquals(mapOf("file:///a.inko" to 1), sent)
    }
}
