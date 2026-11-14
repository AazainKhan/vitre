package dev.glasslauncher.system

// Ported to Kotlin from Free My TV's Watchdog.java (github.com/simbasjungles/free-my-tv, commit 0e33f16, MIT, Copyright (c) 2026
// Free My TV contributors; MIT text in licenses/free-my-tv-MIT.txt and NOTICE). Changed for VITRE's one process: the crash handler
// only writes a timestamp, and the revert runs in the separate ":rescue" process at the next start.

import android.app.Activity
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/**
 * The crash safety net for the Home switch (P341): VITRE as the only Home must never leave a TV stuck. After [LIMIT] crashes, native
 * crashes or hangs within [WINDOW_MS], the next start opens [RescueActivity], which puts the TV's own home screen back.
 * Cost: one small file write at a crash, one exit-reason query at a cold start, and only while the switch is on. Nothing at idle.
 */
object HomeWatchdog {
    const val WINDOW_MS = 5 * 60_000L
    const val LIMIT = 3

    /** Records a crash's time, then lets the previous handler (the system's) finish the process. Called first in GlassApp.onCreate. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { file(app).appendText("${System.currentTimeMillis()}\n") }
            previous?.uncaughtException(t, e)
        }
    }

    /** Pure rule (tested): how many of [times] fall in the window ending at [now]. */
    fun recent(times: List<Long>, now: Long): Int = times.count { it <= now && now - it < WINDOW_MS }

    /** True once the switch is on and the app has crashed or hung [LIMIT] times in the window. */
    fun tripped(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        if (!HomeSwitch.isOn(context) && !context.getSharedPreferences("root", 0).getBoolean("takeover", false)) return false
        return recent(crashTimes(context), now) >= LIMIT
    }

    /** Crash times: the system's record (it also has native crashes and hangs) on Android 11+, else the handler's own file. */
    private fun crashTimes(context: Context): List<Long> {
        if (Build.VERSION.SDK_INT >= 30) {
            val am = context.getSystemService(ActivityManager::class.java)
            val reasons = setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR)
            runCatching {
                return am.getHistoricalProcessExitReasons(context.packageName, 0, 10)
                    .filter { it.reason in reasons && it.processName == context.packageName }.map { it.timestamp }
            }
        }
        return runCatching { file(context).readLines().mapNotNull { it.trim().toLongOrNull() } }.getOrDefault(emptyList())
    }

    /** After the user undoes or retries, the count starts over. */
    fun clear(context: Context) { file(context).delete() }

    private fun file(context: Context) = File(context.filesDir, "home-crashes.txt")

    /** True in the ":rescue" process, where GlassApp must start nothing else. */
    fun isRescueProcess(): Boolean = Build.VERSION.SDK_INT >= 28 && android.app.Application.getProcessName().endsWith(":rescue")

    /** When [checkAtStart] saw the net trip (uptime ms), 0 if not: [rescueIfTripped] reads it with no I/O, and only in the seconds after start, so "Try VITRE again" is not undone. */
    @Volatile private var trippedAt = 0L

    /** Called off the main thread from GlassApp.onCreate (main process): opens the rescue screen when the net has tripped. */
    fun checkAtStart(context: Context) {
        if (!tripped(context)) return
        trippedAt = android.os.SystemClock.uptimeMillis()
        startRescue(context)
    }

    /** MainActivity.onResume: if the net tripped, make sure the rescue screen ends up in front (it may have started before Home drew). */
    fun rescueIfTripped(context: Context) { if (trippedAt != 0L && android.os.SystemClock.uptimeMillis() - trippedAt < 5_000) startRescue(context) }

    private fun startRescue(context: Context) =
        context.startActivity(Intent(context, RescueActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * The fallback screen, plain Views in their own process: whatever crashed VITRE (Compose, the glass renderer) cannot take this down.
 * It undoes the Home switch on its own at once, and keeps the undo button for when that couldn't finish (debugging off, no network
 * to the TV's own adb). "Try VITRE again" clears the count and goes Home.
 */
class RescueActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        fun label(text: String, size: Float, bold: Boolean = false) = TextView(this).apply {
            this.text = text; textSize = size; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val undo = Button(this).apply { text = "Restore the original home screen"; setOnClickListener { undo() } }
        val retry = Button(this).apply { text = "Try VITRE again"; setOnClickListener { HomeWatchdog.clear(this@RescueActivity); startActivity(HomeSetup.homeIntent(this@RescueActivity)); finish() } }
        status = label("VITRE crashed ${HomeWatchdog.LIMIT} times in 5 minutes. Putting the TV's own home screen back…", 18f)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(Color.BLACK)
            setPadding((64 * d).toInt(), (32 * d).toInt(), (64 * d).toInt(), (32 * d).toInt())
            addView(label("VITRE needs a rest", 28f, bold = true)); addView(status)
            addView(undo); addView(retry)
            undo.requestFocus()
        })
        undo()
    }

    private fun undo() {
        status.text = "Putting the TV's own home screen back…"
        val done = { o: SwitchOutcome -> runOnUiThread { status.text = o.message; if (o.ok) HomeWatchdog.clear(this) } }
        if (HomeSwitch.path(this) == HomeSwitch.Path.Root) {
            Thread({
                val r = kotlinx.coroutines.runBlocking { RootFeatures.setHomeTakeover(this@RescueActivity, false) }
                done(SwitchOutcome(r.ok, if (r.ok) "The original home screen is back." else "Couldn't restore it (${r.out.take(80)}). From a computer: ${HomeSetup.RESTORE_STOCK_COMMAND}"))
            }, "VitreRescue").start()
        } else HomeSwitch.run(this, restore = true, done = done)
    }
}
