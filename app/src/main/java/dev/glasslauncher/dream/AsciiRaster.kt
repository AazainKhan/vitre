package dev.glasslauncher.dream

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Draws a piece's cell grid into one bitmap, and only the cells a frame changed: most frames change few (the packs are
 * deltas), so a frame costs a few blits, not a full-screen text draw. A glyph is rendered once to an alpha mask and
 * kept; the block characters and Braille are drawn from their geometry, so they meet edge to edge whatever the font does.
 */
class AsciiRaster(private val piece: AsciiPiece, private val cellW: Int, private val cellH: Int) {
    private val head = piece.head
    val bitmap: Bitmap = Bitmap.createBitmap(head.cols * cellW, head.rows * cellH, Bitmap.Config.ARGB_8888).also { it.eraseColor(BACKGROUND) }
    private val colors = IntArray(63) { i ->
        if (i == 0) DEFAULT_INK else head.palette.getOrNull(i - 1)?.let { runCatching { android.graphics.Color.parseColor(it) }.getOrNull() } ?: DEFAULT_INK
    }
    private val masks = HashMap<Char, ByteArray>()
    private val cell = IntArray(cellW * cellH)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textSize = cellH * 0.78f; textAlign = Paint.Align.CENTER; color = -1 }
    private val glyphBitmap by lazy { Bitmap.createBitmap(cellW, cellH, Bitmap.Config.ARGB_8888) }
    private val glyphCanvas by lazy { Canvas(glyphBitmap) }

    /** Paints every cell of [frames] that changed. */
    fun draw(frames: AsciiPiece.Frames) {
        for (k in 0 until frames.dirtyCount) {
            val i = frames.dirty[k]
            val x = i % head.cols
            val y = i / head.cols
            blit(x, y, frames.text[i], colors[(frames.color[i].toInt() and 255).coerceAtMost(62)])
        }
    }

    private fun blit(x: Int, y: Int, ch: Char, ink: Int) {
        val mask = if (ch == ' ') null else masks.getOrPut(ch) { render(ch) }
        if (mask == null) { cell.fill(BACKGROUND) } else {
            val r = (ink shr 16) and 255; val g = (ink shr 8) and 255; val b = ink and 255
            for (p in cell.indices) {
                val a = mask[p].toInt() and 255
                cell[p] = if (a == 0) BACKGROUND else if (a == 255) ink or (255 shl 24)
                else (255 shl 24) or ((r * a / 255) shl 16) or ((g * a / 255) shl 8) or (b * a / 255)
            }
        }
        bitmap.setPixels(cell, 0, cellW, x * cellW, y * cellH, cellW, cellH)
    }

    /** The alpha mask of [ch] at the cell size. */
    private fun render(ch: Char): ByteArray {
        val m = ByteArray(cellW * cellH)
        fun fill(x0: Int, y0: Int, x1: Int, y1: Int, a: Int = 255) {
            for (y in y0.coerceAtLeast(0) until y1.coerceAtMost(cellH)) for (x in x0.coerceAtLeast(0) until x1.coerceAtMost(cellW)) m[y * cellW + x] = a.toByte()
        }
        val c = ch.code
        when {
            c == 0x2588 -> fill(0, 0, cellW, cellH)
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
                val dots = intArrayOf(0, 0, 0, 1, 0, 2, 1, 0, 1, 1, 1, 2, 0, 3, 1, 3)
                val dw = (cellW / 4).coerceAtLeast(1); val dh = (cellH / 8).coerceAtLeast(1)
                for (b in 0 until 8) if (bits and (1 shl b) != 0) {
                    val dx = dots[2 * b]; val dy = dots[2 * b + 1]
                    val cx = cellW * (2 * dx + 1) / 4; val cy = cellH * (2 * dy + 1) / 8
                    fill(cx - dw / 2, cy - dh / 2, cx - dw / 2 + dw, cy - dh / 2 + dh)
                }
            }
            else -> {
                glyphBitmap.eraseColor(0)
                glyphCanvas.drawText(ch.toString(), cellW / 2f, cellH * 0.78f, paint)
                val px = IntArray(cellW * cellH)
                glyphBitmap.getPixels(px, 0, cellW, 0, 0, cellW, cellH)
                for (i in px.indices) m[i] = (px[i] ushr 24).toByte()
            }
        }
        return m
    }

    companion object {
        const val BACKGROUND = 0xFF000000.toInt()
        const val DEFAULT_INK = 0xFFD8DEE9.toInt()
    }
}
