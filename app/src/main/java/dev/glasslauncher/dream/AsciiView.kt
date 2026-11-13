package dev.glasslauncher.dream

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
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
    private var frames: AsciiPiece.Frames? = null
    private var raster: AsciiRaster? = null
    private var message: String? = "Loading…"
    private var started = false
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
    }
    private val step = Runnable { advance() }
    // Frame cost, logged every [LOG_EVERY] frames (device measurement: `adb logcat -s VitreAscii`).
    private var costSum = 0L
    private var costMax = 0L
    private var costFrames = 0

    init { setBackgroundColor(Color.BLACK) }

    override fun start() {
        started = true
        val slug = cfg.asciiPiece
        if (slug == null) { message = "Choose a piece in Settings › Screen Saver › ASCII."; invalidate(); return }
        scope.launch {
            val p = AsciiPacks.load(context, slug)
            if (p == null) { message = "Could not load this piece. Check the network and try again."; invalidate(); return@launch }
            piece = p; frames = p.frames(); message = null
            build()
            advance()
        }
    }

    override fun stop() {
        started = false
        removeCallbacks(step)
        scope.cancel()
        frames?.close(); frames = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (piece != null && started) { removeCallbacks(step); build(); advance() }
    }

    /** The cell size that fills the view without stretching: cells are 1:2 for text pieces, 1:1 for square ones. */
    private fun build() {
        val p = piece ?: return
        if (width == 0 || height == 0) return
        val h = p.head
        val cw = if (h.cell == "square") minOf(width / h.cols, height / h.rows) else minOf(width / h.cols, height / (2 * h.rows))
        val w = cw.coerceAtLeast(2)
        raster?.bitmap?.recycle()
        raster = AsciiRaster(p, w, if (h.cell == "square") w else 2 * w)
        frames?.close(); frames = p.frames()
    }

    private fun advance() {
        val f = frames ?: return
        val r = raster ?: return
        val t0 = SystemClock.uptimeMillis()
        if (!f.next()) { message = "This piece is damaged."; invalidate(); return }
        r.draw(f)
        val cost = SystemClock.uptimeMillis() - t0
        costSum += cost; costMax = maxOf(costMax, cost)
        if (++costFrames == LOG_EVERY) {
            android.util.Log.i("VitreAscii", "${piece?.head?.slug} ${piece?.head?.cols}x${piece?.head?.rows}@${piece?.head?.fps}: decode+blit avg ${costSum * 10 / LOG_EVERY / 10f} ms, max $costMax ms over $LOG_EVERY frames")
            costSum = 0; costMax = 0; costFrames = 0
        }
        invalidate()
        // A still never draws again; anything else waits out the rest of its frame time.
        if (piece?.head?.loop == "still" || piece?.head?.n == 1) return
        postDelayed(step, (1000L / (piece?.head?.fps ?: 24) - (SystemClock.uptimeMillis() - t0)).coerceAtLeast(1))
    }

    override fun onDraw(canvas: Canvas) {
        val r = raster
        if (r != null && message == null) {
            canvas.drawBitmap(r.bitmap, (width - r.bitmap.width) / 2f, (height - r.bitmap.height) / 2f, null)
        }
        message?.let { canvas.drawText(it, width / 2f, height / 2f, text) }
    }

    private companion object { const val LOG_EVERY = 120 }
}
