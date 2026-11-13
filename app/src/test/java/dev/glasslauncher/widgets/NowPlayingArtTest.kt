package dev.glasslauncher.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingArtTest {
    private fun pick(art: String? = null, album: String? = null, icon: String? = null, artUri: String? = null, albumUri: String? = null, iconUri: String? = null) =
        ArtPick.pick(art, album, icon, artUri, albumUri, iconUri)

    // --- source order ---
    @Test fun aRealBitmapWinsWhenThereIsNoUri() {
        val p = pick(art = "A", album = "B", icon = "I")
        assertEquals("A", p.bitmap); assertNull(p.uri); assertFalse(p.isIcon)
        assertEquals("B", pick(album = "B", icon = "I").bitmap)
    }

    /** P324: Spotify TV left the previous track's bitmap in the metadata, so a bitmap first kept the old cover for good. */
    @Test fun anArtUriBeatsAStaleBitmapWhichIsOnlyTheFallback() {
        val p = pick(art = "OLD", icon = "I", artUri = "https://x/y.jpg")
        assertNull(p.bitmap); assertEquals("https://x/y.jpg", p.uri); assertEquals("OLD", p.fallback); assertFalse(p.fallbackIsIcon)
        assertTrue("the icon as fallback is still an icon", pick(icon = "I", artUri = "https://x/y.jpg").fallbackIsIcon)
    }

    @Test fun anArtUriBeatsTheIconBitmapWhichBecomesTheFallback() {
        val p = pick(icon = "LOGO", artUri = "https://i.scdn.co/image/abc")
        assertNull(p.bitmap); assertEquals("https://i.scdn.co/image/abc", p.uri); assertEquals("LOGO", p.fallback); assertFalse(p.isIcon)
        assertEquals("art uri before album art uri", "https://a/1", pick(artUri = "https://a/1", albumUri = "https://a/2").uri)
        assertEquals("https://a/2", pick(albumUri = "https://a/2").uri)
    }

    @Test fun theIconIsTheLastResort() {
        assertTrue(pick(icon = "LOGO").isIcon)
        assertEquals("LOGO", pick(icon = "LOGO", iconUri = "https://a/i").bitmap)
        val p = pick(iconUri = "https://a/i")
        assertEquals("https://a/i", p.uri); assertTrue(p.isIcon)
        assertTrue(pick().none)
    }

    @Test fun onlyHttpsAndContentUrisAreTrusted() {
        assertEquals("https://a/b.png", ArtPick.safeUri("  https://a/b.png "))
        assertEquals("content://x/y", ArtPick.safeUri("content://x/y"))
        for (bad in listOf("http://a/b", "file:///sdcard/x", "intent:#Intent;end", "javascript:1", "https://a/ b", "https://a/\u0000", "", "x".repeat(3000), "https://" + "x".repeat(3000)))
            assertNull(bad.take(20), ArtPick.safeUri(bad))
        assertNull(ArtPick.safeUri(null))
        assertTrue("a bad art uri falls through to the next", pick(artUri = "file:///x", albumUri = "https://a/2").uri == "https://a/2")
    }

    // --- cache ---
    @Test fun cacheKeepsTheLastFewAndEvictsTheOldest() {
        val c = ArtCache<ByteArray>(maxEntries = 3, maxBytes = 1_000) { it.size }
        for (k in "abcd") c.put("$k", ByteArray(100))
        assertNull(c.get("a")); assertEquals(3, c.size())
        c.get("b"); c.put("e", ByteArray(100)) // b was used: c goes, not b
        assertTrue(c.get("b") != null); assertNull(c.get("c"))
    }

    @Test fun cacheIsBoundedByBytesAndRefusesAnOversizedPicture() {
        val c = ArtCache<ByteArray>(maxEntries = 10, maxBytes = 250) { it.size }
        c.put("a", ByteArray(100)); c.put("b", ByteArray(100)); c.put("c", ByteArray(100))
        assertTrue(c.totalBytes() <= 250); assertNull(c.get("a"))
        c.put("huge", ByteArray(500)); assertNull(c.get("huge")); assertTrue(c.totalBytes() <= 250)
        c.put("b", ByteArray(50)); assertEquals("a re-put replaces, not adds", 150, c.totalBytes())
    }

    @Test fun repeatTrackIsACacheHit() {
        val c = ArtCache<String>(sizeOf = { it.length })
        c.put("https://i.scdn.co/image/1", "cover1")
        assertEquals("cover1", c.get("https://i.scdn.co/image/1"))
    }

    // --- decode size ---
    @Test fun decodeSampleKeepsTheArtAtLeastShownSize() {
        assertEquals(1, ArtFetcher.sampleSize(300, 300))
        assertEquals(1, ArtFetcher.sampleSize(640, 640)) // 640/2 = 320 < 512
        assertEquals(2, ArtFetcher.sampleSize(1200, 1200))
        assertEquals(4, ArtFetcher.sampleSize(3000, 2000))
        assertEquals(1, ArtFetcher.sampleSize(0, 0))
    }

    // --- crossfade state machine, fake clock ---
    @Test fun placeholderToArtFadesInWithTheOldOneAlwaysOpaque() {
        val b = ArtBlend<String>(durationMs = 230)
        assertTrue(b.retarget("cover", now = 1_000))
        assertEquals(0f, b.alpha(1_000), 0f)
        var last = 0f
        var mid = 0
        for (t in 1_000..1_300 step 16) {
            val a = b.alpha(t.toLong())
            assertTrue("never goes backwards ($a after $last)", a >= last)
            if (a in 0.05f..0.95f) mid++
            last = a
            assertNull("the base (placeholder) is drawn at full opacity throughout", b.shown)
            if (b.settle(t.toLong())) break
        }
        assertTrue("a real blend, not a cut: $mid frames between", mid >= 4)
        assertEquals(1f, b.alpha(1_230), 0f)
        assertEquals("cover", b.shown); assertFalse(b.fading); assertNull(b.incoming)
    }

    @Test fun sameArtDoesNotRestartTheBlend() {
        val b = ArtBlend<String>(initial = "a")
        assertFalse(b.retarget("a", 0))
        assertTrue(b.retarget("b", 0)); assertFalse(b.retarget("b", 50))
        assertFalse("not settled early", b.settle(100)); assertTrue(b.settle(230))
    }

    @Test fun aSecondArtMidBlendPromotesTheFirstAndStartsAgain() {
        val b = ArtBlend<String>(durationMs = 200)
        b.retarget("one", 0)
        assertTrue(b.retarget("two", 150))
        assertEquals("one", b.shown); assertEquals("two", b.incoming); assertEquals(0f, b.alpha(150), 0f)
        assertTrue(b.settle(350)); assertEquals("two", b.shown)
    }

    @Test fun artGoingBackToThePlaceholderFadesToo() {
        val b = ArtBlend<String>(initial = "cover")
        assertTrue(b.retarget(null, 0)); assertTrue(b.fading)
        assertTrue(b.settle(500)); assertNull(b.shown)
    }
}
