package dev.lrceditor

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import androidx.core.net.toUri
import android.provider.OpenableColumns
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.lrceditor.core.DocPath
import dev.lrceditor.core.Id3
import dev.lrceditor.core.Issue
import dev.lrceditor.core.Issues
import dev.lrceditor.core.LearnState
import dev.lrceditor.core.LineProblem
import dev.lrceditor.core.Line
import dev.lrceditor.core.Lrc
import dev.lrceditor.core.LyricsFilter
import dev.lrceditor.core.LyricsState
import dev.lrceditor.core.NameField
import dev.lrceditor.core.Search
import dev.lrceditor.core.TagCount
import dev.lrceditor.data.*
import dev.lrceditor.data.lyrics.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class ExportKind { LRC, MP3 }

enum class Tab { LIBRARY, LEARN, EDITOR, SETTINGS }

/** State of the "fetch lyrics" dialog. */
sealed interface FetchState {
    data object Loading : FetchState
    data class Found(val result: LyricsResult, val useRomanization: Boolean = false) : FetchState {
        val text get() = if (useRomanization) result.withRomanization ?: result.text else result.text
    }
    /** Nothing usable; the dialog then offers the browser and clipboard routes. */
    data class Failed(val message: String) : FetchState
}

/** A tag rewrite that waits for the "overwrite the original file?" confirmation. */
sealed interface TagAction {
    data class SetLearn(val uri: String, val name: String, val state: LearnState) : TagAction
    data class FixAll(val count: Int) : TagAction
}

/** Technical details of the open MP3, shown by the info dialog. */
data class FileInfo(
    val fileName: String, val path: String?, val sizeBytes: Long,
    val title: String, val artist: String, val album: String, val albumArtist: String,
    val genre: String, val year: String, val track: String,
    val tags: List<String>, val lyrics: LyricsState,
    val bitrateKbps: Int?, val sampleRateHz: Int?, val durationMs: Long?, val id3Version: String?,
    val cover: Bitmap?,
)

data class UiState(
    val loaded: Boolean = false,
    val fileName: String? = null,
    val lines: List<Line> = emptyList(),
    val selectedId: Long? = null,
    val playingId: Long? = null,
    val editingTextId: Long? = null,
    val editingTimeId: Long? = null,
    val dirty: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isPlaying: Boolean = false,
    val speed: Float = 1f,
    val durationMs: Long = 0,
    val settings: Settings = Settings(),
    val showLyricsDialog: Boolean = false,
    val tab: Tab = Tab.LIBRARY,
    /** A file was shared in while unsaved work is open; waiting for the user to confirm replacing it. */
    val pendingOpen: Uri? = null,
    val busy: Boolean = false,
    val confirmSave: Boolean = false,
    val confirmDiscard: Boolean = false,
    val info: FileInfo? = null,
    /** Grouping tags of the open file as last read or written. */
    val tags: List<String> = emptyList(),
    val fetch: FetchState? = null,
    val pendingTag: TagAction? = null,
    /** There is a screen to go back to (see [EditorViewModel.back]). */
    val canGoBack: Boolean = false,
    /** The editor should scroll this line into view, then call [EditorViewModel.consumeScroll]. */
    val scrollToId: Long? = null,
    /** Lyrics of the open file that exist but can't be read, shown raw for fixing; saving is blocked meanwhile. */
    val rawLyrics: String? = null,
    val lyricsError: String? = null,
) {
    val learn get() = Search.learnStateOf(tags)
}

private const val MAX_UNDO = 100

