package garden.vayne.chorus.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import garden.vayne.chorus.designsystem.R
import garden.vayne.chorus.data.JournalPresentation
import androidx.compose.ui.unit.sp
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.UploadWork
import garden.vayne.chorus.data.JournalPost
import garden.vayne.chorus.data.JournalSocial
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.Polls
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import garden.vayne.chorus.data.NamedHistory
import garden.vayne.chorus.data.Entry
import garden.vayne.chorus.data.PostCompose
import garden.vayne.chorus.data.PostDetail
import garden.vayne.chorus.data.PostReaction
import garden.vayne.chorus.data.PostReactions
import garden.vayne.chorus.data.PostThreads
import garden.vayne.chorus.data.Reply
import garden.vayne.chorus.SharedDraft
import garden.vayne.chorus.data.ThreadReply
import garden.vayne.chorus.designsystem.LocalChorusPalette
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/** Own posts and switches remain readable from the local replica while offline. */
@Composable
fun Journal(chorus: Chorus, model: Model, externalReplyPost: String? = null,
    onExternalReplyConsumed: () -> Unit = {}, externalOpenPost: String? = null,
    onExternalOpenConsumed: () -> Unit = {}, sharedDraft: SharedDraft? = null,
    onShareConsumed: () -> Unit = {}, externalNewEntry: Long? = null,
    onExternalNewEntryConsumed: () -> Unit = {}, active: Boolean = true,
    onFocusedRoute: (Boolean) -> Unit = {}, externalOpenProfile: String? = null,
    onExternalProfileConsumed: () -> Unit = {}) {
    val p = LocalChorusPalette.current
    val connection by chorus.status.collectAsState()
    var editorOptions by rememberSaveable { mutableStateOf(false) }
    var journalTab by rememberSaveable { mutableStateOf("system") }
    var feedMenu by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var profileId by rememberSaveable { mutableStateOf<String?>(null) }
    var threadPostId by rememberSaveable { mutableStateOf<String?>(null) }
    var section by rememberSaveable { mutableStateOf("timeline") }
    var selectedListId by rememberSaveable { mutableStateOf("") }
    var replyTo by rememberSaveable { mutableStateOf<String?>(null) }
    var quoteOf by rememberSaveable { mutableStateOf<String?>(null) }
    var kind by rememberSaveable { mutableStateOf("note") }
    var authorId by rememberSaveable { mutableStateOf("") }
    var bodyValue by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    val body = bodyValue.text
    var title by rememberSaveable { mutableStateOf("") }
    var cw by rememberSaveable { mutableStateOf("") }
    var mood by rememberSaveable { mutableStateOf("") }
    var tags by rememberSaveable { mutableStateOf("") }
    var pollOn by rememberSaveable { mutableStateOf(false) }
    val pollOptions = rememberSaveable(saver = PollOptionsSaver) { mutableStateListOf("", "") }
    var pollMultiple by rememberSaveable { mutableStateOf(false) }
    var audience by rememberSaveable { mutableStateOf("private") }
    var busy by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val actions = rememberCoroutineScope()
    val ctx = LocalContext.current
    val attachments = rememberSaveable(saver = AttachmentDraftSaver) { mutableStateListOf<PendingAttachment>() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        for (uri in uris) attachments.add(PendingAttachment.of(ctx, uri))
    }
    val mine = model.active.filter { it.createdByAccountId == null || it.createdByAccountId == chorus.device?.accountId }
    val author = mine.find { it.id == authorId } ?: Reply.speaker(model)?.takeIf { it in mine } ?: mine.firstOrNull()

    LaunchedEffect(externalOpenProfile) {
        if (externalOpenProfile != null) {
            editing = false; threadPostId = null; profileId = externalOpenProfile
            onExternalProfileConsumed()
        }
    }

    LaunchedEffect(sharedDraft?.id, chorus.device?.accountId) {
        val incoming = sharedDraft ?: return@LaunchedEffect
        if (chorus.device == null) return@LaunchedEffect
        profileId = null; threadPostId = null; replyTo = null
        section = "timeline"
        if (incoming.text.isNotBlank()) {
            val text = listOf(body, incoming.text).filter { it.isNotBlank() }.joinToString("\n")
            bodyValue = TextFieldValue(text, TextRange(text.length))
        }
        for (uri in incoming.uris) {
            try { attachments.add(PendingAttachment.of(ctx, uri)) }
            catch (_: Exception) { error = "One shared file could not be opened. Please attach it again." }
        }
        editing = true
        onShareConsumed()
    }
    LaunchedEffect(externalNewEntry, chorus.device?.accountId) {
        if (externalNewEntry == null || chorus.device == null) return@LaunchedEffect
        profileId = null; threadPostId = null; replyTo = null
        section = "timeline"; kind = "entry"; editing = true
        onExternalNewEntryConsumed()
    }

    LaunchedEffect(externalReplyPost) {
        if (externalReplyPost != null) {
            profileId = null; threadPostId = null
            section = "timeline"
            replyTo = externalReplyPost; quoteOf = null
            kind = "note"; title = ""; bodyValue = TextFieldValue(); cw = ""; mood = ""; tags = ""
            attachments.clear()
            audience = "server" // the foreign parent author must be able to read this reply
            authorId = Reply.speaker(model)?.id.orEmpty()
            editing = true
            onExternalReplyConsumed()
        }
    }

    LaunchedEffect(externalOpenPost) {
        if (externalOpenPost != null) {
            editing = false; profileId = null; threadPostId = externalOpenPost
            section = "timeline"
            onExternalOpenConsumed()
        }
    }

    val feedListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val route = when {
        editing -> "write"
        profileId != null -> "profile:$profileId"
        threadPostId != null -> "thread:$threadPostId"
        else -> section
    }
    androidx.compose.runtime.SideEffect { if (active) onFocusedRoute(editing || profileId != null || threadPostId != null) }
    ChorusScene(route, if (editing) 2 else if (profileId != null || threadPostId != null || section != "timeline") 1 else 0) { shown ->
    if (shown.startsWith("profile:")) {
        val id = shown.substringAfter(":")
        BackHandler(active && shown == route) { profileId = null }
        MemberProfile(chorus, model, id, onClose = { profileId = null },
            onWrite = { authorId = id; replyTo = null; editing = true },
            onReply = { postId -> authorId = id; replyTo = postId; editing = true })
        return@ChorusScene
    }

    if (shown.startsWith("thread:")) {
        val id = shown.substringAfter(":")
        BackHandler(active && shown == route) { threadPostId = null }
        JournalThread(chorus, model, id, onClose = { threadPostId = null },
            onReply = { replyTo = id; editing = true })
        return@ChorusScene
    }

    if (shown == "write") {
        BackHandler(active && shown == route) { if (!busy) editing = false }
        val bodyFocus = remember { FocusRequester() }
        Column(Modifier.fillMaxSize().background(p.bg).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(enabled = !busy, onClick = { editing = false }) { Text("Cancel") }
                Text(if (replyTo == null) "Draft" else "Write a reply", color = p.ink3,
                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 14.dp))
                Button(enabled = !busy && author != null && (body.isNotBlank() || attachments.isNotEmpty()), onClick = {
                    val dev = chorus.device ?: return@Button
                    val writer = author ?: return@Button
                    val picked = attachments.toList()
                    // Snapshot the privacy/authoring choices at Post, before file staging suspends.
                    val writingKind = kind
                    val writingBody = body
                    val writingTitle = title
                    val writingCw = cw
                    val writingAudience = audience
                    val writingMood = mood
                    val writingTags = tags
                    val writingReply = replyTo
                    val writingQuote = quoteOf
                    val writingPoll = if (pollOn) Polls.draft(pollOptions.toList(), pollMultiple).getOrElse {
                        error = it.message; return@Button
                    } else null
                    busy = true; error = null
                    actions.launch {
                        try {
                            val attachmentIds = picked.map { a ->
                                sendAttachment(chorus, ctx, a, opKind = "post.attachment")
                            }
                            val payload = PostCompose.payload(model, dev.accountId, writingKind, writer.id, writingBody,
                                writingTitle, writingCw, writingAudience, writingMood, writingTags, writingReply, attachmentIds = attachmentIds, quotePostId = writingQuote)
                            if (writingPoll != null) payload.put("poll", writingPoll)
                            chorus.create("post.create", chorus.newId(), payload)
                            pollOn = false; pollMultiple = false
                            pollOptions.clear(); pollOptions.addAll(listOf("", ""))
                            if (attachmentIds.isNotEmpty()) UploadWork.enqueue(ctx)
                            bodyValue = TextFieldValue(); title = ""; cw = ""; mood = ""; tags = ""; replyTo = null; quoteOf = null
                            attachments.clear()
                            editing = false
                        } catch (e: Exception) { error = e.message ?: "Could not post." }
                        finally { busy = false }
                    }
                }) { Text(if (busy) "Posting…" else "Post") }
            }
            Row(Modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.background(p.surface2, CircleShape)) {
                    JournalModeChoice("Post", kind == "note") { if (!busy) kind = "note" }
                    JournalModeChoice("Journal entry", kind == "entry") { if (!busy) kind = "entry" }
                }
            }
            Box(Modifier.padding(horizontal = 16.dp)) { JournalAuthorPicker(author, mine) { if (!busy) authorId = it } }
            LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (replyTo != null) item {
                    Text("Replying to a post. Check the audience before posting.", color = p.ink2)
                }
                if (kind == "entry") item {
                    JournalTextField(title, { title = it }, "Title (optional)", MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
                        Modifier.padding(start = 52.dp), enabled = !busy)
                }
                item {
                    BasicTextField(bodyValue, { bodyValue = it }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().padding(start = 52.dp).focusRequester(bodyFocus)
                            .semantics { contentDescription = "Post text" },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = p.ink, fontSize = 16.sp, lineHeight = 24.sp),
                        cursorBrush = SolidColor(p.accent), minLines = 4,
                        decorationBox = { inner -> Box {
                            if (body.isEmpty()) Text(if (kind == "entry") "Write your entry…" else if (quoteOf != null) "Add a comment…" else "What’s on your mind?", color = p.ink3,
                                style = MaterialTheme.typography.bodyLarge)
                            inner()
                        } })
                }
                if (quoteOf != null) item {
                    Column(Modifier.padding(start = 52.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        QuoteEmbed(model.posts.find { it.id == quoteOf }?.takeIf { it.repostOf == null }, model)
                        TextButton(enabled = !busy, onClick = { quoteOf = null }) { Text("Remove quote") }
                    }
                }
                if (attachments.isNotEmpty()) item {
                    Box(Modifier.padding(start = 52.dp)) {
                        JournalAttachments(attachments, !busy, onChange = { index, file -> attachments[index] = file },
                            onRemove = { index -> attachments.removeAt(index) })
                    }
                }
                if (editorOptions && !busy) {
                    item { OutlinedTextField(cw, { cw = it }, label = { Text("Content warning (optional)") }, modifier = Modifier.fillMaxWidth()) }
                    item { OutlinedTextField(mood, { mood = it }, label = { Text("Mood (optional)") }, modifier = Modifier.fillMaxWidth()) }
                    item { OutlinedTextField(tags, { tags = it }, label = { Text("Tags, separated by commas") }, modifier = Modifier.fillMaxWidth()) }
                    item { Column { PollDraftFields(pollOn, { pollOn = it }, pollOptions, pollMultiple) { pollMultiple = it } } }
                }
                if (error != null) item { Text(error.orEmpty(), color = p.danger) }
            }
            HorizontalDivider(color = p.line)
            Column(Modifier.fillMaxWidth().background(p.surface)) {
                JournalAudiencePicker(audience, model.isPerson) { if (!busy) audience = it }
                HorizontalDivider(color = p.line, thickness = .5.dp)
                JournalFormatToolbar(bodyValue, !busy) { bodyValue = it; bodyFocus.requestFocus() }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ChatIconButton(R.drawable.ic_image, "Attach images or files") { if (!busy) picker.launch("*/*") }
                    ChatIconButton(R.drawable.ic_more, if (editorOptions) "Hide post options" else "Warning, mood, tags and poll") { editorOptions = !editorOptions }
                    Text(listOfNotNull("CW".takeIf { cw.isNotBlank() }, "Poll".takeIf { pollOn }, "${attachments.size} ${if (attachments.size == 1) "file" else "files"}".takeIf { attachments.isNotEmpty() }).joinToString(" · "),
                        color = p.ink3, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text("${body.trim().split(Regex("\\s+")).count { it.isNotBlank() }} words", color = p.ink3,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 12.dp))
                }
            }
        }
        return@ChorusScene
    }

    if (shown == "lists") {
        JournalLists(chorus, model, selectedListId, onSelect = { selectedListId = it },
            onTimeline = { section = "timeline" },
            onReply = { replyTo = it; editing = true },
            onThread = { threadPostId = it })
        return@ChorusScene
    }
    if (shown == "feeds") {
        JournalFeeds(chorus, model, onTimeline = { section = "timeline" })
        return@ChorusScene
    }
    if (shown == "saved") {
        BackHandler(active && shown == route) { section = "timeline" }
        JournalSaved(chorus, model, onBack = { section = "timeline" }, onReply = { replyTo = it; editing = true },
            onThread = { threadPostId = it }, onQuote = { quoteOf = it.id; replyTo = null; kind = "note"; editing = true },
            onProfile = { profileId = it })
        return@ChorusScene
    }

    // a configured account reads Body's effective changes (exact Fronting layer ID), never the stale log
    val frontRevision by chorus.frontHistoryRevision.collectAsState()
    val named by produceState<NamedHistory?>(null, frontRevision, model.namedFronts != null) {
        value = if (model.namedFronts != null) runCatching { chorus.frontHistory() }.getOrNull() else null
    }
    fun fronting(entries: List<Entry>) = "Front: " + entries.filter { e -> e.level == "front" }
        .mapNotNull { e -> model.subject(e.subjectType, e.subjectId)?.name }.joinToString(" & ").ifBlank { "no one" }
    val switchEvents = if (model.namedFronts != null)
        named?.changes.orEmpty().map { JournalEvent(it.id, it.occurredAt, null, fronting(it.entries.map { e -> e.displayEntry() })) }
    else model.switches.map { JournalEvent(it.id, it.occurredAt, null, if (it.retracted) "Undone switch" else fronting(it.resultingFront)) }
    val events = (model.posts.map { JournalEvent(it.id, it.occurredAt, it, null) } + switchEvents)
        .sortedWith(compareByDescending<JournalEvent> { it.at }.thenByDescending { it.id })
    Column(Modifier.fillMaxSize().background(p.bg)) {
        Row(Modifier.fillMaxWidth().background(p.surface).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            author?.let { ChatAvatar(it, 36.dp) }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text("Journal", style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp, lineHeight = 26.sp), color = p.ink)
                Text(if (connection != garden.vayne.chorus.data.Status.Live) "Offline · saved on this device"
                    else author?.let { "Posting as ${it.shownName}" } ?: "Your system’s stories", color = p.ink3,
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                ChatIconButton(R.drawable.ic_more, "Journal options") { feedMenu = true }
                DropdownMenu(feedMenu, { feedMenu = false }) {
                    DropdownMenuItem(text = { Text("Saved") }, onClick = { feedMenu = false; section = "saved" })
                    DropdownMenuItem(text = { Text("Lists") }, onClick = { feedMenu = false; section = "lists" })
                    DropdownMenuItem(text = { Text("Feeds") }, onClick = { feedMenu = false; section = "feeds" })
                    if (model.isPerson) model.members.firstOrNull { it.isSelf }?.let { self ->
                        DropdownMenuItem(text = { Text("Profile") }, onClick = { feedMenu = false; profileId = self.id })
                    }
                }
            }
        }
        ScrollableTabRow(selectedTabIndex = listOf("system", "friends", "entries").indexOf(journalTab).coerceAtLeast(0),
            containerColor = p.surface, contentColor = p.accent, edgePadding = 16.dp, divider = { HorizontalDivider(color = p.line) }) {
            for ((id, label) in listOf("system" to "Our system", "friends" to "Friends", "entries" to "Journals")) Tab(
                selected = journalTab == id, onClick = { journalTab = id }, text = { Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, lineHeight = 18.sp)) },
                selectedContentColor = p.ink, unselectedContentColor = p.ink3)
        }
        Box(Modifier.weight(1f)) {
        if (journalTab == "friends") JournalFriends(chorus, model, onThread = { threadPostId = it }, onReply = {
            replyTo = it; audience = "server"; editing = true
        }) else LazyColumn(Modifier.fillMaxSize(), state = feedListState, contentPadding = PaddingValues(bottom = 80.dp)) {
        if (events.none { journalTab != "entries" || it.post?.kind == "entry" }) item {
            Text(if (journalTab == "entries") "No journal entries yet. Start with a few words." else "No posts or switches yet. Write the first note.",
                color = p.ink2, modifier = Modifier.padding(24.dp))
        }
        for (event in events.filter { journalTab != "entries" || it.post?.kind == "entry" }) item(key = "${if (event.post == null) "switch" else "post"}:${event.id}") {
            if (event.post != null) JournalPostCard(event.post, model, chorus,
                onReply = { replyTo = it.id; editing = true },
                onThread = { threadPostId = it.id },
                onQuote = { quoteOf = it.id; replyTo = null; kind = "note"; editing = true },
                onOpenPost = { threadPostId = it },
                onProfile = { profileId = it })
            else Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(event.switchLabel.orEmpty(), color = p.ink2)
                Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(event.at)),
                    color = p.ink3, fontSize = 12.sp)
            }
        }
        }
        FloatingActionButton(onClick = { replyTo = null; if (journalTab == "entries") kind = "entry"; editing = true },
            containerColor = p.accent, contentColor = p.onAccent, shape = CircleShape,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).size(56.dp)) {
            ChatIcon(R.drawable.ic_edit, "Write a post", p.onAccent)
        }
        }
    }
    }
}

