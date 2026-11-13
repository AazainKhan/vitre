package dev.glasslauncher.dream

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import dev.glasslauncher.dream.viz.AudioAnalysis
import dev.glasslauncher.dream.viz.SpectrumData
import dev.glasslauncher.dream.viz.VisualizerStyle
import dev.glasslauncher.dream.viz.VizGlView

/** Makes the GPU view for a style (the third argument is called if it cannot start), or null for none. */
typealias GlFactory = (Context, VisualizerStyle, () -> Unit) -> View?

internal val RealGl: GlFactory = { c, s, fail -> VizGlView(c, s, fail) }

/** Where the audio comes from: the output mix on a TV, a fake in tests. Calls [onData] at most about 20 times a second. */
interface SpectrumSource {
    /** Starts; false if it can't (no permission, no effect on this device). */
    fun start(onData: (SpectrumData) -> Unit): Boolean
    fun stop()
}

/** The output mix (audio session 0) through android.media.audiofx.Visualizer. Needs RECORD_AUDIO and MODIFY_AUDIO_SETTINGS, granted. */
class OutputMixSpectrum : SpectrumSource {
    private var visualizer: android.media.audiofx.Visualizer? = null

    override fun start(onData: (SpectrumData) -> Unit): Boolean = runCatching {
        val v = android.media.audiofx.Visualizer(0)
        v.enabled = false
        v.captureSize = android.media.audiofx.Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
        var wave: ByteArray? = null
        v.setDataCaptureListener(object : android.media.audiofx.Visualizer.OnDataCaptureListener {
            override fun onWaveFormDataCapture(visualizer: android.media.audiofx.Visualizer?, waveform: ByteArray?, samplingRate: Int) { wave = waveform?.copyOf() }
            override fun onFftDataCapture(visualizer: android.media.audiofx.Visualizer?, fft: ByteArray?, samplingRate: Int) { if (fft != null) onData(SpectrumData(fft.copyOf(), wave)) }
        }, minOf(android.media.audiofx.Visualizer.getMaxCaptureRate(), 20_000), true, true)
        v.enabled = true
        visualizer = v
        true
    }.getOrDefault(false)

    override fun stop() {
        runCatching { visualizer?.enabled = false; visualizer?.release() }
        visualizer = null
    }
}

/** Canvas bars: the fallback if the GPU path can't start, and what runs where there is no GL (tests). Bold: tall, wide, with peak caps. */
internal class BarsView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    var analysis: AudioAnalysis? = null
    var tint: Int = Color.WHITE
        set(v) { field = v; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val a = analysis ?: return
        val n = 40
        val slot = width / n.toFloat()
        val gap = slot * 0.2f
        val base = height * 0.84f
        for (i in 0 until n) {
            val band = (i * AudioAnalysis.BANDS / n)
            val h = (a.spectrum[band] * height * 0.62f).coerceAtLeast(height * 0.012f)
            val l = i * slot + gap / 2; val r = (i + 1) * slot - gap / 2
            paint.color = Color.argb(150, Color.red(tint), Color.green(tint), Color.blue(tint))
            rect.set(l, base - h, r, base)
            canvas.drawRoundRect(rect, slot * 0.1f, slot * 0.1f, paint)
            paint.color = Color.argb(210, 255, 255, 255)
            val cap = base - a.peaks[band] * height * 0.62f - height * 0.014f
            rect.set(l, cap - height * 0.008f, r, cap + height * 0.008f)
            canvas.drawRect(rect, paint)
        }
    }
}

/**
 * The visualizer layer of the Now Playing saver (P334): the chosen [style] drawn on the GPU behind the cover, or Canvas bars if the GPU path can't
 * start. It listens only while music plays and exists on the GPU only then: [halt] releases the audio effect and removes the GL view, so a
 * paused or idle TV holds no GPU memory and draws no frames. If the source gives silence for [SILENT_MS] while music plays (an app that opts
 * out of audio capture does that), [onSilent] is called once so the saver can say so instead of showing flat bars.
 */
class VisualizerLayer(
    context: Context,
    private val source: SpectrumSource,
    private val style: VisualizerStyle = VisualizerStyle.Spectrum,
    private val onSilent: () -> Unit = {},
    /** The GPU view for a style, or null for none (tests, or a device without GLES 2). */
    private val glFactory: GlFactory? = RealGl,
) : FrameLayout(context) {
    private val analysis = AudioAnalysis()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var gl: View? = null
    private var canvasBars: BarsView? = null
    private var running = false
    private var quietSince = 0L
    private var told = false
    private var tintColor = Color.WHITE
    val usingGpu get() = gl != null

    /** The colour the cover lends the visualizer. */
    fun setTint(color: Int) {
        tintColor = color
        (gl as? VizGlView)?.tint(Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f)
        canvasBars?.tint = color
    }

    /** Starts listening (while music plays). Returns false if the source can't start. */
    fun run(): Boolean {
        if (running) return true
        quietSince = SystemClock.uptimeMillis(); told = false
        analysis.clear()
        show()
        running = source.start { data -> main.post { onData(data) } }
        if (!running) hide()
        return running
    }

    fun halt() {
        if (!running) return
        running = false
        source.stop()
        hide()
        analysis.clear()
    }

    private fun show() {
        val view = glFactory?.invoke(context, style) { fallBack() }
        if (view != null) {
            gl = view
            addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            (view as? VizGlView)?.let { it.tint(Color.red(tintColor) / 255f, Color.green(tintColor) / 255f, Color.blue(tintColor) / 255f); it.start() }
        } else fallBack()
    }

    /** The GPU path could not start (a shader did not build): Canvas bars instead. */
    private fun fallBack() {
        (gl as? VizGlView)?.stop(); gl?.let { removeView(it) }; gl = null
        if (canvasBars == null) canvasBars = BarsView(context).also { it.analysis = analysis; it.tint = tintColor; addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
    }

    private fun hide() {
        (gl as? VizGlView)?.stop(); gl?.let { removeView(it) }; gl = null
        canvasBars?.let { removeView(it) }; canvasBars = null
    }

    private fun onData(data: SpectrumData) {
        if (!running) return
        analysis.update(data)
        (gl as? VizGlView)?.push(analysis)
        canvasBars?.invalidate()
        val now = SystemClock.uptimeMillis()
        if (analysis.signal) quietSince = now
        else if (!told && now - quietSince > SILENT_MS) { told = true; onSilent() }
    }

    override fun onDetachedFromWindow() { halt(); super.onDetachedFromWindow() }

    companion object { const val SILENT_MS = 6_000L }
}
