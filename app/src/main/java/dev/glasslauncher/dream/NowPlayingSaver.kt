package dev.glasslauncher.dream

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import dev.glasslauncher.R
import dev.glasslauncher.app
import dev.glasslauncher.data.ScreensaverConfig
import dev.glasslauncher.widgets.NowPlaying
import dev.glasslauncher.widgets.NowPlayingFixture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private fun Context.dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
private fun Context.nowPlayingFlow(): StateFlow<NowPlaying?> = NowPlayingFixture.state ?: app.nowPlaying.state

/** The track's progress as a 0..1 fraction; 0 when the length is unknown. */
internal fun progressOf(np: NowPlaying, now: Long = SystemClock.elapsedRealtime()): Float =
    if (np.durationMs > 0) (np.positionNow(now) / np.durationMs.toFloat()).coerceIn(0f, 1f) else 0f

/**
 * A soft blur of [art] for the backdrop: the cover scaled down to a small grid, box-blurred there (two passes, so the result has no
 * edges to show), then scaled up smoothly. A blur of a small bitmap costs almost nothing and is made once per track.
 */
internal fun softBackdrop(art: Bitmap, grid: Int = 48, out: Int = 192, radius: Int = 5): Bitmap {
    val small = Bitmap.createScaledBitmap(art, grid, grid, true)
    val px = IntArray(grid * grid).also { small.getPixels(it, 0, grid, 0, 0, grid, grid) }
    val tmp = IntArray(grid * grid)
    repeat(2) { boxBlur(px, tmp, grid, radius, horizontal = true); boxBlur(tmp, px, grid, radius, horizontal = false) }
    val blurred = Bitmap.createBitmap(px, grid, grid, Bitmap.Config.ARGB_8888)
    return Bitmap.createScaledBitmap(blurred, out, out, true)
}

/** One pass of a box blur of [radius] along rows or columns, edges clamped. */
private fun boxBlur(src: IntArray, dst: IntArray, n: Int, radius: Int, horizontal: Boolean) {
    val window = 2 * radius + 1
    for (line in 0 until n) {
        fun at(i: Int) = src[if (horizontal) line * n + i.coerceIn(0, n - 1) else i.coerceIn(0, n - 1) * n + line]
        var r = 0; var g = 0; var b = 0
        for (i in -radius..radius) { val c = at(i); r += c shr 16 and 255; g += c shr 8 and 255; b += c and 255 }
        for (i in 0 until n) {
            dst[if (horizontal) line * n + i else i * n + line] = (255 shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
            val add = at(i + radius + 1); val drop = at(i - radius)
            r += (add shr 16 and 255) - (drop shr 16 and 255); g += (add shr 8 and 255) - (drop shr 8 and 255); b += (add and 255) - (drop and 255)
        }
    }
}

/** A thin progress line: two rounded bars, drawn only when the fraction changes. */
private class ProgressLine(context: Context) : View(context) {
    private val track = android.graphics.Paint().apply { color = Color.argb(60, 255, 255, 255) }
    private val fill = android.graphics.Paint().apply { color = Color.argb(230, 255, 255, 255) }
    var fraction = 0f
        set(v) { if (v != field) { field = v; invalidate() } }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val r = height / 2f
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, track)
        if (fraction > 0f) canvas.drawRoundRect(0f, 0f, width * fraction, height.toFloat(), r, r, fill)
    }
}

private fun rounded(context: Context, radiusDp: Float) = object : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, context.dp(radiusDp))
}

/**
 * The glass Now Playing card (P326): a small card that floats over the screensaver while music plays (a dark, translucent card with a
 * faint edge). It is not on screen when nothing plays or the music is paused, and nothing ticks then. It moves to another corner every
 * few minutes so no part of the picture sits in one place for hours.
 */
class NowPlayingCardView(context: Context) : FrameLayout(context), Saver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private val art = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; outlineProvider = rounded(context, 12f); clipToOutline = true }
    private val title = TextView(context).apply { setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); setTypeface(typeface, android.graphics.Typeface.BOLD); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val artist = TextView(context).apply { setTextColor(Color.argb(190, 255, 255, 255)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val progress = ProgressLine(context)
    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = context.dp(14f).toInt()
        setPadding(pad, pad, pad * 3 / 2, pad)
        background = GradientDrawable().apply { setColor(Color.argb(170, 16, 18, 24)); cornerRadius = context.dp(22f); setStroke(context.dp(1f).toInt(), Color.argb(50, 255, 255, 255)) }
        val side = context.dp(64f).toInt()
        addView(art, LinearLayout.LayoutParams(side, side))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(artist)
            addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(3f).toInt()).apply { topMargin = context.dp(8f).toInt() })
        }
        addView(texts, LinearLayout.LayoutParams(context.dp(260f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = pad })
        alpha = 0f
        visibility = GONE
    }
    private var np: NowPlaying? = null
    private var corner = 0
    private val tick = object : Runnable {
        override fun run() { np?.let { progress.fraction = progressOf(it) }; if (np?.playing == true) handler.postDelayed(this, 1000) }
    }
    private val move = object : Runnable { override fun run() { corner = (corner + 1) % 4; place(); handler.postDelayed(this, MOVE_MS) } }

    init { addView(card, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)); place() }

    private fun place() {
        val margin = context.dp(48f).toInt()
        val g = when (corner) { 0 -> Gravity.BOTTOM or Gravity.START; 1 -> Gravity.TOP or Gravity.END; 2 -> Gravity.BOTTOM or Gravity.END; else -> Gravity.TOP or Gravity.START }
        card.layoutParams = (card.layoutParams as LayoutParams).apply { gravity = g; setMargins(margin, margin, margin, margin) }
    }

    override fun start() {
        scope.launch { context.nowPlayingFlow().collect { show(it) } }
        handler.postDelayed(move, MOVE_MS)
    }

    override fun stop() {
        scope.cancel(); handler.removeCallbacksAndMessages(null)
    }

    private fun show(n: NowPlaying?) {
        val was = np
        np = n
        handler.removeCallbacks(tick)
        if (n == null || !n.playing) { card.animate().alpha(0f).setDuration(300).withEndAction { if (np?.playing != true) card.visibility = GONE }.start(); return }
        if (was?.key != n.key) {
            title.text = n.title; artist.text = n.artist.orEmpty()
            if (n.art != null) art.setImageBitmap(n.art) else art.setImageResource(R.drawable.ic_music_note)
        }
        progress.fraction = progressOf(n)
        card.visibility = VISIBLE
        card.animate().alpha(1f).setDuration(300).start()
        handler.postDelayed(tick, 1000)
    }

    private companion object { const val MOVE_MS = 5 * 60_000L }
}

