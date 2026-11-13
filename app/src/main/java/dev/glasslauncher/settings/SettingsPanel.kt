package dev.glasslauncher.settings

import dev.glasslauncher.data.ScreensaverMode
import dev.glasslauncher.system.SystemControls
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.platform.testTag
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.relocation.bringIntoViewRequester
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.glasslauncher.BuildConfig
import dev.glasslauncher.app
import dev.glasslauncher.data.AerialQuality
import dev.glasslauncher.data.Auto
import dev.glasslauncher.data.BackgroundMode
import dev.glasslauncher.system.PhoneField
import dev.glasslauncher.data.FeaturedMode
import dev.glasslauncher.data.FeaturedSourceId
import dev.glasslauncher.data.LauncherConfig
import dev.glasslauncher.data.ThemeMode
import dev.glasslauncher.data.Wallpaper
import dev.glasslauncher.data.WallpaperKind
import dev.glasslauncher.dream.AerialActivity
import dev.glasslauncher.dream.Screensaver
import dev.glasslauncher.featured.Plex
import dev.glasslauncher.featured.Sources
import dev.glasslauncher.featured.YouTube
import dev.glasslauncher.glass.WallpaperLoader
import dev.glasslauncher.home.HomeLayout
import dev.glasslauncher.home.HomeModel
import dev.glasslauncher.home.MenuList
import dev.glasslauncher.home.Overlay
import dev.glasslauncher.home.PanelTitle
import dev.glasslauncher.system.Backup
import dev.glasslauncher.system.HomeSetup
import dev.glasslauncher.system.RemoteAction
import dev.glasslauncher.system.RemoteButtons
import dev.glasslauncher.system.Release
import dev.glasslauncher.system.Updater
import dev.glasslauncher.ui.Hint
import dev.glasslauncher.ui.LocalPalette
import dev.glasslauncher.ui.MenuRow
import dev.glasslauncher.ui.SectionLabel
import dev.glasslauncher.ui.Shapes
import dev.glasslauncher.ui.ToggleRow
import dev.glasslauncher.ui.Type
import dev.glasslauncher.ui.dissolve
import dev.glasslauncher.widgets.NowPlayingSource
import dev.glasslauncher.widgets.Weather
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import androidx.compose.ui.text.style.TextOverflow
import dev.glasslauncher.ui.FocusTile

private sealed interface Page {
    data object Root : Page
    data object Appearance : Page
    data class Wallpapers(val dark: Boolean) : Page
    data object Featured : Page
    data object PlexLink : Page
    data object Hidden : Page
    /** Which apps may put a row in the notifications drawer (P98). */
    data object Notifications : Page
    data object HideMore : Page
    data object IconPack : Page
    data object Screensaver : Page
    data object ScreensaverChoice : Page
    data object StartAfter : Page
    data object AerialsPrefs : Page
    data object ChooseAerials : Page
    data object SlideshowPrefs : Page
    data object ChoosePhotos : Page
    data object Widgets : Page
    data object HomeButton : Page
    data object Updates : Page
    data object Backup : Page
    data object Downloads : Page
    data object AirPlay : Page
    data object AirPlayAbout : Page
    data object DownloadCodes : Page
    data object About : Page
    data object Accessibility : Page
    data object RemoteButtons : Page
    data object ControllerButtons : Page
    /** A game controller's or a remote's own page (P186), reached from Control Center. */
    data class Device(val kind: dev.glasslauncher.home.PageKind, val name: String) : Page
    data object RootTools : Page
    data object DisplayText : Page
    data object TextSize : Page
    data object Font : Page
    data object ControlCenterTiles : Page
    data object Addons : Page
    data class AddonForm(val pkg: String) : Page
    data object Freezer : Page
    data object RootLog : Page
    data class ButtonAction(val id: String) : Page
    data object CcShortcut : Page
    data class ButtonApp(val id: String) : Page
}

/** A one-shot request to open Settings on a given page (P174): set before opening the overlay, taken when the panel starts. */
object SettingsEntry {
    const val REMOTE_BUTTONS = "remote_buttons"
    const val CONTROLLER_BUTTONS = "controller_buttons"
    private const val DEVICE = "device:"

    /** The entry for one device's page: `device:<Gamepad|Remote>:<name>`. */
    fun device(kind: dev.glasslauncher.home.PageKind, name: String) = "$DEVICE${kind.name}:$name"

    /** The device an entry names, or null. */
    fun parseDevice(entry: String): Pair<dev.glasslauncher.home.PageKind, String>? {
        if (!entry.startsWith(DEVICE)) return null
        val kind = dev.glasslauncher.home.PageKind.entries.firstOrNull { entry.startsWith("$DEVICE${it.name}:") } ?: return null
        return kind to entry.removePrefix("$DEVICE${kind.name}:")
    }
    @Volatile var page: String? = null
    fun take(): String? = page.also { page = null }
}

/** A selected row that removes itself takes focus with it; focus goes back to the page's first row. */
private fun refocus(first: androidx.compose.ui.focus.FocusRequester) {
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runCatching { first.requestFocus() } }, 50)
}

@Composable
fun SettingsPanel(
    model: HomeModel,
    cfg: LauncherConfig,
    layout: HomeLayout,
    active: Boolean,
    open: (Overlay) -> Unit,
    close: () -> Unit,
) {
    var stack by remember {
        // Control Center's Remotes and Game Controllers pages open Settings straight on their button mapping (P174).
        val entry = SettingsEntry.take()
        val device = entry?.let { SettingsEntry.parseDevice(it) }
        mutableStateOf(when {
            entry == SettingsEntry.REMOTE_BUTTONS -> listOf(Page.Root, Page.RemoteButtons)
            entry == SettingsEntry.CONTROLLER_BUTTONS -> listOf(Page.Root, Page.ControllerButtons)
            // A device's own page is the whole stack: Back leaves Settings, as it left Control Center's list.
            device != null -> listOf<Page>(Page.Device(device.first, device.second))
            else -> listOf<Page>(Page.Root)
        })
    }
    fun push(p: Page) { stack = stack + p }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) else close() }
    BackHandler(enabled = active && stack.size > 1) { pop() }

    // tvOS page push: the new page slides in ~130 px from the right while fading in, the old one slides
    // left; reversed going back. Focus appears only once the page has landed.
    val reduceMotion = dev.glasslauncher.ui.LocalUiPrefs.current.reduceMotion
    androidx.compose.animation.AnimatedContent(
        targetState = stack,
        transitionSpec = {
            val forward = targetState.size >= initialState.size
            val dist = 130
            val ms = if (reduceMotion) 0 else dev.glasslauncher.ui.Motion.PAGE_MS
            val ease = androidx.compose.animation.core.LinearOutSlowInEasing
            (androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(ms, easing = ease)) { if (forward) dist else -dist } +
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(ms, easing = ease))) togetherWith
                (androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(ms, easing = ease)) { if (forward) -dist else dist } +
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(ms / 2)))
        },
        contentKey = { it.last() },
        label = "settings-page",
    ) { pages ->
        val page = pages.last()
        val landed = transition.currentState == transition.targetState
        val pageActive = active && landed && page == stack.last()
        if (page is Page.Wallpapers) {
            WallpaperPage(page.dark, model, cfg, pageActive, open)
        } else if (page is Page.Device) androidx.compose.runtime.CompositionLocalProvider(dev.glasslauncher.home.LocalPageKey provides page) {
            dev.glasslauncher.home.DevicePage(page.kind, page.name, cfg, { model.edit(it) }, pageActive) { id -> push(Page.ButtonApp(id)) }
        } else if (page == Page.ChooseAerials) androidx.compose.runtime.CompositionLocalProvider(dev.glasslauncher.home.LocalPageKey provides page) {
            ChooseAerialsPage(model, cfg, pageActive)
        } else androidx.compose.runtime.CompositionLocalProvider(
            dev.glasslauncher.home.LocalPageKey provides page,
            dev.glasslauncher.home.LocalConfirm provides { c -> open(c) },
            dev.glasslauncher.home.LocalOpenOverlay provides { o -> open(o) },
        ) { MenuList(pageActive) { first ->
            val f = Modifier.focusRequester(first)
            when (page) {
                Page.Root -> RootPage(model, cfg, f, ::push, open)
                Page.Appearance -> AppearancePage(model, cfg, f, ::push)
                Page.Featured -> FeaturedPage(model, cfg, f, open, ::push)
                Page.PlexLink -> PlexLinkPage(model, cfg, f, ::pop)
                Page.Hidden -> HiddenPage(model, cfg, layout, f, ::push) { refocus(first) }
                Page.Notifications -> NotificationsPage(model, cfg, layout, f)
                Page.HideMore -> HideMorePage(model, layout, f) { refocus(first) }
                Page.IconPack -> IconPackPage(model, cfg, f)
                Page.Screensaver -> ScreensaverPage(model, cfg, f, ::push)
                Page.ScreensaverChoice -> ScreensaverChoicePage(model, cfg, f)
                Page.StartAfter -> StartAfterPage(model, cfg, f)
                Page.AerialsPrefs -> AerialsPrefsPage(model, cfg, f, ::push)
                Page.SlideshowPrefs -> SlideshowPrefsPage(model, cfg, f, ::push)
                Page.ChoosePhotos -> ChoosePhotosPage(model, cfg, f)
                Page.ChooseAerials -> Unit
                Page.Widgets -> WidgetsPage(model, cfg, f, open)
                Page.HomeButton -> HomeButtonPage(model, cfg, f)
                Page.Updates -> UpdatesPage(f)
                Page.Backup -> BackupPage(model, f)
                Page.Downloads -> DownloadsSettingsPage(model, cfg, f, open) { push(Page.DownloadCodes) }
                Page.DownloadCodes -> DownloadCodesPage(model, cfg, f, open)
                Page.AirPlay -> AirPlayPageHost(pageActive, f, open) { push(Page.AirPlayAbout) }
                Page.AirPlayAbout -> AirPlayAboutPage(f, open)
                Page.About -> AboutPage(model, cfg, f)
                Page.Accessibility -> AccessibilityPage(model, cfg, f)
                Page.RemoteButtons -> RemoteButtonsPage(cfg, f, ::push)
                Page.ControllerButtons -> ControllerButtonsPage(cfg, f, ::push)
                Page.RootTools -> RootToolsPage(f, ::push)
                Page.DisplayText -> DisplayTextPage(model, cfg, f, ::push)
                Page.TextSize -> TextSizePage(model, cfg, f)
                Page.Font -> FontPage(model, cfg, f)
                Page.ControlCenterTiles -> ControlCenterTilesPage(model, cfg, f)
                Page.Addons -> AddonsPage(model, cfg, f, ::push)
                is Page.AddonForm -> AddonFormPage(model, cfg, page.pkg, f, open)
                Page.Freezer -> FreezerPage(f)
                Page.RootLog -> RootLogPage(f)
                is Page.ButtonAction -> ButtonActionPage(model, cfg, page.id, f, ::push, ::pop)
                Page.CcShortcut -> CcShortcutPage(model, cfg, f, ::pop)
                is Page.ButtonApp -> ButtonAppPage(model, layout, page.id, f) { pop(); if (stack.lastOrNull() is Page.ButtonAction) pop() }
                is Page.Wallpapers -> Unit
                is Page.Device -> Unit
            }
        } }
    }
}

@Composable
private fun ColumnScope.AirPlayPageHost(active: Boolean, f: Modifier, open: (Overlay) -> Unit, about: () -> Unit) {
    val (ui, hub) = dev.glasslauncher.system.AirPlay.rememberAirPlay(active)
    AirPlaySettingsPage(ui, hub, f, open, about)
}

