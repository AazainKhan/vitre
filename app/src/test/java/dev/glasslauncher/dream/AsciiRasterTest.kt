package dev.glasslauncher.dream

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The cell renderer (P327): a changed cell is painted in its colour at its place, and an unchanged one is left alone. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AsciiRasterTest {
    private val cw = 4
    private val ch = 8

    private fun raster(vararg frames: String, color: Int = 1): Pair<AsciiRaster, AsciiPiece.Frames> {
        val piece = AsciiPiece.parse(AsciiTestPacks.pack(frames.map { AsciiTestPacks.frame(it, color) }))!!
        return AsciiRaster(piece, cw, ch) to piece.frames()
    }

    private fun px(r: AsciiRaster, cellX: Int, cellY: Int, x: Int = cw / 2, y: Int = ch / 2) = r.bitmap.getPixel(cellX * cw + x, cellY * ch + y)

    @Test fun aFullBlockFillsItsCellInThePaletteColourAndASpaceStaysBlack() {
        val (r, f) = raster("\u2588 ")
        f.next(); r.draw(f)
        assertEquals(0xFFFF0000.toInt(), px(r, 0, 0))
        assertEquals(AsciiRaster.BACKGROUND, px(r, 1, 0))
    }

    @Test fun aLowerBlockFillsOnlyItsBottomPart() {
        val (r, f) = raster("\u2584")   // lower half block
        f.next(); r.draw(f)
        assertEquals("the top of the cell is empty", AsciiRaster.BACKGROUND, px(r, 0, 0, y = 1))
        assertEquals("the bottom is ink", 0xFFFF0000.toInt(), px(r, 0, 0, y = ch - 2))
    }

    @Test fun onlyChangedCellsArePainted() {
        val (r, f) = raster("\u2588\u2588", "\u2588 ")
        f.next(); r.draw(f)
        val before = px(r, 0, 0)
        f.next(); r.draw(f)
        assertEquals("the unchanged cell keeps its pixels", before, px(r, 0, 0))
        assertEquals("the cleared cell is background again", AsciiRaster.BACKGROUND, px(r, 1, 0))
        assertNotEquals(AsciiRaster.BACKGROUND, px(r, 0, 0))
    }
}
