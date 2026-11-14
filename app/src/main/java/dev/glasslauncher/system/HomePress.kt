package dev.glasslauncher.system

import android.app.Activity
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.ViewTreeObserver

/**
 * Home-press timing for the root tier (P342): how long Home takes from being resumed to its first frame, and whether the process had to
 * start (cold) or was already alive (warm). Logged only with `setprop debug.vitre.homepress 1`, so release does nothing.
 *
 *   adb logcat -s VitreHome
 *   VitreHome: resume->first frame 84 ms (warm, process age 412000 ms, oom_score_adj 600)
 *
 * The press itself happens before the app can see it, so the whole press-to-screen time is the system's `wm_activity_launch_time` in the
 * events buffer (`adb logcat -b events | grep wm_activity_launch_time`); this line is the part Home controls, and `cold` tells which
 * case that sample was.
 */
object HomePress {
    private var resumes = 0

    fun enabled(): Boolean = dev.glasslauncher.dream.viz.VizGlView.prop("debug.vitre.homepress")

    /** Called from MainActivity.onResume. One draw listener and one frame callback, only while the property is on. */
    fun resumed(activity: Activity) {
        val first = resumes++ == 0
        if (!enabled()) return
        val t0 = SystemClock.elapsedRealtime()
        val view = activity.window.decorView
        view.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            private var seen = false
            override fun onDraw() {
                if (seen) return
                seen = true
                view.post { view.viewTreeObserver.removeOnDrawListener(this) }
                Choreographer.getInstance().postFrameCallback {
                    val now = SystemClock.elapsedRealtime()
                    Log.i("VitreHome", "resume->first frame ${now - t0} ms (${if (first) "cold" else "warm"}, process age ${now - Process.getStartElapsedRealtime()} ms, oom_score_adj ${oomScoreAdj()})")
                }
            }
        })
        view.invalidate()
    }

    /** This process's oom_score_adj (what lmkd kills by: 600 is the Home app, 900 and up cached), or -1000 if unreadable. */
    fun oomScoreAdj(): Int = runCatching { java.io.File("/proc/${Process.myPid()}/oom_score_adj").readText().trim().toInt() }.getOrDefault(-1000)
}
