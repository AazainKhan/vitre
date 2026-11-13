package dev.glasslauncher.home

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.em
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.focusable
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.Text
import dev.glasslauncher.app
import dev.glasslauncher.apps.AppEntry
import dev.glasslauncher.apps.LoadedTile
import dev.glasslauncher.data.BackgroundMode
import dev.glasslauncher.data.FeaturedSourceId
import dev.glasslauncher.data.LauncherConfig
import dev.glasslauncher.data.ThemeMode
import dev.glasslauncher.data.appKey
import dev.glasslauncher.dream.AerialActivity
import dev.glasslauncher.featured.CoverPreview
import dev.glasslauncher.featured.ExpandedShelf
import dev.glasslauncher.featured.ShelfTitle
import dev.glasslauncher.glass.BackdropState
import dev.glasslauncher.glass.GlassStyle
import dev.glasslauncher.glass.LocalBackdrop
import dev.glasslauncher.glass.MotionBackground
import dev.glasslauncher.glass.glass
import dev.glasslauncher.ui.FocusTile
import dev.glasslauncher.ui.KeyDirection
import dev.glasslauncher.ui.LocalMetrics
import dev.glasslauncher.ui.Motion
import dev.glasslauncher.ui.LocalPalette
import dev.glasslauncher.ui.LocalUiPrefs
import dev.glasslauncher.ui.Metrics
import dev.glasslauncher.ui.Palette
import dev.glasslauncher.ui.Safe
import dev.glasslauncher.ui.Shapes
import dev.glasslauncher.ui.Type
import dev.glasslauncher.ui.UiPrefs
import dev.glasslauncher.widgets.IdleState
import dev.glasslauncher.widgets.StatusPill
import dev.glasslauncher.widgets.rememberIdleState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class HomeRequest { Home, ControlCenter, AppSwitcher, TvSettings, Settings, Remotes, Downloads }

private const val SLIDE_MS = 9_000L
private const val SLIDE_QUIET_MS = 3_000L
/** How long a held scene waits for the new app's scene before it lets go anyway. */
private const val SCENE_HOLD_MAX_MS = 2_000L
/** Home has rested this long before the shader warm-up draws (P115). */
private const val SHADER_WARM_AFTER_MS = 4_000L
/** How dark the held scene goes (tvOS dims the outgoing art for about 230 ms before the new crossfade). */
private const val HOLD_DIM = 0.25f
/** How long the new app's scene may take before the old one is dimmed at all: a quick (cached) switch never dips (user, 2026-10-12). */
private const val HOLD_GRACE_MS = 700L
/** The longest the shelf's title waits for its art before it shows anyway. */
private const val SCENE_TITLE_WAIT_MS = 1_500L

/**
 * The shelf title's gate alpha. [gate] is an animated value, so it lags [ready] by a frame: on a focus change the new
 * app's title was drawn for that frame at the old app's full gate, before its background (P130). Reading [ready]
 * (computed in the same composition as the title) closes the gate in the same frame the title changes.
 */
internal fun titleGateAlpha(ready: Boolean, gate: Float): Float = if (ready) gate else 0f
/** The next slide's bake starts once input has been quiet this long (never during a scroll). */
private const val BAKE_AHEAD_QUIET_MS = 1_500L
private const val BAKE_LEAD_MS = 2_500L
/** Focus resting this long on a tray app shows its hero (browsing past apps doesn't bake). */
private const val APP_HERO_MS = 250L
/** P257b: how long without input before a hero's neighbours' art is prefetched (never during a move or a bake). */
private const val NEIGHBOUR_HEROES_MS = 1_000L
/** Log tag for hero-swap timing (P257b); silent unless enabled with `setprop log.tag.GlassHero DEBUG`. */
private const val HERO_LOG = "GlassHero"
private const val GPU_TRIM_AFTER_MS = 4_000L
/** P247: the shortest gap between two idle collections (an autoplay slide must not become a collection each). */
private const val GC_MIN_INTERVAL_MS = 20_000L
private const val APP_HERO_FADE_MS = 270
/** The Cover Flow preview fades in and out over this (with the backdrop's dissolve; P86). */
private const val PREVIEW_FADE_MS = 450
/** How long after Home leaves the full-screen Cover Flow the leftovers are released (after the morph and its frames). */
private const val COLLAPSE_TRIM_MS = 900L
/** The preview's fade-in waits for its soft scene to be on the backdrop, at most this long (P92). */
private const val SCENE_WAIT_MS = 1_500L
/** ...and this long shows its titles (tvOS 27: the shelf moves with the user first). */
private const val TITLES_AFTER_MS = 1_500L
/** Now Playing stays on Home this long after playback stops (track changes, short pauses). */
private const val NOW_PLAYING_LINGER_MS = 3_000L
private const val NOW_PLAYING_PAUSED_MS = 10 * 60_000L
/** Glass texture fade-in after a scroll back to the top lands. */
private const val TEXTURE_IN_MS = 220

const val SETTINGS_TILE_KEY = "glass:settings"

@OptIn(ExperimentalFoundationApi::class)
private object NoAutoScroll : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f
}

