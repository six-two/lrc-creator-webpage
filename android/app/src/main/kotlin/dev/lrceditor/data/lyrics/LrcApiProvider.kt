package dev.lrceditor.data.lyrics

import dev.lrceditor.core.Lrc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** https://api.lrc.cx (LrcApi): one request that looks through several sources and answers with LRC text. */
class LrcApiProvider(private val timeoutMs: Int) : LyricsProvider {
    override val name = "LrcApi"
    private val headers = mapOf("User-Agent" to "LrcEditor Android")

    override suspend fun search(q: LyricsQuery): List<LyricsResult> = withContext(Dispatchers.IO) {
        if (q.title.isBlank()) return@withContext emptyList()
        val params = buildString {
            append("title=${Http.enc(q.title)}")
            if (q.artist.isNotBlank()) append("&artist=${Http.enc(q.artist)}")
            if (q.album.isNotBlank()) append("&album=${Http.enc(q.album)}")
        }
        val r = Http.get("https://api.lrc.cx/lyrics?$params", timeoutMs, headers)
        if (r.code == 404) return@withContext emptyList()
        Http.requireOk(r, name)
        // The answer carries no title or artist to compare with: it is the service's best match for what was asked.
        listOfNotNull(parse(r.body, "${q.title} — ${q.artist}".trimEnd(' ', '—')))
    }

    companion object {
        /** Section labels like [Verse] or [Chorus], which are not lyrics. */
        private val SECTION = Regex("""^\s*\[[A-Za-z][A-Za-z0-9 \-]*]\s*$""")

        fun parse(body: String, info: String): LyricsResult? {
            val cleaned = body.lines().filterNot { SECTION.matches(it) }.joinToString("\n")
            val text = LyricsText.stripMetadata(LyricsText.stripHeader(cleaned))
            if (text.isBlank() || text.startsWith("<") || text.startsWith("{")) return null
            val synced = Lrc.looksLikeLrc(text)
            return LyricsResult("LrcApi", synced, text, info, MATCH_SCORE)
        }

        /** The service picks the best match itself; it is trusted, but ranks below candidates that were checked against the song. */
        const val MATCH_SCORE = 0.75
    }
}