@OptIn(kotlinx.coroutines.FlowPreview::class)
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class EditorViewModel(app: Application) : AndroidViewModel(app) {
    // Declared first: init blocks below restore a session, which resets these.
    private val undoStack = ArrayDeque<List<Line>>()
    private val redoStack = ArrayDeque<List<Line>>()
    /** Lyrics as last loaded or saved/exported; "discard changes" returns to this. */
    private var baseline: List<Line> = emptyList()
    private var gestureOpen = false
    private var gesturePushed = false

    private val sessions = SessionStore(app)
    private val settingsStore = SettingsStore(app)
    private val tick = TickPlayer()
    private val library = LibraryRepository(app)
    private val recentsStore = RecentsStore(app)
    private val searchStore = SearchPrefsStore(app)

    val recents: StateFlow<List<Recent>> = recentsStore.items
    val scan: StateFlow<ScanState> = library.scan
    val index: StateFlow<LibraryIndex?> = library.index

    private val _search = MutableStateFlow(SearchPrefs())
    val search = _search.asStateFlow()

    val tagCounts: StateFlow<List<TagCount>> = library.index
        .map { Search.tagCounts(it?.songs.orEmpty()) }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Name / tag search results for the current [search] prefs, recomputed off the main thread. */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val results: StateFlow<List<SongEntry>> = combine(library.index, _search) { idx, p -> idx?.songs.orEmpty() to p }
        .debounce(120)
        .mapLatest { (songs, p) ->
            val queryActive = when (p.mode) {
                SongsMode.TITLE -> p.query.isNotBlank()
                SongsMode.FILENAME -> p.fileQuery.isNotBlank()
                SongsMode.TAGS -> p.include.isNotEmpty() || p.exclude.isNotEmpty()
                SongsMode.RECENT, SongsMode.ATTENTION -> false
            }
            val base = when {
                p.mode == SongsMode.RECENT || p.mode == SongsMode.ATTENTION -> emptyList()
                // No text/tag query: a lyrics filter alone lists every song with that lyric state.
                !queryActive -> if (p.lyrics != LyricsFilter.ALL) songs.sortedWith(Search.songOrder) else emptyList()
                p.mode == SongsMode.TITLE -> Search.byName(songs, p.query, NameField.TITLE, p.fuzzy)
                p.mode == SongsMode.FILENAME -> Search.byName(songs, p.fileQuery, NameField.FILENAME, p.fuzzy)
                else -> Search.byTags(songs, p.include, p.exclude)
            }
            Search.byLyrics(base, p.lyrics)
        }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var currentUri: String? = null

    private val _saveAs = MutableSharedFlow<ExportKind>(extraBufferCapacity = 1)
    /** Asks the UI to open the system "save as" picker. */
    val saveAs = _saveAs.asSharedFlow()

    private val _ui = MutableStateFlow(UiState())
    val ui = _ui.asStateFlow()

    /** Playback position is kept out of [UiState] so ~60 Hz updates only recompose the player bar. */
    private val _positionMs = MutableStateFlow(0L)
    val positionMs = _positionMs.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    val player: ExoPlayer = ExoPlayer.Builder(app)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(app, DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true))
        ).build()

    private var saveJob: Job? = null
    private var lastTickPos = -1L

    init {
        viewModelScope.launch { settingsStore.flow.collect { s -> _ui.update { it.copy(settings = s) } } }
        viewModelScope.launch {
            _search.value = searchStore.load()
            _search.drop(1).debounce(400).collect { searchStore.save(it) }
        }
        // Load the index, then refresh it in the background if it is stale.
        viewModelScope.launch {
            library.load()
            val s = settingsStore.flow.first()
            val root = s.libraryRoot ?: return@launch
            if (library.isOutdated(root)) library.runScan(root, ignoredFolders = s.ignoredFolders, ignoredFiles = s.ignoredFiles)
        }
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _ui.update { it.copy(isPlaying = isPlaying) }
                lastTickPos = player.currentPosition
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) _ui.update { it.copy(durationMs = player.duration.coerceAtLeast(0)) }
            }
            override fun onPositionDiscontinuity(o: Player.PositionInfo, n: Player.PositionInfo, reason: Int) {
                lastTickPos = n.positionMs
            }
        })
        // Tight polling loop: needed for the tick sound to land close to the real crossing instant.
        viewModelScope.launch {
            while (isActive) {
                val playing = player.isPlaying
                val pos = player.currentPosition
                _positionMs.value = pos
                updateNowPlaying(pos)
                if (playing) checkTicks(pos)
                delay(if (playing) 8 else 100)
            }
        }
        restoreSession()
    }

    // ---------------------------------------------------------------- loading

    /** (raw text, reason) if the file has lyrics that can't be read into lines; null if they are fine or absent. */
    private fun unreadableLyrics(bytes: ByteArray): Pair<String, String>? {
        val parsed = Id3.parse(bytes)
        if (parsed.frames.any { it.id == "SYLT" }) return null // those are what gets loaded
        val raw = Id3.rawLyricsText(parsed) ?: return null
        return Lrc.multiTimestampProblem(raw)?.let { raw to it }
    }

    private fun showUnreadable(bad: Pair<String, String>) =
        _ui.update { it.copy(rawLyrics = bad.first, lyricsError = bad.second, showLyricsDialog = true) }

    private fun restoreSession() {
        val (saved, lines) = sessions.load() ?: return
        currentUri = saved.uri
        activate(saved.fileName, lines, saved.dirty)
        baseline = when {
            saved.baseline != null -> saved.baseline.map { Lrc.newLine(it.timeMs, it.text) }
            !saved.dirty -> lines
            // Older session: fall back to the lyrics embedded in the copied MP3.
            else -> runCatching { Id3.extractLines(Id3.parse(sessions.audioFile.readBytes())) }.getOrDefault(emptyList())
        }
        _ui.update { it.copy(tab = Tab.EDITOR) }
        if (lines.isEmpty() && !saved.dirty) runCatching { unreadableLyrics(sessions.audioFile.readBytes()) }.getOrNull()?.let { bad ->
            _ui.update { it.copy(rawLyrics = bad.first, lyricsError = bad.second) }
        }
        _messages.tryEmit("Session restored")
    }

    /** Entry point for share / "open with" intents. */
    fun openUri(uri: Uri) {
        val s = _ui.value
        if (s.loaded && uri.toString() == currentUri) { navigate(Tab.EDITOR); return } // already open
        if (s.loaded && s.dirty) _ui.update { it.copy(pendingOpen = uri) } else loadUri(uri)
    }

    fun confirmPendingOpen() {
        val uri = _ui.value.pendingOpen ?: return
        _ui.update { it.copy(pendingOpen = null) }
        loadUri(uri)
    }

    fun cancelPendingOpen() = _ui.update { it.copy(pendingOpen = null) }

    private fun loadUri(uri: Uri) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            val result = withContext(Dispatchers.IO) {
                runCatching<Triple<String, List<Line>, Pair<String, String>?>?> {
                    val resolver = getApplication<Application>().contentResolver
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "song.mp3"
                    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Could not open file")
                    if (!Lrc.looksLikeMp3(bytes)) return@runCatching null
                    sessions.audioFile.writeBytes(bytes)
                    val bad = unreadableLyrics(bytes)
                    Triple(name, if (bad != null) emptyList() else Id3.extractLines(Id3.parse(bytes)), bad)
                }
            }
            _ui.update { it.copy(busy = false) }
            result.onFailure { _messages.tryEmit("Could not read file: ${it.message}") }
            result.onSuccess { r ->
                if (r == null) {
                    _messages.tryEmit("That doesn't look like a valid MP3 file — no MP3 header found.")
                    return@onSuccess
                }
                val (name, lines, bad) = r
                currentUri = uri.toString()
                val entry = library.index.value?.songs?.firstOrNull { it.uri == currentUri }
                recentsStore.touch(Recent(currentUri!!, name, entry?.title.orEmpty(), entry?.artist.orEmpty(), System.currentTimeMillis()))
                activate(name, lines, dirty = false)
                baseline = lines
                // A song with a problem opens on the first problem line.
                Issues.nextProblemLine(lines, null)?.let { id -> _ui.update { it.copy(selectedId = id, scrollToId = id) } }
                navigate(Tab.EDITOR)
                if (bad != null) showUnreadable(bad)
                scheduleSave()
                _messages.tryEmit(when {
                    bad != null -> "The lyrics in this file can't be read — see the message"
                    lines.isEmpty() -> "No existing lyrics found — fetch or paste some"
                    else -> "Loaded existing lyrics from the file"
                })
            }
        }
    }

    private fun activate(name: String, lines: List<Line>, dirty: Boolean) {
        undoStack.clear(); redoStack.clear()
        _ui.update {
            it.copy(loaded = true, fileName = name, lines = lines, selectedId = lines.firstOrNull()?.id,
                playingId = null, editingTextId = null, editingTimeId = null, dirty = dirty, speed = 1f,
                canUndo = false, canRedo = false, tags = sessionTags(), fetch = null, rawLyrics = null, lyricsError = null)
        }
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(sessions.audioFile)))
        player.setPlaybackParameters(PlaybackParameters(1f))
        player.prepare()
        lastTickPos = -1
    }

    /** Grouping tags of the working copy of the open file. */
    private fun sessionTags(): List<String> =
        runCatching { Search.parseTags(sessions.audioFile.inputStream().use { Id3.readMeta(it) }.grouping) }.getOrDefault(emptyList())

    // ---------------------------------------------------------------- line editing

    /** A drag that changes lines many times (e.g. nudging) counts as a single undo step. */
    fun beginGesture() { gestureOpen = true; gesturePushed = false }
    fun endGesture() { gestureOpen = false }

    private fun mutateLines(markDirty: Boolean = true, f: (List<Line>) -> List<Line>) {
        val before = _ui.value.lines
        val after = f(before)
        if (markDirty && !(gestureOpen && gesturePushed)) {
            undoStack.addLast(before)
            if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
            redoStack.clear()
            gesturePushed = true
        }
        _ui.update { it.copy(lines = after, dirty = it.dirty || markDirty, canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty()) }
        if (markDirty) scheduleSave()
    }

    fun undo() = restore(undoStack, redoStack)
    fun redo() = restore(redoStack, undoStack)

    private fun restore(from: ArrayDeque<List<Line>>, to: ArrayDeque<List<Line>>) {
        val target = from.removeLastOrNull() ?: return
        to.addLast(_ui.value.lines)
        _ui.update {
            it.copy(
                lines = target, dirty = true, editingTextId = null, editingTimeId = null,
                selectedId = it.selectedId.takeIf { id -> target.any { l -> l.id == id } } ?: target.firstOrNull()?.id,
                playingId = it.playingId.takeIf { id -> target.any { l -> l.id == id } },
                canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty(),
            )
        }
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(700)
            saveNow()
        }
    }

    private suspend fun saveNow() = withContext(Dispatchers.IO) {
        val s = _ui.value
        if (s.loaded && s.fileName != null) {
            runCatching { sessions.save(s.fileName, s.lines, s.dirty, currentUri, baseline) }
            currentUri?.let { recentsStore.bump(it) }
        }
    }

    /** Selects and scrolls to the next line with a problem (of [kind], or any), wrapping around. */
    fun jumpToProblem(kind: LineProblem?) {
        val s = _ui.value
        val id = Issues.nextProblemLine(s.lines, s.selectedId, kind) ?: return
        _ui.update { it.copy(selectedId = id, scrollToId = id) }
    }

    fun consumeScroll() = _ui.update { it.copy(scrollToId = null) }

    fun select(id: Long) = _ui.update { it.copy(selectedId = id) }

    fun moveSelection(delta: Int) {
        val s = _ui.value
        if (s.lines.isEmpty()) return
        val idx = s.lines.indexOfFirst { it.id == s.selectedId }
        val n = if (idx == -1) (if (delta > 0) 0 else s.lines.lastIndex) else (idx + delta).coerceIn(0, s.lines.lastIndex)
        select(s.lines[n].id)
    }

    fun nudge(id: Long, deltaMs: Long) = mutateLines { ls ->
        ls.map { if (it.id == id) it.copy(timeMs = Lrc.clampTime((it.timeMs ?: 0) + deltaMs)) else it }
    }

    fun nudgeSelected(sign: Int) {
        val s = _ui.value
        s.selectedId?.let { nudge(it, sign * s.settings.nudgeMs) } ?: _messages.tryEmit("Select a line first")
    }

    fun delete(id: Long) {
        val s = _ui.value
        val idx = s.lines.indexOfFirst { it.id == id }
        if (idx == -1) return
        val remaining = s.lines.filter { it.id != id }
        val newSel = if (s.selectedId == id) (remaining.getOrNull(idx) ?: remaining.getOrNull(idx - 1))?.id else s.selectedId
        _ui.update { it.copy(selectedId = newSel, playingId = it.playingId.takeIf { p -> p != id }) }
        mutateLines { remaining }
    }

    fun setTimeForSelected() {
        val s = _ui.value
        val id = s.selectedId ?: return run { _messages.tryEmit("Select a line first") }
        val now = player.currentPosition
        mutateLines { ls -> ls.map { if (it.id == id) it.copy(timeMs = Lrc.clampTime(now)) else it } }
        if (s.settings.autoAdvance) {
            val idx = s.lines.indexOfFirst { it.id == id }
            s.lines.getOrNull(idx + 1)?.let { select(it.id) }
        }
    }

    fun clearTimeForSelected() {
        val id = _ui.value.selectedId ?: return run { _messages.tryEmit("Select a line first") }
        mutateLines { ls -> ls.map { if (it.id == id) it.copy(timeMs = null) else it } }
    }

    fun setTime(id: Long, ms: Long?) = mutateLines { ls -> ls.map { if (it.id == id) it.copy(timeMs = ms?.let(Lrc::clampTime)) else it } }
    fun setText(id: Long, text: String) = mutateLines { ls -> ls.map { if (it.id == id) it.copy(text = text) else it } }

    /** Inserts an empty ("pause") line at the playhead. Position rule depends on the auto-advance setting (spec §6). */
    fun insertPauseLine() {
        val s = _ui.value
        val line = Lrc.newLine(Lrc.clampTime(player.currentPosition), "")
        val sel = s.lines.indexOfFirst { it.id == s.selectedId }
        val isLast = sel != -1 && sel == s.lines.lastIndex
        var at = s.lines.size
        var keepSelected = false
        if (sel != -1) {
            if (s.settings.autoAdvance && !isLast) { at = sel; keepSelected = true } else at = sel + 1
        }
        mutateLines { ls -> ls.toMutableList().apply { add(at, line) } }
        if (!keepSelected) select(line.id)
    }

    fun startEditText(id: Long?) = _ui.update { it.copy(editingTextId = id) }
    fun startEditTime(id: Long?) = _ui.update { it.copy(editingTimeId = id) }

    /** Returns false (and keeps the dialog open with the reason) if the text can't be read into lines. */
    fun replaceAllLyrics(text: String): Boolean {
        Lrc.multiTimestampProblem(text)?.let { problem ->
            _ui.update { it.copy(rawLyrics = text, lyricsError = problem, showLyricsDialog = true) }
            return false
        }
        val lines = Lrc.parseAny(text)
        _ui.update { it.copy(selectedId = lines.firstOrNull()?.id, playingId = null, editingTextId = null, editingTimeId = null,
            rawLyrics = null, lyricsError = null) }
        mutateLines { lines }
        closeLyricsDialog()
        return true
    }

    fun currentLrc(): String = Lrc.serialize(_ui.value.lines)

    // ---------------------------------------------------------------- player

    fun togglePlay() { if (player.isPlaying) player.pause() else player.play() }

    fun seekTo(ms: Long) {
        val dur = _ui.value.durationMs
        player.seekTo(if (dur > 0) ms.coerceIn(0, dur) else maxOf(0, ms))
        _positionMs.value = player.currentPosition
    }

    fun seekBy(deltaMs: Long) = seekTo(player.currentPosition + deltaMs)

    /** Seek to the line's time and play; also selects it. Selecting a row alone never moves playback. */
    /** Tapping a line selects it and, if it has a time, plays from there. Untimed lines are only selected. */
    fun tapLine(id: Long) {
        select(id)
        if (_ui.value.lines.firstOrNull { it.id == id }?.timeMs != null) playFromLine(id)
    }

    fun playFromLine(id: Long) {
        val line = _ui.value.lines.firstOrNull { it.id == id } ?: return
        val t = line.timeMs ?: return run { _messages.tryEmit("This line has no time set yet") }
        select(id)
        seekTo(t)
        player.play()
    }

    fun playFromSelected() {
        val id = _ui.value.selectedId ?: return run { _messages.tryEmit("Select a line first") }
        playFromLine(id)
    }

    fun setSpeed(speed: Float) {
        player.setPlaybackParameters(PlaybackParameters(speed))
        _ui.update { it.copy(speed = speed) }
    }

    private fun updateNowPlaying(pos: Long) {
        val lines = _ui.value.lines
        var best: Line? = null
        for (l in lines) {
            val t = l.timeMs ?: continue
            if (t <= pos && (best == null || t > best.timeMs!!)) best = l
        }
        if (best?.id != _ui.value.playingId) _ui.update { it.copy(playingId = best?.id) }
    }

    private fun checkTicks(pos: Long) {
        val s = _ui.value
        if (!s.settings.tickEnabled) { lastTickPos = pos; return }
        if (lastTickPos < 0 || pos <= lastTickPos) { lastTickPos = pos; return } // seek / first sample: don't burst-fire
        val crossed = s.lines.count { val t = it.timeMs; t != null && t > lastTickPos && t <= pos }
        if (crossed > 0) tick.tick(s.settings.tickVolume)
        lastTickPos = pos
    }

    // ---------------------------------------------------------------- dialogs & settings

    fun openLyricsDialog() = _ui.update { it.copy(showLyricsDialog = true) }
    fun closeLyricsDialog() = _ui.update { it.copy(showLyricsDialog = false) }
    /** Where Back goes: the screens we came from, most recent last. */
    private val tabStack = ArrayDeque<Tab>()

    /** Tab bar tap. Starts a fresh trail, except that leaving the editor lets Back return to it. */
    fun showTab(tab: Tab) {
        val from = _ui.value.tab
        tabStack.clear()
        if (from == Tab.EDITOR && tab != Tab.EDITOR) tabStack.addLast(Tab.EDITOR)
        _ui.update { it.copy(tab = tab, canGoBack = tabStack.isNotEmpty()) }
    }

    /** Moves to [tab] from inside the app (e.g. opening a song) so that Back returns to where you were. */
    fun navigate(tab: Tab) {
        val from = _ui.value.tab
        if (tab == from) return
        if (tabStack.lastOrNull() == tab) tabStack.removeLast() else tabStack.addLast(from)
        while (tabStack.size > 8) tabStack.removeFirst()
        _ui.update { it.copy(tab = tab, canGoBack = tabStack.isNotEmpty()) }
    }

    fun back() {
        val to = tabStack.removeLastOrNull() ?: return
        _ui.update { it.copy(tab = to, canGoBack = tabStack.isNotEmpty()) }
    }

    fun updateSettings(s: Settings) {
        _ui.update { it.copy(settings = s) }
        viewModelScope.launch { settingsStore.save(s) }
    }

    // ---------------------------------------------------------------- library & search

    fun setLibraryRoot(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        val old = _ui.value.settings.libraryRoot
        if (old != null && old != uri.toString()) {
            runCatching { resolver.releasePersistableUriPermission(old.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
        updateSettings(_ui.value.settings.copy(libraryRoot = uri.toString()))
        rescan()
    }

    fun rescan(full: Boolean = false) {
        val root = _ui.value.settings.libraryRoot ?: return run { _messages.tryEmit("Choose a music folder first") }
        viewModelScope.launch {
            if (library.scan.value.running) return@launch
            runCatching { library.runScan(root, full, _ui.value.settings.ignoredFolders, _ui.value.settings.ignoredFiles) }
                .onSuccess {
                    val skipped = library.skippedFolders
                    _messages.tryEmit("Library indexed: ${library.index.value?.songs?.size ?: 0} songs" +
                        if (skipped > 0) " ($skipped folder${if (skipped == 1) "" else "s"} could not be read — rescan to retry)" else "")
                }
                .onFailure { android.util.Log.e("LibraryScan", "Scan failed", it); _messages.tryEmit("Scan failed: ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    fun updateSearch(f: (SearchPrefs) -> SearchPrefs) = _search.update(f)

    fun openSong(uriString: String) = openUri(uriString.toUri())

    fun currentUriString(): String? = currentUri

    fun removeRecent(uri: String) = recentsStore.remove(uri)

    // ---------------------------------------------------------------- file info

    fun showInfo() {
        val name = _ui.value.fileName ?: return
        val uri = currentUri
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { runCatching { readFileInfo(name, uri) }.getOrNull() }
            if (info == null) _messages.tryEmit("Could not read file info") else _ui.update { it.copy(info = info) }
        }
    }

    fun closeInfo() = _ui.update { it.copy(info = null) }

    private fun readFileInfo(name: String, uri: String?): FileInfo {
        val file = sessions.audioFile
        val meta = file.inputStream().use { Id3.readMeta(it) }
        val version = file.inputStream().use { ins ->
            val h = ByteArray(4)
            if (ins.read(h) == 4 && h[0] == 'I'.code.toByte() && h[1] == 'D'.code.toByte() && h[2] == '3'.code.toByte()) "ID3v2.${h[3]}" else null
        }
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(file.path)
            fun k(key: Int) = mmr.extractMetadata(key).orEmpty()
            val cover = mmr.embeddedPicture?.let { decodeSampled(it, 1024) }
            return FileInfo(
                fileName = name, path = uri?.let { DocPath.fullPath(it) ?: it }, sizeBytes = file.length(),
                title = meta.title.ifBlank { k(MediaMetadataRetriever.METADATA_KEY_TITLE) },
                artist = meta.artist.ifBlank { k(MediaMetadataRetriever.METADATA_KEY_ARTIST) },
                album = meta.album.ifBlank { k(MediaMetadataRetriever.METADATA_KEY_ALBUM) },
                albumArtist = k(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                genre = k(MediaMetadataRetriever.METADATA_KEY_GENRE),
                year = k(MediaMetadataRetriever.METADATA_KEY_YEAR).ifBlank { k(MediaMetadataRetriever.METADATA_KEY_DATE) },
                track = k(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER),
                tags = Search.parseTags(meta.grouping), lyrics = meta.lyrics,
                bitrateKbps = k(MediaMetadataRetriever.METADATA_KEY_BITRATE).toIntOrNull()?.let { it / 1000 },
                sampleRateHz = k(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE).toIntOrNull(),
                durationMs = k(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull(),
                id3Version = version, cover = cover,
            )
        } finally {
            mmr.release()
        }
    }

    private fun decodeSampled(bytes: ByteArray, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    // ---------------------------------------------------------------- export

    /** Save button: embeds the lyrics into the original MP3 (after asking, if enabled). */
    fun requestSave() {
        if (!_ui.value.loaded) return
        if (blockedByUnreadable()) return
        if (_ui.value.settings.confirmOverwrite) _ui.update { it.copy(confirmSave = true) } else saveToOriginal()
    }

    /** Saving now would overwrite lyrics that were never loaded. */
    private fun blockedByUnreadable(): Boolean {
        if (_ui.value.rawLyrics == null) return false
        _messages.tryEmit("The file's lyrics couldn't be read, so nothing is saved. Fix them in \"Import / edit lyrics text\" first.")
        return true
    }

    fun requestDiscard() = _ui.update { it.copy(confirmDiscard = true) }
    fun dismissDiscard() = _ui.update { it.copy(confirmDiscard = false) }

    /** Goes back to the lyrics as last loaded or saved. Undo can bring the discarded changes back. */
    fun confirmDiscard() {
        dismissDiscard()
        val restored = baseline.map { Lrc.newLine(it.timeMs, it.text) }
        mutateLines { restored }
        _ui.update {
            it.copy(dirty = false, editingTextId = null, editingTimeId = null, playingId = null,
                selectedId = restored.firstOrNull()?.id)
        }
        scheduleSave()
        _messages.tryEmit("Changes discarded")
    }

    fun dismissSaveConfirm() = _ui.update { it.copy(confirmSave = false) }

    fun confirmSave() {
        dismissSaveConfirm()
        saveToOriginal()
    }

    /** Tags with Lyrics/Karaoke recomputed for [lines], starting from the grouping of [file]. */
    private fun tagsFor(file: ByteArray, lines: List<Line>): List<String> =
        Search.withLyricsTags(Search.parseTags(Id3.readMeta(file.inputStream()).grouping), lines)

    /**
     * Writes straight into the original file (lyrics and the matching Lyrics/Karaoke tags in one rewrite).
     * Falls back to the "save as" picker if we have no write access.
     */
    private fun saveToOriginal() {
        val uriString = currentUri ?: return run { _saveAs.tryEmit(ExportKind.MP3) }
        val uri = uriString.toUri()
        val lines = _ui.value.lines
        val text = currentLrc()
        val note = exportNote()
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val src = sessions.audioFile.readBytes()
                    val tags = tagsFor(src, lines)
                    val bytes = Id3.update(src, text, tags)
                    val out = getApplication<Application>().contentResolver.openOutputStream(uri, "wt") ?: error("no output stream")
                    out.use { it.write(bytes) }
                    tags
                }
            }
            r.onSuccess { tags ->
                markClean()
                val lyrics = if (Lrc.hasLyrics(lines)) LyricsState.SYNCED else LyricsState.NONE
                library.updateEntry(uriString) {
                    it.copy(tags = tags, lyrics = lyrics, issues = Issues.checkSong(tags, text).toList(), untimed = Lrc.countUntimed(lines))
                }
                _ui.update { it.copy(tags = tags) }
                _messages.tryEmit("Saved lyrics into ${_ui.value.fileName}$note")
            }
            r.onFailure {
                _messages.tryEmit("Can't write to the original file (${it.javaClass.simpleName}) — choose where to save instead")
                _saveAs.tryEmit(ExportKind.MP3)
            }
        }
    }

    fun suggestedName(kind: ExportKind): String {
        val base = Lrc.baseName(_ui.value.fileName ?: "lyrics")
        return if (kind == ExportKind.LRC) "$base.lrc" else _ui.value.fileName ?: "$base.mp3"
    }

    private fun exportNote(): String {
        val n = Lrc.countPlaceholders(_ui.value.lines)
        return if (n > 0) " ($n line${if (n == 1) "" else "s"} saved without a time, as 00:00.00)" else ""
    }

    private fun markClean() {
        baseline = _ui.value.lines
        _ui.update { it.copy(dirty = false) }
        scheduleSave()
    }

    fun copyLrc() {
        val cm = getApplication<Application>().getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("lrc", currentLrc()))
        markClean()
        _messages.tryEmit("Copied .lrc to clipboard" + exportNote())
    }

    fun writeExport(kind: ExportKind, uri: Uri) {
        if (kind == ExportKind.MP3 && blockedByUnreadable()) return
        val lines = _ui.value.lines
        val text = currentLrc()
        val note = exportNote()
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = if (kind == ExportKind.LRC) text.toByteArray(Charsets.UTF_8)
                    else sessions.audioFile.readBytes().let { src -> Id3.update(src, text, tagsFor(src, lines)) }
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
                }
            }
            r.onSuccess { markClean(); _messages.tryEmit((if (kind == ExportKind.LRC) "Saved .lrc" else "Saved .mp3 with embedded lyrics") + note) }
            r.onFailure { _messages.tryEmit("Export failed: ${it.message}") }
        }
    }

    // ---------------------------------------------------------------- learning tags & fixes

    /** Asks first when "ask before overwriting" is on, since this rewrites the song's file. */
    fun requestLearnState(uri: String, name: String, state: LearnState) {
        val action = TagAction.SetLearn(uri, name, state)
        if (_ui.value.settings.confirmOverwrite) _ui.update { it.copy(pendingTag = action) } else runTagAction(action)
    }

    fun requestFixTags() {
        val n = brokenTagSongs().size
        if (n == 0) return run { _messages.tryEmit("Nothing to fix") }
        val action = TagAction.FixAll(n)
        if (_ui.value.settings.confirmOverwrite) _ui.update { it.copy(pendingTag = action) } else runTagAction(action)
    }

    fun dismissTagAction() = _ui.update { it.copy(pendingTag = null) }

    fun confirmTagAction() {
        val a = _ui.value.pendingTag ?: return
        dismissTagAction()
        runTagAction(a)
    }

    private fun brokenTagSongs() = library.index.value?.songs.orEmpty().filter { s -> s.issues.orEmpty().any { it.tagIssue } }

    private fun runTagAction(a: TagAction) {
        when (a) {
            is TagAction.SetLearn -> viewModelScope.launch {
                val ok = rewriteTags(a.uri) { tags, lines -> Search.withLyricsTags(Search.withLearnState(tags, a.state), lines) }
                if (ok) _messages.tryEmit("${a.name}: ${if (a.state == LearnState.NONE) "learning tag removed" else a.state.label}")
                else if (a.uri != currentUri) _messages.tryEmit("Can't write to ${a.name} — choose the music folder again in Settings")
            }
            is TagAction.FixAll -> viewModelScope.launch {
                val songs = brokenTagSongs()
                _ui.update { it.copy(busy = true) }
                var failed = 0
                for (s in songs) {
                    if (!rewriteTags(s.uri) { tags, lines -> Search.withLyricsTags(tags, lines) }) failed++
                }
                _ui.update { it.copy(busy = false) }
                _messages.tryEmit("Fixed tags on ${songs.size - failed} songs" + if (failed > 0) " ($failed could not be written)" else "")
            }
        }
    }

    /**
     * Rewrites only the grouping tags of a song's file. [transform] gets the tags and the lyrics currently in the file.
     * If the song is open in the editor, its working copy gets the same tags so a later Save doesn't undo this.
     * Returns false when the file couldn't be written (for the open song the save-as picker is offered).
     */
    private suspend fun rewriteTags(uriString: String, transform: (List<String>, List<Line>) -> List<String>): Boolean {
        val isOpen = uriString == currentUri
        val res = withContext(Dispatchers.IO) {
            runCatching {
                val resolver = getApplication<Application>().contentResolver
                val uri = uriString.toUri()
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Could not open file")
                val meta = Id3.readMeta(bytes.inputStream())
                val tags = Search.parseTags(meta.grouping)
                val lines = Id3.extractLines(Id3.parse(bytes))
                val newTags = transform(tags, lines)
                // Only touch the working copy (which restarts the player) if its tags actually differ.
                if (isOpen && newTags != sessionTags()) applyTagsToSession(newTags)
                if (newTags != tags) {
                    val out = resolver.openOutputStream(uri, "wt") ?: error("no output stream")
                    out.use { it.write(Id3.setGrouping(bytes, newTags)) }
                }
                Triple(newTags, meta, lines)
            }
        }
        val (tags, meta, _) = res.getOrElse {
            if (isOpen) { _messages.tryEmit("Can't write to the original file — choose where to save instead"); _saveAs.tryEmit(ExportKind.MP3) }
            return false
        }
        library.updateEntry(uriString) { it.copy(tags = tags, issues = Issues.checkSong(tags, meta.lyricsText, meta.syltOnly).toList()) }
        if (isOpen) _ui.update { it.copy(tags = tags) }
        return true
    }

    /** Rewrites the grouping in the working copy and re-prepares the player (the audio moved inside the file). */
    private suspend fun applyTagsToSession(tags: List<String>) {
        val file = sessions.audioFile
        val bytes = Id3.setGrouping(file.readBytes(), tags)
        val tmp = java.io.File(file.path + ".tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(file)
        withContext(Dispatchers.Main) {
            val pos = player.currentPosition
            val wasPlaying = player.playWhenReady
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)), pos)
            player.prepare()
            player.playWhenReady = wasPlaying
        }
    }

    // ---------------------------------------------------------------- fetching lyrics

    private var fetchJob: Job? = null

    private fun lyricsQuery(): LyricsQuery {
        val s = _ui.value
        val meta = runCatching { sessions.audioFile.inputStream().use { Id3.readMeta(it) } }.getOrNull()
        return LyricsQuery(
            title = meta?.title?.ifBlank { null } ?: Lrc.baseName(s.fileName.orEmpty()),
            artist = meta?.artist.orEmpty(), album = meta?.album.orEmpty(),
            durationMs = s.durationMs.takeIf { it > 0 },
        )
    }

    /** Looks for lyrics online; the result is shown in a preview and only applied when accepted. */
    fun fetchLyrics() {
        if (!_ui.value.loaded) return
        fetchJob?.cancel()
        _ui.update { it.copy(fetch = FetchState.Loading) }
        val cfg = _ui.value.settings
        fetchJob = viewModelScope.launch {
            val enabled = cfg.lyricsSources.filter { it.enabled }
            if (enabled.isEmpty()) {
                _ui.update { it.copy(fetch = FetchState.Failed("No lyrics source is turned on. Enable one in Settings > Lyrics sources.")) }
                return@launch
            }
            val timeout = cfg.lyricsTimeoutSec * 1000
            val providers = enabled.map {
                when (it.source) {
                    LyricsSource.LRCLIB -> LrclibProvider(timeout)
                    LyricsSource.NETEASE -> NeteaseProvider(timeout)
                    LyricsSource.LYRICSIFY -> LyricsifyProvider(timeout)
                }
            }
            val outcome = withContext(Dispatchers.IO) { LyricsFetcher.fetch(lyricsQuery(), providers) }
            val state = outcome.result?.let { FetchState.Found(it) } ?: FetchState.Failed(
                if (outcome.errors.size == providers.size) "Couldn't reach any lyrics source. Check your internet connection.\n" + outcome.errors.joinToString("\n")
                else "No lyrics found for this song." + outcome.errors.joinToString("") { "\n$it" }
            )
            _ui.update { if (it.fetch == FetchState.Loading) it.copy(fetch = state) else it }
        }
    }

    fun dismissFetch() { fetchJob?.cancel(); _ui.update { it.copy(fetch = null) } }

    fun setUseRomanization(on: Boolean) =
        _ui.update { s -> (s.fetch as? FetchState.Found)?.let { s.copy(fetch = it.copy(useRomanization = on)) } ?: s }

    fun acceptFetched() {
        val f = _ui.value.fetch as? FetchState.Found ?: return
        _ui.update { it.copy(fetch = null) }
        if (!replaceAllLyrics(f.text)) return
        _messages.tryEmit(if (f.result.synced) "Lyrics from ${f.result.source} loaded — save to keep them"
        else "Plain lyrics from ${f.result.source} loaded — set the times, then save")
    }

    /** Manual route for sources that block the app: the user copies the lyrics in the browser and pastes them here. */
    fun pasteFetchedFromClipboard() {
        val cm = getApplication<Application>().getSystemService(ClipboardManager::class.java)
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(getApplication())?.toString().orEmpty().trim()
        if (text.isEmpty()) return run { _messages.tryEmit("The clipboard is empty") }
        val synced = Lrc.looksLikeLrc(text)
        _ui.update { it.copy(fetch = FetchState.Found(LyricsResult("Clipboard", synced, text, "Pasted text", 1.0))) }
    }

    fun openLyricsInBrowser() {
        val intent = Intent(Intent.ACTION_VIEW, LyricsifyProvider.searchUrl(lyricsQuery()).toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { getApplication<Application>().startActivity(intent) }.onFailure { _messages.tryEmit("No browser found") }
    }

    override fun onCleared() {
        runBlocking { saveNow() }
        player.release()
        tick.release()
    }
}
