package dev.lrceditor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.lrceditor.EditorViewModel
import dev.lrceditor.FetchState
import dev.lrceditor.core.Lrc

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = Studio.Danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun LyricsTextDialog(initial: String, error: String?, onApply: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        title = { Text("Import / edit lyrics text") },
        text = {
            Column {
                if (error != null) Text(error, color = Studio.Danger, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp))
                Text("Paste LRC or plain lyrics. This replaces all current lines.", color = Studio.Dim,
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 420.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onApply(text) }) { Text("Replace lyrics") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun TimeEditDialog(initialMs: Long?, onCommit: (Long?) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(if (initialMs == null) "" else Lrc.formatTime(initialMs)) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit timestamp") },
        text = {
            OutlinedTextField(
                text, { text = it; error = false }, singleLine = true, isError = error,
                label = { Text("mm:ss.xx (empty clears)") },
                supportingText = if (error) ({ Text("Not a valid time") }) else null,
            )
        },
        confirmButton = {
            TextButton(onClick = {
                when (val r = Lrc.parseTimeInput(text)) {
                    is Lrc.TimeInput.Ok -> onCommit(r.ms)
                    Lrc.TimeInput.Invalid -> error = true
                }
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Preview of fetched (or pasted) lyrics. Nothing changes until "Use these lyrics" is pressed. */
@Composable
fun FetchDialog(state: FetchState, vm: EditorViewModel) {
    when (state) {
        FetchState.Loading -> AlertDialog(
            onDismissRequest = vm::dismissFetch,
            title = { Text("Looking for lyrics…") },
            text = { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(16.dp)); Text("Trying your lyrics sources in order") } },
            confirmButton = {},
            dismissButton = { TextButton(onClick = vm::dismissFetch) { Text("Cancel") } },
        )
        is FetchState.Failed -> AlertDialog(
            onDismissRequest = vm::dismissFetch,
            title = { Text("No lyrics found") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(state.message)
                    Spacer(Modifier.height(12.dp))
                    Text("You can look them up yourself and paste them here: open the search in your browser, copy the lyrics, then paste from the clipboard.",
                        color = Studio.Dim, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = vm::openLyricsInBrowser, Modifier.fillMaxWidth()) { Text("Open in browser") }
                    OutlinedButton(onClick = vm::pasteFetchedFromClipboard, Modifier.fillMaxWidth()) { Text("Paste from clipboard") }
                }
            },
            confirmButton = { TextButton(onClick = vm::fetchLyrics) { Text("Try again") } },
            dismissButton = { TextButton(onClick = vm::dismissFetch) { Text("Close") } },
        )
        is FetchState.Found -> {
            val r = state.result
            AlertDialog(
                onDismissRequest = vm::dismissFetch,
                properties = DialogProperties(usePlatformDefaultWidth = false),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                title = { Text("Lyrics from ${r.source}") },
                text = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (r.synced) "SYNCED" else "PLAIN", color = if (r.synced) Studio.Teal else Studio.Warn, style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.width(8.dp))
                            Text(r.matchInfo, color = Studio.Dim, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                        if (!r.synced) Text("Plain lyrics have no times. They are added as untimed lines for you to time, and the song stays flagged until you do.",
                            color = Studio.Warn, style = MaterialTheme.typography.bodySmall)
                        if (r.withRomanization != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Include romanization", Modifier.weight(1f))
                            Switch(state.useRomanization, vm::setUseRomanization)
                        }
                        Spacer(Modifier.height(8.dp))
                        Surface(color = Studio.Panel2, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                            Text(state.text, Modifier.verticalScroll(rememberScrollState()).padding(8.dp), style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Replaces the current lyrics. You can undo this.", color = Studio.Dim, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                },
                confirmButton = { TextButton(onClick = vm::acceptFetched) { Text("Use these lyrics") } },
                dismissButton = { TextButton(onClick = vm::dismissFetch) { Text("Cancel") } },
            )
        }
    }
}
