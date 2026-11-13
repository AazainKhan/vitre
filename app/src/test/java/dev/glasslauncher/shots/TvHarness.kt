package dev.glasslauncher.shots

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.test.core.app.ApplicationProvider
import dev.glasslauncher.GlassApp
import dev.glasslauncher.app
import dev.glasslauncher.data.FeaturedConfig
import dev.glasslauncher.data.LauncherConfig
import kotlinx.coroutines.runBlocking
import coil3.SingletonImageLoader
import coil3.imageDecoderEnabled
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * Puts the Robolectric device into a known state before [dev.glasslauncher.MainActivity] starts:
 * a fixed set of installed TV apps, a seeded featured-shelf cache with local fixture art, and no
 * network, so every render is deterministic.
 */
object TvHarness {

    private class FakeApp(val label: String, val from: Int, val to: Int, val ink: Int = 0xFFFFFFFF.toInt())

    private val fakeApps = linkedMapOf(
        "com.netflix.ninja" to FakeApp("Netflix", 0xFF141414.toInt(), 0xFF2B0A0C.toInt(), 0xFFE50914.toInt()),
        "com.amazon.firetv.youtube" to FakeApp("YouTube", 0xFFFFFFFF.toInt(), 0xFFF2F2F2.toInt(), 0xFFFF0033.toInt()),
        "com.stremio.one" to FakeApp("Stremio", 0xFF1B1340.toInt(), 0xFF3A2A8C.toInt()),
        "com.spotify.tv.android" to FakeApp("Spotify", 0xFF121212.toInt(), 0xFF1E3A26.toInt(), 0xFF1ED760.toInt()),
        "com.plexapp.android" to FakeApp("Plex", 0xFF1F1F1F.toInt(), 0xFF3A3A3A.toInt(), 0xFFE5A00D.toInt()),
        "org.jellyfin.androidtv" to FakeApp("Jellyfin", 0xFF101828.toInt(), 0xFF5B2A86.toInt()),
        "com.disney.disneyplus" to FakeApp("Disney+", 0xFF0B1A3A.toInt(), 0xFF1D3F8F.toInt()),
        "com.hbo.hbonow" to FakeApp("Max", 0xFF002BE7.toInt(), 0xFF0016A0.toInt()),
        "com.apple.atve.amazon.appletv" to FakeApp("Apple TV", 0xFF000000.toInt(), 0xFF1C1C1E.toInt()),
        "tv.twitch.android.viewer" to FakeApp("Twitch", 0xFF6441A5.toInt(), 0xFF9146FF.toInt()),
        "org.videolan.vlc" to FakeApp("VLC", 0xFFFF8800.toInt(), 0xFFFFA733.toInt()),
        "com.amazon.tv.settings.v2" to FakeApp("Settings", 0xFF2C2C2E.toInt(), 0xFF48484A.toInt()),
        "com.esaba.downloader" to FakeApp("Downloader", 0xFFF57C00.toInt(), 0xFFFF9800.toInt()),
        "com.estrongs.android.pop" to FakeApp("ES File Explorer", 0xFF1E88E5.toInt(), 0xFF42A5F5.toInt()),
    )

    /** The dock HomeModel's first run would seed from [fakeApps] (Netflix first). */
    private val DOCK: List<String> by lazy {
        dev.glasslauncher.home.HomeModel.DEFAULT_DOCK.filter { it in fakeApps }.distinct().take(dev.glasslauncher.home.DOCK_SIZE)
    }

    private var savedZone: java.util.TimeZone? = null
    private var savedNow: (() -> Long)? = null

    /**
     * The clock, date and status pill read [dev.glasslauncher.widgets.WallClock] (java.util.Date ignores
     * Robolectric's clock), so pin it: a fixed zone and instant, never the machine's. GLASS_SHOTS_TIME=HH:mm
     * (UTC, on 2026-10-07) overrides the default 09:41; the baselines must hold at every time (the clock is
     * masked), which is how that is proven, e.g. `GLASS_SHOTS_TIME=10:05 scripts/shots verify`. The variable
     * is a declared input of the unit-test task, so changing it re-runs the tests instead of reusing a cache.
     * [PinnedClockRule] puts the real clock back after each test.
     */
    private fun pinClock() {
        if (savedNow == null) { savedZone = java.util.TimeZone.getDefault(); savedNow = dev.glasslauncher.widgets.WallClock.now }
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        val raw = System.getenv("GLASS_SHOTS_TIME")?.takeIf { it.isNotBlank() } ?: "09:41"
        val parts = raw.split(":")
        require(parts.size == 2 && parts.all { it.length in 1..2 && it.all(Char::isDigit) } && parts[0].toInt() < 24 && parts[1].toInt() < 60) {
            "GLASS_SHOTS_TIME must be HH:mm (00:00-23:59), was \"$raw\""
        }
        val at = java.time.LocalDate.of(2026, 10, 7).atTime(parts[0].toInt(), parts[1].toInt()).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        dev.glasslauncher.widgets.WallClock.now = { at }
    }

