package dev.glasslauncher.dream

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.data.ScreensaverConfig
import dev.glasslauncher.widgets.NowPlaying
import dev.glasslauncher.widgets.NowPlayingFixture
import kotlinx.coroutines.flow.MutableStateFlow
import com.github.takahirom.roborazzi.captureRoboImage
import dev.glasslauncher.shots.shot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** P326: the glass card shows only while music plays; the full-screen saver shows the track, or a clock when nothing plays. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class NowPlayingSaverTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    private val controls = object : NowPlaying.Controls { override fun play() {}; override fun pause() {}; override fun next() {}; override fun previous() {} }

    private fun np(playing: Boolean, title: String = "Almost Full Moon") = NowPlaying(
        title = title, artist = "Enigma", album = null, packageName = "com.spotify.tv.android", art = null,
        durationMs = 200_000, positionMs = 50_000, positionAt = android.os.SystemClock.elapsedRealtime(), speed = 1f, playing = playing, controller = controls,
    )

    private fun texts(v: View): List<String> =
        if (v is TextView) listOf(v.text.toString()) else if (v is ViewGroup) (0 until v.childCount).flatMap { texts(v.getChildAt(it)) } else emptyList()

    private fun settle() = shadowOf(android.os.Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)

    @After fun clear() { NowPlayingFixture.state = null }

    @Test fun theCardShowsWhilePlayingAndHidesWhenPaused() {
        val state = MutableStateFlow<NowPlaying?>(np(playing = true))
        NowPlayingFixture.state = state
        val card = NowPlayingCardView(context)
        card.start(); settle()
        assertTrue("the track is on the card", texts(card).contains("Almost Full Moon"))
        assertEquals(View.VISIBLE, card.getChildAt(0).visibility)
        state.value = np(playing = false); settle()
        assertEquals("paused: the card goes", View.GONE, card.getChildAt(0).visibility)
        state.value = null; settle()
        assertEquals(View.GONE, card.getChildAt(0).visibility)
        card.stop()
    }

    @Test fun theFullScreenSaverShowsTheTrackAndAClockWhenNothingPlays() {
        val state = MutableStateFlow<NowPlaying?>(null)
        NowPlayingFixture.state = state
        val saver = NowPlayingSaverView(context, ScreensaverConfig())
        saver.start(); settle()
        val clock = (0 until saver.childCount).map { saver.getChildAt(it) }.filterIsInstance<android.widget.TextClock>().single()
        assertEquals("nothing playing: a quiet clock", View.VISIBLE, clock.visibility)
        state.value = np(playing = true); settle()
        assertTrue(texts(saver).contains("Almost Full Moon") && texts(saver).contains("Enigma"))
        assertEquals("playing: the clock is gone", View.GONE, clock.visibility)
        state.value = np(playing = true, title = "Next Song"); settle()
        assertTrue("a track change swaps the text", texts(saver).contains("Next Song"))
        saver.stop()
    }

    /** P326 review: a hard-edged cover (a bright disc on a dark field) must blur into a gradient, not stair-steps. */
    @Test fun theBackdropIsASmoothBlurOfTheCover() {
        val disc = android.graphics.Bitmap.createBitmap(512, 512, android.graphics.Bitmap.Config.ARGB_8888).also {
            android.graphics.Canvas(it).apply { drawColor(0xFF101020.toInt()); drawCircle(256f, 256f, 130f, android.graphics.Paint().apply { color = 0xFFFFE680.toInt() }) }
        }
        val blurred = softBackdrop(disc)
        val px = IntArray(blurred.width * blurred.height).also { blurred.getPixels(it, 0, blurred.width, 0, 0, blurred.width, blurred.height) }
        var worst = 0
        for (y in 0 until blurred.height) for (x in 0 until blurred.width - 1) {
            val a = px[y * blurred.width + x]; val b = px[y * blurred.width + x + 1]
            for (shift in intArrayOf(16, 8, 0)) worst = maxOf(worst, Math.abs((a shr shift and 255) - (b shr shift and 255)))
        }
        assertTrue("neighbouring pixels differ by at most a few levels (worst $worst)", worst <= 8)
    }

    @Test fun progressIsAFractionOfTheLength() {
        val n = np(playing = false)
        assertEquals(0.25f, progressOf(n), 0.001f)
        assertEquals(0f, progressOf(n.copy(durationMs = 0)), 0f)
    }

    /** A stand-in cover: a diagonal gradient with a disc, so the backdrop and the card have something to show. */
    private fun cover(): android.graphics.Bitmap = android.graphics.Bitmap.createBitmap(512, 512, android.graphics.Bitmap.Config.ARGB_8888).also { b ->
        val c = android.graphics.Canvas(b)
        c.drawRect(0f, 0f, 512f, 512f, android.graphics.Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, 512f, 512f, 0xFF1E3A8A.toInt(), 0xFFF97316.toInt(), android.graphics.Shader.TileMode.CLAMP) })
        c.drawCircle(300f, 220f, 120f, android.graphics.Paint().apply { color = 0xFFFDE68A.toInt() })
    }

    private fun capture(v: View, name: String) {
        v.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1920, 1080)
        val bmp = android.graphics.Bitmap.createBitmap(1920, 1080, android.graphics.Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        bmp.captureRoboImage(shot(name))
    }

    @Test fun shotsOfTheFullScreenSaverAndTheCardOverAPhoto() {
        NowPlayingFixture.state = MutableStateFlow(np(playing = true).copy(art = cover(), title = "Almost Full Moon", artist = "Enigma"))
        val saver = NowPlayingSaverView(context, ScreensaverConfig())
        saver.start(); settle()
        capture(saver, "np-saver-fullscreen")
        saver.stop()
        // The card over a stand-in photo of the sort the slideshow shows.
        val photo = android.widget.ImageView(context).apply { setImageBitmap(cover()); scaleType = android.widget.ImageView.ScaleType.CENTER_CROP }
        val over = CardOverSaver(context, photo, NowPlayingCardView(context))
        over.start(); settle()
        capture(over, "np-saver-card")
        over.stop()
    }
}
