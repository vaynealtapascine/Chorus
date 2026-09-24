package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.data.LocalSearch
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.MessageSearchApi
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.SearchDocument
import garden.vayne.chorus.designsystem.LocalChorusPalette
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Search the permitted local replica even when the server is asleep. */
@Composable
internal fun ContentSearch(chorus: Chorus, model: Model, online: Boolean, onClose: () -> Unit) {
    val p = LocalChorusPalette.current
    val actions = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var section by rememberSaveable { mutableStateOf("Messages") }
    var remote by remember { mutableStateOf<List<SearchDocument>>(emptyList()) }
    var cursor by remember { mutableStateOf<String?>(null) }
    var remoteError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val currentSession by rememberUpdatedState(chorus.device?.session)
    val index by produceState<LocalSearch?>(initialValue = null, model) {
        value = null
        value = withContext(Dispatchers.Default) { LocalSearch.fromModel(model) }
    }
    val results = remember(index, query, section) { index?.search(query, section).orEmpty() }
    val parsed = remember(query) { LocalSearch.parse(query) }
    LaunchedEffect(query, section, chorus.device?.session, online, model) {
        remote = emptyList(); cursor = null; remoteError = null; loading = false
        val dev = chorus.device ?: return@LaunchedEffect
        if (!online || section != "Messages" || parsed.terms.isEmpty()) return@LaunchedEffect
        delay(250)
        loading = true
        try {
            val page = MessageSearchApi.page(dev, parsed)
            remote = page.items
            cursor = page.nextCursor
        } catch (e: Exception) { remoteError = e.message ?: "Older messages are unavailable." }
        finally { loading = false }
    }
    val known = results.mapTo(HashSet()) { it.id }
    val shown = if (section == "Messages") results + remote.filter { it.id !in known } else results

    fun loadMore() {
        val next = cursor ?: return
        val dev = chorus.device ?: return
        if (loading) return
        val requestedQuery = query
        loading = true
        actions.launch {
            try {
                val page = MessageSearchApi.page(dev, parsed, next)
                if (query == requestedQuery && currentSession == dev.session && cursor == next) {
                    remote = remote + page.items
                    cursor = page.nextCursor
                    remoteError = null
                }
            } catch (e: Exception) {
                if (query == requestedQuery && currentSession == dev.session) remoteError = e.message ?: "Could not load more messages."
            } finally { loading = false }
        }
    }
    LazyColumn(Modifier.fillMaxSize().background(p.bg).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Search", color = p.ink, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 14.dp))
                TextButton(onClick = onClose) { Text("Close") }
            }
            OutlinedTextField(query, { query = it }, label = { Text("Search this device") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Try words or from:, in:, has:, before:, after:.", color = p.ink2)
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (choice in listOf("Messages", "Posts", "Switches")) item(key = choice) {
                    JournalChoice(choice, section == choice) { section = choice }
                }
            }
        }
        if (index == null) item { Text("Preparing local search…", color = p.ink2) }
        else if (query.isBlank()) item { Text("Type to search messages, posts and switches on this device.", color = p.ink2) }
        else if (shown.isEmpty() && !loading) item { Text("No $section matches on this device${if (online && section == "Messages") " or server" else ""}.", color = p.ink2) }
        for (doc in shown) item(key = "${doc.kind}:${doc.id}") { SearchCard(doc, model) }
        if (results.size == 100) item { Text("Showing the newest 100 local matches. Narrow the search for more.", color = p.ink2) }
        if (loading) item { Text("Searching older messages…", color = p.ink2) }
        if (remoteError != null && section == "Messages") item { Text("Older messages unavailable: ${remoteError.orEmpty()}", color = p.ink2) }
        if (section == "Messages" && cursor != null) item {
            TextButton(enabled = !loading, onClick = ::loadMore) { Text("Load more from server") }
        }
        item { Text("", modifier = Modifier.padding(bottom = 16.dp)) }
    }
}

@Composable
private fun SearchCard(doc: SearchDocument, model: Model) {
    val p = LocalChorusPalette.current
    var revealed by rememberSaveable(doc.id) { mutableStateOf(false) }
    val label = when (doc.kind) {
        "Messages" -> model.channels.find { it.id == doc.channelId }?.name?.let { "#$it" } ?: "Message"
        "Posts" -> doc.authors.mapNotNull { model.member(it)?.shownName }.joinToString(" & ").ifBlank { "Post" }
        else -> "Switch"
    }
    Column(Modifier.fillMaxWidth().background(p.surface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$label · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(doc.occurredAt))}",
            color = p.ink2)
        if (doc.cw != null) Text("Content warning: ${doc.cw} · ${if (revealed) "Hide" else "Show"}",
            color = p.accent, modifier = Modifier.clickable { revealed = !revealed })
        if (doc.cw == null || revealed) {
            if (doc.title != null) Text(doc.title, color = p.ink, fontWeight = FontWeight.SemiBold)
            Text(doc.text.ifBlank { "(no text)" }, color = p.ink)
            if (doc.tags.isNotEmpty()) Text(doc.tags.joinToString(" ") { "#$it" }, color = p.ink2)
        }
    }
}
