package dev.lrceditor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.lrceditor.EditorViewModel
import dev.lrceditor.FetchState
import dev.lrceditor.SourceRun
import dev.lrceditor.SourceStatus
import dev.lrceditor.data.lyrics.LyricsResult

/**
 * The lyrics search, full screen. Every enabled source is asked at once and listed with what it is doing; tap a source to preview
 * its best candidate, and use the candidate chips or "Next match" to skip to another one when a more popular song is in the way.
 * Nothing changes until "Use these lyrics".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FetchScreen(state: FetchState, vm: EditorViewModel, snackbar: SnackbarHostState) {
    val compact = isCompactScreen()
    Dialog(vm::dismissFetch, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Studio.Bg) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime))) {
                Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = if (compact) 4.dp else 8.dp)) {
                            if (!compact) Text("Find lyrics", style = MaterialTheme.typography.titleLarge)
                            Text(state.title, color = if (compact) Studio.Text else Studio.Dim, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                        if (state.searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        IconButton(vm::dismissFetch) { Icon(Icons.Filled.Close, "Close") }
                    }

                    // The sources, each with its state.
                    Column(Modifier.heightIn(max = if (compact) 130.dp else 240.dp).verticalScroll(rememberScrollState())) {
                        state.runs.forEachIndexed { i, run -> SourceRow(run, selected = state.shown?.first == i && state.pasted == null) {
                            if (run.results.isNotEmpty()) vm.showFetched(i, 0)
                        } }
                    }

                    // Which candidate is previewed: the dot says synced (green) or plain (yellow); the rest of what is known is in the text.
                    val shown = state.shown
                    val cur = state.current
                    if (state.pasted != null && cur != null) Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SyncDot(cur.synced); Spacer(Modifier.width(8.dp)); Text("Pasted text", maxLines = 1)
                    } else if (shown != null) MatchPicker(state.runs[shown.first].results, shown.second) { vm.showFetched(shown.first, it) }

                    HorizontalDivider(color = Studio.Border, modifier = Modifier.padding(top = 4.dp))
                    val r = state.current
                    if (r == null) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(if (state.searching) "Searching…" else "Nothing usable found. Tap a source with matches, or look the lyrics up yourself below.",
                                color = Studio.Dim, modifier = Modifier.padding(16.dp))
                        }
                    } else Preview(state, r, vm, Modifier.weight(1f))

                    // "Use" gets the space that is left, so it can never be pushed off a narrow screen.
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(vm::openLyricsInBrowser) { Icon(Icons.Filled.OpenInBrowser, "Look up in the browser") }
                        IconButton(vm::pasteFetchedFromClipboard) { Icon(Icons.Filled.ContentPaste, "Paste lyrics from the clipboard") }
                        if (state.pasted == null && state.runs.sumOf { it.results.size } > 1) {
                            OutlinedButton(vm::showNextFetched, contentPadding = PaddingValues(horizontal = 10.dp)) {
                                Text("Next", maxLines = 1); Icon(Icons.Filled.NavigateNext, null, Modifier.size(18.dp))
                            }
                        }
                        Button(vm::acceptFetched, enabled = r != null, modifier = Modifier.weight(1f)) { Text("Use these lyrics", maxLines = 1) }
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter))
            }
        }
    }
}

private val SyncedGreen = Color(0xFF4CAF50)
private val PlainYellow = Color(0xFFE6C84A)

/** Green dot = synced lyrics, yellow dot = plain lyrics without times. */
@Composable
private fun SyncDot(synced: Boolean) = Box(Modifier.size(10.dp).background(if (synced) SyncedGreen else PlainYellow, CircleShape))

/** Dropdown of one source's candidates (also for a single one, to read its full title); long names wrap in the list. */
@Composable
private fun MatchPicker(results: List<LyricsResult>, selected: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val cur = results.getOrNull(selected) ?: return
    fun label(r: LyricsResult) = "${r.matchInfo}  ${(r.score * 100).toInt()}%"
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        OutlinedButton({ open = true }, Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp)) {
            SyncDot(cur.synced); Spacer(Modifier.width(8.dp))
            Text(if (results.size > 1) "${selected + 1}/${results.size}  ${label(cur)}" else label(cur), Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(open, { open = false }, Modifier.fillMaxWidth(0.94f)) {
            results.forEachIndexed { i, r ->
                DropdownMenuItem(
                    leadingIcon = { SyncDot(r.synced) },
                    text = { Text(label(r), color = if (i == selected) Studio.Amber else Studio.Text) },
                    onClick = { open = false; onPick(i) },
                )
            }
        }
    }
}

@Composable
private fun SourceRow(run: SourceRun, selected: Boolean, onClick: () -> Unit) {
    val tappable = run.results.isNotEmpty()
    Surface(
        color = if (selected) Studio.Panel2 else Studio.Bg, shape = MaterialTheme.shapes.small,
        border = if (selected) BorderStroke(1.dp, Studio.Amber) else null,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable(enabled = tappable, onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                when (run.status) {
                    SourceStatus.SEARCHING -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    SourceStatus.FAILED -> Icon(Icons.Filled.ErrorOutline, null, tint = Studio.Danger, modifier = Modifier.size(18.dp))
                    SourceStatus.DONE -> Icon(Icons.Filled.CheckCircle, null, tint = if (tappable) Studio.Teal else Studio.Faint, modifier = Modifier.size(18.dp))
                }
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(run.source.label, color = if (tappable || run.status == SourceStatus.SEARCHING) Studio.Text else Studio.Dim)
                val sub = when (run.status) {
                    SourceStatus.SEARCHING -> "Searching…"
                    SourceStatus.FAILED -> run.error.orEmpty()
                    SourceStatus.DONE -> if (!tappable) "No match" else {
                        val synced = run.results.count { it.synced }
                        "${run.results.size} match${if (run.results.size == 1) "" else "es"}" + if (synced > 0) ", $synced synced" else ", plain only"
                    }
                }
                Text(sub, color = if (run.status == SourceStatus.FAILED) Studio.Warn else Studio.Dim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Preview(state: FetchState, r: LyricsResult, vm: EditorViewModel, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 6.dp)) {
        if (r.withRomanization != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Include romanization", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Switch(state.useRomanization, vm::setUseRomanization)
        }
        Surface(color = Studio.Panel2, shape = MaterialTheme.shapes.small, modifier = Modifier.weight(1f).fillMaxWidth()) {
            Text(state.text, Modifier.verticalScroll(rememberScrollState()).padding(8.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}
