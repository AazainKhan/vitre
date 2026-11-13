package dev.glasslauncher.dream

import android.app.Application
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.glasslauncher.data.ScreensaverConfig
import dev.glasslauncher.dream.viz.AudioAnalysis
import dev.glasslauncher.dream.viz.SpectrumData
import dev.glasslauncher.dream.viz.VisualizerStyle
import dev.glasslauncher.shots.shot
import dev.glasslauncher.widgets.NowPlaying
import dev.glasslauncher.widgets.NowPlayingFixture
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** P326 and P334: the audio analysis the shaders read, the layer's listen/release lifecycle and fallback, and the style setting. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class NowPlayingVisualizerTest {
    @get:org.junit.Rule val pinnedClock = dev.glasslauncher.shots.PinnedClockRule()
    private val context = ApplicationProvider.getApplicationContext<Application>()

    private class FakeSource : SpectrumSource {
        var running = false; var starts = 0
        var push: ((SpectrumData) -> Unit)? = null
        override fun start(onData: (SpectrumData) -> Unit): Boolean { running = true; starts++; push = onData; return true }
        override fun stop() { running = false }
    }

    /** FFT bytes (real, imaginary per bin) with [magnitude] in the bins from [fromBin]. */
    private fun fft(bins: Int = 512, fromBin: Int = 0, toBin: Int = bins, magnitude: Int = 0) = ByteArray(bins * 2).also { for (k in fromBin until toBin) it[2 * k] = magnitude.toByte() }
    private fun data(fft: ByteArray, wave: ByteArray? = null) = SpectrumData(fft, wave)
    private fun idle(ms: Long = 50) = shadowOf(android.os.Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)

    private val controls = object : NowPlaying.Controls { override fun play() {}; override fun pause() {}; override fun next() {}; override fun previous() {} }
    private fun np(playing: Boolean) = NowPlaying(
        title = "T", artist = "A", album = null, packageName = "p", art = null, durationMs = 200_000, positionMs = 50_000,
        positionAt = android.os.SystemClock.elapsedRealtime(), speed = 1f, playing = playing, controller = controls,
    )

    @After fun clear() { NowPlayingFixture.state = null }

    // --- the analysis the shaders read ---

    @Test fun bandsRiseWithTheSignalAndFallSlowly() {
        val a = AudioAnalysis()
        a.update(data(fft(fromBin = 300, magnitude = 100)))      // energy in the top of the spectrum only
        assertTrue("signal seen", a.signal)
        assertTrue("the highest band is up", a.spectrum[AudioAnalysis.BANDS - 1] > 0.5f)
        assertEquals("the lowest is not", 0f, a.spectrum[0], 0.001f)
        val up = a.spectrum[AudioAnalysis.BANDS - 1]
        a.update(data(fft()))                                     // silence
        assertFalse(a.signal)
        assertTrue("it falls, not at once", a.spectrum[AudioAnalysis.BANDS - 1] in 0.1f..up * 0.95f)
    }

    @Test fun peaksHoldAboveTheFallingBand() {
        val a = AudioAnalysis()
        a.update(data(fft(fromBin = 1, toBin = 6, magnitude = 120)))
        val top = a.peaks[0]
        repeat(5) { a.update(data(fft())) }
        assertTrue("the peak stays above the band that fell from it", a.peaks[0] > a.spectrum[0] && a.peaks[0] < top)
    }

    @Test fun aLoudBassHitAfterQuietIsABeat() {
        val a = AudioAnalysis()
        repeat(10) { a.update(data(fft(fromBin = 1, toBin = 8, magnitude = 6))) }
        assertTrue("steady quiet is no beat", a.beat < 0.5f)
        a.update(data(fft(fromBin = 1, toBin = 8, magnitude = 120)))
        assertEquals("a jump in the bass is", 1f, a.beat, 0.001f)
        repeat(8) { a.update(data(fft(fromBin = 1, toBin = 8, magnitude = 120))) }
        assertTrue("and it fades if the bass just stays loud", a.beat < 0.5f)
    }

    @Test fun theWaveformIsResampledAndTheTextureHasThreeRows() {
        val a = AudioAnalysis()
        val wave = ByteArray(1024) { if (it < 512) 0.toByte() else 255.toByte() }
        a.update(data(fft(fromBin = 1, toBin = 50, magnitude = 80), wave))
        val tex = a.texture()
        assertEquals(256 * 3, tex.size)
        assertEquals("row 1 is the waveform: first half low", 0, tex[256 + 10].toInt() and 255)
        assertEquals("second half high", 255, tex[256 + 200].toInt() and 255)
        assertTrue("row 0 is the spectrum", (tex[5].toInt() and 255) > 0)
    }

    // --- the layer ---

    @Test fun theGpuViewExistsOnlyWhileListeningAndIsRemovedWhenHalted() {
        val src = FakeSource(); val made = ArrayList<View>()
        val layer = VisualizerLayer(context, src, VisualizerStyle.Ribbons, glFactory = { c, _, _ -> View(c).also { made.add(it) } })
        assertEquals("nothing before it runs", 0, layer.childCount)
        layer.run(); assertTrue(src.running); assertTrue(layer.usingGpu); assertEquals(1, layer.childCount)
        layer.halt(); assertFalse("released", src.running); assertEquals("the GPU view is gone", 0, layer.childCount); assertFalse(layer.usingGpu)
        layer.run(); assertEquals("a fresh one the next time", 2, made.size)
        layer.halt()
    }

    @Test fun ifTheGpuViewCannotStartTheCanvasBarsTakeOver() {
        val src = FakeSource(); var fail: (() -> Unit)? = null
        val layer = VisualizerLayer(context, src, VisualizerStyle.Feedback, glFactory = { c, _, f -> fail = f; View(c) })
        layer.run(); assertTrue(layer.usingGpu)
        fail!!.invoke()
        assertFalse("a shader that did not build is dropped", layer.usingGpu)
        assertEquals("and the Canvas bars show instead", 1, layer.childCount)
        layer.halt()
        assertEquals(0, layer.childCount)
    }

    @Test fun withNoGpuTheCanvasBarsRunAndSilenceIsReportedOnce() {
        val src = FakeSource(); var told = 0
        val layer = VisualizerLayer(context, src, onSilent = { told++ }, glFactory = null)
        layer.run(); assertEquals(1, layer.childCount)
        src.push!!.invoke(data(fft())); idle()
        assertEquals("silence is not reported at once", 0, told)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(VisualizerLayer.SILENT_MS + 1000, TimeUnit.MILLISECONDS)
        src.push!!.invoke(data(fft())); idle()
        src.push!!.invoke(data(fft())); idle()
        assertEquals("after a few seconds of nothing, once", 1, told)
        layer.halt(); assertFalse(src.running)
    }

    @Test fun theSaverRunsTheVisualizerOnlyWhileMusicPlays() {
        val state = MutableStateFlow<NowPlaying?>(null)
        NowPlayingFixture.state = state
        val src = FakeSource()
        val saver = NowPlayingSaverView(context, ScreensaverConfig(visualizer = true), spectrum = src, glFactory = null)
        saver.start(); idle(1000)
        assertFalse("nothing plays: nothing listens", src.running)
        state.value = np(playing = true); idle(1000)
        assertTrue("music: it listens", src.running)
        state.value = np(playing = false); idle(1000)
        assertFalse("paused: it stops", src.running)
        state.value = np(playing = true); idle(1000)
        saver.stop()
        assertFalse("the saver closed: it is released", src.running)
    }

    @Test fun withoutAudioAccessThereIsNoSource() {
        assertNull(defaultSpectrum(context, ScreensaverConfig(visualizer = true)))     // RECORD_AUDIO is not granted here
        assertNull(defaultSpectrum(context, ScreensaverConfig(visualizer = false)))
    }

    // --- the style setting ---

    @Test fun theStyleDefaultsToBarsAndSurvivesTheConfigRoundTrip() {
        assertEquals(VisualizerStyle.Bars, ScreensaverConfig().visualizerStyle)
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        VisualizerStyle.entries.forEach { s ->
            assertEquals(s, json.decodeFromString(ScreensaverConfig.serializer(), json.encodeToString(ScreensaverConfig.serializer(), ScreensaverConfig(visualizerStyle = s))).visualizerStyle)
        }
        assertEquals("an old config with no style reads as Bars", VisualizerStyle.Bars, json.decodeFromString(ScreensaverConfig.serializer(), "{}").visualizerStyle)
        assertEquals(5, VisualizerStyle.entries.size)
    }

    // --- the picture (Canvas bars: Robolectric has no GL; the shaders were checked in WebGL and are checked on the stick) ---

    @Test fun shotOfTheSaverWithTheBars() {
        val state = MutableStateFlow<NowPlaying?>(np(playing = true).copy(speed = 0f, art = android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888).also {
            android.graphics.Canvas(it).apply {
                drawRect(0f, 0f, 256f, 256f, android.graphics.Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, 256f, 256f, 0xFF0F766E.toInt(), 0xFFBE185D.toInt(), android.graphics.Shader.TileMode.CLAMP) })
            }
        }, title = "Almost Full Moon", artist = "Enigma"))
        NowPlayingFixture.state = state
        dev.glasslauncher.shots.TvHarness.pinClockForTest()
        val src = FakeSource()
        val saver = NowPlayingSaverView(context, ScreensaverConfig(visualizer = true), spectrum = src, glFactory = null)
        saver.start(); idle(1000)
        // A spectrum that falls away to the top, like most music: strong bass, quiet highs.
        val d = ByteArray(1024).also { for (k in 1 until 512) { val m = (120 / (1 + k / 24f)).toInt(); it[2 * k] = m.toByte(); it[2 * k + 1] = (m / 2).toByte() } }
        repeat(3) { src.push!!.invoke(data(d)); idle() }
        saver.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        saver.layout(0, 0, 1920, 1080)
        val bmp = android.graphics.Bitmap.createBitmap(1920, 1080, android.graphics.Bitmap.Config.ARGB_8888)
        saver.draw(android.graphics.Canvas(bmp))
        bmp.captureRoboImage(shot("np-saver-visualizer"))
        saver.stop()
    }
}
