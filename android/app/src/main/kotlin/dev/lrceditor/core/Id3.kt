package dev.lrceditor.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset

/** Port of the ID3v2 reader/writer from lrc-editor.html. Only USLT/SYLT are touched; other frames pass through. */
object Id3 {
    class Frame(val id: String, val data: ByteArray)
    class Parsed(val present: Boolean, val audioStart: Int, val frames: List<Frame>)
    class Uslt(val language: String, val descriptor: String, val text: String)
    class SyltLine(val timeMs: Long, val text: String)
    class Sylt(val language: String, val timestampFormat: Int, val contentType: Int, val lines: List<SyltLine>)

    private fun u(b: ByteArray, i: Int): Int = if (i in b.indices) b[i].toInt() and 0xFF else 0

    private fun readSyncsafe(b: ByteArray, o: Int) =
        ((u(b, o) and 0x7F) shl 21) or ((u(b, o + 1) and 0x7F) shl 14) or
            ((u(b, o + 2) and 0x7F) shl 7) or (u(b, o + 3) and 0x7F)

    private fun readU32BE(b: ByteArray, o: Int): Long =
        (u(b, o).toLong() shl 24) or (u(b, o + 1).toLong() shl 16) or (u(b, o + 2).toLong() shl 8) or u(b, o + 3).toLong()

    fun parse(bytes: ByteArray): Parsed {
        if (bytes.size < 10 || bytes[0] != 0x49.toByte() || bytes[1] != 0x44.toByte() || bytes[2] != 0x33.toByte()) {
            return Parsed(false, 0, emptyList())
        }
        val major = u(bytes, 3)
        val flags = u(bytes, 5)
        val size = readSyncsafe(bytes, 6)
        val headerLen = 10
        val footerLen = if (flags and 0x10 != 0) 10 else 0
        val audioStart = minOf(headerLen + size + footerLen, bytes.size)

        var framesStart = headerLen
        if (flags and 0x40 != 0) { // extended header: skip it
            framesStart += if (major >= 4) readSyncsafe(bytes, framesStart)
            else (4 + readU32BE(bytes, framesStart)).toInt()
        }
        val regionEnd = minOf(headerLen + size, bytes.size)

        var buf = bytes
        var pos = framesStart
        var end = regionEnd
        if (flags and 0x80 != 0 && major < 4) { // undo tag-level unsynchronisation
            val out = ByteArrayOutputStream()
            var i = framesStart
            while (i < regionEnd) {
                out.write(bytes[i].toInt())
                if (bytes[i] == 0xFF.toByte() && i + 1 < regionEnd && bytes[i + 1] == 0.toByte()) i++
                i++
            }
            buf = out.toByteArray()
            pos = 0
            end = buf.size
        }

        val frames = mutableListOf<Frame>()
        while (pos + 10 <= end) {
            if (buf[pos] == 0.toByte() && buf[pos + 1] == 0.toByte() && buf[pos + 2] == 0.toByte() && buf[pos + 3] == 0.toByte()) break
            val id = String(buf, pos, 4, Charsets.ISO_8859_1)
            val frameSize = if (major >= 4) readSyncsafe(buf, pos + 4).toLong() else readU32BE(buf, pos + 4)
            val dataStart = pos + 10
            if (frameSize < 0 || dataStart + frameSize > end) break
            frames += Frame(id, buf.copyOfRange(dataStart, dataStart + frameSize.toInt()))
            pos = dataStart + frameSize.toInt()
        }
        return Parsed(true, audioStart, frames)
    }

    internal fun decodeText(bytes: ByteArray, enc: Int): String {
        val text = when (enc) {
            0 -> String(bytes, Charsets.ISO_8859_1)
            1 -> when {
                bytes.size >= 2 && u(bytes, 0) == 0xFF && u(bytes, 1) == 0xFE -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
                bytes.size >= 2 && u(bytes, 0) == 0xFE && u(bytes, 1) == 0xFF -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
                else -> String(bytes, Charsets.UTF_16LE)
            }
            2 -> String(bytes, Charsets.UTF_16BE)
            else -> String(bytes, Charsets.UTF_8)
        }
        return text.trimEnd('\u0000')
    }

