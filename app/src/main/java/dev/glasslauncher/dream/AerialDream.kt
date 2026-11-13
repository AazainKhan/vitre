package dev.glasslauncher.dream

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.service.dreams.DreamService
import android.view.KeyEvent
import android.view.WindowManager
import dev.glasslauncher.app

/** The view for the chosen screensaver: the photo slideshow, or Aerials. */
internal fun saverView(context: Context, slideshow: Boolean, nearEndMs: Long = 0L, ascii: Boolean = false, asciiSlug: String? = null): android.view.View {
    val full = context.app.config.config.value
    // Reduce Motion: the slideshow cross-fades without its slow pan and zoom.
    val reduceMotion = dev.glasslauncher.ui.UiPrefs.resolve(context, full).reduceMotion
    val cfg = full.screensaver.let { if (reduceMotion) it.copy(kenBurns = false) else it }
    return if (asciiSlug != null) AsciiView(context, cfg.copy(asciiPiece = asciiSlug)) else if (ascii) AsciiView(context, cfg) else if (slideshow) SlideshowView(context, cfg) else AerialView(context, cfg, nearEndMs)
}

private fun Context.asciiChosen() = app.config.config.value.screensaverMode == dev.glasslauncher.data.ScreensaverMode.Ascii

private fun Context.slideshowChosen() = app.config.config.value.screensaverMode == dev.glasslauncher.data.ScreensaverMode.Slideshow

class AerialDreamService : DreamService() {
    private var view: Saver? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true
        val v = saverView(this, slideshowChosen(), ascii = asciiChosen())
        setContentView(v)
        view = v as Saver
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        view?.start()
    }

    override fun onDreamingStopped() {
        view?.stop()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        view?.stop()
        view = null
        super.onDetachedFromWindow()
    }
}

/** Plays the screensaver on demand (preview, or the launcher's own idle fallback). Any key exits. */
class AerialActivity : Activity() {
    private var view: Saver? = null

    companion object {
        const val EXTRA_NEAR_END_MS = "dev.glasslauncher.extra.NEAR_END_MS"
        /** Which screensaver to show (a preview); without it, the one chosen in Settings. */
        const val EXTRA_SLIDESHOW = "dev.glasslauncher.extra.SLIDESHOW"
        /** Play this ASCII piece (a preview from the menu, or a device test: `am start … --es dev.glasslauncher.extra.ASCII <slug>`). */
        const val EXTRA_ASCII = "dev.glasslauncher.extra.ASCII"

        /** Starts the screensaver fading in over Home rather than cutting to black. */
        fun start(context: android.content.Context, slideshow: Boolean? = null, asciiSlug: String? = null) {
            val options = android.app.ActivityOptions.makeCustomAnimation(context, dev.glasslauncher.R.anim.glass_fade_in, dev.glasslauncher.R.anim.glass_hold)
            val intent = android.content.Intent(context, AerialActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            if (slideshow != null) intent.putExtra(EXTRA_SLIDESHOW, slideshow)
            if (asciiSlug != null) intent.putExtra(EXTRA_ASCII, asciiSlug)
            runCatching { context.startActivity(intent, options.toBundle()) }
        }
    }

    override fun finish() {
        super.finish()
        // Fade out to Home instead of the system's slide.
        overridePendingTransition(dev.glasslauncher.R.anim.glass_hold, dev.glasslauncher.R.anim.glass_fade_out)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Device tests: start the first clip this many ms before its end, so a clip change can be checked
        // without waiting minutes for one (`am start … --el dev.glasslauncher.extra.NEAR_END_MS 6000`).
        val nearEnd = intent.getLongExtra(EXTRA_NEAR_END_MS, 0L)
        val slideshow = if (intent.hasExtra(EXTRA_SLIDESHOW)) intent.getBooleanExtra(EXTRA_SLIDESHOW, false) else slideshowChosen()
        val v = saverView(this, slideshow, nearEnd, ascii = !intent.hasExtra(EXTRA_SLIDESHOW) && asciiChosen(), asciiSlug = intent.getStringExtra(EXTRA_ASCII))
        setContentView(v)
        view = v as Saver
    }

    private val sleepAfter = Runnable { finish() }

    override fun onStart() {
        super.onStart()
        view?.start()
        // Keep the screen on only until the TV's own sleep timeout, so Aerials never stop it sleeping; with no
        // timeout ("never": sleep_timeout <= 0) they run until a key is pressed.
        val timeout = runCatching { android.provider.Settings.Secure.getLong(contentResolver, "sleep_timeout") }.getOrNull()
        Screensaver.keepAwakeMs(timeout)?.let { window.decorView.postDelayed(sleepAfter, it) }
    }

    override fun onStop() {
        window.decorView.removeCallbacks(sleepAfter)
        view?.stop()
        super.onStop()
        finish()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // A key that wakes the screensaver also cancels a Sleep Timer that has shown its one-minute banner, even if
        // the accessibility key filter never saw it (injected keys, P83).
        dev.glasslauncher.system.SleepTimer.cancelOnInteraction(this)
        if (event.action == KeyEvent.ACTION_UP) finish()
        return true
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        dev.glasslauncher.system.SleepTimer.cancelOnInteraction(this)
    }
}

object Screensaver {
    /**
     * How long Aerials may keep the screen on, from the TV's sleep timeout in ms ([timeout], null when unreadable):
     * null means no limit (the timeout is <= 0, AOSP's "never"); a positive timeout is honoured as it is, with a
     * 5 minute floor (Fire's UI tops out at 8 h and that is the user's choice); unreadable falls back to 20 minutes.
     */
    fun keepAwakeMs(timeout: Long?): Long? = when {
        timeout == null -> 20 * 60_000L
        timeout <= 0L -> null
        else -> maxOf(timeout, 5 * 60_000L)
    }