@Composable
private fun ColumnScope.RootPage(model: HomeModel, cfg: LauncherConfig, f: Modifier, push: (Page) -> Unit, open: (Overlay) -> Unit) {
    val context = LocalContext.current
    val graph = context.app
    val scope = rememberCoroutineScope()
    PanelTitle("Settings")
    // Grouped, as tvOS's Settings are, so the list isn't one long run of rows.
    SectionLabel("Home Screen")
    MenuRow("Appearance", { push(Page.Appearance) }, f, value = cfg.theme.name, chevron = true)
    MenuRow("Display & Text", { push(Page.DisplayText) }, value = textSizeName(cfg.textScale), chevron = true)
    MenuRow("Control Center", { push(Page.ControlCenterTiles) }, chevron = true)
    // Only when an add-on is installed, so Settings stays as it was for everyone else.
    val addons by androidx.compose.runtime.produceState(emptyList<dev.glasslauncher.addons.AddonInfo>()) { value = dev.glasslauncher.addons.AddonHost.installed(context) }
    if (addons.isNotEmpty()) MenuRow("Add-ons", { push(Page.Addons) }, value = addons.count { dev.glasslauncher.addons.AddonTrust.isEnabled(cfg.addonsEnabled, it.pkg, it.cert) }.toString(), chevron = true)
    MenuRow("Top Shelf Content", { push(Page.Featured) }, value = featuredSummary(cfg.featured), chevron = true)
    MenuRow("Hidden Apps", { push(Page.Hidden) }, value = cfg.hidden.size.toString(), chevron = true)
    MenuRow("Notifications", { push(Page.Notifications) }, value = if (cfg.blockedNoticePackages.isEmpty()) "All" else "${cfg.blockedNoticePackages.size} blocked", chevron = true)
    MenuRow("Icon Pack", { push(Page.IconPack) }, value = if (cfg.iconPack == null) "None" else "On", chevron = true)
    MenuRow("Screen Saver", { push(Page.Screensaver) }, chevron = true)
    MenuRow("Widgets", { push(Page.Widgets) }, chevron = true)
    SectionLabel("Remote & Accessibility")
    MenuRow("Accessibility", { push(Page.Accessibility) }, chevron = true)
    MenuRow("Home Button", { push(Page.HomeButton) }, chevron = true)
    MenuRow("Remote Buttons", { push(Page.RemoteButtons) }, chevron = true)
    SectionLabel("System")
    MenuRow("Set Up from Phone", {
        open(Overlay.PhoneSetup(
            "VITRE Setup",
            listOf(
                PhoneField("tmdb", "TMDB API key or read token", cfg.featured.tmdbKey, "For Netflix, Prime Video, Apple TV+… rows", secret = true),
                PhoneField("youtube", "YouTube Data API key", cfg.featured.youtubeKey, "For the YouTube row", secret = true),
                PhoneField("city", "Weather city", cfg.weather?.city?.substringBefore(',') ?: "", "e.g. Toronto"),
                PhoneField("wallpaper", "Wallpaper image URL", "", "https://…"),
            ),
        ) { v ->
            v["tmdb"]?.takeIf { it.isNotBlank() }?.let { k -> model.edit { it.copy(featured = it.featured.copy(tmdbKey = k)) } }
            v["youtube"]?.takeIf { it.isNotBlank() }?.let { k -> model.edit { it.copy(featured = it.featured.copy(youtubeKey = k)) } }
            v["city"]?.takeIf { it.isNotBlank() }?.let { q ->
                scope.launch { Weather.geocode(graph.http, q, cfg.weather?.fahrenheit ?: false)?.let { w -> model.edit { it.copy(weather = w) } } }
            }
            v["wallpaper"]?.takeIf { it.isNotBlank() }?.let { url ->
                scope.launch {
                    if (graph.wallpapers.downloadUrl(url)) model.edit {
                        val w = Wallpaper(WallpaperKind.Url, url)
                        if (it.theme == ThemeMode.Light) it.copy(wallpaperLight = w) else it.copy(wallpaperDark = w)
                    }
                }
            }
        })
    }, value = "QR code")
    // Only on rooted devices (su present and granted).
    val rooted by androidx.compose.runtime.produceState(dev.glasslauncher.system.Root.known) { value = dev.glasslauncher.system.Root.available() }
    MenuRow("Root", { push(Page.RootTools) }, value = if (rooted) "Detected" else "Not detected", chevron = true)
    MenuRow("Updates", { push(Page.Updates) }, value = BuildConfig.VERSION_NAME, chevron = true)
    MenuRow("Downloads", { push(Page.Downloads) }, chevron = true)
    val airPlay = dev.glasslauncher.system.AirPlay.rememberAirPlay(true).first
    MenuRow("AirPlay", { push(Page.AirPlay) }, value = airPlayRowValue(airPlay).ifEmpty { null }, chevron = true)
    MenuRow("Backup & Restore", { push(Page.Backup) }, chevron = true)
    MenuRow("About", { push(Page.About) }, chevron = true)
}

private fun featuredSummary(fc: dev.glasslauncher.data.FeaturedConfig) = when {
    fc.mode == FeaturedMode.Off || fc.source == FeaturedSourceId.Off -> "Off"
    fc.mode == FeaturedMode.FocusedApp -> "Focused App"
    else -> sourceName(fc.source)
}

private fun sourceName(id: FeaturedSourceId) = when (id) {
    FeaturedSourceId.Off -> "Off"
    FeaturedSourceId.Stremio -> "Stremio"
    FeaturedSourceId.Tmdb -> "TMDB"
    FeaturedSourceId.YouTube -> "YouTube"
    FeaturedSourceId.Plex -> "Plex"
    FeaturedSourceId.ContinueWatching -> "Continue Watching"
    FeaturedSourceId.TvApp -> "App's Own Row"
    FeaturedSourceId.JustWatch -> "JustWatch"
    FeaturedSourceId.Addon -> "Add-on"
}

@Composable
private fun ColumnScope.AppearancePage(model: HomeModel, cfg: LauncherConfig, f: Modifier, push: (Page) -> Unit) {
    val screen = dev.glasslauncher.ui.LocalScreenDissolve.current
    PanelTitle("Appearance")
    MenuRow("Theme", {
        val next = ThemeMode.entries[(cfg.theme.ordinal + 1) % ThemeMode.entries.size]
        screen.dissolve { model.edit { it.copy(theme = next) } }
    }, f, value = cfg.theme.name)
    MenuRow("Background", {
        val next = BackgroundMode.entries[(cfg.background.ordinal + 1) % BackgroundMode.entries.size]
        screen.dissolve { model.edit { it.copy(background = next) } }
    }, value = when (cfg.background) { BackgroundMode.Featured -> "Top Shelf"; BackgroundMode.Wallpaper -> "Wallpaper"; BackgroundMode.Motion -> "Motion (Aerials)" },
        help = "Top Shelf: the focused app and its titles. Motion: Apple's Aerial videos behind your apps.")
    MenuRow("Dark Mode Wallpaper", { push(Page.Wallpapers(dark = true)) }, value = wallpaperName(cfg.wallpaperDark), chevron = true)
    MenuRow("Light Mode Wallpaper", { push(Page.Wallpapers(dark = false)) }, value = wallpaperName(cfg.wallpaperLight), chevron = true)
    val fadeOptions = listOf(1, 2, 3, 5, 10, 0)
    MenuRow("Hide UI When Idle", {
        val next = fadeOptions[(fadeOptions.indexOf(cfg.idleFadeMinutes).coerceAtLeast(0) + 1) % fadeOptions.size]
        model.edit { it.copy(idleFadeMinutes = next) }
    }, value = if (cfg.idleFadeMinutes == 0) "Never" else "${cfg.idleFadeMinutes} min",
        help = "Fades your apps and the clock out when nothing is happening, leaving the background, and stops Home's animation. Any button brings them back. Protects OLED and plasma screens.")
}

private fun wallpaperName(w: Wallpaper) = when (w.kind) {
    WallpaperKind.Preset -> WallpaperLoader.presets.firstOrNull { it.id == w.value }?.name ?: "Preset"
    WallpaperKind.File -> "Image"
    WallpaperKind.Url -> "Web Image"
}

@Composable
private fun WallpaperPage(dark: Boolean, model: HomeModel, cfg: LauncherConfig, active: Boolean, open: (Overlay) -> Unit) {
    val graph = LocalContext.current.app
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    val current = if (dark) cfg.wallpaperDark else cfg.wallpaperLight
    fun set(w: Wallpaper) = model.edit { if (dark) it.copy(wallpaperDark = w) else it.copy(wallpaperLight = w) }
    val first = remember { FocusRequester() }
    LaunchedEffect(active) { if (active) { delay(16); runCatching { first.requestFocus() } } }

    Column(Modifier.fillMaxSize().padding(start = 14.dp, top = 22.dp, end = 14.dp)) {
        PanelTitle(if (dark) "Dark Wallpaper" else "Light Wallpaper")
        WallpaperLoader.presets.forEachIndexed { i, preset ->
            val swatch = remember(preset.id) { graph.wallpapers.renderPreset(preset, 96, 54).asImageBitmap() }
            MenuRow(
                preset.name,
                { set(Wallpaper(WallpaperKind.Preset, preset.id)) },
                if (i == 0) Modifier.focusRequester(first) else Modifier,
                value = if (current.kind == WallpaperKind.Preset && current.value == preset.id) "✓" else if (preset.light) "Light" else "Dark",
                leading = {
                    Image(swatch, null, contentScale = ContentScale.Crop, modifier = Modifier.width(44.dp).height(25.dp).graphicsLayer { shape = Shapes.row; clip = true })
                },
            )
        }
        MenuRow("Image from URL…", {
            open(Overlay.TextInput("Wallpaper URL", if (current.kind == WallpaperKind.Url) current.value else "https://", "A large landscape image works best.") { url ->
                scope.launch {
                    status = "Downloading…"
                    if (graph.wallpapers.downloadUrl(url)) { set(Wallpaper(WallpaperKind.Url, url)); status = null }
                    else status = "Couldn't load an image from that address."
                }
            })
        }, value = if (current.kind == WallpaperKind.Url) "✓" else null)
        status?.let { Hint(it) }
        SectionLabel("On this device")
        ImagePicker(
            modifier = Modifier.weight(1f),
            // The page's 22 dp bottom inset, inside the grid so a focused tile's shadow isn't cut short.
            bottomInset = 22.dp,
            onPicked = { uri ->
                scope.launch {
                    status = "Importing…"
                    val path = graph.wallpapers.importImage(uri, "wallpaper-${if (dark) "dark" else "light"}-${System.currentTimeMillis()}")
                    if (path != null) { set(Wallpaper(WallpaperKind.File, path)); status = null } else status = "Couldn't read that image."
                }
            },
        )
    }
}

