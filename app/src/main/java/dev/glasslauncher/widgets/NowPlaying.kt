package dev.glasslauncher.widgets

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/** Exists only so the launcher may read active media sessions; it ignores notifications. */
import dev.glasslauncher.system.NoticeStore

class NowPlayingService : NotificationListenerService() {
    /**
     * The drawer's source (P98). Now Playing runs off media *sessions*, so this listener was empty; the same grant
     * (`cmd notification allow_listener`) is what lets Glass see other apps' notifications at all.
     */
    override fun onListenerConnected() {
        super.onListenerConnected(); instance = this; NoticeStore.onConnected()
        // The system does NOT re-post what is already up when a listener connects, so a listener that binds late
        // (after a boot, or after the app was force-stopped) would show an empty drawer while the shade is full.
        val active = runCatching { activeNotifications }.getOrNull()
        android.util.Log.e("P98x", "connected, active=${active?.size}")
        active?.forEach { sbn -> dev.glasslauncher.system.noticeOf(sbn)?.let { NoticeStore.posted(it) } }
    }

    override fun onListenerDisconnected() { super.onListenerDisconnected(); NoticeStore.disconnected() }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn ?: return
        val n = dev.glasslauncher.system.noticeOf(sbn)?.let { if (it.media || !playsMedia(it.pkg)) it else it.copy(media = true) }
        android.util.Log.e("P98x", "posted ${sbn.packageName} -> ${n?.title ?: "FILTERED"} flags=${sbn.notification?.flags} cat=${sbn.notification?.category}")
        n?.let { NoticeStore.posted(it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        sbn?.key?.let { NoticeStore.removed(it) }
    }

    /** What Select on a row does: drop it from the shade, as the drawer does. */
    fun dismiss(key: String) = runCatching { cancelNotification(key) }

    /**
     * Whether [pkg] is playing something right now, by its media session rather than by anything on the notification.
     * Plenty of players (Fire's own Alexa media controller among them) post a bare foreground-service notification with
     * no category and no session extra, so the notification alone cannot say - and Now Playing already owns those.
     */
    private fun playsMedia(pkg: String): Boolean = runCatching {
        val mgr = getSystemService(android.media.session.MediaSessionManager::class.java) ?: return false
        mgr.getActiveSessions(ComponentName(this, NowPlayingService::class.java)).any { it.packageName == pkg }
    }.getOrDefault(false)

    companion object {
        /** The connected listener, for the one thing the drawer has to ask it: drop this notification. */
        @Volatile var instance: NowPlayingService? = null
    }
}

/**
 * The media session in front: what's playing (or paused) in any app that publishes one (Spotify, Amazon
 * Music, YouTube Music, VLC…). Position is extrapolated from the last update, so progress needs no polling.
 */
data class NowPlaying(
    val title: String,
    val artist: String?,
    val album: String?,
    val packageName: String,
    val art: Bitmap?,
    val durationMs: Long,
    val positionMs: Long,
    val positionAt: Long,
    val speed: Float,
    val playing: Boolean,
    val controller: Controls,
    /** The session accepts ACTION_SEEK_TO and the item has a length: the scrubber shows. */
    val canSeek: Boolean = false,
    /** The like control the session offers (hidden when [Like.None]) and its state. */
    val like: Like = Like.None,
    /** [art] is the app's own icon (no cover was published): fine in the art slot, never baked into Home's backdrop. */
    val artIsIcon: Boolean = false,
    /** Playing, or the session says it is buffering (Spotify does between tracks and after a resume while sound may still come): the visualizer stays up for both. */
    val sounding: Boolean = playing,
) {
    /** What play/pause and skip do: a media session's controls, or AirPlay's transport. */
    interface Controls {
        fun play()
        fun pause()
        fun next()
        fun previous()
        fun seekTo(positionMs: Long) {}
        /** Heart rating (true) or its removal. */
        fun setHeart(liked: Boolean) {}
        /** Thumb-up rating (true) or clearing it. */
        fun setThumbUp(liked: Boolean) {}
        fun customAction(id: String) {}
    }

    fun positionNow(now: Long = SystemClock.elapsedRealtime()): Long =
        if (!playing) positionMs else (positionMs + ((now - positionAt) * speed).toLong()).coerceIn(0, durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)

    fun playPause() = if (playing) controller.pause() else controller.play()
    fun next() = controller.next()
    fun previous() = controller.previous()

    /** Seeks [deltaMs] from where the bar is now, clamped to 0..duration. No-op unless the session can seek. */
    fun seekBy(deltaMs: Long) {
        if (!canSeek || durationMs <= 0) return
        controller.seekTo((positionNow() + deltaMs).coerceIn(0, durationMs))
    }

    /** Flips the like through whichever mechanism the session offers. No-op without one. */
    fun toggleLike() {
        when (val l = like) {
            Like.None -> Unit
            is Like.Heart -> controller.setHeart(!l.liked)
            is Like.ThumbUpDown -> controller.setThumbUp(!l.liked)
            is Like.Custom -> controller.customAction(l.id)
        }
    }

    /** Identity of the track and its state, for animations and backdrop keys (not the per-update position). */
    val key: String get() = "$packageName|$title|$artist|$album"
}

private class SessionControls(private val t: MediaController.TransportControls) : NowPlaying.Controls {
    override fun play() = t.play()
    override fun pause() = t.pause()
    override fun next() = t.skipToNext()
    override fun previous() = t.skipToPrevious()
    override fun seekTo(positionMs: Long) = t.seekTo(positionMs)
    override fun setHeart(liked: Boolean) = t.setRating(Rating.newHeartRating(liked))
    override fun setThumbUp(liked: Boolean) =
        t.setRating(if (liked) Rating.newThumbRating(true) else Rating.newUnratedRating(Rating.RATING_THUMB_UP_DOWN))
    override fun customAction(id: String) = t.sendCustomAction(id, null)
}

object NowPlayingSource {
    fun component(context: Context) = ComponentName(context, NowPlayingService::class.java)

