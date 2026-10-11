package dev.lrceditor.data.lyrics

import dev.lrceditor.core.Lrc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.IOException

/** NetEase Cloud Music through its unofficial web API. May be rate limited or blocked outside China. */
class NeteaseProvider(private val timeoutMs: Int) : LyricsProvider {
    override val name = "NetEase"
    private val headers = mapOf("User-Agent" to Http.BROWSER_UA, "Referer" to "https://music.163.com/")

    private class Candidate(val id: Long, val title: String, val artist: String, val durationMs: Long?, val score: Double)

    override suspend fun search(q: LyricsQuery): List<LyricsResult> = withContext(Dispatchers.IO) {
        val term = listOf(q.title, q.artist).filter { it.isNotBlank() }.joinToString(" ")
        if (term.isBlank()) return@withContext emptyList()
        val r = Http.get("https://music.163.com/api/search/get?s=${Http.enc(term)}&type=1&limit=8&offset=0", timeoutMs, headers)
        Http.requireOk(r, name)
        val best = parseSearch(r.body, q).filter { it.score >= LyricsMatch.MIN_SCORE }.sortedByDescending { it.score }.take(2)
        val out = ArrayList<LyricsResult>()
        for (c in best) {
            val lr = Http.get("https://music.163.com/api/song/lyric?id=${c.id}&lv=1&tv=-1&rv=-1", timeoutMs, headers)
            Http.requireOk(lr, name)
            parseLyric(lr.body, "${c.title} — ${c.artist}", c.score)?.let { out += it }
        }
        out
    }

    companion object {
        private val CREDIT = Regex(
            """^\s*(作词|作詞|作曲|编曲|編曲|制作人|製作人|监制|監製|混音|母带|母帶|词|詞|曲|Lyrics|Composer|Arranger|Producer)\s*[:：]""",
            RegexOption.IGNORE_CASE,
        )
        private val TIME_TAGS = Regex("""\[[^\]]*]""")
        /** Credit lines only appear at the top, so only the first lines are checked. */
        private const val CREDIT_WINDOW = 12

        private fun JsonObject.obj(k: String) = this[k] as? JsonObject
        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

        private fun parseSearch(json: String, q: LyricsQuery): List<Candidate> {
            val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: throw IOException("unexpected answer from NetEase")
            val songs = root.obj("result")?.get("songs") as? JsonArray ?: return emptyList()
            return songs.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val id = (o["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
                val title = o.str("name")
                val artist = (o["artists"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("name")?.takeIf { n -> n.isNotBlank() } }
                    ?.joinToString(", ").orEmpty()
                val dur = (o["duration"] as? JsonPrimitive)?.longOrNull
                Candidate(id, title, artist, dur, LyricsMatch.score(q, title, artist, dur))
            }
        }

        /** Removes "作词 : ..." style credit lines from the top of the lyrics. */
        fun stripCredits(lrc: String): String =
            lrc.lines().filterIndexed { i, line -> !(i < CREDIT_WINDOW && CREDIT.containsMatchIn(TIME_TAGS.replace(line, ""))) }.joinToString("\n")

        /** Adds each romanized line as a continuation line of the entry with the same time. Null if nothing matched. */
        fun mergeRomanization(lrc: String, roma: String): String? {
            val byTime = Lrc.parse(roma).filter { it.timeMs != null && it.text.isNotBlank() }.associate { it.timeMs to it.text.lines().first() }
            var merged = 0
            val lines = Lrc.parse(lrc).map { l ->
                val r = l.timeMs?.let { byTime[it] }
                if (r != null && l.text.isNotBlank()) { merged++; l.copy(text = l.text + "\n" + r) } else l
            }
            return if (merged == 0) null else Lrc.serialize(lines)
        }

        fun parseLyric(json: String, info: String, score: Double): LyricsResult? {
            val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: throw IOException("unexpected answer from NetEase")
            val lyric = root.obj("lrc")?.str("lyric").orEmpty()
            val text = LyricsText.stripMetadata(stripCredits(lyric))
            if (text.isBlank() || "纯音乐" in text && text.lines().size < 4) return null
            val roma = root.obj("romalrc")?.str("lyric").orEmpty()
            val synced = Lrc.looksLikeLrc(text)
            val withRoma = if (synced && roma.isNotBlank()) mergeRomanization(text, LyricsText.stripMetadata(stripCredits(roma))) else null
            return LyricsResult("NetEase", synced, text, info, score, withRoma)
        }
    }
}
