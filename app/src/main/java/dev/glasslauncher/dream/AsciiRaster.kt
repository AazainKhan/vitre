package dev.glasslauncher.dream

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Draws a piece's cell grid into bitmaps, and only the cells a frame changed: most frames change few (the packs are deltas), so a frame
 * costs a few draws, not a full-screen text draw. A cell is a background rectangle and, if it holds a glyph, a tinted alpha mask of that
 * glyph (one small ALPHA_8 bitmap per glyph, made once; the full block is a plain rectangle). The block characters and Braille come from
 * their geometry, so they meet edge to edge whatever the font does; in a "dots" piece the lower blocks are halftone dots.
 *
 * Two buffers (RGB_565: half the memory and upload of ARGB), so a frame can be drawn off the UI thread while the other is on show.
 * Each frame draws the cells the buffer is missing: the ones the frame before it changed, and its own.
 */
class AsciiRaster(piece: AsciiPiece, private val cellW: Int, private val cellH: Int) {
    private val head = piece.head
    private val cells = head.cols * head.rows
    private val bitmaps = Array(2) { Bitmap.createBitmap(head.cols * cellW, head.rows * cellH, Bitmap.Config.RGB_565).also { it.eraseColor(BACKGROUND) } }
    private val canvases = Array(2) { Canvas(bitmaps[it]) }
    private var back = 0
    private val previous = IntArray(cells)
    private var previousCount = 0
    private val stamp = IntArray(cells)
    private var round = 0
    private val dots = head.look == "dots" && head.cell == "square"
    private val colors = IntArray(63) { i ->
        val raw = if (i == 0) null else head.palette.getOrNull(i - 1)
        val c = raw?.let { runCatching { android.graphics.Color.parseColor(it) }.getOrNull() } ?: DEFAULT_INK
        if (dots && i > 0) lift(c) else c
    }
    private val background = Paint().apply { color = BACKGROUND; style = Paint.Style.FILL }
    private val ink = Paint()
    private val masks = HashMap<Char, Bitmap?>()
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textSize = cellH * 0.78f; textAlign = Paint.Align.CENTER; color = -1 }

    /** Draws [frames]' current frame into the buffer that is not on show, and returns that buffer (now the one to show). */
    fun draw(frames: AsciiPiece.Frames): Bitmap {
        val canvas = canvases[back]
        round++
        for (k in 0 until previousCount) { val i = previous[k]; stamp[i] = round; paint(canvas, i, frames) }
        for (k in 0 until frames.dirtyCount) { val i = frames.dirty[k]; if (stamp[i] != round) paint(canvas, i, frames) }
        System.arraycopy(frames.dirty, 0, previous, 0, frames.dirtyCount)
        previousCount = frames.dirtyCount
        val shown = bitmaps[back]
        back = 1 - back
        return shown
    }

    val width get() = bitmaps[0].width
    val height get() = bitmaps[0].height

    private fun paint(canvas: Canvas, i: Int, frames: AsciiPiece.Frames) {
        val l = (i % head.cols * cellW).toFloat()
        val t = (i / head.cols * cellH).toFloat()
        canvas.drawRect(l, t, l + cellW, t + cellH, background)
        val ch = frames.text[i]
        if (ch == ' ') return
        ink.color = colors[(frames.color[i].toInt() and 255).coerceAtMost(62)]
        if (ch == '█') { canvas.drawRect(l, t, l + cellW, t + cellH, ink); return }
        val mask = masks.getOrPut(ch) { render(ch)?.let { alpha8(it) } } ?: return
        canvas.drawBitmap(mask, l, t, ink)
    }

    private fun alpha8(m: ByteArray): Bitmap = Bitmap.createBitmap(cellW, cellH, Bitmap.Config.ALPHA_8).also { it.copyPixelsFromBuffer(ByteBuffer.wrap(m)) }

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
