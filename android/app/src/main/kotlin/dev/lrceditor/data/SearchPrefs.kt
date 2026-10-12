package dev.lrceditor.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.lrceditor.core.LyricsFilter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class SongsMode { RECENT, ATTENTION, TITLE, FILENAME, TAGS }

/** Everything about the last search; restored on next launch. */
@Serializable
data class SearchPrefs(
    val mode: SongsMode = SongsMode.RECENT,
    val query: String = "",        // song-title search
    val fileQuery: String = "",    // file-name search
    val fuzzy: Boolean = true,
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    /** Fix list: problem categories (by label) a song must all have / must have none of. */
    val fixInclude: List<String> = emptyList(),
    val fixExclude: List<String> = emptyList(),
    /** Fix list without included categories: also list songs that only have warnings. */
    val fixWarnings: Boolean = false,
    /** Shared by all search pages. */
    val lyrics: LyricsFilter = LyricsFilter.ALL,
)

class SearchPrefsStore(private val context: Context) {
    private val key = stringPreferencesKey("search_prefs")
    private val codec = Json { ignoreUnknownKeys = true }

    suspend fun load(): SearchPrefs =
        context.dataStore.data.map { it[key] }.first()
            ?.let { runCatching { codec.decodeFromString(SearchPrefs.serializer(), it) }.getOrNull() }
            ?: SearchPrefs()

    suspend fun save(p: SearchPrefs) {
        context.dataStore.edit { it[key] = codec.encodeToString(SearchPrefs.serializer(), p) }
    }
}
