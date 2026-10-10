package dev.lrceditor

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.provider.OpenableColumns
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.lrceditor.core.Id3
import dev.lrceditor.core.Line
import dev.lrceditor.core.Lrc
import dev.lrceditor.core.LyricsFilter
import dev.lrceditor.core.NameField
import dev.lrceditor.core.Search
import dev.lrceditor.core.TagCount
import dev.lrceditor.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class ExportKind { LRC, MP3 }

enum class Tab { SONGS, EDITOR, SETTINGS }

data class UiState(
    val loaded: Boolean = false,
    val fileName: String? = null,
    val lines: List<Line> = emptyList(),
    val selectedId: Long? = null,
    val playingId: Long? = null,
    val editingTextId: Long? = null,
    val editingTimeId: Long? = null,
    val dirty: Boolean = false,
    val isPlaying: Boolean = false,
    val speed: Float = 1f,
    val durationMs: Long = 0,
    val settings: Settings = Settings(),
    val showLyricsDialog: Boolean = false,
    val tab: Tab = Tab.SONGS,
    /** A file was shared in while unsaved work is open; waiting for the user to confirm replacing it. */
    val pendingOpen: Uri? = null,
    val busy: Boolean = false,
)

class EditorViewModel(app: Application) : AndroidViewModel(app) {
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
                SongsMode.RECENT -> false
            }
            val base = when {
                p.mode == SongsMode.RECENT -> emptyList()
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
            val root = settingsStore.flow.first().libraryRoot ?: return@launch
            if (library.isOutdated(root)) library.runScan(root)
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

    private fun restoreSession() {
        val (saved, lines) = sessions.load() ?: return
        currentUri = saved.uri
        activate(saved.fileName, lines, saved.dirty)
        _ui.update { it.copy(tab = Tab.EDITOR) }
        _messages.tryEmit("Session restored")
    }

    /** Entry point for share / "open with" intents. */
    fun openUri(uri: Uri) {
        val s = _ui.value
        if (s.loaded && uri.toString() == currentUri) { showTab(Tab.EDITOR); return } // already open
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
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "song.mp3"
                    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Could not open file")
                    if (!Lrc.looksLikeMp3(bytes)) return@runCatching null
                    sessions.audioFile.writeBytes(bytes)
                    name to Id3.extractLines(Id3.parse(bytes))
                }
            }
            _ui.update { it.copy(busy = false) }
            result.onFailure { _messages.tryEmit("Could not read file: ${it.message}") }
            result.onSuccess { r ->
                if (r == null) {
                    _messages.tryEmit("That doesn't look like a valid MP3 file — no MP3 header found.")
                    return@onSuccess
                }
                val (name, lines) = r
                currentUri = uri.toString()
                val entry = library.index.value?.songs?.firstOrNull { it.uri == currentUri }
                recentsStore.touch(Recent(currentUri!!, name, entry?.title.orEmpty(), entry?.artist.orEmpty(), System.currentTimeMillis()))
                activate(name, lines, dirty = false)
                _ui.update { it.copy(tab = Tab.EDITOR) }
                scheduleSave()
                _messages.tryEmit(if (lines.isEmpty()) "No existing lyrics found — starting blank" else "Loaded existing lyrics from the file")
                if (lines.isEmpty()) _ui.update { it.copy(showLyricsDialog = true) }
            }
        }
    }

    private fun activate(name: String, lines: List<Line>, dirty: Boolean) {
        _ui.update {
            it.copy(loaded = true, fileName = name, lines = lines, selectedId = lines.firstOrNull()?.id,
                playingId = null, editingTextId = null, editingTimeId = null, dirty = dirty, speed = 1f)
        }
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(sessions.audioFile)))
        player.setPlaybackParameters(PlaybackParameters(1f))
        player.prepare()
        lastTickPos = -1
    }

    // ---------------------------------------------------------------- line editing

    private fun mutateLines(markDirty: Boolean = true, f: (List<Line>) -> List<Line>) {
        _ui.update { it.copy(lines = f(it.lines), dirty = it.dirty || markDirty) }
        if (markDirty) scheduleSave()
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
            runCatching { sessions.save(s.fileName, s.lines, s.dirty, currentUri) }
            currentUri?.let { recentsStore.bump(it) }
        }
    }

    fun select(id: Long) = _ui.update { it.copy(selectedId = id) }

    fun moveSelection(delta: Int) {
        val s = _ui.value
        if (s.lines.isEmpty()) return
        val idx = s.lines.indexOfFirst { it.id == s.selectedId }
        val n = if (idx == -1) (if (delta > 0) 0 else s.lines.lastIndex) else (idx + delta).coerceIn(0, s.lines.lastIndex)
        select(s.lines[n].id)
    }

    fun nudge(id: Long, deltaMs: Long) = mutateLines { ls ->
        ls.map { if (it.id == id) it.copy(timeMs = maxOf(0, (it.timeMs ?: 0) + deltaMs)) else it }
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
        mutateLines { ls -> ls.map { if (it.id == id) it.copy(timeMs = now) else it } }
        if (s.settings.autoAdvance) {
            val idx = s.lines.indexOfFirst { it.id == id }
            s.lines.getOrNull(idx + 1)?.let { select(it.id) }
        }
    }

    fun clearTimeForSelected() {
        val id = _ui.value.selectedId ?: return run { _messages.tryEmit("Select a line first") }
        mutateLines { ls -> ls.map { if (it.id == id) it.copy(timeMs = null) else it } }
    }

    fun setTime(id: Long, ms: Long?) = mutateLines { ls -> ls.map { if (it.id == id) it.copy(timeMs = ms) else it } }
    fun setText(id: Long, text: String) = mutateLines { ls -> ls.map { if (it.id == id) it.copy(text = text) else it } }

    /** Inserts an empty ("pause") line at the playhead. Position rule depends on the auto-advance setting (spec §6). */
    fun insertPauseLine() {
        val s = _ui.value
        val line = Lrc.newLine(player.currentPosition, "")
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

    fun replaceAllLyrics(text: String) {
        val lines = Lrc.parseAny(text)
        _ui.update { it.copy(selectedId = lines.firstOrNull()?.id, playingId = null, editingTextId = null, editingTimeId = null) }
        mutateLines { lines }
        closeLyricsDialog()
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
    fun showTab(tab: Tab) = _ui.update { it.copy(tab = tab) }

    fun updateSettings(s: Settings) {
        _ui.update { it.copy(settings = s) }
        viewModelScope.launch { settingsStore.save(s) }
    }

    // ---------------------------------------------------------------- library & search

    fun setLibraryRoot(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val old = _ui.value.settings.libraryRoot
        if (old != null && old != uri.toString()) {
            runCatching { resolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        updateSettings(_ui.value.settings.copy(libraryRoot = uri.toString()))
        rescan()
    }

    fun rescan(full: Boolean = false) {
        val root = _ui.value.settings.libraryRoot ?: return run { _messages.tryEmit("Choose a music folder first") }
        viewModelScope.launch {
            if (library.scan.value.running) return@launch
            runCatching { library.runScan(root, full) }
                .onSuccess { _messages.tryEmit("Library indexed: ${library.index.value?.songs?.size ?: 0} songs") }
                .onFailure { _messages.tryEmit("Scan failed: ${it.message}") }
        }
    }

    fun updateSearch(f: (SearchPrefs) -> SearchPrefs) = _search.update(f)

    fun openSong(uriString: String) = openUri(Uri.parse(uriString))

    fun removeRecent(uri: String) = recentsStore.remove(uri)

    // ---------------------------------------------------------------- export

    fun export(action: ExportAction): ExportKind? = when (action) {
        ExportAction.DOWNLOAD_LRC -> ExportKind.LRC
        ExportAction.EMBED_MP3 -> ExportKind.MP3
        ExportAction.COPY_LRC -> { copyLrc(); null }
    }

    fun suggestedName(kind: ExportKind): String {
        val base = Lrc.baseName(_ui.value.fileName ?: "lyrics")
        return if (kind == ExportKind.LRC) "$base.lrc" else _ui.value.fileName ?: "$base.mp3"
    }

    private fun exportNote(): String {
        val n = Lrc.countPlaceholders(_ui.value.lines)
        return if (n > 0) " ($n line${if (n == 1) "" else "s"} used a placeholder timestamp)" else ""
    }

    private fun markClean() {
        _ui.update { it.copy(dirty = false) }
        scheduleSave()
    }

    private fun copyLrc() {
        val cm = getApplication<Application>().getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("lrc", currentLrc()))
        markClean()
        _messages.tryEmit("Copied .lrc to clipboard" + exportNote())
    }

    fun writeExport(kind: ExportKind, uri: Uri) {
        val text = currentLrc()
        val note = exportNote()
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = if (kind == ExportKind.LRC) text.toByteArray(Charsets.UTF_8)
                    else Id3.embedLyrics(sessions.audioFile.readBytes(), text)
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
                }
            }
            r.onSuccess { markClean(); _messages.tryEmit((if (kind == ExportKind.LRC) "Saved .lrc" else "Saved .mp3 with embedded lyrics") + note) }
            r.onFailure { _messages.tryEmit("Export failed: ${it.message}") }
        }
    }

    override fun onCleared() {
        runBlocking { saveNow() }
        player.release()
        tick.release()
    }
}