    private class Split(val descriptor: String, val textBytes: ByteArray)

    private fun splitDescriptorAndText(b: ByteArray, enc: Int): Split {
        val wide = enc == 1 || enc == 2
        var i = 0
        if (wide) {
            if (b.size >= 2 && ((u(b, 0) == 0xFF && u(b, 1) == 0xFE) || (u(b, 0) == 0xFE && u(b, 1) == 0xFF))) i = 2
            while (i + 1 < b.size && !(b[i] == 0.toByte() && b[i + 1] == 0.toByte())) i += 2
            return Split(decodeText(b.copyOfRange(0, minOf(i, b.size)), enc), b.copyOfRange(minOf(i + 2, b.size), b.size))
        }
        while (i < b.size && b[i] != 0.toByte()) i++
        return Split(decodeText(b.copyOfRange(0, i), enc), b.copyOfRange(minOf(i + 1, b.size), b.size))
    }

    private fun lang(d: ByteArray) = String(d, 1, 3, Charsets.ISO_8859_1)

    fun parseUslt(d: ByteArray): Uslt {
        val enc = u(d, 0)
        val split = splitDescriptorAndText(d.copyOfRange(4, d.size), enc)
        return Uslt(lang(d), split.descriptor, decodeText(split.textBytes, enc))
    }

    fun parseSylt(d: ByteArray): Sylt {
        val enc = u(d, 0)
        val fmt = u(d, 4)
        val type = u(d, 5)
        var rest = splitDescriptorAndText(d.copyOfRange(6, d.size), enc).textBytes
        val wide = enc == 1 || enc == 2
        val lines = mutableListOf<SyltLine>()
        while (rest.isNotEmpty()) {
            var i = 0
            val textPart: ByteArray
            val after: ByteArray
            if (wide) {
                if (rest.size >= 2 && ((u(rest, 0) == 0xFF && u(rest, 1) == 0xFE) || (u(rest, 0) == 0xFE && u(rest, 1) == 0xFF))) i = 2
                while (i + 1 < rest.size && !(rest[i] == 0.toByte() && rest[i + 1] == 0.toByte())) i += 2
                if (i + 1 >= rest.size) break
                textPart = rest.copyOfRange(0, i); after = rest.copyOfRange(i + 2, rest.size)
            } else {
                while (i < rest.size && rest[i] != 0.toByte()) i++
                if (i >= rest.size) break
                textPart = rest.copyOfRange(0, i); after = rest.copyOfRange(i + 1, rest.size)
            }
            if (after.size < 4) break
            lines += SyltLine(readU32BE(after, 0), decodeText(textPart, enc))
            rest = after.copyOfRange(4, after.size)
        }
        return Sylt(lang(d), fmt, type, lines)
    }

    /** [lyricsText] is the first non-blank USLT text (null if none); [syltOnly] = synced frame but no readable USLT. */
    class Meta(
        val title: String, val artist: String, val album: String, val grouping: String, val lyrics: LyricsState,
        val lyricsText: String? = null, val syltOnly: Boolean = false,
        /** Length of the audio, worked out from the first MPEG frame (Xing/VBRI frame count, else the constant bitrate). Null if unknown. */
        val durationMs: Long? = null,
        /** The file has a Work (TIT1) frame. This app stores the grouping in GRP1, so TIT1 usually means a grouping written by an older version. */
        val hasWork: Boolean = false,
    )

