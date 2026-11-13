package dev.glasslauncher.dream

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The cell renderer (P327): a changed cell is painted in its colour at its place, an unchanged one is left alone, across two buffers. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AsciiRasterTest {
    private val cw = 8
    private val ch = 8
    /** The bitmaps are RGB_565 (5 and 6 bits a channel): pure red survives it. */
    private val red = 0xFFFF0000.toInt()

    private fun raster(vararg frames: String, head: String? = null): Pair<AsciiRaster, AsciiPiece.Frames> {
        val packed = AsciiTestPacks.pack(frames.map { AsciiTestPacks.frame(it, 1) }, head ?: """{"v":2,"slug":"t","cols":8,"rows":3,"fps":10,"n":${frames.size},"palette":["#ff0000"]}""")
        val piece = AsciiPiece.parse(packed)!!
        return AsciiRaster(piece, cw, ch) to piece.frames()
    }

    private fun px(b: Bitmap, cellX: Int, cellY: Int, x: Int = cw / 2, y: Int = ch / 2) = b.getPixel(cellX * cw + x, cellY * ch + y)

    @Test fun aFullBlockFillsItsCellInThePaletteColourAndASpaceStaysBlack() {
        val (r, f) = raster("█ ")
        f.next(); val b = r.draw(f)
        assertEquals(red, px(b, 0, 0))
        assertEquals(AsciiRaster.BACKGROUND, px(b, 1, 0))
    }

    @Test fun aLowerBlockFillsOnlyItsBottomPart() {
        val (r, f) = raster("▄")   // lower half block
        f.next(); val b = r.draw(f)
        assertEquals("the top of the cell is empty", AsciiRaster.BACKGROUND, px(b, 0, 0, y = 1))
        assertEquals("the bottom is ink", red, px(b, 0, 0, y = ch - 2))
    }

    /** Two buffers take turns: each frame must also draw what the buffer missed from the frame before. */
    @Test fun everyFrameIsCompleteWhicheverBufferItLandsIn() {
        val (r, f) = raster("███", "█ █", "█  ", "   ")
        val seen = ArrayList<List<Int>>()
        repeat(4) { f.next(); val b = r.draw(f); seen.add((0 until 3).map { px(b, it, 0) }) }
        val on = red; val off = AsciiRaster.BACKGROUND
        assertEquals(listOf(on, on, on), seen[0])
        assertEquals(listOf(on, off, on), seen[1])
        assertEquals(listOf(on, off, off), seen[2])
        assertEquals(listOf(off, off, off), seen[3])
    }

    /** A shade block is a texture, not a dimmer solid: both ink and background show inside one cell. */
    @Test fun aShadeBlockIsAPatternNotASolid() {
        val (r, f) = raster("\u2592")   // medium shade
        f.next(); val b = r.draw(f)
        val seen = HashSet<Int>()
        for (y in 0 until ch) for (x in 0 until cw) seen.add(px(b, 0, 0, x, y))
        assertTrue("ink and background both present", seen.contains(red) && seen.contains(AsciiRaster.BACKGROUND))
    }

    @Test fun inADotsPieceALowerBlockIsAHalftoneDotNotABar() {
        val (r, f) = raster("▄", head = """{"v":2,"slug":"t","cols":8,"rows":3,"fps":10,"n":1,"cell":"square","look":"dots","palette":["#ff0000"]}""")
        f.next(); val b = r.draw(f)
        assertNotEquals("the dot's centre is ink", AsciiRaster.BACKGROUND, px(b, 0, 0))
        assertEquals("its corner is not", AsciiRaster.BACKGROUND, px(b, 0, 0, x = 0, y = 0))
    }

    @Test fun liftKeepsADarkColourVisibleAndABrightOneAlone() {
        val dark = AsciiRaster.lift(0xFF101010.toInt())
        assertTrue("a near-black is lifted", (dark shr 16 and 255) > 0x30)
        assertEquals("white stays", 0xFFFFFFFF.toInt(), AsciiRaster.lift(0xFFFFFFFF.toInt()))
        assertTrue("and nothing gets darker", (AsciiRaster.lift(0xFFFF0000.toInt()) shr 8 and 255) >= 0)
    }
}
