package dev.lrceditor.core

/** Realigning a whole song: time the first few lines again, then shift every line by how far off they were. */
object Realign {
    const val DEFAULT_LINES = 5
    const val MAX_LINES = 20
    const val DEFAULT_UNDO_JUMP_SEC = 3
    const val MAX_UNDO_JUMP_SEC = 15

    /**
     * Where the playback goes after undoing a Set: the time of the timed line before it, but not further back than [maxBackMs]
     * from [positionMs]. With no timed line before it ([previousMs] null) it goes to the start of the song.
     */
    fun undoJumpTarget(previousMs: Long?, positionMs: Long, maxBackMs: Long): Long =
        if (previousMs == null) 0L else maxOf(previousMs, positionMs - maxBackMs, 0L)

    /** The first [count] lines to time again: those that have text and a time to compare with. */
    fun targets(lines: List<Line>, count: Int): List<Line> =
        lines.filter { it.timeMs != null && it.text.isNotBlank() }.take(count.coerceAtLeast(1))

    /** Offset to add to every time, from (old time, actual time) pairs: the median difference, so one mistimed tap doesn't skew it. */
    fun offset(pairs: List<Pair<Long, Long>>): Long {
        val d = pairs.map { (old, actual) -> actual - old }.sorted()
        if (d.isEmpty()) return 0
        return if (d.size % 2 == 1) d[d.size / 2] else (d[d.size / 2 - 1] + d[d.size / 2]) / 2
    }

    /** Biggest distance of one line's difference from [offset], to tell whether the taps agree with each other. */
    fun spread(pairs: List<Pair<Long, Long>>, offset: Long): Long = pairs.maxOfOrNull { (old, actual) -> kotlin.math.abs(actual - old - offset) } ?: 0

    /** Adds [offsetMs] to every timed line; times never go below [Lrc.MIN_TIME_MS]. Untimed lines stay untimed. */
    fun shift(lines: List<Line>, offsetMs: Long): List<Line> =
        lines.map { l -> l.timeMs?.let { l.copy(timeMs = Lrc.clampTime(it + offsetMs)) } ?: l }

    /** "+1.25 s" / "−0.30 s". */
    fun formatOffset(ms: Long): String = "%s%.2f s".format(if (ms < 0) "−" else "+", kotlin.math.abs(ms) / 1000.0)
}
