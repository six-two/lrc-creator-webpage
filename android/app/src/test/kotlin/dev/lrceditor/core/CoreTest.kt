package dev.lrceditor.core

import dev.lrceditor.data.*
import dev.lrceditor.data.lyrics.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    @Test fun multilineEntriesRoundTrip() {
        val src = "[00:01.000] This is a multiline text\nDies ist ein Mehrzeilen-Text\n[00:02.000] Normal single line text"
        val lines = Lrc.parse(src)
        assertEquals(2, lines.size)
        assertEquals(1000L, lines[0].timeMs)
        assertEquals("This is a multiline text\nDies ist ein Mehrzeilen-Text", lines[0].text)
        assertEquals("[00:01.00]This is a multiline text\nDies ist ein Mehrzeilen-Text\n[00:02.00]Normal single line text", Lrc.serialize(lines))
    }

    @Test fun untimedLinesAreSavedAsZeroAndReadBackUntimed() {
        val lines = listOf(Lrc.newLine(5000, "a"), Lrc.newLine(null, "b"))
        assertEquals("[00:05.00]a\n[00:00.00]b", Lrc.serialize(lines))
        val back = Lrc.parse(Lrc.serialize(lines))
        assertEquals(listOf<Long?>(5000L, null), back.map { it.timeMs })
        assertEquals(listOf("a", "b"), back.map { it.text })
    }

    @Test fun zeroOnFirstLyricLineIsARealTime() {
        val lines = Lrc.parse("[ti:x]\n[00:00.00]first\n[00:05.00]second\n[00:00.00]odd one")
        assertEquals(listOf<Long?>(10L, 5000L, null), lines.map { it.timeMs })
        // saved as 00:00.01 and stable afterwards
        assertEquals("[00:00.01]first\n[00:05.00]second\n[00:00.00]odd one", Lrc.serialize(lines))
        assertEquals(lines.map { it.timeMs }, Lrc.parse(Lrc.serialize(lines)).map { it.timeMs })
        // a blank pause before it doesn't count as the first lyric line, but the text line does
        assertEquals(listOf<Long?>(null, 10L, 3000L), Lrc.parse("[00:00.00]\n[00:00.00]a\n[00:03.00]b").map { it.timeMs })
        // all zero = an unfinished song saved by this app: stays untimed
        assertEquals(listOf<Long?>(null, null), Lrc.parse("[00:00.00]a\n[00:00.00]b").map { it.timeMs })
        assertTrue(Lrc.isFullyTimed(Lrc.parse("[00:00.00]a\n[00:04.00]b")))
    }

    @Test fun realZeroIsClampedSoItStaysTimed() {
        assertEquals(10L, Lrc.clampTime(0))
        val back = Lrc.parse(Lrc.serialize(listOf(Lrc.newLine(0, "x"), Lrc.newLine(3, "y"))))
        assertEquals(listOf<Long?>(10L, 10L), back.map { it.timeMs })
    }

    @Test fun fullyTimedIgnoresRomanizationAndPauses() {
        val src = "[00:01.00]こんにちは\nkonnichiwa\n[00:03.00]\n[00:05.00]bye\nbai"
        val lines = Lrc.parse(src)
        assertTrue(Lrc.isFullyTimed(lines))
        assertEquals(0, Lrc.countUntimed(lines))
        assertTrue(Lrc.isFullyTimed(Lrc.parse(Lrc.serialize(lines))))
    }

    @Test fun metadataLinesAreNotLyrics() {
        val src = "[ti: Song]\n[al: Some Album]\n[ar:Me]\n[00:01.00]a\n[offset:0]\n[00:02.00]b"
        val lines = Lrc.parse(src)
        assertEquals(listOf("a", "b"), lines.map { it.text })
        assertTrue(Lrc.isFullyTimed(lines))
        assertEquals(emptySet<Issue>(), Issues.checkSong(listOf("Lyrics", "Karaoke"), src))
    }

    @Test fun fancyUnicodeDigitsAreNotTimestamps() {
        val src = "[\uD835\uDFF6\uD835\uDFF9:\uD835\uDFF6\uD835\uDFF8.00]odd digits\n[00:05.00]normal"
        val lines = Lrc.parse(src) // must not throw
        assertEquals(1, lines.count { it.timeMs != null })
        assertTrue(Issue.ODD_TIMESTAMPS in Issues.checkSong(emptyList(), src))
        assertTrue(Issue.ODD_TIMESTAMPS.warning)
        assertFalse(Issue.ODD_TIMESTAMPS in Issues.checkSong(listOf("Lyrics", "Karaoke"), "[00:01.00]fine\nromaji [x]"))
        assertTrue(Lrc.hasOddTimestamps("[00:01.00]a\n[\uD835\uDFF6\uD835\uDFF9:00.00]b"))
        assertFalse(Lrc.hasOddTimestamps("[00:01.00]a"))
        assertEquals(Lrc.TimeInput.Invalid, Lrc.parseTimeInput("\uD835\uDFF6\uD835\uDFF9:00"))
    }

    @Test fun metadataWithNestedBracketsIsSkipped() {
        val src = "[ti:something [english ver.]]\n[ar: A [feat. B]]\n[00:01.00]a\n[00:02.00]b"
        assertEquals(listOf("a", "b"), Lrc.parse(src).map { it.text })
        assertEquals(emptySet<Issue>(), Issues.checkSong(listOf("Lyrics", "Karaoke"), src))
        assertEquals("[00:01.00]a", LyricsText.stripMetadata("[ti:x [y]]\n[00:01.00]a"))
    }

    @Test fun partialTimingIsNotFullyTimed() {
        val lines = listOf(Lrc.newLine(1000, "a"), Lrc.newLine(null, "b"), Lrc.newLine(null, ""), Lrc.newLine(4000, "c"))
        assertFalse(Lrc.isFullyTimed(lines))
        assertEquals(1, Lrc.countUntimed(lines))
        assertFalse(Lrc.isFullyTimed(Lrc.parse(Lrc.serialize(lines))))
        assertFalse(Lrc.isFullyTimed(Lrc.plainTextToLines("one\ntwo")))
    }

    @Test fun shortTimeFormat() {
        assertEquals("0:00.0", Lrc.formatTimeShort(0))
        assertEquals("1:23.5", Lrc.formatTimeShort(83450))
        assertEquals("12:05.0", Lrc.formatTimeShort(725_000))
        assertEquals("-:--.-", Lrc.formatTimeShort(null))
    }

    @Test fun timeInput() {
        assertEquals(Lrc.TimeInput.Ok(83450), Lrc.parseTimeInput("01:23.45"))
        assertEquals(Lrc.TimeInput.Ok(83000), Lrc.parseTimeInput("1:23"))
        assertEquals(Lrc.TimeInput.Ok(null), Lrc.parseTimeInput("  "))
        assertEquals(Lrc.TimeInput.Invalid, Lrc.parseTimeInput("1:99"))
    }

    @Test fun embedPreservesOtherFramesAndAudio() {
        val tit2 = byteArrayOf(3) + "Song".toByteArray()
        fun frame(id: String, p: ByteArray) =
            id.toByteArray() + byteArrayOf(0, 0, 0, p.size.toByte(), 0, 0) + p
        val body = frame("TIT2", tit2)
        val audio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0, 1, 2, 3)
        val file = byteArrayOf(0x49, 0x44, 0x33, 3, 0, 0, 0, 0, 0, body.size.toByte()) + body + audio

        val text = "[00:01.00]héllo 🎵"
        val out = Id3.embedLyrics(file, text)
        val parsed = Id3.parse(out)
        assertEquals(listOf("TIT2", "USLT"), parsed.frames.map { it.id })
        assertArrayEquals(tit2, parsed.frames[0].data)
        assertEquals(text, Id3.parseUslt(parsed.frames[1].data).text)
        assertArrayEquals(audio, out.copyOfRange(parsed.audioStart, out.size))
        assertEquals(text, Lrc.serialize(Id3.extractLines(parsed)).replace("[00:01.00]", "[00:01.00]"))
    }

    @Test fun noTagFileGetsFreshTag() {
        val audio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0)
        assertTrue(Lrc.looksLikeMp3(audio))
        val out = Id3.embedLyrics(audio, "[00:00.00]x")
        assertEquals("[00:00.00]x", Id3.parseUslt(Id3.parse(out).frames.single().data).text)
    }
}

