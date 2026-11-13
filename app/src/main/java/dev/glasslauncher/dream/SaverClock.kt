package dev.glasslauncher.dream

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import dev.glasslauncher.data.ScreensaverConfig
import dev.glasslauncher.widgets.WallClock
import java.util.Date

/**
 * The one clock every screensaver shows (P338): the Aerials clock. Light Inter, "9:41" with a small "AM"/"PM" in 12-hour mode, flat text with
 * no pill or shadow, top right. It ticks once a minute and only while it is shown. It reads the app's [WallClock] (as the status pill does),
 * so a screenshot test can pin it.
 */
internal class SaverClock(context: Context, private val h24: Boolean = DateFormat.is24HourFormat(context)) : TextView(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 60_000 - WallClock.now() % 60_000 + 50) }
    }

    init {
        setTextColor(Color.argb(242, 255, 255, 255))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        letterSpacing = -0.01f
        val d = resources.displayMetrics.density
        setPadding((10 * d).toInt(), (4 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
        typeface = runCatching { androidx.core.content.res.ResourcesCompat.getFont(context, dev.glasslauncher.R.font.inter_medium) }.getOrNull()
            ?: android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        visibility = GONE
    }

    fun refresh() { text = AerialClock.text(Date(WallClock.now()), h24) }

    /** Shown (and ticking) or gone (and silent). */
    fun shown(on: Boolean) {
        handler.removeCallbacks(tick)
        visibility = if (on) VISIBLE else GONE
        if (on) tick.run()
    }

    companion object {
        /** Top right, where Aerials has always had it. */
        fun params(context: Context): FrameLayout.LayoutParams {
            val d = context.resources.displayMetrics.density
            return FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
                .apply { setMargins(0, (32 * d).toInt(), (46 * d).toInt(), 0) }
        }
    }
}

/** A screensaver [base] (one that draws no clock of its own) with the shared clock over it, if Show Clock is on. */
internal class ClockOverSaver(context: Context, private val base: View, cfg: ScreensaverConfig) : FrameLayout(context), Saver {
    private val clock = SaverClock(context)
    private val on = cfg.showClock

    init {
        addView(base, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(clock, SaverClock.params(context))
    }

    override fun start() { (base as? Saver)?.start(); clock.shown(on) }

    override fun stop() { clock.shown(false); (base as? Saver)?.stop() }
}
