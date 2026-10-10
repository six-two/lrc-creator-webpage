package dev.lrceditor.data

import android.content.Context
import dev.lrceditor.core.Lrc
import dev.lrceditor.core.Line
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class SavedLine(val timeMs: Long?, val text: String)

@Serializable
data class SavedSession(val fileName: String, val lines: List<SavedLine>, val dirty: Boolean, val uri: String? = null)

/**
 * Persists the working session in app-private storage: a copy of the MP3 (current.mp3) plus the lyric
 * data (session.json). Because the audio is copied in on open, resuming needs no URI permission and
 * works for share-intent URIs, which only carry a temporary grant.
 */
class SessionStore(context: Context) {
    private val dir = context.filesDir
    val audioFile = File(dir, "current.mp3")
    private val json = File(dir, "session.json")
    private val codec = Json { ignoreUnknownKeys = true }

    fun save(fileName: String, lines: List<Line>, dirty: Boolean, uri: String?) {
        val s = SavedSession(fileName, lines.map { SavedLine(it.timeMs, it.text) }, dirty, uri)
        val tmp = File(dir, "session.json.tmp")
        tmp.writeText(codec.encodeToString(SavedSession.serializer(), s))
        tmp.renameTo(json)
    }

    fun load(): Pair<SavedSession, List<Line>>? {
        if (!json.exists() || !audioFile.exists()) return null
        return runCatching {
            val s = codec.decodeFromString(SavedSession.serializer(), json.readText())
            s to s.lines.map { Lrc.newLine(it.timeMs, it.text) }
        }.getOrNull()
    }

    fun clear() {
        json.delete()
        audioFile.delete()
    }
}