    private val META_FRAMES = setOf("TIT2", "TPE1", "TALB", "TIT1", "GRP1", "USLT")

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) return false
            n += r
        }
        return true
    }

    private fun skipFully(input: InputStream, count: Long): Boolean {
        var left = count
        while (left > 0) {
            val s = input.skip(left)
            if (s > 0) left -= s
            else if (input.read() < 0) return false else left--
        }
        return true
    }

    /**
     * Reads title/artist/album/grouping from the start of a stream without loading the whole tag
     * (album art frames are skipped, not read). The grouping is the iTunes GRP1 frame; a file without one (older versions of this
     * app wrote TIT1) falls back to TIT1, which is really the "Work".
     */
    fun readMeta(input: InputStream, fileSize: Long = -1): Meta {
        val empty = Meta("", "", "", "", LyricsState.NONE)
        val header = ByteArray(10)
        if (!readFully(input, header)) return empty
        if (header[0] != 0x49.toByte() || header[1] != 0x44.toByte() || header[2] != 0x33.toByte()) return empty
        val major = u(header, 3)
        val flags = u(header, 5)
        val size = readSyncsafe(header, 6)
        if (major !in 3..4 || size <= 0) return empty

        if (flags and 0xC0 != 0) { // unsynchronisation or extended header: take the simple, slower path
            val region = ByteArray(size)
            val whole = readFully(input, region)
            return metaFromFrames(parse(header + region).frames, if (whole) audioDuration(input, 10L + size, fileSize) else null)
        }
        val frames = mutableListOf<Frame>()
        val fh = ByteArray(10)
        var consumed = 0
        // Only when the tag was read up to its padding is the stream positioned so that the audio can be found after it.
        var positioned = true
        while (consumed + 10 <= size) {
            if (!readFully(input, fh)) { positioned = false; break }
            consumed += 10
            if (fh[0] == 0.toByte()) break
            val id = String(fh, 0, 4, Charsets.ISO_8859_1)
            val fs = if (major >= 4) readSyncsafe(fh, 4) else readU32BE(fh, 4).toInt()
            if (fs < 0 || consumed + fs > size) { positioned = false; break }
            if (id == "SYLT") { // only its presence matters
                frames += Frame(id, ByteArray(0))
                if (!skipFully(input, fs.toLong())) { positioned = false; break }
            } else if (id in META_FRAMES && fs <= (if (id == "USLT") 1 shl 20 else 65536)) {
                val d = ByteArray(fs)
                if (!readFully(input, d)) { positioned = false; break }
                frames += Frame(id, d)
            } else if (!skipFully(input, fs.toLong())) { positioned = false; break }
            consumed += fs
        }
        val audioStart = 10L + size + (if (flags and 0x10 != 0) 10 else 0)
        val duration = if (positioned && skipFully(input, audioStart - 10 - consumed)) audioDuration(input, audioStart, fileSize) else null
        return metaFromFrames(frames, duration)
    }

    private val MPEG1_L3_KBPS = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val MPEG2_L3_KBPS = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
    private val MPEG1_RATES = intArrayOf(44100, 48000, 32000)

    /** Reads the first bytes of the audio from [input] (positioned at its start) and estimates its length; see [durationFromHead]. */
    private fun audioDuration(input: InputStream, audioStart: Long, fileSize: Long): Long? {
        val head = ByteArray(4096)
        var n = 0
        while (n < head.size) {
            val r = runCatching { input.read(head, n, head.size - n) }.getOrDefault(-1)
            if (r < 0) break
            n += r
        }
        return durationFromHead(head, n, if (fileSize > audioStart) fileSize - audioStart else -1)
    }

    /**
     * Length of an MP3 from the first Layer III frame in [head] (the first [n] bytes of the audio): a Xing/Info or VBRI header
     * gives the exact frame count; without one the file is taken as constant bitrate over [audioBytes]. Null if not found.
     */
    internal fun durationFromHead(head: ByteArray, n: Int, audioBytes: Long): Long? {
        for (i in 0 until n - 4) {
            if (u(head, i) != 0xFF || u(head, i + 1) and 0xE0 != 0xE0) continue
            val version = (u(head, i + 1) shr 3) and 3 // 0 = 2.5, 2 = 2, 3 = 1
            val layer = (u(head, i + 1) shr 1) and 3 // 1 = Layer III
            val bitrateIdx = u(head, i + 2) shr 4
            val rateIdx = (u(head, i + 2) shr 2) and 3
            if (version == 1 || layer != 1 || bitrateIdx == 0 || bitrateIdx == 15 || rateIdx == 3) continue
            val mpeg1 = version == 3
            val rate = MPEG1_RATES[rateIdx] / (if (mpeg1) 1 else if (version == 2) 2 else 4)
            val samples = if (mpeg1) 1152 else 576
            val mono = (u(head, i + 3) shr 6) == 3
            val xing = i + 4 + (if (mpeg1) (if (mono) 17 else 32) else (if (mono) 9 else 17))
            val tag = if (xing + 12 <= n) String(head, xing, 4, Charsets.ISO_8859_1) else ""
            if ((tag == "Xing" || tag == "Info") && u(head, xing + 7) and 1 != 0) {
                val frames = readU32BE(head, xing + 8)
                if (frames > 0) return frames * samples * 1000 / rate
            }
            val vbri = i + 36
            if (vbri + 18 <= n && String(head, vbri, 4, Charsets.ISO_8859_1) == "VBRI") {
                val frames = readU32BE(head, vbri + 14)
                if (frames > 0) return frames * samples * 1000 / rate
            }
            val kbps = (if (mpeg1) MPEG1_L3_KBPS else MPEG2_L3_KBPS)[bitrateIdx]
            return if (audioBytes > 0) audioBytes * 8 / kbps else null
        }
        return null
    }

    private fun usltTexts(frames: List<Frame>) = frames.filter { it.id == "USLT" }
        .mapNotNull { runCatching { parseUslt(it.data).text }.getOrNull() }.filter { it.isNotBlank() }

    private fun lyricsState(frames: List<Frame>): LyricsState {
        if (frames.any { it.id == "SYLT" }) return LyricsState.SYNCED
        val texts = usltTexts(frames)
        return when {
            texts.isEmpty() -> LyricsState.NONE
            texts.any { Lrc.looksLikeLrc(it) } -> LyricsState.SYNCED
            else -> LyricsState.UNSYNCED
        }
    }

    /** The non-empty values of the text frames [id]; v2.4 multi-values are NUL separated. */
    private fun textValues(frames: List<Frame>, id: String): List<String> = frames.filter { it.id == id && it.data.size > 1 }.flatMap {
        decodeText(it.data.copyOfRange(1, it.data.size), u(it.data, 0)).split('\u0000')
    }.map { it.trim() }.filter { it.isNotEmpty() }

    /** The grouping: GRP1, or when there is none the TIT1 an older version of this app wrote it to. */
    private fun groupingValues(frames: List<Frame>): List<String> =
        textValues(frames, "GRP1").ifEmpty { textValues(frames, "TIT1") }

    private fun metaFromFrames(frames: List<Frame>, durationMs: Long? = null): Meta {
        fun values(id: String) = textValues(frames, id)
        return Meta(
            values("TIT2").firstOrNull().orEmpty(),
            values("TPE1").firstOrNull().orEmpty(),
            values("TALB").firstOrNull().orEmpty(),
            groupingValues(frames).joinToString(";"),
            lyricsState(frames),
            usltTexts(frames).firstOrNull(),
            frames.any { it.id == "SYLT" } && usltTexts(frames).isEmpty(),
            durationMs,
            hasWork = textValues(frames, "TIT1").isNotEmpty(),
        )
    }

    /** Existing lyrics: SYLT (ms format only) first, then USLT (LRC text or plain), else empty. */
    fun extractLines(parsed: Parsed): List<Line> {
        parsed.frames.firstOrNull { it.id == "SYLT" }?.let { f ->
            runCatching { parseSylt(f.data) }.getOrNull()?.let { s ->
                if (s.timestampFormat == 2 && s.lines.isNotEmpty()) return s.lines.map { Lrc.newLine(Lrc.clampTime(it.timeMs), it.text) }
            }
        }
        parsed.frames.firstOrNull { it.id == "USLT" }?.let { f ->
            runCatching { parseUslt(f.data) }.getOrNull()?.let { u ->
                if (u.text.isNotBlank()) return Lrc.parseAny(u.text)
            }
        }
        return emptyList()
    }

    /** The text of the first non-blank USLT frame, exactly as stored. */
    fun rawLyricsText(parsed: Parsed): String? = parsed.frames.filter { it.id == "USLT" }
        .firstNotNullOfOrNull { f -> runCatching { parseUslt(f.data).text }.getOrNull()?.takeIf { it.isNotBlank() } }

    private fun frameBytes(id: String, payload: ByteArray): ByteArray {
        val out = ByteArray(10 + payload.size)
        id.toByteArray(Charsets.ISO_8859_1).copyInto(out)
        out[4] = (payload.size ushr 24).toByte(); out[5] = (payload.size ushr 16).toByte()
        out[6] = (payload.size ushr 8).toByte(); out[7] = payload.size.toByte()
        payload.copyInto(out, 10)
        return out
    }

    fun buildUsltFrame(text: String, language: String = "und"): ByteArray {
        val lang = language.padEnd(3, 'n').take(3).toByteArray(Charsets.ISO_8859_1)
        val textBytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charset.forName("UTF-16LE"))
        val payload = byteArrayOf(1) + lang + byteArrayOf(0, 0) + textBytes // enc=UTF-16, empty descriptor
        return frameBytes("USLT", payload)
    }

    /** A text frame's payload: encoding byte + text. Keeps UTF-16 if the old frame had it, else ISO-8859-1 when possible. */
    private fun textPayload(text: String, oldEnc: Int): ByteArray {
        val latin1 = text.all { it.code <= 0xFF }
        if (latin1 && oldEnc != 1 && oldEnc != 2) return byteArrayOf(0) + text.toByteArray(Charsets.ISO_8859_1)
        return byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charset.forName("UTF-16LE"))
    }

    private fun tagHeader(bodySize: Int): ByteArray {
        val n = bodySize
        return byteArrayOf(0x49, 0x44, 0x33, 3, 0, 0,
            ((n ushr 21) and 0x7F).toByte(), ((n ushr 14) and 0x7F).toByte(), ((n ushr 7) and 0x7F).toByte(), (n and 0x7F).toByte())
    }

    /**
     * Rewrites the file with a fresh ID3v2.3 tag. All frames are kept verbatim except:
     *  - [lrcText] != null: USLT/SYLT are replaced by one new USLT.
     *  - [tags] != null: the grouping is set to those tags, in one GRP1 frame (the iTunes grouping; TIT1 is the "Work" in most
     *    taggers). A TIT1 that was standing in for a missing GRP1 (see [groupingValues]) is removed, as it held the old grouping.
     *    An empty list removes the grouping. Tags equal to the current ones change nothing.
     *  - [other] (frame id -> text): those text frames are replaced; null or blank removes the frame.
     * The audio data is copied unchanged.
     */
    fun update(file: ByteArray, lrcText: String?, tags: List<String>?, language: String = "und", other: Map<String, String?> = emptyMap()): ByteArray {
        val parsed = parse(file)
        val audio = file.copyOfRange(parsed.audioStart, file.size)
        val frames = ByteArrayOutputStream()
        // Unchanged tags leave the grouping frames alone (a TIT1 stays until the metadata editor moves it).
        val unchanged = tags != null && tags == Search.parseTags(groupingValues(parsed.frames).joinToString(";"))
        val groupText = if (unchanged) null else tags?.joinToString("; ")
        val legacyGrouping = textValues(parsed.frames, "GRP1").isEmpty()
        val groupEnc = parsed.frames.firstOrNull { it.id == "GRP1" || (legacyGrouping && it.id == "TIT1") }?.let { u(it.data, 0) } ?: 0
        var groupWritten = false
        for (f in parsed.frames) {
            when {
                lrcText != null && (f.id == "USLT" || f.id == "SYLT") -> {}
                // The grouping takes the place of the first frame that held it.
                groupText != null && (f.id == "GRP1" || (legacyGrouping && f.id == "TIT1")) -> {
                    if (!groupWritten && groupText.isNotEmpty()) frames.write(frameBytes("GRP1", textPayload(groupText, groupEnc)))
                    groupWritten = true
                }
                f.id in other -> {}
                else -> frames.write(frameBytes(f.id, f.data))
            }
        }
        for ((id, text) in other) {
            if (text.isNullOrBlank()) continue
            val oldEnc = parsed.frames.firstOrNull { it.id == id }?.let { u(it.data, 0) } ?: 0
            frames.write(frameBytes(id, textPayload(text, oldEnc)))
        }
        if (lrcText != null) frames.write(buildUsltFrame(lrcText, language))
        if (!groupText.isNullOrEmpty() && !groupWritten) frames.write(frameBytes("GRP1", textPayload(groupText, groupEnc)))
        val body = frames.toByteArray()

        val out = ByteArrayOutputStream(10 + body.size + audio.size)
        out.write(tagHeader(body.size))
        out.write(body)
        out.write(audio)
        return out.toByteArray()
    }

    fun embedLyrics(file: ByteArray, lrcText: String, language: String = "und"): ByteArray = update(file, lrcText, null, language)

    fun setGrouping(file: ByteArray, tags: List<String>): ByteArray = update(file, null, tags)

    /** Replaces the text frames in [changes] (frame id -> text; null or blank removes the frame). Everything else is kept. */
    fun setTextFrames(file: ByteArray, changes: Map<String, String?>): ByteArray = update(file, null, null, other = changes)

    /** Text of the frame [id] for the metadata editor: all values joined with "; ", empty if there is none. */
    fun textOf(parsed: Parsed, id: String): String = textValues(parsed.frames, id).joinToString("; ")

    /** Text of the first comment (COMM) frame without a description, empty if there is none. */
    fun commentOf(parsed: Parsed): String = parsed.frames.filter { it.id == "COMM" && it.data.size > 4 }.firstNotNullOfOrNull { f ->
        runCatching {
            val enc = u(f.data, 0)
            val sp = splitDescriptorAndText(f.data.copyOfRange(4, f.data.size), enc)
            decodeText(sp.textBytes, enc).takeIf { sp.descriptor.isEmpty() }
        }.getOrNull()
    }.orEmpty()

    /** Replaces the comment (COMM) frame without a description; other comment frames are kept. Blank removes it. */
    fun setComment(file: ByteArray, text: String): ByteArray {
        val parsed = parse(file)
        val audio = file.copyOfRange(parsed.audioStart, file.size)
        val frames = ByteArrayOutputStream()
        for (f in parsed.frames) {
            val mine = f.id == "COMM" && f.data.size > 4 &&
                runCatching { splitDescriptorAndText(f.data.copyOfRange(4, f.data.size), u(f.data, 0)).descriptor.isEmpty() }.getOrDefault(false)
            if (!mine) frames.write(frameBytes(f.id, f.data))
        }
        if (text.isNotBlank()) {
            val payload = byteArrayOf(1, 'e'.code.toByte(), 'n'.code.toByte(), 'g'.code.toByte(), 0xFF.toByte(), 0xFE.toByte(), 0, 0,
                0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charset.forName("UTF-16LE"))
            frames.write(frameBytes("COMM", payload))
        }
        val body = frames.toByteArray()
        return tagHeader(body.size) + body + audio
    }
}