class SearchTest {
    private fun song(title: String, artist: String = "", tags: String = "", file: String = "$title.mp3") =
        dev.lrceditor.data.SongEntry("content://x/$file", file, title, artist, "", Search.parseTags(tags), 0, 0)

    @Test fun tagParsing() {
        assertEquals(listOf("Anime", "English", "Cover"), Search.parseTags("Anime; English; Cover"))
        assertEquals(listOf("Anime"), Search.parseTags(" Anime ;anime;; "))
    }

    @Test fun tagCountsAndFilters() {
        val songs = listOf(song("a", tags = "Anime; English"), song("b", tags = "anime; Cover"), song("c", tags = "English"))
        assertEquals(listOf("Anime" to 2, "English" to 2, "Cover" to 1), Search.tagCounts(songs).map { it.tag to it.count })
        assertEquals(listOf("a"), Search.byTags(songs, listOf("english", "ANIME"), emptyList()).map { it.title })
        assertEquals(listOf("c"), Search.byTags(songs, emptyList(), listOf("anime")).map { it.title })
        assertTrue(Search.byTags(songs, emptyList(), emptyList()).isEmpty())
    }

    @Test fun fuzzyNameSearch() {
        val songs = listOf(song("Gurenge", "LiSA"), song("Unravel", "TK"))
        assertTrue(Search.byName(songs, "gurnge", NameField.TITLE, fuzzy = false).isEmpty())
        assertEquals(listOf("Gurenge"), Search.byName(songs, "gurnge", NameField.TITLE, fuzzy = true).map { it.title })
        assertTrue(Search.byName(songs, "tk", NameField.TITLE, fuzzy = false).isEmpty()) // artist is not searched
        assertEquals(listOf("Unravel"), Search.byName(songs, "unrav", NameField.FILENAME, fuzzy = false).map { it.title })
    }
}

class LyricsDetectTest {
    private fun state(lrc: String?): LyricsState {
        val audio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0)
        val file = if (lrc == null) audio else Id3.embedLyrics(audio, lrc)
        return Id3.readMeta(java.io.ByteArrayInputStream(file)).lyrics
    }

    @Test fun detectsLyricsStates() {
        assertEquals(LyricsState.NONE, state(null))
        assertEquals(LyricsState.NONE, state("  "))
        assertEquals(LyricsState.UNSYNCED, state("just some words"))
        assertEquals(LyricsState.SYNCED, state("[00:01.00]hello"))
    }

    @Test fun filters() {
        assertTrue(LyricsFilter.ANY.matches(LyricsState.SYNCED))
        assertTrue(LyricsFilter.ANY.matches(LyricsState.UNSYNCED))
        assertFalse(LyricsFilter.ANY.matches(LyricsState.NONE))
        assertFalse(LyricsFilter.NONE.matches(LyricsState.UNKNOWN))
        assertTrue(LyricsFilter.ALL.matches(LyricsState.UNKNOWN))
    }
}

class IgnoreTest {
    @Test fun defaultsAndWildcards() {
        val m = Ignore.matcher(Ignore.DEFAULTS)
        listOf(".stfolder", ".STVERSIONS", ".debris", ".Trash-1000", ".trashed-1700000-x", "\$RECYCLE.BIN", "@eaDir").forEach { assertTrue(it, m(it)) }
        listOf("Music", "stfolder", "My .debris stash", "Anime").forEach { assertFalse(it, m(it)) }
        assertTrue(Ignore.matcher(listOf("Podcasts*"))("podcasts old"))
        val files = Ignore.matcher(Ignore.DEFAULT_FILES)
        assertTrue(files("Song.sync-conflict-20250101-120000-ABCDEFG.mp3"))
        assertFalse(files("Song.mp3"))
    }
}

class DocPathTest {
    @Test fun relativePath() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AMusic%2Fsyncthing/document/primary%3AMusic%2Fsyncthing%2Fcovers%20misc%2FKit%20-%20Song%20(v2)%2B.mp3"
        assertEquals("covers misc/Kit - Song (v2)+.mp3", DocPath.relativePath(uri))
        assertEquals("日本語/曲.mp3", DocPath.relativePath("content://a/tree/p%3AM/document/p%3AM%2F%E6%97%A5%E6%9C%AC%E8%AA%9E%2F%E6%9B%B2.mp3"))
        assertNull(DocPath.relativePath("content://a/document/p%3AM%2Fx.mp3"))
        assertEquals("/storage/emulated/0/Music/a b/x.mp3", DocPath.fullPath("content://a/tree/primary%3AMusic/document/primary%3AMusic%2Fa%20b%2Fx.mp3"))
    }
}

class LearnTagTest {
    @Test fun learnStateSwapsAndKeepsOtherTags() {
        val tags = listOf("Anime", "learn", "English", "Lyrics")
        assertEquals(listOf("Anime", "English", "Lyrics", "SingAlong"), Search.withLearnState(tags, LearnState.SING_ALONG))
        assertEquals(listOf("Anime", "English", "Lyrics"), Search.withLearnState(tags, LearnState.NONE))
        assertEquals(listOf("Anime", "learn", "English", "Lyrics"), Search.withLearnState(tags, LearnState.LEARN))
        assertEquals(listOf("Anime", "Unsingable"), Search.withLearnState(listOf("SINGALONG", "Anime", "Learn"), LearnState.UNSINGABLE))
        assertEquals(listOf("Learn"), Search.withLearnState(emptyList(), LearnState.LEARN))
    }

    @Test fun learnStateOf() {
        assertEquals(LearnState.NONE, Search.learnStateOf(listOf("Anime")))
        assertEquals(LearnState.UNSINGABLE, Search.learnStateOf(listOf("Anime", "unsingable")))
    }

    private fun lines(vararg t: Long?) = t.map { Lrc.newLine(it, "x") }

    @Test fun lyricsAndKaraokeTags() {
        val full = lines(1000, 2000)
        assertEquals(listOf("Anime", "Lyrics", "Karaoke"), Search.withLyricsTags(listOf("Anime"), full))
        assertEquals(listOf("Anime", "Lyrics"), Search.withLyricsTags(listOf("Anime", "karaoke"), lines(1000, null)))
        assertEquals(listOf("Anime"), Search.withLyricsTags(listOf("Anime", "LYRICS", "Karaoke"), emptyList()))
        assertEquals(listOf("Anime"), Search.withLyricsTags(listOf("Anime", "Lyrics"), listOf(Lrc.newLine(null, ""))))
        // already right: nothing moves
        assertEquals(listOf("Lyrics", "Anime", "Karaoke"), Search.withLyricsTags(listOf("Lyrics", "Anime", "Karaoke"), full))
    }
}

