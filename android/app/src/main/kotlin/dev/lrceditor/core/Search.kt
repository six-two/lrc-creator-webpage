package dev.lrceditor.core

import dev.lrceditor.data.SongEntry
import java.text.Normalizer

data class TagCount(val tag: String, val count: Int)

enum class NameField { TITLE, FILENAME }

/** What lyrics a file carries. UNKNOWN = indexed before lyric detection existed (needs a rescan). */
@kotlinx.serialization.Serializable
enum class LyricsState { UNKNOWN, NONE, UNSYNCED, SYNCED }

@kotlinx.serialization.Serializable
enum class LyricsFilter(val label: String) {
    ALL("All"), ANY("Any lyrics"), UNSYNCED("Unsynced"), SYNCED("Synced"), NONE("No lyrics");

    fun matches(s: LyricsState) = when (this) {
        ALL -> true
        ANY -> s == LyricsState.UNSYNCED || s == LyricsState.SYNCED
        UNSYNCED -> s == LyricsState.UNSYNCED
        SYNCED -> s == LyricsState.SYNCED
        NONE -> s == LyricsState.NONE
    }
}

object Search {
    fun tagKey(tag: String) = tag.trim().lowercase()

    /** "Anime; English; Cover" -> [Anime, English, Cover]. Case-insensitive de-duplication, first spelling wins. */
    fun parseTags(grouping: String): List<String> {
        val seen = HashSet<String>()
        return grouping.split(';').map { it.trim() }.filter { it.isNotEmpty() && seen.add(tagKey(it)) }
    }

    /** All tags with the number of songs having each, most common first. Spelling of the first occurrence is shown. */
    fun tagCounts(songs: List<SongEntry>): List<TagCount> {
        val names = HashMap<String, String>()
        val counts = HashMap<String, Int>()
        for (s in songs) for (t in s.tags) {
            val k = tagKey(t)
            names.putIfAbsent(k, t)
            counts[k] = (counts[k] ?: 0) + 1
        }
        return counts.map { (k, c) -> TagCount(names.getValue(k), c) }
            .sortedWith(compareByDescending<TagCount> { it.count }.thenBy { it.tag.lowercase() })
    }

    /** Songs that have ALL include tags and NONE of the exclude tags. Empty filters -> empty result. */
    fun byTags(songs: List<SongEntry>, include: Collection<String>, exclude: Collection<String>): List<SongEntry> {
        if (include.isEmpty() && exclude.isEmpty()) return emptyList()
        val inc = include.map(::tagKey)
        val exc = exclude.map(::tagKey).toSet()
        return songs.filter { s ->
            val keys = s.tags.map(::tagKey).toSet()
            inc.all { it in keys } && keys.none { it in exc }
        }.sortedWith(songOrder)
    }

    fun byName(songs: List<SongEntry>, query: String, field: NameField, fuzzy: Boolean): List<SongEntry> {
        val terms = normalize(query).split(' ').filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()
        val scored = ArrayList<Pair<SongEntry, Int>>()
        for (s in songs) {
            val text = normalize(
                if (field == NameField.FILENAME) Lrc.baseName(s.fileName)
                else s.displayTitle
            )
            var total = 0
            var ok = true
            for (t in terms) {
                val d = if (fuzzy) substringDistance(t, text) else if (text.contains(t)) 0 else Int.MAX_VALUE
                if (d > (if (fuzzy) maxErrors(t) else 0)) { ok = false; break }
                total += d
            }
            if (ok) scored += s to total
        }
        return scored.sortedWith(compareBy<Pair<SongEntry, Int>> { it.second }.thenComparator { a, b -> songOrder.compare(a.first, b.first) })
            .map { it.first }
    }

    fun byLyrics(songs: List<SongEntry>, f: LyricsFilter) = if (f == LyricsFilter.ALL) songs else songs.filter { f.matches(it.lyrics) }

    val songOrder: Comparator<SongEntry> =
        compareBy<SongEntry> { it.artist.lowercase() }.thenBy { it.displayTitle.lowercase() }

    /** Lowercase, accents stripped, punctuation -> spaces, whitespace collapsed. */
    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    fun maxErrors(term: String) = when {
        term.length <= 3 -> 0
        term.length <= 5 -> 1
        else -> 2
    }

    /** Minimum edit distance between [pattern] and any substring of [text] (Sellers' algorithm). */
    fun substringDistance(pattern: String, text: String): Int {
        val m = pattern.length
        if (m == 0) return 0
        var prev = IntArray(m + 1) { it }
        var best = prev[m]
        for (c in text) {
            val cur = IntArray(m + 1)
            for (i in 1..m) {
                cur[i] = minOf(prev[i] + 1, cur[i - 1] + 1, prev[i - 1] + if (pattern[i - 1] == c) 0 else 1)
            }
            if (cur[m] < best) best = cur[m]
            prev = cur
        }
        return best
    }
}
