package dev.glasslauncher.data

import kotlinx.serialization.Serializable

@Serializable
data class LauncherConfig(
    val dock: List<String> = emptyList(),
    /** Grid order. Entries are "app:<package>" or "folder:<id>"; unknown entries are ignored. */
    val order: List<String> = emptyList(),
    val folders: List<Folder> = emptyList(),
    val hidden: Set<String> = emptySet(),
    /** Package name -> absolute path of a user-chosen icon image. */
    val customIcons: Map<String, String> = emptyMap(),
    val iconPack: String? = null,
    val theme: ThemeMode = ThemeMode.Dark,
    val wallpaperDark: Wallpaper = Wallpaper(WallpaperKind.Preset, "aurora"),
    val wallpaperLight: Wallpaper = Wallpaper(WallpaperKind.Preset, "dawn"),
    val featured: FeaturedConfig = FeaturedConfig(),
    val screensaver: ScreensaverConfig = ScreensaverConfig(),
    val idleFadeMinutes: Int = 2,
    val clock24h: Boolean = false,
    val weather: WeatherConfig? = null,
    val showNowPlaying: Boolean = true,
    val homeGuard: Boolean = false,
    val seededDefaults: Boolean = false,
    val reduceMotion: Auto = Auto.Auto,
    val reduceTransparency: Boolean = false,
    val sounds: Boolean = true,
    /** Start the Aerial screensaver after this many idle minutes on Home; 0 = leave it to the system. */
    val aerialsOnIdleMinutes: Int = 0,
    val tipsSeen: Boolean = false,
    /** Packages whose notifications the drawer hides (P98). Empty = show everything. */
    val blockedNoticePackages: List<String> = emptyList(),
    /** What fills the screen behind Home: featured artwork, the wallpaper, or Aerial video. */
    val background: BackgroundMode = BackgroundMode.Featured,
    /** Text Size, tvOS-style: 1.0 default, up to 1.3; tiles and gutters grow with it. */
    val textScale: Float = 1f,
    /** Settings › Display & Text. */
    val boldText: Boolean = false,
    /** Settings › Display & Text › Font: the typeface of the whole interface. */
    val font: UiFont = UiFont.Inter,
    val increaseContrast: Boolean = false,
    /** Control Center tiles turned off in Settings › Control Center (ids in CONTROL_CENTER_TILES). */
    val ccHidden: Set<String> = emptySet(),
    /** The customizable Control Center's layout (P83, [dev.glasslauncher.home.CcLayout] JSON); null = the default, minus [ccHidden]. */
    val ccLayout: String? = null,
    /** Settings › About (Select on Version seven times): Control Center's demo add-on tiles, with fake data. Off by default. */
    val ccDemoTiles: Boolean = false,
    /** Add-ons the user has switched on: package -> SHA-256 of its signing certificate. A changed certificate leaves it off again. */
    val addonsEnabled: Map<String, String> = emptyMap(),
    /** Add-on settings the user set on an add-on's page: `package/field` -> value. Glass stores them and sends them with every call. */
    val addonSettings: Map<String, String> = emptyMap(),
    /** Back or Home while an app Glass just started is still loading returns Home at once (P87). */
    val instantBack: Boolean = true,
    /** Which one-time "hide by default" additions have been applied to this install. */
    val hiddenMigration: Int = 0,
    /** Screensaver: Glass's Aerials or photo slideshow, or Fire TV's own screensaver. */
    val screensaverMode: ScreensaverMode = ScreensaverMode.Aerials,
    /** Packages seen before; anything not in here is new and gets the blue dot until opened. */
    val seenApps: Set<String> = emptySet(),
    /** Most recently opened first; the app switcher's fallback when usage access isn't granted. */
    val recentApps: List<String> = emptyList(),
    /**
     * Apps closed from the app switcher, with when (epoch ms): hidden from the switcher until they next come to
     * the foreground after that moment (the system's usage history would otherwise bring them straight back).
     */
    val closedRecents: Map<String, Long> = emptyMap(),
    /** Remote button name (e.g. "KEYCODE_APP_1") -> action; see RemoteAction. Missing = Fire TV default. */
    val remoteButtons: Map<String, String> = emptyMap(),
    /** Stick tuning per game controller, by the controller's name (P186): the dead zone and sensitivity of each stick. */
    val controllerSticks: Map<String, ControllerSticks> = emptyMap(),
    /** Saved button mappings (P233), by device kind (`pad` or `remote`): see [MappingProfiles]. */
    val mappingProfiles: Map<String, List<MappingProfile>> = emptyMap(),
    /** The profile last loaded on each device, by device name, so its page can show which one it is on. */
    val activeProfile: Map<String, String> = emptyMap(),
    /** The remote key that opens Control Center over any app without the key-layout module (P167); see [dev.glasslauncher.system.CcShortcut]. */
    val ccShortcut: String = "menu_hold",
    /** Top Shelf: show the focused app's titles after a dwell (else only its hero). */
    val topShelfTitles: Boolean = true,
    /** Start the screensaver while Now Playing fills Home (off: the album art is the screensaver). */
    val screensaverDuringMusic: Boolean = false,
    /** Settings › Downloads. */
    val downloads: DownloadsConfig = DownloadsConfig(),
    /** Read-only legacy (one speaker): the picker now stores [audioOutputs]; this is read by [speakers] until the first change. */
    val audioOutput: AudioOutputConfig? = null,
    /** Control Center › AirPlay › Audio Output: the chosen network speakers (several play at once); empty = this TV. */
    val audioOutputs: List<AudioOutputConfig> = emptyList(),
    /** Mute the TV's music stream while playing on Sonos (restored on stop). Off by default. */
    val muteTvOnSonos: Boolean = false,
) {
    /** The speakers chosen for audio: [audioOutputs], else the single legacy [audioOutput]. */
    val speakers: List<AudioOutputConfig> get() = audioOutputs.ifEmpty { listOfNotNull(audioOutput) }

    /** Minutes of idle Home before Aerials start (when Aerials is the screensaver); 0 stored means the default. */
    val aerialsIdleMinutes: Int get() = aerialsOnIdleMinutes.takeIf { it > 0 } ?: 5
}

