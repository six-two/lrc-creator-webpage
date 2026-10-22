package dev.lrceditor.ui

import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.Icons
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import android.content.ClipboardManager
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
    // All of the text starts selected, so typing or pasting replaces it.
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val context = LocalContext.current
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
                OutlinedButton(onClick = {
                    val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                    val pasted = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    if (pasted.isEmpty()) Toast.makeText(context, "The clipboard is empty", Toast.LENGTH_SHORT).show()
                    else text = TextFieldValue(pasted, TextRange(pasted.length))
                }) { Icon(Icons.Filled.ContentPaste, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Replace text with clipboard") }
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 420.dp).focusRequester(focus))
            }
        },
        confirmButton = { TextButton(onClick = { onApply(text.text) }) { Text("Replace lyrics") } },
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
