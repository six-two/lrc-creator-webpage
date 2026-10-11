package dev.lrceditor.core

import java.util.concurrent.atomic.AtomicLong

/** One lyric entry. [timeMs] == null means "no timestamp yet" (distinct from 0). [text] may contain '\n'. */
data class Line(val id: Long, val timeMs: Long?, val text: String)

object Lrc {
    private val ids = AtomicLong(1)
    fun newLine(timeMs: Long?, text: String) = Line(ids.getAndIncrement(), timeMs, text)

    private val TAG_RE = Regex("""\[([0-9]{1,3}):([0-9]{2})(?:[.:]([0-9]{1,3}))?]""")
    private val TIME_INPUT_RE = Regex("""^([0-9]{1,3}):([0-9]{1,2})(?:[.:]([0-9]{1,3}))?$""")
    /** ID tag lines like [ar:Artist], [al: Album], [offset:0], [ti:Song [english ver.]] (brackets may be nested): not lyrics, and not timestamps (those start with digits). */
    val METADATA_RE = Regex("""^\s*\[[A-Za-z]{1,12}\s*:.*]\s*$""")
    /** The ID tags that identify a song (a second copy repeats them). Others, like [offset:0], can sit anywhere. Used where a false positive matters, since lyrics can contain things like [Chorus: Drake]. */
    private val KNOWN_META_RE = Regex("""^\s*\[(ti|ar|al|au|by)\s*:.*]\s*$""", RegexOption.IGNORE_CASE)
    private val NEWLINE_RE = Regex("""\r\n|\r|\n""")

    /** m:ss.t (tenths) for small screens, or -:--.- for null. */
    fun formatTimeShort(ms: Long?): String {
        if (ms == null) return "-:--.-"
        val tenths = (maxOf(ms, 0L) + 50) / 100
        return "%d:%02d.%d".format(tenths / 600, (tenths % 600) / 10, tenths % 10)
    }

    /** mm:ss.xx (hundredths), or --:--.-- for null. */
    fun formatTime(ms: Long?): String {
        if (ms == null) return "--:--.--"
        val centis = (maxOf(ms, 0L) + 5) / 10
        val m = centis / 6000
        val s = (centis % 6000) / 100
        val c = centis % 100
        return "%02d:%02d.%02d".format(m, s, c)
    }

    private fun fracToMs(frac: String?): Int {
        val f = frac ?: "0"
        val padded = when (f.length) { 1 -> f + "00"; 2 -> f + "0"; else -> f.take(3) }
        return padded.toInt()
    }

    sealed interface TimeInput {
        data class Ok(val ms: Long?) : TimeInput
        data object Invalid : TimeInput
    }

    /** Parses a hand-typed timestamp ("1:23", "01:23.45", "01:23:45"). Blank clears the time (Ok(null)). */
    fun parseTimeInput(input: String): TimeInput {
        val s = input.trim()
        if (s.isEmpty()) return TimeInput.Ok(null)
        val m = TIME_INPUT_RE.matchEntire(s) ?: return TimeInput.Invalid
        val mins = m.groupValues[1].toLong()
        val secs = m.groupValues[2].toLong()
        if (secs > 59) return TimeInput.Invalid
        val frac = m.groups[3]?.value
        return TimeInput.Ok(mins * 60_000 + secs * 1000 + fracToMs(frac))
    }

    private val LOOKALIKE_RE = Regex("""\[\p{Nd}{1,3}:\p{Nd}{2}(?:[.:]\p{Nd}{1,3})?]""")

    /** Timestamp-looking tags written with non-ASCII digits (e.g. 𝟶𝟹:𝟶𝟸). They are not timestamps here; they stay as plain text. */
    fun hasOddTimestamps(text: String) = LOOKALIKE_RE.findAll(text).any { m -> m.value.any { it.code > 127 } || m.value.codePoints().anyMatch { it > 127 } }

