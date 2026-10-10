package dev.lrceditor.ui

import android.net.Uri
import android.provider.DocumentsContract
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import dev.lrceditor.core.Ignore
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lrceditor.EditorViewModel
import dev.lrceditor.data.Settings
import dev.lrceditor.data.SwipeAction
import dev.lrceditor.data.TimeSwipe

private enum class SettingsPage(val title: String, val summary: String, val icon: ImageVector) {
    LIBRARY("Library", "Music folder, scanning, ignored folders and files", Icons.Filled.LibraryMusic),
    SEARCH("Search", "What each song shows in the Songs tab", Icons.Filled.Search),
    EDITOR("Editor", "Timing, tick sound and swipe gestures", Icons.Filled.Timer),
    SAVING("Saving", "Writing lyrics into the MP3", Icons.Filled.Save),
}

@Composable
fun SettingsScreen(vm: EditorViewModel, s: Settings) {
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    // Back from a sub page returns to the list (registered after the app-level handler, so it wins).
    BackHandler(enabled = page != null) { page = null }

    Column(Modifier.fillMaxSize()) {
        val current = page
        Row(Modifier.fillMaxWidth().padding(start = if (current == null) 16.dp else 4.dp, top = 8.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (current != null) IconButton({ page = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to settings") }
            Text(current?.title ?: "Settings", style = MaterialTheme.typography.headlineSmall)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (current) {
                null -> {
                    SettingsPage.entries.forEach { p ->
                        ListItem(
                            headlineContent = { Text(p.title) }, supportingContent = { Text(p.summary) },
                            leadingContent = { Icon(p.icon, null, tint = Studio.Amber) },
                            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Studio.Faint) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { page = p },
                        )
                        HorizontalDivider(color = Studio.Border.copy(alpha = 0.5f))
                    }
                    // Keep the chosen library folder: it is not one of the "defaults".
                    TextButton(onClick = { vm.updateSettings(Settings(libraryRoot = s.libraryRoot)) }) { Text("Reset all to defaults") }
                }
                SettingsPage.LIBRARY -> LibrarySettings(vm, s)
                SettingsPage.SEARCH -> SearchSettings(s, vm::updateSettings)
                SettingsPage.EDITOR -> EditorSettings(s, vm::updateSettings)
                SettingsPage.SAVING -> SavingSettings(s, vm::updateSettings)
            }
        }
    }
}

@Composable
private fun ColumnScope.LibrarySettings(vm: EditorViewModel, s: Settings) {
    val index by vm.index.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::setLibraryRoot) }
    val onChange = vm::updateSettings

    SectionTitle("Music folder")
    Text(s.libraryRoot?.let { folderLabel(it) } ?: "No folder chosen", color = if (s.libraryRoot == null) Studio.Dim else Studio.Text)
    OutlinedButton(onClick = { pickFolder.launch(null) }) { Text("Choose folder") }

    SectionTitle("Scanning")
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
    Button(onClick = { vm.rescan() }, enabled = s.libraryRoot != null && !scan.running) { Text("Rescan now") }
    TextButton(onClick = { vm.rescan(full = true) }, enabled = s.libraryRoot != null && !scan.running) {
        Text("Full rescan (re-read every file)")
    }
    if (scan.running) LinearProgressIndicator(Modifier.fillMaxWidth())

    HorizontalDivider(color = Studio.Border)
    SectionTitle("Ignored")
    IgnoreList("Ignored folders", "Folders with these names are skipped anywhere in the music folder.", "Add folder name",
        s.ignoredFolders, Ignore.DEFAULTS) { onChange(s.copy(ignoredFolders = it)) }
    IgnoreList("Ignored files", "Files with these names are not indexed.", "Add file name pattern",
        s.ignoredFiles, Ignore.DEFAULT_FILES) { onChange(s.copy(ignoredFiles = it)) }
}

