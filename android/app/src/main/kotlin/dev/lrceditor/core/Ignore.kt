package dev.lrceditor.core

/** Folder- and file-name ignore patterns for library scans: case-insensitive, `*` matches any run of characters. */
object Ignore {
    /** Sync tools, trash folders, thumbnails and OS bookkeeping folders that never hold real music. */
    val DEFAULTS = listOf(
        ".stfolder", ".stversions", ".debris", ".sync", ".syncthing*",
        ".trash*", ".trashed-*", ".Trashes", ".thumbnails", ".git",
        "@eaDir", "\$RECYCLE.BIN", "System Volume Information", "lost+found",
    )

    /** Syncthing conflict copies, e.g. `Song.sync-conflict-20250101-120000-ABCDEFG.mp3`. */
    val DEFAULT_FILES = listOf("*.sync-conflict-*")

    fun matcher(patterns: List<String>): (String) -> Boolean {
        val regexes = patterns.map { it.trim() }.filter { it.isNotEmpty() }.map { p ->
            Regex(p.split('*').joinToString(".*") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
        }
        return { name -> regexes.any { it.matches(name) } }
    }
}