class GroupingWriteTest {
    private fun frame(id: String, p: ByteArray) = id.toByteArray() + byteArrayOf(0, 0, 0, p.size.toByte(), 0, 0) + p
    private fun text(enc: Int, s: String) = byteArrayOf(enc.toByte()) + s.toByteArray(if (enc == 0) Charsets.ISO_8859_1 else Charsets.UTF_8)
    private val audio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0, 1, 2, 3, 4)
    private fun file(vararg frames: ByteArray): ByteArray {
        val body = frames.fold(ByteArray(0)) { a, b -> a + b }
        return byteArrayOf(0x49, 0x44, 0x33, 3, 0, 0, 0, 0, 0, body.size.toByte()) + body + audio
    }
    private fun meta(b: ByteArray) = Id3.readMeta(java.io.ByteArrayInputStream(b))
    private fun tags(b: ByteArray) = Search.parseTags(meta(b).grouping)

    @Test fun rewritesTit1KeepingOtherFramesAndAudio() {
        val tit2 = text(3, "Song"); val apic = byteArrayOf(0, 9, 9, 9)
        val f = file(frame("TIT2", tit2), frame("TIT1", text(0, "Anime; Learn")), frame("APIC", apic))
        val out = Id3.setGrouping(f, Search.withLearnState(Search.parseTags("Anime; Learn"), LearnState.SING_ALONG))
        val p = Id3.parse(out)
        assertEquals(listOf("TIT2", "GRP1", "APIC"), p.frames.map { it.id }) // the grouping moves from TIT1 (the Work) to GRP1
        assertArrayEquals(tit2, p.frames[0].data)
        assertArrayEquals(apic, p.frames[2].data)
        assertArrayEquals(audio, out.copyOfRange(p.audioStart, out.size))
        assertEquals(listOf("Anime", "SingAlong"), tags(out))
        assertEquals("Song", meta(out).title)
    }

    @Test fun writesToGrp1WhenThatIsWhatExistsAndCreatesGrp1WhenNeither() {
        val g = Id3.setGrouping(file(frame("GRP1", text(0, "Old"))), listOf("New", "Learn"))
        assertEquals(listOf("GRP1"), Id3.parse(g).frames.map { it.id })
        assertEquals(listOf("New", "Learn"), tags(g))
        val n = Id3.setGrouping(file(frame("TIT2", text(3, "Song"))), listOf("Learn"))
        assertEquals(listOf("TIT2", "GRP1"), Id3.parse(n).frames.map { it.id })
        assertEquals(listOf("Learn"), tags(n))
        val bare = Id3.setGrouping(audio, listOf("Learn"))
        assertEquals(listOf("Learn"), tags(bare))
        assertArrayEquals(audio, bare.copyOfRange(Id3.parse(bare).audioStart, bare.size))
    }

    @Test fun nonLatinTagsUseUtf16AndEmptyRemovesFrame() {
        val out = Id3.setGrouping(file(frame("TIT1", text(0, "a"))), listOf("アニメ", "Learn"))
        assertEquals(listOf("アニメ", "Learn"), tags(out))
        assertEquals(emptyList<String>(), tags(Id3.setGrouping(out, emptyList())))
        assertTrue(Id3.parse(Id3.setGrouping(out, emptyList())).frames.isEmpty())
    }

    @Test fun lyricsAndTagsInOnePass() {
        val f = file(frame("TIT1", text(0, "Anime; Learn")))
        val lines = Lrc.parse("[00:01.00]a\n[00:02.00]b")
        val tags = Search.withLyricsTags(Search.parseTags("Anime; Learn"), lines)
        val out = Id3.update(f, Lrc.serialize(lines), tags)
        val m = meta(out)
        assertEquals(listOf("Anime", "Learn", "Lyrics", "Karaoke"), Search.parseTags(m.grouping))
        assertEquals("[00:01.00]a\n[00:02.00]b", m.lyricsText)
        assertEquals(Search.learnStateOf(Search.parseTags(m.grouping)), LearnState.LEARN)
        // learn-state-only change keeps the lyrics byte for byte
        val again = Id3.setGrouping(out, Search.withLearnState(tags, LearnState.NONE))
        assertEquals(m.lyricsText, meta(again).lyricsText)
        assertEquals(listOf("Anime", "Lyrics", "Karaoke"), tags(again))
    }
}

class IssuesTest {
    private fun counts(tags: String, lyrics: String?) = Issues.countSong(Search.parseTags(tags), lyrics)
    private fun check(tags: String, lyrics: String?, sylt: Boolean = false) = Issues.checkSong(Search.parseTags(tags), lyrics, sylt)

    @Test fun cleanSong() {
        assertEquals(emptySet<Issue>(), check("Lyrics; Karaoke", "[00:01.00]a\n[00:02.00]b"))
        assertEquals(emptySet<Issue>(), check("", null))
        assertEquals(emptySet<Issue>(), check("Lyrics", "just words"))
    }

    @Test fun untimedAndMismatch() {
        val r = check("Lyrics; Karaoke", "[00:01.00]a\n[00:00.00]b")
        assertEquals(setOf(Issue.UNTIMED_LINES, Issue.UNDESERVED_KARAOKE_TAG), r)
        assertEquals(1, counts("Lyrics; Karaoke", "[00:01.00]a\n[00:00.00]b")[Issue.UNTIMED_LINES])
        assertNull(counts("Lyrics", "plain words")[Issue.UNTIMED_LINES])
    }

    @Test fun specificTagIssues() {
        assertEquals(setOf(Issue.MISSING_LYRICS_TAG, Issue.MISSING_KARAOKE_TAG), check("", "[00:01.00]a"))
        assertEquals(setOf(Issue.MISSING_KARAOKE_TAG), check("Lyrics", "[00:01.00]a"))
        assertEquals(setOf(Issue.MISSING_LYRICS_TAG), check("Karaoke", "[00:01.00]a"))
        assertEquals(setOf(Issue.MISSING_LYRICS_TAG), check("", "plain words"))
        assertEquals(setOf(Issue.UNDESERVED_KARAOKE_TAG), check("Lyrics; Karaoke", "plain words"))
        assertEquals(setOf(Issue.UNDESERVED_LYRICS_TAG, Issue.UNDESERVED_KARAOKE_TAG), check("Lyrics; Karaoke", null))
        assertEquals(setOf(Issue.UNDESERVED_LYRICS_TAG), check("lyrics", "   "))
        assertEquals(setOf(Issue.UNDESERVED_KARAOKE_TAG), check("Lyrics; karaoke", "[00:01.00]a\n[00:00.00]b").minus(Issue.UNTIMED_LINES))
        assertTrue(Issue.entries.filter { it.tagIssue }.none { it.warning })
    }

    @Test fun outOfOrderSkipsUntimed() {
        assertEquals(setOf(Issue.OUT_OF_ORDER), check("Lyrics; Karaoke", "[00:05.00]a\n[00:02.00]b"))
        assertFalse(Issue.OUT_OF_ORDER in check("Lyrics", "[00:05.00]a\n[00:00.00]b\n[00:06.00]c"))
    }

    @Test fun doubleLyricsDetected() {
        val a = "[00:10.00]a\n[00:20.00]b\n[00:30.00]c\n[00:40.00]d"
        // metadata in the middle
        assertTrue(Issue.DOUBLE_LYRICS in check("Lyrics; Karaoke", "[ar:X]\n$a\n[ar:X]\n[00:11.00]a\n[00:21.00]b"))
        assertFalse(Issue.DOUBLE_LYRICS in check("Lyrics; Karaoke", "[ar:X]\n[ti:Y]\n\n$a"))
        assertFalse(Issue.DOUBLE_LYRICS in check("Lyrics; Karaoke", "$a\n[Chorus: Drake]"))
        // two ascending runs, second one slightly shifted
        assertTrue(Issue.DOUBLE_RUNS in check("Lyrics; Karaoke", "$a\n[00:12.00]a\n[00:22.00]b\n[00:32.00]c\n[00:43.00]d"))
        // one misplaced line is plain out of order
        val glitch = "[00:01.00]a\n[00:02.00]b\n[00:03.00]c\n[00:04.00]d\n[00:05.00]e\n[05:00.00]f\n[00:07.00]g\n[00:08.00]h\n[00:09.00]i"
        assertEquals(setOf(Issue.OUT_OF_ORDER), check("Lyrics; Karaoke", glitch))
        assertEquals(setOf(Issue.OUT_OF_ORDER), check("Lyrics; Karaoke", "[00:05.00]a\n[00:02.00]b"))
    }

    private fun doubles(lyrics: String) = check("Lyrics; Karaoke", lyrics).filter { it == Issue.DOUBLE_LYRICS || it == Issue.DOUBLE_RUNS }

    @Test fun normalHeadersAndTrailersAreNotDoubleLyrics() {
        val body = "[00:10.00]one\n[00:20.00]two\n[00:30.00]three\n[00:40.00]four"
        // other tags in any order before the lyrics
        assertEquals(emptyList<Issue>(), doubles("[ti:T]\n[offset:0]\n[ar:A]\n[length:03:20]\n[al:B]\n[by:C]\n[re:tool]\n[ve:1]\n[ar:A]\n$body"))
        // credits at the end of the file
        assertEquals(emptyList<Issue>(), doubles("[ti:T]\n$body\n[by:someone]"))
        assertEquals(emptyList<Issue>(), doubles("$body\n[ar:A]\n[ti:T]\n"))
        // plain text before the header, CRLF line ends, blank lines
        assertEquals(emptyList<Issue>(), doubles("Karaoke version\r\n\r\n[ar:A]\r\n$body"))
        // a tag between lyrics that is not an identifying one
        assertEquals(emptyList<Issue>(), doubles("[00:05.00]zero\n[offset:0]\n$body"))
        // a timed line with no text doesn't count as lyrics
        assertEquals(emptyList<Issue>(), doubles("[00:00.00]\n[ar:A]\n$body"))
        assertEquals(emptyList<Issue>(), doubles("[00:01.00]\n[ar:A]\n[00:02.00]\n$body"))
    }

