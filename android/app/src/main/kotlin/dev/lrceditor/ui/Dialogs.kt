package dev.lrceditor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
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
fun LyricsTextDialog(initial: String, onApply: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        title = { Text("Import / edit lyrics text") },
        text = {
            Column {
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