/**
 * Home, laid out like tvOS 27: the featured artwork (or wallpaper, or Aerial video) fills the whole
 * screen, the top app row sits in a glass tray over it, the grid scrolls up over a blurred copy, and
 * pressing Up from the tray opens the featured shelf full screen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(model: HomeModel, homePresses: Flow<HomeRequest>) {
    val context = LocalContext.current
    val graph = context.app
    val view = LocalView.current
    val cfg by model.config.collectAsStateWithLifecycle()
    val layout by model.layout.collectAsStateWithLifecycle()
    val backdrop = remember { BackdropState() }
    val dark = when (cfg.theme) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> isSystemInDarkTheme()
    }
    val prefs = remember(cfg) { UiPrefs.resolve(context, cfg) }
    val metrics = remember(cfg.textScale) { Metrics(cfg.textScale) }
    val palette = remember(backdrop.backdrop, dark, prefs) { Palette(light = !dark, highContrast = prefs.highContrast) }
    backdrop.reduceTransparency = prefs.reduceTransparency
    backdrop.light = !dark
    Type.bold = cfg.boldText
    Type.font = cfg.font

    var lastDockFocused by remember { mutableStateOf<String?>(null) }
    // Featured content drives the live backdrop. In "Focused app" mode the shelf follows the focused
    // top-row app once focus rests on it (browsing along the row doesn't fetch and bake every app).
    var shelfApp by remember { mutableStateOf<String?>(null) }
    // tvOS 27's Top Shelf moves with the user: the focused tray app's own hero within a beat (cached,
    // cheap), its titles only once focus has rested there. Browsing along the row never bakes titles.
    var heroApp by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(lastDockFocused) {
        val pkg = lastDockFocused?.removePrefix("app:") ?: return@LaunchedEffect
        delay(APP_HERO_MS)
        heroApp = pkg
    }
    var dwelled by remember { mutableStateOf(false) }
    LaunchedEffect(heroApp) {
        dwelled = false
        if (heroApp == null) return@LaunchedEffect
        delay(TITLES_AFTER_MS)
        dwelled = true
    }
    val appsWithRows by produceState(emptySet<String>(), layout.loaded) { value = dev.glasslauncher.featured.TvRows.packagesWithRows(context) }
    // Show Titles: Never means no title fetch at all (the shelf is only ever the app's hero).
    val featuredCfg = remember(cfg.featured, shelfApp, appsWithRows, cfg.topShelfTitles) {
        if (!cfg.topShelfTitles) null else dev.glasslauncher.featured.AppSources.effective(cfg.featured, shelfApp, appsWithRows)
    }
    LaunchedEffect(featuredCfg, cfg.addonsEnabled, cfg.addonSettings) {
        featuredCfg?.let { graph.featured.refresh(it, addons = dev.glasslauncher.addons.AddonSnapshot(cfg.addonsEnabled, cfg.addonSettings)) }
    }
    // Add-ons' Home rows: read when Home opens and when an add-on or its settings change (then at most every declared interval, 15 min or more).
    LaunchedEffect(cfg.addonsEnabled, cfg.addonSettings) { dev.glasslauncher.addons.AddonSurfaces.refreshRows(context, dev.glasslauncher.addons.AddonSnapshot(cfg.addonsEnabled, cfg.addonSettings)) { stale -> model.edit { c -> c.copy(addonSettings = c.addonSettings - stale) } } }
    val featuredState by graph.featured.state.collectAsStateWithLifecycle()
    // The previous app's feed stands while this app's load is under way (the cached path: its feed lands in a frame or
    // two and the art crossfades straight in), but not once that load has settled without a feed of its own (offline):
    // then it is not the shelf's, and the app's hero and the dim hold take over (P113).
    val feed = featuredState.feed?.takeIf {
        featuredCfg != null && it.items.isNotEmpty() &&
            dev.glasslauncher.featured.ShelfFeed.usable(featuredState.key, dev.glasslauncher.featured.cacheKey(featuredCfg), featuredState.settledKey)
    }
    // Focus has left the app whose feed this is: its Cover Flow and slideshow stop stepping at once (the feed stays for
    // the exit).
    val focusLeftShelf = dev.glasslauncher.featured.ShelfFeed.focusLeft(
        focusedApp = lastDockFocused?.removePrefix("app:"), shelfApp = shelfApp,
        followsFocus = cfg.featured.mode == dev.glasslauncher.data.FeaturedMode.FocusedApp && cfg.topShelfTitles,
    )
    // A different shelf starts from its first item; a shelf of square covers (Spotify's Cover Flow preview) from the
    // middle one, so there are covers on both sides (P85). Keyed, so the first frame already has it (no jump).
    // Keyed by the state's feed, not the gated one: the feed going away (offline) must not reset the index under the exit.
    val stateFeed = featuredState.feed?.takeIf { it.items.isNotEmpty() }
    var heroIndex by remember(stateFeed?.items?.firstOrNull()?.id) {
        mutableIntStateOf(if (stateFeed != null && dev.glasslauncher.featured.CardShape.square(stateFeed.items)) dev.glasslauncher.featured.CoverAutoplay.startIndex(stateFeed.items.size) else 0)
    }
    // The preview's stepping direction (back and forth, never a sweep from the last cover to the first).
    var coverDir by remember { mutableIntStateOf(1) }
    val idle = rememberIdleState(cfg.idleFadeMinutes)
    // P257b: while focus rests on an app's hero, its tray neighbours' hero art is written to the disk cache (rendering
    // one took ~565 ms of a cold app-hero swap on the stick). Only after NEIGHBOUR_HEROES_MS with no input, so it never
    // competes with a move; nothing is kept in memory, each bitmap is dropped once it is on disk.
    LaunchedEffect(heroApp) {
        val pkg = heroApp ?: return@LaunchedEffect
        val i = layout.dock.indexOfFirst { it.packageName == pkg }.takeIf { it >= 0 } ?: return@LaunchedEffect
        for (n in listOfNotNull(layout.dock.getOrNull(i + 1), layout.dock.getOrNull(i - 1))) {
            while (idle.millisSinceInput() < NEIGHBOUR_HEROES_MS) delay(NEIGHBOUR_HEROES_MS - idle.millisSinceInput())
            kotlinx.coroutines.withContext(dev.glasslauncher.glass.WallpaperLoader.BakeDispatcher) { graph.tileArt.prefetchHeroArt(n) }
        }
    }
    // What follows the focused item (backdrop, title, synopsis) is the settled one: while a key is held it waits for the repeats to
    // stop, so a long scroll bakes and swaps once, not per item (P158). A single press settles in the same frame.
    val heroSettle = remember(stateFeed?.items?.firstOrNull()?.id) { dev.glasslauncher.featured.HeroSettle(heroIndex) }
    // A press is not a hold: then the focus index is used as it is, with no state written, no effect launched and nothing read that a
    // later write could invalidate (the dock route's autoplay steps are presses). Only during a hold does Home read the settled index,
    // and the timer's single write after the repeats rest is the one recomposition that swaps the hero (P158).
    val heldKey = dev.glasslauncher.featured.HoldSettle.holding(idle.millisSinceRepeat())
    val shownIndex = heroSettle.shown(heldKey, heroIndex)
    if (heldKey) LaunchedEffect(heroIndex) {
        delay(dev.glasslauncher.featured.HoldSettle.SETTLE_MS)
        heroSettle.settle(heroIndex)
    }
    val hero = feed?.items?.getOrNull(shownIndex.coerceIn(0, (feed.items.size - 1).coerceAtLeast(0)))
    val wallpaper = if (dark) cfg.wallpaperDark else cfg.wallpaperLight

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val overlays = remember { mutableStateListOf<Overlay>() }
    val trayInMain = overlays.isNotEmpty() || !TrayHost.split
    androidx.compose.runtime.SideEffect { if (TrayHost.inMain != trayInMain) TrayHost.inMain = trayInMain }
    var moving by remember { mutableStateOf<String?>(null) }
    // Settings was opened from its tile (with the zoom): its close plays back into that tile.
    var settingsFromTile by remember { mutableStateOf(false) }
    val settingsEntry = remember { dev.glasslauncher.apps.AppEntry("dev.glasslauncher.settings", "Settings", android.content.ComponentName("dev.glasslauncher", "dev.glasslauncher.MainActivity")) }
    val gearGlyph = remember {
        runCatching {
            val d = androidx.core.content.ContextCompat.getDrawable(context, dev.glasslauncher.R.drawable.ic_settings)!!
            val b = android.graphics.Bitmap.createBitmap(192, 192, android.graphics.Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, 192, 192); d.setTint(android.graphics.Color.WHITE); d.draw(android.graphics.Canvas(b))
            b.asImageBitmap()
        }.getOrNull()
    }
    // The app being rearranged inside the open folder (Rearrange Folder in its app menu).
    var folderMoving by remember { mutableStateOf<String?>(null) }
    // While an app is being moved the layout is edited in memory and saved once, when the move ends.
    val inMove = moving != null || folderMoving != null
    LaunchedEffect(inMove) { if (inMove) model.beginMove() else model.endMove() } // begun at once by the entry points below
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { model.endMove() } }
    LaunchedEffect(overlays.none { it is Overlay.FolderOpen }) { if (overlays.none { it is Overlay.FolderOpen }) folderMoving = null }
    var lastFocused by remember { mutableStateOf<String?>(null) }
    // Whether any tile, the pill or the hero holds focus (the root's own focus state).
    var rootFocused by remember { mutableStateOf(true) }
    // The list swaps layouts a tile's fade after the change landed (P62): positions shift under the focused tile,
    // so focus is put back on what it was on when that happens.
    var heldFocus by remember { mutableStateOf<String?>(null) }
    var swaps by remember { mutableIntStateOf(0) }
    var focusedRow by remember { mutableIntStateOf(1) }
    val expand = remember { Animatable(0f) }
    var expanded by remember { mutableStateOf(false) }
    var shelfSheet by remember { mutableStateOf(false) }
    var pillFocused by remember { mutableStateOf(false) }
    val menuAnchor = remember { MenuAnchor() }
    val dissolve = remember { dev.glasslauncher.ui.ScreenDissolve(context, scope) }
    var homeFocused by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val layer = rememberGraphicsLayer()
    val transition = remember { AppTransition(context, scope, backdrop, model) }
    // Read each app's starting-window colour ahead of its launch: the first read (createPackageContext) held the zoom's
    // first frame back by up to ~350 ms on the stick (P149).
    LaunchedEffect(layout.installed) { transition.prewarm(layout.installed) }
    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }
    val cardRequester = remember { FocusRequester() }
    val sectionCursor = remember { dev.glasslauncher.featured.SectionCursor() }
    // Now Playing: Up from the tray reaches its controls, Up again the status pill (Control Center).
    val heroPlay = remember { FocusRequester() }
    var heroFocused by remember { mutableStateOf(false) }
    // The Now Playing scrubber (above the buttons) has focus: Down goes to the buttons, not on to the tray.
    var heroScrubFocused by remember { mutableStateOf(false) }
    val pillRequester = remember { FocusRequester() }

    // Now Playing takes over the top shelf while music plays: the artwork becomes the backdrop (baked
    // like any slide) and the track replaces the shelf title. A pause or gap shorter than 3 s doesn't
    // flip Home back and forth.
    val nowPlaying by (dev.glasslauncher.widgets.NowPlayingFixture.state ?: graph.nowPlaying.state).collectAsStateWithLifecycle()
    // Playing now is decided in this very composition (not a LaunchedEffect hop later), so Home opening with music up
    // draws Now Playing on its first frame, not the Cover Flow for a frame and then a swap (P142). `held` only carries
    // the pause/gap linger.
    var held by remember { mutableStateOf(nowPlaying?.takeIf { cfg.showNowPlaying && it.playing }) }
    val takeover = nowPlaying?.takeIf { cfg.showNowPlaying && it.playing } ?: held
    LaunchedEffect(heroApp, cfg.featured.mode, cfg.topShelfTitles, takeover != null) {
        if (cfg.featured.mode != dev.glasslauncher.data.FeaturedMode.FocusedApp || !cfg.topShelfTitles) return@LaunchedEffect
        // While Now Playing has the shelf, moving along the tray doesn't fetch each app's titles (perf: 19%
        // janky frames browsing with music up); the shelf catches up when the music stops.
        if (takeover != null) return@LaunchedEffect
        shelfApp = heroApp
    }
    LaunchedEffect(nowPlaying, cfg.showNowPlaying) {
        val np = nowPlaying
        val current = held
        when {
            cfg.showNowPlaying && np != null && np.playing -> held = np
            // Paused from the hero (or anywhere): the same track keeps Home for a while, controls and all.
            cfg.showNowPlaying && np != null && current != null && np.key == current.key -> {
                held = np
                delay(NOW_PLAYING_PAUSED_MS)
                held = null
            }
            current != null -> { delay(NOW_PLAYING_LINGER_MS); held = null }
        }
    }
    // The app's own hero shows until its titles are due: after a dwell, with titles on, and some to show.
    // A shelf app known to be square covers (Spotify, learned once it has rested and kept): its soft scene and Cover
    // Flow preview come straight away, one dissolve from the previous backdrop, not the app's flat hero first and
    // the covers popping in 1.5 s later (P86).
    val coverApps = remember {
        androidx.compose.runtime.mutableStateSetOf<String>().also { it.addAll(context.getSharedPreferences("home", 0).getStringSet("coverApps", emptySet()).orEmpty()) }
    }
    val feedIsCovers = feed != null && dev.glasslauncher.featured.CardShape.square(feed.items)
    val coverEarly = feedIsCovers && heroApp != null && heroApp in coverApps
    LaunchedEffect(dwelled, feedIsCovers, shelfApp) {
        val app = shelfApp
        if (dwelled && feedIsCovers && app != null && app == heroApp && app !in coverApps) {
            coverApps += app
            context.getSharedPreferences("home", 0).edit().putStringSet("coverApps", coverApps.toSet()).apply()
        }
    }
    // An app whose feed has been shown before is a known quantity (its titles are on disk and in memory): its real art is
    // what the shelf shows after the same short beat as the app hero, not a logo hero first and the art 1.5 s later
    // (two swaps, P105 #12 follow-up). Learned once the feed has rested and kept, like the cover apps above.
    val feedApps = remember {
        androidx.compose.runtime.mutableStateSetOf<String>().also { it.addAll(context.getSharedPreferences("home", 0).getStringSet("feedApps", emptySet()).orEmpty()) }
    }
    val feedEarly = !feedIsCovers && feed != null && heroApp != null && shelfApp == heroApp && heroApp in feedApps
    LaunchedEffect(dwelled, feed != null, shelfApp) {
        val app = shelfApp
        if (dwelled && feed != null && app != null && app == heroApp && app !in feedApps && !feedIsCovers) {
            feedApps += app
            context.getSharedPreferences("home", 0).edit().putStringSet("feedApps", feedApps.toSet()).apply()
        }
    }
    val titlesDue = cfg.topShelfTitles && feed != null && (dwelled || expanded || coverEarly || feedEarly)
    // The shelf app's own glyph for the Cover Flow's title (decoded once per app, small).
    val shelfIcon by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, shelfApp) {
        value = shelfApp?.let { pkg ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val d = context.packageManager.getApplicationIcon(pkg)
                    val b = android.graphics.Bitmap.createBitmap(52, 52, android.graphics.Bitmap.Config.ARGB_8888)
                    d.setBounds(0, 0, 52, 52); d.draw(android.graphics.Canvas(b))
                    b.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    val appHeroShown = cfg.background == BackgroundMode.Featured && takeover == null && !expanded && heroApp != null && !titlesDue
    val appHeroPkg = heroApp.takeIf { appHeroShown }
    val appHeroes = HeroCache.app
    // Apps whose hero is full-screen art (no logo plate drawn over it).
    val artApps = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    val sceneUrl = if (cfg.background == BackgroundMode.Featured && !appHeroShown) hero?.image else null
    // The scene (art) the backdrop has installed for the shelf's current item: the title and logo wait for it, so they
    // and their art come in together instead of the title showing alone over the previous scene (P107). A scene that
    // never arrives (offline, art that fails) lets the title in after 1.5 s.
    var sceneShownFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sceneUrl) { if (sceneUrl != null) { delay(SCENE_TITLE_WAIT_MS); sceneShownFor = sceneUrl } }
    val titleReady = (sceneUrl == null || sceneShownFor == sceneUrl) &&
        dev.glasslauncher.featured.ShelfFeed.titleBelongsToFocus(
            focusLeftShelf, feed != null, featuredState.key, featuredCfg?.let { dev.glasslauncher.featured.cacheKey(it) },
        )
    // The app whose scene the backdrop shows now. While focus has moved to another app and its scene hasn't arrived
    // (uncached art, a slow bake), the previous app's scene is held dimmed instead of standing in as if it were the new
    // app's (P105 #12 follow-up); it undims as the new scene dissolves in. A scene that never comes lets go after 2 s.
    var sceneOfApp by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(heroApp) { if (heroApp != null) { delay(SCENE_HOLD_MAX_MS); sceneOfApp = heroApp } }
    // The hold's dim rides the backdrop state itself (sceneDim), so the glass over it (tray, pill) dims with the scene and
    // stays one material; written from the animation, not read in composition (no recomposition per frame).
    // Not at once: moving along the tray, the scene is usually there within a beat (cached art), and a dip to dark for it
    // read as a black flash on every switch. Only a scene that is really late dims the old one.
    var holdArmed by remember { mutableStateOf(false) }
    LaunchedEffect(heroApp, sceneOfApp, takeover != null) {
        holdArmed = false
        if (takeover == null && heroApp != null && sceneOfApp != null && sceneOfApp != heroApp) { delay(HOLD_GRACE_MS); holdArmed = true }
    }
    val holdTarget = if (holdArmed && dev.glasslauncher.featured.ShelfFeed.holds(heroApp, sceneOfApp, cfg.background == BackgroundMode.Featured, prefs.reduceMotion, nowPlaying = takeover != null)) HOLD_DIM else 0f
    val holdAnim = remember { Animatable(0f) }
    // After the first frame following the focus change (the stall there ate the tween's start on the stick: the dim landed
    // in three frames, a cut), over 300 ms so it is seen for at least 150 ms.
    LaunchedEffect(holdTarget) {
        androidx.compose.runtime.withFrameNanos { }
        holdAnim.animateTo(holdTarget, tween(300)) { backdrop.sceneDim = value }
    }
    val titleGate by androidx.compose.animation.core.animateFloatAsState(
        if (titleReady) 1f else 0f,
        if (titleReady && !prefs.reduceMotion) androidx.compose.animation.core.tween(Motion.OVERLAY_MS) else androidx.compose.animation.core.snap(),
        label = "title-gate",
    )
    // The slideshow bakes the next slide before switching, so its art and title dissolve in together.
    val prebaked = remember { arrayOfNulls<Pair<String, dev.glasslauncher.glass.Backdrop>>(1) }
    // The Top Shelf scene last shown (P257): coming back to it from an app's hero is a swap, not a bake again. At rest it is
    // the backdrop on screen itself; it is an extra scene in memory only while an app's hero stands in for it.
    val lastScene = remember { arrayOfNulls<Pair<String, dev.glasslauncher.glass.Backdrop>>(1) }
    // The app's own icon (no cover published) is no backdrop: it would bake a big blurred logo (Spotify: green).
    val takeoverArt = takeover?.takeIf { !it.artIsIcon }?.art
    // An expanded shelf of square covers (Spotify's albums and playlists) is a Cover Flow (P33): its background is
    // the focused cover, softly blurred and glossy, not the art drawn sharp.
    val coverShelf = expanded && feed != null && dev.glasslauncher.featured.CardShape.square(feed.items)
    // Square covers are too small to fill the screen sharp (a 640 px cover is 3x at 1080p: pixelated, P79), at rest
    // as much as in the Cover Flow: they get the soft cover backdrop under the same cache key, so opening or closing
    // the Cover Flow re-bakes and dissolves nothing.
    val softFeed = feed != null && dev.glasslauncher.featured.CardShape.square(feed.items)
    val coverBackdrop = coverShelf || softFeed
    // The cover SHELF (identified by its first item) whose soft backdrop has come up: the preview's fade-in on ARRIVAL
    // waits for it. Per shelf, not per cover: stepping through the covers must never hide and re-create the preview
    // (its pool would be released and re-decoded every step: the +15 MB the bisect found, and a flicker).
    val shelfKey = feed?.items?.firstOrNull()?.id
    var coverSceneFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(shelfKey) { delay(SCENE_WAIT_MS); if (coverSceneFor != shelfKey) coverSceneFor = shelfKey }
    // A Cover Flow whose cover couldn't be fetched shows the wallpaper; it bakes again when the network returns (P65).
    val coverNetwork = if (coverBackdrop) dev.glasslauncher.ui.NetworkEpoch.value else 0
    LaunchedEffect(sceneUrl, wallpaper, cfg.background, dark, takeoverArt, appHeroPkg, coverBackdrop, coverNetwork) {
        if (takeoverArt != null) {
            backdrop.glassFades = true
            backdrop.swap(graph.wallpapers.fromImage(dev.glasslauncher.widgets.backdropArt(takeoverArt), light = !dark), animate = backdrop.backdrop != null)
            return@LaunchedEffect
        }
        if (appHeroPkg != null) {
            // The app's update time is part of the key: an updated app gets a hero drawn from its new art.
            val key = "$appHeroPkg|${layout.installed.firstOrNull { it.packageName == appHeroPkg }?.updated ?: 0L}|$dark"
            val inMemory = appHeroes[key] != null
            val baked = appHeroes[key] ?: run {
                // The app's logo art full screen: Amazon's Fire TV icon, else its own banner (or icon) drawn sharp.
                val art = dev.glasslauncher.apps.AppArt.url(context, appHeroPkg)
                    ?.let { runCatching { graph.wallpapers.heroFromUrl(it, light = !dark) }.getOrNull() }
                    ?: appHeroBackdrop(graph, layout, appHeroPkg, dark)
                artApps[appHeroPkg] = art != null
                art
            }?.also { appHeroes[key] = it }
            // A short fade (APP_HERO_FADE_MS): the tray and pill dissolve with the backdrop on the same clock
            // (state.fade), else their glass changes colour before the background does. Only those two draw
            // twice, only for these 200 ms.
            if (baked != null) {
            // Off unless `setprop log.tag.GlassHero DEBUG`: the fade start on the uptime clock, for timing hero swaps from a key (P257b).
            if (android.util.Log.isLoggable(HERO_LOG, android.util.Log.DEBUG)) android.util.Log.d(HERO_LOG, "swap $appHeroPkg at ${android.os.SystemClock.uptimeMillis()} inMemory=$inMemory")
            backdrop.glassFades = true
            backdrop.swap(baked, animate = backdrop.backdrop != null, fadeMs = APP_HERO_FADE_MS); sceneOfApp = appHeroPkg; return@LaunchedEffect }
        }
        if (cfg.background == BackgroundMode.Motion) return@LaunchedEffect
        // The featured feed is usually a few ms behind the first composition: wait for it rather than
        // baking the wallpaper only to throw it away (the two bakes used to run in parallel at startup).
        if (sceneUrl == null && cfg.background == BackgroundMode.Featured && featuredCfg != null && backdrop.backdrop == null) delay(1_200)
        val ready = prebaked[0]?.takeIf { it.first == sceneUrl && !coverBackdrop }?.second?.takeIf { it.isLight == !dark }
        prebaked[0] = null
        // A Cover Flow step whose cover was baked ahead (the neighbours are, below) shows at once.
        // Square covers (P79b): never a full-screen picture, at rest or expanded: the cover's own soft colour
        // scene, under the same key, so opening the Cover Flow re-bakes and dissolves nothing.
        val coverReady = if (coverBackdrop) sceneUrl?.let { HeroCache.cover["$it|$dark"] } else null
        // Let quick left/right browsing settle before re-baking the glass (a Cover Flow scrolls in quick steps).
        if (ready == null && coverReady == null && expanded) delay(if (coverBackdrop) COVER_SETTLE_MS else 220)
        backdrop.glassFades = true
        val sceneKey = "$sceneUrl|$dark|$coverBackdrop"
        val next = ready ?: coverReady ?: lastScene[0]?.takeIf { it.first == sceneKey && sceneUrl != null }?.second ?: sceneUrl?.let { url ->
            runCatching {
                if (coverBackdrop) graph.wallpapers.coverFromUrl(url, light = !dark)?.also { HeroCache.cover["$url|$dark"] = it }
                else graph.wallpapers.fromUrl(url, light = !dark)
            }.getOrNull()
        } ?: graph.wallpapers.load(wallpaper, light = !dark)
        // A dissolve, never a cut; it's also what Reduce Motion asks for instead of movement. (The same scene again, as
        // when the network returns with the cover already showing, is left as it is.)
        lastScene[0] = sceneKey to next
        if (next !== backdrop.backdrop) backdrop.swap(next, animate = backdrop.backdrop != null)
        // The shelf's title and logo may come in now: the art they sit on is on screen (P107).
        sceneShownFor = sceneUrl
        sceneOfApp = shelfApp ?: heroApp
        // The preview's covers may show now: their scene is the backdrop (P92).
        if (coverBackdrop) coverSceneFor = feed?.items?.firstOrNull()?.id
        // Cover Flow: bake the covers either side while this one rests, so the next step needn't wait.
        if (coverShelf && feed != null) {
            for (i in listOf(heroIndex + 1, heroIndex - 1)) {
                val url = feed.items.getOrNull(i)?.image ?: continue
                if (HeroCache.cover["$url|$dark"] != null) continue
                runCatching { graph.wallpapers.coverFromUrl(url, light = !dark) }.getOrNull()?.let { HeroCache.cover["$url|$dark"] = it }
            }
            // Keep the one showing: the neighbours just baked mustn't push it out of the three.
            sceneUrl?.let { url -> HeroCache.cover["$url|$dark"]?.let { HeroCache.cover["$url|$dark"] = it } }
        }
    }

    // Back from the full-screen Cover Flow (P92): what browsing left behind goes: the neighbours' baked scenes (only
    // the showing one is kept), and the full flow's cover bitmaps (its pool is released as the flow leaves). A GC hint
    // a moment later lets the freed native pixels go (they are only released when the collector runs).
    LaunchedEffect(expanded) {
        if (expanded) return@LaunchedEffect
        delay(COLLAPSE_TRIM_MS)
        if (!expanded && expand.value == 0f) {
            HeroCache.cover.keepOnly(sceneUrl?.let { "$it|$dark" })
            dev.glasslauncher.featured.CoverArtCache.Flow.release()
            dev.glasslauncher.ui.GpuCaches.trim()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { System.gc() }
        }
    }
    // Once Home has settled on a scene, the renderer's copies of the scenes it has left are released
    // (see GpuCaches): they otherwise pile up while browsing titles and app heroes. Not on Control Center opening or
    // closing (P197): it leaves no scene behind, and the trim (UI_HIDDEN in HWUI) dropped its shader programs, so
    // every open reloaded ~50-70 of them (3 ms each) from the disk cache.
    // P205: once Home has stayed on a scene for a moment (focus rests, before a scroll to the grid can start), its blur
    // ladder goes to the GPU, off the main thread. Moving on cancels it, so a quick pass along the dock uploads nothing.
    LaunchedEffect(backdrop.backdrop) { val b = backdrop.backdrop ?: return@LaunchedEffect; delay(LADDER_AFTER_MS); b.ensureLadder() }
    val lastCollectAt = remember { longArrayOf(0L) }
    LaunchedEffect(backdrop.backdrop, expanded, overlays.size) {
        delay(GPU_TRIM_AFTER_MS)
        dev.glasslauncher.ui.GpuCaches.trim()
        // P247: a hero visited while browsing the tray stayed on the GPU (7-9 MB of textures) long after Home settled
        // somewhere else. Keep the one on screen; the others re-bake from the hero disk cache when focus returns.
        dev.glasslauncher.home.HeroCache.app.retain(appHeroPkg)
        // P216: the scenes left behind are garbage whose native pixels go only when a collection runs (and their cleaners
        // after it): a heap dump after a dock pass found ~40 MB of unreachable bitmaps, and one collection took the grid's
        // PSS from ~133 to ~119 MB. Home is at rest here, so a collection costs nothing on screen.
        // P222 skipped it after the Top Shelf's own autoplay steps, because one collection per slide was ~2 % of a core.
        // P247: that left a Home nobody touches (a TV on the shelf for a few minutes) holding every slide it had drawn:
        // 58 MB of graphics against the same screen's 36 MB after a collection. Collect at rest too, but at most once
        // every [GC_MIN_INTERVAL_MS], with no fade running and no key in the last few seconds (never mid-scroll).
        val now = android.os.SystemClock.uptimeMillis()
        if (backdrop.previous == null &&
            idle.millisSinceInput() > 3_000L && now - lastCollectAt[0] > GC_MIN_INTERVAL_MS
        ) {
            lastCollectAt[0] = now
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { System.gc(); System.runFinalization(); System.gc() }
        }
    }

    // Loading state: Home stays hidden until its first backdrop is baked and the app list is in, then
    // fades in whole, instead of assembling piece by piece (black screen, bare tray, tiles jumping).
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withTimeoutOrNull(8_000) {
            snapshotFlow { layout.loaded && (backdrop.backdrop != null || cfg.background == BackgroundMode.Motion) }.first { it }
            withFrameNanos { }
        }
        ready = true
        // Startup metric: the system logs "Fully drawn" (time to real content, not the first frame).
        runCatching { (view.context as? android.app.Activity ?: (context as? android.app.Activity))?.reportFullyDrawn() }
        // P204: the app menu's glyphs, rasterized once now so its first frame doesn't make them.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { MenuIcons.warm(context.applicationContext) }
    }
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(ready) { if (ready) reveal.animateTo(1f, tween(if (prefs.reduceMotion) 150 else 450)) }
    // P203: Control Center is composed once, hidden, after Home has appeared, and kept between opens (as its overlay
    // window does): composing its ~20 controls from scratch made its first frame ~110 ms on the stick, a visible
    // stall. While parked it is "closed" (exiting, inactive): invisible, unfocusable, out of the semantics tree, and
    // nothing in it runs (ControlCenter gates its effects on being open).
    var ccParked by remember { mutableStateOf(false) }
    LaunchedEffect(ready) { if (ready) { delay(CC_PARK_AFTER_MS); ccParked = true } }

    fun open(overlay: Overlay) {
        scope.launch {
            // The app switcher draws Home's baked blur instead; capturing the screen was most of its open cost.
            // A scene cross-fade still running would be frozen half-way in the capture while Control Center's glass and its
            // chrome patch sample the incoming scene: tiles tinted by the next hero and a mismatched corner (P320). End it first.
            if (overlays.isEmpty() && backdrop.previous != null) { backdrop.fade.snapTo(1f); withFrameNanos { } }
            // Control Center draws only the sharp capture: it opens on that, and the blurs land while it grows (P316).
            var added = false
            val early: ((androidx.compose.ui.graphics.ImageBitmap) -> Unit)? = if (overlay == Overlay.ControlCenter) { sharp -> backdrop.overlaySharp = sharp; overlays.add(overlay); added = true } else null
            if (overlays.isEmpty() && overlay != Overlay.AppSwitcher) captureOverlay(view, backdrop.light, keepSharp = overlay == Overlay.ControlCenter || overlay is Overlay.FolderOpen, onSharp = early).let {
                // Closed before its blurs came in: don't hold them (the close already let go of the captures).
                if (!added || overlay in overlays) { backdrop.overlay = it.frosted; backdrop.overlaySoft = it.soft }
                if (!added) backdrop.overlaySharp = it.sharp
            }
            if (!added) overlays.add(overlay)
        }
    }
    // Overlays on their way out (exit animation), drawn above the rest until it finishes.
    val exiting = remember { mutableStateListOf<Overlay>() }
    var dismissGen by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // Settings (opened out of its tile) is gone: its window comes back full screen and shrinks into the tile (P105 #7).
    val settingsUp = overlays.any { it === Overlay.Settings }
    LaunchedEffect(settingsUp) {
        if (!settingsUp && settingsFromTile) {
            settingsFromTile = false
            val b = menuAnchor.bounds
            val tile = b?.let { val dx = it.width * 0.1f; val dy = it.height * 0.1f; Rect(it.left - dx, it.top - dy, it.right + dx, it.bottom + dy) }
            transition.closePage(tile)
        }
    }
    fun dismiss(overlay: Overlay) {
        exiting.add(overlay)
        val gen = ++dismissGen
        scope.launch {
            // Control Center plays its bubble collapse; the others a short fade.
            delay(if (overlay == Overlay.ControlCenter) CcMorph.CLOSE_MS + 40L else Motion.OVERLAY_MS + 40L)
            // A close that was reversed and closed again is a newer one: it removes its own entry, not this older timer.
            if (gen != dismissGen && overlay === Overlay.ControlCenter) return@launch
            exiting.remove(overlay)
            // Nothing open any more: let go of the screen captures (two full-screen images were held
            // after Control Center closed: perf-gate PSS 131 MB, over budget).
            if (overlays.isEmpty() && exiting.isEmpty()) {
                backdrop.overlay = null; backdrop.overlaySoft = null; backdrop.overlaySharp = null
            }
        }
    }
    fun closeTop() { overlays.removeLastOrNull()?.let(::dismiss) }
    /** Closes everything; only the top overlay is visible, so only it animates out. */
    fun closeAll() { overlays.lastOrNull()?.let(::dismiss); overlays.clear() }
    // AirPlay's pairing code: a card of Glass's own while PhairPlay says a code is up (it ends with the close event, the TTL or Back).
    val airPlayHub = remember { dev.glasslauncher.system.AirPlay.hub(context) }
    val airPlayCode = airPlayHub.ui.collectAsStateWithLifecycle().value.pairingCode
    // A text field open keeps the card waiting behind it (it would take the keyboard's focus); another app in front gets the banner instead.
    val textUp = overlays.any { it is Overlay.TextInput || it is Overlay.PhoneSetup }
    LaunchedEffect(airPlayCode, textUp) {
        val where = dev.glasslauncher.system.AirPlayCodePlacement.decide(
            airPlayCode, glassInFront = dev.glasslauncher.system.RemoteWatch.homeInFront, textInputUp = textUp,
            bannerService = dev.glasslauncher.system.RemoteKeysService.instance != null,
        )
        val up = overlays.lastOrNull { it is Overlay.AirPlayCode } as? Overlay.AirPlayCode
        if (where == dev.glasslauncher.system.AirPlayCodePlacement.Where.Queued) return@LaunchedEffect
        if (up != null && up.code == airPlayCode) return@LaunchedEffect
        up?.let { overlays.remove(it); dismiss(it) }
        if (airPlayCode != null && where == dev.glasslauncher.system.AirPlayCodePlacement.Where.GlassCard) open(Overlay.AirPlayCode(airPlayCode) { airPlayHub.dismissPairing(airPlayCode) })
    }
    /**
     * Control Center, always in Home's own window (P307).
     *
     * The accessibility-hosted window is a second window, and a second window is a third layer once the native renderer
     * draws Home - the composition that cancels the renderer's gain. Home is on screen here, so it can draw CC itself;
     * the window stays for summoning CC over *another* app, where Home's window is not there to draw into.
     */
    fun openControlCenter() {
        // A window opened over Home earlier (from another app) has to go, or it sits on top of the in-window copy.
        dev.glasslauncher.system.RemoteKeysService.instance?.controlCenter?.hide()
        open(Overlay.ControlCenter)
    }
    /**
     * The Control Center key: one instance, reversed mid-motion, never stacked. Open → close it; closing → the same
     * panel turns back open (no new capture: the screen then holds the closing panel, and each press baked one more copy
     * of it into the background); otherwise it opens.
     */
    fun toggleControlCenter() {
        when {
            overlays.lastOrNull() === Overlay.ControlCenter -> { overlays.removeAt(overlays.lastIndex); dismiss(Overlay.ControlCenter) }
            Overlay.ControlCenter in exiting && overlays.isEmpty() -> { exiting.remove(Overlay.ControlCenter); overlays.add(Overlay.ControlCenter) }
            Overlay.ControlCenter in overlays || Overlay.ControlCenter in exiting -> Unit
            else -> { closeAll(); openControlCenter() }
        }
    }
    /**
     * Takes the accessibility window off screen once Control Center is drawn in Home's window (P307).
     *
     * Called from the overlay's own dispose as well as on an app change: closing CC does not reliably produce a
     * WINDOW_STATE_CHANGED, so relying on that alone left the parked window attached (and a layer) after every close.
     */
    fun dropControlCenterWindow() {
        dev.glasslauncher.system.RemoteKeysService.instance?.controlCenter?.detach()
    }
    fun firstKey(): String? = layout.dock.firstOrNull()?.let { appKey(it.packageName) } ?: layout.grid.firstOrNull()?.key

    fun exists(key: String) = key == SETTINGS_TILE_KEY ||
        layout.dock.any { appKey(it.packageName) == key } || layout.grid.any { it.key == key }
    /** The tiles of the layout before the last change, by area (tray, grid): who a tile's neighbours were. */
    val before = remember { PreviousTiles() }

    /**
     * Where focus goes when [key] has left Home: the folder the app went into; else its neighbour where it stood
     * (the next tile in the same area, else the previous one), so Hide or Uninstall leaves focus in place and
     * only the gap closes; else the first app.
     */
    fun replacement(key: String): String? =
        layout.grid.firstOrNull { it is GridItem.FolderItem && it.apps.any { a -> appKey(a.packageName) == key } }?.key
            ?: before.neighbour(key, ::exists)
            ?: firstKey()

    fun tryFocus(key: String) = runCatching { requester(key).requestFocus(FocusDirection.Enter) }.getOrDefault(false)

    suspend fun focusKey(key: String?) {
        val target = key?.let { if (exists(it)) it else replacement(it) } ?: return
        // A few frames: when an overlay closes, the tray and grid move back to their own views (P248, P258) and attach a frame or two later.
        repeat(4) { withFrameNanos { }; if (tryFocus(target)) return }
        // Grid rows off screen aren't composed yet: bring the row in, then focus it.
        val index = layout.grid.indexOfFirst { it.key == target }.takeIf { it >= 0 }
            ?: if (target == SETTINGS_TILE_KEY) layout.grid.size else null
        if (index != null) {
            val within = GridHost.rowTops.getOrElse(index / metrics.columns) { 0 }
            listState.scrollToItem(2, within - with(density) { metrics.gridPivot.roundToPx() })
            withFrameNanos { }
            if (tryFocus(target)) return
        }
        firstKey()?.let { if (it != target) tryFocus(it) }
    }

    suspend fun scrollToTop() {
        if (listState.firstVisibleItemIndex == 0) {
            listState.animateScrollBy(-listState.firstVisibleItemScrollOffset.toFloat(), Motion.scroll())
        } else listState.animateScrollToItem(0)
    }

    fun onRowFocused(index: Int) {
        focusedRow = index
        scope.launch {
            launch {
                val target = if (index >= 2) 1f else 0f
                // The blur eases in over the scroll rather than riding its front-loaded curve: on a ladder of
                // baked steps, the scroll's fast start reads as the backdrop lurching.
                if (target > 0f) backdrop.textureIn.snapTo(0f)
                if (prefs.reduceMotion) backdrop.wallpaperBlur.snapTo(target) else backdrop.wallpaperBlur.animateTo(target, tween(520, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                // Landed at the top: the glass frosts back over the settled frame rather than snapping.
                if (target == 0f) { if (prefs.reduceMotion) backdrop.textureIn.snapTo(1f) else backdrop.textureIn.animateTo(1f, tween(TEXTURE_IN_MS)) }
            }
            if (index <= 1) scrollToTop() else {
                // The focused row's tiles settle at the pivot; the first row has the tray gap above it.
                val pad = if (index == 2) metrics.trayToGrid else 0.dp
                val target = with(density) { (metrics.gridPivot - pad).toPx() }
                // Grid rows share one item (P258): aim at the row's top inside it. Add-on rows follow as their own items.
                val n = GridHost.rowTops.size
                val (item, within) = if (index - 2 < n) 2 to GridHost.rowTops[index - 2] else (index - n + 1) to 0
                val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == item }
                // tvOS: full speed on the first frame, then exponential deceleration (not a spring from rest).
                if (info != null) listState.animateScrollBy(info.offset + within - target, Motion.scroll())
                else listState.animateScrollToItem(item, within - target.toInt())
            }
        }
    }

    fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        scope.launch {
            if (prefs.reduceMotion) expand.snapTo(if (value) 1f else 0f)
            else expand.animateTo(if (value) 1f else 0f, spring(dampingRatio = 0.9f, stiffness = 420f))
        }
        scope.launch {
            withFrameNanos { }
            if (value) runCatching { cardRequester.requestFocus() } else focusKey(lastDockFocused ?: firstKey())
        }
    }

    LaunchedEffect(layout.loaded) {
        if (!layout.loaded) return@LaunchedEffect
        focusKey(firstKey())
        if (!cfg.tipsSeen) {
            delay(1500) // let the backdrop draw so the card's glass has something to blur
            open(Overlay.Tips)
            // Seen once shown: Back closes the card too, and it shouldn't come back on every launch.
            model.edit { it.copy(tipsSeen = true) }
        }
    }
    // The top shelf advances on its own, to the right, at rest and in full screen, as on tvOS. Any
    // browsing restarts the timer (heroIndex is a key). In full screen focus follows, so the row glides.
    // It pauses while the grid is up (the backdrop is blurred there) and waits for a few quiet seconds,
    // so a slide's bake and cross-fade never land on top of a scroll.
    // Paused while Home is hidden (an app is in front): no bakes behind the app, and Home comes back on
    // the slide it left on, which is what the app-close animation's blurred picture shows.
    val homeVisible = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
    // Control Center over Home reuses Home's own glass (no screen capture needed).
    val homeScene = backdrop.backdrop
    // The Top Shelf of an add-on is shown (Home visible, focus on the shelf row): ask again; the repository's own interval (15 min or more) decides.
    LaunchedEffect(homeVisible, focusedRow == 1, featuredCfg) {
        val fc = featuredCfg
        if (homeVisible && focusedRow == 1 && fc?.source == dev.glasslauncher.data.FeaturedSourceId.Addon) graph.featured.refresh(fc, addons = dev.glasslauncher.addons.AddonSnapshot(cfg.addonsEnabled, cfg.addonSettings))
    }
    // A TV app's shelf (Spotify's Cover Flow) stays current (P153): re-read when Home comes back, when the app publishes new rows
    // (a content observer, settled for 3 s), and every five minutes while Home is up. The repository only fetches when its copy is
    // older than five minutes, so none of these can run more often than that. Without the EPG permission the observer is empty.
    // One observer and one timer per time Home is up, not per focused app: keyed on the focused app they were torn down and rebuilt
    // (content-provider binder calls) on every dock move, which the dock route paid per press.
    val tvShelfCfg by androidx.compose.runtime.rememberUpdatedState(featuredCfg?.takeIf { it.source == dev.glasslauncher.data.FeaturedSourceId.TvApp })
    val addonSnapNow by androidx.compose.runtime.rememberUpdatedState(dev.glasslauncher.addons.AddonSnapshot(cfg.addonsEnabled, cfg.addonSettings))
    LaunchedEffect(homeVisible) {
        if (!homeVisible) return@LaunchedEffect
        suspend fun reread() { tvShelfCfg?.let { graph.featured.refresh(it, addons = addonSnapNow) } }
        reread()
        kotlinx.coroutines.coroutineScope {
            launch { dev.glasslauncher.featured.TvRows.changes(context).collectLatest { delay(3_000); reread() } }
            launch { while (true) { delay(dev.glasslauncher.featured.FeaturedRepository.TV_MAX_AGE_MS); reread() } }
        }
    }
    LaunchedEffect(homeVisible, homeScene) { ControlCenterWindow.homeBackdrop = homeScene.takeIf { homeVisible } }
    // The effect above can't run once an app hides Home (no frames), so the lifecycle clears it on STOP.
    val latestScene = androidx.compose.runtime.rememberUpdatedState(homeScene)
    val homeLifecycle = LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(homeLifecycle) {
        val observer = ControlCenterWindow.homeLifecycleObserver { latestScene.value }
        homeLifecycle.addObserver(observer)
        onDispose { homeLifecycle.removeObserver(observer) }
    }
    LaunchedEffect(feed, heroIndex, expanded, overlays.isEmpty(), ControlCenterWindow.open, cfg.background, focusedRow >= 2, homeVisible, takeover != null, appHeroShown, prefs.reduceMotion, focusLeftShelf) {
        // Only while titles show: an app's own hero never advances or bakes slides; nor once focus has left the shelf's app.
        if (!homeVisible || takeover != null || appHeroShown || focusLeftShelf) return@LaunchedEffect
        // Paused under Control Center too: its translucent tiles would change colour with every slide.
        if (feed == null || feed.items.size < 2 || overlays.isNotEmpty() || ControlCenterWindow.open || cfg.background != BackgroundMode.Featured || focusedRow >= 2) return@LaunchedEffect
        // Full screen is browsed by hand: the row, details and backdrop follow focus, not a timer (advancing
        // moved the backdrop and details off the focused card).
        if (expanded) return@LaunchedEffect
        // The Cover Flow preview (P85): steps sooner (4 s after the last key, then every 3.5 s), one cover at a time,
        // back and forth; not at all with Reduce Motion (it would only jump).
        val covers = dev.glasslauncher.featured.CardShape.square(feed.items)
        if (covers && prefs.reduceMotion) return@LaunchedEffect
        // After a minute with no input the shelf holds on this item (P102): no bakes at all until a key resumes it.
        while (dev.glasslauncher.featured.TopShelfIdle.paused(idle.millisSinceInput())) delay(dev.glasslauncher.featured.TopShelfIdle.POLL_MS)
        val (stepTo, stepDir) = if (covers) dev.glasslauncher.featured.CoverAutoplay.next(heroIndex, coverDir, feed.items.size)
            else ((heroIndex + 1) % feed.items.size) to 1
        val next = stepTo
        val url = feed.items[next].image
        // The scene the next step shows, baked at background priority (so it never competes with a scroll or focus
        // move) and kept under the key the resting backdrop looks up (P79); nothing when it is already there.
        suspend fun bakeNext() {
            if (url == null) return
            if (covers) {
                if (HeroCache.cover["$url|$dark"] == null) runCatching { graph.wallpapers.coverFromUrl(url, light = !dark) }.getOrNull()?.let { HeroCache.cover["$url|$dark"] = it }
            } else if (prebaked[0]?.first != url) {
                runCatching { graph.wallpapers.fromUrl(url, background = true, light = !dark) }.getOrNull()?.let { prebaked[0] = url to it }
            }
        }
        // Bake the next scene while this one holds (P102), so the step at the end of the hold is only a swap and the
        // bake never lands on the dissolve; but not while a key is still moving things (a scroll or focus move).
        val holdStart = android.os.SystemClock.elapsedRealtime()
        val hold = if (covers) dev.glasslauncher.featured.CoverAutoplay.STEP_MS else SLIDE_MS
        // Baked late in the hold, not at its start (P247b): the next scene is a second full-screen backdrop, so it is
        // held for the last seconds only, and never through an idle pause.
        delay((hold - BAKE_LEAD_MS - (android.os.SystemClock.elapsedRealtime() - holdStart)).coerceAtLeast(0L))
        while (dev.glasslauncher.featured.TopShelfIdle.paused(idle.millisSinceInput())) delay(dev.glasslauncher.featured.TopShelfIdle.POLL_MS)
        while (idle.millisSinceInput() < BAKE_AHEAD_QUIET_MS) delay(BAKE_AHEAD_QUIET_MS - idle.millisSinceInput() + 50)
        bakeNext()
        delay((hold - (android.os.SystemClock.elapsedRealtime() - holdStart)).coerceAtLeast(0L))
        val quiet = if (covers) dev.glasslauncher.featured.CoverAutoplay.QUIET_MS else SLIDE_QUIET_MS
        // The hold may have crossed the minute: wait out the pause here too, then the usual few quiet seconds.
        while (dev.glasslauncher.featured.TopShelfIdle.paused(idle.millisSinceInput())) delay(dev.glasslauncher.featured.TopShelfIdle.POLL_MS)
        while (idle.millisSinceInput() < quiet) delay(quiet - idle.millisSinceInput() + 50)
        // Normally already baked; if a focus move consumed it, bake now.
        bakeNext()
        feed.items[next].logo?.let { logo -> runCatching { coil3.SingletonImageLoader.get(context).execute(dev.glasslauncher.featured.logoRequest(context, logo)) } }
        coverDir = stepDir
        heroIndex = next
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Coming back to Home (from an app, Aerials, a system screen): it settles in from slightly larger
    // instead of appearing in one frame, while the closing app animates away above it.
    val homeSettle = remember { Animatable(1f) }
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        var stopped = false
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> { stopped = true; transition.onHomeStopped() }
                androidx.lifecycle.Lifecycle.Event.ON_START -> if (stopped) {
                    stopped = false
                    // Back from an app Home opened: it shrinks into its tile instead (motion-spec §9).
                    if (!prefs.reduceMotion && transition.onHomeStarted { null }) return@LifecycleEventObserver
                    scope.launch {
                        if (prefs.reduceMotion) return@launch
                        homeSettle.snapTo(0f)
                        homeSettle.animateTo(1f, spring(dampingRatio = 1f, stiffness = 260f))
                    }
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(cfg.aerialsIdleMinutes, cfg.screensaverMode, cfg.screensaverDuringMusic) {
        val limit = if (cfg.screensaverMode != dev.glasslauncher.data.ScreensaverMode.System) cfg.aerialsIdleMinutes * 60_000L else 0L
        if (limit <= 0) return@LaunchedEffect
        while (true) {
            val remaining = limit - idle.millisSinceInput()
            if (remaining > 0) delay(remaining)
            else {
                // Not over Now Playing (the album art is Home's screensaver while music plays), unless Show
                // During Music is on.
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && overlays.isEmpty() && !ControlCenterWindow.open &&
                    (takeover == null || cfg.screensaverDuringMusic)) {
                    AerialActivity.start(context)
                }
                idle.touch()
            }
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { overlays.isEmpty() }.collect { empty ->
            if (empty) {
                // The tray and grid go back to their own views first: focusing now would land on their old nodes in Home's view,
                // which leave a frame later and take the focus with them (nothing focused, keys dead).
                snapshotFlow { TrayHost.inMain }.first { !it || !TrayHost.split }
                if (expanded) runCatching { cardRequester.requestFocus() } else focusKey(lastFocused ?: firstKey())
                // The leaving overlay's glass still samples the snapshot until its exit finishes.
                snapshotFlow { exiting.isEmpty() }.first { it }
                if (overlays.isEmpty()) backdrop.overlay = null
            }
        }
    }
    LaunchedEffect(layout, moving) { moving?.let { focusKey(it) } }
    LaunchedEffect(swaps) {
        val held = heldFocus
        if (swaps > 0 && held != null && overlays.isEmpty() && !expanded && moving == null) {
            withFrameNanos { }
            focusKey(held)
        }
    }
    // An app menu action can take the focused tile off Home (into a folder, hidden); config writes land
    // after the menu closes, so re-home focus whenever the layout changes under it.
    LaunchedEffect(layout) {
        val last = lastFocused ?: return@LaunchedEffect
        if (overlays.isEmpty() && !expanded && moving == null && !exists(last)) focusKey(last)
    }
    // Focus can never stay nowhere (P190): a tile that changed places (Move to, App Dock) is a new node and takes the focus of the old
    // one with it; the layout changes only once, so nothing asked for focus again and every key reached nothing until Home. A cheap
    // watchdog: when Home is in front, nothing is open, and no tile, the pill or the hero has had focus for two looks in a row, focus
    // goes back to the last tile (or the first).
    LaunchedEffect(Unit) {
        var lost = 0
        while (true) {
            kotlinx.coroutines.delay(FOCUS_WATCH_MS)
            val idle = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && view.hasWindowFocus() && overlays.isEmpty() &&
                !expanded && moving == null && folderMoving == null && !ControlCenterWindow.open && homeLoadedForFocus(layout)
            lost = if (idle && !rootFocused) lost + 1 else 0
            if (lost >= 2) { lost = 0; focusKey(lastFocused ?: firstKey()) }
        }
    }
    // After the effect above has chosen its target (it does so before its first suspension), the layout just
    // shown becomes "before" for the next change.
    LaunchedEffect(layout) {
        before.dock = layout.dock.map { appKey(it.packageName) }
        before.grid = layout.grid.map { it.key } + SETTINGS_TILE_KEY
    }
    LaunchedEffect(backdrop.backdrop) {
        if (overlays.isNotEmpty()) {
            withFrameNanos { }
            captureOverlay(view, backdrop.light).let { backdrop.overlay = it.frosted; backdrop.overlaySoft = it.soft }
        }
    }
    // The hero leaving with focus inside it would leave nothing focused: back to the tray.
    LaunchedEffect(takeover == null) { if (takeover == null && heroFocused) { heroFocused = false; focusKey(firstKey()) } }
    // The Glass Browser (a separate app) asks for the keyboard or a download card; Home shows it and the browser comes back after.
    val downloadsNow by rememberUpdatedState(cfg.downloads)
    LaunchedEffect(Unit) { dev.glasslauncher.downloads.browser.BrowserLink.asks.collect { it.run(context, downloadsNow, ::open) } }
    LaunchedEffect(Unit) {
        homePresses.collect { request ->
            moving = null
            folderMoving = null
            // A Home press (or a remote button) is activity too: Aerials wait for real idle time.
            idle.touch()
            when (request) {
                HomeRequest.Home -> {
                    dev.glasslauncher.system.RemoteKeysService.instance?.controlCenter?.hide()
                    closeAll()
                    setExpanded(false)
                    // In its own coroutine: a scroll interrupted by another one is cancelled, and that
                    // cancellation must not end this collector (Home would stop responding). Focus moves
                    // once the top row is on screen again: requested while the grid was still scrolled
                    // down, it found no tile and Home was left with no focus at all.
                    scope.launch { runCatching { scrollToTop() }; focusKey(firstKey()) }
                }
                // From remote buttons: pressing the same button again closes it.
                HomeRequest.ControlCenter -> toggleControlCenter()
                HomeRequest.AppSwitcher, HomeRequest.TvSettings, HomeRequest.Settings, HomeRequest.Remotes, HomeRequest.Downloads -> {
                    val target = when (request) {
                        HomeRequest.AppSwitcher -> Overlay.AppSwitcher
                        HomeRequest.Settings -> Overlay.Settings
                        HomeRequest.Remotes -> Overlay.Remotes
                        HomeRequest.Downloads -> Overlay.Downloads
                        else -> Overlay.TvSettings
                    }
                    val reopen = overlays.lastOrNull() != target
                    closeAll()
                    if (reopen) open(target)
                }
            }
        }
    }

    var backDownOnHome by remember { mutableStateOf(false) }
    /** Back on Home itself (no overlay): leave full screen, or go back to the top row. */
    fun homeBack() {
        when {
            expanded -> setExpanded(false)
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 -> scope.launch {
                runCatching { scrollToTop() }; focusKey(firstKey())
            }
        }
    }
    // P93: a plain-APK install can't turn Glass's accessibility service on itself: once, a card that opens Fire's
    // Accessibility settings (never again until the setting is turned back on).
    // P95: the first-run "Make VITRE your Home" card (a plain APK isn't Home until the user does one of two things).
    val homeCard by dev.glasslauncher.system.KeysService.homeCard.collectAsStateWithLifecycle()
    LaunchedEffect(homeCard, overlays.isEmpty()) {
        if (!homeCard || overlays.isNotEmpty()) return@LaunchedEffect
        dev.glasslauncher.system.KeysService.homeCardShown()
        open(Overlay.Confirm(
            "Make VITRE your Home",
            // The adb command names Fire's launcher: any other device is pointed at its own Home setting instead.
            dev.glasslauncher.system.HomeSetup.homeCardText(dev.glasslauncher.system.HomeSetup.isFireTv),
            // Short: the card's buttons are half-width, and a longer label was cut to "Open A…".
            "Settings",
        ) {
            runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        })
    }
    // P98: APK mode, the remote has been gone for 30 s with the screen on: one card that opens Fire's remote settings.
    val remoteCard by dev.glasslauncher.system.RemoteWatch.card.collectAsStateWithLifecycle()
    LaunchedEffect(remoteCard, overlays.isEmpty()) {
        if (!remoteCard || overlays.isNotEmpty()) return@LaunchedEffect
        dev.glasslauncher.system.RemoteWatch.cardShown()
        open(Overlay.Confirm(
            "Remote not connected",
            "Hold Home on the remote for 10 s to pair it. If that does nothing, open Fire's remote settings and choose Add New Remote.",
            "Settings",
        ) { runCatching { dev.glasslauncher.system.SystemControls.openGameControllers(context) } })
    }
    val keysCard by dev.glasslauncher.system.KeysService.card.collectAsStateWithLifecycle()
    LaunchedEffect(keysCard, overlays.isEmpty()) {
        if (!keysCard || overlays.isNotEmpty()) return@LaunchedEffect
        dev.glasslauncher.system.KeysService.cardShown()
        open(Overlay.Confirm(
            "Turn on remote buttons?",
            "VITRE needs its accessibility service for the remote's app buttons and instant Back. Turn it on in Accessibility settings.",
            "Settings",
        ) {
            runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        })
    }
    BackHandler {
        when {
            // Back during the app-open zoom, before the app was started: undo it (P87).
            transition.cancelBeforeHandoff() -> Unit
            moving != null -> moving = null
            folderMoving != null -> folderMoving = null
            // Control Center's edit mode takes Back first (cancel a lift, then save and leave edit mode).
            overlays.isNotEmpty() -> if (overlays.last() != Overlay.ControlCenter || ControlCenterWindow.editBack?.invoke() != true) closeTop()
            else -> homeBack()
        }
    }

    val scaledDensity = Density(density.density, density.fontScale * cfg.textScale)
    val rtl = dev.glasslauncher.ui.isRtl()
    // Home's keys (P248): the tray's own view sends its keys here first, so both views behave as one.
    val rootKeys: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = rootKeys@{ ev ->
                    val e = ev.nativeKeyEvent
                    idle.touch()
                    if (e.action == AndroidKeyEvent.ACTION_DOWN) idle.noteKey(e.repeatCount)
                    if (e.action == AndroidKeyEvent.ACTION_DOWN) when (e.keyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { KeyDirection.dx = -1; KeyDirection.dy = 0 }
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { KeyDirection.dx = 1; KeyDirection.dy = 0 }
                        AndroidKeyEvent.KEYCODE_DPAD_UP -> { KeyDirection.dx = 0; KeyDirection.dy = -1 }
                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> { KeyDirection.dx = 0; KeyDirection.dy = 1 }
                    }
                    if (overlays.isNotEmpty()) { backDownOnHome = false; return@rootKeys false }
                    val key = moving
                    if (key != null) {
                        if (e.action == AndroidKeyEvent.ACTION_DOWN) handleMoveKey(e.keyCode, key, model, metrics.columns, rtl) { moving = null }
                        return@rootKeys true
                    }
                    // Compose treats Back as "leave the focus group" first, so from the grid the first press
                    // only dropped focus and a second one went back up (found by e2e/test_home.py).
                    // Only a Back that went down on Home: the key-up of a Back that closed a menu isn't one.
                    if (e.keyCode == AndroidKeyEvent.KEYCODE_BACK && shelfSheet) return@rootKeys false
                    if (e.keyCode == AndroidKeyEvent.KEYCODE_BACK) {
                        if (e.action == AndroidKeyEvent.ACTION_DOWN) backDownOnHome = e.repeatCount == 0 || backDownOnHome
                        if (e.action == AndroidKeyEvent.ACTION_UP && backDownOnHome) { backDownOnHome = false; homeBack() }
                        return@rootKeys true
                    }
                    if (e.action != AndroidKeyEvent.ACTION_DOWN) return@rootKeys false
                    when {
                        e.keyCode == AndroidKeyEvent.KEYCODE_SETTINGS -> { toggleControlCenter(); true }
                        // The info card keeps Up and Down until Back (P231): Home's "Down leaves full screen" below must not see them.
                        dev.glasslauncher.featured.InfoCardOpen.open && (e.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN || e.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP) -> false
                        // Up from the grid's first row goes back to the tray app focus left from (the tray is its own view, P248).
                        e.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP && !expanded && focusedRow == 2 && moving == null -> {
                            val key = lastDockFocused?.takeIf { exists(it) } ?: firstKey()
                            key != null && runCatching { requester(key).requestFocus() }.isSuccess
                        }
                        // Up from the tray opens the featured shelf full screen ("Swipe up for full screen").
                        e.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP && !expanded && focusedRow == 1 && feed != null && cfg.topShelfTitles && takeover == null -> { setExpanded(true); true }
                        e.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP && !expanded && focusedRow == 1 && takeover != null && !pillFocused && !heroFocused ->
                            runCatching { heroPlay.requestFocus() }.isSuccess
                        e.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN && heroScrubFocused && takeover != null && !expanded ->
                            runCatching { heroPlay.requestFocus() }.isSuccess
                        // From the status pill, Down goes back to the Now Playing controls.
                        e.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN && pillFocused && takeover != null && !expanded ->
                            runCatching { heroPlay.requestFocus() }.isSuccess
                        // Down from the pill (no music) or from the Now Playing controls: back to the tray app
                        // focus came up from (the pill sits outside the grid, so spatial search found nothing).
                        e.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN && !expanded && (pillFocused || heroFocused) -> {
                            val key = lastDockFocused?.takeIf { exists(it) } ?: firstKey()
                            key != null && runCatching { requester(key).requestFocus() }.isSuccess
                        }
                        // Down always leaves full screen, wherever focus is (even mid-transition); from the
                        // status pill it goes back down to the titles instead.
                        // A sectioned shelf moves Down to its next row first (its own handler); only the last row leaves.
                        e.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN && expanded && !pillFocused && sectionCursor.down() == dev.glasslauncher.featured.DownAction.Native -> false
                        // Down from the hero's buttons enters the first row; it does not leave full screen (P132).
                        e.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN && expanded && !pillFocused && sectionCursor.down() == dev.glasslauncher.featured.DownAction.EnterRows ->
                            sectionCursor.enterRows?.invoke() ?: false
                        e.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN && expanded -> when {
                            pillFocused -> { runCatching { cardRequester.requestFocus() }; true }
                            // Focus already back on Home (a stale state): just let Down move as usual.
                            homeFocused -> { setExpanded(false); false }
                            else -> { setExpanded(false); true }
                        }
                        else -> false
                    }
                }
    TrayHost.keys = rootKeys
    GridHost.keys = rootKeys
    // Home's own content alpha (P300): also applied to the tray and grid, which draw in *their own* ComposeViews and so
    // are untouched by it. Without this they stay on top of an app's open cover and of a hidden Home.
    val homeChromeAlpha: () -> Float = {
        if ((transition.coverAlpha.value >= 1f && transition.cover != null) || backdrop.homeHidden) 0f else reveal.value
    }
    CompositionLocalProvider(
        LocalMenuAnchor provides menuAnchor,
        dev.glasslauncher.widgets.LocalHomeHidden provides idle.hidden,
        dev.glasslauncher.ui.LocalScreenDissolve provides dissolve,
        LocalAppTransition provides transition.takeUnless { prefs.reduceMotion },
        LocalBackdrop provides backdrop,
        LocalPalette provides palette,
        LocalUiPrefs provides prefs,
        LocalMetrics provides metrics,
        LocalDensity provides scaledDensity,
        LocalTextStyle provides Type.body,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .semantics { testTagsAsResourceId = true }
                .onSizeChanged { backdrop.rootSize = it }
                .onFocusChanged { rootFocused = it.hasFocus }
                // Black under the fade-in only; afterwards the backdrop is the only full-screen fill.
                .drawBehind { if (reveal.value < 1f) drawRect(Color.Black) }
                .onPreviewKeyEvent(rootKeys),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = idle.shift.x; translationY = idle.shift.y
                        // Hidden while an app open/close covers it completely: one less full screen to draw.
                        alpha = homeChromeAlpha()
                        val settle = 1.04f - 0.04f * homeSettle.value
                        scaleX = settle; scaleY = settle
                    }
                    .drawWithContent {
                        layer.record { this@drawWithContent.drawContent() }
                        drawLayer(layer)
                    },
            ) {
                if (cfg.background == BackgroundMode.Motion) {
                    MotionBackground(cfg.screensaver, backdrop, light = !dark, paused = overlays.isNotEmpty() || ControlCenterWindow.open || idle.idle)
                }
                BackdropLayer(backdrop, drawSharp = cfg.background != BackgroundMode.Motion)

                if (appHeroPkg != null && artApps[appHeroPkg] == true) {
                    Box(Modifier.testTag("top-shelf-app-hero:$appHeroPkg")) { Box(Modifier.size(1.dp).testTag("app-art:$appHeroPkg")) }
                }
                // Spotify at rest (P79b): the carousel itself, covers at their own size, in the Top Shelf area.
                // On arrival the covers wait for their soft scene to be on the backdrop (else they sit over the previous
                // app's picture for the second it takes to bake), at most SCENE_WAIT_MS.
                val previewOn = feed != null && softFeed && cfg.topShelfTitles && takeover == null && !appHeroShown && cfg.background == BackgroundMode.Featured
                // Composed exactly as before (no retained state, nothing re-created); only its ALPHA waits for the cover
                // shelf's soft scene to be on the backdrop (at most SCENE_WAIT_MS), so the covers never sit over the
                // previous app's picture (P92).
                val sceneUp = coverSceneFor == shelfKey || shelfKey == null || expanded
                // Fades in with the backdrop's dissolve and out when focus leaves (the feed is kept just for that).
                val lastCovers = remember { arrayOfNulls<dev.glasslauncher.featured.FeaturedFeed>(1) }
                // The leaving preview keeps the index it had too (P120): the shelf catching up swaps the feed and re-keys
                // heroIndex within a frame or two, and a fading carousel must not be re-centred on the new app's first item.
                val lastIndex = remember { IntArray(1) }
                if (previewOn) { lastCovers[0] = feed; lastIndex[0] = heroIndex }
                val previewAlpha by androidx.compose.animation.core.animateFloatAsState(
                    if (previewOn && sceneUp) 1f else 0f, androidx.compose.animation.core.tween(if (prefs.reduceMotion) 120 else PREVIEW_FADE_MS), label = "preview-fade",
                )
                val previewFeed = if (previewOn) feed else lastCovers[0]
                if (previewFeed != null && (previewOn || previewAlpha > 0.01f)) {
                    CoverPreview(
                        feed = previewFeed, index = (if (previewOn) heroIndex else lastIndex[0]).coerceIn(0, previewFeed.items.lastIndex), expand = { expand.value },
                        caption = true,
                        restCentreY = metrics.trayTop(androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp, androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp) * 0.55f,
                        trayTop = metrics.trayTop(androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp, androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp),
                        modifier = Modifier.testTag("cover-preview-host").graphicsLayer {
                            alpha = previewAlpha * idle.chromeAlpha * (1f - (backdrop.wallpaperBlur.value * 4f).coerceIn(0f, 1f))
                            translationY = -(if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset.toFloat() else 2000f)
                        },
                    )
                }
                if (hero != null && takeover == null && !appHeroShown) {
                    ShelfTitle(
                        item = hero,
                        textFallback = !softFeed,
                        expanded = { expand.value },
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("shelf-title")
                            .graphicsLayer {
                                val atRest = 1f - (backdrop.wallpaperBlur.value * 4f).coerceIn(0f, 1f)
                                // Gone within the first third of the expand, before the large title fades in.
                                alpha = idle.chromeAlpha * atRest * (1f - expand.value * 3f).coerceIn(0f, 1f) * titleGateAlpha(titleReady, titleGate)
                                translationY = -(if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset.toFloat() else 2000f)
                                compositingStrategy = CompositingStrategy.ModulateAlpha
                            },
                    )
                }

                CompositionLocalProvider(LocalBringIntoViewSpec provides NoAutoScroll) {
                    HomeList(
                        target = layout,
                        idle = idle,
                        homeChromeAlpha = homeChromeAlpha,
                        onSwap = { heldFocus = lastFocused; swaps++ },
                        model = model,
                        listState = listState,
                        moving = moving,
                        // Only the menus whose actions take a tile off Home: not Settings, Control Center, the switcher.
                        overlayOpen = overlays.any { it is Overlay.AppMenu || it is Overlay.MoveTo || it is Overlay.FolderPicker || it is Overlay.FolderMenu },
                        // Only when there are titles to open (Up); an app with nothing shows no chevron.
                        showHint = feed != null && cfg.topShelfTitles && takeover == null,
                        hintAlpha = { (1f - backdrop.wallpaperBlur.value * 4f).coerceIn(0f, 1f) * (1f - expand.value) * idle.chromeAlpha },
                        requester = ::requester,
                        onFocused = { key, row ->
                            lastFocused = key
                            if (row == 1) lastDockFocused = key
                        },
                        onRowFocused = ::onRowFocused,
                        onAppMenu = { app, inDock -> open(Overlay.AppMenu(app, inDock, null, menuAnchor.bounds)) },
                        onFolderOpen = { open(Overlay.FolderOpen(it, menuAnchor.bounds)) },
                        onFolderMenu = { open(Overlay.FolderMenu(it, menuAnchor.bounds)) },
                        onSettings = {
                            // Settings opens out of its tile as an app does (P105 #7): the same zoom with the page's colour
                            // and the gear; with no tile bounds it just opens.
                            val b = menuAnchor.bounds
                            val from = b?.let { val dx = it.width * 0.1f; val dy = it.height * 0.1f; Rect(it.left - dx, it.top - dy, it.right + dx, it.bottom + dy) }
                            if (from == null || prefs.reduceMotion) open(Overlay.Settings)
                            else {
                                settingsFromTile = true
                                transition.openPage(
                                    from, settingsEntry, if (dark) Color(0xFF22252E) else Color(0xFFF2F2F8), gearGlyph, view,
                                ) { open(Overlay.Settings) }
                            }
                        },
                        launch = { app, bounds -> model.launch(app, view, bounds) },
                        modifier = Modifier
                            .graphicsLayer {
                                // Expanding the shelf slides the tray and grid off the bottom of the screen.
                                translationY = expand.value * size.height * 0.62f
                                // The apps fade out with the rest of the chrome when Home is idle (P226), and back in with any key.
                                alpha = (1f - expand.value * 0.999f) * idle.chromeAlpha
                                compositingStrategy = CompositingStrategy.ModulateAlpha
                            }
                            .focusProperties { canFocus = !expanded }
                            // Focus on the tray or grid means full screen is over, however it got there.
                            .onFocusChanged { homeFocused = it.hasFocus; if (it.hasFocus && expanded) setExpanded(false) },
                    )
                }
                // While music plays, the Now Playing hero takes the featured title's place (dissolving both
                // ways, in step with the backdrop). Drawn after the list: the list is full screen (empty up
                // here), and Compose leaves anything a later sibling covers out of the accessibility tree.
                androidx.compose.animation.AnimatedVisibility(
                    visible = takeover != null,
                    enter = androidx.compose.animation.fadeIn(tween(550)),
                    exit = androidx.compose.animation.fadeOut(tween(450)),
                    modifier = Modifier.graphicsLayer {
                        alpha = idle.chromeAlpha * (1f - (backdrop.wallpaperBlur.value * 4f).coerceIn(0f, 1f))
                        translationY = -(if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset.toFloat() else 2000f)
                    },
                ) {
                    val shown = takeover ?: nowPlaying
                    if (shown != null) {
                        // Live position and play state while it's up; the takeover itself lingers through gaps.
                        val live = nowPlaying?.takeIf { it.key == shown.key } ?: shown
                        val onLight = backdrop.backdrop?.artLight(0.12f, 0.15f, 0.88f, 0.65f) == true
                        Box(Modifier.fillMaxWidth().padding(top = metrics.chromeInset + 70.dp).onFocusChanged { heroFocused = it.hasFocus }, contentAlignment = Alignment.TopCenter) {
                            dev.glasslauncher.widgets.NowPlayingHero(live, onLight, heroPlay, pillRequester, onScrubFocus = { heroScrubFocused = it })
                        }
                    }
                }

                if (feed != null && (expanded || expand.value > 0f)) {
                    ExpandedShelf(
                        feed = feed,
                        index = heroIndex,
                        settledIndex = shownIndex,
                        holding = { dev.glasslauncher.featured.HoldSettle.holding(idle.millisSinceRepeat()) },
                        progress = { expand.value },
                        firstCard = cardRequester,
                        onIndex = { heroIndex = it },
                        onExitDown = { setExpanded(false) },
                        modifier = Modifier.fillMaxSize(),
                        onSheet = { shelfSheet = it },
                        // Up from the Cover Flow reaches the pill (Control Center), as the card row's Play button did.
                        upTo = pillRequester,
                        labelIcon = shelfIcon,
                        cursor = sectionCursor,
                    )
                }
            }

            val noticeTick = dev.glasslauncher.system.NoticeStore.state.value
            StatusPill(
                cfg = cfg,
                idle = idle,
                focusable = expanded || feed == null || takeover != null,
                onSelect = { openControlCenter() },
                onSearch = { open(Overlay.Search) },
                // P93 #7: the time is the one piece of chrome worth leaving over the wallpaper when Home fades out.
                keepClockWhenIdle = true,
                onNotices = { open(Overlay.Notices) },
                // Read the store's state here so a posted notification re-draws the dot (P98).
                noticeCount = noticeTick.let { dev.glasslauncher.system.NoticeStore.count(cfg.blockedNoticePackages.toSet()) },
                modifier = Modifier.align(Alignment.TopEnd).focusRequester(pillRequester).onFocusChanged { pillFocused = it.hasFocus; ControlCenterWindow.pillFocused = it.hasFocus },
                // Control Center draws its own clock in this corner.
                fade = { if (ControlCenterWindow.pillCovered) 0f else (1f - backdrop.wallpaperBlur.value) * reveal.value },
            )

            moving?.let { MoveBanner(it, layout, Modifier.align(Alignment.BottomCenter)) }

            // The return from Settings' edit is over once Control Center has left.
            LaunchedEffect(exiting.none { it is Overlay.ControlCenter }) { if (exiting.none { it is Overlay.ControlCenter }) CcEditEntry.returning = false }
            // Control Center opened from Settings (Edit Layout): composed once, hidden, while Settings' page for it is up,
            // so the open's cold-composition cost isn't between Select and the grid growing (P101).
            if (CcEditEntry.warmWanted && !CcEditEntry.warmed && overlays.lastOrNull() == Overlay.Settings) {
                Box(Modifier.size(1.dp).graphicsLayer { alpha = 0f }) {
                    ControlCenter({}, cfg, active = false, open = {}, closeAll = {})
                }
                LaunchedEffect(Unit) { delay(1_500); CcEditEntry.warmed = true }
            }
            // One loop over open and leaving overlays, keyed by identity, so an overlay keeps its state
            // (and its animation) when it moves from open to leaving.
            (overlays + exiting.filter { it !in overlays } + (if (ccParked && Overlay.ControlCenter !in overlays && Overlay.ControlCenter !in exiting) listOf(Overlay.ControlCenter) else emptyList())).forEach { overlay ->
                androidx.compose.runtime.key(System.identityHashCode(overlay)) {
                    val parkedCc = overlay === Overlay.ControlCenter && overlay !in overlays && overlay !in exiting
                    val leaving = (overlay in exiting && overlay !in overlays) || parkedCc
                    val top = !leaving && overlay === overlays.lastOrNull()
                    // A confirm card sits over the page it came from (Settings), under its scrim: that page stays drawn
                    // instead of vanishing behind it (P64).
                    val underCard = !top && !leaving && keptUnderCard(overlays, overlays.indexOf(overlay))
                    // Tagged for the device tests (e2e/): which overlays are up, and which one is on top.
                    val tag = (if (leaving) "overlay-leaving:" else if (top) "overlay-top:" else "overlay:") + (overlay::class.simpleName ?: "Overlay")
                    // Control Center is collapsing into its corner over what it was opened from (Settings > Edit Layout, Done):
                    // that page waits, then fades in once Control Center is gone, so the grid never draws on top of it. The
                    // hold applies in the very frame of Back (an animated value would start one frame late and show the page).
                    val heldForCc = CcEditEntry.returning && !leaving && exiting.any { it is Overlay.ControlCenter }
                    val heldAlpha by androidx.compose.animation.core.animateFloatAsState(
                        if (heldForCc) 0f else 1f,
                        if (heldForCc) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(if (prefs.reduceMotion) 1 else 160),
                        label = "cc-return",
                    )
                    // Edit Layout (P152a): Settings fades out under Control Center as it opens, instead of vanishing in the frame
                    // of Select (which showed bare Home for a frame: the flash). tvOS's edit is a state change, not a cut.
                    val underCcEdit = !top && !leaving && overlay == Overlay.Settings && CcEditEntry.fromSettings &&
                        overlays.lastOrNull() == Overlay.ControlCenter
                    val editFade by androidx.compose.animation.core.animateFloatAsState(
                        if (underCcEdit) 0f else 1f,
                        if (underCcEdit) androidx.compose.animation.core.tween(if (prefs.reduceMotion) 1 else 220) else androidx.compose.animation.core.snap(),
                        label = "cc-edit-entry",
                    )
                    Box(Modifier.fillMaxSize().then(if (parkedCc) Modifier.ccParked() else Modifier.testTag(tag)).graphicsLayer { alpha = if (parkedCc || heldForCc) 0f else if (underCcEdit) editFade else (if (top || leaving || underCard || overlay is Overlay.FolderOpen) 1f else 0f) * heldAlpha }) {
                        androidx.compose.runtime.CompositionLocalProvider(LocalOverlayExiting provides leaving) {
                            OverlayContent(
                                overlay = overlay,
                                model = model,
                                layout = layout,
                                cfg = cfg,
                                active = top,
                                open = ::open,
                                close = ::closeTop,
                                closeAll = ::closeAll,
                                startMove = { key -> model.beginMove(); closeAll(); moving = key },
                                folderMoving = folderMoving,
                                startFolderMove = { key -> model.beginMove(); closeTop(); folderMoving = key },
                                stopFolderMove = { folderMoving = null },
                            )
                        }
                    }
                }
            }

            if (reveal.value < 1f) StartupMark(loading = !ready, alpha = { 1f - reveal.value })

            AppTransitionLayer(transition, warmSource = backdrop.backdrop?.blurredSoftware.takeIf { ready })

            // After an install or update, once Home has settled: the draw variants the animated paths use, drawn once,
            // invisibly, so their shaders compile now and not in the first focus move or overlay open (P115).
            val warmPrefs = remember { context.getSharedPreferences("home", 0) }
            val updatedAt = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L) }
            var warmDue by remember { mutableStateOf(updatedAt != 0L && ShaderWarmup.due(updatedAt, warmPrefs.getLong(ShaderWarmup.KEY, -1L))) }
            var warmGo by remember { mutableStateOf(false) }
            LaunchedEffect(ready, warmDue, overlays.isEmpty(), reveal.value >= 1f) {
                if (ready && warmDue && overlays.isEmpty() && reveal.value >= 1f) { delay(SHADER_WARM_AFTER_MS); warmGo = true }
            }
            if (warmDue && warmGo) {
                ShaderWarmupLayer(sample = backdrop.backdrop?.blurredSoftware) {
                    warmPrefs.edit().putLong(ShaderWarmup.KEY, updatedAt).apply()
                    warmDue = false
                }
            }

            // The frame before an appearance/background/text-size change, fading out over the new one.
            dissolve.image?.let { frame ->
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    drawImage(frame, dstSize = IntSize(size.width.toInt(), size.height.toInt()), alpha = dissolve.alpha.value)
                }
            }
        }
    }
}

