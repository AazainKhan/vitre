package com.glasslauncher.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Messenger
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import dev.glasslauncher.browserlink.Link
import dev.glasslauncher.browserlink.Msg
import java.security.SecureRandom

/**
 * The Glass Browser: the platform WebView with D-pad link navigation and a pointer mode. It holds no Glass object and
 * writes no files; downloads and the on-screen keyboard are requests to Glass over [GlassLink].
 */
class BrowserActivity : Activity() {
        private enum class Mode { Links, Pointer }
    /** What a Glass keyboard answer is for. */
    private enum class Target { Page, Address }
    /** [nonce] ties a page answer to the load it was asked on. */
    private class PendingText(val id: Long, val target: Target, val nonce: String?)

    private val main = Handler(Looper.getMainLooper())
    private lateinit var link: GlassLink
    private lateinit var stage: FrameLayout
    private lateinit var bar: LinearLayout
    private lateinit var addressButton: Button
    private lateinit var modeButton: Button
    private lateinit var closeButton: Button
    private lateinit var pointerView: PointerView
    private var web: WebView? = null
    private var script = PageScript(newNonce())
    private var url = ""
    private var mode = Mode.Links
    private var busy = false
    private var selectDown = false
    private var cursorX = 200f
    private var cursorY = 200f
    private var pending: PendingText? = null
    private var seq = 0L
    private val random = SecureRandom()
    private val resetBusy = Runnable { busy = false }
    private val dropPending = Runnable {
        pending?.let { expired.addLast(it.id); while (expired.size > 8) expired.removeFirst() }
        pending = null
    }

    private fun newNonce() = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        link = GlassLink(this, ::onGlass)
        buildViews()
        if (!newWeb()) { toast("This device has no web view to browse with"); finishAndRemoveTask(); return }
        // A previous run that was killed (low memory) never reached onDestroy: its clear-on-close is owed.
        if (prefs().getBoolean("dirty", false)) clearData()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    @Suppress("DEPRECATION")
    private fun handleIntent(i: Intent) {
        i.getParcelableExtra<Messenger>(Link.EXTRA_LINK)?.let { link.glass = it }
        // Glass checks https too; this is the second check.
        BrowserRules.openable(i.getStringExtra(Link.EXTRA_URL))?.let { web?.loadUrl(it) }
        if (url.isEmpty()) addressButton.requestFocus() else stage.requestFocus()
    }

    // ---- views

