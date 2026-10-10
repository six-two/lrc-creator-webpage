package dev.lrceditor.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lrceditor.EditorViewModel
import dev.lrceditor.Tab
import dev.lrceditor.UiState
import dev.lrceditor.core.Line
import dev.lrceditor.core.Lrc
import dev.lrceditor.data.ExportAction

@Composable
fun EditorScreen(vm: EditorViewModel, ui: UiState, runExport: (ExportAction) -> Unit) {
    if (!ui.loaded) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.GraphicEq, null, tint = Studio.Faint, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(12.dp))
            Text("No song open", style = MaterialTheme.typography.titleMedium)
            Text("Pick one in Songs, or share an MP3 to this app.", color = Studio.Dim)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { vm.showTab(Tab.SONGS) }) { Text("Choose a song") }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        EditorHeader(vm, ui, runExport)
        HorizontalDivider(color = Studio.Border)
        val listState = rememberLazyListState()

        // Keep the line being played in view while playing (selection never moves playback, and vice versa).
        LaunchedEffect(ui.playingId) {
            val idx = ui.lines.indexOfFirst { it.id == ui.playingId }
            if (ui.isPlaying && idx >= 0) listState.animateScrollToItem(idx)
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            if (ui.lines.isEmpty()) item {
                Text("No lyrics yet. Use the lyrics button above to paste some.", color = Studio.Dim,
                    modifier = Modifier.padding(24.dp))
            }
            itemsIndexed(ui.lines, key = { _, l -> l.id }) { i, line ->
                val prevTime = ui.lines.getOrNull(i - 1)?.timeMs
                val outOfOrder = line.timeMs != null && prevTime != null && line.timeMs < prevTime
                LineRow(vm, ui, line, outOfOrder)
                HorizontalDivider(color = Studio.Border.copy(alpha = 0.5f))
            }
        }
        PlayerBar(vm, ui)
    }
}

@Composable
private fun EditorHeader(vm: EditorViewModel, ui: UiState, runExport: (ExportAction) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        // Filename wraps rather than ellipsizing.
        Text(ui.fileName.orEmpty(), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = vm::openLyricsDialog, contentPadding = PaddingValues(horizontal = 12.dp)) {
                Icon(Icons.Filled.Notes, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Lyrics")
            }
            Spacer(Modifier.width(8.dp))
            ExportSplitButton(ui.settings.exportAction, runExport)
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun ExportSplitButton(default: ExportAction, run: (ExportAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row {
        // Each half has its own partial corner radius (no clipping container, so the menu isn't clipped).
        Button(onClick = { run(default) }, shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp),
            contentPadding = PaddingValues(start = 14.dp, end = 10.dp)) { Text(default.label) }
        Spacer(Modifier.width(1.dp))
        Box {
            Button(onClick = { open = true }, shape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp),
                contentPadding = PaddingValues(horizontal = 6.dp)) { Icon(Icons.Filled.ArrowDropDown, "More export options") }
            DropdownMenu(open, { open = false }) {
                ExportAction.entries.forEach { a ->
                    DropdownMenuItem(text = { Text(a.label) }, onClick = { open = false; run(a) })
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LineRow(vm: EditorViewModel, ui: UiState, line: Line, outOfOrder: Boolean) {
    val selected = ui.selectedId == line.id
    val playing = ui.playingId == line.id
    val editing = ui.editingTextId == line.id

    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) Studio.Teal.copy(alpha = 0.10f) else Color.Transparent)
            // Selected = static teal left border (structural, no layout shift).
            .drawBehind { if (selected) drawLine(Studio.Teal, Offset(2.dp.toPx(), 0f), Offset(2.dp.toPx(), size.height), 4.dp.toPx()) }
            .combinedClickable(onClick = { vm.select(line.id) })
            .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.width(76.dp)) {
            Text(
                Lrc.formatTime(line.timeMs), fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                color = if (line.timeMs == null) Studio.Faint else Studio.Amber,
                modifier = Modifier.combinedClickable(onClick = { vm.select(line.id) }, onDoubleClick = { vm.startEditTime(line.id) })
                    .padding(vertical = 6.dp),
            )
            Row {
                MiniButton(Icons.Filled.Remove, "Nudge earlier") { vm.nudge(line.id, -ui.settings.nudgeMs) }
                MiniButton(Icons.Filled.Add, "Nudge later") { vm.nudge(line.id, ui.settings.nudgeMs) }
            }
        }
        PlayLineButton(playing, enabled = line.timeMs != null) { vm.playFromLine(line.id) }

        Box(Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 6.dp)) {
            if (editing) TextEditor(line.text, onCommit = { vm.setText(line.id, it); vm.startEditText(null) }, onCancel = { vm.startEditText(null) })
            else {
                val blank = line.text.isEmpty()
                Text(
                    if (blank) "(pause)" else line.text,
                    // Now-playing = recolor existing text (no bold, to avoid reflow).
                    color = if (playing) Studio.Amber else if (blank) Studio.Faint else Studio.Text,
                    fontStyle = if (blank) FontStyle.Italic else FontStyle.Normal,
                    modifier = Modifier.fillMaxWidth().combinedClickable(
                        onClick = { vm.select(line.id) },
                        onDoubleClick = { if (!editing) vm.startEditText(line.id) },
                    ),
                )
            }
        }
        Column {
            if (outOfOrder) Icon(Icons.Filled.Warning, "Out of order", tint = Studio.Warn, modifier = Modifier.padding(8.dp).size(16.dp))
            else Spacer(Modifier.height(0.dp))
            MiniButton(Icons.Filled.Edit, "Edit text") { vm.startEditText(line.id) }
            MiniButton(Icons.Filled.Delete, "Delete line", tint = Studio.Danger) { vm.delete(line.id) }
        }
    }

    if (ui.editingTimeId == line.id) {
        TimeEditDialog(line.timeMs, onCommit = { vm.setTime(line.id, it); vm.startEditTime(null) }, onDismiss = { vm.startEditTime(null) })
    }
}

@Composable
private fun TextEditor(initial: String, onCommit: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    Column {
        // Multi-line: Enter inserts a newline; commit with the check button.
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth())
        Row {
            TextButton(onClick = onCancel) { Text("Cancel") }
            TextButton(onClick = { onCommit(text) }) { Text("Done") }
        }
    }
}