/**
 * Shown only if startup takes more than a moment: the name, breathing slowly in the middle of a black
 * screen, then fading out as Home fades in.
 */
@Composable
private fun BoxScope.StartupMark(loading: Boolean, alpha: () -> Float) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(400); shown = true }
    if (!shown) return
    val breath = remember { Animatable(0.35f) }
    LaunchedEffect(loading) {
        if (loading) breath.animateTo(0.7f, androidx.compose.animation.core.infiniteRepeatable(tween(1400), androidx.compose.animation.core.RepeatMode.Reverse))
    }
    Text(
        "VITRE",
        style = Type.title.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
        color = Color.White,
        modifier = Modifier.align(Alignment.Center).graphicsLayer { this.alpha = breath.value * alpha() },
    )
}

/** Home's tile keys as they were before the last layout change, tray and grid apart. */
private class PreviousTiles {
    var dock: List<String> = emptyList()
    var grid: List<String> = emptyList()

    /** The nearest tile still on Home next to [key] in the area it was in: those after it first, then those before. */
    fun neighbour(key: String, exists: (String) -> Boolean): String? {
        val area = listOf(dock, grid).firstOrNull { key in it } ?: return null
        val i = area.indexOf(key)
        return (area.drop(i + 1) + area.take(i).reversed()).firstOrNull(exists)
    }
}