    fun isAllowed(context: Context) =
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    fun flow(context: Context): Flow<NowPlaying?> {
        if (!isAllowed(context)) { android.util.Log.w("GlassNowPlaying", "notification access not granted"); return flowOf(null) }
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return flowOf(null)
        return callbackFlow {
            val handler = Handler(Looper.getMainLooper())
            val watched = HashMap<MediaController, MediaController.Callback>()
            // Each playback update hands back a new MediaMetadata object, so the art is kept per track
            // (by its text), not per object: otherwise every pause re-scaled it and re-baked Home's backdrop.
            var artFor: String? = null
            var art: Bitmap? = null
            // Cover URIs in flight, and ones that failed (retried after a minute): the fetch starts when the metadata
            // arrives, off the main thread, and publishes again when it lands.
            var republish: () -> Unit = {}
            val inflight = HashSet<String>()
            val failedAt = HashMap<String, Long>()
            // The cover is cached per track AND uri (P324): some apps keep one uri whose bytes change with the track, and a
            // uri-only key kept the first cover for good. [waitMs] gives such a provider time to switch before it is read.
            var lastUri: String? = null
            var lastPick: ArtPick.Pick<Bitmap>? = null
            var lastPickInputs: List<Any?>? = null
            var lastUriTrack: String? = null
            fun fetchArt(uri: String, key: String, pkg: String, waitMs: Long) {
                if (!inflight.add(key)) return
                val began = SystemClock.elapsedRealtime()
                val (flight, mine) = ArtStore.begin(key)
                launch(kotlinx.coroutines.Dispatchers.IO) {
                    if (mine) {
                        if (waitMs > 0) kotlinx.coroutines.delay(waitMs)
                        ArtStore.finish(key, flight, ArtFetcher.fetch(context.applicationContext, uri, pkg))
                    }
                    val bmp = flight.await()
                    handler.post {
                        inflight.remove(key)
                        if (bmp == null) failedAt[key] = SystemClock.elapsedRealtime()
                        android.util.Log.i("GlassNowPlaying", "art ${if (bmp != null) "ok" else "failed"} in ${SystemClock.elapsedRealtime() - began} ms")
                        republish()
                    }
                }
            }

            fun publish() {
                // Playing first; else a paused session (so it can be resumed from Control Center).
                val c = watched.keys.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                    ?: watched.keys.firstOrNull { it.playbackState?.state == PlaybackState.STATE_BUFFERING }
                    ?: watched.keys.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED }
                val meta = c?.metadata
                val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                if (c == null || meta == null || title == null) { trySend(null); return }
                val track = "${c.packageName}|$title|${meta.getString(MediaMetadata.METADATA_KEY_ARTIST)}|${meta.getString(MediaMetadata.METADATA_KEY_ALBUM)}"
                // Every playback tick and metadata callback lands here; the pick depends only on these inputs, so it is made once per change.
                val artBitmap = meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
                val albumBitmap = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                val iconBitmap = meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                val artUri = meta.getString(MediaMetadata.METADATA_KEY_ART_URI)
                val albumUri = meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                val iconUri = meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
                val inputs = listOf(track, artUri, albumUri, iconUri, System.identityHashCode(artBitmap), System.identityHashCode(albumBitmap), System.identityHashCode(iconBitmap))
                val pick = lastPick?.takeIf { lastPickInputs == inputs }
                    ?: ArtPick.pick(artBitmap, albumBitmap, iconBitmap, artUri, albumUri, iconUri).also { lastPick = it; lastPickInputs = inputs }
                var artIsIcon = pick.isIcon
                val shownArt: Bitmap?
                if (pick.uri != null) {
                    val uri = pick.uri
                    val key = "$track|$uri"
                    val hit = ArtStore.cache.get(key)
                    val failed = failedAt[key]?.let { SystemClock.elapsedRealtime() - it < 60_000 } == true
                    // The same uri as the previous track's: the app may not have switched its cover yet.
                    val sameUriNewTrack = uri == lastUri && track != lastUriTrack
                    if (hit == null && !failed) fetchArt(uri, key, c.packageName, if (sameUriNewTrack) 1_500 else 0)
                    lastUri = uri; lastUriTrack = track
                    // Until the cover lands: the neutral placeholder (never the app's logo, unless the fetch failed).
                    shownArt = hit ?: pick.fallback?.takeIf { failed }?.let { scaled(it) }.also { if (it != null) artIsIcon = pick.fallbackIsIcon }
                } else {
                    // The same kind of source for the same track keeps the scaled copy (a pause hands back a new metadata object).
                    val sourceKey = "$track|${if (pick.bitmap == null) "none" else if (pick.isIcon) "icon" else "art"}"
                    if (sourceKey != artFor || (art == null && pick.bitmap != null)) { artFor = sourceKey; art = pick.bitmap?.let { scaled(it) } }
                    shownArt = art
                }
                val state = c.playbackState
                val now = SystemClock.elapsedRealtime()
                val duration = NowPlayingCaps.duration(meta.getLong(MediaMetadata.METADATA_KEY_DURATION))
                val actions = state?.actions ?: 0L
                val userRating = runCatching { meta.getRating(MediaMetadata.METADATA_KEY_USER_RATING) }.getOrNull()
                val userLiked = userRating?.takeIf { it.isRated }?.let { r ->
                    when (r.ratingStyle) { Rating.RATING_HEART -> r.hasHeart(); Rating.RATING_THUMB_UP_DOWN -> r.isThumbUp; else -> null }
                }
                val custom = state?.customActions.orEmpty().map { it.action to it.name }
                trySend(
                    NowPlaying(
                        title = NowPlayingCaps.text(title) ?: run { trySend(null); return },
                        artist = NowPlayingCaps.text(meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)),
                        album = NowPlayingCaps.text(meta.getString(MediaMetadata.METADATA_KEY_ALBUM)),
                        packageName = c.packageName,
                        art = shownArt,
                        durationMs = duration,
                        positionMs = NowPlayingCaps.position(state?.position ?: 0, duration),
                        positionAt = NowPlayingCaps.updatedAt(state?.lastPositionUpdateTime ?: 0, now),
                        speed = NowPlayingCaps.speed(state?.playbackSpeed ?: 1f),
                        playing = state?.state == PlaybackState.STATE_PLAYING,
                        sounding = state?.state == PlaybackState.STATE_PLAYING || state?.state == PlaybackState.STATE_BUFFERING,
                        controller = SessionControls(c.transportControls),
                        canSeek = NowPlayingCaps.canSeek(actions, duration),
                        like = NowPlayingCaps.like(actions, c.ratingType, userLiked, custom),
                        artIsIcon = artIsIcon && shownArt != null,
                    ),
                )
            }

