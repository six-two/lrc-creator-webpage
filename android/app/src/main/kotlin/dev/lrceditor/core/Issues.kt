package dev.lrceditor.core

/** Problems found on a song that the user probably wants to fix. Stored in the library index. */
@kotlinx.serialization.Serializable
enum class Issue(val label: String, val warning: Boolean = false, val tagIssue: Boolean = false) {
    UNTIMED_LINES("untimed lines"),
    OUT_OF_ORDER("out of order"),
    /** Two synced lyrics in one: metadata in the middle (DOUBLE_LYRICS), or two ascending runs of times one after the other (DOUBLE_RUNS). */
    DOUBLE_LYRICS("two lyrics in one (tags in the middle)"),
    DOUBLE_RUNS("two lyrics in one (times start over)"),
    MISSING_LYRICS_TAG("Lyrics tag missing", tagIssue = true),
    UNDESERVED_LYRICS_TAG("Lyrics tag but no lyrics", tagIssue = true),
    MISSING_KARAOKE_TAG("Karaoke tag missing", tagIssue = true),
    UNDESERVED_KARAOKE_TAG("Karaoke tag but not fully timed", tagIssue = true),
    /** Old combined issue, only kept so an older saved library index can still be read; never produced any more. */
    TAG_MISMATCH("Lyrics/Karaoke tags wrong", tagIssue = true),
    CONFLICTING_LEARN_TAGS("conflicting learn tags"),
    SYLT_ONLY("synced frame only"),
    /** Two timed lines in a row with exactly the same time. Review only. */
    SAME_TIME("same timestamp twice in a row", warning = true),
    /** Marked by hand from the editor menu. Never produced by [Issues.checkSong]; the app adds it from its own list of marks. */
    BROKEN_LYRICS("marked as broken"),
    UNREADABLE_LYRICS("lyrics can't be read"),
    /** Tags like [𝟶𝟹:𝟶𝟸.𝟶𝟶] with non-ASCII digits, e.g. left behind by a romanization tool. Review only. */
    ODD_TIMESTAMPS("odd timestamps in text", warning = true),
}

/** Problems that sit on a particular line, for jumping to them in the editor. */
enum class LineProblem { UNTIMED, OUT_OF_ORDER, SAME_TIME, ODD_TIMESTAMPS }

object Issues {
    /** (line id, problem) in line order. A line can appear more than once if it has several problems. */
    fun lineProblems(lines: List<Line>): List<Pair<Long, LineProblem>> {
        val out = ArrayList<Pair<Long, LineProblem>>()
        var prev: Long? = null
        for (l in lines) {
            val t = l.timeMs
            if (t == null && l.text.isNotBlank()) out += l.id to LineProblem.UNTIMED
            if (t != null) {
                if (prev != null && t < prev) out += l.id to LineProblem.OUT_OF_ORDER
                if (prev != null && t == prev) out += l.id to LineProblem.SAME_TIME
                prev = t
            }
            if (Lrc.hasOddTimestamps(l.text)) out += l.id to LineProblem.ODD_TIMESTAMPS
        }
        return out
    }

    /** The first problem line after [afterId] (wrapping around), optionally only of [kind]. Null if none. */
    fun nextProblemLine(lines: List<Line>, afterId: Long?, kind: LineProblem? = null): Long? {
        val ids = lineProblems(lines).filter { kind == null || it.second == kind }.map { it.first }.distinct()
        if (ids.isEmpty()) return null
        val pos = lines.indexOfFirst { it.id == afterId }
        return ids.firstOrNull { id -> lines.indexOfFirst { it.id == id } > pos } ?: ids.first()
    }

    /** True if a timed entry is earlier than the previous timed one. Untimed entries are skipped. */
    fun hasOutOfOrder(lines: List<Line>): Boolean {
        var prev: Long? = null
        for (l in lines) {
            val t = l.timeMs ?: continue
            if (prev != null && t < prev) return true
            prev = t
        }
        return false
    }

    /** True if two timed entries in a row (untimed ones skipped) have exactly the same time. */
    fun hasSameTimeTwice(lines: List<Line>): Boolean =
        lines.mapNotNull { it.timeMs }.zipWithNext().any { (a, b) -> a == b }

