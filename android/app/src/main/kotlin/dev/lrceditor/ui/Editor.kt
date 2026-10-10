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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lrceditor.EditorViewModel
import dev.lrceditor.Tab
import dev.lrceditor.UiState
import dev.lrceditor.core.Line
import dev.lrceditor.core.Lrc
import dev.lrceditor.data.Settings
import dev.lrceditor.data.SwipeAction
import dev.lrceditor.data.TimeSwipe
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.abs

@Composable
fun EditorScreen(vm: EditorViewModel, ui: UiState, onSaveLrcAs: () -> Unit, onSaveMp3As: () -> Unit) {
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
        EditorHeader(vm, ui, onSaveLrcAs, onSaveMp3As)
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
private fun EditorHeader(vm: EditorViewModel, ui: UiState, onSaveLrcAs: () -> Unit, onSaveMp3As: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        // Long names are shortened to make room for the buttons.
        Text(ui.fileName.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        // Primary action: write the lyrics into the MP3 being edited.
        FilledIconButton(onClick = vm::requestSave) { Icon(Icons.Filled.Save, "Save lyrics into the MP3") }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More options") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Undo") }, leadingIcon = { Icon(Icons.Filled.Undo, null) }, enabled = ui.canUndo,
                    onClick = { menu = false; vm.undo() })
                DropdownMenuItem(text = { Text("Redo") }, leadingIcon = { Icon(Icons.Filled.Redo, null) }, enabled = ui.canRedo,
                    onClick = { menu = false; vm.redo() })
                DropdownMenuItem(text = { Text("Discard all changes…") }, leadingIcon = { Icon(Icons.Filled.RestartAlt, null) }, enabled = ui.dirty,
                    onClick = { menu = false; vm.requestDiscard() })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Import / edit lyrics text…") }, leadingIcon = { Icon(Icons.Filled.Notes, null) },
                    onClick = { menu = false; vm.openLyricsDialog() })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Save .lrc file…") }, onClick = { menu = false; onSaveLrcAs() })
                DropdownMenuItem(text = { Text("Copy .lrc to clipboard") }, onClick = { menu = false; vm.copyLrc() })
                DropdownMenuItem(text = { Text("Save MP3 copy as…") }, onClick = { menu = false; onSaveMp3As() })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("File info") }, leadingIcon = { Icon(Icons.Filled.Info, null) },
                    onClick = { menu = false; vm.showInfo() })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun LineRow(vm: EditorViewModel, ui: UiState, line: Line, outOfOrder: Boolean) {
    val selected = ui.selectedId == line.id
    val playing = ui.playingId == line.id
    val editing = ui.editingTextId == line.id
    val compact = isCompactScreen()
    val cfg by rememberUpdatedState(ui.settings)

    val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { target ->
        // Also called with Settled when a row snaps back; that must never trigger an action.
        val action = when (target) {
            SwipeToDismissBoxValue.StartToEnd -> cfg.swipeRight
            SwipeToDismissBoxValue.EndToStart -> cfg.swipeLeft
            SwipeToDismissBoxValue.Settled -> SwipeAction.NONE
        }
        when (action) {
            SwipeAction.DELETE -> { vm.delete(line.id); true }
            // Clearing keeps the row in place, so it snaps back.
            SwipeAction.CLEAR_TIME -> { vm.setTime(line.id, null); false }
            SwipeAction.NONE -> false
        }
    })

    SwipeToDismissBox(
        swipe,
        enableDismissFromStartToEnd = !editing && ui.settings.swipeRight != SwipeAction.NONE,
        enableDismissFromEndToStart = !editing && ui.settings.swipeLeft != SwipeAction.NONE,
        backgroundContent = {
            val toEnd = swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val action = when (swipe.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> ui.settings.swipeRight
                SwipeToDismissBoxValue.EndToStart -> ui.settings.swipeLeft
                else -> SwipeAction.NONE
            }
            if (action != SwipeAction.NONE) Box(
                Modifier.fillMaxSize().background(if (action == SwipeAction.DELETE) Studio.Danger else Studio.Panel2).padding(horizontal = 20.dp),
                contentAlignment = if (toEnd) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Icon(if (action == SwipeAction.DELETE) Icons.Filled.Delete else Icons.Filled.TimerOff, action.label, tint = Studio.Text)
            }
        },
    ) {
        Row(
            Modifier.fillMaxWidth()
                .background(Studio.Bg)
                .background(if (selected) Studio.Teal.copy(alpha = 0.10f) else Color.Transparent)
                // Selected = static teal left border (structural, no layout shift).
                .drawBehind { if (selected) drawLine(Studio.Teal, Offset(2.dp.toPx(), 0f), Offset(2.dp.toPx(), size.height), 4.dp.toPx()) }
                .combinedClickable(onClick = { vm.select(line.id) })
                .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (editing) {
                // Timestamp and buttons are hidden so the text gets the whole row.
                TextEditor(line.text, Modifier.weight(1f), onCommit = { vm.setText(line.id, it); vm.startEditText(null) }, onCancel = { vm.startEditText(null) })
            } else {
                Text(
                    if (compact) Lrc.formatTimeShort(line.timeMs) else Lrc.formatTime(line.timeMs),
                    fontFamily = FontFamily.Monospace, fontSize = 14.sp, maxLines = 1, softWrap = false,
                    color = if (line.timeMs == null) Studio.Faint else Studio.Amber,
                    modifier = Modifier.widthIn(min = if (compact) 52.dp else 72.dp).padding(vertical = 4.dp)
                        .combinedClickable(onClick = { vm.select(line.id) }, onDoubleClick = { vm.startEditTime(line.id) })
                        .timeSwipe(vm, line.id, line.timeMs != null, ui.settings)
                        .padding(vertical = 4.dp),
                )
                val blank = line.text.isEmpty()
                Text(
                    if (blank) "(pause)" else line.text,
                    // Now-playing = recolor existing text (no bold, to avoid reflow).
                    color = if (playing) Studio.Amber else if (blank) Studio.Faint else Studio.Text,
                    fontStyle = if (blank) FontStyle.Italic else FontStyle.Normal,
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 6.dp).combinedClickable(
                        onClick = { vm.tapLine(line.id) },
                        onDoubleClick = { vm.startEditText(line.id) },
                    ),
                )
                if (outOfOrder) Icon(Icons.Filled.Warning, "Out of order", tint = Studio.Warn, modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp).size(16.dp))
            }
        }
    }

    if (ui.editingTimeId == line.id) {
        TimeEditDialog(line.timeMs, onCommit = { vm.setTime(line.id, it); vm.startEditTime(null) }, onDismiss = { vm.startEditTime(null) })
    }
}

