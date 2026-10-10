package dev.lrceditor.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lrceditor.EditorViewModel
import dev.lrceditor.ExportKind
import dev.lrceditor.Tab
import dev.lrceditor.data.ExportAction

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
    val runExport: (ExportAction) -> Unit = { action ->
        when (vm.export(action)) {
            ExportKind.LRC -> createLrc.launch(vm.suggestedName(ExportKind.LRC))
            ExportKind.MP3 -> createMp3.launch(vm.suggestedName(ExportKind.MP3))
            null -> Unit
        }
    }

    // Back from Songs/Settings returns to the editor (if a song is open); back from the editor leaves the app.
    BackHandler(enabled = ui.tab != Tab.EDITOR && ui.loaded) { vm.showTab(Tab.EDITOR) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = Studio.Bg,
        bottomBar = {
            NavigationBar(containerColor = Studio.Panel) {
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
                Tab.EDITOR -> EditorScreen(vm, ui, runExport)
                Tab.SETTINGS -> SettingsScreen(vm, ui.settings)
            }
            if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }

    if (ui.showLyricsDialog) LyricsTextDialog(vm.currentLrc(), vm::replaceAllLyrics, vm::closeLyricsDialog)
    if (ui.pendingOpen != null) ConfirmDialog(
        title = "Open another song?",
        text = "You have unexported changes to \"${ui.fileName}\". Opening the new file will discard them.",
        confirm = "Open and discard", onConfirm = vm::confirmPendingOpen, onDismiss = vm::cancelPendingOpen,
    )
}
