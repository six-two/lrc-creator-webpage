package dev.lrceditor.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dev.lrceditor.core.Ignore
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

data class Settings(
    val tickEnabled: Boolean = false,
    val tickVolume: Float = 0.6f,
    val nudgeMs: Long = 100,
    val autoAdvance: Boolean = true,
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
)

internal val Context.dataStore by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private object K {
        val tick = booleanPreferencesKey("tick")
        val tickVol = floatPreferencesKey("tick_volume")
        val nudge = longPreferencesKey("nudge_ms")
        val auto = booleanPreferencesKey("auto_advance")
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
    }

    val flow: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            tickEnabled = p[K.tick] ?: false,
            tickVolume = p[K.tickVol] ?: 0.6f,
            nudgeMs = p[K.nudge] ?: 100,
            autoAdvance = p[K.auto] ?: true,
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
            ignoredFolders = p[K.ignored]?.split('\n')?.filter { it.isNotBlank() } ?: Ignore.DEFAULTS,
            ignoredFiles = p[K.ignoredFiles]?.split('\n')?.filter { it.isNotBlank() } ?: Ignore.DEFAULT_FILES,
        )
    }

    suspend fun save(s: Settings) {
        context.dataStore.edit { p ->
            p[K.tick] = s.tickEnabled; p[K.tickVol] = s.tickVolume; p[K.nudge] = s.nudgeMs
            p[K.auto] = s.autoAdvance; p[K.confirmOverwrite] = s.confirmOverwrite
            p[K.ignored] = s.ignoredFolders.joinToString("\n")
            p[K.ignoredFiles] = s.ignoredFiles.joinToString("\n")
            p[K.showArtistAlbum] = s.showArtistAlbum; p[K.showTags] = s.showTags; p[K.showLyrics] = s.showLyrics
            p[K.showFileName] = s.showFileName; p[K.showPath] = s.showPath
            p[K.swipeLeft] = s.swipeLeft.name; p[K.swipeRight] = s.swipeRight.name
            p[K.timeSwipe] = s.timeSwipe.name
            if (s.libraryRoot != null) p[K.root] = s.libraryRoot else p.remove(K.root)
        }
    }
}