@Composable
private fun ColumnScope.FeaturedPage(model: HomeModel, cfg: LauncherConfig, f: Modifier, open: (Overlay) -> Unit, push: (Page) -> Unit) {
    val graph = LocalContext.current.app
    val scope = rememberCoroutineScope()
    val fc = cfg.featured
    fun setFeatured(transform: (dev.glasslauncher.data.FeaturedConfig) -> dev.glasslauncher.data.FeaturedConfig) =
        model.edit { it.copy(featured = transform(it.featured)) }

    PanelTitle("Top Shelf Content")
    // tvOS 27: the focused app's own hero at once, its titles after resting on it. Never keeps the hero
    // only (no title fetches, no slideshow bakes).
    MenuRow("Show Titles", { model.edit { it.copy(topShelfTitles = !it.topShelfTitles) } }, f,
        help = "Automatically: an app's titles appear after resting on it for a moment.",
        value = if (cfg.topShelfTitles) "Automatically" else "Never")
    // Turning it off keeps the default source, so turning it back on restores it.
    val off = fc.mode == FeaturedMode.Off || fc.source == FeaturedSourceId.Off
    val defaultSource = fc.source.takeIf { it != FeaturedSourceId.Off } ?: FeaturedSourceId.Stremio
    SectionLabel("Show content from")
    MenuRow("Focused App", { setFeatured { it.copy(mode = FeaturedMode.FocusedApp, source = defaultSource) } }, value = if (!off && fc.mode == FeaturedMode.FocusedApp) "✓" else null,
        help = "Each app's own titles: its TV rows, its catalog, or its popular titles from JustWatch.")
    MenuRow("One Source", { setFeatured { it.copy(mode = FeaturedMode.OneSource, source = defaultSource) } }, value = if (!off && fc.mode == FeaturedMode.OneSource) "✓" else null,
        help = "The same titles whichever app is focused, from the Source below.")
    MenuRow("Off", { setFeatured { it.copy(mode = FeaturedMode.Off) } }, value = if (off) "✓" else null)
    if (off) return
    // The same list does two jobs: the one source in One Source mode, and the fallback for an app with nothing to show in Focused App mode.
    SectionLabel(if (fc.mode == FeaturedMode.OneSource) "Source" else "When an app has nothing to show")
    val tvRows = dev.glasslauncher.featured.TvRows.available(LocalContext.current)
    FeaturedSourceId.entries.filter { it != FeaturedSourceId.Off && it != FeaturedSourceId.TvApp && it != FeaturedSourceId.JustWatch && it != FeaturedSourceId.Addon && (it != FeaturedSourceId.ContinueWatching || tvRows) }.forEach { id ->
        MenuRow(sourceName(id), { setFeatured { it.copy(source = id) } }, value = if (fc.source == id) "✓" else null)
    }
    // Add-ons that supply a Top Shelf feed (switched on in Settings > Add-ons).
    val feedAddons by androidx.compose.runtime.produceState(emptyList<dev.glasslauncher.addons.AddonInfo>(), cfg.addonsEnabled) {
        value = dev.glasslauncher.addons.AddonHost.installed(graph).filter { dev.glasslauncher.addon.AddonPoint.TOPSHELF in it.points && dev.glasslauncher.addons.AddonTrust.isEnabled(cfg.addonsEnabled, it.pkg, it.cert) }
    }
    feedAddons.forEach { a ->
        MenuRow("${a.title} (add-on)", { setFeatured { it.copy(source = FeaturedSourceId.Addon, addonPackage = a.pkg) } }, value = if (fc.source == FeaturedSourceId.Addon && fc.addonPackage == a.pkg) "✓" else null)
    }
    if (fc.mode == FeaturedMode.FocusedApp) {
        // Every service an app can use, whichever is the default.
        SectionLabel("Services")
        MenuRow("TMDB API Key", {
            open(Overlay.TextInput("TMDB API Key", fc.tmdbKey, "Free at themoviedb.org → Settings → API. Used for Netflix, Prime Video, Disney+, Apple TV, Max and Hulu.") { k -> setFeatured { it.copy(tmdbKey = k) } })
        }, value = mask(fc.tmdbKey))
        MenuRow("YouTube Channels", {
            open(Overlay.TextInput("YouTube Channels", fc.youtubeChannels, "Channel IDs (UC…) or channel URLs, separated by spaces. Uses each channel's public feed; no sign-in.") { c -> setFeatured { it.copy(youtubeChannels = c) } })
        }, value = channelsSummary(fc.youtubeChannels))
        MenuRow("YouTube API Key", {
            open(Overlay.TextInput("YouTube Data API Key", fc.youtubeKey, "Create one in Google Cloud Console with YouTube Data API v3 enabled.") { k -> setFeatured { it.copy(youtubeKey = k) } })
        }, value = mask(fc.youtubeKey))
        if (fc.plexToken.isBlank()) MenuRow("Plex", { push(Page.PlexLink) }, value = "Sign In")
        else {
            val askPlex = dev.glasslauncher.home.LocalConfirm.current
            MenuRow("Plex", { askPlex(dev.glasslauncher.home.Overlay.Confirm("Sign Out of Plex?", "The Top Shelf stops showing your Plex titles until you sign in again.", "Sign Out", destructive = true) { setFeatured { it.copy(plexToken = "") } }) }, value = "Sign Out")
        }
    }
    when (fc.source) {
        FeaturedSourceId.Stremio -> {
            SectionLabel("Catalog")
            Sources.stremioCatalogs.forEach { (key, name) ->
                MenuRow(name, { setFeatured { it.copy(stremioCatalog = key) } }, value = if (fc.stremioCatalog == key) "✓" else null)
            }
        }
        FeaturedSourceId.Tmdb -> {
            SectionLabel("TMDB")
            MenuRow("API Key", {
                open(Overlay.TextInput("TMDB API Key", fc.tmdbKey, "Free at themoviedb.org → Settings → API. Either the v3 key or the read access token works.") { k -> setFeatured { it.copy(tmdbKey = k) } })
            }, value = mask(fc.tmdbKey))
            MenuRow("Region", {
                open(Overlay.TextInput("Region (2-letter country code)", fc.tmdbRegion) { r -> setFeatured { it.copy(tmdbRegion = r.uppercase().take(2)) } })
            }, value = fc.tmdbRegion)
            SectionLabel("Service")
            Sources.tmdbProviders.forEach { (key, name) ->
                MenuRow(name, { setFeatured { it.copy(tmdbProvider = key) } }, value = if (fc.tmdbProvider == key) "✓" else null)
            }
        }
        FeaturedSourceId.YouTube -> {
            SectionLabel("YouTube")
            MenuRow("YouTube Channels", {
                open(Overlay.TextInput("YouTube Channels", fc.youtubeChannels, "Channel IDs (UC…) or channel URLs, separated by spaces. Uses each channel's public feed; no sign-in.") { c -> setFeatured { it.copy(youtubeChannels = c) } })
            }, value = channelsSummary(fc.youtubeChannels))
            MenuRow("API Key", {
                open(Overlay.TextInput("YouTube Data API Key", fc.youtubeKey, "Create one in Google Cloud Console with YouTube Data API v3 enabled.") { k -> setFeatured { it.copy(youtubeKey = k) } })
            }, value = mask(fc.youtubeKey))
            MenuRow("Region", {
                open(Overlay.TextInput("Region (2-letter country code)", fc.youtubeRegion) { r -> setFeatured { it.copy(youtubeRegion = r.uppercase().take(2)) } })
            }, value = fc.youtubeRegion)
        }
        FeaturedSourceId.Plex -> {
            SectionLabel("Plex")
            if (fc.plexToken.isBlank()) MenuRow("Sign In", { push(Page.PlexLink) })
            else {
                val askPlex = dev.glasslauncher.home.LocalConfirm.current
                MenuRow("Sign Out", { askPlex(dev.glasslauncher.home.Overlay.Confirm("Sign Out of Plex?", "The Top Shelf stops showing your Plex titles until you sign in again.", "Sign Out", destructive = true) { setFeatured { it.copy(plexToken = "") } }) }, value = "Signed in")
            }
        }
        FeaturedSourceId.ContinueWatching -> Hint("What you were watching, from the rows apps publish to the TV.")
        FeaturedSourceId.Off, FeaturedSourceId.TvApp, FeaturedSourceId.JustWatch -> Unit
        FeaturedSourceId.Addon -> Hint("Titles from an add-on you switched on. Its art is fetched by VITRE.")
    }
    MenuRow("Refresh Now", { scope.launch { graph.featured.refresh(fc, force = true, addons = dev.glasslauncher.addons.AddonSnapshot(cfg.addonsEnabled, cfg.addonSettings)) } })
}

private fun channelsSummary(text: String) = YouTube.channelIds(text).size.let { if (it == 0) "Not set" else "$it" }

private fun mask(key: String) = if (key.isBlank()) "Not set" else "••••" + key.takeLast(4)

@Composable
private fun ColumnScope.PlexLinkPage(model: HomeModel, cfg: LauncherConfig, f: Modifier, done: () -> Unit) {
    val graph = LocalContext.current.app
    var code by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val clientId = cfg.featured.plexClientId.ifBlank { UUID.randomUUID().toString() }
        if (cfg.featured.plexClientId.isBlank()) model.edit { it.copy(featured = it.featured.copy(plexClientId = clientId)) }
        val pin = runCatching { Plex.createPin(graph.http, clientId) }.getOrNull()
        if (pin == null) {
            error = "Couldn't reach plex.tv"
        } else {
            code = pin.second
            var token: String? = null
            var attempts = 0
            while (token == null && attempts < 150) {
                delay(2000)
                token = Plex.pollPin(graph.http, clientId, pin.first)
                attempts++
            }
            if (token != null) {
                model.edit { it.copy(featured = it.featured.copy(plexToken = token, plexClientId = clientId)) }
                done()
            } else error = "The code expired. Go back and try again."
        }
    }
    PanelTitle("Sign In to Plex")
    Hint("On your phone or computer, go to plex.tv/link and enter:")
    Text(code ?: "…", style = Type.title.copy(fontSize = Type.title.fontSize * 1.6f), color = LocalPalette.current.primary, modifier = Modifier.padding(18.dp))
    error?.let { Hint(it) }
    MenuRow("Cancel", done, f)
}

@Composable
private fun ColumnScope.HiddenPage(model: HomeModel, cfg: LauncherConfig, layout: HomeLayout, f: Modifier, push: (Page) -> Unit, refocus: () -> Unit) {
    PanelTitle("Hidden Apps")
    MenuRow("Hide Apps…", { push(Page.HideMore) }, f)
    val hidden = layout.installed.filter { it.packageName in cfg.hidden }
    if (hidden.isEmpty()) Hint("No apps are hidden.") else SectionLabel("Select to show again")
    hidden.forEach { app -> MenuRow(app.label, { model.unhide(app.packageName); refocus() }, value = "Show") }
}

/**
 * Which apps may put a row in the notifications drawer (P98). Every app that can show on Home is listed, switched on
 * unless it has been silenced, because the useful question is "what is noisy" and an app that has never posted is
 * indistinguishable from one that has until it does.
 */
@Composable
private fun ColumnScope.NotificationsPage(model: HomeModel, cfg: LauncherConfig, layout: HomeLayout, f: Modifier) {
    PanelTitle("Notifications")
    Hint("Switch an app off to keep it out of the drawer.")
    val blocked = cfg.blockedNoticePackages.toSet()
    layout.installed.sortedBy { it.label.lowercase() }.forEachIndexed { i, app ->
        MenuRow(
            app.label,
            {
                model.edit { c ->
                    c.copy(blockedNoticePackages = if (app.packageName in blocked) c.blockedNoticePackages - app.packageName else c.blockedNoticePackages + app.packageName)
                }
                // No refocus: the row stays where it is (a switch does not remove itself), so focus stays on it for the next app.
            },
            if (i == 0) f else Modifier,
            checked = app.packageName !in blocked,
        )
    }
}

@Composable
private fun ColumnScope.HideMorePage(model: HomeModel, layout: HomeLayout, f: Modifier, refocus: () -> Unit) {
    PanelTitle("Hide Apps")
    val visible = layout.dock + layout.grid.flatMap {
        when (it) {
            is dev.glasslauncher.home.GridItem.App -> listOf(it.app)
            is dev.glasslauncher.home.GridItem.FolderItem -> it.apps
        }
    }
    visible.sortedBy { it.label.lowercase() }.forEachIndexed { i, app ->
        MenuRow(app.label, { model.hide(app.packageName); refocus() }, if (i == 0) f else Modifier, value = "Hide")
    }
}

