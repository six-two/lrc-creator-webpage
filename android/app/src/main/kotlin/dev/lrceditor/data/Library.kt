package dev.lrceditor.data

import android.content.ContentProviderClient
import android.content.Context
import android.util.Log
import android.os.Bundle
import android.net.Uri
import androidx.core.net.toUri
import android.provider.DocumentsContract
import dev.lrceditor.core.DocPath
import dev.lrceditor.core.Id3
import dev.lrceditor.core.Ignore
import dev.lrceditor.core.Issue
import dev.lrceditor.core.Issues
import dev.lrceditor.core.LyricsState
import dev.lrceditor.core.Lrc
import dev.lrceditor.core.Search
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class SongEntry(
    val uri: String,
    val fileName: String,
    val title: String,
    val artist: String,
    val album: String,
    val tags: List<String>,
    val size: Long,
    val modified: Long,
    val lyrics: LyricsState = LyricsState.UNKNOWN,
    /** Problems found when the file was read. null = indexed before this existed (forces one re-read). */
    val issues: List<Issue>? = null,
    /** How often each of the [issues] occurs in the file (see [Issues.countSong]). */
    val counts: Map<Issue, Int> = emptyMap(),
    /** Length of the audio in ms, 0 if it couldn't be worked out. */
    val durationMs: Long = 0,
    /** Lengths of the short breaks in the lyrics (see [Issues.breakGaps]); which of them warn depends on the setting. */
    val breakGapsMs: List<Long> = emptyList(),
) {
    /**
     * The song as the lists show it: the stored problems plus those that don't come from the file alone: short breaks (the limit
     * is a setting), and the marks the user made by hand ([broken]: lyrics, [buggy]: file).
     */
    fun withMarks(minBreakMs: Long, broken: Set<String>, buggy: Set<String>): SongEntry {
        val extra = linkedMapOf<Issue, Int>()
        Issues.shortBreaks(breakGapsMs, minBreakMs).takeIf { it > 0 }?.let { extra[Issue.SHORT_BREAK] = it }
        if (uri in broken) extra[Issue.BROKEN_LYRICS] = 1
        if (uri in buggy) extra[Issue.BUGGY_FILE] = 1
        return if (extra.isEmpty()) this else copy(issues = issues.orEmpty() + extra.keys, counts = counts + extra)
    }

    /** Each problem as shown in the lists, with how often it occurs where that is more than once. */
    val issueLabels get() = issues.orEmpty().map { Issues.labelWithCount(it, counts[it]) }
    val displayTitle get() = title.ifBlank { Lrc.baseName(fileName) }
    /** Path below the music folder, e.g. `Artist/Album/Song.mp3` (derived from the document URI). */
    val relPath get() = DocPath.relativePath(uri) ?: fileName
}

@Serializable
data class LibraryIndex(val root: String, val builtAt: Long, val songs: List<SongEntry>, val durationMs: Long = 0,
    /** [LibraryRepository.DATA_VERSION] this index was written with. A Long so it can never overflow; 0 = written before versioning. */
    val version: Long = 0)

/** One song with problems, as written by "Export debug log". */
@Serializable
data class DebugSong(
    val relPath: String, val title: String, val artist: String, val lyrics: LyricsState,
    val tags: List<String>, val issues: List<Issue>, val counts: Map<Issue, Int>,
    val durationMs: Long = 0, val breakGapsMs: List<Long> = emptyList(),
)

@Serializable
data class DebugExport(
    val appVersion: String, val dataVersion: Long, val builtAt: Long, val songCount: Int,
    val issueCounts: Map<String, Int>, val songs: List<DebugSong>,
)

/** While [listing], [total] is the running count of files found so far; afterwards it is the final count and [found] the number processed. */
data class ScanState(val running: Boolean = false, val found: Int = 0, val total: Int = 0, val listing: Boolean = false)

/** Persistent index of the songs under the user's chosen music folder (SAF tree), including their tags. */
class LibraryRepository(private val context: Context) {
    private val file = File(context.filesDir, "library-index.json")
    private val codec = Json { ignoreUnknownKeys = true }
    private val prettyCodec = Json { prettyPrint = true }

    private val _index = MutableStateFlow<LibraryIndex?>(null)
    val index: StateFlow<LibraryIndex?> = _index.asStateFlow()
    private val unreadable = AtomicInteger()
    /** Folders that could not be listed in the last scan (skipped). */
    val skippedFolders: Int get() = unreadable.get()
    private val _scan = MutableStateFlow(ScanState())
    val scan: StateFlow<ScanState> = _scan.asStateFlow()

