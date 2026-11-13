package dev.glasslauncher.system

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import dev.glasslauncher.MainActivity
import dev.glasslauncher.app
import kotlinx.coroutines.launch

/**
 * Remote buttons (Settings › Remote Buttons): runs the action picked for each button in
 * RemoteButtons, e.g. an app button opens any app, Recent Apps opens Glass's app switcher. Every other
 * key passes straight through. It also snapshots the app in front for the app switcher's blurred card
 * previews (AppPreviews); screen text and window content are never read.
 */
class RemoteKeysService : AccessibilityService() {

    /** Control Center as an overlay over any app (and over Home). */
    val controlCenter by lazy { dev.glasslauncher.home.ControlCenterWindow(this) }

    override fun onServiceConnected() {
        instance = this
        // A bound keys service is a second way to have the always-on remote scan armed (P100): a Home that was force-
        // stopped cancels the scan's PendingIntent, and nothing delivers to a stopped app until it starts again.
        if (RootFeatures.ownRemotePairing(this)) runCatching { RemotePairer.obtain(this).start() }
        // Some builds drop the XML flag; asking again at runtime turns key filtering on.
        serviceInfo = serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        Log.i(TAG, "connected, flags=${serviceInfo.flags} caps=${serviceInfo.capabilities}")
        // Control Center is drawn in Home's window whenever Home is in front (P307), so the parked accessibility window
        // is a DEVICE layer for nothing. warmUp() still runs for the over-another-app case, then it is dropped again
        // once Home is confirmed in front - otherwise every service (re)connect re-attached it and the layer came back.
        runCatching { controlCenter.warmUp() }
        runCatching {
            // front is only known from window-state events, so read the active window instead of trusting it here.
            val active = rootInActiveWindow?.packageName?.toString()
            if (active == null || active == packageName) controlCenter.detach()
        }
    }

    // ── Notices (P229): one glass card at the top left, over any app (an accessibility overlay: never takes focus or touches) ─────────────

    private val notices by lazy {
        dev.glasslauncher.system.NoticeHost(this, dev.glasslauncher.system.WindowSurface(this, android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY), TAG)
    }

    fun showBanner(text: String, forMs: Long, actionable: Boolean = false) = showNotice(Notice(text, forMs, actionable))

    fun showNotice(notice: Notice) = notices.show(notice)

