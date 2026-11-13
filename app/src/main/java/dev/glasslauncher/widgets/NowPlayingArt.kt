package dev.glasslauncher.widgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.core.FastOutSlowInEasing

/**
 * Where Now Playing's cover comes from. Sessions publish it three ways (and Spotify's TV app, like most, often only
 * as a URI, with the app's own LOGO as DISPLAY_ICON): picking that logo as "the art" is what left a green logo
 * (and a green backdrop baked from it) up for seconds, or for good. Order: an art URI, then a real art bitmap, then
 * the icon (bitmap, then URI). The URI comes first because an app may leave the previous track's bitmap in the metadata
 * (Spotify TV did: the cover stayed on the first track, P324); the bitmap or icon is only the fallback if the fetch fails.
 */
object ArtPick {
    class Pick<B : Any>(val bitmap: B?, val uri: String?, val fallback: B?, val isIcon: Boolean, val fallbackIsIcon: Boolean = true) {
        val none get() = bitmap == null && uri == null
    }

    fun <B : Any> pick(art: B?, albumArt: B?, icon: B?, artUri: String?, albumArtUri: String?, iconUri: String?): Pick<B> {
        val bitmap = art ?: albumArt
        (safeUri(artUri) ?: safeUri(albumArtUri))?.let { return Pick(null, it, bitmap ?: icon, false, fallbackIsIcon = bitmap == null) }
        bitmap?.let { return Pick(it, null, null, false) }
        icon?.let { return Pick(it, null, null, true) }
        safeUri(iconUri)?.let { return Pick(null, it, null, true) }
        return Pick(null, null, null, false)
    }

    /** Only https and content URIs, of a sane length: a session is untrusted, so no file:, http:, intent: or a megabyte of text. */
    fun safeUri(raw: String?): String? {
        val s = raw?.trim() ?: return null
        if (s.isEmpty() || s.length > 2048 || s.any { Character.isISOControl(it) || it == ' ' }) return null
        val scheme = s.substringBefore(':', "").lowercase(java.util.Locale.ROOT)
        return s.takeIf { scheme == "https" || scheme == "content" }
    }
}

/** A few covers kept by key, bounded by count and by bytes (the pool rule: nothing here may grow with listening time). */
class ArtCache<V : Any>(private val maxEntries: Int = 4, private val maxBytes: Int = 6 * 1024 * 1024, private val sizeOf: (V) -> Int) {
    private val map = LinkedHashMap<String, V>(8, 0.75f, true)
    private var bytes = 0

    @Synchronized fun get(key: String): V? = map[key]

    @Synchronized fun put(key: String, value: V) {
        val size = sizeOf(value)
        if (size > maxBytes) return
        map.remove(key)?.let { bytes -= sizeOf(it) }
        map[key] = value
        bytes += size
        val it = map.entries.iterator()
        while ((map.size > maxEntries || bytes > maxBytes) && it.hasNext() && map.size > 1) {
            val e = it.next()
            if (e.key == key) continue
            bytes -= sizeOf(e.value); it.remove()
        }
    }

    @Synchronized fun size() = map.size
    @Synchronized fun totalBytes() = bytes
}

/**
 * The cover changing in the Now Playing art slot: the old picture stays fully opaque underneath while the new one
 * fades in over it ([durationMs], ease), so no frame is ever see-through (a plain Crossfade dips to 75%) and nothing
 * snaps. Pure: time is passed in.
 */
class ArtBlend<T : Any>(val durationMs: Long = 230, initial: T? = null) {
    var shown: T? = initial; private set
    var incoming: T? = null; private set
    var fading = false; private set
    private var start = 0L

    /** A new art (null = back to the placeholder). Returns true if a blend began. */
    fun retarget(art: T?, now: Long): Boolean {
        if (art == (if (fading) incoming else shown)) return false
        if (fading) shown = incoming // mid-blend: what is mostly visible becomes the base
        incoming = art; fading = true; start = now
        return true
    }

    /** Opacity of [incoming] over [shown]; [shown] itself is always drawn at 1. */
    fun alpha(now: Long): Float {
        if (!fading) return 1f
        val t = ((now - start).toFloat() / durationMs).coerceIn(0f, 1f)
        return FastOutSlowInEasing.transform(t)
    }

