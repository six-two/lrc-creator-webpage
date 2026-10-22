package dev.lrceditor.data.lyrics

import dev.lrceditor.core.Lrc
import dev.lrceditor.core.Search
import dev.lrceditor.data.LyricsSource
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** What we know about the song we want lyrics for. */
data class LyricsQuery(val title: String, val artist: String, val album: String, val durationMs: Long?)

/**
 * One lyrics candidate. [synced] = LRC text with timestamps, otherwise plain lines.
 * [withRomanization] is the same lyrics with a romanized second line per entry, if the source has one.
 */
data class LyricsResult(
    val source: String,
    val synced: Boolean,
    val text: String,
    val matchInfo: String,
    val score: Double,
    val withRomanization: String? = null,
)

interface LyricsProvider {
    val name: String
    /** Candidates for [q], best first. Throws [IOException] when the source can't be reached or blocks us. */
    suspend fun search(q: LyricsQuery): List<LyricsResult>
}

/** The source answered with a block page, captcha or rate limit instead of data. */
class BlockedException(message: String) : IOException(message)

/** Scores how well a candidate (title / artist / length) matches what we are looking for, 0..1. */
object LyricsMatch {
    /** Candidates scoring at least this are offered in the lyrics search to choose from. */
    const val LIST_SCORE = 0.45
    /** The search picks a result by itself only if it scores at least this. */
    const val MIN_SCORE = 0.6

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    /** Similarity of two names after normalising; containment (e.g. "A feat. B" vs "A") counts as a strong match. */
    fun similarity(a: String, b: String): Double {
        val x = Search.normalize(a)
        val y = Search.normalize(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        if (minOf(x.length, y.length) >= 3 && (x.contains(y) || y.contains(x))) return 0.85
        return 1.0 - levenshtein(x, y).toDouble() / maxOf(x.length, y.length)
    }

    private fun durationScore(wantMs: Long?, gotMs: Long?): Double {
        if (wantMs == null || gotMs == null || wantMs <= 0 || gotMs <= 0) return 0.5
        val d = kotlin.math.abs(wantMs - gotMs)
        return when {
            d <= 2_000 -> 1.0
            d <= 5_000 -> 0.7
            d <= 10_000 -> 0.3
            else -> 0.0
        }
    }

    fun score(q: LyricsQuery, title: String, artist: String, durationMs: Long?): Double {
        val t = similarity(q.title, title)
        val a = if (q.artist.isBlank() || artist.isBlank()) 0.5 else similarity(q.artist, artist)
        return 0.5 * t + 0.3 * a + 0.2 * durationScore(q.durationMs, durationMs)
    }
}

/** Other ways to ask for the same song, for when the exact tags find nothing (extra info in the title, several artists, ...). */
object LyricsQueries {
    private val BRACKETS = Regex("""\s*[(\[{][^)\]}]*[)\]}]""")
    private val FEAT = Regex("""\s+(feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)
    private val VERSION_SUFFIX = Regex(
        """\s+[-–—]\s+(remaster(ed)?|live|radio|single|album|mono|stereo|bonus|acoustic|demo|remix|edit|version|extended|deluxe|\d{4}).*$""",
        RegexOption.IGNORE_CASE,
    )
    private val ARTIST_SPLIT = Regex("""\s*(?:;|,|\s&\s|\s+feat\.?\s|\s+ft\.?\s|\s+featuring\s|\s+x\s|\s+with\s)\s*""", RegexOption.IGNORE_CASE)

    /** The title without "(feat. X)", "[Live]", "- Remastered 2011" and the like; the original if nothing would be left. */
    fun cleanTitle(title: String): String {
        val t = FEAT.replace(VERSION_SUFFIX.replace(BRACKETS.replace(title, ""), ""), "").trim()
        return t.ifBlank { title.trim() }
    }

    /** The first of several artists ("A feat. B", "A; B", "A, B", "A & B"). */
    fun firstArtist(artist: String): String = artist.split(ARTIST_SPLIT).firstOrNull { it.isNotBlank() }?.trim() ?: artist.trim()

    /** [q] first, then the cleaned-up forms that differ from it. A file name like "Artist - Title" (no artist tag) is split up as well. */
    fun variants(q: LyricsQuery): List<LyricsQuery> {
        val out = ArrayList<LyricsQuery>()
        out += q
        out += q.copy(title = cleanTitle(q.title), artist = firstArtist(q.artist), album = "")
        if (q.artist.isBlank() && " - " in q.title) {
            val artist = q.title.substringBefore(" - ").trim()
            val title = q.title.substringAfter(" - ").trim()
            if (artist.isNotEmpty() && title.isNotEmpty()) {
                out += q.copy(title = title, artist = artist)
                out += q.copy(title = cleanTitle(title), artist = firstArtist(artist), album = "")
            }
        }
        return out.distinctBy { Search.normalize(it.title) to Search.normalize(it.artist) }
    }
}

object LyricsFetcher {
    /** At most this many candidates per source are offered. */
    const val MAX_PER_SOURCE = 10

    /**
     * Everything [p] finds for [q]: synced candidates first, then by score. If the exact tags give no synced candidate, the
     * cleaned-up forms of the query ([LyricsQueries.variants]) are tried too. Throws if the source can't be asked at all.
     */
    suspend fun searchSource(p: LyricsProvider, q: LyricsQuery): List<LyricsResult> {
        val all = ArrayList<LyricsResult>()
        for ((i, variant) in LyricsQueries.variants(q).withIndex()) {
            val found = try {
                p.search(variant)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (i == 0) throw e
                break // the first answer is kept; a later variant failing only means no more candidates
            }
            all += found.filter { it.score >= LyricsMatch.LIST_SCORE && it.text.isNotBlank() }
            if (all.any { it.synced && it.score >= LyricsMatch.MIN_SCORE }) break
        }
        return all.distinctBy { it.text }
            .sortedWith(compareByDescending<LyricsResult> { it.synced && it.score >= LyricsMatch.MIN_SCORE }.thenByDescending { it.score })
            .take(MAX_PER_SOURCE)
    }

    /**
     * The candidate to show first, from the results of each source in the user's order: the best synced one of the first source
     * that has one, else the best plain one of all. (source index, index in that source's list); null if nothing is good enough.
     */
    fun best(perSource: List<List<LyricsResult>>): Pair<Int, Int>? {
        perSource.forEachIndexed { i, list ->
            val at = list.indexOfFirst { it.synced && it.score >= LyricsMatch.MIN_SCORE }
            if (at >= 0) return i to at
        }
        var best: Pair<Int, Int>? = null
        var bestScore = -1.0
        perSource.forEachIndexed { i, list -> list.forEachIndexed { j, r -> if (!r.synced && r.score >= LyricsMatch.MIN_SCORE && r.score > bestScore) { best = i to j; bestScore = r.score } } }
        return best
    }
}

/** Cleanup shared by the providers. */
object LyricsText {
    /**
     * Drops the lines some sources put at the top of synced lyrics: a "Title - Artist" line at 00:00 and credits like
     * "Written by：...", "Producer：...", "作词 : ...". Only the first lines are looked at.
     */
    fun stripHeader(lrc: String): String {
        val credit = Regex("""^\s*(作词|作詞|作曲|编曲|編曲|制作人|製作人|监制|監製|混音|母带|母帶|词|詞|曲|Lyrics|Composer|Composed|Arranger|Arranged|Producer|Produced|Written|Lyricist|Mixed|Mastered|Recorded|Engineer|OP|SP|ISRC)(\s+by)?\s*[:：]""", RegexOption.IGNORE_CASE)
        val tags = Regex("""\[[^\]]*]""")
        var seenText = false
        return lrc.lines().filterIndexed { i, line ->
            if (i >= 14) return@filterIndexed true
            val text = tags.replace(line, "").trim()
            if (credit.containsMatchIn(text)) return@filterIndexed false
            // The first timed line, when it reads "Title - Artist" at the very start of the song.
            val header = !seenText && " - " in text && Regex("""^\[00:0[0-1][.:]\d+]""").containsMatchIn(line.trim())
            if (text.isNotBlank() && !Lrc.METADATA_RE.matches(line)) seenText = true
            !header
        }.joinToString("\n")
    }

    /** Drops LRC metadata lines ([ar:..], [ti:..], ...) which would otherwise turn into untimed entries. */
    fun stripMetadata(text: String): String =
        text.lines().filterNot { Lrc.METADATA_RE.matches(it) }.joinToString("\n").trim()
}

/** The provider that fetches from [this] source. */
fun LyricsSource.provider(timeoutMs: Int): LyricsProvider = when (this) {
    LyricsSource.LRCLIB -> LrclibProvider(timeoutMs)
    LyricsSource.NETEASE -> NeteaseProvider(timeoutMs)
    LyricsSource.LYRICSIFY -> LyricsifyProvider(timeoutMs)
    LyricsSource.QQMUSIC -> QqMusicProvider(timeoutMs)
    LyricsSource.LRCAPI -> LrcApiProvider(timeoutMs)
    LyricsSource.LYRICS_OVH -> LyricsOvhProvider(timeoutMs)
}
