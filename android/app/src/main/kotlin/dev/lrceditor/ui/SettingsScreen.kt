package dev.lrceditor.ui

import android.net.Uri
import android.provider.DocumentsContract
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lrceditor.EditorViewModel
import dev.lrceditor.data.ExportAction
import dev.lrceditor.data.Settings

@Composable
fun SettingsScreen(vm: EditorViewModel, s: Settings) {
    val index by vm.index.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::setLibraryRoot) }
    val onChange = vm::updateSettings

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        SectionTitle("Music library")
        Text(s.libraryRoot?.let { folderLabel(it) } ?: "No folder chosen", color = if (s.libraryRoot == null) Studio.Dim else Studio.Text)
        Text(
            when {
                scan.running -> if (scan.listing) "Looking for songs… ${scan.total} found" else "Scanning… ${scan.found} / ${scan.total}"
                index != null -> "${index!!.songs.size} songs · indexed " +
                    DateUtils.getRelativeTimeSpanString(index!!.builtAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) +
                    (if (index!!.durationMs > 0) " (took ${formatDuration(index!!.durationMs)})" else "")
                else -> "Not indexed yet"
            }, color = Studio.Dim, style = MaterialTheme.typography.bodySmall,
        )
        Text("The index refreshes automatically on launch when it is older than 2 days.", color = Studio.Faint, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickFolder.launch(null) }) { Text("Choose folder") }
            Button(onClick = { vm.rescan() }, enabled = s.libraryRoot != null && !scan.running) { Text("Rescan now") }
        }
        TextButton(onClick = { vm.rescan(full = true) }, enabled = s.libraryRoot != null && !scan.running) {
            Text("Full rescan (re-read every file)")
        }
        if (scan.running) LinearProgressIndicator(Modifier.fillMaxWidth())

        HorizontalDivider(color = Studio.Border)
        SectionTitle("Timing")
        SwitchRow("Tick sound at each line", s.tickEnabled) { onChange(s.copy(tickEnabled = it)) }
        Text("Tick volume ${(s.tickVolume * 100).toInt()}%")
        Slider(s.tickVolume, { onChange(s.copy(tickVolume = it)) }, enabled = s.tickEnabled)

        Text("Nudge amount: ${"%.2f".format(s.nudgeMs / 1000.0)} s")
        Slider(s.nudgeMs / 1000f, { onChange(s.copy(nudgeMs = (it * 20).toLong().coerceAtLeast(1) * 50)) }, valueRange = 0.05f..1f)

        SwitchRow("Auto-advance after setting a time", s.autoAdvance) { onChange(s.copy(autoAdvance = it)) }

        HorizontalDivider(color = Studio.Border)
        SectionTitle("Default export action")
        ExportAction.entries.forEach { a ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(s.exportAction == a, { onChange(s.copy(exportAction = a)) })
                Text(a.label)
            }
        }
        // Keep the chosen library folder: it is not one of the "defaults".
        TextButton(onClick = { onChange(Settings(libraryRoot = s.libraryRoot)) }) { Text("Reset to defaults") }
    }
}

private fun formatDuration(ms: Long): String {
    val s = (ms + 500) / 1000
    return if (s < 60) "${s} s" else "${s / 60} min ${s % 60} s"
}

private fun folderLabel(tree: String): String =
    runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(tree)) }.getOrNull()?.substringAfter(':')?.ifBlank { "Storage root" } ?: tree

@Composable
private fun SectionTitle(t: String) = Text(t, color = Studio.Amber, style = MaterialTheme.typography.titleSmall)

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}
