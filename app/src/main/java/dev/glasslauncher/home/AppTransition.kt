package dev.glasslauncher.home

import androidx.compose.ui.graphics.graphicsLayer
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import dev.glasslauncher.R
import dev.glasslauncher.apps.AppEntry
import dev.glasslauncher.glass.BackdropState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.withFrameNanos
import kotlin.math.exp
import kotlin.math.max

/**
 * App open and close, drawn by the launcher (motion-spec §9, the HotshotTek reference).
 *
 * Open: Home blurs in one frame; the tile becomes a rounded window filled with the app's launch colour
 * and its glyph in grey, and grows to full screen at full speed, decelerating (τ ≈ 130 ms). The app
 * is started part way through and fades in over the window, so the system's own zoom never plays.
 *
 * Close: when Home comes back from that app, a full-screen window in the launch colour shrinks into
 * the app's tile on an ease-in-out curve, turning back into the tile art by half size, lands a little
 * large, settles into the focused tile, and only then does Home sharpen.
 *
 * Everything is one blurred-Home image plus one rounded window, so it costs about a full-screen pass.
 */
@Stable
class AppTransition(
    private val context: Context,
    private val scope: CoroutineScope,
    private val backdrop: BackdropState,
    private val model: HomeModel,
) {
    /** Blurred capture of Home, drawn over it while a transition runs. */
    var cover by mutableStateOf<ImageBitmap?>(null); private set
    val coverAlpha = Animatable(0f)
    var window by mutableStateOf<LaunchWindow?>(null); private set
    var closing by mutableStateOf(false); private set
    /** Home is hidden behind the app it launched: its window shows the app's preview, for the system's snapshot of Home. */
    var returning by mutableStateOf(false); private set
    /** Open: 0 = tile, 1 = full screen. Close: 1 = full screen, 0 = landed (slightly large). */
    val progress = Animatable(0f)
    /** 0 = the tile art, 1 = launch colour and glyph. */
    val fill = Animatable(0f)
    /** Close only: 1 = landed large, 0 = exactly the focused tile. */
    val settle = Animatable(1f)
    /** The launch window's own opacity: a page (Settings) fades it off itself on open, and on with it on close (P105 #7). */
    val windowAlpha = Animatable(1f)
    /**
     * True while [openPage] has the page coming up under its window: the page then enters already opaque (no fade of
     * its own), so when the window fades off it there is only the page underneath, never Home.
     */
    var pageOpening by mutableStateOf(false); private set

    private var last: LaunchWindow? = null
    /** Home's view, to tell when Home is really on screen again (window focus) before the close starts. */
    private var host: java.lang.ref.WeakReference<android.view.View>? = null
    /** When [last] launched: Home leaving much later than that isn't that app opening. */
    private var lastAt = 0L
    private var job: Job? = null
    /** Launch colour and glyph per tile art (art bitmaps are cached and reused, so identity works). */
    private val looks = java.util.Collections.synchronizedMap(java.util.WeakHashMap<ImageBitmap, Pair<Color, ImageBitmap?>>())

    val running get() = window != null

    /** An open is in flight: zooming, or waiting for the app it started to cover Home. */
    fun launching(): Boolean = job?.isActive == true && !closing && page == null && !pageOpening

    /** The app hasn't been asked to start yet (the zoom is still within [HANDOFF_MS]). */
    private var startRequested = false

    /**
     * Back during the zoom, before the app has been started: reverse the zoom and never start it (P87). False when
     * there is nothing to cancel (no open zoom, or the app is already starting: Glass can't undo a start).
     */
    fun cancelBeforeHandoff(): Boolean {
        // (Also in the first frames, before the window is shown: the launch job is already running then.)
        if (closing || startRequested || job?.isActive != true) return false
        job?.cancel()
        job = scope.launch {
            launch { fill.animateTo(0f, tween(CLOSE_FADE_MS)) }
            progress.animateTo(0f, tween(CLOSE_MS, easing = FastOutSlowInEasing))
            coverAlpha.animateTo(0f, tween(UNBLUR_MS))
            reset()
        }
        last = null
        return true
    }

    fun open(app: AppEntry, from: Rect?, art: ImageBitmap?, view: android.view.View) {
        // A second Select while a launch is under way (a gamepad's quick double press, P178) is ignored: it used to
        // cancel the zoom and start it again from the tile, a visible flash, and could start the app twice.
        if (launching()) return
        if (from == null || backdrop.rootSize == IntSize.Zero) { model.launch(app, view, from); return }
        host = java.lang.ref.WeakReference(view)
        // A new launch: nothing has been started yet, so Back can still cancel it from its very first frame.
        startRequested = false
        job?.cancel()
        job = scope.launch {
            // Glyph and colour come from the tile art in parallel with the Home capture (both ~10–30 ms).
            val look = async(Dispatchers.Default) { art?.let { a -> looks[a] ?: launchLook(a).also { looks[a] = it } } }
            val background = async(Dispatchers.Default) { startingColor(app) }
            val captured = captureScreen(view) ?: backdrop.backdrop?.blurredSoftware
            val (artColor, glyph) = look.await() ?: (Color(0xFF15161A) to null)
            // The app's own starting window (its theme's windowBackground) is what covers the zoom once it starts:
            // in the same colour the hand-off doesn't flash (P147). The art's colour only when the theme has none.
            val color = background.await() ?: artColor
            val w = LaunchWindow(app, from, art, color, glyph)
            closing = false
            returning = false
            cover = captured
            coverAlpha.snapTo(1f)
            // Already moving on its first frame, as Apple's is (about a quarter of the way at 0 ms).
            progress.snapTo(OPEN_START)
            fill.snapTo(0f)
            settle.snapTo(1f)
            window = w
            last = w
            startRequested = false
            lastAt = android.os.SystemClock.uptimeMillis()
            launch { fill.animateTo(1f, tween(FILL_MS)) }
            launch { progress.animateTo(1f, tween(OPEN_MS, easing = ZoomOut)) }
            delay(HANDOFF_MS)
            startRequested = true
            if (!startApp(app)) {
                // Honest failure (P162): the window goes back into its tile and a notice says so, instead of Home
                // quietly coming back as if nothing had been asked.
                startRequested = false
                launch { fill.animateTo(0f, tween(CLOSE_FADE_MS)) }
                progress.animateTo(0f, tween(CLOSE_MS, easing = FastOutSlowInEasing))
                coverAlpha.animateTo(0f, tween(UNBLUR_MS))
                reset()
                last = null
                dev.glasslauncher.system.LaunchNotice.couldNotOpen(context, app.label)
                return@launch
            }
            // From here until its first window shows, Back or Home means "cancel it" (RemoteKeysService).
            dev.glasslauncher.system.LaunchGate.begin(app.packageName, android.os.SystemClock.uptimeMillis())
            model.noteLaunched(app.packageName)
            // Some apps take seconds to cover Home; only undo if the app never took focus from it.
            delay(2_500)
            if (window != null && !closing && view.hasWindowFocus()) { last = null; coverAlpha.animateTo(0f, tween(200)); reset() }
        }
    }

    /** The in-Glass page (Settings) whose zoom is up; its close plays back into the tile it grew from. */
    private var page: LaunchWindow? = null

    /**
     * Settings (and any page Glass shows itself) from its tile, as an app opens (P105 #7): the same zoom, with the page's
     * own colour and a glyph on a full-screen window, then [show] puts the page up under it and the window goes. Home
     * isn't stopped, so this plays no lifecycle close: see [closePage].
     */
    fun openPage(tile: Rect?, entry: AppEntry, color: Color, glyph: ImageBitmap?, view: android.view.View, show: () -> Unit) {
        if (tile == null || backdrop.rootSize == IntSize.Zero) { show(); return }
        startRequested = true // a page is not an app start: Back cancels nothing here
        job?.cancel()
        job = scope.launch {
            val captured = captureScreen(view) ?: backdrop.backdrop?.blurredSoftware
            val w = LaunchWindow(entry, tile, null, color, glyph)
            closing = false
            cover = captured
            windowAlpha.snapTo(1f)
            coverAlpha.snapTo(1f)
            progress.snapTo(OPEN_START)
            fill.snapTo(0f)
            settle.snapTo(1f)
            window = w
            page = w
            last = null
            launch { fill.animateTo(1f, tween(FILL_MS)) }
            progress.animateTo(1f, tween(PAGE_OPEN_MS, easing = ZoomOut))
            // The page is composed under the full-screen window, already opaque; once it is drawn the window fades off
            // it on one alpha. The blurred Home isn't drawn meanwhile (the page covers it), so no frame shows Home.
            pageOpening = true
            try {
                show()
                // The page's first frames are slow (composition, first draw): on the stick about 300 ms went by with
                // nothing presented, and a fade started on a timer was over before it showed. Wait for two steady frames
                // (as the close does), then hold a moment and fade.
                var previous = withFrameNanos { it }
                var steady = 0
                for (i in 0 until 30) {
                    val now = withFrameNanos { it }
                    steady = if (now - previous < 25_000_000L) steady + 1 else 0
                    previous = now
                    if (steady >= 2) break
                }
                delay(PAGE_HOLD_MS)
                coverAlpha.snapTo(0f)
                windowAlpha.animateTo(0f, tween(PAGE_REVEAL_MS))
                window = null
                cover = null
            } finally {
                pageOpening = false
                windowAlpha.snapTo(1f)
            }
        }
    }

    /** The page opened by [openPage] is going: its window comes back full screen and shrinks into [tile] (the focused tile). */
    fun closePage(tile: Rect?) {
        val w = page ?: return
        page = null
        val target = tile ?: w.tile
        val coverImage = cover ?: backdrop.backdrop?.blurredSoftware ?: return
        playClose(w.copy(tile = target), coverImage, fadeIn = true)
    }

    /**
     * The screen as it is, small and blurred. PixelCopy reads the window's last frame on the
     * compositor's side straight into a 480×270 bitmap, so the UI thread never waits on it
     * (GraphicsLayer.toImageBitmap re-rendered Home on the main thread: ~50–90 ms per press).
     */
    private suspend fun captureScreen(view: android.view.View): ImageBitmap? {
        val window = generateSequence(view.context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>().firstOrNull()?.window ?: return null
        val small = Bitmap.createBitmap(480, 270, Bitmap.Config.ARGB_8888)
        val ok = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
            runCatching {
                android.view.PixelCopy.request(window, small, { cont.resumeWith(Result.success(it == android.view.PixelCopy.SUCCESS)) }, android.os.Handler(android.os.Looper.getMainLooper()))
            }.onFailure { cont.resumeWith(Result.success(false)) }
        }
        if (!ok) { small.recycle(); return null }
        return withContext(Dispatchers.Default) {
            val blurred = dev.glasslauncher.glass.Blur.backdrop(small, dev.glasslauncher.glass.WallpaperLoader.BLUR_W, dev.glasslauncher.glass.WallpaperLoader.BLUR_H, radius = 6).also { small.recycle() }
            (blurred.copy(Bitmap.Config.HARDWARE, false)?.also { blurred.recycle() } ?: blurred).asImageBitmap()
        }
    }

    /** Reads the starting-window colours of [apps] off the main thread, so a launch never waits on one. */
    suspend fun prewarm(apps: List<AppEntry>) = withContext(Dispatchers.IO) {
        for (app in apps) if (!startingColors.containsKey(app.packageName)) startingColor(app)
    }

    /** Starting-window colour per package (themes don't change while installed; reinstall clears nothing it matters for). */
    private val startingColors = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * The launch activity's windowBackground, which Android shows as the app's starting window, when it is an opaque
     * colour. Null for a transparent or drawable background, or when the theme can't be read. No permission needed.
     */
    private fun startingColor(app: AppEntry): Color? {
        startingColors[app.packageName]?.let { return if (it == 0) null else Color(it) }
        val argb = runCatching {
            val intent = model.launchIntent(app) ?: return@runCatching 0
            val info = context.packageManager.resolveActivity(intent, 0)?.activityInfo ?: return@runCatching 0
            val theme = info.themeResource.takeIf { it != 0 } ?: info.applicationInfo.theme
            if (theme == 0) return@runCatching 0
            val pkg = context.createPackageContext(info.packageName, 0)
            pkg.setTheme(theme)
            val a = pkg.theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowBackground))
            try {
                val d = a.getDrawable(0)
                val c = (d as? android.graphics.drawable.ColorDrawable)?.color
                    ?: (d as? android.graphics.drawable.LayerDrawable)?.takeIf { it.numberOfLayers > 0 }
                        ?.getDrawable(0)?.let { it as? android.graphics.drawable.ColorDrawable }?.color
                if (c != null && (c ushr 24) >= 0xF0) c or (0xFF shl 24) else 0
            } finally { a.recycle() }
        }.getOrDefault(0)
        startingColors[app.packageName] = argb
        return if (argb == 0) null else Color(argb)
    }

    /** Off the main thread: the binder call blocked it for ~40–60 ms, mid-zoom. */
    private suspend fun startApp(app: AppEntry): Boolean = withContext(Dispatchers.IO) {
        // Straight to the store (its own entry only bounces a request back to Home).
        if (app.packageName == dev.glasslauncher.system.AmazonStore.PACKAGE) {
            if (dev.glasslauncher.system.AmazonStore.open(context)) return@withContext true
            // No root: the store through its public links (an empty search, which is its front door), not the launcher entry that only bounces to Home (P168).
            if (dev.glasslauncher.system.StoreLauncher.open(context, null)) return@withContext true
        }
        val intent = model.launchIntent(app) ?: return@withContext false
        val options = ActivityOptions.makeCustomAnimation(context, R.anim.app_open_enter, R.anim.app_open_hold).toBundle()
        try {
            context.startActivity(intent, options)
            true
        } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }
    }

    /**
     * The zoom's draw operations compile GPU programs on first use (~95 ms on the GE9215), which made the
     * first launch after Home started stall for three frames. Home draws them once, invisibly, up front.
     */
    var warmed by mutableStateOf(false)

    /** Home is hidden: drop the window so nothing is left over if Home comes back another way. */
    fun onHomeStopped() {
        if (closing) return
        job?.cancel()
        // An app Glass opened covers Home within a few seconds. Home leaving later than that (an app
        // opened some other way, the store's own flow) mustn't replay that launch's close when it returns.
        if (last != null && android.os.SystemClock.uptimeMillis() - lastAt > STALE_LAUNCH_MS) last = null
        // Left full screen on purpose: Home's first frames when it comes back then match the snapshot
        // the system shows meanwhile (the launch window), instead of dipping to black.
        if (last != null) {
            // Home's last frame (which the system shows as Home comes back) is the app's preview when there is one.
            dev.glasslauncher.system.CloseSnapshot.preferPreview(context, last!!.app.packageName)
            returning = true
            scope.launch { progress.snapTo(1f); fill.snapTo(1f); coverAlpha.snapTo(1f) }
        } else {
            window = null
            scope.launch { coverAlpha.snapTo(0f) }
        }
    }

    /**
     * Home is visible again. If the last launch came from Home, plays the close into its tile and
     * returns true (Home skips its own settle). [tileBounds] is where that tile is now, if on screen.
     */
    fun onHomeStarted(tileBounds: (AppEntry) -> Rect?): Boolean {
        val w = last ?: return false
        last = null
        dev.glasslauncher.system.CloseSnapshot.preferPreview(context, w.app.packageName)
        val target = tileBounds(w.app) ?: w.tile
        val coverImage = cover ?: backdrop.backdrop?.blurredSoftware ?: return false
        playClose(w.copy(tile = target), coverImage)
        return true
    }

    /** [fadeIn]: a page's close; the window fades on over the page (held opaque meanwhile) before it shrinks. */
    private fun playClose(w: LaunchWindow, coverImage: ImageBitmap, fadeIn: Boolean = false) {
        job?.cancel()
        job = scope.launch {
            val me = coroutineContext[Job]
            try {
            cover = coverImage
            closing = true
            returning = false
            window = w
            coverAlpha.snapTo(1f)
            progress.snapTo(1f)
            fill.snapTo(1f)
            settle.snapTo(1f)
            if (fadeIn) {
                // The page's rows fade into the window rather than vanish in one frame (the cover isn't drawn under a
                // full-size window, so what shows through is the page itself).
                windowAlpha.snapTo(0f)
                windowAlpha.animateTo(1f, tween(PAGE_CLOSE_FADE_MS))
            } else windowAlpha.snapTo(1f)
            // Home's first frames after coming back are slow (the system shows its snapshot, the full
            // window, meanwhile): start the clock once frames are steady, or the shrink is skipped.
            // Steady frames alone weren't enough on the stick: Home drew steadily while the system still showed its
            // snapshot, so the first frame seen was already half way through the shrink (P147). Wait for Home's window
            // to have focus too (it is on screen then), up to ~600 ms.
            var previous = withFrameNanos { it }
            for (i in 0 until 36) {
                val now = withFrameNanos { it }
                val steady = now - previous < 25_000_000L
                previous = now
                // The app's snapshot (decoded in parallel) usually lands within a few frames: show the app shrinking, not
                // its colour then the app. Without one, the colour after ~200 ms.
                val snapshot = fadeIn || dev.glasslauncher.system.CloseSnapshot.peek(w.app.packageName) != null || i >= 12
                if (steady && snapshot && host?.get()?.hasWindowFocus() != false) break
            }
            // Its own clock, at most 20 ms a frame: Home's first frames back stall (the app is still letting go), and a
            // tween timed by the wall clock then skipped most of the shrink in one frame (90% to 30% size, P149).
            var t = 0f
            var prev = withFrameNanos { it }
            while (t < CLOSE_MS) {
                val now = withFrameNanos { it }
                t = (t + ((now - prev) / 1_000_000f).coerceAtMost(MAX_STEP_MS)).coerceAtMost(CLOSE_MS.toFloat())
                prev = now
                // The tile art is back by about half size, so you see where it's going.
                fill.snapTo(1f - ((t - 30f) / CLOSE_FADE_MS).coerceIn(0f, 1f))
                progress.snapTo(1f - FastOutSlowInEasing.transform(t / CLOSE_MS))
            }
            // Home starts sharpening as the tile settles, so there's no blurred pause after landing; the
            // window (exactly the real tile by then) goes once Home is clear.
            launch { settle.animateTo(0f, tween(LAND_MS, easing = FastOutSlowInEasing)) }
            coverAlpha.animateTo(0f, tween(UNBLUR_MS, easing = FastOutSlowInEasing))
            } finally {
                // Also when cancelled part-way: a close left behind covered Home with the launch window
                // until the next launch. Unless a newer transition has already taken over.
                if (job === me) {
                    dev.glasslauncher.system.CloseSnapshot.clear()
                    window = null
                    closing = false
                    cover = null
                    scope.launch { coverAlpha.snapTo(0f) }
                }
            }
        }
    }

    private fun reset() {
        window = null
        returning = false
        closing = false
        scope.launch { coverAlpha.snapTo(0f) }
        cover = null
    }

    companion object {
        const val OPEN_MS = 450
        const val OPEN_START = 0.12f
        const val FILL_MS = 110
        /** startActivity this far into the zoom; the app's first window fades in over the rest. */
        // Early, so the app starts while the zoom plays (P310, the user's pick): its first frame ~330 ms after the key
        // instead of ~590. P149 once saw 100-270 ms gaps from an early start; on 2026-10-27 (10 warm launches each) the
        // worst zoom frame was 13-44 ms at 150 vs 13-38 ms at 420, so that stall no longer reproduces. The app fades in
        // over the zoom's last ~100 ms, while the window is ~90% of full size.
        const val HANDOFF_MS = 150L
        /** A page zooms to full screen in this long, then holds this long under the window while it draws. */
        const val PAGE_OPEN_MS = 260
        const val PAGE_HOLD_MS = 60L
        /** The window fades off the page this long on open, and onto it this long on close (the page holds opaque longer). */
        const val PAGE_REVEAL_MS = 120
        const val PAGE_CLOSE_FADE_MS = 100
        const val PAGE_CLOSE_HOLD_MS = 120L
        const val CLOSE_MS = 240
        /** The close advances at most this much per frame, so a stalled frame slows it instead of skipping it. */
        const val MAX_STEP_MS = 20f
        const val CLOSE_FADE_MS = 110
        const val LAND_MS = 90
        const val UNBLUR_MS = 120
        /** Home stopping later than this after a launch isn't that launch. */
        const val STALE_LAUNCH_MS = 6_000L
        /** Where the close lands before settling, relative to the focused tile. */
        const val LAND_SCALE = 1.5f

        /** Exponential decelerate with τ ≈ 130 ms over 450 ms (k = 3.5), full speed on the first frame. */
        val ZoomOut = Easing { x -> ((1f - exp(-3.5f * x)) / (1f - exp(-3.5f))) }
    }
}

