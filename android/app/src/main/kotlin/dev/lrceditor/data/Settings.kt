package dev.lrceditor.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dev.lrceditor.core.Ignore
import dev.lrceditor.core.Realign
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** What swiping a line in the editor does. */
enum class SwipeAction(val label: String) {
    NONE("Nothing"), DELETE("Delete line"), CLEAR_TIME("Clear time");

    companion object {
        fun parse(name: String?, default: SwipeAction) = entries.firstOrNull { it.name == name } ?: default
    }
}

/** Which swipe on a line's timestamp nudges its time (right/up = later, left/down = earlier). */
enum class TimeSwipe(val label: String) {
    OFF("Off"), HORIZONTAL("Swipe left / right"), VERTICAL("Swipe up / down");

    companion object {
        fun parse(name: String?) = entries.firstOrNull { it.name == name } ?: HORIZONTAL
    }
}

/** The lyrics sources, in the default order. */
enum class LyricsSource(val id: String, val label: String, val note: String) {
    LRCLIB("lrclib", "LRCLIB", "Open lyrics database. Synced and plain text."),
    NETEASE("netease", "NetEase Cloud Music", "Large synced catalog, good for Asian music. Unofficial API; may be blocked in some regions."),
    LYRICSIFY("lyricsify", "Lyricsify", "Best effort: the site blocks apps at times. You can then open it in the browser or paste.");
}

data class SourceSetting(val source: LyricsSource, val enabled: Boolean = true)

private fun defaultSources() = LyricsSource.entries.map { SourceSetting(it) }

/** "lrclib:1,netease:0" -> ordered list; unknown ids are dropped and missing sources appended (enabled). */
internal fun parseSources(raw: String?): List<SourceSetting> {
    val parsed = raw?.split(',')?.mapNotNull { part ->
        val (id, on) = part.split(':').let { it.getOrNull(0) to it.getOrNull(1) }
        LyricsSource.entries.firstOrNull { it.id == id }?.let { SourceSetting(it, on != "0") }
    }.orEmpty().distinctBy { it.source }
    return parsed + defaultSources().filter { d -> parsed.none { it.source == d.source } }
}

internal fun formatSources(list: List<SourceSetting>) = list.joinToString(",") { "${it.source.id}:${if (it.enabled) 1 else 0}" }

data class Settings(
    val tickEnabled: Boolean = false,
    val tickVolume: Float = 0.6f,
    val nudgeMs: Long = 100,
    val autoAdvance: Boolean = true,
    /** How many of the first lines are timed again in realign mode. */
    val realignLines: Int = Realign.DEFAULT_LINES,
    /** Ask before writing lyrics into the original MP3 in place. */
    val confirmOverwrite: Boolean = true,
    /** SAF tree URI of the music folder, or null if not chosen yet. */
    val libraryRoot: String? = null,
    /** Folder names (with `*` wildcards) skipped by library scans. */
    val ignoredFolders: List<String> = Ignore.DEFAULTS,
    /** File names (with `*` wildcards) skipped by library scans. */
    val ignoredFiles: List<String> = Ignore.DEFAULT_FILES,
    // What each search result row shows.
    val showArtistAlbum: Boolean = true,
    val showTags: Boolean = true,
    val showLyrics: Boolean = true,
    val showFileName: Boolean = true,
    val showPath: Boolean = false,
    // Editor row swipe gestures.
    val swipeLeft: SwipeAction = SwipeAction.DELETE,
    val swipeRight: SwipeAction = SwipeAction.CLEAR_TIME,
    val timeSwipe: TimeSwipe = TimeSwipe.HORIZONTAL,
    /** Lyrics sources in the order they are tried. */
    val lyricsSources: List<SourceSetting> = defaultSources(),
    val lyricsTimeoutSec: Int = 10,
)

