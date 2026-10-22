package dev.lrceditor.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.IOException

/** QQ Music through its unofficial web API: a big synced catalog, western music too. May be blocked outside China at times. */
class QqMusicProvider(private val timeoutMs: Int) : LyricsProvider {
    override val name = "QQ Music"
    private val headers = mapOf("User-Agent" to Http.BROWSER_UA, "Referer" to "https://y.qq.com/")

    class Candidate(val mid: String, val title: String, val artist: String, val durationMs: Long?, val score: Double)

    override suspend fun search(q: LyricsQuery): List<LyricsResult> = withContext(Dispatchers.IO) {
        val term = listOf(q.artist, q.title).filter { it.isNotBlank() }.joinToString(" ")
        if (term.isBlank()) return@withContext emptyList()
        val r = Http.get("https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=${Http.enc(term)}&format=json&n=8&p=1&t=0", timeoutMs, headers)
        Http.requireOk(r, name)
        val best = parseSearch(r.body, q).filter { it.score >= LyricsMatch.LIST_SCORE }.sortedByDescending { it.score }.take(5)
        val out = ArrayList<LyricsResult>()
        for (c in best) {
            val lr = Http.get("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=${c.mid}&format=json&nobase64=1&g_tk=5381", timeoutMs, headers)
            Http.requireOk(lr, name)
            parseLyric(lr.body, "${c.title} — ${c.artist}", c.score)?.let { out += it }
        }
        out
    }

    companion object {
        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

        fun parseSearch(json: String, q: LyricsQuery): List<Candidate> {
            val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: throw IOException("unexpected answer from QQ Music")
            val list = ((root["data"] as? JsonObject)?.get("song") as? JsonObject)?.get("list") as? JsonArray ?: return emptyList()
            return list.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val mid = o.str("songmid").ifBlank { return@mapNotNull null }
                val title = o.str("songname")
                val artist = (o["singer"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("name")?.takeIf { n -> n.isNotBlank() } }
                    ?.joinToString(", ").orEmpty()
                val dur = (o["interval"] as? JsonPrimitive)?.longOrNull?.let { it * 1000 }
                Candidate(mid, title, artist, dur, LyricsMatch.score(q, title, artist, dur))
            }
        }

        fun parseLyric(json: String, info: String, score: Double): LyricsResult? {
            val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: throw IOException("unexpected answer from QQ Music")
            val text = LyricsText.stripMetadata(LyricsText.stripHeader(root.str("lyric")))
            if (text.isBlank() || !dev.lrceditor.core.Lrc.looksLikeLrc(text)) return null
            return LyricsResult("QQ Music", true, text, info, score)
        }
    }
}
