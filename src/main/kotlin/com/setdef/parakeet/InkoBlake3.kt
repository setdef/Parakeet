package com.setdef.parakeet

/**
 * BLAKE3 (hash mode, 32-byte output), which `inko` uses to remember which
 * `inko.pkg` it last synced (in `dep/hash`). The IDE doesn't bundle BLAKE3, so
 * this follows the reference implementation in the BLAKE3 paper.
 */
object InkoBlake3 {
    private const val BLOCK_LEN = 64
    private const val CHUNK_LEN = 1024
    private const val CHUNK_START = 1
    private const val CHUNK_END = 2
    private const val PARENT = 4
    private const val ROOT = 8

    private val IV = intArrayOf(
        0x6A09E667, -0x4498517b, 0x3C6EF372, -0x5ab00ac6, 0x510E527F, -0x64fa9774, 0x1F83D9AB, 0x5BE0CD19,
    )
    private val PERMUTATION = intArrayOf(2, 6, 3, 10, 7, 0, 4, 13, 1, 11, 12, 5, 9, 14, 15, 8)

    /** The lowercase hex BLAKE3 hash of [input]. */
    fun hex(input: ByteArray): String {
        val stack = ArrayDeque<IntArray>()
        var chunk = Chunk(IV, 0)
        var offset = 0
        while (offset < input.size) {
            if (chunk.length == CHUNK_LEN) {
                var cv = chunk.output().chainingValue()
                var total = chunk.counter + 1
                // Merge completed subtrees: one for each trailing zero bit of the chunk count.
                while (total and 1L == 0L) {
                    cv = parent(stack.removeLast(), cv).chainingValue()
                    total = total shr 1
                }
                stack.addLast(cv)
                chunk = Chunk(IV, chunk.counter + 1)
            }
            val take = minOf(CHUNK_LEN - chunk.length, input.size - offset)
            chunk.update(input, offset, take)
            offset += take
        }
        var output = chunk.output()
        while (stack.isNotEmpty()) output = parent(stack.removeLast(), output.chainingValue())
        return output.rootBytes().joinToString("") { "%02x".format(it) }
    }

    private class Output(val cv: IntArray, val block: IntArray, val counter: Long, val blockLength: Int, val flags: Int) {
        fun chainingValue(): IntArray = compress(cv, block, counter, blockLength, flags).copyOf(8)

        fun rootBytes(): ByteArray {
            val words = compress(cv, block, 0, blockLength, flags or ROOT)
            return ByteArray(32) { (words[it / 4] ushr (8 * (it % 4))).toByte() }
        }
    }

    private class Chunk(key: IntArray, val counter: Long) {
        private var cv = key.copyOf()
        private val block = ByteArray(BLOCK_LEN)
        private var blockLength = 0
        private var blocksCompressed = 0
        val length: Int get() = BLOCK_LEN * blocksCompressed + blockLength

        private fun startFlag() = if (blocksCompressed == 0) CHUNK_START else 0

        fun update(input: ByteArray, from: Int, count: Int) {
            var offset = from
            val end = from + count
            while (offset < end) {
                // A full block is only compressed once more input arrives: the last block is the chunk's output.
                if (blockLength == BLOCK_LEN) {
                    cv = compress(cv, words(block), counter, BLOCK_LEN, startFlag()).copyOf(8)
                    blocksCompressed++
                    block.fill(0)
                    blockLength = 0
                }
                val take = minOf(BLOCK_LEN - blockLength, end - offset)
                System.arraycopy(input, offset, block, blockLength, take)
                blockLength += take
                offset += take
            }
        }

        fun output() = Output(cv, words(block), counter, blockLength, startFlag() or CHUNK_END)
    }

    private fun parent(left: IntArray, right: IntArray) = Output(IV, left + right, 0, BLOCK_LEN, PARENT)

    private fun words(bytes: ByteArray) = IntArray(16) { i ->
        (0 until 4).fold(0) { word, b -> word or ((bytes[i * 4 + b].toInt() and 0xFF) shl (8 * b)) }
    }

    private fun compress(cv: IntArray, block: IntArray, counter: Long, blockLength: Int, flags: Int): IntArray {
        val s = intArrayOf(
            cv[0], cv[1], cv[2], cv[3], cv[4], cv[5], cv[6], cv[7],
            IV[0], IV[1], IV[2], IV[3], counter.toInt(), (counter ushr 32).toInt(), blockLength, flags,
        )
        var m = block.copyOf()
        repeat(7) { round ->
            g(s, 0, 4, 8, 12, m[0], m[1]); g(s, 1, 5, 9, 13, m[2], m[3])
            g(s, 2, 6, 10, 14, m[4], m[5]); g(s, 3, 7, 11, 15, m[6], m[7])
            g(s, 0, 5, 10, 15, m[8], m[9]); g(s, 1, 6, 11, 12, m[10], m[11])
            g(s, 2, 7, 8, 13, m[12], m[13]); g(s, 3, 4, 9, 14, m[14], m[15])
            if (round < 6) m = IntArray(16) { m[PERMUTATION[it]] }
        }
        for (i in 0 until 8) {
            s[i] = s[i] xor s[i + 8]
            s[i + 8] = s[i + 8] xor cv[i]
        }
        return s
    }

    private fun g(s: IntArray, a: Int, b: Int, c: Int, d: Int, x: Int, y: Int) {
        s[a] = s[a] + s[b] + x
        s[d] = (s[d] xor s[a]).rotateRight(16)
        s[c] = s[c] + s[d]
        s[b] = (s[b] xor s[c]).rotateRight(12)
        s[a] = s[a] + s[b] + y
        s[d] = (s[d] xor s[a]).rotateRight(8)
        s[c] = s[c] + s[d]
        s[b] = (s[b] xor s[c]).rotateRight(7)
    }
}