    /** For tests outside the harness's setUp that need the pinned clock (the screensaver views). */
    fun pinClockForTest() = pinClock()

    internal fun restoreClock() {
        savedZone?.let { java.util.TimeZone.setDefault(it) }
        savedNow?.let { dev.glasslauncher.widgets.WallClock.now = it }
        savedZone = null; savedNow = null
    }

    /** Package -> label, in install order. */
    val apps: Map<String, String> get() = fakeApps.mapValues { it.value.label }

    /**
     * Call before launching the activity. [config] edits the stored [LauncherConfig]; by default the
     * first-run tips are marked seen so they don't trap focus.
     */
    /**
     * [split] on: the tray and grid draw in their *own* ComposeViews, as they do on the device (TrayHost P248,
     * GridHost P258). The default (off) keeps them inside Home's view, which is the overlay path. Bugs live in the
     * difference — P226's fade and P300's tray-over-an-app were both invisible here because this line was always
     * false — so at least one screen is captured with it on (P295).
     */
    fun setUp(featured: Boolean = true, squareFeed: Boolean = false, split: Boolean = false, config: (LauncherConfig) -> LauncherConfig = { it }) {
        dev.glasslauncher.home.TrayHost.split = split
        // Keep Control Center's sheet (baked with each backdrop) readable, so tests can compare the tiles with it.
        dev.glasslauncher.home.CcMaterial.recordLast = true
        // P206: Control Center's default is the dock's clear glass (E), which this software canvas can't draw (it shows the
        // fallback fill), so the screens and material checks here run on A, the tone the fallback matches. E is checked
        // on the stick (contrast, stability) and by CcDockGlassTest.
        dev.glasslauncher.glass.CcAdapt.defaultVariant = "A"; dev.glasslauncher.glass.CcAdapt.variant = "A"
        dev.glasslauncher.glass.GlassMatch.keepPixels = true
        // The notifications store is process-wide too, and the pill draws a dot or a ring from it (P98), so a test must
        // not inherit another's - or the pill's golden depends on which tests ran first.
        dev.glasslauncher.system.NoticeStore.clear()
        dev.glasslauncher.system.NoticeStore.disconnected()
        // The app switcher remembers its last list in a process-wide static (so it opens full the next time): a test must not inherit another's.
        runCatching {
            Class.forName("dev.glasslauncher.home.AppSwitcherKt").getDeclaredField("lastApps").apply { isAccessible = true }.set(null, emptyList<dev.glasslauncher.apps.AppEntry>())
        }
        // P229: notices are cards on the screen now (a Toast never was one); keep them out of the goldens and brightness checks.
        dev.glasslauncher.system.RemoteNotice.overlayHostForTest = dev.glasslauncher.system.NoticeProbe(ApplicationProvider.getApplicationContext()).host
        blockNetwork()
        pinClock()
        useBitmapFactoryForImages()
        installApps()
        if (featured) seedFeatured(squareFeed)
        val app = ApplicationProvider.getApplicationContext<GlassApp>().app
        // From the defaults every time: the stored config outlives a test, so one test's text size or theme
        // leaked into the next.
        // Process-wide caches would carry one test's scene into the next.
        dev.glasslauncher.home.HeroCache.app.trim()
        // The dock is seeded here, not by HomeModel.seedDefaults: that runs after the first layout (a real
        // first-run bug, see the report: focus and hero start on the wrong dock app, then jump), so which app
        // got the first focus and hero depended on whether the test was the JVM's first (cold) or not.
        runBlocking { app.config.update { config(LauncherConfig(tipsSeen = true, seededDefaults = true, dock = DOCK)) } }
    }

    /** Fire's Alexa app, as a bare package: Control Center shows its page only where the app is (P155), and a JVM test is not a Fire TV. */
    fun installAlexa() {
        val pm = ApplicationProvider.getApplicationContext<GlassApp>().packageManager
        val info = ApplicationInfo().apply { packageName = dev.glasslauncher.home.AlexaIcon.PACKAGE; flags = ApplicationInfo.FLAG_INSTALLED }
        shadowOf(pm).installPackage(android.content.pm.PackageInfo().apply { packageName = info.packageName; applicationInfo = info })
    }