@Composable
private fun ColumnScope.IconPackPage(model: HomeModel, cfg: LauncherConfig, f: Modifier) {
    val context = LocalContext.current
    val graph = context.app
    // Re-read when the page comes back from a store or the installer: that is when a new pack appears.
    var refresh by remember { mutableStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val packs = remember(refresh) { graph.iconPacks.installed() }
    val sources = remember(refresh) { dev.glasslauncher.apps.IconPackSources.available(context) }
    val canPick = remember { dev.glasslauncher.apps.IconPackSources.canPickFiles(context) }
    val toast = { text: String -> dev.glasslauncher.system.RemoteNotice.show(context, text, 4_000) }
    val pickApk = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val ok = try { context.startActivity(dev.glasslauncher.apps.IconPackSources.installIntent(uri)); true }
                catch (_: android.content.ActivityNotFoundException) { false } catch (_: SecurityException) { false }
            if (!ok) toast("Couldn't open that file. This TV may have no package installer.")
        }
    }
    // Only real icon packs (their manifest declares a launcher theme), and each asks first: nothing else from
    // Downloads is ever offered for install here.
    val apks by androidx.compose.runtime.produceState(emptyList<String>(), refresh) { value = dev.glasslauncher.apps.IconPackSources.downloadedIconPacks() }
    val confirm = dev.glasslauncher.home.LocalConfirm.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    PanelTitle("Icon Pack")
    MenuRow("None", { model.edit { it.copy(iconPack = null) } }, f, value = if (cfg.iconPack == null) "✓" else null)
    packs.forEach { pack ->
        MenuRow(pack.label, { model.edit { it.copy(iconPack = pack.packageName) } }, value = if (cfg.iconPack == pack.packageName) "✓" else null)
    }
    if (packs.isEmpty()) Hint("No icon packs installed yet.")
    SectionLabel("Get an Icon Pack")
    // Only what opens on this device. A downloaded .apk in the Downloads folder is installed from here.
    apks.forEach { path ->
        val name = java.io.File(path).name
        MenuRow("Install $name", {
            confirm(dev.glasslauncher.home.Overlay.Confirm(
                title = "Install this icon pack?",
                message = "$name, from the Downloads folder, will be installed on this TV as an app, using root access to the package installer. An icon pack needs no permissions of its own. Only install packs you trust.",
                confirm = "Install",
            ) {
                // Not tied to this page: leaving it mid-install must not cancel the report (pm keeps running anyway).
                context.app.scope.launch {
                    val (ok, said) = dev.glasslauncher.apps.IconPackSources.install(context, path)
                    toast(if (ok) "Installed. Pick it in the list above." else "Couldn't install: ${said.ifBlank { "the installer refused it" }}")
                    refresh++
                }
            })
        }, chevron = true)
    }
    sources.forEach { source ->
        MenuRow(source.label, {
            if (!dev.glasslauncher.apps.IconPackSources.open(context, source)) toast("Couldn't open ${source.label.removePrefix("Open ")}.")
        }, chevron = true)
    }
    if (canPick) {
        MenuRow("Import from File…", {
            pickApk.launch(arrayOf(dev.glasslauncher.apps.IconPackSources.APK_MIME, "application/octet-stream"))
        }, chevron = true, help = "Pick an icon pack's .apk (for example from a USB drive or Downloads) and install it.")
    }
    if (apks.isEmpty() && sources.isEmpty() && !canPick) {
        Hint("Nothing here can fetch a pack. Put an icon pack's .apk in the Downloads folder and it will be listed to install (only real icon packs are).")
    } else if (apks.isEmpty()) {
        Hint("Download an icon pack's .apk with Downloader or ES File Explorer into Downloads and it is listed here to install (other files are not).")
    }
    Hint("Any ADW- or Nova-compatible pack works. Most are made for phones, so expect square icons.")
}

private fun screensaverName(mode: ScreensaverMode) = when (mode) {
    ScreensaverMode.Aerials -> "Aerials"
    ScreensaverMode.Slideshow -> "Slideshow"
    ScreensaverMode.System -> "Fire TV Screensaver"
}

private val START_AFTER_MINUTES = listOf(3, 5, 10, 15, 30)

/** tvOS Settings › Screen Saver: what plays and when, then each screensaver's own preferences. */
@Composable
private fun ColumnScope.ScreensaverPage(model: HomeModel, cfg: LauncherConfig, f: Modifier, push: (Page) -> Unit) {
    val context = LocalContext.current
    PanelTitle("Screen Saver")
    MenuRow("Current Selection", { push(Page.ScreensaverChoice) }, f, value = screensaverName(cfg.screensaverMode), chevron = true)
    // The TV's sleep timeout set to "never", so the screensaver plays on (P80b); off puts the old timeout back.
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var alwaysOn by remember { mutableStateOf(Screensaver.alwaysOn(ctx)) }
    val canAlwaysOn = Screensaver.canSetAlwaysOn(ctx)
    ToggleRow("Always-On Screen Saver", alwaysOn, { v ->
        scope.launch { Screensaver.setAlwaysOn(ctx, v); alwaysOn = Screensaver.alwaysOn(ctx) }
    }, enabled = canAlwaysOn || alwaysOn,
        help = if (canAlwaysOn || alwaysOn) "The TV never goes to sleep: the screen saver plays until you press a button. Off puts the old sleep timeout back."
        else "Needs permission to change the TV's sleep timeout. One-time setup from a computer: ${Screensaver.GRANT_COMMAND}")
    if (cfg.screensaverMode == ScreensaverMode.System) {
        MenuRow("Fire TV Screensaver Settings", {
            SystemControls.openTvSettings(context, SystemControls.tvSettingsSections.first { it.title == "Display & Sounds" })
        }, chevron = true)
        Hint("Fire TV's own screensaver runs after the delay set in its Display settings.")
    } else {
        MenuRow("Start After", { push(Page.StartAfter) }, value = "${cfg.aerialsIdleMinutes} Minutes", chevron = true,
            help = "The screen saver starts when Home has been idle this long.")
        ToggleRow("Show During Music", cfg.screensaverDuringMusic, { v -> model.edit { it.copy(screensaverDuringMusic = v) } },
            help = "Off: while music plays, Home shows the album art instead.")
    }
    SectionLabel("Screen Saver Preferences")
    MenuRow("Aerials", { push(Page.AerialsPrefs) }, chevron = true)
    MenuRow("Slideshow", { push(Page.SlideshowPrefs) }, chevron = true)
}

@Composable
private fun ColumnScope.ScreensaverChoicePage(model: HomeModel, cfg: LauncherConfig, f: Modifier) {
    PanelTitle("Current Selection")
    ScreensaverMode.entries.forEachIndexed { i, mode ->
        MenuRow(screensaverName(mode), { model.edit { it.copy(screensaverMode = mode) } }, if (i == 0) f else Modifier,
            value = if (cfg.screensaverMode == mode) "✓" else null)
    }
}

@Composable
private fun ColumnScope.StartAfterPage(model: HomeModel, cfg: LauncherConfig, f: Modifier) {
    PanelTitle("Start After")
    START_AFTER_MINUTES.forEachIndexed { i, m ->
        MenuRow("$m Minutes", { model.edit { it.copy(aerialsOnIdleMinutes = m) } }, if (i == 0) f else Modifier,
            value = if (cfg.aerialsIdleMinutes == m) "✓" else null)
    }
}

@Composable
private fun ColumnScope.AerialsPrefsPage(model: HomeModel, cfg: LauncherConfig, f: Modifier, push: (Page) -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    val isSystem = remember(refresh) { Screensaver.isSystemScreensaver(context) }
    val canWrite = remember { Screensaver.canWriteSecureSettings(context) }
    val sc = cfg.screensaver
    PanelTitle("Aerials")
    MenuRow("Quality", {
        model.edit { it.copy(screensaver = sc.copy(quality = if (sc.quality == AerialQuality.Hd1080) AerialQuality.Uhd4k else AerialQuality.Hd1080)) }
    }, f, value = if (sc.quality == AerialQuality.Hd1080) "1080p" else "4K",
        help = "Aerials stream from Apple and are cached (up to 600 MB) to replay offline.")
    ToggleRow("Show Location", sc.showLocation, { v -> model.edit { it.copy(screensaver = sc.copy(showLocation = v)) } })
    ToggleRow("Show Clock", sc.showClock, { v -> model.edit { it.copy(screensaver = sc.copy(showClock = v)) } })
    MenuRow("Choose Aerials", { push(Page.ChooseAerials) },
        value = if (sc.hiddenAerials.isEmpty()) "All" else "${sc.hiddenAerials.size} Hidden", chevron = true)
    MenuRow("Preview", { AerialActivity.start(context, slideshow = false) })
    SectionLabel("System")
    MenuRow(
        "Use as System Screensaver",
        { if (Screensaver.setAsSystemScreensaver(context)) refresh++ },
        value = when {
            isSystem -> "Active"
            canWrite -> "Set"
            else -> "Needs permission"
        },
        enabled = canWrite || isSystem,
        help = when {
            HomeSetup.isFireTv -> "Fire OS only runs Amazon's screensavers; VITRE starts its own when Home is idle."
            !canWrite && !isSystem -> "One-time setup from a computer: ${Screensaver.GRANT_COMMAND}"
            else -> null
        },
    )
}

/** tvOS Choose Aerials: categories on the left, the category's clips as a grid; Select hides or shows one. */
@Composable
private fun ChooseAerialsPage(model: HomeModel, cfg: LauncherConfig, active: Boolean) {
    val context = LocalContext.current
    val graph = context.app
    val sink = dev.glasslauncher.home.LocalTitleSink.current
    val pageKey = dev.glasslauncher.home.LocalPageKey.current
    androidx.compose.runtime.DisposableEffect(sink, pageKey) {
        sink?.wide = pageKey
        onDispose { if (sink != null && sink.wide == pageKey) sink.wide = null }
    }
    PanelTitle("Choose Aerials")
    val videos by androidx.compose.runtime.produceState<List<dev.glasslauncher.dream.AerialVideo>?>(null) {
        value = dev.glasslauncher.dream.AerialCatalog(context, graph.http).videos()
    }
    var category by remember { mutableStateOf(dev.glasslauncher.dream.AerialCategory.Cityscape) }
    val hidden = cfg.screensaver.hiddenAerials
    fun setHidden(h: Set<String>) = model.edit { it.copy(screensaver = it.screensaver.copy(hiddenAerials = h)) }
    val first = remember { FocusRequester() }
    LaunchedEffect(active) { if (active) { delay(16); runCatching { first.requestFocus() } } }
    val clips = videos.orEmpty().filter { category.id in it.categories }
    // The stills of the category being looked at are fetched ahead (two at a time, after the visible ones) into the disk cache, so scrolling
    // finds them there instead of waiting on 400 KB each.
    val prefetchContext = LocalContext.current
    LaunchedEffect(clips.map { it.id }) {
        // After the visible ones had their go (they ask first, and the prefetch never crowds them out), two at a time.
        kotlinx.coroutines.delay(1_500)
        val loader = coil3.SingletonImageLoader.get(prefetchContext)
        val gate = kotlinx.coroutines.sync.Semaphore(2)
        kotlinx.coroutines.coroutineScope {
            clips.mapNotNull { it.thumbnail }.forEach { url ->
                launch {
                    gate.acquire()
                    try { loader.execute(coil3.request.ImageRequest.Builder(prefetchContext).data(url).size(384, 216).build()) } finally { gate.release() }
                }
            }
        }
    }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(220.dp).padding(top = 44.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            dev.glasslauncher.dream.AerialCategory.entries.forEachIndexed { i, c ->
                val count = videos.orEmpty().count { c.id in it.categories }
                MenuRow(
                    c.label, { category = c },
                    (if (i == 0) Modifier.focusRequester(first) else Modifier)
                        .onFocusChanged { if (it.isFocused) category = c },
                    value = if (category == c) "✓" else count.takeIf { it > 0 }?.toString(),
                )
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            val ids = clips.map { it.id }.toSet()
            val allHidden = ids.isNotEmpty() && hidden.containsAll(ids)
            Row(Modifier.fillMaxWidth().padding(end = 12.dp), horizontalArrangement = Arrangement.End) {
                Box(Modifier.width(150.dp)) {
                    MenuRow(if (allHidden) "Show All" else "Hide All", { setHidden(if (allHidden) hidden - ids else hidden + ids) })
                }
            }
            when {
                videos == null -> Text("Loading Aerials…", style = dev.glasslauncher.ui.Type.secondary, color = dev.glasslauncher.ui.LocalPalette.current.secondary, modifier = Modifier.padding(start = 30.dp, top = 14.dp))
                videos!!.isEmpty() -> Text("Connect to the internet to download Aerial videos.", style = dev.glasslauncher.ui.Type.secondary, color = dev.glasslauncher.ui.LocalPalette.current.secondary, modifier = Modifier.padding(start = 30.dp, top = 14.dp))
            }
            // A gap under Hide All that stays when the list is scrolled (the grid's own top padding scrolls away,
            // and tiles then ran right up to the button).
            androidx.compose.foundation.layout.Spacer(Modifier.height(AERIALS_GAP_UNDER_HIDE_ALL))
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
                // Bottom: the page fades its last 36 dp, and the list ends this far above its edge, so the last
                // row's caption scrolls up clear of the fade (see AERIALS_BRING_BELOW for the rows before it).
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 30.dp, end = 18.dp, top = 6.dp, bottom = 44.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().testTag("aerials-grid"),
            ) {
                items(clips.size, key = { clips[it].id }) { i ->
                    val v = clips[i]
                    val isHidden = v.id in hidden
                    AerialThumb(v, isHidden) { setHidden(if (isHidden) hidden - v.id else hidden + v.id) }
                }
            }
        }
    }
}