internal val Context.dataStore by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private object K {
        val tick = booleanPreferencesKey("tick")
        val tickVol = floatPreferencesKey("tick_volume")
        val nudge = longPreferencesKey("nudge_ms")
        val auto = booleanPreferencesKey("auto_advance")
        val realignLines = intPreferencesKey("realign_lines")
        val confirmOverwrite = booleanPreferencesKey("confirm_overwrite")
        val root = stringPreferencesKey("library_root")
        val ignored = stringPreferencesKey("ignored_folders")
        val ignoredFiles = stringPreferencesKey("ignored_files")
        val showArtistAlbum = booleanPreferencesKey("show_artist_album")
        val showTags = booleanPreferencesKey("show_tags")
        val showLyrics = booleanPreferencesKey("show_lyrics")
        val showFileName = booleanPreferencesKey("show_file_name")
        val showPath = booleanPreferencesKey("show_path")
        val swipeLeft = stringPreferencesKey("swipe_left")
        val swipeRight = stringPreferencesKey("swipe_right")
        val timeSwipe = stringPreferencesKey("time_swipe")
        val lyricsSources = stringPreferencesKey("lyrics_sources")
        val lyricsTimeout = intPreferencesKey("lyrics_timeout_sec")
    }

    val flow: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            tickEnabled = p[K.tick] ?: false,
            tickVolume = p[K.tickVol] ?: 0.6f,
            nudgeMs = p[K.nudge] ?: 100,
            autoAdvance = p[K.auto] ?: true,
            realignLines = (p[K.realignLines] ?: Realign.DEFAULT_LINES).coerceIn(1, Realign.MAX_LINES),
            confirmOverwrite = p[K.confirmOverwrite] ?: true,
            libraryRoot = p[K.root],
            showArtistAlbum = p[K.showArtistAlbum] ?: true,
            showTags = p[K.showTags] ?: true,
            showLyrics = p[K.showLyrics] ?: true,
            showFileName = p[K.showFileName] ?: true,
            showPath = p[K.showPath] ?: false,
            swipeLeft = SwipeAction.parse(p[K.swipeLeft], SwipeAction.DELETE),
            swipeRight = SwipeAction.parse(p[K.swipeRight], SwipeAction.CLEAR_TIME),
            timeSwipe = TimeSwipe.parse(p[K.timeSwipe]),
            lyricsSources = parseSources(p[K.lyricsSources]),
            lyricsTimeoutSec = p[K.lyricsTimeout] ?: 10,
            ignoredFolders = p[K.ignored]?.split('\n')?.filter { it.isNotBlank() } ?: Ignore.DEFAULTS,
            ignoredFiles = p[K.ignoredFiles]?.split('\n')?.filter { it.isNotBlank() } ?: Ignore.DEFAULT_FILES,
        )
    }

    suspend fun save(s: Settings) {
        context.dataStore.edit { p ->
            p[K.tick] = s.tickEnabled; p[K.tickVol] = s.tickVolume; p[K.nudge] = s.nudgeMs
            p[K.auto] = s.autoAdvance; p[K.realignLines] = s.realignLines; p[K.confirmOverwrite] = s.confirmOverwrite
            p[K.ignored] = s.ignoredFolders.joinToString("\n")
            p[K.ignoredFiles] = s.ignoredFiles.joinToString("\n")
            p[K.showArtistAlbum] = s.showArtistAlbum; p[K.showTags] = s.showTags; p[K.showLyrics] = s.showLyrics
            p[K.showFileName] = s.showFileName; p[K.showPath] = s.showPath
            p[K.swipeLeft] = s.swipeLeft.name; p[K.swipeRight] = s.swipeRight.name
            p[K.timeSwipe] = s.timeSwipe.name
            p[K.lyricsSources] = formatSources(s.lyricsSources); p[K.lyricsTimeout] = s.lyricsTimeoutSec
            if (s.libraryRoot != null) p[K.root] = s.libraryRoot else p.remove(K.root)
        }
    }
}
