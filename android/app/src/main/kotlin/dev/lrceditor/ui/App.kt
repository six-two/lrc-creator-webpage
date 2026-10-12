package dev.lrceditor.ui

import androidx.compose.ui.platform.LocalFocusManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lrceditor.EditorViewModel
import dev.lrceditor.ExportKind
import dev.lrceditor.Tab
import dev.lrceditor.TagAction
import dev.lrceditor.core.LearnState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LrcApp(vm: EditorViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // Closing the keyboard (e.g. with Back) also takes the focus off the text field, so its suggestion dropdown goes away too.
    val focusManager = LocalFocusManager.current
    val keyboardOpen = WindowInsets.isImeVisible
    LaunchedEffect(keyboardOpen) { if (!keyboardOpen) focusManager.clearFocus() }

    val pickFolder = rememberLauncherForActivityResult(OpenWritableTree()) { it?.let(vm::setLibraryRoot) }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    // Export targets are chosen with the system "save as" picker; the file name is pre-filled.
    val createLrc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { vm.writeExport(ExportKind.LRC, it) }
    }
    val createMp3 = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mpeg")) { uri ->
        uri?.let { vm.writeExport(ExportKind.MP3, it) }
    }
    LaunchedEffect(Unit) {
        vm.saveAs.collect { kind ->
            if (kind == ExportKind.LRC) createLrc.launch(vm.suggestedName(kind)) else createMp3.launch(vm.suggestedName(kind))
        }
    }

    // Back returns to the screen we came from (e.g. to the list a song was opened from); with nowhere to go it leaves the app.
    BackHandler(enabled = ui.canGoBack) { vm.back() }

    // Keeps each tab's scroll position and search state while another tab is showing.
    val tabState = rememberSaveableStateHolder()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = Studio.Bg,
        bottomBar = {
            // On short screens drop the icons (and the big pill indicator) to give the content more room.
            val compact = isCompactScreen()
            if (compact) CompactTabBar(ui.tab, ui.dirty, vm::showTab) else NavigationBar(containerColor = Studio.Panel) {
                NavigationBarItem(ui.tab == Tab.LIBRARY, { vm.showTab(Tab.LIBRARY) },
                    icon = { Icon(Icons.Filled.LibraryMusic, null) }, label = { Text("Library") })
                NavigationBarItem(ui.tab == Tab.LEARN, { vm.showTab(Tab.LEARN) },
                    icon = { Icon(Icons.Filled.School, null) }, label = { Text("Learn") })
                NavigationBarItem(ui.tab == Tab.EDITOR, { vm.showTab(Tab.EDITOR) },
                    icon = {
                        BadgedBox(badge = { if (ui.dirty) Badge(containerColor = Studio.Amber) }) { Icon(Icons.Filled.Timer, null) }
                    }, label = { Text("Editor") })
                NavigationBarItem(ui.tab == Tab.SETTINGS, { vm.showTab(Tab.SETTINGS) },
                    icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("Settings") })
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            tabState.SaveableStateProvider(ui.tab) { when (ui.tab) {
                Tab.LIBRARY -> SongsScreen(vm)
                Tab.LEARN -> LearnScreen(vm)
                Tab.EDITOR -> EditorScreen(vm, ui,
                    onSaveLrcAs = { createLrc.launch(vm.suggestedName(ExportKind.LRC)) },
                    onSaveMp3As = { createMp3.launch(vm.suggestedName(ExportKind.MP3)) })
                Tab.SETTINGS -> SettingsScreen(vm, ui.settings)
            } }
            if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }

    if (ui.showLyricsDialog) LyricsTextDialog(ui.rawLyrics ?: vm.currentLrc(), ui.lyricsError, { vm.replaceAllLyrics(it) }, vm::closeLyricsDialog)
    val compare by vm.compare.collectAsStateWithLifecycle()
    compare?.let { CompareDialog(vm, it, ui.isPlaying) }
    if (ui.folderReadOnly) AlertDialog(
        onDismissRequest = vm::dismissFolderReadOnly,
        title = { Text("Music folder is read-only") },
        text = { Text("The app can read your music folder but not change it. Saving lyrics into songs will ask where to save instead, and tag fixes won't work. Choose the folder again and allow changes.") },
        confirmButton = { TextButton(onClick = { vm.dismissFolderReadOnly(); pickFolder.launch(null) }) { Text("Choose folder") } },
        dismissButton = { TextButton(onClick = vm::dismissFolderReadOnly) { Text("Later") } },
    )
    ui.info?.let { InfoDialog(it, vm::closeInfo) }
    if (ui.confirmSave) ConfirmDialog(
        title = "Overwrite the original file?",
        text = "The lyrics will be written into \"${ui.fileName}\" in place, replacing any lyrics it already has. Other tags are kept.",
        confirm = "Save", onConfirm = vm::confirmSave, onDismiss = vm::dismissSaveConfirm,
    )
    if (ui.confirmDiscard) ConfirmDialog(
        title = "Discard all changes?",
        text = "The lyrics of \"${ui.fileName}\" go back to how they were when last loaded or saved. You can still bring the changes back with Undo.",
        confirm = "Discard", onConfirm = vm::confirmDiscard, onDismiss = vm::dismissDiscard,
    )
    ui.pendingTag?.let { a ->
        ConfirmDialog(
            title = if (a is TagAction.FixLeadingEmpty) "Change the lyrics in the files?" else "Change tags in the file?",
            text = when (a) {
                is TagAction.SetLearn -> "The tags of \"${a.name}\" are rewritten in place (${a.state.label.let { if (a.state == LearnState.NONE) "learning tag removed" else "marked $it" }}). Other tags and the lyrics are kept."
                is TagAction.FixLeadingEmpty -> "The empty first line is removed from the lyrics of ${a.uris.size} songs, in place. Nothing else in the lyrics or tags changes."
                is TagAction.FixAll -> "The Lyrics and Karaoke tags of ${a.count} songs are rewritten in place to match their lyrics. Other tags and the lyrics are kept."
            },
            confirm = "Change", onConfirm = vm::confirmTagAction, onDismiss = vm::dismissTagAction,
        )
    }
    ui.fetch?.let { FetchDialog(it, vm) }
    if (ui.pendingOpen != null) ConfirmDialog(
        title = "Open another song?",
        text = "You have unexported changes to \"${ui.fileName}\". Opening the new file will discard them.",
        confirm = "Open and discard", onConfirm = vm::confirmPendingOpen, onDismiss = vm::cancelPendingOpen,
    )
}

/** Text-only tab bar for small screens: a slim row with an amber top line on the selected tab. */
@Composable
private fun CompactTabBar(selected: Tab, dirty: Boolean, onSelect: (Tab) -> Unit) {
    Surface(color = Studio.Panel) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(44.dp)) {
            listOf(Tab.LIBRARY to "Library", Tab.LEARN to "Learn", Tab.EDITOR to "Editor", Tab.SETTINGS to "Settings").forEach { (tab, label) ->
                val on = tab == selected
                Box(
                    Modifier.weight(1f).fillMaxHeight().clickable { onSelect(tab) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (on) Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp).background(Studio.Amber))
                    Text(
                        if (tab == Tab.EDITOR && dirty) "$label •" else label,
                        color = if (on) Studio.Amber else Studio.Dim,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}