    @Test fun translationBlockOnTheSameTimesIsADoubleLyrics() {
        val head = "[ti:T]\n[ar:A]\n[offset:+0]\n[by:Tool]\n"
        val jp = "[00:05.00]jp1\nromaji1\n[00:10.00]jp2\nromaji2\n[00:15.00]jp3\nromaji3"
        val cn = "[by:translator]\n[00:05.00]cn1\nromaji1\n[00:10.00]cn2\nromaji2\n[00:15.00]cn3\nromaji3"
        // a second block after the song info, on the same times but another text (a translation of its own)
        val sameTimes = "$head$jp\n\n$cn"
        assertEquals(3, Lrc.secondCopyStart(sameTimes))
        assertEquals(listOf(Issue.DOUBLE_LYRICS), doubles(sameTimes))
        assertEquals(listOf(Issue.DOUBLE_LYRICS), doubles("$head$jp\n[by:x]\n[00:05.00]cn1\n[00:10.00]cn2"))
        // the same without the song info in between: two runs on the same times
        val noInfo = "$head$jp\n\n" + cn.lines().drop(1).joinToString("\n")
        assertEquals(listOf(Issue.DOUBLE_RUNS), doubles(noInfo))
        assertTrue(Issue.OUT_OF_ORDER in check("Lyrics; Karaoke", sameTimes))
        // and a second block with other times is a second sync
        assertEquals(listOf(Issue.DOUBLE_LYRICS), doubles("$head$jp\n[by:x]\n[00:06.00]cn1\n[00:11.50]cn2\n[00:16.00]cn3"))
    }

    /** Shape of "Better Off Dead": original with romanization, then a translation block with its own [by:] on the same times. */
    @Test fun songWithTranslationBlockAfterItIsNotJustOutOfOrder() {
        val times = listOf("00:13.53", "00:19.38", "00:24.17", "00:25.99", "00:29.12", "00:32.23", "00:35.25", "00:44.65")
        val original = "[ti:Song]\n[ar:Band]\n[by:Tool]\n" + times.withIndex().joinToString("\n") { (i, t) -> "[$t]original $i\nromanized $i" }
        val translation = "\n\n\n[by:someone]\n" + times.withIndex().joinToString("\n") { (i, t) -> "[$t] translated $i  \nromanized again $i" }
        val found = check("Lyrics; Karaoke", original + translation)
        assertEquals(setOf(Issue.OUT_OF_ORDER, Issue.DOUBLE_LYRICS), found)
        val lines = Lrc.parse(original + translation)
        val hint = lines[Lrc.secondCopyStart(original + translation)!!].id
        val (first, second) = Issues.splitDouble(lines, hint)!!
        assertEquals(times.size, first.size)
        assertEquals(times.size, second.size)
        assertTrue(first.all { it.text.startsWith("original") })
        assertTrue(second.all { it.text.startsWith("translated") })
    }

    @Test fun lineSungTwiceWithTheSameTimeInOneBlockIsNotAnotherBlock() {
        val song = "[ti:T]\n[00:05.00]a\n[00:05.00]b\n[00:10.00]c\n[00:15.00]d\n[by:credit]"
        assertEquals(emptyList<Issue>(), doubles(song))
    }

    @Test fun repeatedChorusWithAscendingTimesIsNotDoubleLyrics() {
        val song = (0 until 30).joinToString("\n") { i -> "[%02d:%02d.00]%s".format(i / 6, (i % 6) * 10, if (i % 5 == 0) "chorus line" else "line $i") }
        assertEquals(emptyList<Issue>(), doubles(song))
        // every line has its own timestamp, even many at the same time
        assertEquals(emptyList<Issue>(), doubles((0 until 20).joinToString("\n") { "[00:%02d.00]w$it".format(it / 2) }))
    }

    @Test fun duetWithOneSingerAfterTheOtherIsOnlyOutOfOrder() {
        val a = "[00:10.00]I walk alone\n[00:20.00]down the road\n[00:30.00]nobody knows\n[00:40.00]where I go"
        val b = "[00:12.00]you wait at home\n[00:22.00]by the door\n[00:32.00]counting stars\n[00:42.00]once more"
        assertEquals(setOf(Issue.OUT_OF_ORDER), check("Lyrics; Karaoke", "$a\n$b"))
    }

    @Test fun singleOrderGlitchesAreNotDoubleLyrics() {
        assertEquals(emptyList<Issue>(), doubles("[00:10.00]a\n[00:20.00]b\n[00:30.00]c\n[00:05.00]d\n[00:40.00]e"))
        assertEquals(emptyList<Issue>(), doubles("[00:10.00]a\n[00:20.00]b\n[00:30.00]c\n[00:05.00]a\n[00:06.00]b\n[00:07.00]c"))
        assertEquals(emptyList<Issue>(), doubles(""))
        assertEquals(emptyList<Issue>(), doubles("[00:10.00]only"))
    }

    @Test fun reportsWhichKindOfDoubleLyrics() {
        val a = "[00:10.00]a\n[00:20.00]b\n[00:30.00]c\n[00:40.00]d"
        assertEquals(listOf(Issue.DOUBLE_LYRICS), doubles("[ar:X]\n$a\n[ar:X]\n[00:11.00]a\n[00:21.00]b"))
        assertEquals(listOf(Issue.DOUBLE_RUNS), doubles("$a\n[00:12.00]a\n[00:22.00]b\n[00:32.00]c\n[00:43.00]d"))
        // a second copy that is a translation (different text) is not detected by the times alone
        assertEquals(emptyList<Issue>(), doubles("$a\n[00:12.00]x\n[00:22.00]y\n[00:32.00]z\n[00:43.00]w"))
    }

    /** The same lyrics twice, with identical timestamps in both copies. */
    @Test fun lyricsRepeatedWithTheSameTimestampsAreDoubleLyrics() {
        val body = "[00:10.00]a\n[00:20.00]b\n[00:30.00]c"
        val long = (1..8).joinToString("\n") { "[00:%02d.00]line $it".format(it * 5) }
        val doubledLines = (1..8).joinToString("\n") { "[00:%02d.00]line $it\n[00:%02d.00]line $it".format(it * 5, it * 5) }
        val cases = mapOf(
            "one copy after the other" to "$body\n$body",
            "longer song" to "$long\n$long",
            "CRLF and blank lines" to "$long\r\n\r\n$long\r\n",
            "song info repeated in between" to "[ar:X]\n[ti:Y]\n$long\n[ar:X]\n[ti:Y]\n$long",
            "short, info repeated" to "[ar:X]\n$body\n[ar:X]\n$body",
            "capitals, punctuation and pauses" to "[00:05.00]\n$long\n[00:50.00]\n" + long.uppercase() + "!",
            "every line twice in a row" to doubledLines,
            "three copies" to "$long\n$long\n$long",
        )
        val missed = cases.filter { (_, lrc) -> doubles(lrc).isEmpty() }.keys
        assertEquals(emptySet<String>(), missed)
    }

    @Test fun otherTextOnTheSameTimesIsOneMoreLyricButASungRepeatIsNot() {
        val long = (1..8).joinToString("\n") { "[00:%02d.00]line $it".format(it * 5) }
        val translated = (1..8).joinToString("\n") { "[00:%02d.00]Zeile $it".format(it * 5) }
        assertEquals(listOf(Issue.DOUBLE_RUNS), doubles("$long\n$translated"))
        assertEquals(listOf(Issue.DOUBLE_LYRICS), doubles("[ar:X]\n$long\n[ar:X]\n$translated"))
        // a line sung twice in a row is not a copy of the song
        assertEquals(emptyList<Issue>(), doubles("[00:05.00]a\n[00:05.00]a\n[00:10.00]b\n[00:15.00]c\n[00:20.00]d\n[00:25.00]e"))
    }

    @Test fun interleavedCopiesCanBeSplitForComparing() {
        val lines = (1..4).flatMap { listOf(Line(it * 2L, it * 5000L, "line $it"), Line(it * 2L + 1, it * 5000L, "line $it")) }
        val (first, second) = Issues.splitDouble(lines, null)!!
        assertEquals(listOf(2L, 4L, 6L, 8L), first.map { it.id })
        assertEquals(listOf(3L, 5L, 7L, 9L), second.map { it.id })
        // lines that have no copy are in both, so either choice is a whole song
        val withPause = listOf(Line(100, null, "")) + lines
        val (a, b) = Issues.splitDouble(withPause, null)!!
        assertEquals(100L, a.first().id)
        assertEquals(100L, b.first().id)
    }

