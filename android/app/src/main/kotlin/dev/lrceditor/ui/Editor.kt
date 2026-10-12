package dev.lrceditor.ui

import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
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
import dev.lrceditor.RealignState
import dev.lrceditor.Tab
import dev.lrceditor.UiState
import dev.lrceditor.core.Issue
import dev.lrceditor.core.Issues
import dev.lrceditor.core.Line
import dev.lrceditor.core.LineProblem
import dev.lrceditor.core.Lrc
import dev.lrceditor.core.Realign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
            Text("Pick one in Library, or share an MP3 to this app.", color = Studio.Dim)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { vm.navigate(Tab.LIBRARY) }) { Text("Choose a song") }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        EditorHeader(vm, ui, onSaveLrcAs, onSaveMp3As)
        HorizontalDivider(color = Studio.Border)
        EditorWarnings(vm, ui)
        val listState = rememberLazyListState()
        val duration = ui.durationMs.takeIf { it > 0 }
        val problems = remember(ui.lines, ui.settings.minBreakMs, duration) { Issues.lineProblems(ui.lines, ui.settings.minBreakMs, duration) }
        val kindsByLine = remember(problems) { problems.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() } }

        // Keep the line being played in view while playing (selection never moves playback, and vice versa).
        LaunchedEffect(ui.playingId) {
            val idx = ui.lines.indexOfFirst { it.id == ui.playingId }
            if (ui.isPlaying && idx >= 0) listState.animateScrollToItem(idx)
        }

        // Jumps requested by opening a song with problems or by tapping a warning.
        LaunchedEffect(ui.scrollToId) {
            val id = ui.scrollToId ?: return@LaunchedEffect
            val idx = ui.lines.indexOfFirst { it.id == id }
            if (idx >= 0) listState.animateScrollToItem(idx)
            vm.consumeScroll()
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            if (ui.lines.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (ui.rawLyrics != null) {
                        Text(ui.lyricsError.orEmpty(), color = Studio.Danger, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = vm::openLyricsDialog) { Text("Show lyrics as text") }
                    } else Text("No lyrics yet.", color = Studio.Dim)
                    Spacer(Modifier.height(12.dp))
                    if (ui.rawLyrics == null) {
                        Button(onClick = vm::fetchLyrics) { Icon(Icons.Filled.CloudDownload, null); Spacer(Modifier.width(8.dp)); Text("Fetch lyrics") }
                        OutlinedButton(onClick = vm::openLyricsDialog) { Text("Paste lyrics") }
                    }
                }
            }
            itemsIndexed(ui.lines, key = { _, l -> l.id }) { _, line ->
                LineRow(vm, ui, line, kindsByLine[line.id].orEmpty())
                HorizontalDivider(color = Studio.Border.copy(alpha = 0.5f))
            }
        }
        PlayerBar(vm, ui)
    }
    ui.realign?.takeIf { it.finished }?.let { RealignDialog(vm, it) }
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
                DropdownMenuItem(text = { Text("Undo") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Undo, null) }, enabled = ui.canUndo,
                    onClick = { menu = false; vm.undo() })
                DropdownMenuItem(text = { Text("Redo") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Redo, null) }, enabled = ui.canRedo,
                    onClick = { menu = false; vm.redo() })
                DropdownMenuItem(text = { Text("Discard all changes…") }, leadingIcon = { Icon(Icons.Filled.RestartAlt, null) }, enabled = ui.dirty,
                    onClick = { menu = false; vm.requestDiscard() })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Realign timings…") }, leadingIcon = { Icon(Icons.Filled.MoreTime, null) },
                    enabled = ui.realign == null && ui.lines.any { it.timeMs != null && it.text.isNotBlank() },
                    onClick = { menu = false; vm.startRealign() })
                DropdownMenuItem(text = { Text("Fetch lyrics…") }, leadingIcon = { Icon(Icons.Filled.CloudDownload, null) },
                    onClick = { menu = false; vm.fetchLyrics() })
                DropdownMenuItem(text = { Text("Import / edit lyrics text…") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Notes, null) },
                    onClick = { menu = false; vm.openLyricsDialog() })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Save .lrc file…") }, onClick = { menu = false; onSaveLrcAs() })
                DropdownMenuItem(text = { Text("Copy .lrc to clipboard") }, onClick = { menu = false; vm.copyLrc() })
                DropdownMenuItem(text = { Text("Save MP3 copy as…") }, onClick = { menu = false; onSaveMp3As() })
                HorizontalDivider()
                Text("Learning tag", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = Studio.Dim, style = MaterialTheme.typography.labelMedium)
                LearnStateItems(ui.learn) { state -> menu = false; vm.currentUriString()?.let { vm.requestLearnState(it, ui.fileName.orEmpty(), state) } }
                HorizontalDivider()
                val broken by vm.brokenLyrics.collectAsStateWithLifecycle()
                val isBroken = vm.currentUriString() in broken
                DropdownMenuItem(text = { Text(if (isBroken) "Remove broken-lyrics mark" else "Mark lyrics as broken") },
                    leadingIcon = { Icon(Icons.Filled.Warning, null) }, onClick = { menu = false; vm.toggleBrokenLyrics() })
                val buggy by vm.buggyFiles.collectAsStateWithLifecycle()
                DropdownMenuItem(text = { Text(if (vm.currentUriString() in buggy) "Remove buggy-file mark" else "Mark file as buggy") },
                    leadingIcon = { Icon(Icons.Filled.BugReport, null) }, onClick = { menu = false; vm.toggleBuggyFile() })
                DropdownMenuItem(text = { Text("File info") }, leadingIcon = { Icon(Icons.Filled.Info, null) },
                    onClick = { menu = false; vm.showInfo() })
            }
        }
    }
}