    /** Any request fails fast against a dead proxy, so sources fall back to the seeded cache. */
    private fun blockNetwork() {
        for (scheme in listOf("http", "https")) {
            System.setProperty("$scheme.proxyHost", "127.0.0.1")
            System.setProperty("$scheme.proxyPort", "9")
        }
    }

    /** Robolectric's native graphics lacks ImageDecoder, which Coil uses on API 28+; fall back to BitmapFactory. */
    private fun useBitmapFactoryForImages() {
        val context = ApplicationProvider.getApplicationContext<GlassApp>()
        SingletonImageLoader.setUnsafe(
            context.newImageLoader(context).newBuilder().imageDecoderEnabled(false).build(),
        )
    }

    private fun installApps() {
        val context = ApplicationProvider.getApplicationContext<GlassApp>()
        val pm = shadowOf(context.packageManager)
        for ((index, entry) in fakeApps.entries.withIndex()) {
            val (pkg, fake) = entry
            val label = fake.label
            val component = ComponentName(pkg, "$pkg.Main")
            val bannerId = 0x7f080000 + index
            val app = ApplicationInfo().apply {
                packageName = pkg
                name = label
                nonLocalizedLabel = label
                flags = ApplicationInfo.FLAG_INSTALLED
            }
            pm.installPackage(PackageInfo().apply { packageName = pkg; applicationInfo = app })
            pm.addOrUpdateActivity(ActivityInfo().apply {
                packageName = pkg
                name = component.className
                nonLocalizedLabel = label
                applicationInfo = app
                exported = true
                banner = bannerId
            })
            pm.addDrawableResolution(pkg, bannerId, BitmapDrawable(context.resources, banner(fake)))
            val filter = IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER) }
            pm.addIntentFilterForActivity(component, filter)
        }
    }

    /** A 320x180 stand-in for the app's TV banner: brand-ish gradient with the name set in bold. */
    private fun banner(app: FakeApp): Bitmap {
        val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRect(0f, 0f, 320f, 180f, Paint().apply {
            shader = LinearGradient(0f, 0f, 320f, 180f, app.from, app.to, Shader.TileMode.CLAMP)
        })
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = app.ink
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = if (app.label.length > 10) 30f else 46f
        }
        canvas.drawText(app.label, 160f, 90f - (text.descent() + text.ascent()) / 2, text)
        return bitmap
    }

    /** Writes the featured cache in [dev.glasslauncher.featured.FeaturedRepository]'s format. */
    private fun seedFeatured(square: Boolean = false) {
        val context = ApplicationProvider.getApplicationContext<GlassApp>()
        val titles = listOf(
            "Northern Lights" to "A cartographer maps the last uncharted coastline.",
            "The Long Quiet" to "Two keepers, one lighthouse, and a storm that won't end.",
            "Glass City" to "A heist across the rooftops of a city made of mirrors.",
            "Deep Field" to "The night shift at an observatory finds a signal.",
            "Saltwater" to "A family restaurant on the edge of the sea.",
            "Paper Planes" to "Kids build a glider club in a town with no wind.",
            "Low Orbit" to "A repair crew is stranded between two stations.",
            "Midnight Market" to "Every stall sells something you lost.",
        )
        val items = titles.mapIndexed { i, (title, description) ->
            val image = File(fixtures(), "featured${i + 1}.jpg").toURI().toString()
            """{"id":"fixture-$i","title":"$title","subtitle":"2026 · ${90 + i * 7} min","description":"$description","image":"$image"${if (square) ",\"aspect\":1.0" else ""}}"""
        }
        val key = dev.glasslauncher.featured.cacheKey(FeaturedConfig()).replace("\"", "\\\"")
        File(context.cacheDir, "featured.json").writeText(
            """{"key":"$key","heading":"Popular Movies","items":[${items.joinToString(",")}]}""",
        )
    }

    private fun fixtures(): File =
        File(requireNotNull(javaClass.classLoader?.getResource("fixtures/featured1.jpg")).toURI()).parentFile!!
}

/** Add as `@get:Rule val clock = PinnedClockRule()` to every test class that calls [TvHarness.setUp]; it undoes the clock pin afterwards. */
class PinnedClockRule : org.junit.rules.TestWatcher() {
    override fun finished(description: org.junit.runner.Description?) = TvHarness.restoreClock()
}