    /**
     * Line index of an identifying ID tag ([ar:..], [ti:..]) in the middle of the lyrics: after a timed lyric line and followed
     * by another one, which is what a second LRC pasted below the first looks like. Other tags ([offset:0], [length:..]) are
     * skipped over, and so is a lone tag at the end of the file (a credit line), which many LRCs have.
     */
    private fun midMetadataAt(raw: List<String>): Int? {
        var seenLyric = false
        var tagAt: Int? = null
        for ((i, line) in raw.withIndex()) {
            when {
                line.isBlank() -> {}
                KNOWN_META_RE.matches(line) -> if (seenLyric && tagAt == null) tagAt = i
                METADATA_RE.matches(line) -> {}
                TAG_RE.containsMatchIn(line) && TAG_RE.replace(line, "").isNotBlank() -> { if (tagAt != null) return tagAt; seenLyric = true }
            }
        }
        return null
    }

    fun hasMetadataInMiddle(text: String): Boolean = secondCopyStart(text) != null

    /**
     * Number of entries before the middle ID tag that starts a second copy (see [midMetadataAt]), or null if there is none.
     * A second block with (nearly) the same times as the first is not a second sync but the same lyrics with a translation or
     * romanization after each line, so it doesn't count.
     */
    fun secondCopyStart(text: String): Int? {
        val raw = text.split(NEWLINE_RE)
        val at = midMetadataAt(raw) ?: return null
        val first = parse(raw.take(at).joinToString("\n"))
        if (first.isEmpty()) return null
        val firstTimes = first.mapNotNull { it.timeMs }.toSet()
        val second = parse(raw.drop(at).joinToString("\n")).mapNotNull { it.timeMs }
        if (second.isNotEmpty() && second.count { it in firstTimes } * 10 >= second.size * 8) return null
        return first.size
    }

    fun looksLikeLrc(text: String) = TAG_RE.containsMatchIn(text)

    /** Smallest real timestamp. An exact 00:00.00 in a file means "untimed", so a real time is never below this. */
    const val MIN_TIME_MS = 10L

    fun clampTime(ms: Long): Long = maxOf(ms, MIN_TIME_MS)


    private val LEADING_TAGS_RE = Regex("""^\s*((?:\[[0-9]{1,3}:[0-9]{2}(?:[.:][0-9]{1,3})?]\s*)+)""")

    private fun tagMs(m: MatchResult) =
        m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1000 + fracToMs(m.groups[3]?.value)

    private class Parsed(val entries: List<Line>, val multiTag: Boolean)

    private fun parseEntries(text: String): Parsed {
        val out = mutableListOf<Line>()
        val raw = text.split(NEWLINE_RE).toMutableList()
        while (raw.isNotEmpty() && raw.last().isBlank()) raw.removeAt(raw.size - 1)

        var curTimes: List<Long> = emptyList()
        var curLines: MutableList<String>? = null
        var multi = false
        // One entry per timestamp, all sharing the text (and continuation lines) of their source line.
        fun flush() {
            curLines?.let { ls ->
                val t = ls.joinToString("\n")
                // This app never writes several timestamps on one line, so a 00:00.00 there is a real time, not "untimed".
                val multiLine = curTimes.size > 1
                curTimes.forEach { out += newLine(if (multiLine && it == 0L) MIN_TIME_MS else it, t) }
            }
        }

        for (line in raw) {
            if (METADATA_RE.matches(line)) continue
            // Timestamps in front of the text are the line's times; a tag elsewhere only counts if there is none in front.
            val lead = LEADING_TAGS_RE.find(line)
            val tags = (if (lead != null) TAG_RE.findAll(lead.groupValues[1]) else TAG_RE.findAll(line)).map(::tagMs).toList()
            if (tags.isNotEmpty()) {
                flush()
                if (tags.size > 1) multi = true
                curTimes = tags
                curLines = mutableListOf(TAG_RE.replace(line, "").trim())
            } else if (curLines != null) {
                curLines.add(line)
            } else {
                out += newLine(null, line.trim())
            }
        }
        flush()
        return Parsed(resolveZeroTimes(out), multi)
    }

    /** Stable sort by time; entries without a time (pauses) stay behind the entry before them. */
    private fun sortByTime(entries: List<Line>): List<Line> {
        var key = 0L
        return entries.map { l -> (l.timeMs?.also { key = it } ?: key) to l }.sortedBy { it.first }.map { it.second }
    }