    @Test fun countsTellHowOftenEachProblemOccurs() {
        val lrc = "[00:10.00]a\n[00:10.00]b\n[00:10.00]c\n[00:05.00]d\n[00:00.00]e\n[00:00.00]f\n[00:20.00]g"
        val c = counts("Lyrics; Karaoke", lrc)
        assertEquals(2, c[Issue.UNTIMED_LINES])
        assertEquals(1, c[Issue.OUT_OF_ORDER])
        assertEquals(2, c[Issue.SAME_TIME])
        assertEquals(check("Lyrics; Karaoke", lrc), c.keys)
        // whole-song problems count once
        assertEquals(1, counts("", "[00:01.00]a")[Issue.MISSING_LYRICS_TAG])
        assertEquals(1, counts("Learn; unsingable", null)[Issue.CONFLICTING_LEARN_TAGS])
        assertEquals(mapOf(Issue.OUT_OF_ORDER to 3), counts("Lyrics; Karaoke", "[00:30.00]a\n[00:20.00]b\n[00:10.00]c\n[00:05.00]d"))
        assertEquals(3, counts("Lyrics", "[𝟶𝟹:𝟶𝟸.𝟶𝟶]a\n[𝟶𝟹:𝟶𝟸.𝟶𝟶]b\n[𝟶𝟹:𝟶𝟸.𝟶𝟶]c")[Issue.ODD_TIMESTAMPS])
    }

    @Test fun countLabels() {
        assertEquals("untimed lines ×4", Issues.labelWithCount(Issue.UNTIMED_LINES, 4))
        assertEquals("untimed lines", Issues.labelWithCount(Issue.UNTIMED_LINES, 1))
        assertEquals("untimed lines", Issues.labelWithCount(Issue.UNTIMED_LINES, null))
        assertEquals("Lyrics tag missing", Issues.labelWithCount(Issue.MISSING_LYRICS_TAG, 5))
        val song = SongEntry("u", "f.mp3", "", "", "", emptyList(), 0, 0, issues = listOf(Issue.SAME_TIME, Issue.OUT_OF_ORDER),
            counts = mapOf(Issue.SAME_TIME to 7, Issue.OUT_OF_ORDER to 1))
        assertEquals(listOf("same timestamp twice in a row ×7", "out of order"), song.issueLabels)
    }

    @Test fun realignOffsetIsTheMiddleDifference() {
        assertEquals(0L, Realign.offset(emptyList()))
        assertEquals(1500L, Realign.offset(listOf(10_000L to 11_500L)))
        // one mistimed tap doesn't move the result
        assertEquals(2000L, Realign.offset(listOf(1000L to 3000L, 2000L to 4000L, 3000L to 5000L, 4000L to 20_000L, 5000L to 7000L)))
        assertEquals(-500L, Realign.offset(listOf(10_000L to 9_500L, 20_000L to 19_500L)))
        // even count: the mean of the two middle ones
        assertEquals(1500L, Realign.offset(listOf(0L to 1000L, 0L to 2000L)))
        assertEquals(0L, Realign.spread(listOf(0L to 1000L, 5L to 1005L), 1000))
        assertEquals(300L, Realign.spread(listOf(0L to 1000L, 0L to 1300L), 1000))
    }

    @Test fun realignShiftsEveryTimedLine() {
        val lines = listOf(Line(1, 5000, "a"), Line(2, null, "b"), Line(3, 7000, ""), Line(4, 20, "c"))
        assertEquals(listOf<Long?>(6500L, null, 8500L, 1520L), Realign.shift(lines, 1500).map { it.timeMs })
        assertEquals(listOf("a", "b", "", "c"), Realign.shift(lines, 1500).map { it.text })
        // never below the smallest real time, so lines don't turn untimed
        assertEquals(listOf<Long?>(3500L, null, 5500L, Lrc.MIN_TIME_MS), Realign.shift(lines, -1500).map { it.timeMs })
        assertEquals(lines, Realign.shift(lines, 0))
        assertEquals("+1.25 s", Realign.formatOffset(1250))
        assertEquals("−0.30 s", Realign.formatOffset(-300))
    }

    @Test fun realignTimesTheFirstLinesThatHaveATime() {
        val lines = listOf(Line(1, null, "untimed"), Line(2, 1000, ""), Line(3, 2000, "a"), Line(4, 3000, "b"), Line(5, 4000, "c"))
        assertEquals(listOf(3L, 4L), Realign.targets(lines, 2).map { it.id })
        assertEquals(listOf(3L, 4L, 5L), Realign.targets(lines, 5).map { it.id })
        assertEquals(listOf(3L), Realign.targets(lines, 0).map { it.id })
        assertEquals(emptyList<Line>(), Realign.targets(listOf(Line(1, null, "a")), 5))
    }

    @Test fun backGoesToTheLastUsedTab() {
        val h = TabHistory<String>()
        assertFalse(h.canGoBack)
        assertNull(h.back())
        // library -> editor (opened a song) -> settings -> back is the editor, then the library
        h.moved("library", "editor"); h.moved("editor", "settings")
        assertEquals("editor", h.back())
        assertEquals("library", h.back())
        assertNull(h.back())
        assertFalse(h.canGoBack)
    }

    @Test fun backSkipsTabsAlreadyVisitedAndStaysUnique() {
        val h = TabHistory<String>()
        h.moved("library", "editor"); h.moved("editor", "library"); h.moved("library", "editor"); h.moved("editor", "learn")
        assertEquals("editor", h.back())
        assertEquals("library", h.back())
        assertNull(h.back())
        h.moved("a", "a")
        assertFalse(h.canGoBack)
    }

    @Test fun splitsTwoLyrics() {
        val a = Lrc.parse("[00:10.00]a\n[00:20.00]b\n[00:30.00]c\n[00:40.00]d")
        val b = Lrc.parse("[00:12.00]a\n[00:22.00]b\n[00:32.00]c\n[00:43.00]d")
        val (first, second) = Issues.splitDouble(a + b, null)!!
        assertEquals(a, first)
        assertEquals(b, second)
        assertNull(Issues.splitDouble(a, null))
        // metadata marks where the second one starts, even when the times don't drop back
        val text = "[ar:X]\n[00:10.00]a\n[00:20.00]b\n[ar:X]\n[00:30.00]c\n[00:40.00]d"
        assertEquals(2, Lrc.secondCopyStart(text))
        val lines = Lrc.parse(text)
        val (x, y) = Issues.splitDouble(lines, lines[2].id)!!
        assertEquals(listOf("a", "b"), x.map { it.text })
        assertEquals(listOf("c", "d"), y.map { it.text })
        assertNull(Lrc.secondCopyStart("[ar:X]\n[00:10.00]a"))
    }

    @Test fun sameTimeTwiceIsAWarning() {
        assertTrue(Issue.SAME_TIME.warning)
        assertEquals(setOf(Issue.SAME_TIME), check("Lyrics; Karaoke", "[00:05.00]a\n[00:05.00]b\n[00:06.00]c"))
        assertFalse(Issue.SAME_TIME in check("Lyrics; Karaoke", "[00:05.00]a\n[00:06.00]b\n[00:05.00]c"))
        val lines = listOf(Line(1, 5000, "a"), Line(2, 5000, "b"))
        assertEquals(listOf(2L to LineProblem.SAME_TIME), Issues.lineProblems(lines))
    }

    @Test fun shortBreaksAreFoundByTheirLength() {
        val lines = Lrc.parse("[00:05.00]a\n[00:10.00]\n[00:10.20]b\n[00:15.00]\n[00:16.00]c\n[00:20.00]d\n[00:20.10]\n[00:30.00]e")
        // Only an empty line followed by a lyric line is a break: 0.2 s and 1.0 s here; the last empty line is followed by a lyric 9.9 s later.
        assertEquals(listOf(200L, 1000L), Issues.breakGaps(lines))
        assertEquals(1, Issues.shortBreaks(Issues.breakGaps(lines), 300))
        assertEquals(2, Issues.shortBreaks(Issues.breakGaps(lines), 1500))
        assertEquals(listOf(lines[1].id to LineProblem.SHORT_BREAK), Issues.lineProblems(lines, 300))
        assertEquals(listOf(200L, 1000L), Issues.breakGaps("[00:05.00]a\n[00:10.00]\n[00:10.20]b\n[00:15.00]\n[00:16.00]c"))
        assertTrue(Issues.breakGaps("plain text\n\nmore").isEmpty())
        // It depends on the setting, so it is never part of the stored result.
        assertFalse(Issue.SHORT_BREAK in check("Lyrics; Karaoke", "[00:05.00]a\n[00:10.00]\n[00:10.05]b"))
        assertTrue(Issue.SHORT_BREAK.warning)
        val entry = SongEntry("u", "f.mp3", "", "", "", emptyList(), 1, 1, breakGapsMs = listOf(200L, 1000L))
        assertEquals(listOf(Issue.SHORT_BREAK), entry.withMarks(300, emptySet(), emptySet()).issues)
        assertEquals(1, entry.withMarks(300, emptySet(), emptySet()).counts[Issue.SHORT_BREAK])
        assertEquals(2, entry.withMarks(5000, emptySet(), emptySet()).counts[Issue.SHORT_BREAK])
        assertEquals(emptyList<Issue>(), entry.withMarks(100, emptySet(), emptySet()).issues.orEmpty())
        assertEquals(listOf(Issue.BROKEN_LYRICS, Issue.BUGGY_FILE), entry.withMarks(100, setOf("u"), setOf("u")).issues)
    }