    private fun buildViews() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(16, 16, 20)) }
        bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(24, 12, 24, 12) }
        fun button(label: String, weight: Float, click: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(if (weight > 0) 0 else WRAP_CONTENT, WRAP_CONTENT, weight).apply { marginEnd = 12 }
            setOnClickListener { click() }
        }
        addressButton = button("Enter a web address", 1f) { askText("address", Target.Address) }
        modeButton = button("Pointer", 0f) { toggleMode() }
        val clear = button("Clear Data", 0f) { clearData(); toast("Browsing data cleared") }
        closeButton = button(closeLabel(), 0f) {
            prefs().edit().putBoolean("clearOnClose", !clearOnClose()).apply()
            closeButton.text = closeLabel()
        }
        listOf(addressButton, modeButton, clear, closeButton).forEach { bar.addView(it) }
        stage = FrameLayout(this).apply { isFocusable = true; isFocusableInTouchMode = true }
        pointerView = PointerView(this).apply { visibility = View.GONE }
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(stage, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun prefs() = getSharedPreferences("browser", MODE_PRIVATE)
    private fun clearOnClose() = prefs().getBoolean("clearOnClose", false)
    private fun closeLabel() = "Clear on close: " + if (clearOnClose()) "On" else "Off"

    private inner class PointerView(c: Context) : View(c) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3f }
        override fun onDraw(canvas: Canvas) {
            if (mode != Mode.Pointer) return
            canvas.drawCircle(cursorX, cursorY, 11f, fill); canvas.drawCircle(cursorX, cursorY, 11f, ring)
        }
    }

    // ---- web view

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWeb(): Boolean {
        val w = runCatching { WebView(this) }.getOrNull() ?: return false
        with(w.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSafeBrowsingEnabled(true)
            @Suppress("DEPRECATION") saveFormData = false
            @Suppress("DEPRECATION") savePassword = false
            setGeolocationEnabled(false)
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
        }
        // Keys stay with the activity; the page is driven by the script and synthetic taps.
        w.isFocusable = false
        w.isFocusableInTouchMode = false
        w.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        w.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, false)
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (BrowserRules.allowNavigation(request.url.toString())) return false
                toast("Only https pages open here")
                return true
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (view !== web) return true
                if (detail.didCrash()) { toast("The web page crashed"); finishAndRemoveTask() }
                else { val back = url; replaceWeb(); if (back.isNotEmpty()) web?.loadUrl(back) }
                return true
            }
            override fun onPageStarted(view: WebView, u: String, favicon: Bitmap?) {
                url = u; script = PageScript(newNonce()); busy = false; loading = true; showUrl()
                if (pending?.target == Target.Page) pending = null
                if (clearOnClose()) prefs().edit().putBoolean("dirty", true).apply()
            }
            override fun onPageFinished(view: WebView, u: String) { url = u; loading = false; pagesShown++; trail.shown(u); showUrl(); scanPage()
                Log.i("GlassBrowser", "finished size=${view.copyBackForwardList().size} index=${view.copyBackForwardList().currentIndex} canGoBack=${view.canGoBack()} trail=${trail.size} url=$u")
            }
        }
        w.setDownloadListener { dlUrl, _, disposition, mime, _ ->
            if (Link.httpsHost(dlUrl) == null) { toast("Downloads need an https link"); return@setDownloadListener }
            val name = URLUtil.guessFileName(dlUrl, disposition, mime).take(Link.MAX_NAME)
            val sent = link.send(Msg.Download(dlUrl, name, mime.orEmpty().take(127), BrowserRules.host(url).orEmpty()))
            if (!sent) toast("VITRE isn't listening; open the browser from VITRE Downloads")
        }
        stage.addView(w, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        if (pointerView.parent == null) stage.addView(pointerView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        web = w
        return true
    }

    private fun replaceWeb() {
        web?.let { stage.removeView(it); it.destroy() }
        web = null
        newWeb()
    }

    private fun showUrl() {
        addressButton.text = BrowserRules.host(url) ?: "Enter a web address"
    }

    private fun js(code: String, then: (String?) -> Unit = {}) { web?.evaluateJavascript(code, then) }

    /** After a load: put the script in, count what it can reach, ring the first link; nothing reachable means the pointer. */
    private fun scanPage() {
        val s = script
        js("${s.inject()};${s.scan()}") { raw ->
            if (s !== script) return@js
            val scan = Scan.parse(raw, s.nonce)
            if (scan == null || scan.boxes.isEmpty()) { if (mode == Mode.Links) toPointer("Pointer mode: no links to step through here") }
            else if (mode == Mode.Links) LinkNav.first(scan.boxes, scan.viewTop, scan.viewBottom)?.let { js(s.focus(it)) }
        }
    }

    // ---- keys

    // The remote's Back is a key press; the activity opts out of predictive back so it still arrives as one.
    @SuppressLint("GestureBackNavigation")
    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        val down = ev.action == KeyEvent.ACTION_DOWN
        if (ev.keyCode == KeyEvent.KEYCODE_BACK) {
            if (ev.action == KeyEvent.ACTION_UP) {
                val list = web?.copyBackForwardList()
                val prev = trail.previous()
                val act = BackPolicy.act(web?.canGoBack() == true, loading, pagesShown, prev != null)
                Log.i("GlassBrowser", "back act=$act canGoBack=${web?.canGoBack()} loading=$loading pages=$pagesShown backForwardList.size=${list?.size} index=${list?.currentIndex} trail=${trail.size} url=${web?.url}")
                when (act) {
                BackPolicy.Act.GoBack -> web?.goBack()
                BackPolicy.Act.LoadPrevious -> prev?.let { web?.loadUrl(it) }
                BackPolicy.Act.CancelLoad -> web?.stopLoading()
                BackPolicy.Act.Close -> finishAndRemoveTask()
                }
            }
            return true
        }
        if (!stage.isFocused) {
            if (ev.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && down && currentFocus?.parent === bar) { stage.requestFocus(); return true }
            return super.dispatchKeyEvent(ev)
        }
        val dir = when (ev.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> Dir.Up
            KeyEvent.KEYCODE_DPAD_DOWN -> Dir.Down
            KeyEvent.KEYCODE_DPAD_LEFT -> Dir.Left
            KeyEvent.KEYCODE_DPAD_RIGHT -> Dir.Right
            else -> null
        }
        return when {
            ev.keyCode == KeyEvent.KEYCODE_MENU -> { if (down && ev.repeatCount == 0) toggleMode(); true }
            dir != null -> { if (down) { if (mode == Mode.Links) moveLinks(dir) else movePointer(dir, ev.repeatCount) }; true }
            ev.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || ev.keyCode == KeyEvent.KEYCODE_ENTER || ev.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                // Only a Select that began here acts: a key-up left over from Glass or the address bar must not click.
                if (down) { if (ev.repeatCount == 0) selectDown = true } else if (selectDown) { selectDown = false; select() }
                true
            }
            else -> super.dispatchKeyEvent(ev)
        }
    }

    private fun toggleMode() {
        mode = if (mode == Mode.Links) Mode.Pointer else Mode.Links
        pointerView.visibility = if (mode == Mode.Pointer) View.VISIBLE else View.GONE
        modeButton.text = if (mode == Mode.Links) "Pointer" else "Links"
        if (mode == Mode.Links) scanPage()
        pointerView.invalidate()
    }

    private fun toPointer(why: String) {
        mode = Mode.Pointer; pointerView.visibility = View.VISIBLE; modeButton.text = "Links"; pointerView.invalidate(); toast(why)
    }

    private fun moveLinks(dir: Dir) {
        val w = web ?: return
        if (busy) return
        setBusy()
        val s = script
        js(s.scan()) { raw ->
            if (s !== script) return@js
            val scan = Scan.parse(raw, s.nonce)
            if (scan == null || scan.boxes.isEmpty()) { clearBusy(); toPointer("Pointer mode: no links to step through here"); return@js }
            val next = if (scan.current < 0) LinkNav.first(scan.boxes, scan.viewTop, scan.viewBottom) else LinkNav.next(scan.boxes, scan.current, dir)
            if (next != null) { js(s.focus(next)) { clearBusy() }; return@js }
            clearBusy()
            // Nothing that way: scroll the page if it can, else (Up) leave for the address bar.
            when (dir) {
                Dir.Down -> if (w.canScrollVertically(1)) w.scrollBy(0, w.height / 2)
                Dir.Up -> if (w.canScrollVertically(-1)) w.scrollBy(0, -w.height / 2) else addressButton.requestFocus()
                else -> Unit
            }
        }
    }

    /** A stuck flag (a reply that never comes) clears itself. */
    private fun setBusy() { busy = true; main.removeCallbacks(resetBusy); main.postDelayed(resetBusy, 1500) }
    private fun clearBusy() { busy = false; main.removeCallbacks(resetBusy) }

    private fun movePointer(dir: Dir, repeat: Int) {
        val w = web ?: return
        val step = PointerSpeed.step(repeat).toFloat()
        val dx = when (dir) { Dir.Left -> -step; Dir.Right -> step; else -> 0f }
        val dy = when (dir) { Dir.Up -> -step; Dir.Down -> step; else -> 0f }
        val maxX = stage.width.toFloat(); val maxY = stage.height.toFloat()
        var x = cursorX + dx; var y = cursorY + dy
        // Pushing past an edge scrolls the page under the cursor.
        if (y < 0f) { if (w.canScrollVertically(-1)) w.scrollBy(0, y.toInt()) else if (dir == Dir.Up) addressButton.requestFocus() }
        if (y > maxY) w.scrollBy(0, (y - maxY).toInt())
        if (x < 0f) w.scrollBy(x.toInt(), 0)
        if (x > maxX) w.scrollBy((x - maxX).toInt(), 0)
        cursorX = x.coerceIn(0f, maxX); cursorY = y.coerceIn(0f, maxY)
        pointerView.invalidate()
    }

    private fun select() {
        if (mode == Mode.Pointer) tap(cursorX, cursorY) else {
            val s = script
            js(s.act()) { raw ->
                if (s !== script) return@js
                val point = TapPoint.parse(raw, s.nonce)
                val w = web
                if (point != null && w != null) { val (x, y) = point.inView(w.width, w.height); tap(x, y) }
                else Reply.textKind(raw, s.nonce)?.let { askText(it, Target.Page) }
            }
        }
    }

    /** A real touch, so the page sees a user gesture (a script click would not count as one). */
    private fun tap(x: Float, y: Float) {
        val w = web ?: return
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        w.dispatchTouchEvent(down); down.recycle()
        main.postDelayed({
            val up = MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, x, y, 0)
            web?.dispatchTouchEvent(up); up.recycle()
            // A tap that landed in a text field asks Glass for its keyboard instead of the system one.
            main.postDelayed({
                val s = script
                js(s.activeText()) { raw -> if (s === script) Reply.textKind(raw, s.nonce)?.let { askText(it, Target.Page) } }
            }, 200)
        }, 40)
    }

    // ---- Glass

    /** Asks Glass for its keyboard; the answer comes back as TextResult. The field's current value is never sent. */
    private fun askText(kind: String, target: Target) {
        val id = ++seq
        // The committed page's host (web.url), not the last load that merely started: a navigation that never commits must not lend its host.
        val host = if (target == Target.Address) "" else BrowserRules.host(web?.url.orEmpty()).orEmpty()
        if (!link.send(Msg.TextRequest(id, host, kind, kind == "password"))) { toast("VITRE isn't listening; open the browser from VITRE Downloads"); return }
        pending = PendingText(id, target, if (target == Target.Page) script.nonce else null)
        main.removeCallbacks(dropPending); main.postDelayed(dropPending, 120_000)
    }

    private fun onGlass(msg: Msg) {
        when (msg) {
            Msg.ShowGlass -> { handoffAt = SystemClock.elapsedRealtime(); startActivity(
                Intent(Link.ACTION_SHOW_GLASS).setComponent(ComponentName(Link.GLASS_PACKAGE, Link.GLASS_ACTIVITY)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) }
            is Msg.TextResult -> {
                val p = pending
                val route = TextRouting.route(p?.id, p?.nonce, msg.id, script.nonce, expired.toSet())
                if (route == TextRouting.Route.DropLate) { toast("That took too long, so the text wasn't entered"); return }
                if (route == TextRouting.Route.Ignore || p == null) return
                pending = null
                if (route == TextRouting.Route.DropStale) { toast("The page changed, so the text wasn't entered"); return }
                when (p.target) {
                    Target.Page -> js(script.setText(msg.text))
                    Target.Address -> when (val a = BrowserRules.address(msg.text)) {
                        is Address.Go -> web?.loadUrl(a.url)
                        is Address.Refused -> toast(a.message)
                    }
                }
            }
            is Msg.Notice -> toast(msg.text)
            else -> Unit
        }
    }

    // ---- data, lifecycle

    private fun clearData() {
        prefs().edit().putBoolean("dirty", false).apply()
        val cookies = CookieManager.getInstance()
        // removeAllCookies is async: persist only once it is done.
        cookies.removeAllCookies { cookies.flush() }
        WebStorage.getInstance().deleteAllData()
        WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword()
        WebView.clearClientCertPreferences(null)
        web?.apply { clearCache(true); clearHistory(); clearFormData(); clearSslPreferences() }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    /** Hidden for a while, or the system wants memory: give the WebView (and its renderer) back, and rebuild it on return. */
    private val release: Runnable = Runnable { if (mayRelease()) releaseWeb() else main.postDelayed(release, 60_000) }
    private var handoffAt = -ReleasePolicy.HANDOFF_MS
    private val expired = ArrayDeque<Long>()
    private var loading = false
    private var pagesShown = 0
    private val trail = Trail()

    /** Never while Glass holds a request of ours (a typed answer, a download card): the page, form and back stack must survive it. */
    private fun mayRelease() = ReleasePolicy.mayRelease(pending != null, SystemClock.elapsedRealtime(), handoffAt)
    private var releasedUrl: String? = null

    private fun releaseWeb() {
        val w = web ?: return
        releasedUrl = url.takeIf { it.isNotEmpty() }
        w.stopLoading(); stage.removeView(w); w.destroy()
        web = null
    }

    override fun onStart() {
        super.onStart()
        main.removeCallbacks(release)
        if (web == null && newWeb()) { releasedUrl?.let { web?.loadUrl(it) }; releasedUrl = null }
    }

    override fun onStop() {
        main.postDelayed(release, 120_000)
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // UI_HIDDEN fires on every hand-off to Glass; only a real background trim (or the stop timer) gives the page up.
        if (level >= TRIM_MEMORY_BACKGROUND && !hasWindowFocus() && mayRelease()) releaseWeb()
    }

    override fun onResume() {
        super.onResume()
        handoffAt = -ReleasePolicy.HANDOFF_MS   // back from Glass: nothing is in flight any more
        web?.onResume(); web?.resumeTimers()
    }

    override fun onPause() {
        // A hidden page must not keep running scripts and timers.
        web?.onPause(); web?.pauseTimers()
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (clearOnClose()) clearData()
        link.send(Msg.Closed)
        link.dispose()
        // Give the renderer back at once, so the closed browser does not sit in the cache holding 100 MB.
        web?.let { it.stopLoading(); it.loadUrl("about:blank"); it.onPause(); it.pauseTimers(); stage.removeView(it); it.destroy() }
        web = null
        super.onDestroy()
    }
}
