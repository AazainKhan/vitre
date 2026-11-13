package dev.glasslauncher.dream

import java.util.zip.Inflater
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A baked ASCII piece's header (scripts/ascii-bake.mjs writes it; the pack is untrusted input, so everything here is bounded). */
@Serializable
class AsciiHead(
    val v: Int,
    val slug: String,
    val name: String = "",
    val category: String = "",
    val note: String = "",
    val cols: Int,
    val rows: Int,
    val fps: Int,
    /** Frames in one loop. */
    val n: Int,
    /** "still" (one frame), "period" (an exact repeat) or "dissolve" (the end dissolves into the start). */
    val loop: String = "dissolve",
    /** "text" cells are twice as tall as wide; "square" cells are not. */
    val cell: String = "text",
    /** "dots": the piece's lower-block characters stand for brightness and are drawn as halftone dots. */
    val look: String? = null,
    /** The palette keys 0-9, a-z, A-Z in order: a colour as #rrggbb, or null. */
    val palette: List<String?> = emptyList(),
)

/**
 * One baked piece: its header and the deflated frame stream. The stream holds, per frame, three planes of cols*rows bytes (the text
 * code unit's low byte, its high byte, a colour key index + 1 or 0), each XOR the previous frame's, as one raw deflate stream,
 * then one last delta from the final frame back to the first (so the loop closes with a step as small as any other):
 * [Frames] inflates it a frame at a time and starts over at the end, so a loop costs no more memory than one frame.
 */
class AsciiPiece(val head: AsciiHead, private val bytes: ByteArray, private val offset: Int) {
    val cells get() = head.cols * head.rows

    fun frames() = Frames()

    inner class Frames {
        private var inflater = newInflater()
        private var index = 0
        private val frame = ByteArray(3 * cells)
        private val read = ByteArray(3 * cells)
        /** The picture now: a text code unit and a colour index per cell. */
        val text = CharArray(cells)
        val color = ByteArray(cells)
        /** The cells [next] changed, [dirtyCount] of them (all of them on the very first frame). */
        val dirty = IntArray(cells)
        var dirtyCount = 0; private set

        private fun newInflater() = Inflater(true).also { it.setInput(bytes, offset, bytes.size - offset) }

        /** Fills [read] with the next chunk of the stream; false if it is damaged or ends early. */
        private fun chunk(): Boolean {
            var got = 0
            while (got < read.size) {
                val r = try { inflater.inflate(read, got, read.size - got) } catch (_: java.util.zip.DataFormatException) { return false }
                if (r == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) return false
                got += r
            }
            return true
        }

        /** Advances one frame; false if the stream is damaged (the caller stops). */
        fun next(): Boolean {
            if (!chunk()) return false
            val first = index == 0
            apply(first)
            index++
            if (index > head.n) {
                // That chunk was the closing delta: the picture is frame 0 again. Start the stream over past its whole first frame.
                inflater.end(); inflater = newInflater()
                if (!chunk()) return false
                index = 1
            }
            return true
        }

        private fun apply(all: Boolean) {
            val n = cells
            var d = 0
            for (i in 0 until n) {
                val dl = read[i].toInt(); val dh = read[n + i].toInt(); val dc = read[2 * n + i].toInt()
                if (all || dl != 0 || dh != 0 || dc != 0) dirty[d++] = i
                val lo = frame[i].toInt() xor dl
                val hi = frame[n + i].toInt() xor dh
                val c = frame[2 * n + i].toInt() xor dc
                frame[i] = lo.toByte(); frame[n + i] = hi.toByte(); frame[2 * n + i] = c.toByte()
                text[i] = (((hi and 255) shl 8) or (lo and 255)).toChar()
                color[i] = c.toByte()
            }
            dirtyCount = d
        }

        fun close() = inflater.end()
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private const val MAX_CELLS = 240 * 120
        private const val MAX_FRAMES = 4000

        /** The piece in [bytes], or null if it is not a well-formed pack of this version within the size limits. */
        fun parse(bytes: ByteArray): AsciiPiece? = runCatching {
            if (bytes.size < 8 || String(bytes, 0, 4, Charsets.US_ASCII) != "ASC1") return null
            val len = ((bytes[4].toInt() and 255) shl 24) or ((bytes[5].toInt() and 255) shl 16) or ((bytes[6].toInt() and 255) shl 8) or (bytes[7].toInt() and 255)
            if (len < 2 || len > 64 * 1024 || 8 + len >= bytes.size) return null
            val head = json.decodeFromString(AsciiHead.serializer(), String(bytes, 8, len, Charsets.UTF_8))
            if (head.v != 2 || head.cols !in 8..240 || head.rows !in 3..120 || head.cols * head.rows > MAX_CELLS) return null
            if (head.fps !in 1..60 || head.n !in 1..MAX_FRAMES) return null
            AsciiPiece(head, bytes, 8 + len)
        }.getOrNull()
    }
}