private val AERIALS_GAP_UNDER_HIDE_ALL = 12.dp
/** What a focused thumbnail brings into view below its caption: clear of the page's 36 dp bottom fade. */
private val AERIALS_BRING_BELOW = 44.dp

/** A focused tile scales by 1.1 about its centre, so its top edge rises a few dp above the cell: part of what is brought into view. */
private val AERIALS_BRING_ABOVE = 8.dp

@Composable
private fun AerialThumb(v: dev.glasslauncher.dream.AerialVideo, hidden: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val palette = dev.glasslauncher.ui.LocalPalette.current
    // Focus scrolls the list to show the focused tile, which stops short of its caption and left the last rows
    // cut off at the bottom. Ask for the whole cell, and some room under it, to be brought into view instead.
    val bring = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val below = with(androidx.compose.ui.platform.LocalDensity.current) { AERIALS_BRING_BELOW.toPx() }
    val above = with(androidx.compose.ui.platform.LocalDensity.current) { AERIALS_BRING_ABOVE.toPx() }
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var thumbFocused by remember { mutableStateOf(false) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.onSizeChanged { size = it }.bringIntoViewRequester(bring),
    ) {
        dev.glasslauncher.ui.FocusTile(
            label = if (hidden) "${v.label}, Hidden" else v.label,
            onClick = onClick,
            focusedScale = 1.1f,
            onFocusChange = { thumbFocused = it; if (it) scope.launch { bring.bringIntoView(androidx.compose.ui.geometry.Rect(0f, -above, size.width.toFloat(), size.height + below)) } },
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).testTag("aerial:${v.id}"),
        ) {
            // Retried with backoff; the clip's name on a tint while the still isn't there (it is ~400 KB over the network).
            dev.glasslauncher.ui.ReliableImage(
                v.thumbnail, Modifier.fillMaxSize().graphicsLayer { alpha = if (hidden) 0.35f else 1f },
                title = v.label.ifEmpty { "Aerial" }, width = 384, height = 216, focused = thumbFocused,
            )
            if (hidden) Image(
                androidx.compose.ui.res.painterResource(dev.glasslauncher.R.drawable.ic_visibility_off), null,
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.9f)),
                modifier = Modifier.align(Alignment.Center).size(30.dp).testTag("aerial-hidden:${v.id}"),
            )
        }
        Text(
            v.label.ifEmpty { "Aerial" }, style = dev.glasslauncher.ui.Type.caption,
            color = if (hidden) palette.faint else palette.secondary, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private val PHOTO_SECONDS = listOf(5, 8, 12, 20)

@Composable
private fun ColumnScope.SlideshowPrefsPage(model: HomeModel, cfg: LauncherConfig, f: Modifier, push: (Page) -> Unit) {
    val context = LocalContext.current
    val sc = cfg.screensaver
    PanelTitle("Slideshow")
    MenuRow("Choose Photos", { push(Page.ChoosePhotos) }, f, value = sc.album ?: "All Photos", chevron = true,
        help = "Copy photos to Pictures or Downloads (USB drive or Downloader), then choose an album.")
    MenuRow("Duration", {
        val next = PHOTO_SECONDS[(PHOTO_SECONDS.indexOf(sc.photoSeconds).coerceAtLeast(0) + 1) % PHOTO_SECONDS.size]
        model.edit { it.copy(screensaver = it.screensaver.copy(photoSeconds = next)) }
    }, value = "${sc.photoSeconds} Seconds")
    ToggleRow("Pan and Zoom", sc.kenBurns, { v -> model.edit { it.copy(screensaver = it.screensaver.copy(kenBurns = v)) } })
    MenuRow("Preview", { AerialActivity.start(context, slideshow = true) })
}

@Composable
private fun ColumnScope.ChoosePhotosPage(model: HomeModel, cfg: LauncherConfig, f: Modifier) {
    val context = LocalContext.current
    val permission = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_IMAGES else android.Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember {
        mutableStateOf(androidx.core.content.ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }
    val request = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted = it }
    val album = cfg.screensaver.album
    fun choose(a: String?) = model.edit { it.copy(screensaver = it.screensaver.copy(album = a)) }
    PanelTitle("Choose Photos")
    MenuRow("All Photos", { choose(null) }, f, value = if (album == null) "✓" else null)
    if (!granted) {
        MenuRow("Allow Access to Photos", { request.launch(permission) }, help = "The slideshow needs access to the photos on this TV.")
        return
    }
    val albums by androidx.compose.runtime.produceState<List<Pair<String, Int>>?>(null) { value = dev.glasslauncher.dream.SlideshowView.albums(context) }
    SectionLabel("Albums")
    albums?.forEach { (name, count) ->
        MenuRow(name, { choose(name) }, value = if (album == name) "✓" else "$count")
    }
    if (albums?.isEmpty() == true) Hint("No photos found. Copy some to Pictures or Downloads (for example with Downloader or a USB drive).")
}

@Composable
private fun ColumnScope.WidgetsPage(model: HomeModel, cfg: LauncherConfig, f: Modifier, open: (Overlay) -> Unit) {
    val context = LocalContext.current
    val graph = context.app
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    PanelTitle("Widgets")
    ToggleRow("24-Hour Time", cfg.clock24h, { v -> model.edit { it.copy(clock24h = v) } }, f)
    SectionLabel("Weather")
    MenuRow("Location", {
        open(Overlay.TextInput("Weather Location", cfg.weather?.city?.substringBefore(',') ?: "", "City name, e.g. Toronto") { q ->
            scope.launch {
                status = "Looking up $q…"
                val w = Weather.geocode(graph.http, q, cfg.weather?.fahrenheit ?: false)
                if (w != null) { model.edit { it.copy(weather = w) }; status = null } else status = "Couldn't find \"$q\"."
            }
        })
    }, value = cfg.weather?.city ?: "Not set")
    cfg.weather?.let { w ->
        ToggleRow("Fahrenheit", w.fahrenheit, { v -> model.edit { it.copy(weather = w.copy(fahrenheit = v)) } })
        ToggleRow("Show Weather", true, { model.edit { it.copy(weather = null) } })
    }
    status?.let { Hint(it) }
    SectionLabel("Now Playing")
    ToggleRow("Show on Home", cfg.showNowPlaying, { v -> model.edit { it.copy(showNowPlaying = v) } },
        help = if (NowPlayingSource.isAllowed(context)) "While music plays, Home shows the album art and track."
        else "Needs media access. From a computer: adb shell cmd notification allow_listener ${context.packageName}/${dev.glasslauncher.widgets.NowPlayingService::class.java.name}")
}

@Composable
private fun ColumnScope.HomeButtonPage(model: HomeModel, cfg: LauncherConfig, f: Modifier) {
    val context = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    val isDefault = remember(refresh) { HomeSetup.isDefaultHome(context) }
    val guardOn = remember(refresh) { HomeSetup.isGuardEnabled(context) }
    val canWrite = remember { Screensaver.canWriteSecureSettings(context) }
    val requestIntent = remember(refresh) { HomeSetup.requestDefaultHomeIntent(context) }
    PanelTitle("Home Button")
    MenuRow("Default Home App", { refresh++ }, f, value = if (isDefault) "VITRE" else "Another app")
    val keysContext = LocalContext.current
    ToggleRow("Remote buttons & instant Back", cfg.instantBack, { on ->
        model.edit { it.copy(instantBack = on) }
        dev.glasslauncher.system.KeysService.onSettingChanged(keysContext, on)
    },
        help = "Maps the remote's app buttons and Recent Apps, and lets Back or Home cancel an app that is still loading. Needs VITRE's accessibility service: rooted installs keep it on for you; otherwise VITRE asks once, and you turn it on in Fire's Accessibility settings. Back inside a running app is never changed.")
    if (!isDefault && requestIntent != null) {
        MenuRow("Make VITRE the Default", { runCatching { HomeSetup.startRequest(context, requestIntent) } })
    }
    if (HomeSetup.isFireTv || !isDefault) {
        SectionLabel("Fallback")
        val askGuard = dev.glasslauncher.home.LocalConfirm.current
        ToggleRow("Home Button Takeover", cfg.homeGuard, { enable ->
            val apply = {
                model.edit { it.copy(homeGuard = enable) }
                if (canWrite) HomeSetup.setGuardEnabled(context, enable)
                refresh++
            }
            // Turning it off can leave the stock home screen in charge: ask first.
            if (enable) apply() else askGuard(dev.glasslauncher.home.Overlay.Confirm("Turn Off Home Button Takeover?", "If the stock home screen comes back, VITRE won't take over again.", "Turn Off", destructive = true) { apply() })
        }, help = "When the stock home screen appears, VITRE takes over. It never intercepts buttons.")
        if (cfg.homeGuard && !guardOn) {
            Hint(if (canWrite) "Couldn't enable the accessibility service." else "Grant once from a computer: ${Screensaver.GRANT_COMMAND}, then toggle again. Or enable it in Accessibility settings.")
        }
    }
    if (HomeSetup.isFireTv) {
        SectionLabel("Most reliable on Fire TV (from a computer)")
        Hint("${HomeSetup.DISABLE_STOCK_COMMAND}  (undo: ${HomeSetup.RESTORE_STOCK_COMMAND})")
    }
}

@Composable
private fun ColumnScope.UpdatesPage(f: Modifier) {
    val context = LocalContext.current
    val graph = context.app
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var release by remember { mutableStateOf<Release?>(null) }
    PanelTitle("Updates")
    MenuRow("Current Version", {}, f, value = BuildConfig.VERSION_NAME, help = "Updates come from github.com/${BuildConfig.UPDATE_REPO}/releases.")
    MenuRow("Check for Updates", {
        scope.launch {
            status = "Checking…"
            val latest = runCatching { Updater.latest(graph.http) }.getOrNull()
            release = latest?.takeIf { Updater.isNewer(it.version) }
            status = when {
                latest == null -> "Couldn't reach GitHub."
                release == null -> "You're up to date."
                else -> null
            }
        }
    })
    release?.let { r ->
        val askUpdate = dev.glasslauncher.home.LocalConfirm.current
        MenuRow("Install ${r.version}", { askUpdate(dev.glasslauncher.home.Overlay.Confirm("VITRE ${r.version}", "Download and install it now? VITRE restarts when it's done.", "Download and Install") {
            if (!Updater.canInstall(context)) {
                runCatching { context.startActivity(Updater.unknownSourcesIntent(context)) }
                status = "Allow VITRE to install apps, then select Install again."
            } else scope.launch {
                runCatching {
                    Updater.downloadAndInstall(context, graph.http, r) { p -> status = "Downloading… ${(p * 100).toInt()}%" }
                }.onSuccess { status = "Installing…" }.onFailure { status = it.message ?: "Update failed." }
            }
        }) })
    }
    status?.let { Hint(it) }
}

@Composable
private fun ColumnScope.BackupPage(model: HomeModel, f: Modifier) {
    val context = LocalContext.current
    val graph = context.app
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val text = Backup.readUri(context, uri)
            status = if (text != null && runCatching { graph.config.import(text) }.isSuccess) "Restored." else "That file isn't a VITRE backup."
        }
    }
    val canBrowse = remember {
        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").resolveActivity(context.packageManager) != null
    }
    PanelTitle("Backup & Restore")
    MenuRow("Save Backup", {
        scope.launch { status = runCatching { "Saved to " + Backup.export(context, graph.config.export()) }.getOrElse { it.message ?: "Couldn't save." } }
    }, f, help = "Your layout, folders, hidden apps and settings. Custom images stay on this TV.")
    val askRestore = dev.glasslauncher.home.LocalConfirm.current
    MenuRow("Restore from Downloads", { askRestore(dev.glasslauncher.home.Overlay.Confirm("Restore from Downloads?", "Your current layout, folders and settings are replaced by the backup's.", "Restore", destructive = true) {
        scope.launch {
            val text = runCatching { Backup.read(context) }.getOrNull()
            status = when {
                text == null -> "No ${Backup.FILE_NAME} found in Downloads."
                runCatching { graph.config.import(text) }.isSuccess -> "Restored."
                else -> "The backup file couldn't be read."
            }
        }
    }) })
    if (canBrowse) MenuRow("Restore from File…", {
        askRestore(dev.glasslauncher.home.Overlay.Confirm("Restore from a File?", "Your current layout, folders and settings are replaced by the file's.", "Choose File", destructive = true) { pick.launch(arrayOf("application/json", "*/*")) })
    })
    status?.let { Hint(it) }
}