    /** Ends the blend once it is complete; true when it did. */
    fun settle(now: Long): Boolean {
        if (!fading || now - start < durationMs) return false
        shown = incoming; incoming = null; fading = false
        return true
    }
}

/** Fetching a cover URI: off the main thread, capped in bytes, decoded at the size it is shown (never the full image). */
object ArtFetcher {
    const val MAX_SIDE = 512
    const val MAX_BYTES = 4_000_000

    /** The power-of-two decode sample that keeps the longer side at or above [max] (never smaller than shown). */
    fun sampleSize(w: Int, h: Int, max: Int = MAX_SIDE): Int {
        var s = 1
        while (maxOf(w, h) / (s * 2) >= max) s *= 2
        return s
    }

    private val http by lazy { dev.glasslauncher.net.PublicHttp.client() }

    /**
     * A content URI is read only from the session's own app: Glass reads with its own (privileged) permissions, so a session
     * must not be able to point it at another app's provider. [ownerOf] maps an authority to the package that declares it.
     */
    fun contentAllowed(uri: String, sessionPackage: String, ownerOf: (String) -> String?): Boolean {
        if (!uri.startsWith("content://", ignoreCase = true)) return false
        val authority = runCatching { java.net.URI(uri).rawAuthority }.getOrNull()?.substringAfterLast('@')?.takeIf { it.isNotEmpty() } ?: return false
        return ownerOf(authority) == sessionPackage
    }

    /** Whether [uri] may be fetched for a session of [sessionPackage]: https to a public name (re-checked on every hop by the client), or its own content. */
    fun allowed(uri: String, sessionPackage: String, ownerOf: (String) -> String?): Boolean = when {
        ArtPick.safeUri(uri) == null -> false
        uri.startsWith("content", ignoreCase = true) -> contentAllowed(uri, sessionPackage, ownerOf)
        else -> !dev.glasslauncher.net.PublicHosts.blockedName(runCatching { java.net.URI(uri).host }.getOrNull() ?: "")
    }

    /** Blocking. Null on any failure (offline, 404, too big, not an image, refused host or provider). */
    fun fetch(context: Context, uri: String, sessionPackage: String): Bitmap? = runCatching {
        val pm = context.packageManager
        if (!allowed(uri, sessionPackage) { authority -> pm.resolveContentProvider(authority, 0)?.packageName }) return null
        val bytes = read(context, uri) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight); inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val k = MAX_SIDE / maxOf(decoded.width, decoded.height).toFloat()
        if (k >= 1f) decoded
        else Bitmap.createScaledBitmap(decoded, (decoded.width * k).toInt().coerceAtLeast(1), (decoded.height * k).toInt().coerceAtLeast(1), true).also { if (it !== decoded) decoded.recycle() }
    }.getOrNull()

    private fun read(context: Context, uri: String): ByteArray? {
        if (uri.startsWith("content", ignoreCase = true)) {
            return context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readCapped() }
        }
        return http.newCall(okhttp3.Request.Builder().url(uri).header("Accept", "image/*").build()).execute().use { r ->
            if (!r.isSuccessful || r.body.contentLength() > MAX_BYTES) null else dev.glasslauncher.net.PublicHttp.readCapped(r.body.source(), MAX_BYTES)
        }
    }

    private fun java.io.InputStream.readCapped(): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BYTES) return null
        }
        return out.toByteArray()
    }
}

/** Covers fetched by URI, process-wide: a repeat track (or Home reopening) is a hit, and one URI is fetched once however many collectors ask. */
internal object ArtStore {
    val cache = ArtCache<Bitmap> { it.byteCount }
    private val flights = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Bitmap?>>()

    /** The shared fetch for [uri] and whether the caller is the one that must run it. */
    fun begin(uri: String): Pair<kotlinx.coroutines.CompletableDeferred<Bitmap?>, Boolean> {
        val mine = kotlinx.coroutines.CompletableDeferred<Bitmap?>()
        val existing = flights.putIfAbsent(uri, mine)
        return (existing ?: mine) to (existing == null)
    }

    fun finish(uri: String, flight: kotlinx.coroutines.CompletableDeferred<Bitmap?>, bitmap: Bitmap?) {
        if (bitmap != null) cache.put(uri, bitmap)
        flights.remove(uri, flight)
        flight.complete(bitmap)
    }
}
