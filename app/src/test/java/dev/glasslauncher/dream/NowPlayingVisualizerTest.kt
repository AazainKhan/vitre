package dev.glasslauncher.dream

import android.app.Application
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** P326: the visualizer's bars follow the spectrum, it listens only while music plays, and a silent source is reported. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class NowPlayingVisualizerTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    private class FakeSource : SpectrumSource {
        var running = false; var starts = 0
        var push: ((ByteArray) -> Unit)? = null
        override fun start(onFft: (ByteArray) -> Unit): Boolean { running = true; starts++; push = onFft; return true }
        override fun stop() { running = false }
    }

    /** FFT bytes (real, imaginary per bin) with [magnitude] in the bins from [fromBin]. */
    private fun fft(bins: Int = 256, fromBin: Int = 0, magnitude: Int = 0) = ByteArray(bins * 2).also { for (k in fromBin until bins) it[2 * k] = magnitude.toByte() }

    private val controls = object : NowPlaying.Controls { override fun play() {}; override fun pause() {}; override fun next() {}; override fun previous() {} }
    private fun np(playing: Boolean) = NowPlaying(
        title = "T", artist = "A", album = null, packageName = "p", art = null, durationMs = 1000, positionMs = 0,
        positionAt = android.os.SystemClock.elapsedRealtime(), speed = 1f, playing = playing, controller = controls,
    )

    @After fun clear() { NowPlayingFixture.state = null }

    @Test fun barsRiseWithTheSignalAndFallSlowly() {
        val bars = SpectrumBars(count = 8)
        bars.update(fft(fromBin = 100, magnitude = 100))     // energy in the top of the spectrum only
        assertTrue("signal seen", bars.signal)
        assertTrue("the last bar is up", bars.level[7] > 0.5f)
        assertEquals("the first bar is not", 0f, bars.level[0], 0.001f)
        val up = bars.level[7]
        bars.update(fft())                                    // silence
        assertFalse(bars.signal)
        assertTrue("it falls, not at once", bars.level[7] in 0.1f..up * 0.9f)
    }

    @Test fun theLayerListensOnlyWhileRunAndReportsSilenceOnce() {
        val src = FakeSource()
        var told = 0
        val layer = VisualizerLayer(context, src) { told++ }
        layer.run(); assertTrue(src.running)
        src.push!!.invoke(fft()); shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("silence is not reported at once", 0, told)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(VisualizerLayer.SILENT_MS + 1000, TimeUnit.MILLISECONDS)
        src.push!!.invoke(fft()); shadowOf(android.os.Looper.getMainLooper()).idle()
        src.push!!.invoke(fft()); shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("after a few seconds of nothing, once", 1, told)
        layer.halt(); assertFalse("halted: the source is released", src.running)
    }

    @Test fun theSaverRunsTheVisualizerOnlyWhileMusicPlays() {
        val state = MutableStateFlow<NowPlaying?>(null)
        NowPlayingFixture.state = state
        val src = FakeSource()
        val saver = NowPlayingSaverView(context, ScreensaverConfig(visualizer = true), spectrum = src)
        saver.start(); shadowOf(android.os.Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        assertFalse("nothing plays: nothing listens", src.running)
        state.value = np(playing = true); shadowOf(android.os.Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        assertTrue("music: it listens", src.running)
        state.value = np(playing = false); shadowOf(android.os.Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        assertFalse("paused: it stops", src.running)
        state.value = np(playing = true); shadowOf(android.os.Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        saver.stop()
        assertFalse("the saver closed: it is released", src.running)
    }

    @Test fun withoutAudioAccessThereIsNoSource() {
        assertEquals(null, defaultSpectrum(context, ScreensaverConfig(visualizer = true)))     // RECORD_AUDIO is not granted here
        assertEquals(null, defaultSpectrum(context, ScreensaverConfig(visualizer = false)))
    }

    @Test fun shotOfTheSaverWithTheVisualizer() {
        val state = MutableStateFlow<NowPlaying?>(np(playing = true).copy(art = android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888).also {
            android.graphics.Canvas(it).apply {
                drawRect(0f, 0f, 256f, 256f, android.graphics.Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, 256f, 256f, 0xFF0F766E.toInt(), 0xFFBE185D.toInt(), android.graphics.Shader.TileMode.CLAMP) })
            }
        }, title = "Almost Full Moon", artist = "Enigma"))
        NowPlayingFixture.state = state
        val src = FakeSource()
        val saver = NowPlayingSaverView(context, ScreensaverConfig(visualizer = true), spectrum = src)
        saver.start(); shadowOf(android.os.Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        // A spectrum that falls away to the top, like most music: strong bass, quiet highs.
        val data = ByteArray(512).also { for (k in 1 until 256) { val m = (120 / (1 + k / 12f)).toInt(); it[2 * k] = m.toByte(); it[2 * k + 1] = (m / 2).toByte() } }
        repeat(3) { src.push!!.invoke(data); shadowOf(android.os.Looper.getMainLooper()).idle() }
        saver.measure(android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY))
        saver.layout(0, 0, 1920, 1080)
        val bmp = android.graphics.Bitmap.createBitmap(1920, 1080, android.graphics.Bitmap.Config.ARGB_8888)
        saver.draw(android.graphics.Canvas(bmp))
        bmp.captureRoboImage(shot("np-saver-visualizer"))
        saver.stop()
    }
}