    /**
     * Reads the saved index. Returns true if the data is from another [DATA_VERSION] (or can't be read at all) and should be
     * rebuilt with a full rescan; an index that can still be decoded stays usable until then.
     */
    suspend fun load(): Boolean = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext false
        val idx = runCatching { codec.decodeFromString(LibraryIndex.serializer(), file.readText()) }.getOrNull()
        _index.value = idx
        idx == null || idx.version != DATA_VERSION
    }

    /** Writes the songs that have problems, and how many there are of each, to [dir]/lrc-debug-log.json. Null if there is no index. */
    suspend fun exportDebugLog(dir: File, appVersion: String): File? = withContext(Dispatchers.IO) {
        val idx = _index.value ?: return@withContext null
        val flagged = idx.songs.filter { it.issues.orEmpty().isNotEmpty() }
        val export = DebugExport(
            appVersion, idx.version, idx.builtAt, idx.songs.size,
            flagged.flatMap { it.issues.orEmpty() }.groupingBy { it.name }.eachCount().toSortedMap(),
            flagged.sortedBy { it.relPath }.map { DebugSong(it.relPath, it.title, it.artist, it.lyrics, it.tags, it.issues.orEmpty(), it.counts, it.durationMs, it.breakGapsMs) },
        )
        dir.mkdirs()
        File(dir, "lrc-debug-log.json").also { it.writeText(prettyCodec.encodeToString(DebugExport.serializer(), export)) }
    }

    fun isOutdated(root: String, now: Long = System.currentTimeMillis()): Boolean {
        val i = _index.value ?: return true
        return i.root != root || now - i.builtAt > MAX_AGE_MS
    }

    /** Replaces one song's cached entry (e.g. after we rewrote its file) and persists the index. */
    suspend fun updateEntry(uri: String, f: (SongEntry) -> SongEntry) = withContext(Dispatchers.IO) {
        val cur = _index.value ?: return@withContext
        if (cur.songs.none { it.uri == uri }) return@withContext
        val updated = cur.copy(songs = cur.songs.map { if (it.uri == uri) f(it) else it })
        _index.value = updated
        persist(updated)
    }

    /** Same, for many songs at once (one write). */
    suspend fun updateEntries(changes: Map<String, (SongEntry) -> SongEntry>) = withContext(Dispatchers.IO) {
        val cur = _index.value ?: return@withContext
        val updated = cur.copy(songs = cur.songs.map { s -> changes[s.uri]?.invoke(s) ?: s })
        _index.value = updated
        persist(updated)
    }

    private fun persist(idx: LibraryIndex) {
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(codec.encodeToString(LibraryIndex.serializer(), idx))
            tmp.renameTo(file)
        }
    }

    fun clear() {
        _index.value = null
        file.delete()
    }

    private class Child(val docId: String, val name: String, val mime: String, val size: Long, val modified: Long)

    /**
     * Walks the tree and (re)reads tags. Files whose size+modified time are unchanged reuse their old entry;
     * [full] ignores the cached entries and re-reads every file's tags. [ignoredFolders] / [ignoredFiles]
     * are name patterns (see [Ignore]).
     */
    suspend fun runScan(
        root: String, full: Boolean = false,
        ignoredFolders: List<String> = emptyList(), ignoredFiles: List<String> = emptyList(),
    ) {
        if (_scan.value.running) return
        _scan.value = ScanState(running = true, listing = true)
        val started = System.currentTimeMillis()
        try {
            val songs = walk(root.toUri(), full, Ignore.matcher(ignoredFolders), Ignore.matcher(ignoredFiles))
            val now = System.currentTimeMillis()
            val idx = LibraryIndex(root, now, songs, now - started, DATA_VERSION)
            withContext(Dispatchers.IO) {
                val tmp = File(file.path + ".tmp")
                tmp.writeText(codec.encodeToString(LibraryIndex.serializer(), idx))
                tmp.renameTo(file)
            }
            _index.value = idx
        } finally {
            _scan.value = _scan.value.copy(running = false)
        }
    }

    /**
     * Phase 1 lists the directory tree; phase 2 reads tags for new/changed files. Both phases are dominated
     * by cross-process calls into the storage provider (not CPU), so both run several requests in flight.
     */
    private suspend fun walk(
        tree: Uri, full: Boolean, ignoreDir: (String) -> Boolean, ignoreFile: (String) -> Boolean,
    ): List<SongEntry> = coroutineScope {
        val resolver = context.contentResolver
        val old = if (full) emptyMap()
        else _index.value?.takeIf { it.root == tree.toString() }?.songs?.associateBy { it.uri }.orEmpty()

        val files = resolver.acquireContentProviderClient(tree)?.use { client ->
            listFiles(client, tree, ignoreDir, ignoreFile)
        } ?: error("Music folder provider is unavailable")

        val total = files.size
        val done = AtomicInteger()
        val gate = Semaphore(READ_PARALLELISM)
        _scan.value = ScanState(true, 0, total)
        files.map { (c, uri) ->
            async(Dispatchers.IO) {
                val key = uri.toString()
                val prev = old[key]
                val entry = if (prev != null && prev.size == c.size && prev.modified == c.modified &&
                    prev.lyrics != LyricsState.UNKNOWN && prev.issues != null) prev
                else gate.withPermit {
                    val meta = runCatching { resolver.openInputStream(uri)?.use { Id3.readMeta(it, c.size) } }.getOrNull()
                    val tags = Search.parseTags(meta?.grouping.orEmpty())
                    // A song whose lyrics can't be checked must never abort the whole scan.
                    val counts = meta?.let { m ->
                        runCatching { Issues.countSong(tags, m.lyricsText, m.syltOnly, m.durationMs, m.hasWork) }
                            .onFailure { Log.e(TAG, "Could not check ${c.name}", it) }.getOrDefault(emptyMap())
                    } ?: emptyMap()
                    val gaps = runCatching { Issues.breakGaps(meta?.lyricsText) }.getOrDefault(emptyList())
                    SongEntry(key, c.name, meta?.title.orEmpty(), meta?.artist.orEmpty(), meta?.album.orEmpty(),
                        tags, c.size, c.modified, meta?.lyrics ?: LyricsState.UNKNOWN, counts.keys.toList(), counts,
                        meta?.durationMs ?: 0, gaps)
                }
                val n = done.incrementAndGet()
                if (n % 25 == 0 || n == total) _scan.value = ScanState(true, n, total)
                entry
            }
        }.awaitAll()
    }

    /**
     * Lists all MP3 files below [tree]. Sub-directories are listed concurrently (up to [LIST_PARALLELISM]
     * queries at once) through one shared provider client, instead of one sequential query per directory.
     */
    private suspend fun listFiles(
        client: ContentProviderClient, tree: Uri, ignoreDir: (String) -> Boolean, ignoreFile: (String) -> Boolean,
    ): List<Pair<Child, Uri>> {
        val gate = Semaphore(LIST_PARALLELISM)
        val found = AtomicInteger()
        unreadable.set(0)

        suspend fun visit(docId: String): List<Pair<Child, Uri>> = coroutineScope {
            val kids = gate.withPermit {
                withContext(Dispatchers.IO) {
                    // Provider reads are flaky under parallel load (e.g. "Couldn't read row 0, col 2 from CursorWindow"):
                    // retry once, then skip just this folder instead of failing the whole scan.
                    runCatching { children(client, tree, docId) }
                        .recoverCatching { children(client, tree, docId) }
                        .onFailure { Log.e(TAG, "Could not list folder $docId", it); unreadable.incrementAndGet() }
                        .getOrDefault(emptyList())
                }
            }
            val here = ArrayList<Pair<Child, Uri>>()
            val subDirs = ArrayList<Deferred<List<Pair<Child, Uri>>>>()
            for (c in kids) {
                if (c.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (!ignoreDir(c.name)) subDirs += async { visit(c.docId) }
                } else if ((c.name.endsWith(".mp3", ignoreCase = true) || c.mime == "audio/mpeg") && !ignoreFile(c.name)) {
                    here += c to DocumentsContract.buildDocumentUriUsingTree(tree, c.docId)
                    val n = found.incrementAndGet()
                    if (n % 50 == 0) _scan.value = ScanState(true, 0, n, listing = true)
                }
            }
            here + subDirs.awaitAll().flatten()
        }
        return visit(DocumentsContract.getTreeDocumentId(tree))
    }

    /** Size / modified columns are numbers by contract, but some providers return odd strings (e.g. "0 2"): treat those as 0. */
    private fun numberAt(c: android.database.Cursor, i: Int): Long = when (c.getType(i)) {
        android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
        android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i).toLong()
        android.database.Cursor.FIELD_TYPE_STRING -> c.getString(i)?.trim()?.toLongOrNull() ?: 0L
        else -> 0L
    }

    private fun children(client: ContentProviderClient, tree: Uri, parentDocId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val list = ArrayList<Child>()
        client.query(uri, cols, Bundle.EMPTY, null)?.use { c ->
            while (c.moveToNext()) list += Child(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "", numberAt(c, 3), numberAt(c, 4))
        }
        return list
    }

    companion object {
        private const val TAG = "LibraryScan"
        /**
         * Version of everything stored in the library index. INCREASE IT whenever the stored data changes in a way old data can't
         * be used as is: a field is added, removed or changes meaning, or [Issues.checkSong] gains or changes a check. The app
         * then rebuilds the index with a full rescan in the background on the next start.
         */
        const val DATA_VERSION = 7L
        const val READ_PARALLELISM = 8
        const val LIST_PARALLELISM = 8
        const val MAX_AGE_MS = 2L * 24 * 60 * 60 * 1000
    }
}
