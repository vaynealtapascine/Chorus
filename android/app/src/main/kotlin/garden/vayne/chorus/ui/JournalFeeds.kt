package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.FeedInfo
import garden.vayne.chorus.data.Feeds
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.SharedPost
import garden.vayne.chorus.designsystem.LocalChorusPalette
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Saved definitions are local; matching posts are read through the server's audience filter. */
@Composable
internal fun JournalFeeds(chorus: Chorus, model: Model, onTimeline: () -> Unit) {
    val p = LocalChorusPalette.current
    val actions = rememberCoroutineScope()
    var remoteFeeds by remember { mutableStateOf<List<FeedInfo>>(emptyList()) }
    var loadedAccount by remember { mutableStateOf<String?>(null) }
    var selectedId by rememberSaveable { mutableStateOf("") }
    var editingId by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var audience by rememberSaveable { mutableStateOf("private") }
    var posts by remember { mutableStateOf<List<SharedPost>>(emptyList()) }
    var loadedId by remember { mutableStateOf("") }
    var cursor by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val own = model.savedFeeds.map { FeedInfo(it.id, it.name, it.description, it.query, "You", false) }
    val all = own + remoteFeeds.filter { it.shared }
    val selected = all.find { it.id == selectedId }

    LaunchedEffect(chorus.device?.session, refresh) {
        val dev = chorus.device ?: return@LaunchedEffect
        if (loadedAccount != dev.accountId) {
            loadedAccount = dev.accountId
            selectedId = ""; loadedId = ""; posts = emptyList(); cursor = null
        }
        remoteFeeds = emptyList()
        try { remoteFeeds = Feeds.list(dev) }
        catch (e: Exception) { error = e.message ?: "Could not load shared feeds." }
    }

    fun open(feed: FeedInfo) {
        selectedId = feed.id; loadedId = feed.id; posts = emptyList(); cursor = null; error = null; loading = true
        actions.launch {
            try {
                val dev = checkNotNull(chorus.device) { "Not signed in." }
                val page = Feeds.items(dev, feed.id)
                posts = page.posts; cursor = page.nextCursor
            } catch (e: Exception) { error = e.message ?: "Could not load this feed." }
            finally { loading = false }
        }
    }

    LazyColumn(Modifier.fillMaxSize().background(p.bg).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onTimeline) { Text("Timeline") }
                Text("Feeds", color = p.ink, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 14.dp))
                TextButton(onClick = { refresh++ }) { Text("Refresh") }
            }
            Text("Save a filter, or open a feed shared with you. Results follow each reader's access.", color = p.ink2)
        }
        if (all.isNotEmpty()) item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(all, key = { it.id }) { feed ->
                    JournalChoice("${feed.name}${if (feed.shared) " · ${feed.ownerName}" else ""}", feed.id == selectedId) { open(feed) }
                }
            }
        }
        if (error != null) item { Text(error.orEmpty(), color = p.danger) }
        if (selected != null) {
            item {
                Column(Modifier.fillMaxWidth().background(p.surface).padding(12.dp)) {
                    Text(selected.name, color = p.ink, fontWeight = FontWeight.SemiBold)
                    if (selected.description != null) Text(selected.description, color = p.ink2)
                    if (Feeds.usesFronting(selected.query)) Text(
                        "This feed shows who was fronting when posts were written, as far as your notifications have shown you.",
                        color = p.ink2)
                    if (!selected.shared) TextButton(onClick = {
                        editingId = selected.id; name = selected.name; description = selected.description.orEmpty()
                        query = selected.query
                        audience = model.savedFeeds.find { it.id == selected.id }?.visibility ?: "private"
                    }) { Text("Edit") }
                }
            }
            if (loading) item { Text("Loading posts…", color = p.ink2) }
            if (!loading && posts.isEmpty() && error == null) item {
                Text(if (loadedId == selected.id) "No readable posts in this feed yet." else "Open this feed to load posts.", color = p.ink2)
            }
            items(posts, key = { it.id }) { post -> SharedPostPreview(post, selected.ownerName, chorus) }
            if (cursor != null) item {
                TextButton(enabled = !loading, onClick = {
                    val next = cursor ?: return@TextButton
                    loading = true; error = null
                    actions.launch {
                        try {
                            val dev = checkNotNull(chorus.device) { "Not signed in." }
                            val page = Feeds.items(dev, selected.id, next)
                            posts = posts + page.posts.filter { item -> posts.none { it.id == item.id } }
                            cursor = page.nextCursor
                        } catch (e: Exception) { error = e.message ?: "Could not load more posts." }
                        finally { loading = false }
                    }
                }) { Text("Load more") }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().background(p.surface).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (editingId.isEmpty()) "New feed" else "Edit feed", color = p.ink, fontWeight = FontWeight.SemiBold)
                if (editingId.isNotEmpty()) TextButton(onClick = {
                    editingId = ""; name = ""; description = ""; query = ""; audience = "private"
                }) { Text("New feed") }
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it }, label = { Text("Description (optional)") },
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(query, { query = it }, label = { Text("Filter") }, minLines = 2,
                    modifier = Modifier.fillMaxWidth())
                Text("Try from:, kind:, tag:, mood:, has:, reply:, in:, since:, until:, fronting:, or, and - to exclude.",
                    color = p.ink2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    JournalChoice("Only this account", audience == "private") { audience = "private" }
                    JournalChoice("Followers", audience == "followers") { audience = "followers" }
                }
                if (audience == "followers") {
                    Text("Followers get this filter and only posts they can already read.", color = p.ink2)
                    if (Feeds.usesFronting(query)) Text(
                        "This feed shows who was fronting when posts were written, as far as each follower's notifications have shown them.",
                        color = p.ink2)
                }
                TextButton(enabled = !busy && name.isNotBlank() && query.isNotBlank(), onClick = {
                    busy = true; error = null
                    actions.launch {
                        try {
                            val payload = Feeds.definition(name, description, query, audience)
                            val id = editingId.ifEmpty { chorus.newId() }
                            chorus.create("feed.set", id, payload)
                            editingId = id; selectedId = id; loadedId = ""; posts = emptyList(); cursor = null; refresh++
                        } catch (e: Exception) { error = e.message ?: "Could not save this feed." }
                        finally { busy = false }
                    }
                }) { Text("Save feed") }
                if (editingId.isNotEmpty()) TextButton(enabled = !busy, onClick = {
                    busy = true; error = null
                    actions.launch {
                        try {
                            chorus.create("feed.delete", editingId, JSONObject())
                            selectedId = ""; editingId = ""; name = ""; description = ""; query = ""; audience = "private"
                            posts = emptyList(); loadedId = ""; cursor = null; refresh++
                        } catch (e: Exception) { error = e.message ?: "Could not delete this feed." }
                        finally { busy = false }
                    }
                }) { Text("Delete feed") }
            }
        }
    }
}