/**
 * The full-screen Now Playing screensaver (P326): the cover large and centred, the track and artist under it, over a soft backdrop made
 * from the cover itself (a tiny copy, scaled up smoothly, dimmed). Paused music stays up, dimmer; with nothing playing it is a quiet clock.
 * It redraws on a track change and once a second for the progress line while playing, and not otherwise.
 */
class NowPlayingSaverView(context: Context, private val cfg: ScreensaverConfig) : FrameLayout(context), Saver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private val backdrop = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val dim = View(context).apply { setBackgroundColor(Color.argb(110, 0, 0, 0)) }
    private val art = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; outlineProvider = rounded(context, 28f); clipToOutline = true; elevation = context.dp(24f) }
    private val title = TextView(context).apply { setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f); setTypeface(typeface, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val artist = TextView(context).apply { setTextColor(Color.argb(200, 255, 255, 255)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f); gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val progress = ProgressLine(context)
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(art, LinearLayout.LayoutParams(context.dp(300f).toInt(), context.dp(300f).toInt()))
        addView(title, LinearLayout.LayoutParams(context.dp(640f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(26f).toInt() })
        addView(artist, LinearLayout.LayoutParams(context.dp(640f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(4f).toInt() })
        addView(progress, LinearLayout.LayoutParams(context.dp(420f).toInt(), context.dp(4f).toInt()).apply { topMargin = context.dp(22f).toInt() })
    }
    private val clock = TextClock(context).apply { setTextColor(Color.argb(150, 255, 255, 255)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 64f); format12Hour = "h:mm"; format24Hour = "H:mm"; gravity = Gravity.CENTER; visibility = GONE }
    private var np: NowPlaying? = null
    private val tick = object : Runnable {
        override fun run() { np?.let { progress.fraction = progressOf(it) }; if (np?.playing == true) handler.postDelayed(this, 1000) }
    }

    init {
        setBackgroundColor(Color.BLACK)
        addView(backdrop, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(dim, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(content, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(clock, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    override fun start() { scope.launch { context.nowPlayingFlow().collect { show(it) } } }

    override fun stop() { scope.cancel(); handler.removeCallbacksAndMessages(null) }

    private fun show(n: NowPlaying?) {
        val was = np
        np = n
        handler.removeCallbacks(tick)
        if (n == null) {
            content.visibility = GONE; backdrop.visibility = GONE; dim.visibility = GONE; clock.visibility = VISIBLE
            return
        }
        clock.visibility = GONE; content.visibility = VISIBLE; backdrop.visibility = VISIBLE; dim.visibility = VISIBLE
        if (was?.key != n.key || was.art !== n.art) {
            // A track change dissolves: the content fades out, is swapped, and fades back in.
            val swap = Runnable {
                title.text = n.title; artist.text = n.artist.orEmpty()
                if (n.art != null && !n.artIsIcon) {
                    art.setImageBitmap(n.art); art.scaleType = ImageView.ScaleType.CENTER_CROP
                    backdrop.setImageBitmap(softBackdrop(n.art))
                } else {
                    art.setImageResource(R.drawable.ic_music_note); art.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    backdrop.setImageDrawable(null)
                }
                content.animate().alpha(if (n.playing) 1f else 0.6f).setDuration(250).start()
            }
            if (was == null) { content.alpha = 0f; swap.run() } else content.animate().alpha(0f).setDuration(200).withEndAction(swap).start()
        } else content.animate().alpha(if (n.playing) 1f else 0.6f).setDuration(250).start()
        progress.fraction = progressOf(n)
        if (n.playing) handler.postDelayed(tick, 1000)
    }
}

/** A screensaver [base] with the glass card over it. */
class CardOverSaver(context: Context, private val base: View, private val card: NowPlayingCardView) : FrameLayout(context), Saver {
    init {
        addView(base, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(card, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun start() { (base as? Saver)?.start(); card.start() }

    override fun stop() { card.stop(); (base as? Saver)?.stop() }
}
