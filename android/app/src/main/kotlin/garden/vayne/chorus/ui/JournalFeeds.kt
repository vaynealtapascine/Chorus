package garden.vayne.chorus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.AccountPrefs
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.FeedInfo
import garden.vayne.chorus.data.Feeds
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.SharedPost
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import kotlinx.coroutines.launch

/** A feed as a row: the three built-in Journal tabs and saved feeds, ours and shared with us. */
internal data class FeedRow(val id: String, val name: String, val by: String, val description: String?, val icon: Int,
    val builtIn: Boolean, val shared: Boolean, val feed: FeedInfo? = null)

internal fun feedRows(model: Model, shared: List<FeedInfo>): List<FeedRow> {
    val system = model.systemName?.takeIf { it.isNotBlank() && !model.isPerson } ?: "us"
    return listOf(
        FeedRow("system", if (model.isPerson) "My posts" else "Our system", "Chorus", "Everything posted inside $system", R.drawable.ic_users, true, false),
        FeedRow("friends", "Friends", "Chorus", "Posts friends chose to share with you", R.drawable.ic_globe, true, false),
        FeedRow("entries", "Journals", "Chorus", "Long-form entries only", R.drawable.ic_book, true, false),
    ) + model.savedFeeds.map { f -> FeedRow(f.id, f.name, "You", f.description, R.drawable.ic_sliders, false, false,
        FeedInfo(f.id, f.name, f.description, f.query, "You", false)) } +
        shared.filter { it.shared }.map { FeedRow(it.id, it.name, it.ownerName, it.description, R.drawable.ic_file, false, true, it) }
}