private data class JournalEvent(val id: String, val at: Long, val post: JournalPost?, val switchLabel: String?)

@Composable
internal fun JournalChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    Text(label, color = if (selected) p.accent else p.ink2,
        modifier = Modifier.background(if (selected) p.surface2 else p.surface, RoundedCornerShape(24.dp))
            .clickable(onClick = onClick).heightIn(min = 48.dp).wrapContentSize(Alignment.Center).padding(horizontal = 12.dp, vertical = 8.dp))
}

@Composable
internal fun JournalPostCard(post: JournalPost, model: Model, chorus: Chorus, onReply: (JournalPost) -> Unit,
    onThread: ((JournalPost) -> Unit)? = null, onProfile: ((String) -> Unit)? = null, full: Boolean = false,
    showActions: Boolean = true, repostedBy: String? = null, onQuote: ((JournalPost) -> Unit)? = null,
    onOpenPost: ((String) -> Unit)? = null) {
    val p = LocalChorusPalette.current
    // A repost has no text: show the original (one level only) under "X reposted", or say it's gone.
    if (post.repostOf != null && repostedBy == null) {
        val by = post.authors.mapNotNull { model.member(it)?.shownName }.joinToString(" & ").ifBlank { "Someone" }
        val original = model.posts.find { it.id == post.repostOf }?.takeIf { it.repostOf == null }
        if (original == null) RepostUnavailable(by)
        else JournalPostCard(original, model, chorus, onReply, onThread, onProfile, full, showActions, repostedBy = by, onQuote = onQuote, onOpenPost = onOpenPost)
        return
    }
    if (repostedBy != null) RepostHeader(repostedBy)
    var revealed by rememberSaveable(post.id) { mutableStateOf(false) }
    var expanded by rememberSaveable(post.id) { mutableStateOf(full) }
    var overflow by remember(post.id) { mutableStateOf(false) }
    var authorsOpen by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val names = post.authors.mapNotNull { model.member(it)?.shownName }.joinToString(" & ").ifBlank { "Someone" }
    val open = onThread?.takeIf { !full }?.let { { it(post) } }
    JournalPostFrame(names, compactAgo(post.occurredAt), post.visibility, full, avatar = {
        Box {
            Box(Modifier.size(48.dp).clickable(enabled = onProfile != null) {
                if (post.authors.size > 1) authorsOpen = true else post.authors.firstOrNull()?.let { onProfile?.invoke(it) }
            }, contentAlignment = Alignment.TopCenter) {
                post.authors.firstNotNullOfOrNull(model::member)?.let { ChatAvatar(it, 40.dp) }
            }
            DropdownMenu(authorsOpen, { authorsOpen = false }) {
                for (id in post.authors) model.member(id)?.let { member -> DropdownMenuItem(text = { Text(member.shownName) },
                    onClick = { authorsOpen = false; onProfile?.invoke(id) }) }
            }
        }
    }, overflow = {
        JournalOverflow("More options for this post", listOfNotNull(
            open?.let { "Open post" to it },
            JournalSocial.shareText(post.title, post.cw, post.text).takeIf { it.isNotBlank() }?.let { text ->
                "Copy text" to { clipboard.setText(androidx.compose.ui.text.AnnotatedString(text)) }
            }))
    }, onClick = open) {
        if (post.replyTo != null) Text("Reply", color = p.ink3, style = MaterialTheme.typography.bodySmall)
        if (post.cw != null) Surface(color = p.surface2, shape = RoundedCornerShape(10.dp)) {
            Row(Modifier.fillMaxWidth().clickable { revealed = !revealed }.heightIn(min = 48.dp).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Content warning: ${post.cw}", color = p.ink2, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text(if (revealed) "Hide" else "Show", color = p.accent, style = MaterialTheme.typography.labelMedium)
            }
        }
        if (post.cw == null || revealed) {
            post.title?.let { Text(it, color = p.ink, style = MaterialTheme.typography.titleSmall.copy(
                fontSize = if (full) 24.sp else 16.sp, lineHeight = if (full) 30.sp else 22.sp), fontWeight = FontWeight.Bold) }
            RichBody(post.text, post.entities, maxLines = if (expanded || full) Int.MAX_VALUE else 3,
                onOverflow = { overflow = it }, onPlainClick = { open?.invoke() }, style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = if (full) 16.sp else 15.sp, lineHeight = if (full) 24.sp else 21.sp))
            if (!full && (overflow || expanded)) Text(if (expanded) "Show less" else "Show more", color = p.accent,
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.clickable {
                    if (post.kind == "entry" && onThread != null) onThread(post) else expanded = !expanded
                }.heightIn(min = 32.dp).wrapContentHeight(Alignment.CenterVertically))
            if (post.kind == "entry") Text(JournalPresentation.entryMeta(post.text, post.tags), color = p.ink3,
                style = MaterialTheme.typography.bodySmall)
            post.mood?.let { Text(it, color = p.ink2, style = MaterialTheme.typography.bodySmall) }
            if (post.kind != "entry" && post.tags.isNotEmpty()) Text(post.tags.joinToString("  ") { "#$it" }, color = p.accent,
                style = MaterialTheme.typography.bodySmall)
            for (attachment in post.attachments) ChatAttachmentView(attachment, chorus)
            PollCard(chorus, model, post.id, Reply.speaker(model)?.id)
            post.quotePostId?.let { id -> QuoteEmbed(model.posts.find { it.id == id }?.takeIf { it.repostOf == null }, model,
                onOpen = onOpenPost?.let { open -> { open(id) } }) }
            if (showActions) JournalPostActions(post, model, chorus, onReply = { (onThread ?: onReply)(post) }, onQuote = onQuote)
        }
    }
    if (!full) HorizontalDivider(color = p.line, thickness = .5.dp)
}

