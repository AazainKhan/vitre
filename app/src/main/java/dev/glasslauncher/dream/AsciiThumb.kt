package dev.glasslauncher.dream

import android.graphics.Bitmap

/** A piece's still frame drawn to fit a thumbnail: the same renderer as the screensaver, at a cell size that fits the box. */
object AsciiThumb {
    fun render(piece: AsciiPiece, width: Int, height: Int): Bitmap? {
        val h = piece.head
        val cw = (if (h.cell == "square") minOf(width / h.cols, height / h.rows) else minOf(width / h.cols, height / (2 * h.rows))).coerceAtLeast(1)
        val frames = piece.frames()
        return try {
            if (!frames.next()) null else AsciiRaster(piece, cw, if (h.cell == "square") cw else 2 * cw).draw(frames)
        } finally { frames.close() }
    }
}
