package dev.glasslauncher.dream

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/** Where the spectrum comes from: the output mix on a TV, a fake in tests. Calls [onFft] with Visualizer-format FFT bytes at most about 20 times a second. */
interface SpectrumSource {
    /** Starts; false if it can't (no permission, no effect on this device). */
    fun start(onFft: (ByteArray) -> Unit): Boolean
    fun stop()
}

/** The output mix (audio session 0) through android.media.audiofx.Visualizer. Needs RECORD_AUDIO and MODIFY_AUDIO_SETTINGS, granted. */
class OutputMixSpectrum : SpectrumSource {
    private var visualizer: android.media.audiofx.Visualizer? = null

    override fun start(onFft: (ByteArray) -> Unit): Boolean = runCatching {
        val v = android.media.audiofx.Visualizer(0)
        v.enabled = false
        v.captureSize = android.media.audiofx.Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
        v.setDataCaptureListener(object : android.media.audiofx.Visualizer.OnDataCaptureListener {
            override fun onWaveFormDataCapture(visualizer: android.media.audiofx.Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
            override fun onFftDataCapture(visualizer: android.media.audiofx.Visualizer?, fft: ByteArray?, samplingRate: Int) { if (fft != null) onFft(fft) }
        }, minOf(android.media.audiofx.Visualizer.getMaxCaptureRate(), 20_000), false, true)
        v.enabled = true
        visualizer = v
        true
    }.getOrDefault(false)

    override fun stop() {
        runCatching { visualizer?.enabled = false; visualizer?.release() }
        visualizer = null
    }
}

/** The spectrum as a few bars: log-spaced bins of Visualizer FFT data, a fast rise and a slow fall. Pure, so it can be tested. */
class SpectrumBars(val count: Int = 40) {
    val level = FloatArray(count)
    /** Whether the last data held any signal at all (a silent app, or an app that opts out of capture, gives all zeros). */
    var signal = false; private set

    fun update(fft: ByteArray, fall: Float = 0.82f) {
        val bins = fft.size / 2          // bin k's real and imaginary parts are fft[2k] and fft[2k+1]; bin 0 holds DC and Nyquist
        var any = false
        for (i in 0 until count) {
            val lo = exp(ln(1f) + ln(bins.toFloat()) * i / count).toInt().coerceIn(1, bins - 1)
            val hi = exp(ln(bins.toFloat()) * (i + 1) / count).toInt().coerceIn(lo + 1, bins)
            var peak = 0f
            for (k in lo until hi) peak = max(peak, hypot(fft[2 * k].toFloat(), fft[2 * k + 1].toFloat()))
            if (peak > 0f) any = true
            val v = sqrt((peak / 181f).coerceIn(0f, 1f))
            level[i] = max(v, level[i] * fall)
        }
        signal = any
    }

    fun clear() { level.fill(0f); signal = false }
}

/**
 * The visualizer layer of the Now Playing saver (P326): bars along the bottom, tinted by [tint], drawn only when new spectrum data arrives (about
 * 20 times a second) and only while music plays. If the source gives silence for [SILENT_MS] while music plays (an app that opts out of audio
 * capture does that), [onSilent] is called once so the saver can say so instead of showing flat bars.
 */
class VisualizerLayer(context: Context, private val source: SpectrumSource, private val onSilent: () -> Unit = {}) : View(context) {
    private val bars = SpectrumBars()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255) }
    private val rect = RectF()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var running = false
    private var quietSince = 0L
    private var told = false
    var tint: Int = Color.WHITE
        set(v) { field = v; paint.color = Color.argb(210, Color.red(v), Color.green(v), Color.blue(v)); invalidate() }

    /** Starts listening (while music plays). Returns false if the source can't start. */
    fun run(): Boolean {
        if (running) return true
        quietSince = SystemClock.uptimeMillis(); told = false
        running = source.start { fft -> main.post { onData(fft) } }
        return running
    }

    fun halt() {
        if (!running) return
        running = false
        source.stop()
        bars.clear()
        invalidate()
    }

    private fun onData(fft: ByteArray) {
        if (!running) return
        bars.update(fft)
        val now = SystemClock.uptimeMillis()
        if (bars.signal) quietSince = now
        else if (!told && now - quietSince > SILENT_MS) { told = true; onSilent() }
        invalidate()
    }

    override fun onDetachedFromWindow() { halt(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val n = bars.count
        val slot = width / n.toFloat()
        val gap = slot * 0.28f
        val maxH = height * 0.30f
        for (i in 0 until n) {
            val h = (bars.level[i] * maxH).coerceAtLeast(height * 0.004f)
            rect.set(i * slot + gap / 2, height - h - height * 0.04f, (i + 1) * slot - gap / 2, height - height * 0.04f)
            canvas.drawRoundRect(rect, slot * 0.18f, slot * 0.18f, paint)
        }
    }

    companion object { const val SILENT_MS = 6_000L }
}
