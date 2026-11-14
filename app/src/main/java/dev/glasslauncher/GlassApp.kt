package dev.glasslauncher

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dev.glasslauncher.apps.AppRepository
import dev.glasslauncher.apps.IconPacks
import dev.glasslauncher.apps.TileArt
import dev.glasslauncher.data.ConfigStore
import dev.glasslauncher.featured.FeaturedRepository
import dev.glasslauncher.glass.WallpaperLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class GlassApp : Application(), SingletonImageLoader.Factory {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        dev.glasslauncher.ui.GpuCaches.onPressure(level)
        // Home is hidden (an app opened) or memory is tight: give back what rebuilds quickly on the way back.
        // The switcher's previews and the decoded logos reload from disk; the cached app heroes re-bake in
        // a fraction of a second. (The system trims the renderer's textures itself at these levels.)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            dev.glasslauncher.system.AppPreviews.trim()
            dev.glasslauncher.home.HeroCache.app.trim()
            dev.glasslauncher.home.HeroCache.cover.trim()
            tileArt.trim()
            SingletonImageLoader.get(this).memoryCache?.clear()
        }
    }

    override fun onCreate() {
        super.onCreate()
        dev.glasslauncher.glass.CcAdapt.variantFile = java.io.File(filesDir, "cc-glass")
        dev.glasslauncher.ui.NetworkEpoch.register(this)
        // A previous run that crashed while Glass held the TV muted for Sonos left it muted: undo that.
        chore(Dispatchers.Default) { dev.glasslauncher.sonos.SonosRoutes.recoverMute(this) }
        // Listen for PhairPlay from the start, so a pairing code or Now Playing that arrives before any screen opens is not lost.
        // Off the cold-start path: the hub resolves PhairPlay on its own thread.
        chore(Dispatchers.Default) { dev.glasslauncher.system.AirPlay.hub(this@GlassApp).start() }
        // A pairing code while another app is in front: a banner over it (Remote Buttons service), for as long as the code lives.
        // With the service off Glass's card is all there is (it waits in Home).
        scope.launch {
            val hub = dev.glasslauncher.system.AirPlay.hub(this@GlassApp)
            hub.ui.map { it.pairingCode }.distinctUntilChanged().collect { code ->
                val where = dev.glasslauncher.system.AirPlayCodePlacement.decide(
                    code, glassInFront = dev.glasslauncher.system.RemoteWatch.homeInFront, textInputUp = false,
                    bannerService = dev.glasslauncher.system.RemoteKeysService.instance != null,
                )
                if (code != null && where == dev.glasslauncher.system.AirPlayCodePlacement.Where.Banner) {
                    dev.glasslauncher.system.RemoteNotice.show(this@GlassApp, dev.glasslauncher.system.AirPlayCodePlacement.bannerText(code), (hub.pairingRemainingMs() ?: 4_000L).coerceIn(4_000L, 60_000L), actionable = true)
                }
            }
        }
        // RenderScript and the image loader take ~150 ms to set up cold; do it while the activity starts,
        // not on the first bake's critical path.
        chore(Dispatchers.Default) { wallpapers.prewarm() }
        // Installs and updates in progress: a badge on the tile and a capsule at the start and the end (P168).
        dev.glasslauncher.apps.InstallWatch.start(this)
        // The tile shadows bake once (a few ms to tens of ms on the stick): off the UI thread, before the first tile draws.
        chore(Dispatchers.Default) { dev.glasslauncher.ui.TileShadow.focus; dev.glasslauncher.ui.TileShadow.contact }
        // Old tiles on disk (changed apps, removed packs) are cleared away once per start, off the main thread.
        chore(Dispatchers.IO) { tileArt.trimDisk() }
        chore(Dispatchers.IO) { dev.glasslauncher.system.RootFeatures.reapplyAtStart(this@GlassApp) }
        // Own remote pairing (Settings > Root, P75 B): only when it was turned on.
        if (dev.glasslauncher.system.RootFeatures.ownRemotePairing(this)) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching { dev.glasslauncher.system.RemotePairer.obtain(this).start() }
                    .onFailure { android.util.Log.w("GlassApp", "remote pairing didn't start", it) }
            }
        }
        dev.glasslauncher.system.RemoteWatch.start(this)
        dev.glasslauncher.system.DeviceToasts.start(this)
        // Glass stopped unexpectedly and the system started it again: say so once (P214).
        chore(Dispatchers.IO) { dev.glasslauncher.system.CrashRecovery.check(this@GlassApp) }
        // Downloads that were waiting when Glass died (or the stick rebooted) carry on.
        chore(Dispatchers.IO) { dev.glasslauncher.downloads.Downloads.resumePending(this@GlassApp) }
        // A store visit cut short (Glass restarted): put Glass back as Home.
        chore(Dispatchers.IO) { dev.glasslauncher.system.AmazonStore.close(this@GlassApp, bringHome = false) }
    }

    /**
     * A best-effort start-up task, fire and forget. It must not take the process down if it fails, and in tests the
     * app is torn down while these may still be running (SharedPreferences gone): the exception went uncaught on
     * a pool thread and kotlinx-coroutines-test blamed whichever test ran next ("uncaught exceptions before the
     * test started": the flaky CoverFlowTest, FeaturedRowShadowTest, MoveHintsTest, CardTitleTest, P70).
     */
    private fun chore(dispatcher: kotlin.coroutines.CoroutineContext, block: suspend () -> Unit) {
        scope.launch(dispatcher) {
            try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { android.util.Log.w("GlassApp", "start-up task failed", e) }
        }
    }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val imageHttp: OkHttpClient by lazy {
        http.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()
    }
    val config by lazy { ConfigStore(this, scope) }
    val apps by lazy { AppRepository(this) }
    val iconPacks by lazy { IconPacks(this) }
    val tileArt by lazy { TileArt(this, iconPacks) }
    val wallpapers by lazy { WallpaperLoader(this, http) }
    val featured by lazy { FeaturedRepository(this, http) }
    val weather by lazy { dev.glasslauncher.widgets.WeatherRepository(http, scope) }
    val nowPlaying by lazy { dev.glasslauncher.widgets.NowPlayingRepository(this, scope) }

    /**
     * P247: how long Home may stay behind an app before its caches are released. Long enough that stepping into an
     * app and straight back keeps everything (the common case, and the one that must stay smooth); a real sit in
     * another app gives back the textures nobody can see.
     */
    private var hiddenRelease: kotlinx.coroutines.Job? = null

    fun homeHidden() {
        homeFront = false
        dev.glasslauncher.ui.GpuCaches.homeHidden()
        hiddenRelease?.cancel()
        hiddenRelease = scope.launch {
            kotlinx.coroutines.delay(HIDDEN_RELEASE_MS)
            releaseBehindApp()
        }
    }

    /**
     * Our own full-screen windows over Home (the saver's activity or dream) are in this process. `releaseWhileHidden` trims the whole
     * process's renderer state, which would take their surfaces with it (the visualizer's SurfaceView went hidden 20 s after the
     * saver opened: P334). Counted here by the saver hosts; while one is up the release keeps the memory work and skips that trim.
     */
    private val ownScreens = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var homeFront = false
    private var afterSaver: kotlinx.coroutines.Job? = null

    fun ownScreenUp(up: Boolean) {
        if (up) { ownScreens.incrementAndGet(); afterSaver?.cancel(); afterSaver = null; return }
        if (ownScreens.updateAndGet { maxOf(0, it - 1) } != 0) return
        // The saver's bitmaps (ASCII frames, slideshow pictures) and decoder buffers are freed only by a collection, and none runs while a saver is up
        // (above). So once Home has been back for a while with no saver, collect once: memory returns to where it was before the saver.
        afterSaver = scope.launch {
            kotlinx.coroutines.delay(AFTER_SAVER_GC_MS)
            if (ownScreens.get() == 0 && homeFront) kotlinx.coroutines.withContext(Dispatchers.Default) { System.gc() }
        }
    }

    fun homeShown() {
        homeFront = true
        dev.glasslauncher.ui.GpuCaches.homeShown()
        hiddenRelease?.cancel()
        hiddenRelease = null
    }

    /**
     * Everything here re-reads from disk (tiles, hero art), from the network's own disk cache (Coil) or is
     * rebuilt by the renderer: nothing that shows on the way back has to be fetched again.
     */
    private suspend fun releaseBehindApp() {
        dev.glasslauncher.system.AppPreviews.trim()
        dev.glasslauncher.home.HeroCache.app.trim()
        dev.glasslauncher.home.HeroCache.cover.trim()
        tileArt.empty()
        SingletonImageLoader.get(this).memoryCache?.clear()
        if (ownScreens.get() == 0) dev.glasslauncher.ui.GpuCaches.releaseWhileHidden()   // never under our own saver: it shares this process's renderer
        // Not under our own saver either: a 112 ms collection in the middle of the visualizer is a visible hitch.
        if (ownScreens.get() == 0) kotlinx.coroutines.withContext(Dispatchers.Default) { System.gc(); System.runFinalization(); System.gc() }
    }

    private companion object {
        const val HIDDEN_RELEASE_MS = 20_000L
        const val AFTER_SAVER_GC_MS = 15_000L
    }

    override fun newImageLoader(context: android.content.Context): ImageLoader =
        ImageLoader.Builder(context)
            // Images get tighter timeouts than the API calls: a stalled fetch must fail (and be retried) in seconds,
            // not hold a card empty for the 30 s a feed or tarball may need.
            .components {
                // An add-on's art goes through its own strict client first (dev.glasslauncher.addons.AddonArt).
                add(dev.glasslauncher.addons.AddonArtInterceptor(context))
                add(OkHttpNetworkFetcherFactory(callFactory = { imageHttp }))
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.06).build() }
            .crossfade(true)
            .build()
}

val android.content.Context.app: GlassApp get() = applicationContext as GlassApp