            republish = ::publish

            fun watch(controllers: List<MediaController>?) {
                watched.forEach { (c, cb) -> c.unregisterCallback(cb) }
                watched.clear()
                controllers.orEmpty().forEach { c ->
                    val cb = object : MediaController.Callback() {
                        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
                        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
                        // A killed app's controller keeps reporting its last state (playing), so it goes.
                        override fun onSessionDestroyed() { c.unregisterCallback(this); watched.remove(c); publish() }
                    }
                    c.registerCallback(cb, handler)
                    watched[c] = cb
                }
                publish()
            }

            val listener = MediaSessionManager.OnActiveSessionsChangedListener { watch(it) }
            runCatching {
                manager.addOnActiveSessionsChangedListener(listener, component(context), handler)
                watch(manager.getActiveSessions(component(context)))
            }.onFailure { android.util.Log.w("GlassNowPlaying", "can't read media sessions", it); trySend(null) }
            awaitClose {
                runCatching { manager.removeOnActiveSessionsChangedListener(listener) }
                watched.forEach { (c, cb) -> c.unregisterCallback(cb) }
            }
        }.distinctUntilChanged { a, b ->
            sameState(a, b)
        }
    }

    /** Artwork at most 512 px: enough for the card and the baked backdrop, small to keep. */
    private fun scaled(b: Bitmap): Bitmap {
        val max = 512
        if (b.width <= max && b.height <= max) return b.copy(Bitmap.Config.ARGB_8888, false) ?: b
        val k = max / maxOf(b.width, b.height).toFloat()
        return Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true)
    }
}

