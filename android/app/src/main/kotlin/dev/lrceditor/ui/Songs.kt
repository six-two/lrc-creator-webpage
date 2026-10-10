package dev.lrceditor.ui

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lrceditor.EditorViewModel
import dev.lrceditor.Tab
import dev.lrceditor.core.LyricsFilter
import dev.lrceditor.core.LyricsState
import dev.lrceditor.core.Search
import dev.lrceditor.core.TagCount
import dev.lrceditor.data.Recent
import dev.lrceditor.data.Settings
import dev.lrceditor.data.SongEntry
import dev.lrceditor.data.SongsMode

private const val RECENT_DEFAULT = 10

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongsScreen(vm: EditorViewModel) {
    val prefs by vm.search.collectAsStateWithLifecycle()
    val index by vm.index.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    val settings by vm.ui.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val recents by vm.recents.collectAsStateWithLifecycle()
    val tags by vm.tagCounts.collectAsStateWithLifecycle()
    var showAllRecents by rememberSaveable { mutableStateOf(false) }
    val unknownLyrics = remember(index) { index?.songs?.count { it.lyrics == LyricsState.UNKNOWN } ?: 0 }
    @Composable fun lyricsFilter() = LyricsFilterRow(prefs.lyrics, unknownLyrics) { f -> vm.updateSearch { it.copy(lyrics = f) } }

    val compact = isCompactScreen()
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Text("Songs", style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = if (compact) 4.dp else 8.dp))
        Text(
            when {
                scan.running -> if (scan.listing) "Looking for songs… ${scan.total} found" else "Indexing… ${scan.found} / ${scan.total}"
                index != null -> "${index!!.songs.size} songs · indexed " +
                    DateUtils.getRelativeTimeSpanString(index!!.builtAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                settings.settings.libraryRoot == null -> "No music folder set"
                else -> "Not indexed yet"
            }, color = Studio.Dim, style = MaterialTheme.typography.bodySmall,
        )
        if (scan.running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = if (compact) 4.dp else 8.dp)) {
            val modes = listOf(SongsMode.RECENT to "Recent", SongsMode.TITLE to "Song", SongsMode.FILENAME to "File", SongsMode.TAGS to "Tags")
            modes.forEachIndexed { i, (m, label) ->
                // No check icon and a single-line label: otherwise "Recent" wraps on narrow screens and inflates the row.
                SegmentedButton(prefs.mode == m, { vm.updateSearch { it.copy(mode = m) } },
                    SegmentedButtonDefaults.itemShape(i, modes.size), icon = {}) { Text(label, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge) }
            }
        }

        if (settings.settings.libraryRoot == null && prefs.mode != SongsMode.RECENT) {
            EmptyHint("Choose your music folder in Settings to search your library.") { vm.showTab(Tab.SETTINGS) }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            when (prefs.mode) {
                SongsMode.RECENT -> {
                    if (recents.isEmpty()) item { Hint("Songs you open or edit will show up here.") }
                    val shown = if (showAllRecents) recents else recents.take(RECENT_DEFAULT)
                    items(shown, key = { it.uri }) { r -> RecentRow(r, onClick = { vm.openSong(r.uri) }, onRemove = { vm.removeRecent(r.uri) }) }
                    if (recents.size > RECENT_DEFAULT) item {
                        TextButton(onClick = { showAllRecents = !showAllRecents }) {
                            Text(if (showAllRecents) "Show fewer" else "Show all (${recents.size})")
                        }
                    }
                }
                SongsMode.TITLE -> {
                    item {
                        NameControls("Song title", prefs.query, prefs.fuzzy,
                            onQuery = { q -> vm.updateSearch { it.copy(query = q) } },
                            onFuzzy = { z -> vm.updateSearch { it.copy(fuzzy = z) } })
                        lyricsFilter()
                    }
                    resultItems(results, prefs.query.isNotBlank() || prefs.lyrics != LyricsFilter.ALL, vm, settings.settings, prefs.lyrics)
                }
                SongsMode.FILENAME -> {
                    item {
                        NameControls("File name", prefs.fileQuery, prefs.fuzzy,
                            onQuery = { q -> vm.updateSearch { it.copy(fileQuery = q) } },
                            onFuzzy = { z -> vm.updateSearch { it.copy(fuzzy = z) } })
                        lyricsFilter()
                    }
                    resultItems(results, prefs.fileQuery.isNotBlank() || prefs.lyrics != LyricsFilter.ALL, vm, settings.settings, prefs.lyrics)
                }
                SongsMode.TAGS -> {
                    item {
                        TagField("Include (all of)", prefs.include, tags) { t -> vm.updateSearch { it.copy(include = t) } }
                        Spacer(Modifier.height(8.dp))
                        TagField("Exclude (none of)", prefs.exclude, tags) { t -> vm.updateSearch { it.copy(exclude = t) } }
                        lyricsFilter()
                    }
                    resultItems(results, prefs.include.isNotEmpty() || prefs.exclude.isNotEmpty() || prefs.lyrics != LyricsFilter.ALL, vm, settings.settings, prefs.lyrics)
                }
            }
        }
    }
}

private fun LazyListScope.resultItems(results: List<SongEntry>, searched: Boolean, vm: EditorViewModel, settings: Settings, lyricsFilter: LyricsFilter) {
    item {
        Text(
            if (!searched) "" else "${results.size} result${if (results.size == 1) "" else "s"}",
            color = Studio.Dim, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 8.dp),
        )
    }
    items(results, key = { it.uri }) { s -> SongRow(s, settings, lyricsFilter) { vm.openSong(s.uri) } }
}

