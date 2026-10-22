package dev.lrceditor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lrceditor.EditorViewModel
import dev.lrceditor.MetaState
import dev.lrceditor.core.MetaField
import dev.lrceditor.core.MetaFields
import dev.lrceditor.core.Search
import dev.lrceditor.core.TagCount

/**
 * Metadata editor: every text tag of one song as a free text field. Artist, title, album and grouping are always there; others
 * show when the song has them or after "Add field". Grouping (and artists / album) suggest values the library already has.
 */
@Composable
fun MetaScreen(vm: EditorViewModel) {
    val m by vm.meta.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    // Always the song of the editor: follows it when another song is opened.
    LaunchedEffect(ui.fileName, ui.loaded) { vm.syncMetadata() }
    val index by vm.index.collectAsStateWithLifecycle()
    val tags by vm.tagCounts.collectAsStateWithLifecycle()
    val artists = remember(index) { counts(index?.songs.orEmpty().map { it.artist }) }
    val albums = remember(index) { counts(index?.songs.orEmpty().map { it.album }) }
    val compact = isCompactScreen()

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Text("Metadata", style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = if (compact) 4.dp else 8.dp))
        val cur = m
        if (cur == null) {
            Text(if (ui.loaded) "Can't read this song's tags." else "Open a song in the editor first. Its tags are edited here.",
                color = Studio.Dim, modifier = Modifier.padding(vertical = 16.dp))
            return@Column
        }
        MetaForm(vm, cur, tags, artists, albums, Modifier.weight(1f), index)
    }
}

private fun counts(names: List<String>): List<TagCount> =
    names.filter { it.isNotBlank() }.groupingBy { it.trim() }.eachCount().map { TagCount(it.key, it.value) }
        .sortedWith(compareByDescending<TagCount> { it.count }.thenBy { it.tag.lowercase() })

/** Keys ([Search.tagKey]) of the albums the library has for [artist]. */
private fun albumsOf(index: dev.lrceditor.data.LibraryIndex?, artist: String): Set<String> {
    val a = Search.normalize(artist)
    if (a.isEmpty()) return emptySet()
    return index?.songs.orEmpty().filter { it.album.isNotBlank() && Search.normalize(it.artist) == a }.map { Search.tagKey(it.album) }.toSet()
}

