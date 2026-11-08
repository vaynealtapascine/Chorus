package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import garden.vayne.chorus.data.SearchFilters
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
import androidx.compose.ui.unit.sp
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
import garden.vayne.chorus.designsystem.R
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The search page's tabs (F5.9) and the D-114 scopes beside them: (section, label). */
private val SEARCH_TABS = listOf("Chats" to "Chats", "Messages" to "Messages", "Media" to "Media", "Files" to "Files",
    "Links" to "Links", "Posts" to "Journal", "Saved" to "Saved", "Switches" to "Switches")

/** Tabs answered from what the device already shows, without the search index or the server. */
private val LIBRARY_TABS = setOf("Chats", "Media", "Files", "Links")

/**
 * Search (Figma F5.9): the field in the top bar, tabs (Chats, Messages, Media, Files, Links, then
 * Journal, Saved and Switches, D-114), and for messages the From / In / date chips, which write
 * the query's operators. Message results stay D-114 context cards; the whole card opens it.
 * Searches the permitted local replica even when the server is asleep.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ContentSearch(chorus: Chorus, model: Model, online: Boolean, onClose: () -> Unit,
    onOpenMessage: (SearchDocument) -> Unit, onOpenPost: (String) -> Unit, channelId: String? = null, initialSection: String = "Messages",
    onOpenChat: (String) -> Unit = {}) {
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
    val results = if (section in LIBRARY_TABS) emptyList() else if (section == "Messages") (if(messageResult.raw == query) messageResult.items else emptyList()) else
        remember(index, query, section) { index?.search(query, if(section == "Saved") "Posts" else section, limit = Int.MAX_VALUE).orEmpty()
            .filter { section != "Saved" || it.id in model.accountPrefs.bookmarks }.take(100) }
    val parsed = remember(query) { LocalSearch.parse(query) }
    // Recomposition selects a fresh owner synchronously. A-B-A and reconnect never reuse it.
    val validMessageQuery = remember(query) { runCatching { uniffi.chorus_ffi.searchParse(query) }.isSuccess }
    val requests = remember(query, section, session, online, model, channelId) { SearchRequests() }
    DisposableEffect(requests) { onDispose { requests.close() } }
    LaunchedEffect(requests) {
        val dev = chorus.device ?: return@LaunchedEffect
        if (!online || section == "Switches" || section in LIBRARY_TABS || query.isBlank()) return@LaunchedEffect
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
    val remote = if (online && section !in LIBRARY_TABS) requests.items else emptyList()
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
    val words = SearchFilters.words(query)
    val accountId = chorus.device?.accountId.orEmpty()
    // what this front may see now, for the tabs that list chats and what they shared
    val visibleMessages = remember(model, visibility, section) {
        if (section !in setOf("Media", "Files", "Links")) emptyList()
        else model.chatMessages.values.flatten().filter { (channelId == null || it.channelId == channelId) && visibility.message(it) }
    }
    val chats = remember(model, accountId, section, words) {
        if (section != "Chats") emptyList()
        else chatListRows(model, null, accountId, emptyMap(), emptyMap(), emptyMap())
            .filter { SearchFilters.matches(words, it.title, it.space.name) }.sortedByDescending { it.latest?.occurredAt ?: 0L }
    }
    var viewing by remember { mutableStateOf<garden.vayne.chorus.data.ChatFileItem?>(null) }
    var chip by remember { mutableStateOf<String?>(null) }
    fun openAt(m: ChatMessage) = onOpenMessage(SearchDocument(m.id, "Messages", m.occurredAt, m.text, channelId = m.channelId))
    fun place(id: String?): String = model.channels.firstOrNull { it.id == id }?.let { c ->
        val space = model.spaces.firstOrNull { it.id == c.spaceId }
        listOfNotNull(c.name.ifBlank { null }, space?.name?.takeIf { it.isNotBlank() && space.kind == "shared" }).joinToString(" • ")
    } ?: "Chat"
    Column(Modifier.fillMaxSize().background(p.bg)) {
        // the field lives in the top bar (F5.9): back, the search, and × to clear it
        Row(Modifier.fillMaxWidth().background(p.surface).heightIn(min = 64.dp).padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            BarIcon(R.drawable.ic_back, "Back", p.ink) { onClose() }
            Row(Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).background(fieldFill()).padding(start = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Glyph(R.drawable.ic_search, p.ink3, 18.dp)
                // opening search is asking to type: the field takes the keyboard at once
                val typing = remember { androidx.compose.ui.focus.FocusRequester() }
                LaunchedEffect(Unit) { runCatching { typing.requestFocus() } }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text(if (channelId != null) "Search this chat" else "Search", color = p.ink3, fontSize = 15.sp)
                    BasicTextField(query, { query = it }, singleLine = true, cursorBrush = SolidColor(p.accent),
                        textStyle = TextStyle(color = p.ink, fontSize = 15.sp),
                        modifier = Modifier.fillMaxWidth().focusRequester(typing).semantics { contentDescription = "Search" })
                }
                if (query.isNotEmpty()) BarIcon(R.drawable.ic_close, "Clear", p.ink2, 18.dp) { query = "" }
                else Spacer(Modifier.width(12.dp))
            }
        }
        if (channelId == null) Column(Modifier.background(p.surface)) {
            InfoTabs(SEARCH_TABS, section) { section = it }
            HorizontalDivider(color = p.line)
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp)) {
            if (section == "Messages") item(key = "chips") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val from = SearchFilters.value(query, "from")
                    ChoiceChip("From: " + (from ?: "any member"), { chip = "from" }, active = from != null)
                    if (channelId == null) SearchFilters.value(query, "in").let { at ->
                        ChoiceChip("In: " + (at ?: "all chats"), { chip = "in" }, active = at != null)
                    }
                    val date = SearchFilters.dateLabel(query)
                    ChoiceChip(date, { chip = "after" }, active = SearchFilters.value(query, "after") != null)
                }
            }
            val available=channelId == null || visibility.document(SearchDocument("__search_channel__","Messages",Long.MAX_VALUE,"",channelId=channelId))
            if(!available) item { EmptyState(R.drawable.ic_lock, "Private chat") }
            else if (section == "Chats") {
                items(chats, key = { "chat:${it.channel.id}" }) { row ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Open chat") { onOpenChat(row.channel.id) }
                        .heightIn(min = 60.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ChatRowDisc(row, 44.dp, row.peer(model, null))
                        Column(Modifier.weight(1f)) {
                            Text(row.title, color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val line = when (row.space.kind) {
                                "dm" -> "Direct"
                                "internal" -> model.systemName?.takeIf { it.isNotBlank() } ?: row.space.name
                                else -> place(row.channel.id).takeIf { it != row.title } ?: row.space.name
                            }
                            Text(line, color = p.ink3, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
                if (chats.isEmpty()) item { EmptyState(R.drawable.ic_search, "No matches") }
            }
            else if (section == "Media") {
                val media = garden.vayne.chorus.data.ChatLibrary.media(visibleMessages).filter { SearchFilters.matches(words, it.attachment.filename, it.attachment.altText, it.message.text) }
                items(media.take(150).chunked(3), key = { row -> "media:" + row.first().attachment.id }) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (item in row) MediaTile(item, chorus, Modifier.weight(1f).aspectRatio(1f)) { viewing = item }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                if (media.isEmpty()) item { EmptyState(R.drawable.ic_image, "Nothing yet") }
            }
            else if (section == "Files") {
                val files = garden.vayne.chorus.data.ChatLibrary.files(visibleMessages).filter { SearchFilters.matches(words, it.attachment.filename, it.message.text) }
                items(files.take(150), key = { "file:" + it.attachment.id }) { item ->
                    FileRow(item, chorus, garden.vayne.chorus.data.ChatLibrary.who(item.message, model))
                }
                if (files.isEmpty()) item { EmptyState(R.drawable.ic_file, "Nothing yet") }
            }
            else if (section == "Links") {
                val links = garden.vayne.chorus.data.ChatLibrary.links(visibleMessages).filter { SearchFilters.matches(words, it.url, it.message.text) }
                items(links.take(150), key = { "link:" + it.message.id + it.url }) { link ->
                    LinkRow(link, garden.vayne.chorus.data.ChatLibrary.who(link.message, model))
                }
                if (links.isEmpty()) item { EmptyState(R.drawable.ic_link, "Nothing yet") }
            }
            else if (index == null || section == "Messages" && messageIndex == null)
                item { Text("Preparing local search…", color = p.ink2) }
            else if (query.isBlank()) Unit
            else if (section == "Messages" && messageResult.error != null)
                item { Text("Search: ${messageResult.error}", color = p.ink2) }
            else if (shown.isEmpty() && !loading) item { EmptyState(R.drawable.ic_search, "No matches") }
            else if (section == "Messages") item(key = "count") {
                Text(if (shown.size == 1) "1 message found" else "${shown.size}${if (results.size == 100 || cursor != null) "+" else ""} messages found",
                    color = p.ink3, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            if (available && section !in LIBRARY_TABS) for (doc in shown) item(key = "${doc.kind}:${doc.id}") {
                if (doc.kind == "Messages") {
                    // the whole card opens the message (F5.9); the match is the bright one, its neighbours dim
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Open message") { if(visibility.document(doc)) onOpenMessage(doc) },
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(place(doc.channelId), color = p.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(garden.vayne.chorus.data.ChatPresentation.dateLabel(doc.occurredAt), color = p.ink3, fontSize = 12.sp)
                        }
                        for (id in contextIds[doc.id].orEmpty()) cards[id]?.let { card ->
                            Box(Modifier.alpha(if (id == doc.id) 1f else .55f)) {
                                ChatMessageCard(card,model,emptyMap(),chorus,emptyList(),null,null,onReply={},
                                    onReplyPrivately=null,onEdit=null,reactions=model.messageReactions[card.id].orEmpty(),interactive=false,onStage={})
                            }
                        }
                    }
                } else if(doc.kind == "Posts") {
                    val post=model.posts.firstOrNull {it.id==doc.id}
                    if(post != null) JournalPostCard(post,model,chorus,onReply={onOpenPost(it.id)},onThread={onOpenPost(it.id)},showActions=false)
                    else doc.sharedPost?.let {SharedPostPreview(it,"Shared post",chorus,model,onThread={onOpenPost(it.id)})}
                } else SearchCard(doc, model, onOpenMessage, onOpenPost)
            }
            if (section !in LIBRARY_TABS) {
                if (loading) item { Text("Searching server…", color = p.ink2) }
                if (remoteError != null && section != "Switches") item { Text("Server search unavailable: ${remoteError.orEmpty()}", color = p.ink2) }
                if (section != "Switches" && cursor != null && online) item {
                    TextButton(enabled = !loading, onClick = ::loadMore) { Text("Load more from server") }
                }
            }
            if (!online && query.isNotBlank()) item(key = "device") {
                Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph(R.drawable.ic_clock, p.ink3, 16.dp)
                    Text("Searching this device", color = p.ink3, fontSize = 13.sp)
                }
            }
            item { Spacer(Modifier.padding(bottom = 16.dp)) }
        }
    }
    viewing?.let { item ->
        MediaViewer(item.attachment, chorus, garden.vayne.chorus.data.ChatLibrary.who(item.message, model), item.message.occurredAt,
            item.message.cw?.let { "Content warning: $it" } ?: "Spoiler".takeIf { item.attachment.spoiler }, onClose = { viewing = null },
            onShowMessage = { viewing = null; openAt(item.message) })
    }
    // the chips' choices: who wrote it, where, since when
    when (chip) {
        "from" -> {
            val members = model.active.filter { it.createdByAccountId == null || it.createdByAccountId == accountId }
            ChoiceDrawer(true, "From", listOf<garden.vayne.chorus.data.Member?>(null) + members,
                members.firstOrNull { it.shownName.equals(SearchFilters.value(query, "from"), ignoreCase = true) },
                { it?.shownName ?: "Any member" }, { m -> query = SearchFilters.set(query, "from", m?.shownName); chip = null }, { chip = null })
        }
        "in" -> {
            val rows = remember(model, accountId) { chatListRows(model, null, accountId, emptyMap(), emptyMap(), emptyMap()) }
            ChoiceDrawer(true, "In", listOf<ChatListRow?>(null) + rows,
                rows.firstOrNull { it.channel.name.equals(SearchFilters.value(query, "in"), ignoreCase = true) },
                { it?.title ?: "All chats" }, { r -> query = SearchFilters.set(query, "in", r?.channel?.name?.ifBlank { r.channel.id }); chip = null }, { chip = null })
        }
        "after" -> ChoiceDrawer(true, "Date", SearchFilters.DATES, SearchFilters.DATES.firstOrNull { it.second == SearchFilters.value(query, "after") },
            { it.first }, { d -> query = SearchFilters.set(query, "after", d.second); chip = null }, { chip = null })
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
