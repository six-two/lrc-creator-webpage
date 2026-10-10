package dev.lrceditor.data

import android.content.ContentProviderClient
import android.content.Context
import android.os.Bundle
import android.net.Uri
import android.provider.DocumentsContract
import dev.lrceditor.core.DocPath
import dev.lrceditor.core.Id3
import dev.lrceditor.core.Ignore
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
) {
    val displayTitle get() = title.ifBlank { Lrc.baseName(fileName) }
    /** Path below the music folder, e.g. `Artist/Album/Song.mp3` (derived from the document URI). */
    val relPath get() = DocPath.relativePath(uri) ?: fileName
}

@Serializable
data class LibraryIndex(val root: String, val builtAt: Long, val songs: List<SongEntry>, val durationMs: Long = 0)

/** While [listing], [total] is the running count of files found so far; afterwards it is the final count and [found] the number processed. */
data class ScanState(val running: Boolean = false, val found: Int = 0, val total: Int = 0, val listing: Boolean = false)

/** Persistent index of the songs under the user's chosen music folder (SAF tree), including their tags. */
class LibraryRepository(private val context: Context) {
    private val file = File(context.filesDir, "library-index.json")
    private val codec = Json { ignoreUnknownKeys = true }

    private val _index = MutableStateFlow<LibraryIndex?>(null)
    val index: StateFlow<LibraryIndex?> = _index.asStateFlow()
    private val _scan = MutableStateFlow(ScanState())
    val scan: StateFlow<ScanState> = _scan.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        if (file.exists()) _index.value = runCatching { codec.decodeFromString(LibraryIndex.serializer(), file.readText()) }.getOrNull()
    }

    fun isOutdated(root: String, now: Long = System.currentTimeMillis()): Boolean {
        val i = _index.value ?: return true
        return i.root != root || now - i.builtAt > MAX_AGE_MS
    }

    /** Keeps the cached lyrics state of a song in sync after we saved lyrics into it. */
    suspend fun setLyrics(uri: String, state: LyricsState) = withContext(Dispatchers.IO) {
        val cur = _index.value ?: return@withContext
        if (cur.songs.none { it.uri == uri }) return@withContext
        val updated = cur.copy(songs = cur.songs.map { if (it.uri == uri) it.copy(lyrics = state) else it })
        _index.value = updated
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(codec.encodeToString(LibraryIndex.serializer(), updated))
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
            val songs = walk(Uri.parse(root), full, Ignore.matcher(ignoredFolders), Ignore.matcher(ignoredFiles))
            val now = System.currentTimeMillis()
            val idx = LibraryIndex(root, now, songs, now - started)
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
                val entry = if (prev != null && prev.size == c.size && prev.modified == c.modified && prev.lyrics != LyricsState.UNKNOWN) prev
                else gate.withPermit {
                    val meta = runCatching { resolver.openInputStream(uri)?.use { Id3.readMeta(it) } }.getOrNull()
                    SongEntry(key, c.name, meta?.title.orEmpty(), meta?.artist.orEmpty(), meta?.album.orEmpty(),
                        Search.parseTags(meta?.grouping.orEmpty()), c.size, c.modified, meta?.lyrics ?: LyricsState.UNKNOWN)
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

        suspend fun visit(docId: String): List<Pair<Child, Uri>> = coroutineScope {
            val kids = gate.withPermit { withContext(Dispatchers.IO) { children(client, tree, docId) } }
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

    private fun children(client: ContentProviderClient, tree: Uri, parentDocId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val list = ArrayList<Child>()
        client.query(uri, cols, Bundle.EMPTY, null)?.use { c ->
            while (c.moveToNext()) list += Child(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getLong(3), c.getLong(4))
        }
        return list
    }

    companion object {
        const val READ_PARALLELISM = 8
        const val LIST_PARALLELISM = 8
        const val MAX_AGE_MS = 2L * 24 * 60 * 60 * 1000
    }
}
