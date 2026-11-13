package dev.glasslauncher.dream

import android.content.Context
import dev.glasslauncher.net.PublicHttp
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request

@Serializable class AsciiCategory(val id: String, val name: String, val blurb: String = "", val pieces: Int = 0, val zipBytes: Long = 0)

@Serializable
class AsciiEntry(
    val slug: String, val name: String, val category: String, val cols: Int, val rows: Int, val fps: Int,
    val frames: Int, val loop: String = "dissolve", val cell: String = "text", val bytes: Int, val sha256: String, val zip: String,
    val tags: List<String> = emptyList(),
    val note: String = "",
)

@Serializable class AsciiIndex(val v: Int, val categories: List<AsciiCategory>, val pieces: List<AsciiEntry>)

/**
 * The baked ASCII pieces (P327): not in the APK. The index is fetched once; a piece's category pack (one zip, a few MB) is fetched the first
 * time one of its pieces is picked, then every piece of it is on disk. Everything fetched is checked: https to a public name only, size
 * caps, file names, and each piece's SHA-256 against the index. The packs are built by scripts/ascii-bake.mjs from ascii-magic-art (MIT).
 */
object AsciiPacks {
    const val BASE = "https://github.com/AazainKhan/vitre/releases/download/ascii-packs-1"
    private const val MAX_INDEX = 512 * 1024
    private const val MAX_ZIP = 16 * 1024 * 1024
    private const val MAX_PIECE = 2 * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true }
    private val name = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
    // Category packs are a few MB (the biggest about 8): more time than the art fetches get.
    private val http by lazy {
        PublicHttp.client().newBuilder().readTimeout(20, java.util.concurrent.TimeUnit.SECONDS).callTimeout(90, java.util.concurrent.TimeUnit.SECONDS).build()
    }

    private fun dir(context: Context) = File(context.filesDir, "ascii").also { it.mkdirs() }

    private fun get(url: String, max: Int): ByteArray? = runCatching {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful || r.body.contentLength() > max) null else PublicHttp.readCapped(r.body.source(), max)
        }
    }.getOrNull()

    /** The catalogue: the cached copy, or fetched once. [refresh] fetches it again. Null when there is none and the network is down. */
    suspend fun index(context: Context, refresh: Boolean = false): AsciiIndex? = withContext(Dispatchers.IO) {
        val file = File(dir(context), "index.json")
        if (refresh || !file.exists()) get("$BASE/vitre-ascii-index.json", MAX_INDEX)?.let { runCatching { parse(it) }.getOrNull()?.let { _ -> file.writeBytes(it) } }
        runCatching { parse(file.readBytes()) }.getOrNull()
    }

    /** The cached catalogue, read at once (a small local file), for a screen whose first row must exist when it opens. Null before the first fetch. */
    fun cachedIndex(context: Context): AsciiIndex? = runCatching { parse(File(dir(context), "index.json").readBytes()) }.getOrNull()

    private fun parse(bytes: ByteArray) = json.decodeFromString(AsciiIndex.serializer(), String(bytes, Charsets.UTF_8)).also { require(it.v == 1) }

    /** Whether [slug]'s pack is already on disk (no network needed to play it). */
    fun cached(context: Context, slug: String) = name.matches(slug) && File(dir(context), "$slug.asc").exists()

    /** The piece [slug], fetching its category pack the first time. Null on any failure. */
    suspend fun load(context: Context, slug: String): AsciiPiece? = withContext(Dispatchers.IO) {
        if (!name.matches(slug)) return@withContext null
        val file = File(dir(context), "$slug.asc")
        fun cached() = runCatching { AsciiPiece.parse(file.readBytes()) }.getOrNull()
        cached()?.let { return@withContext it }
        // Not on disk, or on disk in a format this build can't play (an older pack): fetch the current catalogue and category once.
        if (file.exists()) { file.delete(); index(context, refresh = true) }
        val entry = index(context)?.pieces?.firstOrNull { it.slug == slug } ?: return@withContext null
        fetchCategory(context, entry.zip)
        cached()
    }

    private fun fetchCategory(context: Context, zip: String) {
        if (!name.matches(zip.removeSuffix(".zip"))) return
        val index = runCatching { parse(File(dir(context), "index.json").readBytes()) }.getOrNull() ?: return
        val want = index.pieces.filter { it.zip == zip }.associateBy { it.slug }
        val bytes = get("$BASE/$zip", MAX_ZIP) ?: return
        ZipInputStream(bytes.inputStream()).use { zin ->
            var n = 0
            while (n++ < 2000) {
                val e = zin.nextEntry ?: break
                val slug = e.name.removeSuffix(".asc")
                val entry = want[slug]
                if (e.isDirectory || !e.name.endsWith(".asc") || entry == null) continue
                val data = readEntry(zin) ?: continue
                if (sha256(data) != entry.sha256) continue
                // Written whole under a temporary name, then renamed: a half-written piece is never seen.
                val tmp = File(dir(context), "$slug.asc.tmp")
                tmp.writeBytes(data)
                tmp.renameTo(File(dir(context), "$slug.asc"))
            }
        }
    }

    private fun readEntry(zin: ZipInputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16 * 1024)
        while (true) {
            val r = zin.read(buf)
            if (r < 0) return out.toByteArray()
            out.write(buf, 0, r)
            if (out.size() > MAX_PIECE) return null
        }
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