    @Test fun emptyLineAtTheStartIsAWarningAndCanBeRemoved() {
        assertTrue(Issue.LEADING_EMPTY.warning)
        assertEquals(setOf(Issue.LEADING_EMPTY), check("Lyrics; Karaoke", "[ti:T]\n[00:00.50]\n[00:05.00]a\n[00:10.00]b"))
        assertFalse(Issue.LEADING_EMPTY in check("Lyrics; Karaoke", "[ti:T]\n[00:05.00]a\n[00:08.00]\n[00:10.00]b"))
        assertEquals("[ti:T]\n[00:05.00]a\n[00:10.00]b", Lrc.removeLeadingEmpty("[ti:T]\n[00:00.50]\n\n[00:02.00]  \n[00:05.00]a\n[00:10.00]b"))
        assertEquals("[00:05.00]a", Lrc.removeLeadingEmpty("\n[00:01.00]\n[00:05.00]a"))
        assertEquals("[ar:X]\n[00:05.00]a", Lrc.removeLeadingEmpty("[00:01.00]\n[ar:X]\n[00:05.00]a"))
        assertNull(Lrc.removeLeadingEmpty("[ti:T]\n[00:05.00]a\n[00:08.00]\n[00:10.00]b"))
        // A timestamp alone on its line with its text on the next line is not empty.
        assertNull(Lrc.removeLeadingEmpty("[00:05.00]\ncontinued\n[00:10.00]b"))
        val fixed = Lrc.removeLeadingEmpty("[ti:T]\n[00:00.50]\n[00:05.00]a\n[00:10.00]b")!!
        assertFalse(Issue.LEADING_EMPTY in check("Lyrics; Karaoke", fixed))
    }

    @Test fun consecutiveEmptyLinesAreAWarning() {
        assertTrue(Issue.CONSECUTIVE_EMPTY.warning)
        val src = "[00:05.00]a\n[00:10.00]\n[00:11.00]\n[00:12.00]\n[00:20.00]b"
        assertEquals(2, counts("Lyrics; Karaoke", src)[Issue.CONSECUTIVE_EMPTY])
        assertFalse(Issue.CONSECUTIVE_EMPTY in check("Lyrics; Karaoke", "[00:05.00]a\n[00:10.00]\n[00:20.00]b\n[00:25.00]"))
        // Blank lines in the file between timed lines are not entries.
        assertFalse(Issue.CONSECUTIVE_EMPTY in check("Lyrics; Karaoke", "[00:05.00]a\n\n\n[00:20.00]b"))
    }

    @Test fun lastTimeAfterTheEndOfTheSongIsAnError() {
        assertFalse(Issue.PAST_END.warning)
        val src = "[00:05.00]a\n[03:00.00]b\n[03:10.00]c"
        assertEquals(setOf(Issue.PAST_END), Issues.checkSong(listOf("Lyrics", "Karaoke"), src, durationMs = 185_000))
        assertEquals(2, Issues.countSong(listOf("Lyrics", "Karaoke"), src, durationMs = 120_000)[Issue.PAST_END])
        // A second of slack, and nothing when the length is unknown.
        assertEquals(emptySet<Issue>(), Issues.checkSong(listOf("Lyrics", "Karaoke"), src, durationMs = 190_000))
        assertEquals(emptySet<Issue>(), Issues.checkSong(listOf("Lyrics", "Karaoke"), src, durationMs = null))
        val lines = Lrc.parse(src)
        assertEquals(listOf(lines[2].id to LineProblem.PAST_END), Issues.lineProblems(lines, durationMs = 185_000))
    }

    @Test fun creditsBlockInTheMiddleIsOutOfOrderNotTwoLyrics() {
        // OWEN 495: one lyric whose two credit lines are timed at the start but sit in the middle.
        val src = "[ti:T]\n[ar:A]\n[00:10.00]a\n[00:20.00]b\n[00:30.00]c\n[00:40.00]d\n[02:09.00]\n[00:00.84]credit 1\n[00:01.24]credit 2\n" +
            "[02:27.00]e\n[02:37.00]f\n[02:43.00]g\n[02:46.00]h\n[02:50.00]i\n[03:00.00]\n[03:09.00]j\n[03:12.00]k\n[03:17.00]l"
        assertEquals(setOf(Issue.OUT_OF_ORDER), check("Lyrics; Karaoke", src))
    }

    @Test fun mp3LengthFromTheFirstFrame() {
        val frame = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00) // MPEG1 Layer III, 128 kbit/s, 44.1 kHz, stereo
        val cbr = ByteArray(4096).also { frame.copyInto(it) }
        assertEquals(1000L, Id3.durationFromHead(cbr, cbr.size, 16000))
        assertNull(Id3.durationFromHead(cbr, cbr.size, -1))
        val xing = ByteArray(4096).also { frame.copyInto(it); "Xing".toByteArray().copyInto(it, 36); it[43] = 1; it[47] = 100 }
        assertEquals(100L * 1152 * 1000 / 44100, Id3.durationFromHead(xing, xing.size, 999999))
        assertNull(Id3.durationFromHead(ByteArray(100), 100, 5000))
        // Read through the tag reader: an ID3 header with one title frame, padding, then the audio.
        val title = byteArrayOf(3) + "Hi".toByteArray()
        val tag = "TIT2".toByteArray() + byteArrayOf(0, 0, 0, title.size.toByte(), 0, 0) + title + ByteArray(20)
        val file = byteArrayOf(0x49, 0x44, 0x33, 3, 0, 0, 0, 0, 0, tag.size.toByte()) + tag + cbr.copyOf(16000)
        val meta = Id3.readMeta(file.inputStream(), file.size.toLong())
        assertEquals("Hi", meta.title)
        assertEquals(1000L, meta.durationMs)
        assertNull(Id3.readMeta(file.inputStream()).durationMs)
    }

    @Test fun conflictingAndSylt() {
        assertEquals(setOf(Issue.CONFLICTING_LEARN_TAGS), check("Learn; unsingable", null))
        assertEquals(setOf(Issue.SYLT_ONLY), check("", null, sylt = true))
    }
}

class LyricsFetchTest {
    private val q = LyricsQuery("Gurenge", "LiSA", "Demon Slayer", 240_000)

    @Test fun lrclibPrefersSyncedAndFallsBackToPlain() {
        val json = """[
          {"trackName":"Gurenge","artistName":"LiSA","duration":240.4,"plainLyrics":"a\nb","syncedLyrics":"[ar:LiSA]\n[00:01.00]a\n[00:02.00]b"},
          {"trackName":"Gurenge","artistName":"LiSA","duration":240,"plainLyrics":"only plain","syncedLyrics":null},
          {"trackName":"Other","artistName":"X","duration":100,"instrumental":true}
        ]"""
        val r = LrclibProvider.parseList(json, q)
        assertEquals(2, r.size)
        assertTrue(r[0].synced)
        assertEquals("[00:01.00]a\n[00:02.00]b", r[0].text) // metadata line stripped
        assertFalse(r[1].synced)
        assertTrue(r[0].score > 0.9)
        assertNull(LrclibProvider.parseTrack("""{"code":404,"name":"NotFound"}""", q))
    }