/** One stick's tuning: how far it must move before it counts (the dead zone, 0 to 0.5 of its travel) and how strongly it answers (0.5 to 2). */
@Serializable
data class StickTuning(val deadzoneX: Float = 0.12f, val deadzoneY: Float = 0.12f, val sensX: Float = 1f, val sensY: Float = 1f) {
    companion object { const val DEADZONE_MAX = 0.5f; const val SENS_MIN = 0.5f; const val SENS_MAX = 2f }
}

@Serializable
data class ControllerSticks(val left: StickTuning = StickTuning(), val right: StickTuning = StickTuning())

@Serializable
data class DownloadsConfig(
    val concurrent: Int = 2,
    val segments: Int = 4,
    /** Off: an APK is deleted once installed. */
    val keepInstalledApks: Boolean = false,
    /** What was typed into the URL entry, newest first, at most 20. */
    val history: List<String> = emptyList(),
    /** Full links the user already accepted for a download that started from a code (not whole sites: one link on a shared host proves nothing about the next). */
    val acceptedUrls: Set<String> = emptySet(),
    val codes: List<MyCode> = emptyList(),
)

/** A personal shortcut: [code] stands for [url]. */
@Serializable
data class MyCode(val code: String, val url: String, val name: String = "")

@Serializable
enum class BackgroundMode { Featured, Wallpaper, Motion }

@Serializable
enum class ThemeMode { System, Light, Dark }

/** Auto follows the system accessibility setting. */
@Serializable
enum class Auto { Auto, On, Off }

@Serializable
enum class WallpaperKind { Preset, File, Url }

@Serializable
data class Wallpaper(val kind: WallpaperKind, val value: String)

@Serializable
data class Folder(val id: String, val name: String, val apps: List<String>)

@Serializable
/** ContinueWatching and TvApp read other apps' TV rows (system-app Glass only); TvApp is internal to Focused App. */
enum class FeaturedSourceId { Off, Stremio, Tmdb, YouTube, Plex, ContinueWatching, TvApp, JustWatch, Addon }

/** Featured Row: follow the focused top-row app (tvOS), always one source, or off. */
enum class FeaturedMode { FocusedApp, OneSource, Off }

@Serializable
data class FeaturedConfig(
    /** The one source, and the fallback in [FeaturedMode.FocusedApp]. (Off here is the pre-mode way of turning it off.) */
    val source: FeaturedSourceId = FeaturedSourceId.Stremio,
    val mode: FeaturedMode = FeaturedMode.OneSource,
    /** For [FeaturedSourceId.TvApp]: whose rows. */
    val appPackage: String = "",
    /** For [FeaturedSourceId.Addon]: the add-on whose feed it is. */
    val addonPackage: String = "",
    /** For [FeaturedSourceId.JustWatch]: the service's JustWatch short name (nfx, amp, dnp…). */
    val justWatchPackage: String = "",
    val stremioCatalog: String = "movie/top",
    val tmdbKey: String = "",
    /** TMDB watch provider: netflix, prime, appletv, disney, max, or trending. */
    val tmdbProvider: String = "netflix",
    val tmdbRegion: String = "US",
    val youtubeKey: String = "",
    val youtubeRegion: String = "US",
    /** Public channels for the YouTube row (ids or channel URLs, any separator); their RSS feeds need no key or sign-in. */
    val youtubeChannels: String = "",
    val plexToken: String = "",
    val plexClientId: String = "",
)

@Serializable
data class ScreensaverConfig(
    val quality: AerialQuality = AerialQuality.Hd1080,
    val showLocation: Boolean = true,
    val showClock: Boolean = true,
    /** Aerial clip ids left out of rotation (Choose Aerials). */
    val hiddenAerials: Set<String> = emptySet(),
    /** Slideshow: the photo album (MediaStore bucket name); null plays every photo. */
    val album: String? = null,
    val photoSeconds: Int = 8,
    /** Slideshow: a slow zoom and pan across each photo. */
    val kenBurns: Boolean = true,
    /** ASCII: the baked piece's slug (P327); null until one is chosen. */
    val asciiPiece: String? = null,
    /** A glass Now Playing card floats over the screensaver while music plays (P326). */
    val nowPlayingCard: Boolean = false,
    /** The Now Playing saver shows a spectrum visualizer under the cover while music plays (P326). Needs audio access. */
    val visualizer: Boolean = false,
)

@Serializable
enum class AerialQuality { Hd1080, Uhd4k }

@Serializable
data class WeatherConfig(val city: String, val latitude: Double, val longitude: Double, val fahrenheit: Boolean)

fun appKey(pkg: String) = "app:$pkg"
fun folderKey(id: String) = "folder:$id"

enum class ScreensaverMode { Aerials, Slideshow, Ascii, NowPlaying, System }

/** The interface typeface choices (Settings › Display & Text › Font). Inter is bundled; the others are the TV's own faces. */
@kotlinx.serialization.Serializable
enum class UiFont(val label: String) {
    Inter("Inter"),
    System("System"),
    Condensed("Condensed"),
    Serif("Serif"),
}

/** A Sonos speaker chosen as the audio output: its address and room name. */
@Serializable
data class AudioOutputConfig(val host: String, val room: String)
