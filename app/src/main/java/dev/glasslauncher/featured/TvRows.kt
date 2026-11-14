package dev.glasslauncher.featured

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

/**
 * Other apps' TV rows from the system TV provider: the Watch Next list and the preview channels apps
 * publish (Stremio's "Continue Watching", Spotify, VLC…). Reading other apps' rows needs
 * ACCESS_ALL_EPG_DATA, which only a privileged system app gets (Settings › Root › System App).
 */
object TvRows {
    private const val PERMISSION = "com.android.providers.tv.permission.ACCESS_ALL_EPG_DATA"
    private val WATCH_NEXT = Uri.parse("content://android.media.tv/watch_next_program")
    private val CHANNELS = Uri.parse("content://android.media.tv/channel")
    private val PREVIEW = Uri.parse("content://android.media.tv/preview_program")
    private val CONTINUE = Regex("continue|resume|watch next|recently watched|keep watching|up next", RegexOption.IGNORE_CASE)

    /** Needs a runtime grant (the TV provider's own, "read all TV listings"): the plain APK can ask for it or have it granted over adb (P339). */
    const val LISTINGS = "android.permission.READ_TV_LISTINGS"

    /** Everything: other apps' channels and rows as well as Watch Next (a privileged system-app Glass). */
    fun available(context: Context): Boolean = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Watch Next, which is all Continue Watching needs: the system app's access, or the plain APK's READ_TV_LISTINGS. */
    fun watchNextAvailable(context: Context): Boolean = available(context) || context.checkSelfPermission(LISTINGS) == PackageManager.PERMISSION_GRANTED

    // No "browsable" filter: on Android TV the launcher decides which preview channels are browsable,
    // and Fire OS's launcher never marks any, so every app's channels read as hidden.

    /** Apps that publish at least one preview channel (cheap; for the "Focused app" shelf). */
    suspend fun packagesWithRows(context: Context): Set<String> = if (!available(context)) emptySet() else withContext(Dispatchers.IO) {
        query(context, CHANNELS, arrayOf("package_name"), "type=?", arrayOf("TYPE_PREVIEW")) { it.str("package_name") }.filterNotNull().toSet()
    }

    /** What you were watching, newest first: Watch Next, then apps' own "Continue watching" rows. */
    suspend fun continueWatching(context: Context): FeaturedFeed = withContext(Dispatchers.IO) {
        check(watchNextAvailable(context)) { "Allow Continue Watching in Settings › Featured Row" }
        val watchNext = query(context, WATCH_NEXT, null, null, null, "last_engagement_time_utc_millis DESC") { it.item() }
        // Apps' own "Continue watching" channels need the full access; with only READ_TV_LISTINGS, Watch Next is what there is.
        val channels = if (!available(context)) emptyList() else query(context, CHANNELS, arrayOf("_id", "display_name"), "type=?", arrayOf("TYPE_PREVIEW")) { c ->
            c.long("_id") to (c.str("display_name") ?: "")
        }.filter { CONTINUE.containsMatchIn(it.second) }.map { it.first }
        val rows = channels.flatMap { id -> previewItems(context, id) }
        FeaturedFeed("Continue Watching", (watchNext + rows).filterNotNull().distinctBy { it.packages.firstOrNull() + it.title }.take(12))
    }

    /** One app's rows, in its own order: what tvOS shows in the top shelf when that app is focused. */
    suspend fun appRow(context: Context, pkg: String, label: String, rotation: Int = 0): FeaturedFeed = withContext(Dispatchers.IO) {
        check(available(context)) { "Turn on Settings › Root › System App to read other apps' rows" }
        val channels = query(context, CHANNELS, arrayOf("_id", "display_name"), "package_name=? AND type=?", arrayOf(pkg, "TYPE_PREVIEW")) { c ->
            c.long("_id") to (c.str("display_name") ?: "")
        }.sortedByDescending { CONTINUE.containsMatchIn(it.second) }
        // Every preview channel the app publishes, then its Watch Next entries: a row of 4 came from reading one
        // channel and de-duplicating by title alone (P89).
        // One list per channel, then Watch Next, interleaved: a batch mixes the user's recents, the recommendations and the feed (P153).
        val perChannel = channels.map { (id, _) -> previewItems(context, id).filterNotNull() }
        val watchNext = query(context, WATCH_NEXT, null, "package_name=?", arrayOf(pkg), "last_engagement_time_utc_millis DESC") { it.item() }.filterNotNull()
        FeaturedFeed(channels.firstOrNull()?.second?.ifBlank { null } ?: label, assemble(interleave(perChannel + listOf(watchNext)), rotation = rotation))
    }

    /** How many titles a shelf row holds. */
    const val ROW_CAP = 20
    /** A shape filter that would leave fewer than this many keeps the whole row instead: a full row beats a uniform one. */
    private const val MIN_UNIFORM = 8