/** Server replies can come from other accounts, while own replies remain available offline. */
@Composable
internal fun JournalThread(chorus: Chorus, model: Model, postId: String, onClose: () -> Unit, onReply: () -> Unit) {
    val p = LocalChorusPalette.current
    val actions = rememberCoroutineScope()
    var remote by remember(postId) { mutableStateOf<PostDetail?>(null) }
    var error by remember(postId) { mutableStateOf<String?>(null) }
    var refresh by remember(postId) { mutableStateOf(0) }
    var reactionError by remember(postId) { mutableStateOf<String?>(null) }
    var reactionBusy by remember(postId) { mutableStateOf(false) }
    val local = PostThreads.ownReplies(model, postId)
    LaunchedEffect(postId, chorus.device?.session, refresh) {
        val dev = chorus.device ?: return@LaunchedEffect
        try {
            remote = PostThreads.load(dev, postId)
            error = null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) {
            remote = null
            error = "Showing entries and replies saved on this device. Connect and refresh to load more."
        }
    }
    val replies = PostThreads.merge(local, remote?.replies.orEmpty())
    val reactions = PostReactions.merge(model.postReactions[postId].orEmpty(), remote?.reactions.orEmpty())
    val reactMember = Reply.speaker(model)?.takeIf { it.createdByAccountId == null || it.createdByAccountId == chorus.device?.accountId }
    val scroll = androidx.compose.foundation.lazy.rememberLazyListState()
    Column(Modifier.fillMaxSize().background(p.bg)) {
        Row(Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ChatIconButton(R.drawable.ic_back, "Back to journal", onClose)
            Text(if (model.posts.find { it.id == postId }?.kind == "entry") "Journal entry" else "Post and replies", color = p.ink,
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            ChatIconButton(R.drawable.ic_swap, "Refresh replies") { refresh++ }
        }
        LazyColumn(Modifier.weight(1f), state = scroll, contentPadding = PaddingValues(bottom = 16.dp)) {
            model.posts.find { it.id == postId }?.let { root -> item(key = "root:$postId") {
                JournalPostCard(root, model, chorus, onReply = { onReply() }, full = true, showActions = false)
            } } ?: remote?.root?.let { root -> item(key = "root:$postId") {
                ThreadReplyCard(root, chorus, model, reactMember?.id, full = true)
            } }
            if (error != null) item { Text(error.orEmpty(), color = p.ink2, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(20.dp)) }
            if (reactionError != null) item { Text(reactionError.orEmpty(), color = p.danger, modifier = Modifier.padding(20.dp)) }
            if (replies.isEmpty()) item { Text(if (remote == null && error == null) "Loading replies…" else "No replies yet.",
                color = p.ink2, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(20.dp)) }
            items(replies, key = { it.id }) { reply -> ThreadReplyCard(reply, chorus, model, reactMember?.id) }
        }
        HorizontalDivider(color = p.line)
        Box(Modifier.background(p.surface).padding(horizontal = 20.dp)) {
            JournalActions(reactions, reactMember, !reactionBusy, onReply = onReply, onReact = reactMember?.let { member -> {
                val selected = reactions.any { it.emoji == PostReactions.HEART && it.memberId == member.id }
                if (!reactionBusy) {
                    reactionBusy = true; reactionError = null
                    actions.launch {
                        try {
                            chorus.create(if (selected) "post.unreact" else "post.react", postId, PostReactions.payload(postId, member.id))
                            remote = remote?.copy(reactions = PostReactions.toggle(remote?.reactions.orEmpty(), member.id, member.shownName))
                        } catch (e: Exception) { reactionError = "Could not save the reaction. Try again." }
                        finally { reactionBusy = false }
                    }
                }
            } })
        }
    }
}

@Composable
private fun ThreadReplyCard(reply: ThreadReply, chorus: Chorus, model: Model, member: String?, full: Boolean = false) {
    val p = LocalChorusPalette.current
    var revealed by rememberSaveable(reply.id) { mutableStateOf(false) }
    val names = reply.authorNames.joinToString(" & ").ifBlank { "Someone" }
    JournalPostFrame(names, compactAgo(reply.occurredAt), null, full, avatar = {
        Box(Modifier.size(40.dp).background(p.surface3, CircleShape), contentAlignment = Alignment.Center) {
            Text(names.take(1), style = MaterialTheme.typography.titleSmall, color = p.ink2)
        }
    }) {
        if (reply.cw != null) TextButton(onClick = { revealed = !revealed }) {
            Text("Content warning: ${reply.cw} · ${if (revealed) "Hide" else "Show"}")
        }
        if (reply.cw == null || revealed) {
            if (reply.title != null) Text(reply.title, color = p.ink, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge)
            RichBody(reply.text, reply.entities, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp))
            PollCard(chorus, model, reply.id, member, reply.poll, reply.pollTally)
        }
    }
}