    /**
     * Untagged lines following a tagged line are continuation lines of that entry (joined by '\n').
     * Metadata lines ([ar:..], [al:..]) are skipped. Untagged lines before any tag become untimed entries, and so do entries tagged exactly 00:00.00
     * (that is how untimed entries are written, see [resolveExportTimestamps] and [resolveZeroTimes]).
     *
     * A line with several timestamps ("[00:10.00][00:50.00]chorus") becomes one entry per timestamp. In a fully timed
     * song those are put in time order. In a partly timed song that can't be done sensibly: see [multiTimestampProblem]
     * (the entries are then still returned, in file order).
     */
    fun parse(text: String): List<Line> {
        val p = parseEntries(text)
        return if (p.multiTag && isFullyTimed(p.entries)) sortByTime(p.entries) else p.entries
    }

    /** Null if [text] can be read; otherwise why not (several timestamps per line in a song that isn't fully timed). */
    fun multiTimestampProblem(text: String): String? {
        if (!looksLikeLrc(text)) return null
        val p = parseEntries(text)
        return if (p.multiTag && !isFullyTimed(p.entries))
            "Some lines have several timestamps (like [00:10.00][00:50.00]chorus). That can only be read when every line of the song has a time, and here some don't."
        else null
    }

    /**
     * An exact 00:00.00 is how an untimed entry is saved, so it normally reads back as untimed. Exception: LRCs from
     * elsewhere often start the first lyric line at 00:00.00. That one counts as a real time (kept as [MIN_TIME_MS])
     * when some other entry has a real time; otherwise the whole file is just unfinished and stays untimed.
     */
    private fun resolveZeroTimes(entries: List<Line>): List<Line> {
        if (entries.none { it.timeMs == 0L }) return entries
        val first = entries.indexOfFirst { it.text.isNotBlank() }
        val anyReal = entries.any { (it.timeMs ?: 0L) > 0L }
        return entries.mapIndexed { i, l ->
            if (l.timeMs != 0L) l
            else if (i == first && anyReal) l.copy(timeMs = MIN_TIME_MS)
            else l.copy(timeMs = null)
        }
    }

    /** Plain lyrics import: every non-blank line becomes its own untimed entry. */
    fun plainTextToLines(text: String): List<Line> =
        text.split(NEWLINE_RE).map { it.trim() }.filter { it.isNotEmpty() }.map { newLine(null, it) }

    /** Parse either LRC or plain text depending on whether any tag is present. */
    fun parseAny(text: String): List<Line> = if (looksLikeLrc(text)) parse(text) else plainTextToLines(text)

    /** Untimed entries are written as 00:00.00 (read back as untimed); real times are at least [MIN_TIME_MS]. */
    fun resolveExportTimestamps(lines: List<Line>): List<Pair<Long, String>> =
        lines.map { l -> (l.timeMs?.let(::clampTime) ?: 0L) to l.text }

    fun countPlaceholders(lines: List<Line>) = lines.count { it.timeMs == null }

    /** Entries with text but no time. Blank pause entries and continuation (romanization) lines don't count. */
    fun countUntimed(lines: List<Line>) = lines.count { it.timeMs == null && it.text.isNotBlank() }

    fun hasLyrics(lines: List<Line>) = lines.any { it.text.isNotBlank() }

    /** True when every entry with text has a time. */
    fun isFullyTimed(lines: List<Line>) = countUntimed(lines) == 0

    fun serialize(lines: List<Line>): String =
        resolveExportTimestamps(lines).joinToString("\n") { (t, text) -> "[${formatTime(t)}]$text" }

    fun baseName(fileName: String) = fileName.replace(Regex("""\.[^/.]+$"""), "")

    /** Checks bytes, not the extension: "ID3" header, or an MPEG frame sync within the first 8 KiB. */
    fun looksLikeMp3(b: ByteArray): Boolean {
        if (b.size >= 3 && b[0] == 0x49.toByte() && b[1] == 0x44.toByte() && b[2] == 0x33.toByte()) return true
        val scan = minOf(b.size, 8192)
        for (i in 0 until scan - 1) {
            if (b[i] == 0xFF.toByte() && (b[i + 1].toInt() and 0xE0) == 0xE0) return true
        }
        return false
    }
}
