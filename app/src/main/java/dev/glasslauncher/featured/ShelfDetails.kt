package dev.glasslauncher.featured

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient

/** One name in the cast or crew, with the character and a photo when the source has them. */
data class Person(val name: String, val role: String? = null, val photo: String? = null)

/** One episode of a season, from Cinemeta's `videos`. */
data class Episode(val number: Int, val title: String, val overview: String? = null, val thumbnail: String? = null, val minutes: Int? = null)

/** A season and its episodes in order. */
data class Season(val number: Int, val episodes: List<Episode>)

/**
 * What the Info sheet shows that the shelf itself doesn't (P110): cast with photos, director, writers, country,
 * seasons and awards. The title, synopsis, rating, year, runtime and genre are on the shelf already, so they are not
 * repeated here. From Cinemeta's meta (no API key, so it works in APK mode); parsing is pure.
 */
data class ShelfDetails(
    val cast: List<Person> = emptyList(),
    val directors: List<String> = emptyList(),
    val writers: List<String> = emptyList(),
    val country: String? = null,
    val seasons: Int? = null,
    val awards: String? = null,
    /** The one metadata model beyond the row (P156): type word, genres, runtime, the season list, from the same Cinemeta meta. */
    val type: String? = null,
    val genres: List<String> = emptyList(),
    val runtimeMin: Int? = null,
    val seasonList: List<Season> = emptyList(),
    val year: Int? = null,
) {
    fun releaseYear(): Int? = year
    val isEmpty: Boolean get() = cast.isEmpty() && directors.isEmpty() && writers.isEmpty() && country == null && seasons == null && awards == null &&
        type == null && genres.isEmpty() && runtimeMin == null && seasonList.isEmpty()

    companion object {
        /** At most this many faces fit the sheet's row. */
        const val MAX_CAST = 6

        private fun JsonObject.strings(key: String): List<String> =
            (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf { s -> s.isNotBlank() } }.orEmpty()

        private fun JsonObject.str(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

        private fun people(arr: JsonArray?): List<Person> = arr.orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull (e as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.let { Person(it.content) }
            val name = o.str("name") ?: return@mapNotNull null
            Person(name, role = o.str("character"), photo = o.str("photo"))
        }

        fun parse(meta: JsonObject): ShelfDetails {
            val extras = meta["app_extras"] as? JsonObject
            // Cinemeta's app_extras carry faces and roles; the plain arrays are names only.
            val cast = people(extras?.get("cast") as? JsonArray).ifEmpty { meta.strings("cast").map { Person(it) } }.take(MAX_CAST)
            val directors = people(extras?.get("directors") as? JsonArray).map { it.name }.ifEmpty { meta.strings("director") }
            val writers = people(extras?.get("writers") as? JsonArray).map { it.name }.ifEmpty { meta.strings("writer") }
            val seasons = (meta["videos"] as? JsonArray)?.mapNotNull { v ->
                ((v as? JsonObject)?.get("season") as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it > 0 }
            }?.distinct()?.size?.takeIf { it > 0 }
            val seasonList = seasonList(meta)
            return ShelfDetails(
                cast = cast, directors = directors.take(3), writers = writers.take(3),
                country = meta.str("country"), seasons = seasons, awards = meta.str("awards"),
                type = typeWord(meta.str("type")), genres = meta.strings("genres").ifEmpty { meta.strings("genre") }.take(3),
                runtimeMin = runtimeMinutes(meta.str("runtime")), seasonList = seasonList,
                year = Regex("\\d{4}").find(meta.str("releaseInfo") ?: meta.str("year").orEmpty())?.value?.toIntOrNull(),
            )
        }

        /** tvOS's word for the kind of title: "Movie", "TV Show"; null for anything else. */
        fun typeWord(type: String?): String? = when (type) { "movie" -> "Movie"; "series" -> "TV Show"; else -> null }

        /** Cinemeta's runtime text ("148 min", "2h 28min", "1h") in minutes; null when it has no number. */
        fun runtimeMinutes(text: String?): Int? {
            if (text.isNullOrBlank()) return null
            val h = Regex("(\\d+)\\s*h").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val m = Regex("(\\d+)\\s*m").find(text)?.groupValues?.get(1)?.toIntOrNull()
                ?: if (h == 0) Regex("(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() else 0
            return ((h * 60) + (m ?: 0)).takeIf { it > 0 }
        }

        /** Cinemeta's `videos` grouped into seasons (season 0, "specials", is left out), episodes in order. */
        fun seasonList(meta: JsonObject): List<Season> {
            val videos = (meta["videos"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return emptyList()
            return videos.mapNotNull { v ->
                val season = (v["season"] as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
                val number = ((v["episode"] ?: v["number"]) as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
                val title = v.str("title") ?: v.str("name") ?: "Episode $number"
                season to Episode(number, title, v.str("overview") ?: v.str("description"), v.str("thumbnail"), runtimeMinutes(v.str("runtime")))
            }.groupBy({ it.first }, { it.second }).toSortedMap().map { (n, eps) -> Season(n, eps.sortedBy { it.number }) }
        }

        /**
         * The details of [item] from Cinemeta (cached by IMDb id), or null when it has no IMDb link or none arrive. The
         * public Cinemeta meta has the cast as names only (no app_extras, checked), so the faces come from Wikipedia's
         * page summaries (keyless), looked up in parallel and remembered; a person without one keeps their initials.
         */
        /** Everything but the cast photos (no Wikipedia calls): enough for the hero's info line and the title's kind. Cached by Cinemeta's own cache. */
        suspend fun loadMeta(http: OkHttpClient, item: FeaturedItem): ShelfDetails? = withContext(Dispatchers.IO) {
            val meta = cinemetaForInfo(item, http) ?: return@withContext null
            runCatching { parse(meta) }.getOrNull()?.takeIf { !it.isEmpty }
        }

        suspend fun load(http: OkHttpClient, item: FeaturedItem): ShelfDetails? = withContext(Dispatchers.IO) {
            val meta = cinemetaForInfo(item, http) ?: return@withContext null
            val base = runCatching { parse(meta) }.getOrNull()?.takeIf { !it.isEmpty } ?: return@withContext null
            val cast = kotlinx.coroutines.coroutineScope {
                base.cast.map { p -> async { if (p.photo != null) p else p.copy(photo = PersonPhotos.lookup(http, p.name)) } }.map { it.await() }
            }
            base.copy(cast = cast)
        }
    }
}

/**
 * A person's photo from Wikipedia's page summary (REST, no key): the thumbnail of the page named after them, accepted only
 * when its short description says they work in film, TV or music, so a namesake page gives no wrong face. Pure parsing;
 * the fetch is one small JSON GET, remembered in memory (a hit or a miss) for the process.
 */
internal object PersonPhotos {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private const val NONE = ""
    /** Wikimedia refuses generic agents (okhttp's default gets 403), for the summary and the thumbnail alike. */
    const val USER_AGENT = "VITRE/1.0 (https://github.com/AazainKhan/vitre)"
    private val CALLING = Regex("actor|actress|director|filmmaker|comedian|screenwriter|producer|singer|musician|presenter|voice", RegexOption.IGNORE_CASE)

    /** The thumbnail URL in a summary JSON, or null when the page isn't a person of that calling or has no picture. */
    fun photoFrom(summary: JsonObject): String? {
        val description = (summary["description"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val type = (summary["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (type != null && type != "standard") return null
        if (!CALLING.containsMatchIn(description)) return null
        return ((summary["thumbnail"] as? JsonObject)?.get("source") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.startsWith("https://") }
    }

    /** A result worth remembering: the page exists (200) or doesn't (404). A network error or a 5xx is tried again next view. */
    fun cacheable(httpCode: Int?): Boolean = httpCode == 200 || httpCode == 404

    fun lookup(http: OkHttpClient, name: String): String? {
        cache[name]?.let { return it.takeIf { u -> u != NONE } }
        val title = java.net.URLEncoder.encode(name.trim().replace(' ', '_'), "UTF-8").replace("+", "%20")
        var code: Int? = null
        val url = runCatching {
            val req = okhttp3.Request.Builder()
                .url("https://en.wikipedia.org/api/rest_v1/page/summary/$title")
                // Wikimedia asks for a descriptive agent.
                .header("User-Agent", USER_AGENT)
                .build()
            http.newCall(req).execute().use { r ->
                code = r.code
                if (!r.isSuccessful) null
                else photoFrom(kotlinx.serialization.json.Json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject)
            }
        }.getOrNull()
        // Only an answer is remembered; a failure to reach Wikipedia is not a "no photo".
        if (cacheable(code)) cache[name] = url ?: NONE
        return url
    }
}
