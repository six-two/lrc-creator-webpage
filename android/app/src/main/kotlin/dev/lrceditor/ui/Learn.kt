package dev.lrceditor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lrceditor.EditorViewModel
import dev.lrceditor.core.LearnState
import dev.lrceditor.core.LyricsFilter
import dev.lrceditor.core.LyricsState
import dev.lrceditor.core.NameField
import dev.lrceditor.core.Search

private val LEARN_VIEWS = listOf(LearnState.LEARN, LearnState.UNSINGABLE, LearnState.SING_ALONG)

/** Songs grouped by learning state: a ready-made tag filter over the library index. */
@Composable
fun LearnScreen(vm: EditorViewModel) {
    val index by vm.index.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    var view by rememberSaveable { mutableStateOf(LearnState.LEARN) }
    // One search text per list, so switching lists keeps what you typed.
    var qLearn by rememberSaveable { mutableStateOf("") }
    var qUnsingable by rememberSaveable { mutableStateOf("") }
    var qSingAlong by rememberSaveable { mutableStateOf("") }
    val query = when (view) { LearnState.UNSINGABLE -> qUnsingable; LearnState.SING_ALONG -> qSingAlong; else -> qLearn }
    fun setQuery(q: String) = when (view) { LearnState.UNSINGABLE -> qUnsingable = q; LearnState.SING_ALONG -> qSingAlong = q; else -> qLearn = q }
    val groups = remember(index) {
        val songs = index?.songs.orEmpty()
        LEARN_VIEWS.associateWith { st ->
            val key = Search.tagKey(st.tag!!)
            songs.filter { s -> s.tags.any { Search.tagKey(it) == key } }.sortedWith(Search.songOrder)
        }
    }
    val all = groups[view].orEmpty()
    var lyricsName by rememberSaveable { mutableStateOf(LyricsFilter.ALL.name) }
    val lyrics = LyricsFilter.valueOf(lyricsName)
    val prefs by vm.search.collectAsStateWithLifecycle()
    val open = prefs.learnFiltersOpen
    // How many songs of the current list have each lyrics status.
    val counts = remember(all) { LyricsFilter.entries.associateWith { f -> all.count { f.matches(it.lyrics) } } }
    val unknownLyrics = remember(all) { all.count { it.lyrics == LyricsState.UNKNOWN } }
    val byLyrics = remember(all, lyrics) { if (lyrics == LyricsFilter.ALL) all else all.filter { lyrics.matches(it.lyrics) } }
    val shown = remember(byLyrics, query) { if (query.isBlank()) byLyrics else Search.byName(byLyrics, query, NameField.TITLE, fuzzy = true) }
    val compact = isCompactScreen()

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Text("Learn", style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = if (compact) 4.dp else 8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = if (compact) 4.dp else 8.dp)) {
            LEARN_VIEWS.forEachIndexed { i, st ->
                SegmentedButton(view == st, { view = st }, SegmentedButtonDefaults.itemShape(i, LEARN_VIEWS.size), icon = {}) {
                    // Label and count on two lines so neither is cut off on narrow screens.
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(st.label, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge)
                        Text("${groups[st]?.size ?: 0}", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        val active = query.isNotBlank() || lyrics != LyricsFilter.ALL
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (open) "Search & filter" else if (active) "Search & filter (active)" else "Search & filter",
                color = if (!open && active) Studio.Amber else Studio.Dim, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton({ vm.updateSearch { it.copy(learnFiltersOpen = !open) } }) {
                Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (open) "Hide search and filter" else "Show search and filter")
            }
        }
        if (open) {
            OutlinedTextField(
                query, ::setQuery, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search ${view.label}") },
                trailingIcon = { if (query.isNotEmpty()) IconButton({ setQuery("") }) { Icon(Icons.Filled.Close, "Clear") } },
            )
            LyricsFilterRow(lyrics, unknownLyrics, counts) { lyricsName = it.name }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (shown.isEmpty()) item {
                Text(if (index == null) "No library yet. Choose your music folder in Settings." else if (query.isNotBlank() || lyrics != LyricsFilter.ALL) "No ${view.label} songs match." else "No songs are marked ${view.label}.",
                    color = Studio.Dim, modifier = Modifier.padding(vertical = 16.dp))
            }
            items(shown, key = { it.uri }) { s ->
                SongRow(s, ui.settings, lyrics, vm) { vm.openSong(s.uri) }
            }
        }
    }
}