/** The focus watchdog only looks once Home has tiles to focus. */
internal fun homeLoadedForFocus(layout: HomeLayout) = layout.loaded && (layout.dock.isNotEmpty() || layout.grid.isNotEmpty())

/** How often the focus watchdog looks (P190). */
private const val FOCUS_WATCH_MS = 500L

private fun handleMoveKey(keyCode: Int, key: String, model: HomeModel, columns: Int, rtl: Boolean, done: () -> Unit) {
    // Built from the working copy on every press: the composed layout trails a fast D-pad repeat by a frame or more.
    val layout = model.currentLayout()
    val pkg = key.removePrefix("app:")
    val inDock = key.startsWith("app:") && layout.dock.any { it.packageName == pkg }
    val gridIndex = layout.grid.indexOfFirst { it.key == key }
    when (keyCode) {
        // The dock and grid mirror in a right-to-left layout, so Left is the next place there (P308).
        AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> model.move(key, dev.glasslauncher.ui.horizontalStep(keyCode, rtl))
        AndroidKeyEvent.KEYCODE_DPAD_UP -> when {
            inDock -> Unit
            gridIndex in 0 until columns && key.startsWith("app:") -> model.moveIntoDock(pkg, gridIndex)
            else -> model.move(key, -columns)
        }
        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> when {
            inDock -> model.moveOutOfDock(pkg)
            !model.move(key, columns) -> model.move(key, layout.grid.lastIndex - gridIndex)
        }
        AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_BACK -> done()
    }
}

