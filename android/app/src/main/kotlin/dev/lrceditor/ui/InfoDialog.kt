package dev.lrceditor.ui

import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.lrceditor.FileInfo
import dev.lrceditor.core.Lrc
import dev.lrceditor.core.LyricsState

@Composable
fun InfoDialog(info: FileInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val rows = listOf(
        "File name" to info.fileName,
        "Path" to info.path,
        "Title" to info.title,
        "Artist" to info.artist,
        "Album" to info.album,
        "Album artist" to info.albumArtist,
        "Genre" to info.genre,
        "Year" to info.year,
        "Track" to info.track,
        "Tags" to info.tags.joinToString(" · "),
        "Lyrics" to when (info.lyrics) {
            LyricsState.SYNCED -> "Synced"
            LyricsState.UNSYNCED -> "Unsynced"
            LyricsState.NONE -> "None"
            LyricsState.UNKNOWN -> null
        },
        "Duration" to info.durationMs?.let { Lrc.formatTime(it) },
        "Bitrate" to info.bitrateKbps?.let { "$it kbps (average)" },
        "Sample rate" to info.sampleRateHz?.let { "%.1f kHz".format(it / 1000.0) },
        "Tag version" to info.id3Version,
        "Size" to Formatter.formatFileSize(context, info.sizeBytes),
    ).filter { !it.second.isNullOrBlank() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("File info") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                info.cover?.let {
                    Image(it.asImageBitmap(), "Cover art", Modifier.fillMaxWidth().heightIn(max = 220.dp), contentScale = ContentScale.Fit)
                }
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        rows.forEach { (label, value) ->
                            Column {
                                Text(label, color = Studio.Dim, style = MaterialTheme.typography.labelSmall)
                                Text(value!!)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
