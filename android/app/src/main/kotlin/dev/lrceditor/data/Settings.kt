package dev.lrceditor.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ExportAction(val label: String) {
    DOWNLOAD_LRC("Save .lrc"),
    EMBED_MP3("Embed & save .mp3"),
    COPY_LRC("Copy .lrc"),
}

data class Settings(
    val tickEnabled: Boolean = false,
    val tickVolume: Float = 0.6f,
    val nudgeMs: Long = 100,
    val autoAdvance: Boolean = false,
    val exportAction: ExportAction = ExportAction.DOWNLOAD_LRC,
    /** SAF tree URI of the music folder, or null if not chosen yet. */
    val libraryRoot: String? = null,
)

internal val Context.dataStore by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private object K {
        val tick = booleanPreferencesKey("tick")
        val tickVol = floatPreferencesKey("tick_volume")
        val nudge = longPreferencesKey("nudge_ms")
        val auto = booleanPreferencesKey("auto_advance")
        val export = stringPreferencesKey("export_action")
        val root = stringPreferencesKey("library_root")
    }

    val flow: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            tickEnabled = p[K.tick] ?: false,
            tickVolume = p[K.tickVol] ?: 0.6f,
            nudgeMs = p[K.nudge] ?: 100,
            autoAdvance = p[K.auto] ?: false,
            exportAction = p[K.export]?.let { runCatching { ExportAction.valueOf(it) }.getOrNull() } ?: ExportAction.DOWNLOAD_LRC,
            libraryRoot = p[K.root],
        )
    }

    suspend fun save(s: Settings) {
        context.dataStore.edit { p ->
            p[K.tick] = s.tickEnabled; p[K.tickVol] = s.tickVolume; p[K.nudge] = s.nudgeMs
            p[K.auto] = s.autoAdvance; p[K.export] = s.exportAction.name
            if (s.libraryRoot != null) p[K.root] = s.libraryRoot else p.remove(K.root)
        }
    }
}