@Composable
private fun ColumnScope.SearchSettings(s: Settings, onChange: (Settings) -> Unit) {
    Text("What each song shows in the Songs tab. The lyrics status is hidden automatically when you filter for a specific one (unsynced, synced, none).",
        color = Studio.Dim, style = MaterialTheme.typography.bodySmall)
    SwitchRow("Artist and album", s.showArtistAlbum) { onChange(s.copy(showArtistAlbum = it)) }
    SwitchRow("Tags", s.showTags) { onChange(s.copy(showTags = it)) }
    SwitchRow("Lyrics status", s.showLyrics) { onChange(s.copy(showLyrics = it)) }
    SwitchRow("File name", s.showFileName) { onChange(s.copy(showFileName = it)) }
    SwitchRow("Relative path (replaces file name)", s.showPath) { onChange(s.copy(showPath = it)) }
}

@Composable
private fun ColumnScope.EditorSettings(s: Settings, onChange: (Settings) -> Unit) {
    SectionTitle("Timing")
    SwitchRow("Tick sound at each line", s.tickEnabled) { onChange(s.copy(tickEnabled = it)) }
    Text("Tick volume ${(s.tickVolume * 100).toInt()}%")
    Slider(s.tickVolume, { onChange(s.copy(tickVolume = it)) }, enabled = s.tickEnabled)

    Text("Nudge amount: ${"%.2f".format(s.nudgeMs / 1000.0)} s")
    Slider(s.nudgeMs / 1000f, { onChange(s.copy(nudgeMs = (it * 20).toLong().coerceAtLeast(1) * 50)) }, valueRange = 0.05f..1f)

    SwitchRow("Auto-advance after setting a time", s.autoAdvance) { onChange(s.copy(autoAdvance = it)) }

    HorizontalDivider(color = Studio.Border)
    SectionTitle("Swipe gestures")
    Text("Swipe a line in the editor to act on it. Double-tap a line's text to edit it with the full row width.",
        color = Studio.Dim, style = MaterialTheme.typography.bodySmall)
    RadioChoice("Swipe a line left", s.swipeLeft, SwipeAction.entries, { it.label }) { onChange(s.copy(swipeLeft = it)) }
    RadioChoice("Swipe a line right", s.swipeRight, SwipeAction.entries, { it.label }) { onChange(s.copy(swipeRight = it)) }
    RadioChoice("Nudge a time by swiping its timestamp", s.timeSwipe, TimeSwipe.entries, { it.label }) { onChange(s.copy(timeSwipe = it)) }
    Text("Right or up makes the time later, left or down earlier; each step moves it by the nudge amount. Lines without a time are left alone.",
        color = Studio.Dim, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ColumnScope.SavingSettings(s: Settings, onChange: (Settings) -> Unit) {
    SwitchRow("Ask before overwriting the original MP3", s.confirmOverwrite) { onChange(s.copy(confirmOverwrite = it)) }
    Text("The save button writes the lyrics into the MP3 you are editing. Needs write access to the music folder; choose the folder again if saving fails.",
        color = Studio.Dim, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun <T> RadioChoice(label: String, current: T, options: List<T>, text: (T) -> String, onChange: (T) -> Unit) {
    Text(label, style = MaterialTheme.typography.titleSmall)
    options.forEach { o ->
        Row(Modifier.fillMaxWidth().selectable(current == o, onClick = { onChange(o) }, role = Role.RadioButton), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(current == o, null)
            Text(text(o), Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IgnoreList(
    title: String, description: String, addLabel: String,
    folders: List<String>, defaults: List<String>, onChange: (List<String>) -> Unit,
) {
    var text by rememberSaveable(title) { mutableStateOf("") }
    fun add() {
        val t = text.trim().trim('/')
        if (t.isNotEmpty() && folders.none { it.equals(t, ignoreCase = true) }) onChange(folders + t)
        text = ""
    }
    Text(title, style = MaterialTheme.typography.titleSmall)
    Text("$description * is a wildcard. Applies on the next rescan.",
        color = Studio.Dim, style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        folders.forEach { f ->
            InputChip(true, { onChange(folders - f) }, label = { Text(f) },
                trailingIcon = { Icon(Icons.Filled.Close, "Remove $f", Modifier.size(16.dp)) })
        }
    }
    OutlinedTextField(
        text, { text = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(addLabel) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { add() }),
        trailingIcon = { IconButton(::add, enabled = text.isNotBlank()) { Icon(Icons.Filled.Add, "Add") } },
    )
    TextButton(onClick = { onChange(defaults) }) { Text("Restore defaults") }
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
