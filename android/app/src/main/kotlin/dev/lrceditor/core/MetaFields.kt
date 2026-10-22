package dev.lrceditor.core

/**
 * The tags of a song the metadata editor can change. [frame] is the ID3 text frame; [core] fields are always shown, the others
 * only when the song has a value (or after adding them). [multi]: several values separated by ";" (the editor suggests known ones).
 */
enum class MetaField(val label: String, val frame: String, val core: Boolean = false, val multi: Boolean = false) {
    ARTIST("Artist", "TPE1", core = true),
    TITLE("Title", "TIT2", core = true),
    ALBUM("Album", "TALB", core = true),
    GROUPING("Grouping (tags, separated by ;)", "GRP1", core = true, multi = true),
    ALBUM_ARTIST("Album artist", "TPE2"),
    COMPOSER("Composer", "TCOM"),
    LYRICIST("Lyricist", "TEXT"),
    GENRE("Genre", "TCON"),
    YEAR("Year", "TYER"),
    TRACK("Track number", "TRCK"),
    DISC("Disc number", "TPOS"),
    SUBTITLE("Subtitle", "TIT3"),
    /** TIT1. Most taggers call it "Work"; older versions of this app wrote the grouping here by mistake. */
    WORK("Work", "TIT1"),
    PUBLISHER("Publisher", "TPUB"),
    COPYRIGHT("Copyright", "TCOP"),
    LANGUAGE("Language", "TLAN"),
    BPM("BPM", "TBPM"),
    COMMENT("Comment", "COMM");
}

/** Reading and writing the [MetaField]s of an MP3 file's bytes. */
object MetaFields {
    private val OTHER_TEXT = MetaField.entries.filter { it != MetaField.GROUPING && it != MetaField.COMMENT }

    /** Joins the tags the way the grouping is shown and written: "A; B; C". */
    fun joinTags(tags: List<String>) = tags.joinToString("; ")

    /** All fields of the file (empty text for those it doesn't have). The grouping is normalized (see [Search.parseTags]). */
    fun read(bytes: ByteArray): Map<MetaField, String> {
        val parsed = Id3.parse(bytes)
        val meta = Id3.readMeta(bytes.inputStream(), bytes.size.toLong())
        val out = LinkedHashMap<MetaField, String>()
        for (f in MetaField.entries) out[f] = when (f) {
            MetaField.GROUPING -> joinTags(Search.parseTags(meta.grouping))
            MetaField.COMMENT -> Id3.commentOf(parsed)
            MetaField.YEAR -> Id3.textOf(parsed, "TYER").ifEmpty { Id3.textOf(parsed, "TDRC") }
            else -> Id3.textOf(parsed, f.frame)
        }
        return out
    }

    /** Moves the Work value into the grouping tags (without duplicates) and clears Work. */
    fun convertWork(values: Map<MetaField, String>): Map<MetaField, String> {
        val tags = Search.parseTags(values[MetaField.GROUPING].orEmpty() + ";" + values[MetaField.WORK].orEmpty())
        return values + (MetaField.GROUPING to joinTags(tags)) + (MetaField.WORK to "")
    }

    private fun norm(f: MetaField, v: String?) = if (f == MetaField.GROUPING) joinTags(Search.parseTags(v.orEmpty())) else v.orEmpty().trim()

    /** The fields whose text differs between [original] and [values]. */
    fun changed(original: Map<MetaField, String>, values: Map<MetaField, String>): Set<MetaField> =
        MetaField.entries.filter { norm(it, original[it]) != norm(it, values[it]) }.toSet()

    /**
     * [bytes] with the fields that differ between [original] and [values] rewritten. Fields that did not change are not touched,
     * so applying the same change to another copy of the file (the editor's working copy) is safe.
     */
    fun write(bytes: ByteArray, original: Map<MetaField, String>, values: Map<MetaField, String>): ByteArray {
        val diff = changed(original, values)
        if (diff.isEmpty()) return bytes
        val parsed = Id3.parse(bytes)
        val frames = LinkedHashMap<String, String?>()
        for (f in OTHER_TEXT) if (f in diff) {
            var frame = f.frame
            // A year goes where the file keeps it: TDRC (ID3v2.4) if that is the only one.
            if (f == MetaField.YEAR && parsed.frames.none { it.id == "TYER" } && parsed.frames.any { it.id == "TDRC" }) frame = "TDRC"
            frames[frame] = values[f].orEmpty().trim()
        }
        val tags = if (MetaField.GROUPING in diff) Search.parseTags(values[MetaField.GROUPING].orEmpty()) else null
        var out = Id3.update(bytes, null, tags, other = frames)
        if (MetaField.COMMENT in diff) out = Id3.setComment(out, values[MetaField.COMMENT].orEmpty())
        return out
    }
}
