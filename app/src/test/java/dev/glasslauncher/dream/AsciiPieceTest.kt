package dev.glasslauncher.dream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pack format of scripts/ascii-bake.mjs (P327): a header, then XOR-delta cell planes in one raw deflate stream. */
class AsciiPieceTest {
    private val n = AsciiTestPacks.COLS * AsciiTestPacks.ROWS
    private fun frame(text: String, color: Int = 0) = AsciiTestPacks.frame(text, color)
    private fun pack(frames: List<ByteArray>, head: String? = null) = if (head == null) AsciiTestPacks.pack(frames) else AsciiTestPacks.pack(frames, head)

    @Test fun framesComeBackAndOnlyChangedCellsAreDirty() {
        val a = frame("ABCDEFGH" + "        ".repeat(2))
        val b = frame("ABCDEFGZ" + "        ".repeat(2), color = 1)
        val piece = AsciiPiece.parse(pack(listOf(a, b)))
        assertNotNull(piece)
        val f = piece!!.frames()
        assertTrue(f.next()); assertEquals("every cell is dirty on the first frame", n, f.dirtyCount)
        assertEquals('A', f.text[0]); assertEquals('H', f.text[7])
        assertTrue(f.next())
        assertEquals('Z', f.text[7]); assertEquals(1, f.color[7].toInt())
        // the second frame changes the Z cell's text and, as the colour plane is 1 for the whole frame, every cell's colour
        assertEquals(n, f.dirtyCount)
    }

    @Test fun aSecondFrameThatChangesOneCellHasOneDirtyCell() {
        val a = frame("ABCDEFGH" + "        ".repeat(2))
        val b = frame("ABCDEFGZ" + "        ".repeat(2))
        val f = AsciiPiece.parse(pack(listOf(a, b)))!!.frames()
        f.next(); f.next()
        assertEquals(1, f.dirtyCount); assertEquals(7, f.dirty[0]); assertEquals('Z', f.text[7])
    }

    @Test fun theStreamWrapsToTheFirstFrame() {
        val a = frame("ABCDEFGH" + "        ".repeat(2))
        val b = frame("ZZZZZZZZ" + "        ".repeat(2))
        val f = AsciiPiece.parse(pack(listOf(a, b)))!!.frames()
        f.next(); f.next()
        assertTrue(f.next())
        assertEquals("the loop is back at frame 0", 'A', f.text[0])
        assertEquals("by a small step: only the cells that differ, not the whole grid", 8, f.dirtyCount)
        assertTrue(f.next()); assertEquals("and goes on to frame 1", 'Z', f.text[0])
        assertEquals(8, f.dirtyCount)
        assertTrue(f.next()); assertEquals("then round again", 'A', f.text[0])
    }

    @Test fun aWideCharacterSurvivesTheTwoBytePlanes() {
        val f = AsciiPiece.parse(pack(listOf(frame("█⣿▓"))))!!.frames()
        f.next()
        assertEquals('█', f.text[0]); assertEquals('⣿', f.text[1]); assertEquals('▓', f.text[2])
    }

    @Test fun aTruncatedStreamIsRefusedNotCrashed() {
        val bytes = pack(listOf(frame("ABCDEFGH"), frame("ZZZZZZZZ")))
        val f = AsciiPiece.parse(bytes.copyOf(bytes.size - 8))!!.frames()
        var ok = true; repeat(3) { ok = ok && f.next() }
        assertFalse(ok)
    }

    @Test fun badPacksAreRefused() {
        assertNull(AsciiPiece.parse(ByteArray(4)))
        assertNull("wrong magic", AsciiPiece.parse(pack(listOf(frame("A"))).also { it[0] = 'X'.code.toByte() }))
        assertNull("too big a grid", AsciiPiece.parse(pack(listOf(frame("A")), """{"v":2,"slug":"t","cols":500,"rows":500,"fps":10,"n":1}""")))
        assertNull("too fast", AsciiPiece.parse(pack(listOf(frame("A")), """{"v":2,"slug":"t","cols":8,"rows":3,"fps":240,"n":1}""")))
        assertNull("unknown version", AsciiPiece.parse(pack(listOf(frame("A")), """{"v":3,"slug":"t","cols":8,"rows":3,"fps":10,"n":1}""")))
        assertNull("a header longer than the file", AsciiPiece.parse("ASC1".toByteArray() + byteArrayOf(0, 0, 100, 0) + ByteArray(10)))
    }
}
