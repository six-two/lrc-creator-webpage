package dev.lrceditor.ui

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
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lrceditor.EditorViewModel
import dev.lrceditor.ExportKind
import dev.lrceditor.Tab

@Composable
fun LrcApp(vm: EditorViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

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

    // Back from Songs/Settings returns to the editor (if a song is open); back from the editor leaves the app.
    BackHandler(enabled = ui.tab != Tab.EDITOR && ui.loaded) { vm.showTab(Tab.EDITOR) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = Studio.Bg,
        bottomBar = {
            // On short screens drop the icons (and the big pill indicator) to give the content more room.
            val compact = isCompactScreen()
            if (compact) CompactTabBar(ui.tab, ui.dirty, vm::showTab) else NavigationBar(containerColor = Studio.Panel) {
                NavigationBarItem(ui.tab == Tab.SONGS, { vm.showTab(Tab.SONGS) },
                    icon = { Icon(Icons.Filled.LibraryMusic, null) }, label = { Text("Songs") })
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
            when (ui.tab) {
                Tab.SONGS -> SongsScreen(vm)
                Tab.EDITOR -> EditorScreen(vm, ui,
                    onSaveLrcAs = { createLrc.launch(vm.suggestedName(ExportKind.LRC)) },
                    onSaveMp3As = { createMp3.launch(vm.suggestedName(ExportKind.MP3)) })
                Tab.SETTINGS -> SettingsScreen(vm, ui.settings)
            }
            if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }

    if (ui.showLyricsDialog) LyricsTextDialog(vm.currentLrc(), vm::replaceAllLyrics, vm::closeLyricsDialog)
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
            listOf(Tab.SONGS to "Songs", Tab.EDITOR to "Editor", Tab.SETTINGS to "Settings").forEach { (tab, label) ->
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
