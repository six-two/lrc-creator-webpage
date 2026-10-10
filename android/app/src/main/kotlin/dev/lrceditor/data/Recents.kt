package dev.lrceditor.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Recent(val uri: String, val fileName: String, val title: String, val artist: String, val editedAt: Long)

/** Most recently opened/edited songs, newest first. */
class RecentsStore(context: Context) {
    private val file = File(context.filesDir, "recents.json")
    private val codec = Json { ignoreUnknownKeys = true }
    private val ser = ListSerializer(Recent.serializer())

    private val _items = MutableStateFlow(
        runCatching { codec.decodeFromString(ser, file.readText()) }.getOrDefault(emptyList())
    )
    val items: StateFlow<List<Recent>> = _items.asStateFlow()

    @Synchronized
    fun touch(r: Recent) {
        _items.value = (listOf(r) + _items.value.filter { it.uri != r.uri }).take(MAX)
        persist()
    }

    /** Bumps the edit time of an existing entry (called when the song is edited or exported). */
    @Synchronized
    fun bump(uri: String) {
        val cur = _items.value.firstOrNull { it.uri == uri } ?: return
        touch(cur.copy(editedAt = System.currentTimeMillis()))
    }

    @Synchronized
    fun remove(uri: String) {
        _items.value = _items.value.filter { it.uri != uri }
        persist()
    }

    private fun persist() = runCatching { file.writeText(codec.encodeToString(ser, _items.value)) }

    companion object { const val MAX = 100 }
}