@Immutable
data class LaunchWindow(
    val app: AppEntry,
    /** The tile as drawn when focused (window coordinates). */
    val tile: Rect,
    val art: ImageBitmap?,
    val color: Color,
    val glyph: ImageBitmap?,
)

val LocalAppTransition = staticCompositionLocalOf<AppTransition?> { null }

/** Draws the blurred Home and the window. Placed above everything else on Home. */
@Composable
fun AppTransitionLayer(t: AppTransition, warmSource: ImageBitmap?) {
    if (!t.warmed && warmSource != null) {
        val glyph = remember { ImageBitmap(4, 4) }
        val grey = remember { ColorFilter.tint(Color(0xFFB9BBC2)) }
        // The tile art the zoom draws is a HARDWARE bitmap, sampled through another texture type than the software blur: warming
        // with the blur left the art's program to compile on the first open after every start (~100 ms, P321). Warm with a hardware
        // copy, drawn as the zoom draws the art: through an image shader, under a scale, at full and part alpha.
        val hw = remember(warmSource) { warmSource.asAndroidBitmap().copy(android.graphics.Bitmap.Config.HARDWARE, false)?.asImageBitmap() ?: warmSource }
        Canvas(Modifier.size(2.dp)) {
            // The same operations as the zoom, at an alpha too low to see.
            drawImage(warmSource, srcOffset = IntOffset.Zero, srcSize = IntSize(warmSource.width, warmSource.height), dstSize = IntSize(2, 2), alpha = 0.01f)
            drawRoundRect(ShaderBrush(ImageShader(warmSource)), size = Size(2f, 2f), cornerRadius = CornerRadius(1f), alpha = 0.01f)
            withTransform({ scale(2f / hw.width, 2f / hw.height, Offset.Zero) }) {
                drawRoundRect(ShaderBrush(ImageShader(hw)), size = Size(hw.width.toFloat(), hw.height.toFloat()), cornerRadius = CornerRadius(hw.width / 8f, hw.height / 8f), alpha = 0.01f)
            }
            drawRoundRect(Color.Black, Offset.Zero, Size(2f, 2f), CornerRadius(1f), alpha = 0.01f)
            drawImage(glyph, dstOffset = IntOffset.Zero, dstSize = IntSize(2, 2), alpha = 0.01f, colorFilter = grey)
        }
        // And at the zoom's own sizes and full alpha (Skia picks the op by size and radius), under a layer too faint to see.
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = 0.01f }) {
            val tile = (dev.glasslauncher.ui.Metrics().tileRadius * dev.glasslauncher.ui.FOCUSED_SCALE).toPx()
            for ((k, radius) in listOf(0.2f to tile, 0.6f to 18.dp.toPx())) {
                val w = size.width * k; val h = size.height * k
                val sx = w / hw.width; val sy = h / hw.height
                withTransform({ translate((size.width - w) / 2f, (size.height - h) / 2f); scale(sx, sy, Offset.Zero) }) {
                    drawRoundRect(ShaderBrush(ImageShader(hw)), size = Size(hw.width.toFloat(), hw.height.toFloat()), cornerRadius = CornerRadius(radius / sx, radius / sy))
                }
                // The launch window's colour fill and its tinted glyph, as the zoom draws them.
                drawRoundRect(Color(0xFF15161A), Offset((size.width - w) / 2f, (size.height - h) / 2f), Size(w, h), CornerRadius(radius), alpha = 0.6f)
                drawRoundRect(Color(0xFF15161A), Offset((size.width - w) / 2f, (size.height - h) / 2f), Size(w, h), CornerRadius(radius))
                drawImage(glyph, dstOffset = IntOffset((size.width / 2f).toInt(), (size.height / 2f).toInt()), dstSize = IntSize((w * 0.2f).toInt(), (w * 0.12f).toInt()), alpha = 0.5f, colorFilter = grey)
            }
        }
        androidx.compose.runtime.LaunchedEffect(Unit) { withFrameNanos { }; withFrameNanos { }; t.warmed = true }
    }
    val w = t.window
    if (w == null && t.coverAlpha.value == 0f) return
    val grey = remember { ColorFilter.tint(Color(0xFFB9BBC2)) }
    Canvas(Modifier.fillMaxSize()) {
        val cover = t.cover
        val coverAlpha = t.coverAlpha.value
        val coveredByWindow = w != null && t.progress.value >= 0.999f && t.fill.value >= 1f
        if (cover != null && coverAlpha > 0f && !coveredByWindow) {
            drawImage(cover, srcOffset = IntOffset.Zero, srcSize = IntSize(cover.width, cover.height), dstSize = IntSize(size.width.toInt(), size.height.toInt()), alpha = coverAlpha)
        }
        w ?: return@Canvas
        val full = Rect(Offset.Zero, size)
        val p = t.progress.value
        val rect = if (!t.closing) lerpRect(w.tile, full, p) else {
            val land = scaleRect(w.tile, AppTransition.LAND_SCALE)
            if (p > 0f) lerpRect(land, full, p) else lerpRect(w.tile, land, t.settle.value)
        }
        // Corners stay rounded until the window is nearly full screen.
        // The focused tile it grows from shows its corners scaled up with it.
        val tileRadius = (dev.glasslauncher.ui.Metrics().tileRadius * dev.glasslauncher.ui.FOCUSED_SCALE).toPx()
        val toFull = ((rect.width - w.tile.width) / (size.width - w.tile.width)).coerceIn(0f, 1f)
        val radius = lerp(tileRadius, 26.dp.toPx(), toFull) * (1f - ((toFull - 0.92f) / 0.08f).coerceIn(0f, 1f))
        val f = t.fill.value
        // Rounded rects only (the art through an image shader): a clipPath here costs HWUI a mask per
        // frame and made the first frames of the zoom take 80–150 ms on the stick.
        if (f < 1f && w.art != null) {
            val art = w.art
            val sx = rect.width / art.width
            val sy = rect.height / art.height
            withTransform({ translate(rect.left, rect.top); scale(sx, sy, Offset.Zero) }) {
                drawRoundRect(ShaderBrush(ImageShader(art)), size = Size(art.width.toFloat(), art.height.toFloat()), cornerRadius = CornerRadius(radius / sx, radius / sy))
            }
        }
        // Closing an app: its own last frame shrinks into the tile when there is one (P147), not a flat colour.
        val snapshot = if (t.closing || t.returning) dev.glasslauncher.system.CloseSnapshot.peek(w.app.packageName) else null
        if (f > 0f && snapshot != null) {
            val sx = rect.width / snapshot.width
            val sy = rect.height / snapshot.height
            withTransform({ translate(rect.left, rect.top); scale(sx, sy, Offset.Zero) }) {
                drawRoundRect(ShaderBrush(ImageShader(snapshot)), size = Size(snapshot.width.toFloat(), snapshot.height.toFloat()), cornerRadius = CornerRadius(radius / sx, radius / sy), alpha = f * t.windowAlpha.value)
            }
        } else if (f > 0f) {
            val wa = t.windowAlpha.value
            drawRoundRect(w.color, rect.topLeft, rect.size, CornerRadius(radius), alpha = f * wa)
            w.glyph?.let { g ->
                // The glyph stays about tile-sized and centred, as the launch screen's logo does.
                val gw = lerp(w.tile.width * 0.62f, w.tile.width * 0.9f, toFull)
                val gh = gw * g.height / g.width
                drawImage(
                    g,
                    dstOffset = IntOffset((rect.center.x - gw / 2).toInt(), (rect.center.y - gh / 2).toInt()),
                    dstSize = IntSize(gw.toInt(), gh.toInt()),
                    alpha = f * 0.55f * wa,
                    colorFilter = grey,
                )
            }
        }
    }
}