    /** Replaces whatever is up and queued (the sleep warning is answered by a key: "Sleep timer cancelled" takes its place at once). */
    fun showBannerNow(text: String, forMs: Long) = notices.showNow(text, forMs)

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // A key from anything but the remote (CEC, a phone app, a controller) says the user is at the TV: the
        // fresh-batteries hint is allowed for a while after it (P98), never on a plain idle Home.
        if (event.action == KeyEvent.ACTION_DOWN) RemoteWatch.noteKey(event.device?.name)
        // Debug builds log every key that reaches the service: the table of which remote keys an accessibility service sees (docs/research/remote-keys-plain-apk.md).
        if (dev.glasslauncher.BuildConfig.DEBUG && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            Log.i(TAG, "key ${KeyEvent.keyCodeToString(event.keyCode)} scan=${event.scanCode} device=${event.device?.name} front=$front")
        }
        // The Sleep Timer's one-minute banner is up: any key cancels the timer (the key still reaches the app).
        if (event.action == KeyEvent.ACTION_DOWN && SleepTimer.warning(this)) {
            SleepTimer.set(this, 0)
            showBannerNow("Sleep timer cancelled", 2_000)
        }
        // "You can always back out" (P87): Back or Home while the app Glass just started hasn't drawn a window yet
        // cancels it and returns Home. The gate closes the moment any app window appears, so Back inside an app is
        // never taken. Both halves of the press are swallowed.
        if ((event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_HOME) && app.config.config.value.instantBack) {
            val now = android.os.SystemClock.uptimeMillis()
            // The system holds Back until a window of the app has INPUT FOCUS (a starting window and a resumed
            // activity both come earlier). So the app owns Back only once a focused application window of another
            // package exists; until then Back is Glass's to take (P87).
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && LaunchGate.interruptible(now) &&
                LaunchGate.shouldInterrupt(now, appHasFocus())) {
                Log.i(TAG, "instant back: ${LaunchGate.launching(now)} had no focused window yet")
                LaunchGate.clear()
                interrupted = true
                performGlobalAction(GLOBAL_ACTION_HOME)
                return true
            }
            if (interrupted && event.action == KeyEvent.ACTION_UP) { interrupted = false; return true }
            if (interrupted) return true
        }
        // Back in an app may be the app closing (Fire OS never passes Home to accessibility services; the close uses the
        // app switcher's preview then): keep its last frame for Glass's close (P147), as tvOS shrinks a
        // snapshot back into the icon. In memory only, small, and dropped once the close has played.
        if ((event.keyCode == KeyEvent.KEYCODE_HOME || event.keyCode == KeyEvent.KEYCODE_BACK) &&
            event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            front?.let { pkg -> snapshotForClose(pkg) }
        }
        // Escape hatch: holding Back for 1.5 s goes Home from any app, even one that swallows Back, Home
        // and Recents (Fire TV Early Access did). The press itself still reaches the app. Timed from the
        // first down, not from key repeats: the system makes those after accessibility services have seen
        // the key, so a held Back reaches this service as just a down and, later, an up.
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) {
                escaped = false
                handler.removeCallbacks(escape)
                handler.postDelayed(escape, ESCAPE_HOLD_MS)
            }
            if (escaped) return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            handler.removeCallbacks(escape)
            val was = escaped
            escaped = false
            if (was) return true
        }
        if (handleCcShortcut(event)) return true
        val name = KeyEvent.keyCodeToString(event.keyCode)
        val action = RemoteButtons.actionFor(name, app.config.config.value.remoteButtons, event.scanCode, event.device?.name) ?: return false
        if (dev.glasslauncher.BuildConfig.DEBUG && event.action == KeyEvent.ACTION_DOWN) Log.i(TAG, "$name scan=${event.scanCode} -> $action")
        // Act on the first down (a key the layout doesn't name never delivers its up here); ignore
        // auto-repeat, and swallow every half of the press.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) run(action)
        return true
    }

    // ── Control Center shortcut without the module (P167) ──────────────────────────────────────────

    private var ccFired = false
    private val ccHold = Runnable { ccFired = true; run(RemoteAction.ControlCenter) }

    /**
     * The chosen key opens Control Center over another app. A hold is timed from the first down; the press itself still reaches the app,
     * and once the hold has fired its up is swallowed. A key made for settings opens it on the press. Not on Glass's own Home (Menu
     * has its own meaning there), and never while the setting is off.
     */
    private fun handleCcShortcut(e: KeyEvent): Boolean {
        val sc = CcShortcut.parse(app.config.config.value.ccShortcut)
        if (!sc.matches(e.keyCode) || front == null) return false
        if (!sc.hold) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) run(RemoteAction.ControlCenter)
            return true
        }
        when (e.action) {
            KeyEvent.ACTION_DOWN -> {
                if (e.repeatCount == 0) { ccFired = false; handler.removeCallbacks(ccHold); handler.postDelayed(ccHold, sc.holdMs) }
                return ccFired
            }
            KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(ccHold)
                val was = ccFired
                ccFired = false
                return was
            }
        }
        return false
    }

    /** The screen now, for [CloseSnapshot]. The system allows about one screenshot a second; a refused one leaves the fallback. */
    private fun snapshotForClose(pkg: String) {
        if (android.os.Build.VERSION.SDK_INT < 30 || controlCenter.showing) return
        runCatching {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val bitmap = android.graphics.Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    buffer.close()
                    if (bitmap != null) CloseSnapshot.offer(pkg, bitmap)
                }
                override fun onFailure(errorCode: Int) { Log.i(TAG, "close snapshot refused: $errorCode") }
            })
        }.onFailure { Log.i(TAG, "close snapshot failed: $it") }
    }

    /** Whether a focused application window of another package exists (the launched app can take Back). */
    private fun appHasFocus(): Boolean {
        val own = packageName
        // Can't see windows (capability off: the system then answers an empty list, not an error): assume the app has
        // focus, so Back is never taken.
        val windows = runCatching { windows }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return true
        val focused = FocusPick.focusedAppPackage(windows.map { w ->
            FocusPick.Win(w.isFocused, w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION, runCatching { w.root?.packageName?.toString() }.getOrNull())
        })
        return focused != null && focused != own
    }

    private var escaped = false
    /** A Back/Home press was taken as "cancel the launch": its remaining events are swallowed too. */
    private var interrupted = false
    private val escape = Runnable {
        escaped = true
        runCatching { startActivity(HomeSetup.homeIntent(this)) }
    }

    private fun run(action: RemoteAction) {
        when (action) {
            is RemoteAction.OpenApp -> {
                val pm = packageManager
                // Not installed (e.g. Amazon Music after a debloat): its store page: Fire TV's Appstore there, Play elsewhere.
                val intent = pm.getLeanbackLaunchIntentForPackage(action.pkg) ?: pm.getLaunchIntentForPackage(action.pkg)
                    ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse(
                        if (HomeSetup.isFireTv) "amzn://apps/android?p=${action.pkg}" else "market://details?id=${action.pkg}",
                    ))
                runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            RemoteAction.ControlCenter -> {
                // Always logged (P50: a first press after an install was lost once and never reproduced).
                Log.i(TAG, "Settings key: Control Center ${if (controlCenter.showing) "close" else "open"} (front=${front ?: "Glass"})")
                // With Glass in front, Control Center belongs INSIDE Home's window (P307): the accessibility window is a
                // second window, and a second window is a third layer once the native renderer draws Home. It is still
                // the right answer over another app, where Home's window is not on screen to draw into.
                if (front == null) {
                    if (controlCenter.showing) controlCenter.hide()
                    home(MainActivity.ACTION_CONTROL_CENTER)
                } else {
                    controlCenter.toggle()
                }
            }
            RemoteAction.AppSwitcher -> home(MainActivity.ACTION_APP_SWITCHER)
            RemoteAction.TvSettings -> home(MainActivity.ACTION_TV_SETTINGS)
            RemoteAction.Home -> performGlobalAction(GLOBAL_ACTION_HOME)
            is RemoteAction.AddonAction -> runAddonAction(action)
            RemoteAction.Nothing, RemoteAction.Default -> Unit
        }
    }

    /** Binds the add-on off the main thread, with the add-on call budget; its notice is shown as "<add-on>: <notice>"; a failed or absent add-on says "<add-on> unavailable". */
    private fun runAddonAction(a: RemoteAction.AddonAction) {
        val snap = app.config.config.value.let { dev.glasslauncher.addons.AddonSnapshot(it.addonsEnabled, it.addonSettings) }
        app.scope.launch {
            when (val r = dev.glasslauncher.addons.AddonHost.runAction(applicationContext, snap, dev.glasslauncher.addons.AddonActionRef(a.pkg, a.service, a.id))) {
                is dev.glasslauncher.addons.ActionOutcome.Done -> r.notice?.let { RemoteNotice.show(applicationContext, it, 3_000) }
                is dev.glasslauncher.addons.ActionOutcome.Unavailable -> RemoteNotice.show(applicationContext, dev.glasslauncher.addons.AddonNotices.failure(r.title), 3_000)
                dev.glasslauncher.addons.ActionOutcome.Dropped -> Unit
            }
        }
    }

    private fun home(action: String) {
        startActivity(
            Intent(this, MainActivity::class.java).setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
    }

    // ── App switcher previews ──────────────────────────────────────────────────────────────────────
    // A snapshot of the app in front, 3 s after it arrives and every 30 s while it stays (AppPreviews).

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var front: String? = null
    /** The app in front (null when it's Glass), for Control Center's glass. */
    val frontApp: String? get() = front
    private val launchable = HashMap<String, Boolean>()

    private val capture = object : Runnable {
        override fun run() {
            val pkg = front ?: return
            val power = getSystemService(android.os.PowerManager::class.java)
            // Not while Control Center is over the app: the preview would show it instead of the app.
            if (android.os.Build.VERSION.SDK_INT >= 30 && power?.isInteractive == true && !controlCenter.showing) {
                runCatching {
                    takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                        override fun onSuccess(result: ScreenshotResult) {
                            val buffer = result.hardwareBuffer
                            val bitmap = android.graphics.Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            buffer.close()
                            // Still the same app (a screenshot takes a frame or two).
                            if (bitmap != null && front == pkg) AppPreviews.save(this@RemoteKeysService, pkg, bitmap) else bitmap?.recycle()
                        }
                        override fun onFailure(errorCode: Int) = Unit
                    })
                }
            }
            handler.postDelayed(this, PREVIEW_EVERY_MS)
        }
    }

    // ── Resume (P160) ───────────────────────────────────────────────────────────────────────────────

    private var resumeRequest: dev.glasslauncher.featured.ResumePlayback.Request? = null
    /** Uptime when the resumed app's window first showed, null until it does. */
    private var resumeWindowAt: Long? = null

    /** After Resume launched an app: watches for its window and media session, then sends the one key that starts playback (or none). */
    fun autoPlay(request: dev.glasslauncher.featured.ResumePlayback.Request) {
        handler.post {
            handler.removeCallbacks(resumeTick)
            resumeRequest = request; resumeWindowAt = null
            handler.postDelayed(resumeTick, RESUME_POLL_MS)
        }
    }

    private val resumeTick = object : Runnable {
        override fun run() {
            val r = resumeRequest ?: return
            val now = android.os.SystemClock.uptimeMillis()
            val np = app.nowPlaying.state.value
            val session = when {
                np == null || np.packageName != r.pkg -> dev.glasslauncher.featured.ResumePlayback.Session.None
                np.playing -> dev.glasslauncher.featured.ResumePlayback.Session.Playing
                else -> dev.glasslauncher.featured.ResumePlayback.Session.Paused
            }
            val decision = dev.glasslauncher.featured.ResumePlayback.decide(now - r.launchedAt, resumeWindowAt?.let { it - r.launchedAt }, session)
            when (decision) {
                dev.glasslauncher.featured.ResumePlayback.Decision.Wait -> { handler.postDelayed(this, RESUME_POLL_MS); return }
                dev.glasslauncher.featured.ResumePlayback.Decision.MediaPlay -> sendMediaPlay()
                dev.glasslauncher.featured.ResumePlayback.Decision.Select -> clickFocused()
                else -> Unit
            }
            Log.i(TAG, "resume ${r.pkg}: $decision after ${now - r.launchedAt} ms")
            resumeRequest = null
        }
    }

    private fun sendMediaPlay() {
        val audio = getSystemService(android.media.AudioManager::class.java) ?: return
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
    }

    /** Select on the focused control of the app in front: what DPAD_CENTER would do, without injecting a key (no root). */
    private fun clickFocused() {
        val root = rootInActiveWindow ?: return
        val node = root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) ?: return
        node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (resumeWindowAt == null && resumeRequest?.pkg == pkg) resumeWindowAt = android.os.SystemClock.uptimeMillis()
        if (dev.glasslauncher.BuildConfig.DEBUG && pkg != packageName) Log.i(TAG, "window state: $pkg (gate launching=${LaunchGate.launching(android.os.SystemClock.uptimeMillis())})")
        // Glass's own windows other than Home (the Control Center overlay itself) aren't an app change.
        val glassHome = pkg == packageName && event.className?.toString() == MainActivity::class.java.name
        if (pkg == packageName && !glassHome) return
        // Only apps with a launcher entry count; dialogs, the keyboard and system overlays don't change "front".
        val isApp = launchable.getOrPut(pkg) {
            pkg != packageName && (packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)) != null
        }
        // Picking a Sonos speaker in Control Center asks for consent in a system dialog: that is not an app change, and
        // Control Center (with its platter) stays up under it (P136).
        val consentDialog = dev.glasslauncher.sonos.ConsentGate.ignores(android.os.SystemClock.uptimeMillis(), isApp)
        if ((pkg != front || glassHome) && !consentDialog) controlCenter.onAppChanged()
        // Glass in front: Control Center lives in Home's window now, so the parked window is a layer nothing draws into
        // (P307). Another app in front: it is the only way to show CC, so keep it attached.
        if (glassHome) controlCenter.detach()
        if (pkg == front) return
        if (!isApp && pkg != packageName) return
        front = pkg.takeIf { isApp }
        handler.removeCallbacks(capture)
        if (front != null) handler.postDelayed(capture, PREVIEW_FIRST_MS)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        controlCenter.hide()
        controlCenter.release()
        handler.removeCallbacks(capture)
        notices.clear()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    companion object {
        /** The connected service, for Glass to open Control Center as an overlay. */
        @Volatile var instance: RemoteKeysService? = null
            private set
        private const val RESUME_POLL_MS = 250L
        private const val PREVIEW_FIRST_MS = 3_000L
        private const val PREVIEW_EVERY_MS = 30_000L
        const val ESCAPE_HOLD_MS = 1_500L
        const val TAG = "GlassRemote"
    }
}