    @Test fun neteaseStripsCreditsAndMergesRomanization() {
        val json = """{"lrc":{"lyric":"[00:00.000] 作词 : 某人\n[00:01.000] 作曲 : 某人\n[00:05.000]こんにちは\n[00:09.500]さようなら"},
            "romalrc":{"lyric":"[00:05.000]konnichiwa\n[00:09.500]sayounara"}}"""
        val r = NeteaseProvider.parseLyric(json, "Gurenge — LiSA", 0.9)!!
        assertTrue(r.synced)
        assertEquals("[00:05.000]こんにちは\n[00:09.500]さようなら", r.text)
        assertEquals("[00:05.00]こんにちは\nkonnichiwa\n[00:09.50]さようなら\nsayounara", r.withRomanization)
        assertTrue(Lrc.isFullyTimed(Lrc.parse(r.withRomanization!!)))
        assertNull(NeteaseProvider.parseLyric("""{"lrc":{"lyric":"[00:00.00]纯音乐，请欣赏"}}""", "x", 1.0))
        assertNull(NeteaseProvider.parseLyric("""{"lrc":{"lyric":""}}""", "x", 1.0))
    }

    @Test fun lyricsifyPageAndChallenge() {
        val html = "<html><div id='lyrics'>[00:01.00]one<br/>[00:02.00]two &amp; three<br>[00:03.00]four<br>[00:04.00]five</div></html>"
        val r = LyricsifyProvider.parseLyricsPage(html, "/lyric/lisa-gurenge.123", 1.0)!!
        assertTrue(r.synced)
        assertEquals("[00:01.00]one\n[00:02.00]two & three\n[00:03.00]four\n[00:04.00]five", r.text)
        assertNull(LyricsifyProvider.parseLyricsPage("<p>no lyrics</p>", "/x", 1.0))
        assertThrows(BlockedException::class.java) { LyricsifyProvider.checkNotChallenge(Http.Response(200, "<title>Just a moment...</title>")) }
        val links = LyricsifyProvider.candidateLinks("""<a href="/lyric/lisa-gurenge.1">a</a><a href="/lyric/other-song.2">b</a>""", q)
        assertEquals(listOf("/lyric/lisa-gurenge.1"), links.map { it.first })
    }

    private fun fake(name: String, vararg r: LyricsResult, fail: Boolean = false) = object : LyricsProvider {
        override val name = name
        override suspend fun search(q: LyricsQuery): List<LyricsResult> = if (fail) throw java.io.IOException("down") else r.toList()
    }
    private fun res(src: String, synced: Boolean, score: Double = 0.9) = LyricsResult(src, synced, "text $src", "", score)

    private fun best(vararg p: LyricsProvider) = runBlocking {
        val lists = p.map { runCatching { LyricsFetcher.searchSource(it, q) }.getOrDefault(emptyList()) }
        LyricsFetcher.best(lists)?.let { (i, j) -> lists[i][j] }
    }

    @Test fun syncedBeatsPlainAcrossProviders() {
        assertEquals("B", best(fake("A", res("A", false)), fake("B", res("B", true)))?.source)
    }

    @Test fun firstSyncedSourceWinsAndBestPlainOtherwise() {
        assertEquals("A", best(fake("A", res("A", true)), fake("B", res("B", true)))?.source)
        assertEquals("C", best(fake("A", fail = true), fake("B", res("B", false, score = 0.7)), fake("C", res("C", false, score = 0.9)))?.source)
    }

    @Test fun failedSourceThrowsButLaterVariantFailureKeepsResults() = runBlocking {
        assertThrows(java.io.IOException::class.java) { runBlocking { LyricsFetcher.searchSource(fake("A", fail = true), q) } }
        Unit
    }

    @Test fun lowScoresAndEmptyGiveNothing() {
        assertNull(best(fake("A", res("A", true, score = 0.2)), fake("B")))
        // a weak candidate is listed for choosing but never picked by itself
        val listed = runBlocking { LyricsFetcher.searchSource(fake("A", res("A", true, score = 0.5)), q) }
        assertEquals(1, listed.size)
        assertNull(LyricsFetcher.best(listOf(listed)))
    }

    @Test fun matchScoring() {
        assertTrue(LyricsMatch.score(q, "Gurenge", "LiSA", 241_000) > 0.95)
        assertTrue(LyricsMatch.score(q, "Gurenge (TV size)", "LiSA feat. X", null) >= LyricsMatch.MIN_SCORE)
        assertTrue(LyricsMatch.score(q, "Completely different", "Someone", 100_000) < LyricsMatch.MIN_SCORE)
    }
}

class ProblemLinesTest {
    private fun l(t: Long?, text: String) = Lrc.newLine(t, text)

    @Test fun findsAndCyclesProblemLines() {
        val a = l(1000, "ok"); val b = l(null, "untimed"); val c = l(5000, "fine"); val d = l(2000, "early")
        val e = l(6000, "odd [\uD835\uDFF6\uD835\uDFF9:00.00]"); val pause = l(null, "")
        val lines = listOf(a, b, c, d, e, pause)
        assertEquals(listOf(b.id to LineProblem.UNTIMED, d.id to LineProblem.OUT_OF_ORDER, e.id to LineProblem.ODD_TIMESTAMPS), Issues.lineProblems(lines))
        assertEquals(b.id, Issues.nextProblemLine(lines, null))
        assertEquals(d.id, Issues.nextProblemLine(lines, b.id))
        assertEquals(b.id, Issues.nextProblemLine(lines, e.id)) // wraps
        assertEquals(e.id, Issues.nextProblemLine(lines, a.id, LineProblem.ODD_TIMESTAMPS))
        assertNull(Issues.nextProblemLine(listOf(a, c), null))
    }
}

class MultiTimestampTest {
    private fun times(l: List<Line>) = l.map { it.timeMs }
    private fun texts(l: List<Line>) = l.map { it.text }

    @Test fun repeatedLineIsSplitAndShownInTimeOrder() {
        val src = "[00:10.00]intro\n[00:00:00] [00:50.00] [01:43:00] refrain\n[00:30.00]verse\n[02:00.00]outro"
        val lines = Lrc.parse(src)
        assertEquals(listOf("refrain", "intro", "verse", "refrain", "refrain", "outro"), texts(lines))
        assertEquals(listOf<Long?>(10L, 10_000L, 30_000L, 50_000L, 103_000L, 120_000L), times(lines))
        assertTrue(Lrc.isFullyTimed(lines))
        assertNull(Lrc.multiTimestampProblem(src))
    }

    @Test fun continuationLinesGoToEveryCopy() {
        val lines = Lrc.parse("[00:05.00][00:20.00]こんにちは\nkonnichiwa\n[00:10.00]bye\nbai")
        assertEquals(listOf<Long?>(5000L, 10_000L, 20_000L), times(lines))
        assertEquals(listOf("こんにちは\nkonnichiwa", "bye\nbai", "こんにちは\nkonnichiwa"), texts(lines))
    }

    @Test fun sortIsStableAndPausesStayBehindTheirLine() {
        val lines = Lrc.parse("[00:20.00][00:10.00]a\n[00:10.00]b\n[00:15.00]\n[00:30.00]c")
        assertEquals(listOf("a", "b", "", "a", "c"), texts(lines))
        assertEquals(listOf<Long?>(10_000L, 10_000L, 15_000L, 20_000L, 30_000L), times(lines))
    }

    @Test fun serializedResultIsOrderedAndStable() {
        val once = Lrc.serialize(Lrc.parse("[00:30.00][00:10.00]x\n[00:20.00]y"))
        assertEquals("[00:10.00]x\n[00:20.00]y\n[00:30.00]x", once)
        assertEquals(once, Lrc.serialize(Lrc.parse(once)))
    }

    @Test fun partlyTimedSongWithMultiTagLineIsAnError() {
        val src = "[00:10.00][00:50.00]chorus\n[00:20.00]verse\nuntimed line before nothing"
        // continuation text belongs to the verse, so this one is fully timed; add a real untimed entry:
        assertNull(Lrc.multiTimestampProblem(src))
        val bad = "plain first line\n[00:10.00][00:50.00]chorus\n[00:20.00]verse"
        assertNotNull(Lrc.multiTimestampProblem(bad))
        val zero = "[00:10.00]a\n[00:00.00]placeholder\n[00:20.00][00:40.00]b"
        assertNotNull(Lrc.multiTimestampProblem(zero))
        assertTrue(Issue.UNREADABLE_LYRICS in Issues.checkSong(listOf("Lyrics"), bad))
        // still returns best-effort entries in file order
        assertEquals(listOf("plain first line", "chorus", "chorus", "verse"), texts(Lrc.parse(bad)))
    }