@Composable
private fun ColumnScope.AboutPage(model: HomeModel, cfg: LauncherConfig, f: Modifier) {
    PanelTitle("VITRE")
    // Select on Version seven times reveals the developer switch below (hidden otherwise; off by default).
    var taps by remember { mutableStateOf(0) }
    MenuRow("Version", { taps++ }, f, value = "${BuildConfig.VERSION_NAME} (${Build.MODEL})",
        help = "Open source under the Apache License 2.0. github.com/${BuildConfig.UPDATE_REPO}")
    MenuRow("Content Sources", {},
        help = "Aerials from Apple; titles from the apps, JustWatch, Stremio, TMDB, YouTube or Plex; weather by Open-Meteo. Uses the TMDB API but isn't endorsed by TMDB.")
    SectionLabel("Privacy")
    MenuRow("VITRE Collects No Data", {},
        help = "No accounts, analytics, ads or tracking. It only contacts the services you turn on, and sends nothing but the request.")
    if (taps >= 7 || cfg.ccDemoTiles) {
        SectionLabel("Developer")
        ToggleRow("Demo tiles in Control Center", cfg.ccDemoTiles, { on -> model.edit { it.copy(ccDemoTiles = on) } },
            help = "Demo data: adds fake Home Assistant, Claude Code, Plex and Weather tiles to the Add a Control gallery, to show how add-on tiles look. Nothing connects to those services. This switch is hidden again when you leave About: select Version seven times to see it.")
    }
    Box(Modifier.size(1.dp))
}

@Composable
private fun ColumnScope.AccessibilityPage(model: HomeModel, cfg: LauncherConfig, f: Modifier) {
    PanelTitle("Accessibility")
    MenuRow("Reduce Motion", {
        val next = Auto.entries[(cfg.reduceMotion.ordinal + 1) % Auto.entries.size]
        model.edit { it.copy(reduceMotion = next) }
    }, f, value = when (cfg.reduceMotion) { Auto.Auto -> "Automatic"; Auto.On -> "On"; Auto.Off -> "Off" },
        help = "Turns off tilt, wiggle and movement; changes still dissolve. Automatic follows the TV's setting.")
    ToggleRow("Navigation Sounds", cfg.sounds, { v -> model.edit { it.copy(sounds = v) } },
        help = "Plays the system focus and click sounds, if they're on in the TV's settings.")
}