/** Lyrics filter shared by every search page. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LyricsFilterRow(current: LyricsFilter, unknown: Int, onChange: (LyricsFilter) -> Unit) {
    Column(Modifier.padding(top = 4.dp)) {
        Text("Lyrics", color = Studio.Dim, style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LyricsFilter.entries.forEach { f -> FilterChip(current == f, { onChange(f) }, { Text(f.label) }) }
        }
        if (current != LyricsFilter.ALL && unknown > 0) {
            Text("$unknown songs have no lyrics info yet — run a rescan in Settings.", color = Studio.Warn,
                style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun NameControls(
    label: String, query: String, fuzzy: Boolean,
    onQuery: (String) -> Unit, onFuzzy: (Boolean) -> Unit,
) {
    Column {
        OutlinedTextField(
            query, onQuery, Modifier.fillMaxWidth(), singleLine = true, label = { Text(label) },
            trailingIcon = { if (query.isNotEmpty()) IconButton({ onQuery("") }) { Icon(Icons.Filled.Close, "Clear") } },
        )
        FilterChip(fuzzy, { onFuzzy(!fuzzy) }, { Text("Fuzzy matching (typos)") })
    }
}

/** Chip input with autocomplete: suggestions show every known tag with its song count. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagField(label: String, selected: List<String>, all: List<TagCount>, onChange: (List<String>) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val selectedKeys = selected.map(Search::tagKey).toSet()
    val counts = remember(all) { all.associate { Search.tagKey(it.tag) to it.count } }
    val q = Search.tagKey(text)
    val suggestions = all.filter { Search.tagKey(it.tag) !in selectedKeys && (q.isEmpty() || Search.tagKey(it.tag).contains(q)) }
        .let { list -> if (q.isEmpty()) list else list.sortedBy { !Search.tagKey(it.tag).startsWith(q) } }
        .take(8)

    fun add(tag: String) { onChange(selected + tag); text = "" }

    Column {
        if (selected.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            selected.forEach { t ->
                InputChip(true, { onChange(selected - t) },
                    label = { Text(counts[Search.tagKey(t)]?.let { "$t ($it)" } ?: t) },
                    trailingIcon = { Icon(Icons.Filled.Close, "Remove $t", Modifier.size(16.dp)) })
            }
        }
        OutlinedTextField(
            text, { text = it }, Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            singleLine = true, label = { Text(label) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { suggestions.firstOrNull()?.let { add(it.tag) } }),
        )
        if (focused && suggestions.isNotEmpty()) {
            Surface(color = Studio.Panel2, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                Column {
                    suggestions.forEach { s ->
                        Row(Modifier.fillMaxWidth().clickable { add(s.tag) }.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(s.tag, Modifier.weight(1f))
                            Text("${s.count}", color = Studio.Dim)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SongRow(s: SongEntry, cfg: Settings, filter: LyricsFilter, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp)) {
        Text(s.displayTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val sub = listOf(s.artist, s.album).filter { it.isNotBlank() }.joinToString(" · ")
        if (cfg.showArtistAlbum && sub.isNotEmpty()) Text(sub, color = Studio.Dim, style = MaterialTheme.typography.bodySmall)
        if (cfg.showTags && s.tags.isNotEmpty()) Text(s.tags.joinToString(" · "), color = Studio.Teal, style = MaterialTheme.typography.labelSmall)
        // With a specific lyrics filter every row has the same state, so showing it would be noise.
        if (cfg.showLyrics && (filter == LyricsFilter.ALL || filter == LyricsFilter.ANY)) {
            val lyrics = when (s.lyrics) {
                LyricsState.SYNCED -> "Synced lyrics"
                LyricsState.UNSYNCED -> "Unsynced lyrics"
                LyricsState.NONE -> "No lyrics"
                LyricsState.UNKNOWN -> null
            }
            if (lyrics != null) Text(lyrics, color = Studio.Amber, style = MaterialTheme.typography.labelSmall)
        }
        // Relative path replaces the bare file name; helps tell same-named files apart.
        if (cfg.showPath) Text(s.relPath, color = Studio.Faint, style = MaterialTheme.typography.labelSmall)
        else if (cfg.showFileName) Text(s.fileName, color = Studio.Faint, style = MaterialTheme.typography.labelSmall)
    }
    HorizontalDivider(color = Studio.Border.copy(alpha = 0.5f))
}

@Composable
private fun RecentRow(r: Recent, onClick: () -> Unit, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(r.title.ifBlank { r.fileName }, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = listOf(r.artist, DateUtils.getRelativeTimeSpanString(r.editedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString())
                .filter { it.isNotBlank() }.joinToString(" · ")
            Text(sub, color = Studio.Dim, style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onRemove) { Icon(Icons.Filled.Close, "Remove from recent", tint = Studio.Faint) }
    }
    HorizontalDivider(color = Studio.Border.copy(alpha = 0.5f))
}

@Composable
private fun Hint(text: String) = Text(text, color = Studio.Dim, modifier = Modifier.padding(vertical = 16.dp))

@Composable
private fun EmptyHint(text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Studio.Dim, modifier = Modifier.weight(1f))
        TextButton(onClick) { Text("Settings") }
    }
}