    /**
     * An app's raw programs into its row (pure, tested): the same title with a different link or episode is a
     * different program (de-duplicated by link, else by title and subtitle); items with art come before the few
     * without (which draw a tinted title card); a shape filter ([CardShape.uniform]) only applies when it leaves a
     * real row; up to [ROW_CAP].
     */
    fun assemble(items: List<FeaturedItem>, cap: Int = ROW_CAP, rotation: Int = 0): List<FeaturedItem> {
        val distinct = items.distinctBy { it.link ?: (it.title + "|" + (it.subtitle ?: "")) }
        // Most recently watched first, then art as the tiebreak only (P94). The old art-first hoist re-sorted the
        // provider's own recency order (the Watch Next query is already ordered by last_engagement_time), so a
        // recently watched item without a cover fell behind an older one that had one.
        val ordered = distinct.sortedWith(
            compareByDescending<FeaturedItem> { it.watchedAt ?: Long.MIN_VALUE }
                .thenBy { if (it.image.isNullOrBlank()) 1 else 0 },
        )
        val uniform = CardShape.uniform(ordered)
        return window(if (uniform.size >= MIN_UNIFORM || uniform.size == ordered.size) uniform else ordered, cap, rotation)
    }

    /**
     * [cap] items out of everything an app published: the first [cap] at rotation 0, and each later rotation steps half a window on,
     * wrapping round, so the shelf shows new batches over time instead of the same first covers (P153). All of them when they fit.
     */
    fun <T> window(all: List<T>, cap: Int, rotation: Int): List<T> {
        if (all.size <= cap) return all
        val start = (rotation.coerceAtLeast(0).toLong() * (cap / 2).coerceAtLeast(1) % all.size).toInt()
        return List(cap) { all[(start + it) % all.size] }
    }

    /** One item from each list in turn (round robin), so the first of every channel comes before the second of any. */
    fun <T> interleave(lists: List<List<T>>): List<T> {
        val out = ArrayList<T>(); var i = 0
        while (lists.any { i < it.size }) { lists.forEach { l -> l.getOrNull(i)?.let(out::add) }; i++ }
        return out
    }

    /**
     * Fires when an app publishes, changes or removes a row: the TV provider's preview and Watch Next programs and channels. Empty
     * flow without the privileged EPG permission (the plain-APK fallback keeps today's behaviour: no observer).
     */
    fun changes(context: Context): kotlinx.coroutines.flow.Flow<Unit> {
        if (!available(context)) return kotlinx.coroutines.flow.emptyFlow()
        return kotlinx.coroutines.flow.callbackFlow {
            val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) { trySend(Unit) }
            }
            val uris = listOf(PREVIEW, WATCH_NEXT, CHANNELS)
            val ok = uris.map { u -> runCatching { context.contentResolver.registerContentObserver(u, true, observer) }.isSuccess }
            if (ok.none { it }) close()
            awaitClose { runCatching { context.contentResolver.unregisterContentObserver(observer) } }
        }.conflate()
    }

    private fun previewItems(context: Context, channelId: Long) =
        query(context, PREVIEW, null, "channel_id=?", arrayOf(channelId.toString()), "weight DESC") { it.item() }

    private fun Cursor.item(): FeaturedItem? {
        val pkg = str("package_name") ?: return null
        val title = str("title") ?: return null
        val season = str("season_display_number"); val episode = str("episode_display_number")
        val episodeTitle = str("episode_title")
        val subtitle = listOfNotNull(
            if (season != null && episode != null) "S$season E$episode" else null,
            episodeTitle,
        ).joinToString(" · ").ifBlank { null }
        // Landscape art suits the full-screen backdrop; portrait posters fall back to the thumbnail.
        val aspect = int("poster_art_aspect_ratio")
        val poster = str("poster_art_uri"); val thumb = str("thumbnail_uri")
        val image = if (aspect in 0..2) poster ?: thumb else thumb ?: poster
        // The columns TvContract defines for details and progress (Watch Next and preview programs alike).
        val durationMs = long("duration_millis")
        val positionMs = long("last_playback_position_millis")
        return FeaturedItem(
            id = "tv:$pkg:${long("_id")}",
            title = title,
            subtitle = subtitle,
            // Spotify repeats the title as its description; show nothing rather than the title twice.
            description = str("short_description")?.takeUnless { it.equals(title, ignoreCase = true) },
            image = image,
            link = str("intent_uri"),
            packages = listOf(pkg),
            owner = pkg,
            watchedAt = long("last_engagement_time_utc_millis"),
            year = str("release_date")?.take(4)?.toIntOrNull(),
            rating = str("content_rating")?.substringAfterLast('/')?.substringAfterLast('_'),
            genre = str("genre")?.split(',')?.firstOrNull()?.trim()?.lowercase()?.replaceFirstChar { it.uppercase() },
            durationMin = (durationMs / 60_000).toInt().takeIf { it > 0 },
            episode = if (season != null && episode != null) "S$season, E$episode" else null,
            progress = if (durationMs > 0 && positionMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else null,
            // The art shown is the poster (its own aspect) unless it fell back to the thumbnail (16:9).
            aspect = if (image == poster) CardShape.fromTvContract(aspect) else null,
        )
    }

    private fun <T> query(context: Context, uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, order: String? = null, map: (Cursor) -> T): List<T> =
        runCatching {
            context.contentResolver.query(uri, projection, selection, args, order)?.use { c -> buildList { while (c.moveToNext()) add(map(c)) } }
        }.getOrNull().orEmpty()

    private fun Cursor.str(col: String): String? = getColumnIndex(col).takeIf { it >= 0 }?.let { if (isNull(it)) null else getString(it) }?.takeIf { it.isNotBlank() }
    private fun Cursor.long(col: String): Long = getColumnIndex(col).takeIf { it >= 0 }?.let { getLong(it) } ?: 0L
    private fun Cursor.int(col: String): Int = getColumnIndex(col).takeIf { it >= 0 && !isNull(it) }?.let { getInt(it) } ?: -1
}