@Composable
private fun ColumnScope.RemoteButtonsPage(cfg: LauncherConfig, f: Modifier, push: (Page) -> Unit) {
    val context = LocalContext.current
    val active = remember { RemoteButtons.takeoverActive() }
    val apps = remember { context.packageManager }
    val palette = LocalPalette.current
    // The names of mapped add-on actions come from the add-on; read once when the page opens.
    androidx.compose.runtime.LaunchedEffect(Unit) { dev.glasslauncher.addons.AddonHost.loadActions(context, dev.glasslauncher.addons.AddonSnapshot(cfg.addonsEnabled, cfg.addonSettings)) }
    PanelTitle("Remote Buttons")
    // The four app buttons, laid out as they sit on the remote.
    val (grid, others) = RemoteButtons.all.partition { it.id.startsWith("app") }
    grid.chunked(2).forEachIndexed { r, pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            pair.forEachIndexed { c, b ->
                val n = r * 2 + c + 1
                val action = actionName(RemoteButtons.action(b, cfg.remoteButtons), apps)
                FocusTile(
                    label = "${b.label}, $action",
                    onClick = { push(Page.ButtonAction(b.id)) },
                    shape = Shapes.pill,
                    focusedScale = 1.03f,
                    shadow = false,
                    modifier = (if (n == 1) f else Modifier).weight(1f).height(72.dp).testTag("remote-button-$n"),
                ) { focused ->
                    Column(
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxSize()
                            .background(if (focused) palette.focusFill else palette.primary.copy(alpha = if (palette.light) 0.06f else 0.08f))
                            .padding(horizontal = 20.dp),
                    ) {
                        Text(b.label, style = Type.body, color = if (focused) palette.onFocusFill else palette.primary, maxLines = 1)
                        Text(action, style = Type.secondary, color = if (focused) palette.onFocusFill.copy(alpha = 0.7f) else palette.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    others.forEach { b ->
        MenuRow(b.label, { push(Page.ButtonAction(b.id)) }, value = actionName(RemoteButtons.action(b, cfg.remoteButtons), apps), chevron = true)
    }
    // Control Center over any app without the key-layout module (P167): a remote key the accessibility service sees.
    MenuRow("Control Center Shortcut", { push(Page.CcShortcut) }, value = dev.glasslauncher.system.CcShortcut.parse(cfg.ccShortcut).label, chevron = true)
    if (!HomeSetup.isRemoteKeysEnabled(context) && dev.glasslauncher.system.CcShortcut.parse(cfg.ccShortcut) != dev.glasslauncher.system.CcShortcut.Off) {
        Hint("The shortcut needs VITRE Remote Buttons turned on in the TV's Accessibility settings.")
    }
    Hint(when {
        !HomeSetup.isFireTv -> if (HomeSetup.isRemoteKeysEnabled(context)) "Buttons left on Default keep working as usual." else "Turn on VITRE Remote Buttons in the TV's Accessibility settings."
        !active -> "Fire TV keeps these buttons to itself. On a rooted TV, run once from a computer, then restart: ${RemoteButtons.INSTALL_COMMAND}"
        !HomeSetup.isRemoteKeysEnabled(context) -> "Turn on VITRE Remote Buttons in the TV's Accessibility settings."
        else -> "Home, Back, volume, power, Alexa and the TV button keep working as usual."
    })
}

/** Settings › Controller Buttons (P174): each controller button and what it does; unmapped, the app in front keeps it. */
@Composable
private fun ColumnScope.ControllerButtonsPage(cfg: LauncherConfig, f: Modifier, push: (Page) -> Unit) {
    val context = LocalContext.current
    val apps = remember { context.packageManager }
    androidx.compose.runtime.LaunchedEffect(Unit) { dev.glasslauncher.addons.AddonHost.loadActions(context, dev.glasslauncher.addons.AddonSnapshot(cfg.addonsEnabled, cfg.addonSettings)) }
    PanelTitle("Controller Buttons")
    RemoteButtons.pad.forEachIndexed { i, b ->
        MenuRow(b.label, { push(Page.ButtonAction(b.id)) }, if (i == 0) f else Modifier.testTag("pad-button-${b.id.substringAfter(':')}"), value = actionName(RemoteButtons.action(b, cfg.remoteButtons), apps), chevron = true)
    }
    if (!HomeSetup.isRemoteKeysEnabled(context)) Hint("Turn on VITRE Remote Buttons in the TV's Accessibility settings so VITRE can see these buttons.")
    else Hint("A button you leave on Default keeps working in the app in front.")
}

/** The key that opens Control Center over an app (P167): one choice, saved as it is picked. */
@Composable
private fun ColumnScope.CcShortcutPage(model: HomeModel, cfg: LauncherConfig, f: Modifier, done: () -> Unit) {
    val current = dev.glasslauncher.system.CcShortcut.parse(cfg.ccShortcut)
    PanelTitle("Control Center Shortcut")
    Hint("The remote key that opens Control Center over any app. A hold keeps the key's normal press for the app.")
    dev.glasslauncher.system.CcShortcut.entries.forEachIndexed { i, o ->
        MenuRow(if (o == dev.glasslauncher.system.CcShortcut.DEFAULT) "${o.label} (Default)" else o.label, {
            model.edit { it.copy(ccShortcut = o.key) }
            done()
        }, if (i == 0) f else Modifier, value = if (o == current) "✓" else null)
    }
}

@Composable
private fun ColumnScope.ButtonActionPage(model: HomeModel, cfg: LauncherConfig, id: String, f: Modifier, push: (Page) -> Unit, done: () -> Unit) {
    val button = RemoteButtons.find(id)!!
    val current = RemoteButtons.action(button, cfg.remoteButtons)
    val dflt = RemoteButtons.defaultFor(button)
    val apps = LocalContext.current.packageManager
    fun set(action: RemoteAction) {
        model.edit { it.copy(remoteButtons = it.remoteButtons + (id to action.key)) }
        done()
    }
    PanelTitle(button.label)
    val options = listOf(dflt, RemoteAction.TvSettings, RemoteAction.ControlCenter, RemoteAction.AppSwitcher, RemoteAction.Home, RemoteAction.Nothing).distinct()
    options.forEachIndexed { i, a ->
        val label = if (a == dflt && a != RemoteAction.Default) "${actionName(a, apps)} (Default)" else actionName(a, apps)
        MenuRow(label, { set(a) }, if (i == 0) f else Modifier, value = if (a == current) "✓" else null)
    }
    // Add-on actions (switched-on add-ons that offer some): choosing one is the consent; a stored one whose add-on is gone shows as unavailable.
    val ctx = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) { dev.glasslauncher.addons.AddonHost.loadActions(ctx, dev.glasslauncher.addons.AddonSnapshot(cfg.addonsEnabled, cfg.addonSettings)) }
    val addonEntries = dev.glasslauncher.addons.AddonActionPicker.entries(dev.glasslauncher.addons.AddonHost.actionLists, current.key)
    if (addonEntries.isNotEmpty()) {
        SectionLabel("Add-on actions")
        addonEntries.forEach { e ->
            MenuRow(e.label, { if (e.available) set(dev.glasslauncher.system.RemoteAction.parse(e.key)) }, value = if (e.key == current.key) "✓" else null)
        }
    }
    MenuRow("Open Another App…", { push(Page.ButtonApp(id)) }, chevron = true,
        value = (current as? RemoteAction.OpenApp)?.takeIf { it != dflt }?.let { actionName(it, apps) })
}

@Composable
private fun ColumnScope.ButtonAppPage(model: HomeModel, layout: HomeLayout, id: String, f: Modifier, done: () -> Unit) {
    PanelTitle("Choose App")
    layout.installed.sortedBy { it.label.lowercase() }.forEachIndexed { i, app ->
        MenuRow(app.label, {
            model.edit { it.copy(remoteButtons = it.remoteButtons + (id to RemoteAction.OpenApp(app.packageName).key)) }
            done()
        }, if (i == 0) f else Modifier)
    }
}

/** What a button does, in words (the device pages show it next to each button). */
internal fun actionLabel(action: RemoteAction, pm: android.content.pm.PackageManager): String = actionName(action, pm)

private fun actionName(action: RemoteAction, pm: android.content.pm.PackageManager): String = when (action) {
    is RemoteAction.AddonAction -> dev.glasslauncher.addons.AddonActionPicker.name(dev.glasslauncher.addons.AddonActionRef(action.pkg, action.service, action.id), dev.glasslauncher.addons.AddonHost.actionLists)
    is RemoteAction.OpenApp -> runCatching { pm.getApplicationLabel(pm.getApplicationInfo(action.pkg, 0)).toString() }.getOrDefault(action.pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() })
    RemoteAction.TvSettings -> "TV Settings"
    RemoteAction.ControlCenter -> "Control Center"
    RemoteAction.AppSwitcher -> "App Switcher"
    RemoteAction.Home -> "Home"
    RemoteAction.Nothing -> "Do Nothing"
    RemoteAction.Default -> "Default"
}


@Composable
private fun ColumnScope.RootToolsPage(f: Modifier, push: (Page) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rf = dev.glasslauncher.system.RootFeatures
    var tick by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var freed by remember { mutableStateOf<Int?>(null) }
    val system = remember(tick) { rf.systemAppState(context) }
    val takeover = remember(tick) { rf.homeTakeoverOn(context) }
    val memory by androidx.compose.runtime.produceState<Boolean?>(null, tick) { value = rf.memoryTuningOn() }
    fun act(what: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = what
        scope.launch { block(); busy = null; tick++ }
    }
    // Root isn't assumed: the first row says whether su is there and grants Glass root; without it,
    // everything below is greyed out.
    var rootCheck by remember { mutableStateOf(0) }
    val rooted by androidx.compose.runtime.produceState<Boolean?>(null, rootCheck) { value = dev.glasslauncher.system.Root.recheck() }
    val on = rooted == true
    PanelTitle("Root")
    MenuRow("Superuser", { rootCheck++ }, f, value = when (rooted) { null -> "Checking…"; true -> "Detected"; false -> "Not detected" },
        help = if (on) "Root changes are reversible and logged. Some need a restart." else "These need root (Magisk). Select to check again after granting VITRE root.")
    SectionLabel("System")
    val askSystem = dev.glasslauncher.home.LocalConfirm.current
    MenuRow("System App", {
        val install = system == dev.glasslauncher.system.RootFeatures.SystemApp.Off || system == dev.glasslauncher.system.RootFeatures.SystemApp.Removing
        askSystem(dev.glasslauncher.home.Overlay.Confirm(
            if (install) "Install as a System App?" else "Remove the System App?",
            if (install) "VITRE becomes a privileged system app after the next restart (a Magisk module; removing it reverts)." else "VITRE goes back to a regular app after the next restart.",
            if (install) "Install" else "Remove", destructive = !install,
        ) { act("system") { if (install) rf.installSystemApp(context) else rf.removeSystemApp(context) } })
    }, enabled = on, value = when (system) {
        dev.glasslauncher.system.RootFeatures.SystemApp.On -> "On"
        dev.glasslauncher.system.RootFeatures.SystemApp.Pending -> "On after restart"
        dev.glasslauncher.system.RootFeatures.SystemApp.Removing -> "Off after restart"
        dev.glasslauncher.system.RootFeatures.SystemApp.Off -> "Off"
    }, help = "Runs VITRE as a privileged system app: it can read other apps' TV rows and is harder for the system to stop.")
    // Rows that only mean something on some devices are not drawn on the others (P155).
    val stockHome = remember(tick) { rf.hasStockHome(context) }
    if (stockHome) ToggleRow("Home Takeover", takeover, { v -> act("home") { rf.setHomeTakeover(context, v) } }, enabled = on,
        help = "Turns off the TV's own launcher and makes VITRE the Home screen. Off brings the TV's back.")
    var ownPairing by remember { mutableStateOf(rf.ownRemotePairing(context)) }
    if (dev.glasslauncher.system.HomeSetup.isFireTv) ToggleRow("Own Remote Pairing", ownPairing, { v -> ownPairing = v; act("pairing") { rf.setOwnRemotePairing(context, v) } }, enabled = on,
        help = "Turns off Fire's auto-pair, which drops a sleeping remote's pairing, and lets VITRE pair the remote itself. Needs System App on, and a restart.")
    if (dev.glasslauncher.system.DeviceProfile.memoryTuningApplies(context)) ToggleRow("Memory Tuning", memory == true, { v -> act("memory") { rf.setMemoryTuning(context, v) } }, enabled = on,
        help = "Keeps the apps you return to ready (1.2 GB compressed swap, a gentler low-memory killer, 12 cached apps). After a restart.")
    MenuRow("Free Memory", { act("free") { freed = rf.freeMemory(context) } }, value = freed?.let { "$it MB freed" }, enabled = on)
    if (rf.freezableHere(context).isNotEmpty()) MenuRow("App Freezer", { push(Page.Freezer) }, chevron = true, enabled = on)
    SectionLabel("Device")
    val ask = dev.glasslauncher.home.LocalConfirm.current
    MenuRow("Restart Now", {
        ask(dev.glasslauncher.home.Overlay.Confirm("Restart Now?", "The TV restarts to apply root changes. Anything playing stops.", "Restart", destructive = true) { act("restart") { rf.restart(context) } })
    }, enabled = on)
    MenuRow("Root Log", { push(Page.RootLog) }, chevron = true, enabled = on)
}

@Composable
private fun ColumnScope.FreezerPage(f: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rf = dev.glasslauncher.system.RootFeatures
    var tick by remember { mutableStateOf(0) }
    val frozen by androidx.compose.runtime.produceState<Set<String>?>(null, tick) { value = rf.frozen() }
    PanelTitle("App Freezer")
    Hint("Turned-off apps stop running and disappear until turned back on. Nothing is uninstalled.")
    rf.freezableHere(context).forEachIndexed { i, app ->
        val off = frozen?.contains(app.pkg) == true
        ToggleRow(app.label, !off, { on -> scope.launch { rf.setFrozen(context, app.pkg, !on); tick++ } }, if (i == 0) f else Modifier, enabled = frozen != null, help = app.note)
    }
}

@Composable
private fun ColumnScope.RootLogPage(f: Modifier) {
    val context = LocalContext.current
    val lines = remember { dev.glasslauncher.system.Root.readLog(context) }
    PanelTitle("Root Log")
    if (lines.isEmpty()) MenuRow("Nothing yet", {}, f)
    lines.forEachIndexed { i, line -> MenuRow(line, {}, if (i == 0) f else Modifier) }
}

private val TEXT_SIZES = listOf(1f to "Default", 1.1f to "Large", 1.2f to "Larger", 1.3f to "Extra Large", 1.4f to "Largest")

private fun textSizeName(scale: Float) = TEXT_SIZES.minByOrNull { kotlin.math.abs(it.first - scale) }!!.second

/** tvOS 27's Display & Text page: Bold Text and Text Size, then Contrast. Fire TV's own options are linked, not copied. */
@Composable
private fun ColumnScope.DisplayTextPage(model: HomeModel, cfg: LauncherConfig, f: Modifier, push: (Page) -> Unit) {
    val context = LocalContext.current
    val screen = dev.glasslauncher.ui.LocalScreenDissolve.current
    PanelTitle("Display & Text")
    SectionLabel("Text")
    ToggleRow("Bold Text", cfg.boldText, { v -> screen.dissolve { model.edit { it.copy(boldText = v) } } }, f)
    MenuRow("Text Size", { push(Page.TextSize) }, value = textSizeName(cfg.textScale), chevron = true)
    MenuRow("Font", { push(Page.Font) }, value = cfg.font.label, chevron = true)
    SectionLabel("Contrast")
    ToggleRow("Increase Contrast", cfg.increaseContrast, { v -> screen.dissolve { model.edit { it.copy(increaseContrast = v) } } })
    ToggleRow("Reduce Transparency", cfg.reduceTransparency, { v -> screen.dissolve { model.edit { it.copy(reduceTransparency = v) } } },
        help = "Makes glass solid for easier reading.")
    if (HomeSetup.isFireTv) {
        SectionLabel("Fire TV")
        MenuRow("Fire TV Accessibility", {
            SystemControls.openTvSettings(context, SystemControls.tvSettingsSections.first { it.title == "Accessibility" })
        }, chevron = true, help = "High Contrast Text, Screen Magnifier and captions: Fire TV settings for every app.")
    }
}

/** Settings › Display & Text › Font: the interface typeface; the page itself changes with it. */
@Composable
private fun ColumnScope.FontPage(model: HomeModel, cfg: LauncherConfig, f: Modifier) {
    val screen = dev.glasslauncher.ui.LocalScreenDissolve.current
    PanelTitle("Font")
    dev.glasslauncher.data.UiFont.entries.forEachIndexed { i, font ->
        MenuRow(
            font.label, { if (cfg.font != font) screen.dissolve { model.edit { it.copy(font = font) } } },
            if (i == 0) f else Modifier, value = if (cfg.font == font) "✓" else null,
        )
    }
    Hint("Inter is VITRE's own face. System, Condensed and Serif are the TV's.")
}

/** A five-step slider: Left/Right changes the size, and the page grows with it as you go. */
@Composable
private fun ColumnScope.TextSizePage(model: HomeModel, cfg: LauncherConfig, f: Modifier) {
    val palette = LocalPalette.current
    val index = TEXT_SIZES.indexOfFirst { it.first == textSizeName(cfg.textScale).let { n -> TEXT_SIZES.first { s -> s.second == n }.first } }
    fun set(i: Int) { model.edit { it.copy(textScale = TEXT_SIZES[i.coerceIn(0, TEXT_SIZES.lastIndex)].first) } }
    PanelTitle("Text Size")
    // The size's name above the slider (tvOS 27).
    Text(TEXT_SIZES[index].second, style = Type.heading, color = palette.primary, modifier = Modifier.padding(start = 18.dp, bottom = 6.dp).testTag("text-size-name"))
    var focused by remember { mutableStateOf(false) }
    val rtl = dev.glasslauncher.ui.isRtl()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = f
            .fillMaxWidth()
            .background(if (focused) palette.focusFill else palette.primary.copy(alpha = 0.08f), Shapes.pill)
            .padding(horizontal = 22.dp, vertical = 16.dp)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                val k = e.nativeKeyEvent
                if (k.action != android.view.KeyEvent.ACTION_DOWN) return@onKeyEvent false
                when (k.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { set(index + dev.glasslauncher.ui.horizontalStep(k.keyCode, rtl)); true }
                    else -> false
                }
            }
            .semantics {
                contentDescription = "Text Size, ${TEXT_SIZES[index].second}"
                progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(index.toFloat(), 0f..TEXT_SIZES.lastIndex.toFloat(), TEXT_SIZES.size - 2)
                setProgress { v -> set(v.toInt()); true }
            }
            .focusable()
            .testTag("text-size-slider"),
    ) {
        val fg = if (focused) palette.onFocusFill else palette.primary
        Text("A", style = Type.caption, color = fg)
        androidx.compose.foundation.Canvas(Modifier.weight(1f).height(24.dp)) {
            val y = size.height / 2
            drawLine(fg.copy(alpha = 0.3f), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 4.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
            val step = size.width / TEXT_SIZES.lastIndex
            for (i in TEXT_SIZES.indices) drawCircle(fg.copy(alpha = if (i <= index) 0.9f else 0.35f), 4.dp.toPx(), androidx.compose.ui.geometry.Offset(step * i, y))
            drawLine(fg, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(step * index, y), 4.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
            drawCircle(fg, 11.dp.toPx(), androidx.compose.ui.geometry.Offset(step * index, y))
        }
        Text("A", style = Type.title, color = fg)
    }
    Hint("Press left or right. Text and the layout around it grow together, here and across the launcher.")
}

/** Settings › Control Center: which tiles show. */
@Composable
private fun ColumnScope.ControlCenterTilesPage(model: HomeModel, cfg: LauncherConfig, f: Modifier) {
    PanelTitle("Control Center")
    androidx.compose.runtime.LaunchedEffect(Unit) { dev.glasslauncher.home.CcEditEntry.warmWanted = true }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { dev.glasslauncher.home.CcEditEntry.warmWanted = false } }
    // The only way into edit mode (P101): Control Center opens on the right already editing; Done returns here.
    val openOverlay = dev.glasslauncher.home.LocalOpenOverlay.current
    MenuRow("Edit Layout", {
        dev.glasslauncher.home.CcEditEntry.startInEdit = true
        dev.glasslauncher.home.CcEditEntry.fromSettings = true
        openOverlay(dev.glasslauncher.home.Overlay.ControlCenter)
    }, f, chevron = true)
    Hint("Move, resize, remove and add controls. Control Center opens on the right; Done returns here.")
    val hasAlexa = dev.glasslauncher.system.DeviceProfile.hasAlexa(LocalContext.current.packageManager)
    dev.glasslauncher.home.CONTROL_CENTER_TILES.filter { (id, _) -> id != "alexa" || hasAlexa }.forEachIndexed { i, (id, label) ->
        val layout = dev.glasslauncher.home.CcLayout.resolve(cfg.ccLayout, cfg.ccHidden)
        ToggleRow(label, layout.shown(id), { on ->
            // The old per-tile switches fold into the layout (ccHidden is read once, then cleared).
            model.edit { c -> c.copy(ccLayout = dev.glasslauncher.home.CcLayout.resolve(c.ccLayout, c.ccHidden).withShown(id, on).toJson(), ccHidden = emptySet()) }
        }, Modifier)
    }
}


@Composable
private fun ColumnScope.AddonsPage(model: HomeModel, cfg: LauncherConfig, f: Modifier, push: (Page) -> Unit) {
    val context = LocalContext.current
    val ask = dev.glasslauncher.home.LocalConfirm.current
    PanelTitle("Add-ons")
    val addons by androidx.compose.runtime.produceState(emptyList<dev.glasslauncher.addons.AddonInfo>()) { value = dev.glasslauncher.addons.AddonHost.installed(context) }
    Hint("Add-ons are separate apps. A new one starts off; switch it on to show its Control Center tiles. It runs in its own process and can only send VITRE labels and state.")
    addons.forEachIndexed { i, a ->
        val trust = dev.glasslauncher.addons.AddonTrust
        val on = trust.isEnabled(cfg.addonsEnabled, a.pkg, a.cert)
        val paused = on && dev.glasslauncher.addons.AddonHost.isMuted(a.pkg)
        val changed = trust.certChanged(cfg.addonsEnabled, a.pkg, a.cert)
        fun set(v: Boolean) {
            if (v) dev.glasslauncher.addons.AddonHost.resume(a.pkg)
            model.edit { c -> c.copy(addonsEnabled = if (v) trust.enable(c.addonsEnabled, a.pkg, a.cert) else trust.disable(c.addonsEnabled, a.pkg)) }
        }
        ToggleRow(a.title, on, { v ->
            if (v && changed) ask(dev.glasslauncher.home.Overlay.Confirm(
                "Signing certificate changed",
                trust.certChangeMessage(a.title, a.pkg, cfg.addonsEnabled.getValue(a.pkg), a.cert),
                "Switch On", destructive = true,
            ) { set(true) }) else set(v)
        }, if (i == 0) f else Modifier, enabled = a.compatible,
            help = "${a.pkg} · ${trust.fingerprint(a.cert)}\n" + when {
                !a.compatible -> "Needs a newer or older VITRE (API ${a.api})."
                paused -> "Paused · stopped responding. Switch off and on to resume."
                changed -> "Switched off: its signing certificate changed."
                else -> "${a.points.joinToString(", ") { pointName(it) }}${if (a.needsRoot) " · needs root" else ""} · not from the VITRE repo"
            })
        if (on && dev.glasslauncher.addon.AddonPoint.SETTINGS in a.points) MenuRow("${a.title} Settings", { push(Page.AddonForm(a.pkg)) }, chevron = true)
    }
}

private fun pointName(p: String) = when (p) {
    dev.glasslauncher.addon.AddonPoint.TOPSHELF -> "Top Shelf"
    dev.glasslauncher.addon.AddonPoint.HOME_ROW -> "Home row"
    dev.glasslauncher.addon.AddonPoint.SETTINGS -> "Settings page"
    dev.glasslauncher.addon.AddonPoint.ACTIONS -> "Remote action"
    else -> "Control Center tile"
}

/** An add-on's settings page: Glass asks the add-on for the form, draws it in its own style and stores the values (the add-on gets them back with every call). */
@Composable
private fun ColumnScope.AddonFormPage(model: HomeModel, cfg: LauncherConfig, pkg: String, f: Modifier, open: (Overlay) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snap = dev.glasslauncher.addons.AddonSnapshot(cfg.addonsEnabled, cfg.addonSettings)
    // The form is read once when the page opens (not on every value change); a failure shows as such.
    val form by androidx.compose.runtime.produceState<Pair<dev.glasslauncher.addons.SafeForm?, Boolean>>(null to false, pkg) {
        value = dev.glasslauncher.addons.AddonSurfaces.form(context, snap, pkg) to true
    }
    PanelTitle(form.first?.title ?: "Add-on")
    val shown = form.first
    // Values for fields the add-on no longer declares are dropped.
    // Only a valid reply decides this, and only ids it does not name at all: a rule Glass rejected keeps the field's value.
    androidx.compose.runtime.LaunchedEffect(shown) {
        if (shown != null) dev.glasslauncher.addons.AddonPurge.undeclared(cfg.addonSettings, pkg, shown.declared).takeIf { it.isNotEmpty() }
            ?.let { stale -> model.edit { c -> c.copy(addonSettings = c.addonSettings - stale) } }
    }
    if (shown == null) { Hint(if (form.second) "Couldn't reach the add-on." else "Loading…"); Box(Modifier.size(1.dp)); return }
    Hint("From an add-on. VITRE keeps these values and sends them to the add-on.")
    val stored = snap.valuesOf(pkg)
    fun set(id: String, v: String) {
        // Dependents are forgotten only when the value really changes.
        val old = shown.fields.firstOrNull { it.id == id }?.let { dev.glasslauncher.addons.FormRules.valueOf(it, stored) } ?: ""
        val stale = dev.glasslauncher.addons.FormRules.dependentsOnChange(shown.fields, id, old, dev.glasslauncher.addons.AddonContent.value(v)).map { "$pkg/$it" }.toSet()
        model.edit { c -> c.copy(addonSettings = c.addonSettings - stale + ("$pkg/$id" to dev.glasslauncher.addons.AddonContent.value(v))) }
    }
    val cache = remember(pkg) { dev.glasslauncher.addons.OptionsCache() }
    // Fields whose rule does not hold right now are not drawn (their values stay stored); a change to what a rule or an option list
    // depends on redraws or re-asks, nothing else does.
    dev.glasslauncher.addons.FormRules.visible(shown.fields, stored).forEachIndexed { i, fl ->
        androidx.compose.runtime.key(fl.id) {
            val mod = if (i == 0) f else Modifier
            val v = dev.glasslauncher.addons.FormRules.valueOf(fl, stored)
            when (fl.type) {
                "toggle" -> ToggleRow(fl.label, v == "true", { on -> set(fl.id, on.toString()) }, mod, help = fl.help)
                "choice" -> {
                    val dep = fl.optionsFrom?.let { d -> shown.fields.firstOrNull { it.id == d }?.let { dev.glasslauncher.addons.FormRules.valueOf(it, stored) } }
                    // Another read when this row gains focus while its last one failed (never on a timer).
                    var retry by remember(fl.id) { mutableStateOf(0) }
                    val options by androidx.compose.runtime.produceState(dev.glasslauncher.addons.FormRules.initialOptions(fl), fl.id, dep, retry) {
                        // A new dependency value starts from the static options, never the previous dependency's.
                        value = dev.glasslauncher.addons.FormRules.initialOptions(fl)
                        if (fl.optionsFrom == null || dep == null) return@produceState
                        val hit = cache.get(fl.id, dep)
                        if (hit != null) { value = dev.glasslauncher.addons.FormRules.Options(hit, dev.glasslauncher.addons.FormRules.Status.Ready); return@produceState }
                        val got = dev.glasslauncher.addons.AddonSurfaces.options(context, snap, pkg, fl.id)
                        if (got != null) { cache.put(fl.id, dep, got); value = dev.glasslauncher.addons.FormRules.Options(got, dev.glasslauncher.addons.FormRules.Status.Ready) }
                        else value = dev.glasslauncher.addons.FormRules.Options(emptyList(), dev.glasslauncher.addons.FormRules.Status.Failed)
                    }
                    val failed = options.status == dev.glasslauncher.addons.FormRules.Status.Failed
                    MenuRow(fl.label, { if (dev.glasslauncher.addons.FormRules.choiceSelectable(options)) set(fl.id, dev.glasslauncher.addons.FormRules.next(options.choices, v)) },
                        mod.onFocusChanged { if (it.hasFocus && failed) retry++ }, value = dev.glasslauncher.addons.FormRules.choiceText(options, v), help = fl.help)
                }
                "text" -> MenuRow(fl.label, { open(Overlay.TextInput(fl.label, v, fl.help ?: "") { t -> set(fl.id, t) }) }, mod, value = v.ifEmpty { "Not set" }, help = fl.help)
                else -> MenuRow(fl.label, {
                    scope.launch {
                        val msg = dev.glasslauncher.addons.AddonSurfaces.action(context, snap, pkg, fl.id)
                        dev.glasslauncher.system.RemoteNotice.show(context, msg?.ifEmpty { "Done" } ?: "The add-on didn't answer", 3_000)
                    }
                }, mod, help = fl.help)
            }
        }
    }
    Box(Modifier.size(1.dp))
}