/** Swiping on a timestamp scrubs its time: one nudge step per 20dp of travel. Does nothing without a timestamp. */
@Composable
private fun Modifier.timeSwipe(vm: EditorViewModel, id: Long, hasTime: Boolean, cfg: Settings): Modifier {
    if (!hasTime || cfg.timeSwipe == TimeSwipe.OFF) return this
    val stepPx = with(LocalDensity.current) { 20.dp.toPx() }
    val nudgeMs by rememberUpdatedState(cfg.nudgeMs)
    var acc = 0f
    fun drag(delta: Float) {
        acc += delta
        val steps = (acc / stepPx).toInt()
        if (steps != 0) { acc -= steps * stepPx; vm.nudge(id, steps * nudgeMs) }
    }
    return when (cfg.timeSwipe) {
        TimeSwipe.HORIZONTAL -> pointerInput(id) {
            detectHorizontalDragGestures({ acc = 0f; vm.beginGesture() }, vm::endGesture, vm::endGesture) { _, dx -> drag(dx) }
        }
        // Up is "later", like right.
        else -> pointerInput(id) {
            detectVerticalDragGestures({ acc = 0f; vm.beginGesture() }, vm::endGesture, vm::endGesture) { _, dy -> drag(-dy) }
        }
    }
}

@Composable
private fun TextEditor(initial: String, modifier: Modifier, onCommit: (String) -> Unit, onCancel: () -> Unit) {
    // Cursor starts at the end of the text, with the keyboard up.
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(modifier) {
        // Multi-line: Enter inserts a newline; commit with the Done button.
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().focusRequester(focus))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            TextButton(onClick = { onCommit(text.text) }) { Text("Done") }
        }
    }
}

@Composable
private fun PlayerBar(vm: EditorViewModel, ui: UiState) {
    val pos by vm.positionMs.collectAsState()
    var dragging by remember { mutableStateOf<Float?>(null) }
    val dur = ui.durationMs.coerceAtLeast(1)
    var speedMenu by remember { mutableStateOf(false) }
    val compact = isCompactScreen()

    Surface(color = Studio.Panel, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((dragging?.toLong() ?: pos).let { if (compact) Lrc.formatTimeShort(it) else Lrc.formatTime(it) }, fontFamily = FontFamily.Monospace, color = Studio.Amber, fontSize = 13.sp)
                Slider(
                    value = (dragging ?: pos.toFloat()).coerceIn(0f, dur.toFloat()),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = { dragging?.let { vm.seekTo(it.toLong()) }; dragging = null },
                    valueRange = 0f..dur.toFloat(),
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
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
            // Short labels / icons only: these must fit on very narrow and short screens.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::setTimeForSelected, modifier = Modifier.weight(2f).height(if (compact) 44.dp else 52.dp)) {
                    Icon(Icons.Filled.AccessTime, null); Spacer(Modifier.width(6.dp)); Text("Set", maxLines = 1, softWrap = false)
                }
                OutlinedButton(onClick = vm::insertPauseLine, modifier = Modifier.weight(1f).height(if (compact) 44.dp else 52.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)) {
                    // "+ pause": add a pause line at the playhead.
                    Icon(Icons.Filled.Add, null, Modifier.size(16.dp)); Icon(Icons.Filled.Pause, "Add pause line")
                }
            }
        }
    }
}
