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
    private fun np(playing: Boolean, sounding: Boolean = playing) = NowPlaying(
        title = "T", artist = "A", album = null, packageName = "p", art = null, durationMs = 200_000, positionMs = 50_000,
        positionAt = android.os.SystemClock.elapsedRealtime(), speed = 1f, playing = playing, controller = controls, sounding = sounding,
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
        val layer = VisualizerLayer(context, src, VisualizerStyle.Grid, glFactory = { c, _, _ -> View(c).also { made.add(it) } })
        assertEquals("nothing before it runs", 0, layer.childCount)
        layer.run(); assertTrue(src.running); assertTrue(layer.usingGpu); assertEquals(1, layer.childCount)
        layer.halt(); assertFalse("released", src.running); assertEquals("the GPU view is gone", 0, layer.childCount); assertFalse(layer.usingGpu)
        layer.run(); assertEquals("a fresh one the next time", 2, made.size)
        layer.halt()
    }

    @Test fun ifTheGpuViewCannotStartTheCanvasBarsTakeOver() {
        val src = FakeSource(); var fail: (() -> Unit)? = null
        val layer = VisualizerLayer(context, src, VisualizerStyle.Warp, glFactory = { c, _, f -> fail = f; View(c) })
        layer.run(); assertTrue(layer.usingGpu)
        fail!!.invoke()
        assertFalse("a shader that did not build is dropped", layer.usingGpu)
        assertEquals("and the Canvas bars show instead", 1, layer.childCount)
        layer.halt()
        assertEquals(0, layer.childCount)
    }

    @Test fun aCaptureThatGoesSilentWhileMusicPlaysIsCreatedAgain() {
        val src = FakeSource()
        val layer = VisualizerLayer(context, src, glFactory = null)
        layer.run(); assertEquals(1, src.starts)
        // sound: nothing to fix
        src.push!!.invoke(data(fft(fromBin = 0, toBin = 64, magnitude = 90))); idle(1000)
        assertEquals("sound keeps the capture as it is", 1, src.starts)
        // then the capture keeps answering with zeros (what the stick did about 20 s in)
        repeat(8) { src.push!!.invoke(data(fft())); idle(500) }
        assertTrue("all-zero for over 1.5 s: the Visualizer is created again", src.starts >= 2)
        assertTrue("but not in a storm: at most every 3 s", src.starts <= 3)
        src.push!!.invoke(data(fft(fromBin = 0, toBin = 64, magnitude = 90))); idle(100)
        layer.halt()
    }

    @Test fun nearSilenceIsNotSoundAndAPauseIsNeverRestarted() {
        val src = FakeSource()
        val layer = VisualizerLayer(context, src, glFactory = null)
        layer.run()
        // one-LSB noise (the stick's capture while its audio path was re-routed): not sound, so the watchdog still acts
        repeat(8) { src.push!!.invoke(data(fft(fromBin = 0, toBin = 64, magnitude = 1))); idle(500) }
        assertTrue("noise below the sound level is treated as silence", src.starts >= 2)
        // a pause: the layer is told, and a silent capture is left alone however long it lasts
        val before = src.starts
        layer.sounding = false
        repeat(12) { src.push!!.invoke(data(fft())); idle(500) }
        assertEquals("never restarted while nothing is playing", before, src.starts)
        layer.halt()
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
        assertTrue("a blink in the session's state does not tear it down (P336)", src.running)
        state.value = np(playing = true); idle(1000)
        assertTrue(src.running)
        state.value = np(playing = false, sounding = true); idle(5000)
        assertTrue("buffering between tracks still shows the visualizer", src.running)
        state.value = np(playing = false); idle(2500)
        assertFalse("paused for good: it stops", src.running)
        state.value = np(playing = true); idle(1000)
        saver.stop()
        assertFalse("the saver closed: it is released", src.running)
    }

    @Test fun withoutAudioAccessThereIsNoSource() {
        assertNull(defaultSpectrum(context, ScreensaverConfig(visualizer = true)))     // RECORD_AUDIO is not granted here
        assertNull(defaultSpectrum(context, ScreensaverConfig(visualizer = false)))
    }

    // --- the style setting ---

    @Test fun theStyleDefaultsToSpectrumAndSurvivesTheConfigRoundTrip() {
        assertEquals(VisualizerStyle.Spectrum, ScreensaverConfig().visualizerStyle)
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        VisualizerStyle.entries.forEach { s ->
            assertEquals(s, json.decodeFromString(ScreensaverConfig.serializer(), json.encodeToString(ScreensaverConfig.serializer(), ScreensaverConfig(visualizerStyle = s))).visualizerStyle)
        }
        assertEquals("an old config with no style reads as the default", VisualizerStyle.Spectrum, json.decodeFromString(ScreensaverConfig.serializer(), "{}").visualizerStyle)
        // configs saved when there were five styles, under their first names, still read
        for ((old, now) in mapOf("Bars" to VisualizerStyle.Spectrum, "BarsWaves" to VisualizerStyle.Tunnel, "Feedback" to VisualizerStyle.Warp, "Ribbons" to VisualizerStyle.Ribbons, "Scope" to VisualizerStyle.Scope)) {
            assertEquals(now, json.decodeFromString(ScreensaverConfig.serializer(), """{"visualizerStyle":"$old"}""").visualizerStyle)
        }
        assertEquals(6, VisualizerStyle.entries.size)
    }

    // --- the layout: the visualizer owns the screen ---

    private fun visibleTexts(v: View): List<String> =
        if (v.visibility == View.GONE) emptyList() else if (v is android.widget.TextView) listOf(v.text.toString()) else if (v is android.view.ViewGroup) (0 until v.childCount).flatMap { visibleTexts(v.getChildAt(it)) } else emptyList()

    /** The user: a centred cover and seek bar over the visualizer "makes no sense". With the visualizer on there is only a corner card, and it leaves. */
    @Test fun theVisualizerOwnsTheScreenAndTheTrackIsACornerCardThatFadesAway() {
        val state = MutableStateFlow<NowPlaying?>(np(playing = true))
        NowPlayingFixture.state = state
        val src = FakeSource()
        val saver = NowPlayingSaverView(context, ScreensaverConfig(visualizer = true), spectrum = src, glFactory = null)
        saver.start(); idle(1000)
        assertTrue("the track shows in the corner card at first (${visibleTexts(saver)})", visibleTexts(saver).contains("T"))
        // nothing of the centred layout: the elapsed and remaining times belong to it
        assertTrue("no seek bar times in the middle", visibleTexts(saver).none { it == "0:50" || it == "-2:30" })
        idle(7_000)
        assertFalse("after a few seconds the card has gone (${visibleTexts(saver)})", visibleTexts(saver).contains("T"))
        state.value = np(playing = true).copy(positionMs = 60_000); idle(500)
        assertFalse("an update of the same track does not bring it back", visibleTexts(saver).contains("T"))
        state.value = np(playing = true).copy(title = "Next Song"); idle(1000)
        assertTrue("a new track does", visibleTexts(saver).contains("Next Song"))
        saver.stop()
    }

    /** Yin's stick check: a new track's card showed the note glyph and kept it, because the cover arrives after the track and was never swapped in. */
    @Test fun theCardTakesTheCoverWhenItArrivesAfterTheTrack() {
        val state = MutableStateFlow<NowPlaying?>(np(playing = true))
        NowPlayingFixture.state = state
        val card = NowPlayingCardView(context, autoHideMs = 6_000L)
        card.start(); idle(500)
        fun cover() = ((card.getChildAt(0) as android.view.ViewGroup).getChildAt(0) as android.widget.ImageView).drawable
        val before = cover()
        state.value = np(playing = true).copy(art = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888))
        idle(500)
        assertTrue("the cover that arrives for the same track is shown (${before} -> ${cover()})", cover() !== before && cover() is android.graphics.drawable.BitmapDrawable)
        card.stop()
    }

    @Test fun withoutTheVisualizerTheCentredLayoutIsUnchanged() {
        NowPlayingFixture.state = MutableStateFlow<NowPlaying?>(np(playing = false))
        val saver = NowPlayingSaverView(context, ScreensaverConfig())
        saver.start(); idle(1000)
        assertTrue("the times are there", visibleTexts(saver).contains("0:50") && visibleTexts(saver).contains("-2:30"))
        saver.stop()
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
