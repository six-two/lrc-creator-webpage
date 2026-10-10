package dev.lrceditor.core

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

    @Test fun untimedLinesGetPrecedingTimestamp() {
        val lines = listOf(Lrc.newLine(5000, "a"), Lrc.newLine(null, "b"))
        assertEquals("[00:05.00]a\n[00:05.00]b", Lrc.serialize(lines))
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