/**
 * One opaque full-screen draw per frame: the backdrop's blur ladder step for the current scroll
 * blur. Only during a scene change is the outgoing backdrop drawn underneath and faded.
 */
@Composable
private fun BackdropLayer(state: BackdropState, drawSharp: Boolean) {
    Canvas(Modifier.fillMaxSize()) {
        val dst = IntSize(size.width.toInt(), size.height.toInt())
        val blur = state.wallpaperBlur.value
        fun step(b: dev.glasslauncher.glass.Backdrop) = b.ladder[(blur * b.ladder.lastIndex).roundToInt().coerceIn(0, b.ladder.lastIndex)]
        // In Motion mode the video shows through until the grid is scrolled into view.
        if (!drawSharp && blur < 0.05f) return@Canvas
        val alphaForMotion = if (drawSharp) 1f else ((blur - 0.05f) * 3f).coerceIn(0f, 1f)
        val previous = state.previous
        val current = state.backdrop ?: return@Canvas
        // Mid-scroll, blend the two neighbouring blur steps so the blur (and its appearance wash) ramps
        // continuously instead of stepping through the ladder. Two full-screen draws only while it moves.
        val pos = blur * current.ladder.lastIndex
        val lower = pos.toInt().coerceIn(0, current.ladder.lastIndex)
        val frac = pos - lower
        if (previous == null && drawSharp && frac > 0.04f && frac < 0.96f && lower < current.ladder.lastIndex) {
            drawImage(current.ladder[lower], dstSize = dst, filterQuality = FilterQuality.Low)
            drawImage(current.ladder[lower + 1], dstSize = dst, alpha = frac, filterQuality = FilterQuality.Low)
            return@Canvas
        }
        if (previous != null) drawImage(step(previous), dstSize = dst, alpha = alphaForMotion, filterQuality = FilterQuality.Low)
        drawImage(
            step(current),
            dstSize = dst,
            alpha = alphaForMotion * if (previous != null) state.fade.value else 1f,
            filterQuality = FilterQuality.Low,
        )
        // The previous app's scene waiting for the new one: dimmed (one translucent rect, only while it waits).
        val h = state.sceneDim
        if (h > 0.001f) drawRect(Color.Black, alpha = h)
    }
}

