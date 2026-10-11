package dev.lrceditor.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.IOException

/** https://lrclib.net: open lyrics database with synced (LRC) and plain lyrics, no key needed. */
class LrclibProvider(private val timeoutMs: Int) : LyricsProvider {
    override val name = NAME
    private val base = "https://lrclib.net/api"
    private val headers = mapOf("User-Agent" to "LrcEditor Android (https://github.com/)")

    override suspend fun search(q: LyricsQuery): List<LyricsResult> = withContext(Dispatchers.IO) {
        val out = ArrayList<LyricsResult>()
        if (q.title.isNotBlank() && q.artist.isNotBlank()) {
            val params = buildString {
                append("track_name=${Http.enc(q.title)}&artist_name=${Http.enc(q.artist)}")
                if (q.album.isNotBlank()) append("&album_name=${Http.enc(q.album)}")
                q.durationMs?.takeIf { it > 0 }?.let { append("&duration=${(it + 500) / 1000}") }
            }
            val r = Http.get("$base/get?$params", timeoutMs, headers)
            if (r.code != 404) {
                Http.requireOk(r, name)
                parseTrack(r.body, q)?.let { out += it }
            }
        }
        val query = if (q.artist.isNotBlank()) "track_name=${Http.enc(q.title)}&artist_name=${Http.enc(q.artist)}" else "q=${Http.enc(q.title)}"
        val r = Http.get("$base/search?$query", timeoutMs, headers)
        Http.requireOk(r, name)
        out += parseList(r.body, q)
        out.distinctBy { it.text }
    }

    companion object {
        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

        /** One track object from /get or an element of /search. Prefers the synced lyrics, else the plain ones. */
        fun parseTrack(json: String, q: LyricsQuery): LyricsResult? {
            val obj = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
            return fromObject(obj, q)
        }

        fun parseList(json: String, q: LyricsQuery): List<LyricsResult> {
            val arr = runCatching { Json.parseToJsonElement(json) as? JsonArray }.getOrNull() ?: throw IOException("unexpected answer from LRCLIB")
            return arr.mapNotNull { (it as? JsonObject)?.let { o -> fromObject(o, q) } }
        }

        private fun fromObject(o: JsonObject, q: LyricsQuery): LyricsResult? {
            val title = o.str("trackName").ifBlank { o.str("name") }
            val artist = o.str("artistName")
            val durationMs = (o["duration"] as? JsonPrimitive)?.doubleOrNull?.let { (it * 1000).toLong() }
            val synced = LyricsText.stripMetadata(o.str("syncedLyrics"))
            val plain = o.str("plainLyrics").trim()
            val info = "$title — $artist" + (durationMs?.let { " (${it / 60000}:%02d)".format(it / 1000 % 60) } ?: "")
            val score = LyricsMatch.score(q, title, artist, durationMs)
            return when {
                synced.isNotBlank() -> LyricsResult(NAME, true, synced, info, score)
                plain.isNotBlank() -> LyricsResult(NAME, false, plain, info, score)
                else -> null
            }
        }

        const val NAME = "LRCLIB"
    }
}
