package dev.lrceditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lrceditor.Compare
import dev.lrceditor.EditorViewModel
import dev.lrceditor.core.Line
import dev.lrceditor.core.Lrc

/**
 * Full-screen view of the two lyrics found in one song, side by side on wide screens and one above the other on narrow ones.
 * Tapping a timed line plays the song from there; both lyrics highlight the line they have at the playing position,
 * so it's easy to see which one follows the singing.
 */
@Composable
fun CompareDialog(vm: EditorViewModel, compare: Compare, isPlaying: Boolean) {
    Dialog(onDismissRequest = vm::closeCompare, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Studio.Bg) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Two lyrics in one", style = MaterialTheme.typography.titleMedium)
                        Text("Tap a line to play from it, then keep the one that follows the singing.", color = Studio.Dim, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(vm::closeCompare) { Icon(Icons.Filled.Close, "Close without changes") }
                }
                HorizontalDivider(color = Studio.Border)
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    if (maxWidth >= 600.dp) Row(Modifier.fillMaxSize()) {
                        Version("First lyrics", compare.first, vm, true, Modifier.weight(1f).fillMaxHeight())
                        VerticalDivider(color = Studio.Border)
                        Version("Second lyrics", compare.second, vm, false, Modifier.weight(1f).fillMaxHeight())
                    } else Column(Modifier.fillMaxSize()) {
                        Version("First lyrics", compare.first, vm, true, Modifier.weight(1f).fillMaxWidth())
                        HorizontalDivider(color = Studio.Border)
                        Version("Second lyrics", compare.second, vm, false, Modifier.weight(1f).fillMaxWidth())
                    }
                }
                HorizontalDivider(color = Studio.Border)
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledIconButton(vm::togglePlay) { Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (isPlaying) "Pause" else "Play") }
                    val pos by vm.positionMs.collectAsStateWithLifecycle()
                    Text(Lrc.formatTime(pos), Modifier.padding(start = 12.dp), fontFamily = FontFamily.Monospace, color = Studio.Amber)
                }
            }
        }
    }
}

@Composable
private fun Version(title: String, lines: List<Line>, vm: EditorViewModel, first: Boolean, modifier: Modifier) {
    val pos by vm.positionMs.collectAsStateWithLifecycle()
    // Only changes when the playing line changes, so the list isn't recomposed on every position update.
    val current by remember(lines) {
        derivedStateOf {
            var best = -1
            lines.forEachIndexed { i, l -> val t = l.timeMs; if (t != null && t <= pos && (best < 0 || t > lines[best].timeMs!!)) best = i }
            best
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(current) { if (current >= 0) listState.animateScrollToItem(maxOf(current - 2, 0)) }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().background(Studio.Panel).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                val times = lines.mapNotNull { it.timeMs }
                Text("${lines.size} lines" + if (times.isEmpty()) "" else " · ${Lrc.formatTimeShort(times.min())} – ${Lrc.formatTimeShort(times.max())}",
                    color = Studio.Dim, style = MaterialTheme.typography.labelSmall)
            }
            Button(onClick = { vm.chooseCompare(first) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Keep this") }
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            itemsIndexed(lines, key = { _, l -> l.id }) { i, line ->
                val playing = i == current
                Row(
                    Modifier.fillMaxWidth().background(if (playing) Studio.Amber.copy(alpha = 0.14f) else Color.Transparent)
                        .clickable(enabled = line.timeMs != null) { vm.playCompareLine(line) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text(Lrc.formatTimeShort(line.timeMs), Modifier.widthIn(min = 52.dp), fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                        color = if (line.timeMs == null) Studio.Faint else Studio.Amber)
                    val blank = line.text.isEmpty()
                    Text(if (blank) "(pause)" else line.text, Modifier.padding(start = 6.dp),
                        color = if (playing) Studio.Amber else if (blank) Studio.Faint else Studio.Text,
                        fontStyle = if (blank) FontStyle.Italic else FontStyle.Normal)
                }
            }
        }
    }
}
