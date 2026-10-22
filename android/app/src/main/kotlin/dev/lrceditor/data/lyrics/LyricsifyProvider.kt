package dev.lrceditor.data.lyrics

import dev.lrceditor.core.Lrc
import dev.lrceditor.core.Search
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * lyricsify.com has no API and uses bot protection, so this is a best-effort scrape of its search and lyric pages.
 * When it is blocked, the UI offers "Open in browser" ([searchUrl]) and "Paste from clipboard" instead.
 */
class LyricsifyProvider(private val timeoutMs: Int) : LyricsProvider {
    override val name = "Lyricsify"
    private val headers = mapOf("User-Agent" to Http.BROWSER_UA, "Accept-Language" to "en")

    override suspend fun search(q: LyricsQuery): List<LyricsResult> = withContext(Dispatchers.IO) {
        val r = Http.get(searchUrl(q), timeoutMs, headers)
        checkNotChallenge(r)
        Http.requireOk(r, name)
        val out = ArrayList<LyricsResult>()
        for ((path, score) in candidateLinks(r.body, q).take(3)) {
            val page = Http.get("https://www.lyricsify.com$path", timeoutMs, headers)
            checkNotChallenge(page)
            Http.requireOk(page, name)
            parseLyricsPage(page.body, path, score)?.let { out += it }
        }
        out
    }

    companion object {
        fun searchUrl(q: LyricsQuery) =
            "https://www.lyricsify.com/search?q=" + Http.enc(listOf(q.artist, q.title).filter { it.isNotBlank() }.joinToString(" "))

        private val LINK = Regex("""href="(/lyric/[^"#?]+)"""")
        private val LRC_LINE = Regex("""^\s*\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?].*""")
        private val CHALLENGE = listOf("just a moment", "cf-browser-verification", "challenge-platform", "captcha")

        fun checkNotChallenge(r: Http.Response) {
            val head = r.body.take(4000).lowercase()
            if (CHALLENGE.any { it in head }) throw BlockedException("Lyricsify asked for a browser check")
        }

        /** Lyric page links with how many of the wanted words their slug contains (0..1), best first. */
        fun candidateLinks(html: String, q: LyricsQuery): List<Pair<String, Double>> {
            val want = Search.normalize("${q.artist} ${q.title}").split(' ').filter { it.isNotEmpty() }
            if (want.isEmpty()) return emptyList()
            return LINK.findAll(html).map { it.groupValues[1] }.distinct().map { path ->
                val slug = Search.normalize(path.substringAfterLast('/').replace(Regex("""\.\d+$"""), "")).split(' ').toSet()
                path to want.count { it in slug }.toDouble() / want.size
            }.filter { it.second >= LyricsMatch.MIN_SCORE }.sortedByDescending { it.second }.toList()
        }

        private fun htmlToText(html: String): String = html
            .replace(Regex("""<(script|style)[\s\S]*?</\1>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""<br\s*/?>|</p>|</div>|</li>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&#x27;", "'").replace("&nbsp;", " ")

        /** Synced lyrics = the lines of the page that start with a timestamp. Fewer than 4 means there are none. */
        fun parseLyricsPage(html: String, path: String, score: Double): LyricsResult? {
            val lines = htmlToText(html).lines().filter { LRC_LINE.matches(it) }.map { it.trim() }
            if (lines.size < 4 || !Lrc.looksLikeLrc(lines.joinToString("\n"))) return null
            val title = path.substringAfterLast('/').replace(Regex("""\.\d+$"""), "").replace('-', ' ')
            return LyricsResult("Lyricsify", true, LyricsText.stripMetadata(lines.joinToString("\n")), title, score)
        }
    }
}