    /**
     * Index in [lines] where a second complete lyric starts, if the timed lines look like two lyrics one after the other:
     * the times rise, drop back to near the start, and rise again. A single misplaced line doesn't count: both runs need at
     * least [MIN_RUN] timed lines, a similar length and a similar time span, the second must restart near where the first began,
     * and it must mostly repeat the first one's text (otherwise it's e.g. a duet with one singer's lines after the other's).
     */
    fun twoRunsSplit(lines: List<Line>): Int? {
        val timed = lines.withIndex().filter { it.value.timeMs != null }
        val runs = ArrayList<List<IndexedValue<Line>>>()
        var start = 0
        for (i in 1..timed.size) {
            if (i == timed.size || timed[i].value.timeMs!! < timed[i - 1].value.timeMs!!) { runs += timed.subList(start, i); start = i }
        }
        return runs.zipWithNext().firstOrNull { (a, b) ->
            if (a.size < MIN_RUN || b.size < MIN_RUN) return@firstOrNull false
            val a0 = a.first().value.timeMs!!
            val spanA = a.last().value.timeMs!! - a0
            val spanB = b.last().value.timeMs!! - b.first().value.timeMs!!
            val textsA = a.map { norm(it.value.text) }.filter { it.isNotEmpty() }.toSet()
            val textsB = b.map { norm(it.value.text) }.filter { it.isNotEmpty() }
            spanA > 0 && spanB > 0 && textsB.isNotEmpty() && textsB.count { it in textsA } * 2 >= textsB.size &&
                b.first().value.timeMs!! < a0 + spanA / 4 &&
                minOf(spanA, spanB) * 2 >= maxOf(spanA, spanB) &&
                minOf(a.size, b.size) * 2 >= maxOf(a.size, b.size)
        }?.second?.first()?.index
    }

    private fun norm(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

    fun hasTwoRuns(lines: List<Line>): Boolean = twoRunsSplit(lines) != null

    /**
     * The two lyrics of a song that has both, as (first, second). [hintId] is the line where a second copy's metadata was
     * found when the file was loaded (those lines are dropped from [lines], so the split can't be seen otherwise).
     */
    fun splitDouble(lines: List<Line>, hintId: Long?): Pair<List<Line>, List<Line>>? {
        val at = lines.indexOfFirst { it.id == hintId }.takeIf { it > 0 } ?: twoRunsSplit(lines) ?: return null
        return lines.subList(0, at) to lines.subList(at, lines.size)
    }

    private const val MIN_RUN = 3

    /**
     * Problems with a song given its grouping [tags] and the text of its USLT frame ([lyricsText], null if none).
     * [syltOnly]: it has a synced (SYLT) frame but no readable USLT, so its lines aren't checked.
     * Plain (untagged) lyrics are not flagged as untimed: they are simply unsynced.
     */
    fun checkSong(tags: List<String>, lyricsText: String?, syltOnly: Boolean = false): Set<Issue> {
        val out = linkedSetOf<Issue>()
        if (tags.map(Search::tagKey).count { k -> LearnState.tags.any { Search.tagKey(it) == k } } > 1) out += Issue.CONFLICTING_LEARN_TAGS
        if (syltOnly) { out += Issue.SYLT_ONLY; return out }
        val text = lyricsText?.takeIf { it.isNotBlank() }
        if (text != null && Lrc.multiTimestampProblem(text) != null) { out += Issue.UNREADABLE_LYRICS; return out }
        if (text != null && Lrc.hasOddTimestamps(text)) out += Issue.ODD_TIMESTAMPS
        val lines = text?.let(Lrc::parseAny).orEmpty()
        if (text != null && Lrc.looksLikeLrc(text)) {
            if (!Lrc.isFullyTimed(lines)) out += Issue.UNTIMED_LINES
            if (hasOutOfOrder(lines)) out += Issue.OUT_OF_ORDER
            if (hasSameTimeTwice(lines)) out += Issue.SAME_TIME
            if (Lrc.hasMetadataInMiddle(text)) out += Issue.DOUBLE_LYRICS
            if (hasTwoRuns(lines)) out += Issue.DOUBLE_RUNS
        }
        val keys = tags.map(Search::tagKey).toSet()
        val hasLyrics = Lrc.hasLyrics(lines)
        val karaoke = hasLyrics && Lrc.isFullyTimed(lines)
        val lyricsTag = "lyrics" in keys
        val karaokeTag = "karaoke" in keys
        if (hasLyrics && !lyricsTag) out += Issue.MISSING_LYRICS_TAG
        if (!hasLyrics && lyricsTag) out += Issue.UNDESERVED_LYRICS_TAG
        if (karaoke && !karaokeTag) out += Issue.MISSING_KARAOKE_TAG
        if (!karaoke && karaokeTag) out += Issue.UNDESERVED_KARAOKE_TAG
        return out
    }

    /** Number of untimed text entries in LRC-looking lyrics, for the "3 untimed" label. */
    fun untimedCount(lyricsText: String?): Int =
        if (lyricsText != null && Lrc.looksLikeLrc(lyricsText)) Lrc.countUntimed(Lrc.parse(lyricsText)) else 0
}
