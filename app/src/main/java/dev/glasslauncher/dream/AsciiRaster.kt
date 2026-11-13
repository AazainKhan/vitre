package dev.glasslauncher.dream

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import java.nio.ShortBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Draws a piece's cell grid, and only the cells a frame changed: most frames change few (the packs are deltas), so a frame costs a
 * few tile copies, not a full-screen text draw. The picture is a plain 16-bit pixel array. A cell is a tile (the glyph in its colour
 * on the background, made once per glyph and colour from an alpha mask) copied in row by row with System.arraycopy: no Canvas call per
 * cell, which on the stick cost more than the blit itself. Once per frame the array is copied into a bitmap in one go.
 * The block characters and Braille come from their geometry, so they meet edge to edge whatever the font does; in a "dots"
 * piece the lower blocks are halftone dots.
 *
 * Two bitmaps (RGB_565: half the memory and upload of ARGB), so a frame can be drawn off the UI thread while the other is on show.
 */
class AsciiRaster(piece: AsciiPiece, private val cellW: Int, private val cellH: Int) {
    private val head = piece.head
    private val cols = head.cols
    private val stride = head.cols * cellW
    private val pixels = ShortArray(stride * head.rows * cellH)
    private val bitmaps = Array(2) { Bitmap.createBitmap(stride, head.rows * cellH, Bitmap.Config.RGB_565).also { it.eraseColor(BACKGROUND) } }
    private var back = 0
    private val dots = head.look == "dots" && head.cell == "square"
    private val colors = IntArray(63) { i ->
        val raw = if (i == 0) null else head.palette.getOrNull(i - 1)
        val c = raw?.let { runCatching { android.graphics.Color.parseColor(it) }.getOrNull() } ?: DEFAULT_INK
        if (dots && i > 0) lift(c) else c
    }
    private val glyphIndex = HashMap<Char, Int>()
    private var tiles = arrayOfNulls<ShortArray>(0)
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textSize = cellH * 0.78f; textAlign = Paint.Align.CENTER; color = -1 }

    /** Draws the cells [frames] changed into the picture, copies it into the bitmap that is not on show, and returns that bitmap. */
    fun draw(frames: AsciiPiece.Frames): Bitmap {
        for (k in 0 until frames.dirtyCount) {
            val i = frames.dirty[k]
            val tile = tile(frames.text[i], (frames.color[i].toInt() and 255).coerceAtMost(62))
            val x0 = i % cols * cellW
            var at = i / cols * cellH * stride + x0
            var from = 0
            for (row in 0 until cellH) { System.arraycopy(tile, from, pixels, at, cellW); from += cellW; at += stride }
        }
        val shown = bitmaps[back]
        shown.copyPixelsFromBuffer(ShortBuffer.wrap(pixels))
        back = 1 - back
        return shown
    }

    val width get() = stride
    val height get() = head.rows * cellH

    /** The tile for [ch] in colour [color], made on first use. */
    private fun tile(ch: Char, color: Int): ShortArray {
        val g = glyphIndex.getOrPut(ch) { glyphIndex.size }
        val slot = g * 63 + color
        if (slot >= tiles.size) tiles = tiles.copyOf(maxOf(slot + 1, tiles.size * 2, 63 * 8))
        return tiles[slot] ?: makeTile(ch, colors[color]).also { tiles[slot] = it }
    }

    private fun makeTile(ch: Char, ink: Int): ShortArray {
        val t = ShortArray(cellW * cellH)
        val mask = if (ch == ' ') null else if (ch == '\u2588') FULL else render(ch)
        if (mask == null) return t   // all background: 0 is black in RGB_565
        val r = ink shr 16 and 255; val g = ink shr 8 and 255; val b = ink and 255
        for (p in t.indices) {
            val a = if (mask === FULL) 255 else mask[p].toInt() and 255
            val rr = r * a / 255; val gg = g * a / 255; val bb = b * a / 255
            t[p] = (((rr shr 3) shl 11) or ((gg shr 2) shl 5) or (bb shr 3)).toShort()
        }
        return t
    }

    /** The alpha mask of [ch] at the cell size; null for nothing to draw. */
    private fun render(ch: Char): ByteArray? {
        val m = ByteArray(cellW * cellH)
        fun fill(x0: Int, y0: Int, x1: Int, y1: Int, a: Int = 255) {
            for (y in max(y0, 0) until min(y1, cellH)) for (x in max(x0, 0) until min(x1, cellW)) m[y * cellW + x] = a.toByte()
        }
        val c = ch.code
        when {
            dots && c in 0x2581..0x2588 -> dot(m, (c - 0x2580) / 8f)
            c == 0x2580 -> fill(0, 0, cellW, cellH / 2)
            c in 0x2581..0x2587 -> fill(0, cellH - cellH * (c - 0x2580) / 8, cellW, cellH)
            c in 0x2589..0x258F -> fill(0, 0, cellW * (0x2590 - c) / 8, cellH)
            c == 0x2590 -> fill(cellW / 2, 0, cellW, cellH)
            c == 0x2591 -> fill(0, 0, cellW, cellH, 64)
            c == 0x2592 -> fill(0, 0, cellW, cellH, 128)
            c == 0x2593 -> fill(0, 0, cellW, cellH, 192)
            c == 0x2594 -> fill(0, 0, cellW, cellH / 8)
            c == 0x2595 -> fill(cellW - cellW / 8, 0, cellW, cellH)
            c in 0x2800..0x28FF -> {
                // Braille: bits 0-7 are dots (col 0 rows 0-2, col 1 rows 0-2, then the bottom row's two).
                val bits = c - 0x2800
                val pos = intArrayOf(0, 0, 0, 1, 0, 2, 1, 0, 1, 1, 1, 2, 0, 3, 1, 3)
                val dw = (cellW / 4).coerceAtLeast(1); val dh = (cellH / 8).coerceAtLeast(1)
                for (b in 0 until 8) if (bits and (1 shl b) != 0) {
                    val cx = cellW * (2 * pos[2 * b] + 1) / 4; val cy = cellH * (2 * pos[2 * b + 1] + 1) / 8
                    fill(cx - dw / 2, cy - dh / 2, cx - dw / 2 + dw, cy - dh / 2 + dh)
                }
            }
            else -> {
                val scratch = Bitmap.createBitmap(cellW, cellH, Bitmap.Config.ARGB_8888)
                Canvas(scratch).drawText(ch.toString(), cellW / 2f, cellH * 0.78f, glyph)
                val px = IntArray(cellW * cellH)
                scratch.getPixels(px, 0, cellW, 0, 0, cellW, cellH)
                scratch.recycle()
                var any = false
                for (i in px.indices) { val a = px[i] ushr 24; m[i] = a.toByte(); if (a != 0) any = true }
                if (!any) return null
            }
        }
        return m
    }

    /** A halftone dot covering [fraction] of the cell's area, antialiased; a full cell is a full cell. */
    private fun dot(m: ByteArray, fraction: Float) {
        val side = min(cellW, cellH).toFloat()
        val r = side * sqrt(fraction / Math.PI.toFloat())
        val cx = cellW / 2f; val cy = cellH / 2f
        for (y in 0 until cellH) for (x in 0 until cellW) {
            val d = sqrt((x + 0.5f - cx) * (x + 0.5f - cx) + (y + 0.5f - cy) * (y + 0.5f - cy))
            val a = if (fraction >= 1f) 1f else (r - d + 0.5f).coerceIn(0f, 1f)
            m[y * cellW + x] = (a * 255f + 0.5f).toInt().toByte()
        }
    }

    companion object {
        private val FULL = ByteArray(0)
        const val BACKGROUND = 0xFF000000.toInt()
        const val DEFAULT_INK = 0xFFD8DEE9.toInt()

        /** In a halftone look size carries the shading, so a colour is lifted to stay visible (the library's own rule: L = max(l, 0.2 + 0.5 * sqrt l)). */
        fun lift(argb: Int): Int {
            val r = (argb shr 16 and 255) / 255f; val g = (argb shr 8 and 255) / 255f; val b = (argb and 255) / 255f
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            val l = (mx + mn) / 2f; val d = mx - mn
            val sat = if (d != 0f) d / (1f - abs(2f * l - 1f)) else 0f
            var h = 0f
            if (d != 0f) h = when (mx) { r -> ((g - b) / d + 6f) % 6f; g -> (b - r) / d + 2f; else -> (r - g) / d + 4f }
            val bigL = max(l, 0.2f + 0.5f * sqrt(l))
            val c = (1f - abs(2f * bigL - 1f)) * sat
            val x = c * (1f - abs(h % 2f - 1f))
            val m = bigL - c / 2f
            val (rr, gg, bb) = when { h < 1f -> Triple(c, x, 0f); h < 2f -> Triple(x, c, 0f); h < 3f -> Triple(0f, c, x); h < 4f -> Triple(0f, x, c); h < 5f -> Triple(x, 0f, c); else -> Triple(c, 0f, x) }
            fun ch(v: Float) = ((v + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
            return (255 shl 24) or (ch(rr) shl 16) or (ch(gg) shl 8) or ch(bb)
        }
    }
}