    fun component(context: Context) = ComponentName(context, AerialDreamService::class.java)

    /**
     * The "always-on screensaver" setting (P80b), as pure decisions. The TV's sleep timeout is secure setting
     * `sleep_timeout` (ms; <= 0 is AOSP's "never"). Turning it on writes -1 and remembers what was there; turning it
     * off puts that back. Turning it on twice never overwrites the remembered value with -1.
     */
    object AlwaysOn {
        /** Used when nothing was remembered (the key was missing): Fire's own default, 20 minutes. */
        const val DEFAULT_MS = 20 * 60_000L

        fun isOn(timeout: Long?): Boolean = timeout != null && timeout <= 0L

        data class Step(val write: Long, val remember: Long?)

        /** [current]: the sleep timeout now (null when unreadable); [saved]: what an earlier turn-on remembered. */
        fun turnOn(current: Long?, saved: Long?): Step = Step(
            write = -1L,
            // Already never (turned on before, or set elsewhere): keep what was remembered, else a sane default.
            remember = if (isOn(current)) saved else current ?: saved,
        )

        /** What to write when turning it off. */
        fun turnOff(saved: Long?): Long = saved?.takeIf { it > 0L } ?: DEFAULT_MS
    }

    private const val PREFS = "screensaver"
    private const val SAVED = "sleepTimeoutBefore"

    fun sleepTimeout(context: Context): Long? =
        runCatching { Settings.Secure.getLong(context.contentResolver, "sleep_timeout") }.getOrNull()

    fun alwaysOn(context: Context): Boolean = AlwaysOn.isOn(sleepTimeout(context))

    /** Whether Glass can write the TV's sleep timeout here: its own secure-settings grant, or root. */
    fun canSetAlwaysOn(context: Context): Boolean = canWriteSecureSettings(context) || dev.glasslauncher.system.Root.known

    /** Turns always-on on or off. Returns whether the setting now reads as asked. */
    suspend fun setAlwaysOn(context: Context, on: Boolean): Boolean {
        val prefs = context.getSharedPreferences(PREFS, 0)
        val saved = if (prefs.contains(SAVED)) prefs.getLong(SAVED, 0L) else null
        val value: Long
        if (on) {
            val step = AlwaysOn.turnOn(sleepTimeout(context), saved)
            step.remember?.let { prefs.edit().putLong(SAVED, it).apply() }
            value = step.write
        } else {
            value = AlwaysOn.turnOff(saved)
            prefs.edit().remove(SAVED).apply()
        }
        val wrote = canWriteSecureSettings(context) && runCatching { Settings.Secure.putLong(context.contentResolver, "sleep_timeout", value) }.getOrDefault(false)
        if (!wrote && dev.glasslauncher.system.Root.available()) {
            dev.glasslauncher.system.Root.action(context, "Screensaver always on: $on (sleep_timeout $value)", "settings put secure sleep_timeout $value")
        }
        return alwaysOn(context) == on
    }

    fun canWriteSecureSettings(context: Context) =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun isSystemScreensaver(context: Context): Boolean =
        Settings.Secure.getString(context.contentResolver, "screensaver_components")
            ?.split(',')?.any { ComponentName.unflattenFromString(it) == component(context) } == true

    /** Needs WRITE_SECURE_SETTINGS (granted once over adb). Returns false if not permitted. */
    fun setAsSystemScreensaver(context: Context): Boolean = runCatching {
        val cr = context.contentResolver
        Settings.Secure.putString(cr, "screensaver_components", component(context).flattenToString())
        Settings.Secure.putInt(cr, "screensaver_enabled", 1)
        Settings.Secure.putInt(cr, "screensaver_activate_on_sleep", 0)
        true
    }.getOrDefault(false)

    const val GRANT_COMMAND = "adb shell pm grant dev.glasslauncher android.permission.WRITE_SECURE_SETTINGS"
}