/**
 * Left/Right at the end of a row does nothing, as on tvOS, instead of letting focus search wander to
 * whatever is geometrically nearest (the status pill, or the end of the previous row).
 */
fun Modifier.stopAtRowEnds(): Modifier = focusProperties {
    onExit = {
        if (requestedFocusDirection == androidx.compose.ui.focus.FocusDirection.Left ||
            requestedFocusDirection == androidx.compose.ui.focus.FocusDirection.Right
        ) cancelFocusChange()
    }
}.then(Modifier.focusGroup())

private sealed interface Cell {
    data class Item(val item: GridItem) : Cell
    data object Settings : Cell
}

/** The keys of every tile on Home in [layout]: tray, grid and the Settings tile. */
private fun tileKeys(layout: HomeLayout): Set<String> =
    HashSet<String>(layout.dock.size + layout.grid.size + 1).apply {
        layout.dock.forEach { add(appKey(it.packageName)) }
        layout.grid.forEach { add(it.key) }
    }

/** Every tile's key in slot order: the tray's apps, then the grid (the tray's extra apps lead it) and the Settings tile. */
private fun slotKeys(layout: HomeLayout, columns: Int): List<String> {
    val dock = layout.dock.map { appKey(it.packageName) }
    return dock.take(columns) + dock.drop(columns) + layout.grid.map { it.key } + SETTINGS_TILE_KEY
}