    @Test fun singleTimestampSongsAreNeverReordered() {
        val lines = Lrc.parse("[00:20.00]b\n[00:10.00]a")
        assertEquals(listOf("b", "a"), texts(lines))
        assertNull(Lrc.multiTimestampProblem("[00:20.00]b\n[00:10.00]a"))
        assertNull(Lrc.multiTimestampProblem("just words"))
    }

    @Test fun onlyLeadingTagsAreTimes() {
        val lines = Lrc.parse("[00:01.00]hello [00:02.00]world\n[00:05.00]end")
        assertEquals(listOf<Long?>(1000L, 5000L), times(lines))
        assertEquals("hello world", lines[0].text)
        // tags are also accepted with spaces and ":" before the fraction
        assertEquals(listOf<Long?>(1000L, 2000L), times(Lrc.parse("[00:01:00]  [00:02.00]x")))
    }

    @Test fun rawLyricsTextReadsTheStoredFrame() {
        val audio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0)
        val withLyrics = Id3.embedLyrics(audio, "[00:01.00][00:02.00]x")
        assertEquals("[00:01.00][00:02.00]x", Id3.rawLyricsText(Id3.parse(withLyrics)))
        assertNull(Id3.rawLyricsText(Id3.parse(audio)))
    }
}

class Id3CompatTest {
    private val audio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0, 1, 2, 3, 4)

    private fun syncsafe(n: Int) = byteArrayOf(((n shr 21) and 0x7F).toByte(), ((n shr 14) and 0x7F).toByte(), ((n shr 7) and 0x7F).toByte(), (n and 0x7F).toByte())
    private fun frame24(id: String, p: ByteArray) = id.toByteArray() + syncsafe(p.size) + byteArrayOf(0, 0) + p

    /** A v2.4 tag: syncsafe frame sizes (one over 127 bytes, so syncsafe != plain big-endian), UTF-8 text. */
    private fun v24File(): Pair<ByteArray, ByteArray> {
        val long = byteArrayOf(3) + "x".repeat(200).toByteArray()
        val body = frame24("TIT2", byteArrayOf(3) + "Söng".toByteArray()) + frame24("TXXX", long) + frame24("TIT1", byteArrayOf(3) + "Anime".toByteArray())
        return (byteArrayOf(0x49, 0x44, 0x33, 4, 0, 0) + syncsafe(body.size) + body + audio) to long
    }

    @Test fun v24SourceKeepsFramesAndAudioWhenRewritten() {
        val (f, long) = v24File()
        val out = Id3.update(f, "[00:01.00]a", listOf("Anime", "Lyrics", "Karaoke"))
        val p = Id3.parse(out)
        assertEquals(3, out[3].toInt()) // written as v2.3
        assertEquals(listOf("TIT2", "TXXX", "GRP1", "USLT"), p.frames.map { it.id })
        assertArrayEquals(long, p.frames[1].data)
        assertArrayEquals(audio, out.copyOfRange(p.audioStart, out.size))
        val m = Id3.readMeta(out.inputStream())
        assertEquals("Söng", m.title)
        assertEquals(listOf("Anime", "Lyrics", "Karaoke"), Search.parseTags(m.grouping))
        assertEquals("[00:01.00]a", m.lyricsText)
    }

    @Test fun v24MultiValueGroupingIsMergedOnRewrite() {
        val body = frame24("TIT1", byteArrayOf(3) + "Anime\u0000Cover".toByteArray())
        val f = byteArrayOf(0x49, 0x44, 0x33, 4, 0, 0) + syncsafe(body.size) + body + audio
        assertEquals(listOf("Anime", "Cover"), Search.parseTags(Id3.readMeta(f.inputStream()).grouping))
        val out = Id3.setGrouping(f, Search.withLearnState(listOf("Anime", "Cover"), LearnState.LEARN))
        assertEquals(listOf("Anime", "Cover", "Learn"), Search.parseTags(Id3.readMeta(out.inputStream()).grouping))
    }

    @Test fun syltTimeZeroBecomesARealTime() {
        val payload = byteArrayOf(0) + "eng".toByteArray() + byteArrayOf(2, 1, 0) +
            "a".toByteArray() + byteArrayOf(0, 0, 0, 0, 0) + "b".toByteArray() + byteArrayOf(0, 0, 0, 0x03, 0xE8.toByte())
        val f = byteArrayOf(0x49, 0x44, 0x33, 3, 0, 0, 0, 0, 0, (payload.size + 10).toByte()) +
            "SYLT".toByteArray() + byteArrayOf(0, 0, 0, payload.size.toByte(), 0, 0) + payload + audio
        val lines = Id3.extractLines(Id3.parse(f))
        assertEquals(listOf<Long?>(10L, 1000L), lines.map { it.timeMs })
        assertTrue(Lrc.isFullyTimed(lines))
        val m = Id3.readMeta(f.inputStream())
        assertTrue(m.syltOnly)
        assertEquals(LyricsState.SYNCED, m.lyrics)
    }

    @Test fun rewriteIsIdempotent() {
        val (f, _) = v24File()
        val once = Id3.update(f, "[00:01.00]a", listOf("Anime", "Lyrics"))
        val twice = Id3.update(once, "[00:01.00]a", listOf("Anime", "Lyrics"))
        assertArrayEquals(once, twice)
    }
}

class SettingsAndIndexTest {
    @Test fun lyricsSourceSettingsRoundTrip() {
        val custom = listOf(SourceSetting(LyricsSource.NETEASE, true), SourceSetting(LyricsSource.LRCLIB, false), SourceSetting(LyricsSource.LYRICSIFY, true)) +
            listOf(LyricsSource.QQMUSIC, LyricsSource.LRCAPI, LyricsSource.LYRICS_OVH).map { SourceSetting(it, true) }
        assertEquals(custom, parseSources(formatSources(custom)))
    }

    @Test fun lyricsSourceSettingsToleratesJunkAndNewSources() {
        assertEquals(LyricsSource.entries.toList(), parseSources(null).map { it.source })
        val p = parseSources("bogus:1,netease:0,netease:1,,lrclib")
        assertEquals(listOf(LyricsSource.NETEASE, LyricsSource.LRCLIB, LyricsSource.LYRICSIFY, LyricsSource.QQMUSIC, LyricsSource.LRCAPI, LyricsSource.LYRICS_OVH), p.map { it.source })
        assertEquals(listOf(false, true, true, true, true, true), p.map { it.enabled }) // missing flag = enabled, first duplicate wins
    }

    private val codec = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    @Test fun oldLibraryIndexStillLoads() {
        val old = """{"root":"r","builtAt":1,"songs":[{"uri":"u","fileName":"a.mp3","title":"t","artist":"","album":"","tags":["x"],"size":1,"modified":2,"lyrics":"SYNCED"}]}"""
        val idx = codec.decodeFromString(LibraryIndex.serializer(), old)
        assertNull(idx.songs[0].issues) // null forces a re-read on the next scan
        val legacy = old.replace(""""lyrics":"SYNCED"""", """"lyrics":"SYNCED","issues":["TAG_MISMATCH","UNTIMED_LINES"],"untimed":3""")
        assertEquals(listOf(Issue.TAG_MISMATCH, Issue.UNTIMED_LINES), codec.decodeFromString(LibraryIndex.serializer(), legacy).songs[0].issues)
    }

    @Test fun searchPrefsWithNewModeRoundTrip() {
        val p = SearchPrefs(mode = SongsMode.ATTENTION)
        val back = codec.decodeFromString(SearchPrefs.serializer(), codec.encodeToString(SearchPrefs.serializer(), p))
        assertEquals(SongsMode.ATTENTION, back.mode)
    }
}

class HttpErrorTest {
    @Test fun blockedAndOtherStatuses() {
        Http.requireOk(Http.Response(200, ""), "x")
        assertThrows(BlockedException::class.java) { Http.requireOk(Http.Response(403, ""), "x") }
        assertThrows(BlockedException::class.java) { Http.requireOk(Http.Response(429, ""), "x") }
        val e = assertThrows(java.io.IOException::class.java) { Http.requireOk(Http.Response(500, ""), "x") }
        assertFalse(e is BlockedException)
    }

    @Test fun malformedAnswersAreIoErrorsNotCrashes() {
        val q = LyricsQuery("a", "b", "", null)
        assertThrows(java.io.IOException::class.java) { LrclibProvider.parseList("<html>nope</html>", q) }
        assertNull(LrclibProvider.parseTrack("not json", q))
        assertThrows(java.io.IOException::class.java) { NeteaseProvider.parseLyric("<html>", "x", 1.0) }
    }
}
