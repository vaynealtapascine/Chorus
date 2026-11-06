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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.data.SearchVisibility
import garden.vayne.chorus.data.ChatMessage
import garden.vayne.chorus.data.LocalSearch
import garden.vayne.chorus.data.CoreMessageSearch
import garden.vayne.chorus.data.MessageSearchResult
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.MessageSearchApi
import garden.vayne.chorus.data.PostSearchApi
import garden.vayne.chorus.data.Model
import androidx.compose.runtime.collectAsState
import garden.vayne.chorus.data.SearchDocument
import garden.vayne.chorus.designsystem.LocalChorusPalette
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Search the permitted local replica even when the server is asleep. */
@Composable
internal fun ContentSearch(chorus: Chorus, model: Model, online: Boolean, onClose: () -> Unit,
    onOpenMessage: (SearchDocument) -> Unit, onOpenPost: (String) -> Unit, channelId: String? = null, initialSection: String = "Messages") {
    val p = LocalChorusPalette.current
    val actions = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var section by rememberSaveable { mutableStateOf(initialSection) }
    val session=chorus.device?.session
    var remoteChannels by remember(model,session,online,query,section,channelId) { mutableStateOf<Set<String>>(emptySet()) }
    val dependencyOwner = remember(model,session,online,channelId) { Any() }
    val dependencies by produceState<Pair<Any?,Map<String,ChatMessage>>>(null to emptyMap(),dependencyOwner,remoteChannels) {
        value=null to emptyMap()
        val dev=chorus.device
        if(online && dev!=null) {
            val found=garden.vayne.chorus.data.SearchAncestors.load(model,dev,channelId?.let(::listOf) ?: (model.searchMessages.mapNotNull {it.channelId}.toSet() + remoteChannels))
            currentCoroutineContext().ensureActive()
            value=dependencyOwner to found
        }
    }
    val visibility = remember(model, channelId, dependencyOwner, dependencies) {
        SearchVisibility(model,chorus.device?.accountId.orEmpty(),channelId,if(dependencies.first===dependencyOwner && online) dependencies.second else emptyMap())
    }
    val frontRevision by chorus.frontHistoryRevision.collectAsState()
    val index by produceState<LocalSearch?>(initialValue = null, model, frontRevision) {
        value = null
        val saved = if (model.namedFronts != null) runCatching { chorus.frontHistory() }.getOrNull()?.saved else null
        value = withContext(Dispatchers.Default) { LocalSearch.fromModel(model, saved) }
    }
    val messageIndex by produceState<CoreMessageSearch?>(initialValue = null, model, visibility) {
        value = null
        value = withContext(Dispatchers.Default) { CoreMessageSearch(model, visibility::document) }
    }
    val messageResult by produceState(initialValue = MessageSearchResult(emptyList()), messageIndex, query, section) {
        value = MessageSearchResult(emptyList())
        val source = messageIndex
        if (section == "Messages" && source != null && query.isNotBlank()) {
            val now = System.currentTimeMillis()
            val tz = TimeZone.getDefault().getOffset(now) / 60_000
            value = withContext(Dispatchers.Default) { source.search(query, now, tz) }
        }
    }
    val results = if (section == "Messages") (if(messageResult.raw == query) messageResult.items else emptyList()) else
        remember(index, query, section) { index?.search(query, if(section == "Saved") "Posts" else section, limit = Int.MAX_VALUE).orEmpty()
            .filter { section != "Saved" || it.id in model.accountPrefs.bookmarks }.take(100) }
    val parsed = remember(query) { LocalSearch.parse(query) }
    // Recomposition selects a fresh owner synchronously. A-B-A and reconnect never reuse it.
    val validMessageQuery = remember(query) { runCatching { uniffi.chorus_ffi.searchParse(query) }.isSuccess }
    val requests = remember(query, section, session, online, model, channelId) { SearchRequests() }
    DisposableEffect(requests) { onDispose { requests.close() } }
    LaunchedEffect(requests) {
        val dev = chorus.device ?: return@LaunchedEffect
        if (!online || section == "Switches" || query.isBlank()) return@LaunchedEffect
        if (section == "Messages" && !validMessageQuery ||
            section in listOf("Posts", "Saved") && parsed.terms.isEmpty()) return@LaunchedEffect
        val requestedQuery = query
        val requestedSection = section
        requests.first(this) {
            delay(250)
            if (requestedSection == "Messages") {
                val now = System.currentTimeMillis()
                val page = MessageSearchApi.pageRaw(dev, requestedQuery, TimeZone.getDefault().getOffset(now) / 60_000, channelId = channelId)
                SearchRequests.Page(page.items, page.nextCursor)
            } else {
                val page = PostSearchApi.page(dev, parsed, saved = requestedSection == "Saved")
                SearchRequests.Page(page.items, page.nextCursor)
            }
        }
    }
    val remote = if (online) requests.items else emptyList()
    LaunchedEffect(requests,remote) {
        // Retain prior-page dependencies when another page introduces a new thread channel.
        remoteChannels = remoteChannels + remote.mapNotNull {it.channelId}
    }
    val cursor = requests.cursor
    val remoteError = requests.error
    val loading = requests.loading
    val known = results.mapTo(HashSet()) { it.id }
    // members' private chats and private chats in DMs only while theirs are here (D-097, D-098)
    val shown = (if (section == "Switches") results else results + (if(online) remote else emptyList()).filter { doc ->
        doc.id !in known && (section !in listOf("Posts", "Saved") || parsed.from == null ||
            doc.authors.any { it == parsed.from } || doc.authorNames.any { it.contains(parsed.from, ignoreCase = true) })
    }).filter { doc -> (doc.kind != "Posts" || doc.id !in model.deletedPostIds) && (section != "Saved" || doc.id in model.accountPrefs.bookmarks) &&
        (doc.channelId == null || visibility.document(doc)) }
    val contextIds = shown.filter { it.kind == "Messages" }.associate { doc ->
        doc.id to visibility.context(doc, (if(online) remote.firstOrNull {it.id==doc.id} else null).let {listOfNotNull(it?.before,it?.after)} + listOfNotNull(doc.before,doc.after)) }
    val ids = contextIds.values.flatten().toSet()
    val localCards by produceState<Pair<Model?,Map<String,ChatMessage>>>(null to emptyMap(), model, ids) {
        value = null to emptyMap()
        val found = chorus.searchCards(ids)
        currentCoroutineContext().ensureActive()
        value = model to found
    }
    val cards = ((shown + if(online) remote else emptyList()).flatMap { listOfNotNull(it.card,it.before,it.after) }.associateBy {it.id} +
        if(localCards.first === model) localCards.second else emptyMap()).filterValues(visibility::message)

    fun loadMore() {
        val dev = chorus.device ?: return
        val requestedQuery = query
        val requestedSection = section
        requests.more(actions) { next ->
            if (requestedSection == "Messages") {
                val now = System.currentTimeMillis()
                val page = MessageSearchApi.pageRaw(dev, requestedQuery, TimeZone.getDefault().getOffset(now) / 60_000, next, channelId)
                SearchRequests.Page(page.items, page.nextCursor)
            } else {
                val page = PostSearchApi.page(dev, parsed, next, saved = requestedSection == "Saved")
                SearchRequests.Page(page.items, page.nextCursor)
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize().background(p.bg).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if(channelId != null) "Search this chat" else "Search", color = p.ink, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 14.dp))
                TextButton(onClick = onClose) { Text("Close") }
            }
            // opening search is asking to type: the field takes the keyboard at once
            val typing = remember { androidx.compose.ui.focus.FocusRequester() }
            LaunchedEffect(Unit) { runCatching { typing.requestFocus() } }
            ChorusField(query, { query = it }, label = { Text(if(channelId != null) "Search this chat" else "Search ${if(section == "Messages") "all chats" else if(section == "Posts") "Journal" else section}") },
                singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(typing))
            Text("Try words or from:, in:, has:, before:, after:, is:pinned.", color = p.ink2)
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (choice in if(channelId != null) emptyList() else listOf("Messages", "Posts", "Saved", "Switches")) item(key = choice) {
                    JournalChoice(if(choice == "Messages") "All chats" else if(choice == "Posts") "Journal" else choice, section == choice) { section = choice }
                }
            }
        }
        if(!online) item { Text("Offline · local replica only. Shared posts need a connection.",color=p.ink2) }
        val available=channelId == null || visibility.document(SearchDocument("__search_channel__","Messages",Long.MAX_VALUE,"",channelId=channelId))
        if(!available) item { Text("This chat is unavailable to the current front.",color=p.ink2) }
        else if (index == null || section == "Messages" && messageIndex == null)
            item { Text("Preparing local search…", color = p.ink2) }
        else if (query.isBlank()) item { Text("Type to search this scope. Chats use the current front; Journal and Saved contain posts and entries.", color = p.ink2) }
        else if (section == "Messages" && messageResult.error != null)
            item { Text("Search: ${messageResult.error}", color = p.ink2) }
        else if (shown.isEmpty() && !loading) item { Text("Nothing in ${when (section) { "Messages" -> "chats"; "Posts" -> "the Journal"; "Saved" -> "saved posts"; else -> section.lowercase() }} matches, on this device${if (online && section != "Switches") " or the server" else ""}.", color = p.ink2) }
        for (doc in shown) item(key = "${doc.kind}:${doc.id}") {
            if (doc.kind == "Messages") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(model.channels.firstOrNull {it.id==doc.channelId}?.name ?: "Chat",color=p.ink2)
                    for (id in contextIds[doc.id].orEmpty()) cards[id]?.let { card ->
                        if(id==doc.id) Text("Match",color=p.accent)
                        ChatMessageCard(card,model,emptyMap(),chorus,emptyList(),null,null,onReply={},
                            onReplyPrivately=null,onEdit=null,reactions=model.messageReactions[card.id].orEmpty(),interactive=false,onStage={})
                    }
                    TextButton(onClick={ if(visibility.document(doc)) onOpenMessage(doc) }) {Text("Open message")}
                }
            } else if(doc.kind == "Posts") {
                val post=model.posts.firstOrNull {it.id==doc.id}
                if(post != null) JournalPostCard(post,model,chorus,onReply={onOpenPost(it.id)},onThread={onOpenPost(it.id)},showActions=false)
                else doc.sharedPost?.let {SharedPostPreview(it,"Shared post",chorus,model,onThread={onOpenPost(it.id)})}
            } else SearchCard(doc, model, onOpenMessage, onOpenPost)
        }
        if (results.size == 100) item { Text("Showing the newest 100 local matches. Narrow the search for more.", color = p.ink2) }
        if (loading) item { Text("Searching server…", color = p.ink2) }
        if (remoteError != null && section != "Switches") item { Text("Server search unavailable: ${remoteError.orEmpty()}", color = p.ink2) }
        if (section != "Switches" && cursor != null && online) item {
            TextButton(enabled = !loading, onClick = ::loadMore) { Text("Load more from server") }
        }
        item { Text("", modifier = Modifier.padding(bottom = 16.dp)) }
    }
}

@Composable
private fun SearchCard(doc: SearchDocument, model: Model,
    onOpenMessage: (SearchDocument) -> Unit, onOpenPost: (String) -> Unit, channelId: String? = null, initialSection: String = "Messages") {
    val p = LocalChorusPalette.current
    var revealed by rememberSaveable(doc.id) { mutableStateOf(false) }
    val label = when (doc.kind) {
        "Messages" -> model.channels.find { it.id == doc.channelId }?.name?.let { "#$it" } ?: "Message"
        "Posts" -> (doc.authorNames.ifEmpty { doc.authors.mapNotNull { model.member(it)?.shownName } })
            .joinToString(" & ").ifBlank { "Post" }
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
        if (doc.kind == "Posts") TextButton(onClick = { onOpenPost(doc.id) }) { Text("Open thread") }
        else if (doc.kind == "Messages" && model.channels.any { it.id == doc.channelId })
            TextButton(onClick = { onOpenMessage(doc) }) { Text("Open channel") }
    }
}
