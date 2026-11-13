package dev.glasslauncher.dream

import android.content.Context
import android.graphics.Color
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.glasslauncher.app
import dev.glasslauncher.data.ScreensaverConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Date

/**
 * Full-screen Aerial player shared by the system screensaver and the in-app preview: shuffles
 * Apple's aerial videos, caches them on disk for offline replay, and shows the location and time.
 */
@OptIn(UnstableApi::class)
class AerialView(context: Context, private val cfg: ScreensaverConfig, private val nearEndMs: Long = 0L) : FrameLayout(context), Saver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val surface = SurfaceView(context)
    private val fade = View(context).apply { setBackgroundColor(Color.BLACK) }
    // tvOS-style: a light clock (AM/PM small beside it in 12-hour mode) and a small caption, each with only a soft contact shadow.
    private val location = label(13f, alpha = 0.85f).apply { gravity = Gravity.START; letterSpacing = 0.01f }
    private val clock = SaverClock(context)
    private var player: ExoPlayer? = null
    private var videos: List<AerialVideo> = emptyList()

    init {
        setBackgroundColor(Color.BLACK)
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(fade, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // tvOS text is flat (no shadows): the labels read on a soft darkening at the top and bottom edges.
        addView(android.view.View(context).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(90, 0, 0, 0), Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(110, 0, 0, 0)),
            )
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(location, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(46), 0, 0, dp(36)) // text sits at 56/44 after the shadow padding
        })
        addView(clock, SaverClock.params(context))
        location.visibility = if (cfg.showLocation) View.VISIBLE else View.GONE
    }

    override fun start() {
        val app = context.app
        scope.launch {
            clock.shown(cfg.showClock)
            videos = AerialCatalog(context, app.http).videos().playable(cfg.hiddenAerials).shuffled()
            if (videos.isEmpty()) {
                location.text = "Connect to the internet to download Aerial videos"
                location.visibility = View.VISIBLE
                return@launch
            }
            val exo = ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(cacheFactory(context)))
                .setLoadControl(VideoBuffer.loadControl())
                .build()
            player = exo
            exo.setVideoSurfaceView(surface)
            exo.volume = 0f
            exo.repeatMode = Player.REPEAT_MODE_ALL
            exo.setMediaItems(videos.map { MediaItem.Builder().setUri(it.url(cfg.quality)).setMediaId(it.id).build() })
            exo.addListener(object : Player.Listener {
                // The next clip's first frame is on screen (still under black): fade it in.
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    fadingOut = false
                    updateLabel()
                    // Not every decoder reports a first frame per item: fade in anyway shortly after.
                    postDelayed({ if (fade.alpha > 0.99f) fadeIn() }, 500)
                }
                override fun onRenderedFirstFrame() { fadeIn() }
                // Device tests (AerialActivity.EXTRA_NEAR_END_MS): jump near the first clip's end, once.
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY && nearEndMs > 0 && !seekedNearEnd && exo.duration > nearEndMs) {
                        seekedNearEnd = true
                        exo.seekTo(exo.duration - nearEndMs)
                    }
                }
            })
            exo.prepare()
            exo.play()
            updateLabel()
            // Clips used to hard-cut to black when the next one had already started. Now the current one
            // dims to black over its last 1.4 s, the switch happens in the dark, and the next fades up from
            // its first rendered frame. The clip's end is watched four times a second.
            launch {
                while (true) {
                    delay(250)
                    val p = player ?: break
                    val left = p.duration - p.currentPosition
                    if (!fadingOut && p.duration > 0 && left in 1..FADE_OUT_MS) {
                        fadingOut = true
                        fade.animate().cancel()
                        fade.animate().alpha(1f).setDuration(left.coerceAtMost(FADE_OUT_MS)).setStartDelay(0).start()
                    }
                }
            }
        }
    }

    override fun stop() {
        clock.shown(false)
        scope.cancel()
        player?.release()
        player = null
    }

    private var fadingOut = false
    private var seekedNearEnd = false

    private fun updateLabel() {
        val id = player?.currentMediaItem?.mediaId
        location.text = videos.firstOrNull { it.id == id }?.label.orEmpty()
    }

    private fun fadeIn() {
        fade.animate().cancel()
        fade.alpha = 1f
        fade.animate().alpha(0f).setDuration(1400).setStartDelay(150).start()
    }

    private fun label(size: Float, alpha: Float) = TextView(context).apply {
        setTextColor(Color.argb((alpha * 255).toInt(), 255, 255, 255))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setPadding(dp(10), dp(4), dp(10), dp(8))
        typeface = runCatching { androidx.core.content.res.ResourcesCompat.getFont(context, dev.glasslauncher.R.font.inter_medium) }.getOrNull()
            ?: android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val FADE_OUT_MS = 1_400L
        private var cache: SimpleCache? = null

        @Synchronized
        private fun cacheFactory(context: Context): CacheDataSource.Factory {
            val c = cache ?: SimpleCache(
                File(context.cacheDir, "aerials"),
                LeastRecentlyUsedCacheEvictor(600L * 1024 * 1024),
                StandaloneDatabaseProvider(context),
            ).also { cache = it }
            return CacheDataSource.Factory()
                .setCache(c)
                .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true))
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        }
    }
}

/**
 * The Aerial clock's text: "9:41" in 24-hour mode as "9:41", in 12-hour mode "9:41" with a smaller "AM"/"PM" after it
 * (tvOS, capitals, no periods), following the system's 12/24-hour setting ([DateFormat.is24HourFormat]).
 */
object AerialClock {
    /**
     * The time part and, in 12-hour mode, the marker: always capital "AM" or "PM" with no periods (P84), whatever the
     * locale writes (en-CA says "a.m."/"p.m."). The 12/24-hour choice stays the system's.
     */
    fun parts(at: Date, is24: Boolean, locale: java.util.Locale = java.util.Locale.getDefault()): Pair<String, String?> =
        if (is24) java.text.SimpleDateFormat("H:mm", locale).format(at) to null
        else {
            val cal = java.util.Calendar.getInstance().apply { time = at }
            java.text.SimpleDateFormat("h:mm", locale).format(at) to (if (cal.get(java.util.Calendar.AM_PM) == java.util.Calendar.AM) "AM" else "PM")
        }

    /** The marker is drawn at this fraction of the time's size, beside it. */
    const val AMPM_SCALE = 0.55f

    fun text(at: Date, is24: Boolean, locale: java.util.Locale = java.util.Locale.getDefault()): CharSequence {
        val (time, ampm) = parts(at, is24, locale)
        if (ampm == null) return time
        val s = android.text.SpannableString("$time $ampm")
        s.setSpan(android.text.style.RelativeSizeSpan(AMPM_SCALE), time.length + 1, s.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }
}
