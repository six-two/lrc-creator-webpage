package dev.lrceditor.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** https://lyrics.ovh: plain text lyrics only, found by artist and title (falls back to its search when the exact names don't match). */
class LyricsOvhProvider(private val timeoutMs: Int) : LyricsProvider {
    override val name = "lyrics.ovh"
    private val headers = mapOf("User-Agent" to "LrcEditor Android")

    class Candidate(val artist: String, val title: String, val score: Double)

    override suspend fun search(q: LyricsQuery): List<LyricsResult> = withContext(Dispatchers.IO) {
        if (q.title.isBlank()) return@withContext emptyList()
        val out = ArrayList<LyricsResult>()
        if (q.artist.isNotBlank()) fetchOne(q.artist, q.title, "${q.title} — ${q.artist}", 0.9)?.let { out += it }
        if (out.isEmpty()) {
            val r = Http.get("https://api.lyrics.ovh/suggest/${Http.enc(listOf(q.title, q.artist).filter { it.isNotBlank() }.joinToString(" "))}", timeoutMs, headers)
            Http.requireOk(r, name)
            for (c in parseSuggest(r.body, q).filter { it.score >= LyricsMatch.LIST_SCORE }.sortedByDescending { it.score }.take(3)) {
                fetchOne(c.artist, c.title, "${c.title} — ${c.artist}", c.score)?.let { out += it }
            }
        }
        out
    }

    private fun fetchOne(artist: String, title: String, info: String, score: Double): LyricsResult? {
        val r = Http.get("https://api.lyrics.ovh/v1/${Http.enc(artist).replace("+", "%20")}/${Http.enc(title).replace("+", "%20")}", timeoutMs, headers)
        if (r.code == 404) return null
        Http.requireOk(r, name)
        return parseLyrics(r.body, info, score)
    }

    companion object {
        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

        fun parseLyrics(json: String, info: String, score: Double): LyricsResult? {
            val obj = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
            val text = obj.str("lyrics").replace("\r\n", "\n").trim()
            return text.takeIf { it.isNotBlank() }?.let { LyricsResult("lyrics.ovh", false, it, info, score) }
        }

        fun parseSuggest(json: String, q: LyricsQuery): List<Candidate> {
            val arr = (runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()?.get("data") as? JsonArray) ?: return emptyList()
            return arr.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val title = o.str("title_short").ifBlank { o.str("title") }
                val artist = (o["artist"] as? JsonObject)?.str("name").orEmpty()
                val dur = (o["duration"] as? JsonPrimitive)?.longOrNull?.let { it * 1000 }
                if (title.isBlank() || artist.isBlank()) null else Candidate(artist, title, LyricsMatch.score(q, title, artist, dur))
            }
        }
    }
}
