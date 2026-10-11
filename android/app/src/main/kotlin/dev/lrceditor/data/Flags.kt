package dev.lrceditor.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/** Songs the user marked by hand as having broken lyrics. Kept outside the library index so a rescan never drops them. */
class FlagStore(context: Context) {
    private val file = File(context.filesDir, "broken-lyrics.json")
    private val codec = Json { ignoreUnknownKeys = true }
    private val ser = SetSerializer(String.serializer())

    private val _uris = MutableStateFlow(runCatching { codec.decodeFromString(ser, file.readText()) }.getOrDefault(emptySet()))
    val uris: StateFlow<Set<String>> = _uris.asStateFlow()

    /** Flips the mark on [uri] and returns whether it is now marked. */
    @Synchronized
    fun toggle(uri: String): Boolean {
        val on = uri !in _uris.value
        _uris.value = if (on) _uris.value + uri else _uris.value - uri
        runCatching { file.writeText(codec.encodeToString(ser, _uris.value)) }
        return on
    }
}