/** Problems with the lyrics being edited. Those that sit on a line can be tapped to jump to the next such line. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorWarnings(vm: EditorViewModel, ui: UiState) {
    val duration = ui.durationMs.takeIf { it > 0 }
    val problems = remember(ui.lines, ui.settings.minBreakMs, duration) { Issues.lineProblems(ui.lines, ui.settings.minBreakMs, duration) }
    fun count(k: LineProblem) = problems.count { it.second == k }
    val conflict = Issue.CONFLICTING_LEARN_TAGS in Issues.checkSong(ui.tags, null)
    fun times(n: Int) = if (n > 1) " ×$n" else ""
    // label, what to jump to (null = not tied to a line)
    val parts = listOfNotNull(
        ("${count(LineProblem.UNTIMED)} untimed line${if (count(LineProblem.UNTIMED) == 1) "" else "s"}" to LineProblem.UNTIMED).takeIf { count(LineProblem.UNTIMED) > 0 },
        ("times out of order${times(count(LineProblem.OUT_OF_ORDER))}" to LineProblem.OUT_OF_ORDER).takeIf { count(LineProblem.OUT_OF_ORDER) > 0 },
        ("same time twice in a row${times(count(LineProblem.SAME_TIME))}" to LineProblem.SAME_TIME).takeIf { count(LineProblem.SAME_TIME) > 0 },
        ("odd timestamps in text${times(count(LineProblem.ODD_TIMESTAMPS))}" to LineProblem.ODD_TIMESTAMPS).takeIf { count(LineProblem.ODD_TIMESTAMPS) > 0 },
        ("time after the end of the song${times(count(LineProblem.PAST_END))}" to LineProblem.PAST_END).takeIf { count(LineProblem.PAST_END) > 0 },
        ("empty lines in a row${times(count(LineProblem.EMPTY_REPEAT))}" to LineProblem.EMPTY_REPEAT).takeIf { count(LineProblem.EMPTY_REPEAT) > 0 },
        ("short break${times(count(LineProblem.SHORT_BREAK))}" to LineProblem.SHORT_BREAK).takeIf { count(LineProblem.SHORT_BREAK) > 0 },
        ("empty line at the start" to LineProblem.LEADING_EMPTY).takeIf { count(LineProblem.LEADING_EMPTY) > 0 },
    )
    val canCompare = remember(ui.lines) { vm.doubleLyrics(ui.lines) != null }
    if (parts.isEmpty() && !conflict && !canCompare) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Warning, null, tint = Studio.Warn, modifier = Modifier.size(14.dp))
        FlowRow(Modifier.padding(start = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            parts.forEach { (label, kind) ->
                Text("$label ›", color = Studio.Warn, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable { vm.jumpToProblem(kind) }.padding(vertical = 6.dp))
            }
            if (canCompare) Text("two lyrics in one – compare ›", color = Studio.Warn, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clickable { vm.openCompare() }.padding(vertical = 6.dp))
            if (conflict) Text("more than one learning tag", color = Studio.Warn, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(vertical = 6.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun LineRow(vm: EditorViewModel, ui: UiState, line: Line, problems: Set<LineProblem>) {
    val selected = ui.selectedId == line.id
    val playing = ui.playingId == line.id
    val editing = ui.editingTextId == line.id
    val compact = isCompactScreen()
    val cfg by rememberUpdatedState(ui.settings)

    val swipe = rememberSwipeToDismissBoxState()
    LaunchedEffect(swipe.settledValue) {
        val action = when (swipe.settledValue) {
            SwipeToDismissBoxValue.StartToEnd -> cfg.swipeRight
            SwipeToDismissBoxValue.EndToStart -> cfg.swipeLeft
            SwipeToDismissBoxValue.Settled -> SwipeAction.NONE
        }
        when (action) {
            SwipeAction.DELETE -> vm.delete(line.id)
            // Clearing keeps the row in place, so it snaps back.
            SwipeAction.CLEAR_TIME -> { vm.setTime(line.id, null); swipe.reset() }
            SwipeAction.NONE -> if (swipe.settledValue != SwipeToDismissBoxValue.Settled) swipe.reset()
        }
    }

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
                        // Long-press edits the line without moving the player (a tap plays from the line).
                        onLongClick = { vm.startEditText(line.id) },
                    ),
                )
                if (LineProblem.OUT_OF_ORDER in problems) Icon(Icons.Filled.Warning, "Out of order", tint = Studio.Warn, modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp).size(16.dp))
                // These sit at the end of the line, with a short label for what they are.
                if (LineProblem.SAME_TIME in problems) ProblemTag("same", "Same time as the line before")
                if (LineProblem.PAST_END in problems) ProblemTag("past end", "After the end of the song", error = true)
                if (LineProblem.LEADING_EMPTY in problems) ProblemTag("not needed", "Empty line at the start of the song")
                if (LineProblem.EMPTY_REPEAT in problems) ProblemTag("empty ×2", "Empty line right after an empty line")
                if (LineProblem.SHORT_BREAK in problems) ProblemTag("short", "Break to the next line is shorter than the limit in Settings")
            }
        }
    }

    if (ui.editingTimeId == line.id) {
        TimeEditDialog(line.timeMs, onCommit = { vm.setTime(line.id, it); vm.startEditTime(null) }, onDismiss = { vm.startEditTime(null) })
    }
}

@Composable
private fun ProblemTag(label: String, description: String, error: Boolean = false) {
    val color = if (error) Studio.Danger else Studio.Warn
    Row(Modifier.padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Warning, description, tint = color, modifier = Modifier.size(16.dp))
        Text(label, Modifier.padding(start = 2.dp), color = color, style = MaterialTheme.typography.labelSmall)
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
    // All the text starts selected (typing replaces it, a tap in the field puts the cursor), with the keyboard up.
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
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
                val scope = rememberCoroutineScope()
                var taps by remember { mutableIntStateOf(0) }
                var tapJob by remember { mutableStateOf<Job?>(null) }
                // Minimal player: taps on the time are counted until a short pause, then act (see EditorViewModel.timeTapped).
                val tapModifier = if (!ui.settings.minimalPlayer) Modifier else Modifier.clickable {
                    taps++
                    tapJob?.cancel()
                    tapJob = scope.launch { delay(300); val n = taps; taps = 0; vm.timeTapped(n) }
                }.padding(vertical = 10.dp)
                Text((dragging?.toLong() ?: pos).let { if (compact) Lrc.formatTimeShort(it) else Lrc.formatTime(it) }, tapModifier, fontFamily = FontFamily.Monospace, color = Studio.Amber, fontSize = 13.sp)
                Slider(
                    value = (dragging ?: pos.toFloat()).coerceIn(0f, dur.toFloat()),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = { dragging?.let { vm.seekTo(it.toLong()) }; dragging = null },
                    valueRange = 0f..dur.toFloat(),
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
            }
            if (!ui.settings.minimalPlayer) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
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
            val realign = ui.realign
            if (realign != null) RealignControls(vm, realign, compact)
            else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

/** Replaces the Set / pause buttons in realign mode: tap Set when the highlighted line is actually sung. */
@Composable
private fun RealignControls(vm: EditorViewModel, r: RealignState, compact: Boolean) {
    val line = r.current
    Column(Modifier.fillMaxWidth()) {
        Text(
            if (line != null) "Realign ${r.taken.size + 1} of ${r.targets.size}: tap Set when this is sung" else "Realign: all ${r.targets.size} lines timed",
            color = Studio.Amber, style = MaterialTheme.typography.labelMedium,
        )
        if (line != null) Text(line.text.lines().first(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = vm::cancelRealign) { Text("Cancel") }
            TextButton(onClick = vm::realignUndo, enabled = r.taken.isNotEmpty()) { Text("Back") }
            Button(onClick = vm::realignSet, enabled = line != null, modifier = Modifier.weight(1f).height(if (compact) 44.dp else 52.dp)) {
                Icon(Icons.Filled.AccessTime, null); Spacer(Modifier.width(6.dp)); Text("Set", maxLines = 1, softWrap = false)
            }
        }
    }
}

/** Asks to confirm moving every line by the measured offset, once all the lines are timed. */
@Composable
fun RealignDialog(vm: EditorViewModel, r: RealignState) {
    val pairs = r.pairs
    val offset = r.offset
    val spread = Realign.spread(pairs, offset)
    AlertDialog(
        onDismissRequest = vm::realignUndo,
        title = { Text("Move all lines by ${Realign.formatOffset(offset)}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                pairs.forEachIndexed { i, (old, actual) ->
                    Text("${i + 1}.  ${Lrc.formatTime(old)} → ${Lrc.formatTime(actual)}   (${Realign.formatOffset(actual - old)})",
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
                Text(
                    if (spread > 500) "The lines disagree by up to ${"%.1f".format(spread / 1000.0)} s, so the song may not be shifted evenly. Go back and time some lines again, or apply anyway."
                    else "The offset is the middle value of these differences and is added to every timed line. Undo brings the old times back.",
                    color = if (spread > 500) Studio.Warn else Studio.Dim, style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = vm::applyRealign) { Text("Apply") } },
        dismissButton = { Row { TextButton(onClick = vm::cancelRealign) { Text("Cancel") }; TextButton(onClick = vm::realignUndo) { Text("Back") } } },
    )
}