/** The posts a saved feed reads, through the server's audience filter, with paging (also a Journal tab). */
@Composable
internal fun FeedPosts(chorus: Chorus, model: Model, feed: FeedInfo, modifier: Modifier = Modifier,
    onThread: ((String) -> Unit)? = null, onProfile: ((String, String) -> Unit)? = null) {
    val p = LocalChorusPalette.current
    val actions = rememberCoroutineScope()
    var posts by remember(feed.id) { mutableStateOf<List<SharedPost>>(emptyList()) }
    var cursor by remember(feed.id) { mutableStateOf<String?>(null) }
    var loading by remember(feed.id) { mutableStateOf(true) }
    var error by remember(feed.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(feed.id, feed.query, chorus.device?.session) {
        val dev = chorus.device ?: return@LaunchedEffect
        loading = true; error = null
        try { val page = Feeds.items(dev, feed.id); posts = page.posts; cursor = page.nextCursor }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Could not load this feed." }
        finally { loading = false }
    }
    LazyColumn(modifier.fillMaxSize()) {
        if (feed.shared && Feeds.usesFronting(feed.query)) item {
            Text("Shows who was fronting, as far as readers were told", color = p.ink3, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
        }
        if (error != null) item { Text(error.orEmpty(), color = p.danger, modifier = Modifier.padding(16.dp)) }
        if (!loading && posts.isEmpty() && error == null) item { EmptyState(R.drawable.ic_journal, "Nothing yet") }
        items(posts, key = { it.id }) { post -> SharedPostPreview(post, feed.ownerName, chorus, model,
            onThread = onThread?.let { open -> { open(it.id) } }, onProfile = onProfile) }
        if (cursor != null) item {
            TextButton(enabled = !loading, onClick = {
                val next = cursor ?: return@TextButton
                loading = true
                actions.launch {
                    try {
                        val dev = checkNotNull(chorus.device) { "Not signed in." }
                        val page = Feeds.items(dev, feed.id, next)
                        posts = posts + page.posts.filter { item -> posts.none { it.id == item.id } }
                        cursor = page.nextCursor
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "Could not load more posts." }
                    finally { loading = false }
                }
            }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Load more") }
        }
    }
}

/** F6.8 Feeds: pinned ones (they are the Journal's tabs), ours, and those shared with us. */
@Composable
internal fun JournalFeeds(chorus: Chorus, model: Model, onTimeline: () -> Unit, onThread: ((String) -> Unit)? = null,
    onProfile: ((String, String) -> Unit)? = null, onLists: () -> Unit = {}, onOpenTab: (String) -> Unit = {},
    backEnabled: Boolean = true) {
    val p = LocalChorusPalette.current
    val actions = rememberCoroutineScope()
    var shared by remember { mutableStateOf<List<FeedInfo>>(emptyList()) }
    var refresh by remember { mutableStateOf(0) }
    var viewing by rememberSaveable { mutableStateOf<String?>(null) }
    // "" = a new feed, an id = that feed's editor
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(chorus.device?.session, refresh) {
        val dev = chorus.device ?: return@LaunchedEffect
        try { shared = Feeds.list(dev).filter { it.shared } } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { }
    }
    val rows = feedRows(model, shared)
    val tabs = model.accountPrefs.tabs(rows.map { it.id }.toSet())
    fun save(ids: List<String>) = actions.launch {
        try { chorus.create("pref.set", null, AccountPrefs.pinnedFeedsPayload(ids)); error = null }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { error = "Could not save the pinned feeds. Try again." }
    }
    val open = viewing?.let { id -> rows.find { it.id == id } }
    val edited = editing
    BackHandler(backEnabled) { when { edited != null -> editing = null; open != null -> viewing = null; else -> onTimeline() } }
    if (edited != null) {
        FeedEditor(chorus, model, rows.find { it.id == edited }?.takeIf { !it.builtIn && !it.shared }?.feed,
            onClose = { editing = null; refresh++ }, onSaved = { id -> viewing = id })
        return
    }
    if (open?.feed != null) {
        Column(Modifier.fillMaxSize().background(p.bg)) {
            Row(Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                ChatIconButton(R.drawable.ic_back, "Back") { viewing = null }
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(open.name, color = p.ink, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("by ${open.by}", color = p.ink3, style = MaterialTheme.typography.bodySmall)
                }
                if (!open.shared) ChatIconButton(R.drawable.ic_pencil, "Edit feed") { editing = open.id }
            }
            FeedPosts(chorus, model, open.feed, onThread = onThread, onProfile = onProfile)
        }
        return
    }
    val pinned = tabs.mapNotNull { id -> rows.find { it.id == id } }
    val unpinned = rows.filter { it.id !in tabs && (query.isBlank() || it.name.contains(query, true)) }
    val shownPinned = pinned.filter { query.isBlank() || it.name.contains(query, true) }
    Column(Modifier.fillMaxSize().background(p.bg)) {
        Row(Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ChatIconButton(R.drawable.ic_back, "Back", onTimeline)
            Text("Feeds", color = p.ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            ChatIconButton(R.drawable.ic_plus_circle, "New feed") { editing = "" }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item { ChorusField(query, { query = it }, singleLine = true, placeholder = { Text("Search feeds") },
                leadingIcon = { Glyph(R.drawable.ic_search, p.ink3, 20.dp) }, modifier = Modifier.fillMaxWidth().padding(16.dp)) }
            if (error != null) item { Text(error.orEmpty(), color = p.danger, modifier = Modifier.padding(horizontal = 16.dp)) }
            if (shownPinned.isNotEmpty()) {
                item { SectionLabel("Pinned", Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                item {
                    ReorderColumn(shownPinned, { it.id }, onMove = { moved ->
                        // searching shows some of the pins: keep the others where they are
                        if (query.isBlank()) save(moved.map { it.id })
                    }) { row, handle -> FeedListRow(row, true, handle, onClick = { if (row.builtIn) onOpenTab(row.id) else viewing = row.id },
                        onPin = { save(AccountPrefs.pinned(tabs, row.id, false)) }) }
                }
            }
            val own = unpinned.filter { !it.builtIn && !it.shared }
            val builtIn = unpinned.filter { it.builtIn }
            val sharedRows = unpinned.filter { it.shared }
            if (builtIn.isNotEmpty()) {
                item { SectionLabel("Chorus", Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                items(builtIn, key = { "b:${it.id}" }) { row -> FeedListRow(row, false, null, onClick = { onOpenTab(row.id) },
                    onPin = { save(AccountPrefs.pinned(tabs, row.id, true)) }) }
            }
            if (own.isNotEmpty()) {
                item { SectionLabel("Made by you", Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                items(own, key = { "o:${it.id}" }) { row -> FeedListRow(row, false, null, onClick = { viewing = row.id },
                    onPin = { save(AccountPrefs.pinned(tabs, row.id, true)) }) }
            }
            if (sharedRows.isNotEmpty()) {
                item { SectionLabel("Shared with you", Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                items(sharedRows, key = { "s:${it.id}" }) { row ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp))
                        .background(cardColor()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FeedTile(row.icon, true)
                            Column(Modifier.weight(1f)) {
                                Text(row.name, color = p.ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                Text("by ${row.by}", color = p.ink3, fontSize = 13.sp)
                            }
                        }
                        row.description?.let { Text(it, color = p.ink2, fontSize = 14.sp) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DrawerButton("Pin feed", { save(AccountPrefs.pinned(tabs, row.id, true)) }, ButtonKind.Neutral, modifier = Modifier.weight(1f))
                            DrawerButton("Preview", { viewing = row.id }, ButtonKind.Quiet, modifier = Modifier.weight(1f).border(1.dp, p.line, RoundedCornerShape(14.dp)))
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onLists).padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FeedTile(R.drawable.ic_folder, false)
                    Text("Lists", color = p.ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Glyph(R.drawable.ic_chevron_right, p.ink3, 20.dp)
                }
            }
        }
    }
}

@Composable
private fun FeedTile(icon: Int, accent: Boolean) {
    val p = LocalChorusPalette.current
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (accent) p.accentSoft else p.surface2), contentAlignment = Alignment.Center) {
        Glyph(icon, if (accent) p.accent else p.ink2, 20.dp)
    }
}

@Composable
private fun FeedListRow(row: FeedRow, pinned: Boolean, handle: Modifier?, onClick: () -> Unit, onPin: () -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FeedTile(row.icon, pinned)
        Column(Modifier.weight(1f)) {
            Text(row.name, color = p.ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("by ${row.by}", color = p.ink3, fontSize = 13.sp, maxLines = 1)
            row.description?.let { Text(it, color = p.ink2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Box(Modifier.size(40.dp).clip(CircleShape).background(if (pinned) p.accentSoft else p.surface2).clickable(role = Role.Switch, onClick = onPin),
            contentAlignment = Alignment.Center) { Glyph(R.drawable.ic_pin, if (pinned) p.accent else p.ink3, 18.dp, if (pinned) "Unpin ${row.name}" else "Pin ${row.name}") }
        if (handle != null) Box(handle.size(40.dp), contentAlignment = Alignment.Center) { Glyph(R.drawable.ic_grip, p.ink3, 18.dp) }
    }
}
