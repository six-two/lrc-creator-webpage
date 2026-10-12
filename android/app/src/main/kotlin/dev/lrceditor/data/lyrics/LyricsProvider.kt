package dev.lrceditor.data.lyrics

import dev.lrceditor.core.Lrc
import dev.lrceditor.core.Search
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

class FetchOutcome(val result: LyricsResult?, val errors: List<String>)

/** Scores how well a candidate (title / artist / length) matches what we are looking for, 0..1. */
object LyricsMatch {
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

/** Tries the providers in order: the first synced result wins, otherwise the first plain one. */
object LyricsFetcher {
    suspend fun fetch(q: LyricsQuery, providers: List<LyricsProvider>): FetchOutcome {
        val errors = ArrayList<String>()
        var plain: LyricsResult? = null
        for (p in providers) {
            val found = try {
                p.search(q)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runCatching { android.util.Log.w("LyricsFetch", "${p.name} failed", e) } // not available in unit tests
                errors += "${p.name}: ${e.javaClass.simpleName}: ${e.message ?: ""}".trimEnd(' ', ':')
                continue
            }.filter { it.score >= LyricsMatch.MIN_SCORE && it.text.isNotBlank() }
            found.filter { it.synced }.maxByOrNull { it.score }?.let { return FetchOutcome(it, errors) }
            if (plain == null) plain = found.filter { !it.synced }.maxByOrNull { it.score }
        }
        return FetchOutcome(plain, errors)
    }
}

/** Cleanup shared by the providers. */
object LyricsText {
    /** Drops LRC metadata lines ([ar:..], [ti:..], ...) which would otherwise turn into untimed entries. */
    fun stripMetadata(text: String): String =
        text.lines().filterNot { Lrc.METADATA_RE.matches(it) }.joinToString("\n").trim()
}