@Composable
private fun MetaForm(
    vm: EditorViewModel, m: MetaState, tags: List<TagCount>, artists: List<TagCount>, albums: List<TagCount>,
    modifier: Modifier, index: dev.lrceditor.data.LibraryIndex?,
) {
    var addMenu by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Text(m.fileName, color = Studio.Dim, style = MaterialTheme.typography.bodySmall)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (f in m.shown) {
                val value = m.values[f].orEmpty()
                when (f) {
                    MetaField.GROUPING -> TagListField(f.label, value, { vm.setMetaField(f, it) }, tags)
                    MetaField.ARTIST, MetaField.ALBUM_ARTIST -> SuggestField(f.label, value, { vm.setMetaField(f, it) }, artists, separated = false)
                    // Albums of the song's artist come first, marked, so a new song of an album is a tap away.
                    MetaField.ALBUM -> SuggestField(f.label, value, { vm.setMetaField(f, it) }, albums, separated = false,
                        preferred = albumsOf(index, m.values[MetaField.ARTIST].orEmpty()))
                    MetaField.WORK -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value, { vm.setMetaField(f, it) }, Modifier.weight(1f), singleLine = true, label = { Text(f.label) })
                        // Older versions of this app wrote the grouping here; one tap moves it where it belongs.
                        if (value.isNotBlank()) OutlinedButton({ vm.convertWorkToGrouping() }) { Text("→ Grouping") }
                    }
                    MetaField.COMMENT -> OutlinedTextField(value, { vm.setMetaField(f, it) }, Modifier.fillMaxWidth(), minLines = 2, maxLines = 6, label = { Text(f.label) })
                    else -> OutlinedTextField(value, { vm.setMetaField(f, it) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(f.label) })
                }
            }
            val missing = MetaField.entries.filter { it !in m.shown }
            if (missing.isNotEmpty()) Box {
                TextButton({ addMenu = true }) { Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Add field") }
                DropdownMenu(addMenu, { addMenu = false }) {
                    missing.forEach { f -> DropdownMenuItem(text = { Text(f.label) }, onClick = { addMenu = false; vm.addMetaField(f) }) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(vm::revertMeta, enabled = m.changed) { Text("Revert") }
            Button(vm::saveMeta, enabled = m.changed && !m.saving, modifier = Modifier.weight(1f)) {
                Text(if (m.saving) "Saving…" else if (m.changed) "Save changes" else "No changes")
            }
        }
    }
}

/**
 * A free text field with a popup of known values matching what is being typed. With [separated] the text holds several
 * values divided by ";" and the popup completes the one being typed; otherwise the whole text is the value.
 */
@Composable
private fun SuggestField(
    label: String, value: String, onChange: (String) -> Unit, options: List<TagCount>, separated: Boolean,
    preferred: Set<String> = emptySet(),
) {
    var focused by remember { mutableStateOf(false) }
    val token = (if (separated) value.substringAfterLast(';') else value).trim()
    val done = if (separated) Search.parseTags(value.substringBeforeLast(';', "")).map(Search::tagKey).toSet() else emptySet()
    val q = Search.tagKey(token)
    val suggestions = remember(value, options, focused) {
        if (!focused) emptyList() else options
            .filter { Search.tagKey(it.tag) !in done && Search.tagKey(it.tag) != q && (q.isEmpty() || Search.tagKey(it.tag).contains(q)) }
            .sortedWith(compareBy<TagCount> { Search.tagKey(it.tag) !in preferred }.thenBy { q.isNotEmpty() && !Search.tagKey(it.tag).startsWith(q) })
            .take(10)
    }
    fun pick(tag: String) {
        onChange(if (separated) value.substringBeforeLast(';', "").trim().let { if (it.isEmpty()) "" else "$it; " } + tag + "; " else tag)
    }
    Column {
        OutlinedTextField(value, onChange, Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }, singleLine = true, label = { Text(label) })
        if (suggestions.isNotEmpty()) Surface(color = Studio.Panel2, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
            Column {
                suggestions.forEach { s ->
                    Row(Modifier.fillMaxWidth().clickable { pick(s.tag) }.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        val mine = Search.tagKey(s.tag) in preferred
                        Text(s.tag, Modifier.weight(1f), color = if (mine) Studio.Amber else Studio.Text)
                        if (mine) Text("same artist", Modifier.padding(end = 8.dp), color = Studio.Amber, style = MaterialTheme.typography.labelSmall)
                        Text("${s.count}", color = Studio.Dim)
                    }
                }
            }
        }
    }
}

/**
 * The grouping as bubbles (tap one to remove it) and a text field for adding: type free text and press Done, ";" or ",", or tap a
 * known tag in the popup, which lists the library's tags matching what is typed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagListField(label: String, value: String, onChange: (String) -> Unit, options: List<TagCount>) {
    var text by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val selected = Search.parseTags(value)
    val selectedKeys = selected.map(Search::tagKey).toSet()
    val counts = remember(options) { options.associate { Search.tagKey(it.tag) to it.count } }
    val q = Search.tagKey(text)
    val suggestions = remember(text, options, value, focused) {
        if (!focused) emptyList() else options.filter { Search.tagKey(it.tag) !in selectedKeys && (q.isEmpty() || Search.tagKey(it.tag).contains(q)) }
            .let { list -> if (q.isEmpty()) list else list.sortedBy { !Search.tagKey(it.tag).startsWith(q) } }.take(8)
    }
    fun set(tags: List<String>) = onChange(MetaFields.joinTags(tags))
    fun add(raw: String) {
        val t = raw.trim().trim(';', ',').trim()
        if (t.isNotEmpty()) set(Search.parseTags((selected + t).joinToString(";")))
        text = ""
    }
    Column {
        if (selected.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            selected.forEach { t ->
                InputChip(true, { set(selected - t) },
                    label = { Text(counts[Search.tagKey(t)]?.let { "$t ($it)" } ?: t) },
                    trailingIcon = { Icon(Icons.Filled.Close, "Remove $t", Modifier.size(16.dp)) })
            }
        }
        OutlinedTextField(
            text, { v -> if (v.endsWith(';') || v.endsWith(',')) add(v) else text = v },
            Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            singleLine = true, label = { Text(label) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add(text.ifBlank { suggestions.firstOrNull()?.tag.orEmpty() }) }),
        )
        if (suggestions.isNotEmpty()) Surface(color = Studio.Panel2, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
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