/** How long a tile taken off Home takes to fade out before the others close the gap. */
private const val LEAVE_MS = 200

private fun cellKey(cell: Cell): String = when (cell) {
    is Cell.Item -> cell.item.key
    Cell.Settings -> SETTINGS_TILE_KEY
}

@Composable
private fun HomeList(
    target: HomeLayout,
    /** The idle state, for the chrome alpha: the tray and grid draw in their own views, out of reach of the list's (P226). */
    idle: dev.glasslauncher.widgets.IdleState,
    /** Home's own content alpha: the tray and grid are separate views, so the app-open cover would not hide them (P300). */
    homeChromeAlpha: () -> Float,
    /** The list is about to swap to a new layout after a tile's fade: focus will be re-resolved by position. */
    onSwap: () -> Unit,
    model: HomeModel,
    listState: LazyListState,
    moving: String?,
    /** An overlay (an app menu) is up over Home: its action may take a tile away, so placement is tracked meanwhile. */
    overlayOpen: Boolean,
    showHint: Boolean,
    hintAlpha: () -> Float,
    requester: (String) -> FocusRequester,
    onFocused: (String, Int) -> Unit,
    onRowFocused: (Int) -> Unit,
    onAppMenu: (AppEntry, Boolean) -> Unit,
    onFolderOpen: (String) -> Unit,
    onFolderMenu: (dev.glasslauncher.data.Folder) -> Unit,
    onSettings: () -> Unit,
    launch: (AppEntry, Rect?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val m = LocalMetrics.current
    val screen = androidx.compose.ui.platform.LocalConfiguration.current
    val trayTop = m.trayTop(screen.screenWidthDp.dp, screen.screenHeightDp.dp)
    // A changed layout makes the cells that moved glide to their new spots: in move mode, and after an app menu
    // action takes a tile off Home (Hide, Uninstall, Move to Folder): the tiles after it glide into the gap.
    val glide = remember { GlideTracker() }
    val tracking = moving != null || overlayOpen
    // Provided only while moving or a menu is up (and for the tail of the last glide, which covers the config
    // write landing after the menu closes): the rest of the time no cell tracks its placement, so scrolling the
    // grid costs nothing for it.
    var gliding by remember { mutableStateOf(false) }
    // A tile taken off Home fades out where it stands first (LEAVE_MS), and only then do the others glide into
    // the gap: the list is held at the layout it had until the fade is over.
    var shown by remember { mutableStateOf(target) }
    val leavers = remember(target, shown, moving) {
        if (moving != null || shown === target) emptySet() else tileKeys(shown) - tileKeys(target)
    }
    val holding = leavers.isNotEmpty() && (tracking || gliding)
    val layout = if (holding) shown else target
    LaunchedEffect(target, holding) {
        GlideTracker.layoutsSeen++
        if (holding) {
            glide.leaving = leavers
            glide.leaveAlpha.snapTo(1f)
            glide.leaveAlpha.animateTo(0f, tween(LEAVE_MS, easing = androidx.compose.animation.core.FastOutLinearInEasing))
            // The tiles shift places under the focused one when the layout swaps: let Home put focus back.
            onSwap()
            // The tile stays at alpha 0 (leaving) until the swap has taken it out of the list: clearing it first
            // drew it again at full opacity for a frame.
            shown = target
        } else {
            shown = target
            glide.leaving = emptySet()
        }
    }
    // Cleared once the list no longer holds the leaving tile (the recomposition after the swap).
    LaunchedEffect(shown) { if (shown === target) glide.leaving = emptySet() }
    // A layout change while tracking (or in the tail after a menu closed) is a new generation: its cells glide.
    // The slot order (tray, then the grid and the Settings tile) as it was drawn, to say where each cell will be placed.
    val slotOrder = remember { arrayOf<List<String>>(emptyList()) }
    remember(layout) {
        val slots = slotKeys(layout, m.columns)
        if (tracking || gliding) {
            glide.generation++
            val old = slotOrder[0]
            glide.predicted = buildMap {
                slots.forEachIndexed { i, key ->
                    val was = old.getOrNull(i)?.let { glide.last[it] }?.first
                    if (was != null && old.getOrNull(i) != key) put(key, was)
                }
            }
        }
        slotOrder[0] = slots
        glide.generation
    }
    LaunchedEffect(tracking) {
        if (tracking) gliding = true else { delay(GLIDE_TAIL_MS); gliding = false; glide.reset() }
    }
    // With larger text the tray holds fewer columns: its other apps lead the grid instead of vanishing.
    val dockShown = layout.dock.take(m.columns)
    val dockExtra = layout.dock.drop(m.columns)
    val cells = remember(layout.grid, dockExtra) {
        dockExtra.map<dev.glasslauncher.apps.AppEntry, Cell> { Cell.Item(GridItem.App(it)) } + layout.grid.map<GridItem, Cell> { Cell.Item(it) } + Cell.Settings
    }
    val rows = remember(cells, m.columns) { cells.chunked(m.columns) }
    CompositionLocalProvider(LocalGlide provides glide.takeIf { gliding || tracking }) {
    LazyColumn(
        state = listState,
        userScrollEnabled = false,
        contentPadding = PaddingValues(bottom = 160.dp),
        modifier = modifier.fillMaxSize().testTag("home"),
    ) {
        item(key = "shelf") {
            Box(Modifier.fillMaxWidth().height(trayTop), contentAlignment = Alignment.BottomCenter) {
                if (showHint) ShelfHint(hintAlpha)
            }
        }
        item(key = "dock") {
            // The tray draws in its own ComposeView beside Home's (TrayHost, P248); this is only its slot.
            TrayHost.Attach()
            val d = androidx.compose.ui.platform.LocalDensity.current
            // Under an overlay (folder, menu) the tray draws here, in Home's view, so the overlay's dim and frosted snapshot cover it too.
            val tray: @Composable () -> Unit = {
                // The tray usually draws in its own ComposeView (TrayHost, P248), which Home's LazyColumn alpha cannot reach,
                // so the idle fade (P226) is applied here too — but not when the tray is in Home's view, where the list already did it.
                Box(Modifier.graphicsLayer {
                    alpha = if (TrayHost.inMain) 1f else homeChromeAlpha() * idle.chromeAlpha
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }) {
                DockTray(
                    apps = dockShown,
                    model = model,
                    moving = moving,
                    requester = requester,
                    onFocused = { k -> TrayHost.col = dockShown.indexOfFirst { appKey(it.packageName) == k }; onFocused(k, 1) },
                    onAppMenu = { onAppMenu(it, true) },
                    launch = launch,
                    modifier = Modifier.onFocusChanged { if (it.hasFocus) onRowFocused(1) },
                )
                }
            }
            // Called here directly, never through TrayHost.content: reading the state this item writes would recompose it forever.
            // Movable (P310): moving between Home's view and the tray's own keeps the composition instead of recomposing every tile.
            val trayLatest = androidx.compose.runtime.rememberUpdatedState(tray)
            val trayMovable = remember { androidx.compose.runtime.movableContentOf { trayLatest.value() } }
            if (TrayHost.inMain) trayMovable()
            else Box(Modifier.fillMaxWidth().height(with(d) { TrayHost.h.toDp() }).onGloballyPositioned { TrayHost.y = it.positionInWindow().y })
            androidx.compose.runtime.SideEffect {
                TrayHost.slotCompositions++
                TrayHost.content = trayMovable
                // Down from the tray: the grid's first row, same column (the views don't search each other).
                TrayHost.down = { rows.firstOrNull()?.let { r -> runCatching { requester(cellKey(r[TrayHost.col.coerceIn(0, r.lastIndex)])).requestFocus() }.isSuccess } ?: false }
            }
        }
        // Keyed by the row's first cell, not its place: a row that moves keeps its composition (P159).
        item(key = "grid") {
            GridHost.Attach()
            val d = androidx.compose.ui.platform.LocalDensity.current
            if (GridHost.rowTops.size != rows.size) GridHost.rowTops = IntArray(rows.size)
            // shortcut: every grid row is composed (no laziness); measured with 15 apps, re-measure PSS if libraries of 100+ apps matter.
            val grid: @Composable () -> Unit = { Column(Modifier.graphicsLayer {
                // The grid also draws in its own ComposeView (GridHost, P258), out of reach of Home's list alpha (P226);
                // and of the app-open cover (P300). Not when it is in Home's view, where the list already applied both.
                alpha = if (TrayHost.inMain) 1f else homeChromeAlpha() * idle.chromeAlpha
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }) { rows.forEachIndexed { i, row -> androidx.compose.runtime.key("row-" + cellKey(row.first())) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(m.gutter),
                modifier = Modifier
                    // Measured outside the padding: the row's item-relative top, as when each row was an item.
                    .onPlaced { if (i < GridHost.rowTops.size) GridHost.rowTops[i] = it.positionInParent().y.toInt() }
                    .fillMaxWidth()
                    .padding(start = m.inset, end = m.inset, top = if (i == 0) m.trayToGrid else 0.dp)
                    .onFocusChanged { if (it.hasFocus) onRowFocused(i + 2) }
                    .stopAtRowEnds(),
            ) {
                row.forEach { cell ->
                    // Keyed: a cell's composition (its focus, its glide) moves with it when its row shifts.
                    androidx.compose.runtime.key(cellKey(cell)) {
                    Box(Modifier.weight(1f).glide(cellKey(cell)).onFocusChanged { if (it.hasFocus) GridHost.col = row.indexOf(cell) }) {
                        when (cell) {
                            is Cell.Settings -> SettingsCell(requester(SETTINGS_TILE_KEY), { onFocused(SETTINGS_TILE_KEY, i + 2) }, onSettings)
                            is Cell.Item -> when (val item = cell.item) {
                                is GridItem.App -> AppCell(
                                    app = item.app,
                                    model = model,
                                    moving = moving == item.key,
                                    focusRequester = requester(item.key),
                                    onFocused = { onFocused(item.key, i + 2) },
                                    onMenu = { onAppMenu(item.app, false) },
                                    launch = launch,
                                )
                                is GridItem.FolderItem -> FolderCell(
                                    item = item,
                                    model = model,
                                    moving = moving == item.key,
                                    focusRequester = requester(item.key),
                                    onFocused = { onFocused(item.key, i + 2) },
                                    onOpen = { onFolderOpen(item.folder.id) },
                                    onMenu = { onFolderMenu(item.folder) },
                                )
                            }
                        }
                    }
                    }
                }
                repeat(m.columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        } } } }
            val gridLatest = androidx.compose.runtime.rememberUpdatedState(grid)
            val gridMovable = remember { androidx.compose.runtime.movableContentOf { gridLatest.value() } }
            if (TrayHost.inMain) gridMovable()
            else Box(Modifier.fillMaxWidth().height(with(d) { GridHost.h.toDp() }).onGloballyPositioned { GridHost.y = it.positionInWindow().y })
            androidx.compose.runtime.SideEffect {
                GridHost.content = gridMovable
                // Down from the last row: the add-on rows in Home's view when there are any, else focus stays (Home's view has nothing below).
                GridHost.down = { dev.glasslauncher.addons.AddonSurfaces.homeRows.isEmpty() }
                // Up from the grid's first row: the tray, same column (the views don't search each other).
                GridHost.up = { dockShown.getOrNull(GridHost.col.coerceIn(0, (dockShown.size - 1).coerceAtLeast(0)))?.let { a -> runCatching { requester(appKey(a.packageName)).requestFocus() }.isSuccess } ?: false }
            }
        }
        // Add-ons' rows come after the apps, so nothing above them moves; they count as grid rows for focus.
        dev.glasslauncher.addons.AddonSurfaces.homeRows.values.sortedBy { it.pkg }.forEachIndexed { k, shown ->
            item(key = "addon-row-${shown.pkg}") { AddonRow(shown) { onRowFocused(rows.size + 2 + k) } }
        }
    }
    }
}


/** How long a Cover Flow rests on a cover before its background is baked (P33): quick steps don't each bake. */
private const val COVER_SETTLE_MS = 150L

/** tvOS shows "⌃ Swipe up for full screen" just above the tray. */
@Composable
private fun ShelfHint(alpha: () -> Float) {
    // tvOS 27: just a wide, flat chevron above the tray (no words), on the art itself.
    val onLight = LocalBackdrop.current.backdrop?.artLight(0.35f, 0.6f, 0.65f, 0.7f) == true
    val color = (if (onLight) Color(0xFF0E1015) else Color.White).copy(alpha = 0.4f)
    androidx.compose.foundation.Canvas(
        Modifier.padding(bottom = 14.dp).size(28.dp, 9.dp).graphicsLayer { this.alpha = alpha() }.testTag("shelf-chevron"),
    ) {
        // The provided SVG's shape (55×17 viewbox): a stretched chevron with round caps.
        val sx = size.width / 55f; val sy = size.height / 17f
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(3f * sx, 14f * sy); lineTo(27.5f * sx, 3f * sy); lineTo(52f * sx, 14f * sy)
        }
        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.6f * sy * 17f / 9f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

/** HOME-01: the top row lives in one large rounded glass slab spanning nearly the full width. */
@Composable
private fun DockTray(
    apps: List<AppEntry>,
    model: HomeModel,
    moving: String?,
    requester: (String) -> FocusRequester,
    onFocused: (String) -> Unit,
    onAppMenu: (AppEntry) -> Unit,
    launch: (AppEntry, Rect?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val m = LocalMetrics.current
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = m.trayMargin)
            .glass(LocalBackdrop.current, RoundedCornerShape(m.trayRadius), GlassStyle.shelf(palette.light))
            .testTag("tray")
            // Icons only (no names under them), so they sit centred: equal space above and below.
            .padding(horizontal = m.inset - m.trayMargin, vertical = m.trayPadVertical),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(m.gutter), modifier = Modifier.fillMaxWidth().stopAtRowEnds().testTag("dock")) {
            apps.take(m.columns).forEach { app ->
              androidx.compose.runtime.key(appKey(app.packageName)) {
                Box(Modifier.weight(1f).glide(appKey(app.packageName))) {
                    val key = appKey(app.packageName)
                    AppCell(
                        app = app,
                        model = model,
                        moving = moving == key,
                        focusRequester = requester(key),
                        onFocused = { onFocused(key) },
                        onMenu = { onAppMenu(app) },
                        launch = launch,
                        // The top row is icons only (no name under the focused app).
                        showLabel = false,
                        floatingLabel = false,
                    )
                }
              }
            }
            repeat(m.columns - apps.take(m.columns).size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/**
 * A tile's last layout coordinates. Its window bounds are worked out only when a click or a menu needs them,
 * not on every placement (a scrolling grid places every tile each frame).
 */
private class TileBounds {
    var coords: androidx.compose.ui.layout.LayoutCoordinates? = null
    fun rect(): Rect? = coords?.takeIf { it.isAttached }?.boundsInWindow()
}

@Composable
fun AppCell(
    app: AppEntry,
    model: HomeModel,
    moving: Boolean,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onMenu: () -> Unit,
    launch: ((AppEntry, Rect?) -> Unit)? = null,
    showLabel: Boolean = true,
    floatingLabel: Boolean = false,
) {
    val tile = rememberTile(model, app)
    val art = tile?.image
    val isNew by rememberTileValue(model, app.packageName) { model.isNew(app.packageName, it) }
    val installs by dev.glasslauncher.apps.InstallWatch.states.collectAsStateWithLifecycle()
    val install = installs[app.packageName]
    val tileBounds = remember { TileBounds() }
    val anchorStore = LocalMenuAnchor.current
    val launchView = LocalView.current
    val transition = LocalAppTransition.current
    TileWithLabel(
        label = app.label,
        tag = "app:${app.packageName}",
        moving = moving,
        isNew = isNew,
        install = install,
        focusRequester = focusRequester,
        onFocused = onFocused,
        // Launch from the tile as drawn (focused, 1.2x), so the app zooms out of what you see.
        onClick = {
            val from = tileBounds.rect()?.let { val dx = it.width * 0.1f; val dy = it.height * 0.1f; Rect(it.left - dx, it.top - dy, it.right + dx, it.bottom + dy) }
            when {
                transition != null && from != null -> transition.open(app, from, art, launchView)
                launch != null -> launch(app, from)
                else -> model.launch(app, launchView, from)
            }
        },
        onMenu = { anchorStore.bounds = tileBounds.rect(); onMenu() },
        showLabel = showLabel,
        floatingLabel = floatingLabel,
        tileModifier = Modifier.onGloballyPositioned { tileBounds.coords = it },
    ) {
        art?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
private fun FolderCell(
    item: GridItem.FolderItem,
    model: HomeModel,
    moving: Boolean,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
) {
    val tileBounds = remember { TileBounds() }
    val anchorStore = LocalMenuAnchor.current
    TileWithLabel(
        label = item.folder.name,
        tag = "folder:${item.folder.id}",
        moving = moving,
        isNew = false,
        focusRequester = focusRequester,
        onFocused = onFocused,
        onClick = { anchorStore.bounds = tileBounds.rect(); onOpen() },
        tileModifier = Modifier.onGloballyPositioned { tileBounds.coords = it },
        onMenu = { anchorStore.bounds = tileBounds.rect(); onMenu() },
        glassBackground = true,
    ) {
        // HOME-06: a glass tile holding a 3x2 mini grid, filled from the top left like tvOS.
        Column(
            verticalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.fillMaxSize().padding(horizontal = 11.dp, vertical = 10.dp),
        ) {
            item.apps.take(6).chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { app ->
                        val art = rememberArt(model, app)
                        Box(Modifier.weight(1f).aspectRatio(5f / 3f).graphicsLayer { shape = RoundedCornerShape(5.dp); clip = true }) {
                            art?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** The launcher's own Settings, presented like the Apple TV Settings app tile at the end of the grid. */
@Composable
private fun SettingsCell(focusRequester: FocusRequester, onFocused: () -> Unit, onOpen: () -> Unit) {
    val tileBounds = remember { TileBounds() }
    val anchorStore = LocalMenuAnchor.current
    TileWithLabel(
        label = "Settings",
        tag = "settings-tile",
        moving = false,
        isNew = false,
        focusRequester = focusRequester,
        onFocused = onFocused,
        // The tile's bounds go to the home screen (as an app's do) so Settings can zoom out of it.
        onClick = { anchorStore.bounds = tileBounds.rect(); onOpen() },
        onMenu = { anchorStore.bounds = tileBounds.rect(); onOpen() },
        tileModifier = Modifier.onGloballyPositioned { tileBounds.coords = it },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF9CA3AF), Color(0xFF5B616B)))),
            contentAlignment = Alignment.Center,
        ) {
            dev.glasslauncher.widgets.GearIcon(Color(0xFFF2F3F5), size = 44.dp)
        }
    }
}

/**
 * A 5:3 tile with the tvOS focus treatment, and its name in small grey text below it only while
 * focused (HOME-04). Newly installed apps get a blue dot before the name.
 */
@Composable
fun TileWithLabel(
    label: String,
    tag: String,
    moving: Boolean,
    isNew: Boolean,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    /** The app is being installed or updated (P168): a progress badge on the tile's corner. */
    install: dev.glasslauncher.apps.InstallState? = null,
    glassBackground: Boolean = false,
    showLabel: Boolean = true,
    /** In the tray: the name still shows on focus, in the tray's own bottom padding (no row gap to reserve). */
    floatingLabel: Boolean = false,
    tileModifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val palette = LocalPalette.current
    val m = LocalMetrics.current
    var focused by remember { mutableStateOf(false) }
    // Tile first, then name: the new label waits ~80 ms and fades in fast; the old one fades over ~130 ms.
    val labelAlpha by animateFloatAsState(if (focused) 1f else 0f, if (focused) Motion.labelIn() else Motion.labelOut(), label = "label")
    Column {
    Box {
        FocusTile(
            // The blue dot isn't colour-only: "New" is spoken too.
            label = listOfNotNull(label, if (isNew) "New" else null, install?.let { installSpoken(it) }).joinToString(", "),
            onClick = onClick,
            onLongClick = onMenu,
            wiggle = moving,
            shadow = !glassBackground,
            edgeLight = true,
            shape = RoundedCornerShape(m.tileRadius),
            onFocusChange = { focused = it; if (it) onFocused() },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(m.tileAspect)
                .then(tileModifier)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .testTag(tag),
        ) {
            // Inside the tile's scaled layer, so the glass grows with its contents on focus. Frosted,
            // not the tray's clear glass: no refracted edge band per folder.
            if (glassBackground) {
                Box(Modifier.fillMaxSize().glass(LocalBackdrop.current, RoundedCornerShape(m.tileRadius), GlassStyle.shelf(palette.light).copy(clear = false))) { content() }
            } else content()
        }
        if (install != null) InstallBadge(install, Modifier.align(Alignment.TopEnd).padding(6.dp))
        if (showLabel || floatingLabel || isNew) {
            // Sits in the row gap below the tile so labels never change the layout.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationY = (if (floatingLabel) 7.dp else 12.dp).toPx() + size.height
                        alpha = labelAlpha
                    },
            ) {
                if (isNew) Box(Modifier.size(6.dp).background(palette.accent, CircleShape))
                if (showLabel || floatingLabel) Text(
                    text = label,
                    style = Type.caption.copy(fontSize = Type.caption.fontSize * 0.98f),
                    color = palette.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    if (showLabel) Spacer(Modifier.height(m.labelSpace))
    }
}

/**
 * One value a tile derives from the config. A tile recomposes only when that value changes, not on every
 * config edit (there are dozens of tiles, and most edits touch nothing they draw).
 */
@Composable
private fun <T> rememberTileValue(model: HomeModel, vararg keys: Any?, derive: (dev.glasslauncher.data.LauncherConfig) -> T): State<T> {
    val cfg = model.config.collectAsStateWithLifecycle()
    return remember(model, *keys) { derivedStateOf { derive(cfg.value) } }
}

@Composable
fun rememberArt(model: HomeModel, app: AppEntry): ImageBitmap? = rememberTile(model, app)?.image

/** The app's tile art, null until it has loaded. */
@Composable
fun rememberTile(model: HomeModel, app: AppEntry): LoadedTile? {
    val context = LocalContext.current
    val graph = context.app
    val spec by rememberTileValue(model, app) { model.spec(app, it) }
    val tile by produceState(graph.tileArt.peekTile(spec), spec) { value = graph.tileArt.loadTile(spec) }
    return tile
}

/**
 * The Top Shelf's own hero for a tray app (tvOS's "logo only" shelf, e.g. Peacock's): the app's logo
 * large and centred on its own brand colour (the backdrop), or for full-bleed banners the banner itself
 * over a blurred wash of it.
 */
/** The app's own logo art (banner or icon, drawn sharp) as its hero, for apps Amazon has no Fire TV icon for. */
private suspend fun appHeroBackdrop(
    graph: dev.glasslauncher.GlassApp, layout: HomeLayout, pkg: String, dark: Boolean,
): dev.glasslauncher.glass.Backdrop? = kotlinx.coroutines.withContext(dev.glasslauncher.glass.WallpaperLoader.BakeDispatcher) {
    val app = layout.installed.firstOrNull { it.packageName == pkg } ?: return@withContext null
    val art = graph.tileArt.cachedHeroArt(app) ?: return@withContext null
    runCatching { graph.wallpapers.fromHeroArt(art, light = !dark) }.getOrNull().also { art.recycle() }
}


/**
 * Whether the overlay at [index] of [stack] stays drawn under the card on top: a confirm card sits over the page it
 * came from, and a confirm over a confirm over that page too, so everything beneath a top confirm stays visible (P64).
 */
internal fun keptUnderCard(stack: List<Overlay>, index: Int): Boolean =
    index >= 0 && (stack.lastOrNull().let { it is Overlay.Confirm || it is Overlay.InstallConfirm }) && index < stack.lastIndex &&
        // Only a full page (Settings and its kin) stays drawn under the card; a side panel or menu goes, so the card is the
        // one glass layer over Home instead of a second one stacked on the panel (P105 #6).
        when (stack[index]) { Overlay.Settings, Overlay.TvSettings, Overlay.ControlCenter, Overlay.Remotes, Overlay.Downloads, Overlay.Files, Overlay.AppSwitcher, is Overlay.Confirm, is Overlay.InstallConfirm -> true; else -> false }

/** P203: how long after Home appears Control Center is composed (parked), so it doesn't compete with Home's first frames. */
private const val CC_PARK_AFTER_MS = 1_500L

/** P205: how long Home stays on a scene before its blur ladder is uploaded (a dock move every ~0.3 s uploads nothing). */
private const val LADDER_AFTER_MS = 250L

/** A parked Control Center: no focus can enter it, and nothing of it is in the semantics tree (TalkBack, tests). */
private fun Modifier.ccParked(): Modifier = this
    // Not drawn at all, not just at alpha 0: HWUI then lets go of the parked tree's display lists and the textures
    // they hold (drawn at alpha 0 it kept ~12 MB of GPU memory, over P203's 8 MB budget).
    .drawWithContent { }
    // Tagged, so the device tests' unmerged dump can leave the parked tree out (it still lists nodes that clearAndSetSemantics hid from TalkBack).
    .clearAndSetSemantics { this[androidx.compose.ui.semantics.SemanticsProperties.TestTag] = dev.glasslauncher.system.SemanticsDump.PARKED }
    .focusProperties { onEnter = { cancelFocusChange() } }
    .focusGroup()

/** P248: the dock tray in its own ComposeView, a sibling of Home's, so a focus move repaints only the tray's band, not the whole screen (Compose invalidates its whole view). */
internal object TrayHost : SiblingHost()

/** P258: the app grid's rows in their own sibling view, like the tray. */
internal object GridHost : SiblingHost() {
    /** Each grid row's top inside the grid's slot, px: rows are no longer LazyColumn items, so scrolls aim at slot offsets. */
    var rowTops = IntArray(0)
    override var split: Boolean
        get() = TrayHost.split
        set(v) { TrayHost.split = v }
    // Under an overlay the grid moves into Home's view with the tray.
    override var inMain: Boolean
        get() = TrayHost.inMain
        set(v) { TrayHost.inMain = v }
}

internal open class SiblingHost {
    var y by mutableFloatStateOf(0f)
    var h by mutableIntStateOf(0)
    var content: (@Composable () -> Unit)? by mutableStateOf(null)
    var view: android.view.View? = null
    open var inMain by mutableStateOf(false)
    /** Off in the JVM screenshot tests: their harness drives one ComposeView, so the tray stays in Home's (the overlay path). */
    @Volatile open var split = true
    /** For tests: compositions of the tray's slot in Home (bounded while nothing changes). */
    var slotCompositions = 0
    // The view's band reaches past the tray so a focused tile's lift and shadow aren't cut at its edge.
    private val SHADOW_TOP = 12.dp
    private val SHADOW_BOTTOM = 32.dp
    private var padTop by mutableFloatStateOf(0f)
    var col = 0
    var down: () -> Boolean = { false }
    var up: () -> Boolean = { false }
    var keys: ((androidx.compose.ui.input.key.KeyEvent) -> Boolean)? = null

    @Composable
    fun Attach() {
        if (!split) return
        val main = androidx.compose.ui.platform.LocalView.current
        val parent = androidx.compose.runtime.rememberCompositionContext()
        androidx.compose.runtime.DisposableEffect(main) {
            val group = main.rootView.findViewById<android.view.ViewGroup>(android.R.id.content)
            val tray = androidx.compose.ui.platform.ComposeView(main.context).apply {
                setParentCompositionContext(parent)
                setContent {
                    val fm = androidx.compose.ui.platform.LocalFocusManager.current
                    padTop = with(androidx.compose.ui.platform.LocalDensity.current) { SHADOW_TOP.toPx() }
                    Box(Modifier.fillMaxWidth().semantics { testTagsAsResourceId = true }.padding(top = SHADOW_TOP, bottom = SHADOW_BOTTOM).onPreviewKeyEvent { e ->
                        if (keys?.invoke(e) == true) return@onPreviewKeyEvent true
                        val up = e.key == androidx.compose.ui.input.key.Key.DirectionUp
                        if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown || !(up || e.key == androidx.compose.ui.input.key.Key.DirectionDown)) return@onPreviewKeyEvent false
                        // Up and Down leave the tray: Home's view takes focus in that direction, from the tray's band.
                        val band = android.graphics.Rect(0, y.toInt(), main.width, y.toInt() + h)
                        if (!fm.moveFocus(if (up) androidx.compose.ui.focus.FocusDirection.Up else androidx.compose.ui.focus.FocusDirection.Down) && !(if (up) this@SiblingHost.up() else down()))
                            main.requestFocus(if (up) android.view.View.FOCUS_UP else android.view.View.FOCUS_DOWN, band)
                        true
                    }) { Box(Modifier.fillMaxWidth().onSizeChanged { if (!inMain) h = it.height }) { if (!inMain) content?.invoke() } }
                }
            }
            view = tray
            // Read before the grid, as when the tray was part of Home's view.
            // Home's own view (the composition this item lives in), not whichever child happens to be first.
            val home = generateSequence(main) { it.parent as? android.view.View }.first { it.parent === group }
            if (home.id == android.view.View.NO_ID) home.id = android.view.View.generateViewId()
            tray.accessibilityTraversalBefore = home.id
            group.addView(tray, android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
            onDispose { group.removeView(tray); view = null }
        }
        val y = this@SiblingHost.y - padTop
        androidx.compose.runtime.SideEffect { view?.translationY = y }
    }
}
