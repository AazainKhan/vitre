package dev.glasslauncher.dream.viz

import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/** One capture from the output mix: Visualizer-format FFT bytes and/or waveform bytes (either may be missing). */
class SpectrumData(val fft: ByteArray?, val wave: ByteArray?)

/**
 * What the shaders see of the music (P334), computed on the CPU as each capture arrives (about 20 a second): 256 log-spaced spectrum bands with a
 * fast rise and a slow fall, the held peak of each, the waveform resampled to 256 samples, and three band energies and a beat flash. [texture]
 * packs them as a 256 x 3 luminance image (spectrum, waveform, peaks) for the GPU. Pure, so it can be tested.
 */
class AudioAnalysis {
    val spectrum = FloatArray(BANDS)
    val peaks = FloatArray(BANDS)
    val wave = FloatArray(BANDS) { 0.5f }
    var bass = 0f; private set
    var mid = 0f; private set
    var high = 0f; private set
    var beat = 0f; private set
    /** Whether the last capture held any signal at all (silence, or an app that opts out of capture, is all zeros). */
    var signal = false; private set
    private var bassAverage = 0f
    private val bins = IntArray(BANDS + 1)
    private var binsFor = 0

    fun update(data: SpectrumData, fall: Float = 0.86f) {
        data.fft?.let { fft(it, fall) }
        data.wave?.let { waveform(it) }
        // light on the beat: fast up, easy down
        beat = max(beat * 0.86f, if (bass > bassAverage * 1.35f + 0.05f) 1f else 0f)
        bassAverage = bassAverage * 0.75f + bass * 0.25f
    }

    private fun fft(fft: ByteArray, fall: Float) {
        val n = fft.size / 2                 // bin k's real and imaginary parts are fft[2k] and fft[2k+1]; bin 0 holds DC and Nyquist
        if (n < 2) return
        if (binsFor != n) {
            for (i in 0..BANDS) bins[i] = exp(ln(n.toFloat()) * i / BANDS).toInt().coerceIn(1, n)
            for (i in 1..BANDS) if (bins[i] <= bins[i - 1]) bins[i] = minOf(bins[i - 1] + 1, n)
            binsFor = n
        }
        var any = false
        for (i in 0 until BANDS) {
            var peak = 0f
            for (k in bins[i] until max(bins[i + 1], bins[i] + 1).coerceAtMost(n)) peak = max(peak, hypot(fft[2 * k].toFloat(), fft[2 * k + 1].toFloat()))
            if (peak > 0f) any = true
            val v = sqrt((peak / 181f).coerceIn(0f, 1f))
            spectrum[i] = max(v, spectrum[i] * fall)
            peaks[i] = max(spectrum[i], peaks[i] - 0.012f)
        }
        signal = any
        bass = mean(0, 24); mid = mean(24, 130); high = mean(130, BANDS)
    }

    private fun waveform(w: ByteArray) {
        if (w.isEmpty()) return
        for (i in 0 until BANDS) wave[i] = ((w[(i * w.size / BANDS).coerceAtMost(w.size - 1)].toInt() and 255) / 255f)
    }

    private fun mean(from: Int, to: Int): Float { var s = 0f; for (i in from until to) s += spectrum[i]; return s / (to - from) }

    /** The 256 x 3 image for the GPU: row 0 the spectrum, row 1 the waveform, row 2 the peaks, one byte each. */
    fun texture(into: ByteArray = ByteArray(BANDS * 3)): ByteArray {
        for (i in 0 until BANDS) {
            into[i] = (spectrum[i] * 255f).toInt().coerceIn(0, 255).toByte()
            into[BANDS + i] = (wave[i] * 255f).toInt().coerceIn(0, 255).toByte()
            into[2 * BANDS + i] = (peaks[i] * 255f).toInt().coerceIn(0, 255).toByte()
        }
        return into
    }

    fun clear() { spectrum.fill(0f); peaks.fill(0f); wave.fill(0.5f); bass = 0f; mid = 0f; high = 0f; beat = 0f; signal = false; bassAverage = 0f }

    companion object { const val BANDS = 256 }
}
