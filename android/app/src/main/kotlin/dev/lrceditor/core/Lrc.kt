package dev.lrceditor.core

import java.util.concurrent.atomic.AtomicLong

/** One lyric entry. [timeMs] == null means "no timestamp yet" (distinct from 0). [text] may contain '\n'. */
data class Line(val id: Long, val timeMs: Long?, val text: String)

object Lrc {
    private val ids = AtomicLong(1)
    fun newLine(timeMs: Long?, text: String) = Line(ids.getAndIncrement(), timeMs, text)

    private val TAG_RE = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val TIME_INPUT_RE = Regex("""^(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?$""")
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

    fun looksLikeLrc(text: String) = TAG_RE.containsMatchIn(text)

    /**
     * Untagged lines following a tagged line are continuation lines of that entry (joined by '\n').
     * Untagged lines before any tag become untimed entries.
     */
    fun parse(text: String): List<Line> {
        val out = mutableListOf<Line>()
        val raw = text.split(NEWLINE_RE).toMutableList()
        while (raw.isNotEmpty() && raw.last().isBlank()) raw.removeAt(raw.size - 1)

        var curTime: Long? = null
        var curLines: MutableList<String>? = null
        fun flush() { curLines?.let { out += newLine(curTime, it.joinToString("\n")) } }

        for (line in raw) {
            val tags = TAG_RE.findAll(line).map {
                it.groupValues[1].toLong() * 60_000 + it.groupValues[2].toLong() * 1000 + fracToMs(it.groups[3]?.value)
            }.toList()
            if (tags.isNotEmpty()) {
                flush()
                val content = TAG_RE.replace(line, "").trim()
                for (i in 0 until tags.size - 1) out += newLine(tags[i], content)
                curTime = tags.last()
                curLines = mutableListOf(content)
            } else if (curLines != null) {
                curLines.add(line)
            } else {
                out += newLine(null, line.trim())
            }
        }
        flush()
        return out
    }

    /** Plain lyrics import: every non-blank line becomes its own untimed entry. */
    fun plainTextToLines(text: String): List<Line> =
        text.split(NEWLINE_RE).map { it.trim() }.filter { it.isNotEmpty() }.map { newLine(null, it) }

    /** Parse either LRC or plain text depending on whether any tag is present. */
    fun parseAny(text: String): List<Line> = if (looksLikeLrc(text)) parse(text) else plainTextToLines(text)

    /** Untimed lines get the nearest preceding timestamp (0 if none) so nothing is dropped on export. */
    fun resolveExportTimestamps(lines: List<Line>): List<Pair<Long, String>> {
        var last = 0L
        return lines.map { l ->
            val t = l.timeMs ?: last
            last = t
            t to l.text
        }
    }

    fun countPlaceholders(lines: List<Line>) = lines.count { it.timeMs == null }

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
