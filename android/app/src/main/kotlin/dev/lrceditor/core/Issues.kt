package dev.lrceditor.core

/** Problems found on a song that the user probably wants to fix. Stored in the library index. */
@kotlinx.serialization.Serializable
enum class Issue(val label: String, val warning: Boolean = false, val tagIssue: Boolean = false) {
    UNTIMED_LINES("untimed lines"),
    OUT_OF_ORDER("out of order"),
    MISSING_LYRICS_TAG("Lyrics tag missing", tagIssue = true),
    UNDESERVED_LYRICS_TAG("Lyrics tag but no lyrics", tagIssue = true),
    MISSING_KARAOKE_TAG("Karaoke tag missing", tagIssue = true),
    UNDESERVED_KARAOKE_TAG("Karaoke tag but not fully timed", tagIssue = true),
    /** Old combined issue, only kept so an older saved library index can still be read; never produced any more. */
    TAG_MISMATCH("Lyrics/Karaoke tags wrong", tagIssue = true),
    CONFLICTING_LEARN_TAGS("conflicting learn tags"),
    SYLT_ONLY("synced frame only"),
    UNREADABLE_LYRICS("lyrics can't be read"),
    /** Tags like [𝟶𝟹:𝟶𝟸.𝟶𝟶] with non-ASCII digits, e.g. left behind by a romanization tool. Review only. */
    ODD_TIMESTAMPS("odd timestamps in text", warning = true),
}

/** Problems that sit on a particular line, for jumping to them in the editor. */
enum class LineProblem { UNTIMED, OUT_OF_ORDER, ODD_TIMESTAMPS }

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
