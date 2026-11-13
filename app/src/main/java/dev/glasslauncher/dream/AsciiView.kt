package dev.glasslauncher.dream

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import dev.glasslauncher.data.ScreensaverConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The ASCII screensaver (P327): one baked piece, its cells drawn by [AsciiRaster] at the piece's own fps and never faster. A piece that
 * is a single frame is drawn once and then nothing runs. With no piece chosen, or while its pack is being fetched, it says so.
 */
class AsciiView(context: Context, private val cfg: ScreensaverConfig) : View(context), Saver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var piece: AsciiPiece? = null
    @Volatile private var front: Bitmap? = null
    private var message: String? = "Loading…"
    private var started = false
    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
    }

    init { setBackgroundColor(Color.BLACK) }

    override fun start() {
        started = true
        val slug = cfg.asciiPiece
        if (slug == null) { message = "Choose a piece in Settings › Screen Saver › ASCII Art."; invalidate(); return }
        scope.launch {
            val p = AsciiPacks.load(context, slug)
            if (p == null) { message = "Could not load this piece. Check the network and try again."; invalidate(); return@launch }
            piece = p; message = null
            if (width > 0) play()
        }
    }

    override fun stop() {
        started = false
        scope.cancel()
        worker?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null; worker = null; front = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (started && piece != null && worker == null && w > 0) play()
    }

    /**
     * Decoding and drawing run on their own thread, so a heavy frame never holds up the UI thread: it draws one buffer while the
     * thread fills the other. The cell size fills the view without stretching (cells are 1:2 for text pieces, 1:1 for square ones).
     */
    private fun play() {
        val p = piece ?: return
        val h = p.head
        val cw = (if (h.cell == "square") minOf(width / h.cols, height / h.rows) else minOf(width / h.cols, height / (2 * h.rows))).coerceAtLeast(2)
        val t = HandlerThread("VitreAscii", android.os.Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
        thread = t
        val handler = Handler(t.looper)
        worker = handler
        handler.post {
            val frames = p.frames()
            val raster = AsciiRaster(p, cw, if (h.cell == "square") cw else 2 * cw)
            val still = h.loop == "still" || h.n == 1
            val frameMs = 1000L / h.fps
            var costSum = 0L; var costMax = 0L; var count = 0
            lateinit var tick: Runnable
            tick = Runnable {
                val t0 = SystemClock.uptimeMillis()
                if (!frames.next()) { post { message = "This piece is damaged."; invalidate() }; frames.close(); return@Runnable }
                val shown = raster.draw(frames)
                val cost = SystemClock.uptimeMillis() - t0
                costSum += cost; costMax = maxOf(costMax, cost)
                if (++count == LOG_EVERY) {
                    android.util.Log.i("VitreAscii", "${h.slug} ${h.cols}x${h.rows}@${h.fps}: decode+blit avg ${costSum * 10 / LOG_EVERY / 10f} ms, max $costMax ms over $LOG_EVERY frames (off the UI thread)")
                    costSum = 0; costMax = 0; count = 0
                }
                front = shown
                postInvalidate()
                // A still draws once and then nothing runs; anything else waits out the rest of its frame time.
                if (!still) handler.postDelayed(tick, (frameMs - (SystemClock.uptimeMillis() - t0)).coerceAtLeast(1))
            }
            tick.run()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val b = front
        if (b != null && message == null) canvas.drawBitmap(b, (width - b.width) / 2f, (height - b.height) / 2f, null)
        message?.let { canvas.drawText(it, width / 2f, height / 2f, text) }
    }

    private companion object { const val LOG_EVERY = 120 }
}