@Composable
private fun MiniButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, tint: Color = Studio.Dim, onClick: () -> Unit) {
    IconButton(onClick, Modifier.size(36.dp)) { Icon(icon, desc, tint = tint, modifier = Modifier.size(18.dp)) }
}

@Composable
private fun PlayLineButton(playing: Boolean, enabled: Boolean, onClick: () -> Unit) {
    // Pulse via graphicsLayer (transform only), so it never affects layout.
    val t = rememberInfiniteTransition(label = "pulse")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "p")
    IconButton(onClick, Modifier.size(40.dp).graphicsLayer {
        if (playing) { val s = 1f + 0.18f * pulse; scaleX = s; scaleY = s; alpha = 1f - 0.4f * pulse }
    }, enabled = enabled) {
        Icon(Icons.Filled.PlayArrow, "Play from this line", tint = if (playing) Studio.Amber else Studio.Dim)
    }
}

@Composable
private fun PlayerBar(vm: EditorViewModel, ui: UiState) {
    val pos by vm.positionMs.collectAsState()
    var dragging by remember { mutableStateOf<Float?>(null) }
    val dur = ui.durationMs.coerceAtLeast(1)
    var speedMenu by remember { mutableStateOf(false) }

    Surface(color = Studio.Panel, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Lrc.formatTime(dragging?.toLong() ?: pos), fontFamily = FontFamily.Monospace, color = Studio.Amber, fontSize = 13.sp)
                Slider(
                    value = (dragging ?: pos.toFloat()).coerceIn(0f, dur.toFloat()),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = { dragging?.let { vm.seekTo(it.toLong()) }; dragging = null },
                    valueRange = 0f..dur.toFloat(),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(Lrc.formatTime(ui.durationMs), fontFamily = FontFamily.Monospace, color = Studio.Dim, fontSize = 13.sp)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { vm.seekBy(-5000) }, contentPadding = PaddingValues(4.dp)) { Text("−5s") }
                TextButton(onClick = { vm.seekBy(-1000) }, contentPadding = PaddingValues(4.dp)) { Text("−1s") }
                FilledIconButton(onClick = vm::togglePlay) {
                    Icon(if (ui.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (ui.isPlaying) "Pause" else "Play")
                }
                TextButton(onClick = { vm.seekBy(1000) }, contentPadding = PaddingValues(4.dp)) { Text("+1s") }
                TextButton(onClick = { vm.seekBy(5000) }, contentPadding = PaddingValues(4.dp)) { Text("+5s") }
                Box {
                    TextButton(onClick = { speedMenu = true }, contentPadding = PaddingValues(4.dp)) { Text("${(ui.speed * 100).toInt()}%") }
                    DropdownMenu(speedMenu, { speedMenu = false }) {
                        listOf(1f, 0.9f, 0.75f, 0.5f, 0.25f).forEach { s ->
                            DropdownMenuItem(text = { Text("${(s * 100).toInt()}%") }, onClick = { speedMenu = false; vm.setSpeed(s) })
                        }
                    }
                }
                IconToggleButton(ui.settings.tickEnabled, { vm.updateSettings(ui.settings.copy(tickEnabled = it)) }) {
                    Icon(Icons.Filled.MusicNote, "Tick sound", tint = if (ui.settings.tickEnabled) Studio.Amber else Studio.Faint)
                }
            }
            // Touch replacements for the web app's Enter / N keyboard shortcuts (spec §7).
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::setTimeForSelected, modifier = Modifier.weight(2f).height(52.dp)) {
                    Icon(Icons.Filled.AccessTime, null); Spacer(Modifier.width(8.dp)); Text("Set time")
                }
                OutlinedButton(onClick = vm::insertPauseLine, modifier = Modifier.weight(1f).height(52.dp)) { Text("+ Pause") }
                OutlinedButton(onClick = vm::clearTimeForSelected, modifier = Modifier.height(52.dp)) { Text("Clear") }
            }
        }
    }
}