/**
 * The art as Home's takeover backdrop: blurred far past recognition, like tvOS (shrunk to 10 px, then
 * grown back in smooth steps; one cheap pass per track), and dimmed a little so white text reads on any cover.
 */
fun backdropArt(art: Bitmap): Bitmap {
    var b = Bitmap.createScaledBitmap(art, 10, 10, true)
    for (size in intArrayOf(20, 40, 80, 160, 320)) {
        val next = Bitmap.createScaledBitmap(b, size, size * 9 / 16, true)
        if (b !== art) b.recycle()
        b = next
    }
    val out = b.copy(Bitmap.Config.ARGB_8888, true)
    b.recycle()
    android.graphics.Canvas(out).drawColor(android.graphics.Color.argb(70, 0, 0, 0))
    return out
}

/**
 * An AirPlay audio session as a Now Playing item (title and artist when the sender gave them). Position and
 * duration are 0 when unknown, which the views already draw as no progress; there is no art (a broadcast
 * can't carry it). Skip does nothing: PhairPlay's transport is play, pause and stop.
 */
fun airPlayNowPlaying(track: dev.glasslauncher.system.AirPlayTrack, sender: String?, now: Long, onPlayPause: (Boolean) -> Unit): NowPlaying = NowPlaying(
    title = track.title ?: sender?.let { "AirPlay from $it" } ?: "AirPlay",
    artist = track.artist,
    album = track.album,
    packageName = "airplay",
    art = null,
    durationMs = track.durationMs ?: 0,
    positionMs = track.positionMs ?: 0,
    positionAt = now,
    speed = 1f,
    playing = track.playing,
    controller = object : NowPlaying.Controls {
        override fun play() = onPlayPause(true)
        override fun pause() = onPlayPause(false)
        override fun next() = Unit
        override fun previous() = Unit
    },
)

/** One shared session watcher for Home and Control Center (started while anything shows it). An AirPlay audio session takes over while it lasts. */
class NowPlayingRepository(context: Context, scope: CoroutineScope) {
    private val hub = dev.glasslauncher.system.AirPlay.hub(context)
    val state: StateFlow<NowPlaying?> = kotlinx.coroutines.flow.combine(NowPlayingSource.flow(context.applicationContext), hub.ui) { media, air ->
        air.track?.let { airPlayNowPlaying(it, air.sender, SystemClock.elapsedRealtime()) { _ -> hub.playPause() } } ?: media
    }.distinctUntilChanged { a, b ->
        sameState(a, b)
    // Stays warm for ten minutes behind an app, so a Home press already knows what is playing on its first frame (P142).
    }.stateIn(scope, SharingStarted.WhileSubscribed(10 * 60_000), null)
}

/** Whether two items need no re-publish: same track and the same play, position, length, seek and like state. */
internal fun sameState(a: NowPlaying?, b: NowPlaying?) =
    a?.key == b?.key && a?.playing == b?.playing && a?.positionMs == b?.positionMs && a?.durationMs == b?.durationMs &&
        a?.canSeek == b?.canSeek && a?.like == b?.like && a?.art === b?.art && a?.artIsIcon == b?.artIsIcon

/** Test seam: screenshot tests put a fixed now-playing state here; null (always, in the app) uses the real repository. */
object NowPlayingFixture {
    @Volatile var state: StateFlow<NowPlaying?>? = null
}
