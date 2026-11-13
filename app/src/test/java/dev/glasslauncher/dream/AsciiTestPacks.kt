package dev.glasslauncher.dream

import java.util.zip.Deflater

/** Builds packs in the format of scripts/ascii-bake.mjs, for tests. */
object AsciiTestPacks {
    const val COLS = 8
    const val ROWS = 3
    private const val N = COLS * ROWS

    /** A frame as the baker writes it: text low plane, text high plane, colour plane (key index + 1). */
    fun frame(text: String, color: Int = 0): ByteArray {
        val b = ByteArray(3 * N)
        for (i in 0 until N) { val c = text.getOrElse(i) { ' ' }.code; b[i] = (c and 255).toByte(); b[N + i] = (c shr 8).toByte(); b[2 * N + i] = color.toByte() }
        return b
    }

    fun pack(frames: List<ByteArray>, head: String = """{"v":2,"slug":"t","cols":$COLS,"rows":$ROWS,"fps":10,"n":${frames.size},"palette":["#ff0000"]}"""): ByteArray {
        val stream = java.io.ByteArrayOutputStream()
        frames.forEachIndexed { k, f -> stream.write(if (k == 0) f else ByteArray(f.size) { (f[it].toInt() xor frames[k - 1][it].toInt()).toByte() }) }
        // the closing delta, from the last frame back to the first
        stream.write(ByteArray(frames[0].size) { (frames[0][it].toInt() xor frames.last()[it].toInt()).toByte() })
        val d = Deflater(9, true).also { it.setInput(stream.toByteArray()); it.finish() }
        val out = ByteArray(stream.size() + 64); val len = d.deflate(out)
        val h = head.toByteArray()
        return "ASC1".toByteArray() + byteArrayOf(0, 0, (h.size shr 8).toByte(), h.size.toByte()) + h + out.copyOf(len)
    }
}
