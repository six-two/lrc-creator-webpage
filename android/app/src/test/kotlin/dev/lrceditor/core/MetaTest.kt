package dev.lrceditor.core

import dev.lrceditor.data.lyrics.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MetaTest {
    private val audio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0) + ByteArray(400)
    private fun ids(b: ByteArray) = Id3.parse(b).frames.map { it.id }
    private fun meta(b: ByteArray) = Id3.readMeta(b.inputStream(), b.size.toLong())

    @Test fun newGroupingGoesToGrp1NotTit1() {
        val out = Id3.update(audio, "[00:01.00]a", listOf("Learn", "Lyrics"))
        assertTrue("GRP1" in ids(out))
        assertFalse("TIT1" in ids(out))
        assertEquals("Learn; Lyrics", meta(out).grouping)
        assertFalse(meta(out).hasWork)
    }

    @Test fun lyricsOnlyChangeKeepsExistingTit1AsLongAsTagsAreTheSame() {
        val old = Id3.update(audio, null, null, other = mapOf("TIT1" to "Learn; Lyrics"))
        assertEquals("Learn; Lyrics", meta(old).grouping) // fallback for files an older version wrote
        assertTrue(meta(old).hasWork)
        val same = Id3.update(old, "[00:01.00]a", listOf("Learn", "Lyrics"))
        assertTrue("TIT1" in ids(same)); assertFalse("GRP1" in ids(same))
        val changed = Id3.update(old, null, listOf("Learn"))
        assertTrue("GRP1" in ids(changed)); assertFalse("TIT1" in ids(changed))
        assertEquals("Learn", meta(changed).grouping)
        assertFalse(meta(changed).hasWork)
    }

    @Test fun realWorkIsNotGroupingWhenGrp1Exists() {
        val f = Id3.update(Id3.update(audio, null, null, other = mapOf("TIT1" to "Symphony 5")), null, listOf("Anime"))
        // the TIT1 was the only grouping, so it is replaced; now add a genuine work next to an existing GRP1
        val g = Id3.update(f, null, null, other = mapOf("TIT1" to "Symphony 5"))
        assertEquals("Anime", meta(g).grouping)
        assertTrue(meta(g).hasWork)
        val h = Id3.update(g, null, listOf("Anime", "Cover"))
        assertEquals("Anime; Cover", meta(h).grouping)
        assertEquals("Symphony 5", Id3.textOf(Id3.parse(h), "TIT1"))
    }

    @Test fun workFieldIsAWarning() {
        assertTrue(Issue.WORK_FIELD in Issues.countSong(emptyList(), null, hasWork = true))
        assertFalse(Issue.WORK_FIELD in Issues.countSong(emptyList(), null))
        assertTrue(Issue.WORK_FIELD.warning)
    }

    @Test fun metadataRoundTripAndConvertWork() {
        var f = Id3.update(audio, null, null, other = mapOf("TIT2" to "Song", "TPE1" to "Band", "TALB" to "Rec", "TIT1" to "Learn; Anime"))
        val orig = MetaFields.read(f)
        assertEquals("Song", orig[MetaField.TITLE]); assertEquals("Learn; Anime", orig[MetaField.WORK])
        val edited = MetaFields.convertWork(orig + (MetaField.GROUPING to "Cover") + (MetaField.GENRE to "Rock") + (MetaField.COMMENT to "hi"))
        assertEquals("Cover; Learn; Anime", edited[MetaField.GROUPING]); assertEquals("", edited[MetaField.WORK])
        f = MetaFields.write(f, orig, edited)
        val back = MetaFields.read(f)
        assertEquals("Cover; Learn; Anime", back[MetaField.GROUPING])
        assertEquals("", back[MetaField.WORK])
        assertEquals("Rock", back[MetaField.GENRE]); assertEquals("hi", back[MetaField.COMMENT])
        assertEquals("Song", back[MetaField.TITLE]); assertEquals("Band", back[MetaField.ARTIST])
        assertFalse(meta(f).hasWork)
        // clearing a field removes the frame; untouched fields stay
        val cleared = MetaFields.write(f, back, back + (MetaField.GENRE to "") + (MetaField.COMMENT to ""))
        assertFalse("TCON" in ids(cleared)); assertFalse("COMM" in ids(cleared)); assertTrue("TIT2" in ids(cleared))
        assertSame(f, MetaFields.write(f, back, back))
        // the audio is intact
        assertArrayEquals(audio, cleared.copyOfRange(Id3.parse(cleared).audioStart, cleared.size))
    }

    @Test fun removeConsecutiveEmpty() {
        val src = "[ar:x]\n[00:01.00]a\n[00:02.00]\n[00:03.00]\n[00:04.00]\n[00:05.00]b\n[00:06.00]\n[00:07.00]c"
        assertEquals("[ar:x]\n[00:01.00]a\n[00:02.00]\n[00:05.00]b\n[00:06.00]\n[00:07.00]c", Lrc.removeConsecutiveEmpty(src))
        assertNull(Lrc.removeConsecutiveEmpty("[00:01.00]a\n[00:02.00]\n[00:03.00]b"))
        // an empty timestamp followed by its text line is not empty
        assertNull(Lrc.removeConsecutiveEmpty("[00:01.00]\n[00:02.00]\ntext"))
        val fixed = Lrc.removeConsecutiveEmpty(src)!!
        assertFalse(Issues.countSong(emptyList(), fixed).containsKey(Issue.CONSECUTIVE_EMPTY))
    }

    @Test fun undoJumpTarget() {
        assertEquals(0L, Realign.undoJumpTarget(null, 50_000, 3000))
        assertEquals(47_000L, Realign.undoJumpTarget(10_000, 50_000, 3000)) // at most 3 s back
        assertEquals(49_000L, Realign.undoJumpTarget(49_000, 50_000, 3000)) // the line before is nearer
        assertEquals(500L, Realign.undoJumpTarget(500, 1000, 3000))
    }

    private val q = LyricsQuery("Song (feat. Guest) - Remastered 2011", "Band feat. Guest", "Album", 200_000)

    @Test fun queryVariants() {
        assertEquals("Song", LyricsQueries.cleanTitle(q.title))
        assertEquals("Band", LyricsQueries.firstArtist("Band feat. Guest"))
        assertEquals("Band", LyricsQueries.firstArtist("Band; Other"))
        assertEquals("Simon and Garfunkel", LyricsQueries.firstArtist("Simon and Garfunkel"))
        assertEquals("(I Can't Get No) Satisfaction".let { "Satisfaction" }, LyricsQueries.cleanTitle("(I Can't Get No) Satisfaction"))
        val v = LyricsQueries.variants(q)
        assertEquals(2, v.size); assertEquals("Song", v[1].title); assertEquals("", v[1].album)
        assertEquals(1, LyricsQueries.variants(LyricsQuery("Song", "Band", "", null)).size)
        val file = LyricsQueries.variants(LyricsQuery("Band - Song", "", "", null))
        assertTrue(file.any { it.artist == "Band" && it.title == "Song" })
    }

    private fun provider(name: String, f: (LyricsQuery) -> List<LyricsResult>) = object : LyricsProvider {
        override val name = name
        override suspend fun search(q: LyricsQuery) = f(q)
    }

    @Test fun cleanedQueryIsTriedWhenTheExactOneFindsNothing() = runBlocking {
        val asked = ArrayList<String>()
        val p = provider("A") { x -> asked += x.title; if (x.title == "Song") listOf(LyricsResult("A", true, "[00:01.00]x", "", 0.9)) else emptyList() }
        val found = LyricsFetcher.searchSource(p, q)
        assertEquals(listOf("Song (feat. Guest) - Remastered 2011", "Song"), asked)
        assertTrue(found.single().synced)
    }

    @Test fun candidatesAreSortedSyncedFirstThenByScoreAndBestPicksAcrossSources() = runBlocking {
        val p = provider("A") { listOf(
            LyricsResult("A", false, "plain", "p", 0.95), LyricsResult("A", true, "[00:01.00]low", "l", 0.7), LyricsResult("A", true, "[00:01.00]high", "h", 0.9),
        ) }
        val a = LyricsFetcher.searchSource(p, LyricsQuery("Song", "Band", "", null))
        assertEquals(listOf("h", "l", "p"), a.map { it.matchInfo })
        val plainOnly = listOf(LyricsResult("A", false, "x", "x", 0.7))
        val plainBetter = listOf(LyricsResult("B", false, "y", "y", 0.95))
        assertEquals(1 to 0, LyricsFetcher.best(listOf(plainOnly, plainBetter)))
        assertEquals(1 to 0, LyricsFetcher.best(listOf(plainBetter + plainOnly, listOf(LyricsResult("B", true, "[00:01.00]z", "z", 0.8)))))
    }

    @Test fun newProvidersParse() {
        val qq = """{"data":{"song":{"list":[{"songmid":"m1","songname":"Song","singer":[{"name":"Band"}],"interval":200}]}}}"""
        val c = QqMusicProvider.parseSearch(qq, LyricsQuery("Song", "Band", "", 200_000))
        assertEquals("m1", c[0].mid); assertTrue(c[0].score > 0.95)
        val lyric = """{"lyric":"[ti:Song]\n[00:00.00]Song - Band\n[00:01.00]Written by：Someone\n[00:02.00]Producer：X\n[00:05.00]Line one\n[00:09.00]Line two"}"""
        val r = QqMusicProvider.parseLyric(lyric, "i", 0.9)!!
        assertEquals("[00:05.00]Line one\n[00:09.00]Line two", r.text)
        assertNull(QqMusicProvider.parseLyric("""{"lyric":""}""", "i", 1.0))
        val api = LrcApiProvider.parse("[Verse]\n[00:04.89]Yesterday\n[00:08.01]All my troubles\n", "i")!!
        assertTrue(api.synced); assertEquals("[00:04.89]Yesterday\n[00:08.01]All my troubles", api.text)
        assertFalse(LrcApiProvider.parse("Just words\nmore words", "i")!!.synced)
        assertNull(LrcApiProvider.parse("<html>", "i"))
        val ovh = LyricsOvhProvider.parseLyrics("""{"lyrics":"a\r\nb"}""", "i", 0.9)!!
        assertFalse(ovh.synced); assertEquals("a\nb", ovh.text)
        val sug = LyricsOvhProvider.parseSuggest("""{"data":[{"title":"Song","title_short":"Song","duration":200,"artist":{"name":"Band"}}]}""", LyricsQuery("Song", "Band", "", 200_000))
        assertEquals("Band", sug[0].artist)
    }
}