private fun lerpRect(a: Rect, b: Rect, t: Float) =
    Rect(lerp(a.left, b.left, t), lerp(a.top, b.top, t), lerp(a.right, b.right, t), lerp(a.bottom, b.bottom, t))

private fun scaleRect(r: Rect, s: Float): Rect {
    val dx = r.width * (s - 1f) / 2f
    val dy = r.height * (s - 1f) / 2f
    return Rect(r.left - dx, r.top - dy, r.right + dx, r.bottom + dy)
}

/**
 * The window's launch colour and glyph from the tile art: the colour is the art's most saturated
 * hue (its background when that's coloured), darkened to a launch-screen tone; the glyph is the logo
 * lifted off the background as an alpha mask, drawn grey like tvOS's launch screens.
 */
private fun launchLook(art: ImageBitmap): Pair<Color, ImageBitmap?> {
    val src = art.asAndroidBitmap().let { if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it }
    val w = 160
    val h = max(1, w * src.height / src.width)
    val small = Bitmap.createScaledBitmap(src, w, h, true)
    val px = IntArray(w * h).also { small.getPixels(it, 0, w, 0, 0, w, h) }
    if (small !== src) small.recycle()
    if (src !== art.asAndroidBitmap()) src.recycle()

    fun r(c: Int) = (c shr 16 and 0xFF) / 255f
    fun g(c: Int) = (c shr 8 and 0xFF) / 255f
    fun b(c: Int) = (c and 0xFF) / 255f
    fun sat(c: Int): Float { val mx = maxOf(r(c), g(c), b(c)); val mn = minOf(r(c), g(c), b(c)); return if (mx == 0f) 0f else (mx - mn) / mx }

    // Background: the median of the opaque pixels a few in from the border (art can have transparent,
    // rounded corners, which read as black).
    val inset = 4
    val edge = buildList {
        for (x in inset until w - inset) { add(px[inset * w + x]); add(px[(h - 1 - inset) * w + x]) }
        for (y in inset until h - inset) { add(px[y * w + inset]); add(px[y * w + w - 1 - inset]) }
    }.filter { (it ushr 24) > 200 }.ifEmpty { listOf(px[px.size / 2]) }
    fun median(f: (Int) -> Float) = edge.map(f).sorted()[edge.size / 2]
    val bg = floatArrayOf(median(::r), median(::g), median(::b))

    // Colour: saturation-weighted average.
    var sr = 0f; var sg = 0f; var sb = 0f; var sw = 0f
    for (c in px) { val s = sat(c); val wt = s * s; sr += r(c) * wt; sg += g(c) * wt; sb += b(c) * wt; sw += wt }
    val base = if (sw / px.size > 0.02f) floatArrayOf(sr / sw, sg / sw, sb / sw) else bg
    val mx = maxOf(base[0], base[1], base[2]).coerceAtLeast(0.001f)
    val tone = 0.2f / mx // a dark launch tone that keeps the hue
    val color = Color((base[0] * tone).coerceIn(0f, 1f), (base[1] * tone).coerceIn(0f, 1f), (base[2] * tone).coerceIn(0f, 1f))

    // Glyph: how far each pixel is from the background, as alpha.
    val out = IntArray(w * h)
    for (i in px.indices) {
        val c = px[i]
        if ((c ushr 24) < 128) continue
        val d = maxOf(kotlin.math.abs(r(c) - bg[0]), kotlin.math.abs(g(c) - bg[1]), kotlin.math.abs(b(c) - bg[2]))
        // Banners often have a soft gradient or panel behind the logo: only clear contrast counts.
        val a = ((d - 0.2f) / 0.25f).coerceIn(0f, 1f)
        out[i] = ((a * 255).toInt() shl 24) or 0xFFFFFF
    }
    // A logo is a small part of the art; if most of it differs from the "background" (photographic
    // art), there's no glyph to lift, and the window shows just its colour.
    val coverage = out.count { (it ushr 24) > 128 }.toFloat() / out.size
    if (coverage > 0.35f) return color to null
    val glyph = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    return color to glyph.asImageBitmap()
}
