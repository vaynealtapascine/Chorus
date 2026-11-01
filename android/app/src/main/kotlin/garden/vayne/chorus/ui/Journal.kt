package garden.vayne.chorus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.draw.clip
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
import garden.vayne.chorus.data.Member
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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
    // the threads under the open one, newline-separated, so back from a reply's thread returns to its parent
    var threadBack by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(threadPostId) { if (threadPostId == null) threadBack = "" }
    val threadStack = threadBack.split('\n').filter { it.isNotEmpty() }
    var section by rememberSaveable { mutableStateOf("timeline") }
    var selectedListId by rememberSaveable { mutableStateOf("") }
    var replyTo by rememberSaveable { mutableStateOf<String?>(null) }
    var quoteOf by rememberSaveable { mutableStateOf<String?>(null) }
    var kind by rememberSaveable { mutableStateOf("note") }
    var selectedAuthors by rememberSaveable(chorus.device?.accountId, model.isPerson) { mutableStateOf<List<String>?>(null) }
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
    var bucketIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
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
        selectedAuthors = null; profileId = null; threadPostId = null; replyTo = null
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
        selectedAuthors = null; profileId = null; threadPostId = null; replyTo = null
        section = "timeline"; kind = "entry"; editing = true
        onExternalNewEntryConsumed()
    }

    LaunchedEffect(externalReplyPost) {
        if (externalReplyPost != null) {
            profileId = null; threadPostId = null
            section = "timeline"
            replyTo = externalReplyPost; quoteOf = null
            kind = "note"; title = ""; bodyValue = TextFieldValue(); cw = ""; mood = ""; tags = ""
            attachments.clear(); selectedAuthors = null
            audience = "server" // the foreign parent author must be able to read this reply
            selectedAuthors = null; authorId = Reply.speaker(model)?.id.orEmpty()
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
    // our own newest post: shown at once instead of slipping in above the first visible card
    var justPosted by remember { mutableStateOf<String?>(null) }
    // which tab and newest post the feed last showed; kept outside the scene so returning from the
    // composer or a post still notices what arrived meanwhile
    var announced by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var newPosts by remember { mutableStateOf(false) }
    // read before the list measures an inserted post, so it says where the reader was
    val atTop by remember { androidx.compose.runtime.derivedStateOf {
        feedListState.firstVisibleItemIndex == 0 && feedListState.firstVisibleItemScrollOffset < 48 } }
    val route = when {
        editing -> "write"
        profileId != null -> "profile:$profileId"
        threadPostId != null -> "thread:$threadPostId"
        else -> section
    }
    androidx.compose.runtime.SideEffect { if (active) onFocusedRoute(editing || profileId != null || threadPostId != null) }
    val threadDepth = if (threadPostId != null) 1 + threadStack.size else 0
    ChorusScene(route, if (editing) maxOf(2, threadDepth + 1) else if (profileId != null) maxOf(1, threadDepth + 1)
        else if (threadPostId != null) threadDepth else if (section != "timeline") 1 else 0) { shown ->
    if (shown.startsWith("profile:")) {
        val id = shown.substringAfter(":")
        BackHandler(active && shown == route) { profileId = null }
        MemberProfile(chorus, model, id, onClose = { profileId = null },
            onWrite = { selectedAuthors = null; authorId = id; replyTo = null; editing = true },
            onReply = { postId -> selectedAuthors = null; authorId = id; replyTo = postId; editing = true }, onOpenProfile = { profileId = it })
        return@ChorusScene
    }

    if (shown.startsWith("thread:")) {
        val id = shown.substringAfter(":")
        val back = {
            threadPostId = threadStack.lastOrNull()
            threadBack = threadStack.dropLast(1).joinToString("\n")
        }
        BackHandler(active && shown == route) { back() }
        JournalThread(chorus, model, id, onClose = back, onReply = { selectedAuthors = null; replyTo = id; editing = true },
            replyAuthor = author, replyAuthors = mine, onPickAuthor = { selectedAuthors = null; authorId = it },
            onQuote = { selectedAuthors = null; quoteOf = it.id; replyTo = null; kind = "note"; editing = true },
            onOpenPost = { next -> threadBack = (threadStack + id).joinToString("\n"); threadPostId = next })
        return@ChorusScene
    }

    if (shown == "write") {
        BackHandler(active && shown == route) { if (!busy) editing = false }
        val bodyFocus = remember { FocusRequester() }
        // keep the line being typed above the keyboard and toolbar: after each edit (once the field has
        // laid out the new text), ask the list to show the caret with a line of air below it
        val caretView = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
        var bodyLayout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
        var bodyFocused by remember { mutableStateOf(false) }
        val air = with(androidx.compose.ui.platform.LocalDensity.current) { 32.dp.toPx() }
        LaunchedEffect(bodyValue.text, bodyValue.selection) {
            if (!bodyFocused) return@LaunchedEffect
            androidx.compose.runtime.withFrameNanos { }
            val layout = bodyLayout ?: return@LaunchedEffect
            val caret = layout.getCursorRect(bodyValue.selection.end.coerceIn(0, layout.layoutInput.text.length))
            caretView.bringIntoView(caret.copy(top = caret.top - air / 2, bottom = caret.bottom + air))
        }
        // open ready to type, like other apps' composers: focus the empty body and bring up the keyboard
        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) {
            if (body.isNotEmpty() || kind == "entry") return@LaunchedEffect
            // the field is a lazy item: wait for it to attach (and the scene to settle) before focusing
            repeat(20) {
                androidx.compose.runtime.withFrameNanos { }
                if (runCatching { bodyFocus.requestFocus() }.isSuccess) { keyboard?.show(); return@LaunchedEffect }
            }
        }
        Column(Modifier.fillMaxSize().background(p.bg).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(enabled = !busy, onClick = { editing = false }) { Text("Cancel") }
                Text(if (replyTo == null) "Draft" else "Write a reply", color = p.ink3,
                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 14.dp))
                Button(enabled = !busy && author != null && (model.isPerson || selectedAuthors?.isEmpty() != true) && (body.isNotBlank() || attachments.isNotEmpty()), onClick = {
                    val dev = chorus.device ?: return@Button
                    val writer = author ?: return@Button
                    val writingAuthors = garden.vayne.chorus.data.JournalSelection.authors(model.isPerson, selectedAuthors, writer.id)
                    val picked = attachments.toList()
                    // Snapshot the privacy/authoring choices at Post, before file staging suspends.
                    val writingKind = kind
                    val writingBody = body
                    val writingTitle = title
                    val writingCw = cw
                    val writingAudience = audience
                    val writingBuckets = bucketIds
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
                            require(chorus.device?.accountId == dev.accountId) { "Account changed. Please try again." }
                            PostCompose.validatedAuthors(chorus.model.value, dev.accountId, writingAuthors)
                            PostCompose.validatedAudience(garden.vayne.chorus.data.Audience(writingAudience, writingBuckets), chorus.model.value.sharingBuckets.map { it.first }.toSet())
                            val attachmentIds = picked.map { a ->
                                sendAttachment(chorus, ctx, a, opKind = "post.attachment")
                            }
                            require(chorus.device?.accountId == dev.accountId) { "Account changed. Please try again." }
                            val payload = PostCompose.payload(chorus.model.value, dev.accountId, writingKind, writer.id, writingBody,
                                writingTitle, writingCw, writingAudience, writingMood, writingTags, writingReply, attachmentIds = attachmentIds, quotePostId = writingQuote, bucketIds = writingBuckets, authorIds = writingAuthors)
                            if (writingPoll != null) payload.put("poll", writingPoll)
                            val postId = chorus.newId()
                            justPosted = postId
                            chorus.create("post.create", postId, payload)
                            pollOn = false; pollMultiple = false
                            pollOptions.clear(); pollOptions.addAll(listOf("", ""))
                            if (attachmentIds.isNotEmpty()) UploadWork.enqueue(ctx)
                            bodyValue = TextFieldValue(); title = ""; cw = ""; mood = ""; tags = ""; replyTo = null; quoteOf = null
                            attachments.clear(); selectedAuthors = null
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
            // once the draft scrolls under the header, a hairline says where it ends
            val draftList = androidx.compose.foundation.lazy.rememberLazyListState()
            HorizontalDivider(color = if (draftList.canScrollBackward) p.line else androidx.compose.ui.graphics.Color.Transparent,
                thickness = .5.dp)
            LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), state = draftList, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // the post being answered scrolls with the draft, so expanding a long one never hides it
                replyTo?.let { id -> item(key = "replying") { ReplyContext(model.posts.find { it.id == id }, model) } }
                item(key = "author") { if (!model.isPerson) JournalAuthorsPicker(selectedAuthors ?: listOfNotNull(author?.id), mine, !busy) { selectedAuthors = it } }
                if (replyTo != null) item {
                    Text("Check the audience before posting.", color = p.ink3, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 52.dp))
                }
                if (kind == "entry") item {
                    JournalTextField(title, { title = it }, "Title (optional)", MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
                        Modifier.padding(start = 52.dp), enabled = !busy)
                }
                item {
                    BasicTextField(bodyValue, { bodyValue = it }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().padding(start = 52.dp).focusRequester(bodyFocus)
                            .bringIntoViewRequester(caretView)
                            .onFocusChanged { bodyFocused = it.isFocused }
                            .semantics { contentDescription = "Post text" },
                        onTextLayout = { bodyLayout = it },
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
                AudienceChip("This post", garden.vayne.chorus.data.Audience(audience, bucketIds), own = true, buckets = model.sharingBuckets, person = model.isPerson, enabled = !busy, onChange = { value ->
                    value?.let { audience = it.mode; bucketIds = it.buckets }
                })
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
            onReply = { selectedAuthors = null; replyTo = it; editing = true },
            onThread = { threadPostId = it })
        return@ChorusScene
    }
    if (shown == "feeds") {
        JournalFeeds(chorus, model, onTimeline = { section = "timeline" })
        return@ChorusScene
    }
    if (shown == "saved") {
        BackHandler(active && shown == route) { section = "timeline" }
        JournalSaved(chorus, model, onBack = { section = "timeline" }, onReply = { selectedAuthors = null; replyTo = it; editing = true },
            onThread = { threadPostId = it }, onQuote = { selectedAuthors = null; quoteOf = it.id; replyTo = null; kind = "note"; editing = true },
            onProfile = { profileId = it })
        return@ChorusScene
    }

    // a configured account reads Body's effective changes (exact Fronting layer ID), never the stale log
    val frontRevision by chorus.frontHistoryRevision.collectAsState()
    val named by produceState<NamedHistory?>(null, frontRevision, model.namedFronts != null) {
        value = if (model.namedFronts != null) runCatching { chorus.frontHistory() }.getOrNull() else null
    }
    fun fronting(entries: List<Entry>) = entries.filter { e -> e.level == "front" }
        .mapNotNull { e -> model.subject(e.subjectType, e.subjectId)?.name }.joinToString(" & ")
        .let { if (it.isBlank()) "No one fronting" else "$it fronting" }
    val switchEvents = if (model.namedFronts != null)
        named?.changes.orEmpty().map { JournalEvent(it.id, it.occurredAt, null, fronting(it.entries.map { e -> e.displayEntry() })) }
    else model.switches.map { JournalEvent(it.id, it.occurredAt, null, if (it.retracted) "Undone switch" else fronting(it.resultingFront)) }
    val events = (model.posts.map { JournalEvent(it.id, it.occurredAt, it, null) } + switchEvents)
        .sortedWith(compareByDescending<JournalEvent> { it.at }.thenByDescending { it.id })
    val shownEvents = events.filter { journalTab != "entries" || it.post?.kind == "entry" }
    // New posts arriving (from another device) while the reader is scrolled down offer a way back to the top.
    val motion = rememberChorusMotion()
    val newestPost = shownEvents.firstOrNull { it.post != null }?.id
    val wasAtTop = atTop
    LaunchedEffect(journalTab, newestPost) {
        val before = announced
        announced = journalTab to newestPost
        if (before == null || before.first != journalTab || before.second == newestPost) return@LaunchedEffect
        if (newestPost == justPosted || wasAtTop) {
            newPosts = false
            if (motion) feedListState.animateScrollToItem(0) else feedListState.scrollToItem(0)
            justPosted = null
        } else newPosts = true
    }
    LaunchedEffect(feedListState.firstVisibleItemIndex) { if (feedListState.firstVisibleItemIndex == 0) newPosts = false }
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
            selectedAuthors = null; replyTo = it; audience = "server"; editing = true
        }) else LazyColumn(Modifier.fillMaxSize(), state = feedListState, contentPadding = PaddingValues(bottom = 80.dp)) {
        if (shownEvents.isEmpty()) item {
            Text(if (journalTab == "entries") "No journal entries yet. Start with a few words." else "No posts or switches yet. Write the first note.",
                color = p.ink2, modifier = Modifier.padding(24.dp))
        }
        for (event in shownEvents) item(key = "${if (event.post == null) "switch" else "post"}:${event.id}") {
            Column(Modifier.animateItem(fadeInSpec = if (motion) ChorusMotion.tween(true, ChorusMotion.Gentle) else null,
                placementSpec = if (motion) ChorusMotion.settle() else null,
                fadeOutSpec = if (motion) ChorusMotion.tween(true, ChorusMotion.Quick) else null)) {
            if (event.post != null) JournalPostCard(event.post, model, chorus,
                onReply = { selectedAuthors = null; replyTo = it.id; editing = true },
                onThread = { threadPostId = it.id },
                onQuote = { selectedAuthors = null; quoteOf = it.id; replyTo = null; kind = "note"; editing = true },
                onOpenPost = { threadPostId = it },
                onProfile = { profileId = it })
            // a switch is a quiet marker in the timeline, aligned with the post avatars
            else Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) { ChatIcon(R.drawable.ic_swap, tint = p.ink3) }
                Text(event.switchLabel.orEmpty() + " · " + compactAgo(event.at), color = p.ink3,
                    style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            }
        }
        }
        NewPostsPill(newPosts && journalTab != "friends", motion, Modifier.align(Alignment.TopCenter).padding(top = 8.dp)) {
            actions.launch {
                if (motion) feedListState.animateScrollToItem(0) else feedListState.scrollToItem(0)
                newPosts = false
            }
        }
        FloatingActionButton(onClick = { selectedAuthors = null; replyTo = null; if (journalTab == "entries") kind = "entry"; editing = true },
            containerColor = p.accent, contentColor = p.onAccent, shape = CircleShape,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).size(56.dp)) {
            ChatIcon(R.drawable.ic_edit, "Write a post", p.onAccent)
        }
        }
    }
    }
}

/** "↑ New posts": slides in at the top when posts arrive while the reader is further down. */
@Composable
private fun NewPostsPill(visible: Boolean, motion: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    AnimatedVisibility(visible, modifier,
        enter = fadeIn(ChorusMotion.tween(motion)) + slideInVertically(ChorusMotion.tween(motion)) { -it },
        exit = fadeOut(ChorusMotion.tween(motion, ChorusMotion.Quick)) + slideOutVertically(ChorusMotion.tween(motion, ChorusMotion.Quick)) { -it }) {
        Surface(onClick = onClick, color = p.accent, contentColor = p.onAccent, shape = CircleShape, shadowElevation = 6.dp) {
            Text("↑ New posts", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
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

/** One feed card: an optional "X reposted" line, the post and its divider, stacked in one column
 * (lazy list items overlap sibling roots, so the card owns its layout). */
@Composable
internal fun JournalPostCard(post: JournalPost, model: Model, chorus: Chorus, onReply: (JournalPost) -> Unit,
    onThread: ((JournalPost) -> Unit)? = null, onProfile: ((String) -> Unit)? = null, full: Boolean = false,
    showActions: Boolean = true, repostedBy: String? = null, onQuote: ((JournalPost) -> Unit)? = null,
    onOpenPost: ((String) -> Unit)? = null, extraMenu: List<Pair<String, () -> Unit>> = emptyList(),
    lineAbove: Boolean = false, inThread: Boolean = false, quoted: ThreadReply? = null) {
    Column(Modifier.fillMaxWidth()) {
        JournalPostCardBody(post, model, chorus, onReply, onThread, onProfile, full, showActions, repostedBy, onQuote, onOpenPost, extraMenu,
            lineAbove, inThread, quoted)
    }
}

@Composable
private fun JournalPostCardBody(post: JournalPost, model: Model, chorus: Chorus, onReply: (JournalPost) -> Unit,
    onThread: ((JournalPost) -> Unit)?, onProfile: ((String) -> Unit)?, full: Boolean,
    showActions: Boolean, repostedBy: String?, onQuote: ((JournalPost) -> Unit)?,
    onOpenPost: ((String) -> Unit)?, extraMenu: List<Pair<String, () -> Unit>> = emptyList(),
    lineAbove: Boolean = false, inThread: Boolean = false, quoted: ThreadReply? = null) {
    val p = LocalChorusPalette.current
    // A repost has no text: show the original (one level only) under "X reposted", or say it's gone.
    if (post.repostOf != null && repostedBy == null) {
        val by = post.authors.mapNotNull { model.member(it)?.shownName }.joinToString(" & ").ifBlank { "Someone" }
        val original = model.posts.find { it.id == post.repostOf }?.takeIf { it.repostOf == null }
        if (original == null) RepostUnavailable(by)
        else JournalPostCardBody(original, model, chorus, onReply, onThread, onProfile, full, showActions, by, onQuote, onOpenPost, extraMenu,
            lineAbove, inThread)
        return
    }
    if (repostedBy != null) RepostHeader(repostedBy)
    var revealed by rememberSaveable(post.id) { mutableStateOf(false) }
    var expanded by rememberSaveable(post.id) { mutableStateOf(full) }
    var overflow by remember(post.id) { mutableStateOf(false) }
    var authorsOpen by remember { mutableStateOf(false) }
    var audienceOpen by remember(post.id) { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val names = post.authors.mapNotNull { model.member(it)?.shownName }.joinToString(" & ").ifBlank { "Someone" }
    val open = onThread?.takeIf { !full }?.let { { it(post) } }
    JournalPostFrame(names, compactAgo(post.occurredAt), post.visibility, full, endsWithActions = showActions && !full &&
        (post.cw == null || revealed), lineAbove = lineAbove, avatar = {
        Box {
            Box(Modifier.size(if (full) 48.dp else 40.dp).clip(CircleShape).clickable(enabled = onProfile != null) {
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
            },
            ("Change audience" to { audienceOpen = true }).takeIf { JournalSocial.canEditAudience(post, model, chorus.device?.accountId) }) + extraMenu)
    }, onClick = open) {
        // in a feed, a reply says whom it answers (tap to read that); a thread already shows it above
        if (post.replyTo != null && !full && !inThread) {
            val parent = model.posts.find { it.id == post.replyTo }
            val to = parent?.authors?.mapNotNull { model.member(it)?.shownName }?.joinToString(" & ")?.takeIf { it.isNotBlank() }
            Text(if (to != null) "Replying to $to" else "Replying to a post", color = p.ink3, style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = if (onOpenPost == null) Modifier
                    else Modifier.clickable(onClickLabel = "Open the post this replies to") { onOpenPost(post.replyTo) })
        }
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
            JournalMedia(post.attachments, chorus, names, post.occurredAt)
            PollCard(chorus, model, post.id, Reply.speaker(model)?.id)
            post.quotePostId?.let { id -> ThreadQuote(id, quoted, model, onOpenPost) }
            if (showActions) JournalPostActions(post, model, chorus, onReply = { (onThread ?: onReply)(post) }, onQuote = onQuote)
        }
    }
    if (audienceOpen) JournalAudienceDialog(post, model, chorus, onDismiss = { audienceOpen = false })
    if (!full) HorizontalDivider(color = p.line, thickness = .5.dp)
}

/** A post as a thread, the way Twitter reads one: what it answers sits above it (oldest first, one
 * avatar line running down into the focused post), then the focused post, larger, with when it was
 * written, its counts and a large action row, then its replies. Each reply opens as a thread of its
 * own ("N replies" says when it has more), and back returns here. Server replies can come from
 * other accounts, while own replies and own parents remain available offline. The reply bar stays
 * pinned and opens the existing composer, so the audience is still chosen there. [replyAuthors] is
 * who may write here (one member for a person account, so no picker is shown). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun JournalThread(chorus: Chorus, model: Model, postId: String, onClose: () -> Unit, onReply: () -> Unit,
    replyAuthor: Member? = null, replyAuthors: List<Member> = emptyList(), onPickAuthor: ((String) -> Unit)? = null,
    onQuote: ((JournalPost) -> Unit)? = null, onOpenPost: ((String) -> Unit)? = null) {
    val p = LocalChorusPalette.current
    val motion = rememberChorusMotion()
    var remote by remember(postId) { mutableStateOf<PostDetail?>(null) }
    var error by remember(postId) { mutableStateOf<String?>(null) }
    var refresh by remember(postId) { mutableStateOf(0) }
    var refreshing by remember(postId) { mutableStateOf(false) }
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
        } finally { refreshing = false }
    }
    val replies = PostThreads.merge(local, remote?.replies.orEmpty())
    val quotes = PostThreads.merge(PostThreads.ownQuotes(model, postId), remote?.quotes.orEmpty())
    // Replies or Quotes under the post, like Twitter's tabs; quotes only once there are some
    var showQuotes by rememberSaveable(postId) { mutableStateOf(false) }
    val listed = if (showQuotes && quotes.isNotEmpty()) quotes else replies
    val localAncestors = PostThreads.localAncestors(model, postId)
    // the server's chain reaches other accounts' posts; offline, own parents still give context
    val ancestors = remote?.ancestors?.takeIf { it.isNotEmpty() } ?: localAncestors
    val reactions = PostReactions.merge(model.postReactions[postId].orEmpty(), remote?.reactions.orEmpty())
    val rootPost = model.posts.find { it.id == postId }
    val foreignRoot = remote?.root?.takeIf { rootPost == null }
    // a post from another account has no local row: describe it with what the server sent
    val focused = rootPost ?: foreignRoot?.let { JournalPost(it.id, "note", emptyList(), it.title, it.text, it.occurredAt,
        it.cw, "server", null, null, emptyList()) }
    val open: (String) -> Unit = { id -> if (id != postId) onOpenPost?.invoke(id) }
    // opens on the focused post, its context above; parents arriving later keep it in place (keyed)
    val scroll = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = localAncestors.size)
    Column(Modifier.fillMaxSize().background(p.bg).imePadding()) {
        Row(Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ChatIconButton(R.drawable.ic_back, "Back", onClose)
            Text(if (rootPost?.kind == "entry") "Journal entry" else if (ancestors.isNotEmpty()) "Thread" else "Post",
                color = p.ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        }
        // pull down to fetch replies again; own replies are already local
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(refreshing, { refreshing = true; refresh++ }, Modifier.weight(1f)) {
        LazyColumn(Modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(bottom = 16.dp)) {
            itemsIndexed(ancestors, key = { _, it -> "up:${it.id}" }) { i, parent ->
                Column(Modifier.animateItem(fadeInSpec = if (motion) ChorusMotion.tween(true, ChorusMotion.Gentle) else null,
                    placementSpec = if (motion) ChorusMotion.settle() else null, fadeOutSpec = null)) {
                    ThreadReplyCard(parent, chorus, model, replyAuthor?.id, onOpen = { open(parent.id) },
                        lineAbove = i > 0, lineBelow = true, wide = true, divider = false, onOpenPost = open)
                }
            }
            if (rootPost != null) item(key = "root:$postId") {
                JournalPostCard(rootPost, model, chorus, onReply = { onReply() }, full = true, showActions = false,
                    onOpenPost = onOpenPost, lineAbove = ancestors.isNotEmpty(), quoted = remote?.root?.quoted)
            } else if (foreignRoot != null) item(key = "root:$postId") {
                ThreadReplyCard(foreignRoot, chorus, model, Reply.speaker(model)?.id, full = true, lineAbove = ancestors.isNotEmpty(),
                    onOpenPost = open)
            }
            if (focused != null) item(key = "detail:$postId") {
                val hearts = reactions.count { it.emoji == PostReactions.HEART }
                val reposts = JournalSocial.reposts(model.posts, postId).size
                val at = Date(focused.occurredAt)
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    Text(listOfNotNull(DateFormat.getTimeInstance(DateFormat.SHORT).format(at),
                        DateFormat.getDateInstance(DateFormat.MEDIUM).format(at),
                        JournalPresentation.audience(focused.visibility, model.isPerson).takeIf { rootPost != null },
                        // own posts only: who was here when it was written (Body; not for configured layers)
                        rootPost?.takeIf { model.namedFronts == null }?.let { post ->
                            JournalPresentation.frontContext(model.frontIntervals, post.occurredAt) { model.member(it)?.shownName }
                        }).joinToString(" · "),
                        color = p.ink3, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 12.dp))
                    if (hearts > 0 || reposts > 0 || replies.isNotEmpty() || quotes.isNotEmpty()) {
                        HorizontalDivider(color = p.line, thickness = .5.dp)
                        Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (replies.isNotEmpty()) DetailStat(replies.size, if (replies.size == 1) "reply" else "replies")
                            if (reposts > 0) DetailStat(reposts, if (reposts == 1) "repost" else "reposts")
                            // tap to list them under the post
                            if (quotes.isNotEmpty()) DetailStat(quotes.size, if (quotes.size == 1) "quote" else "quotes",
                                onClick = { showQuotes = true })
                            if (hearts > 0) DetailStat(hearts, if (hearts == 1) "like" else "likes")
                        }
                    }
                    HorizontalDivider(color = p.line, thickness = .5.dp)
                    JournalPostActions(focused, model, chorus, onReply = onReply, onQuote = onQuote, large = true,
                        reactions = reactions, foreign = rootPost == null, onReacted = { member ->
                            remote = remote?.copy(reactions = PostReactions.toggle(remote?.reactions.orEmpty(), member.id, member.shownName))
                        })
                    HorizontalDivider(color = p.line, thickness = .5.dp)
                }
            }
            if (error != null) item(key = "error") { Text(error.orEmpty(), color = p.ink2, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(20.dp)) }
            if (quotes.isNotEmpty()) item(key = "tabs") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(Modifier.background(p.surface2, CircleShape)) {
                        JournalModeChoice(if (replies.isEmpty()) "Replies" else "Replies · ${replies.size}", !showQuotes) { showQuotes = false }
                        JournalModeChoice("Quotes · ${quotes.size}", showQuotes) { showQuotes = true }
                    }
                }
            }
            if (listed.isEmpty()) item(key = "empty") { Text(if (remote == null && error == null) "Loading replies…" else "No replies yet.",
                color = p.ink2, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(20.dp)) }
            items(listed, key = { (if (listed === quotes) "quote:" else "reply:") + it.id }) { reply ->
                Column(Modifier.animateItem(fadeInSpec = if (motion) ChorusMotion.tween(true, ChorusMotion.Gentle) else null,
                    placementSpec = if (motion) ChorusMotion.settle() else null, fadeOutSpec = null)) {
                    // own replies keep the whole action row; others' open as their own thread
                    val own = model.posts.find { it.id == reply.id }
                    if (own != null) JournalPostCard(own, model, chorus, onReply = { open(it.id) }, onThread = { open(it.id) },
                        onQuote = onQuote, onOpenPost = open, inThread = listed === replies, quoted = reply.quoted)
                    else ThreadReplyCard(reply, chorus, model, replyAuthor?.id, onOpen = { open(reply.id) }, actions = true, onOpenPost = open)
                }
            }
        }
        }
        HorizontalDivider(color = p.line)
        JournalReplyBar(replyAuthor, replyAuthors, onPickAuthor, onReply)
    }
}

/** Above a reply draft: the post it answers, joined to the writer's avatar by the thread line. Long
 * posts start at three lines; tap to read the rest and tap again to fold it. A post from another
 * account isn't on this device, so it is only named. */
@Composable
private fun ReplyContext(parent: JournalPost?, model: Model) {
    val p = LocalChorusPalette.current
    val names = parent?.authors?.mapNotNull { model.member(it)?.shownName }?.joinToString(" & ")?.takeIf { it.isNotBlank() }
    if (parent == null || names == null) {
        Text("Replying to a post", color = p.ink3, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 6.dp))
        return
    }
    val motion = rememberChorusMotion()
    var expanded by rememberSaveable(parent.id) { mutableStateOf(false) }
    var overflows by remember(parent.id) { mutableStateOf(false) }
    val foldable = overflows || expanded
    Row(Modifier.fillMaxWidth().then(if (foldable) Modifier.clickable(
        onClickLabel = if (expanded) "Show less of the post" else "Show all of the post") { expanded = !expanded } else Modifier)
        .padding(top = 6.dp).drawBehind {
        // centred under the 40 dp avatar, down to the author picker's avatar below
        val x = 20.dp.toPx()
        drawLine(p.line, androidx.compose.ui.geometry.Offset(x, 44.dp.toPx()), androidx.compose.ui.geometry.Offset(x, size.height),
            2.dp.toPx())
    }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(40.dp)) { parent.authors.firstNotNullOfOrNull(model::member)?.let { ChatAvatar(it, 40.dp) } }
        Column(Modifier.weight(1f).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("$names · ${compactAgo(parent.occurredAt)}", color = p.ink2, style = MaterialTheme.typography.labelLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(parent.cw?.let { "Content warning: $it" } ?: listOfNotNull(parent.title, parent.text.takeIf { it.isNotBlank() })
                .joinToString(" — "), color = p.ink2, style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
                modifier = Modifier.animateContentSize(if (motion) ChorusMotion.settle() else androidx.compose.animation.core.snap()))
            if (foldable) Text(if (expanded) "Show less" else "Show more", color = p.accent, style = MaterialTheme.typography.labelLarge)
            Text("Replying to $names", color = p.ink3, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DetailStat(count: Int, label: String, onClick: (() -> Unit)? = null) {
    val p = LocalChorusPalette.current
    Row(if (onClick == null) Modifier else Modifier.clickable(onClickLabel = "Show the $label", onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(count.toString(), color = p.ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
        Text(label, color = p.ink3, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The pinned reply bar: who is replying (tap the chip to change), a rounded "Write your reply"
 * field and send. Both open the existing reply composer, where the audience is checked. */
@Composable
private fun JournalReplyBar(author: Member?, authors: List<Member>, onPick: ((String) -> Unit)?, onReply: () -> Unit) {
    val p = LocalChorusPalette.current
    var picking by remember { mutableStateOf(false) }
    val canPick = onPick != null && authors.size > 1
    Row(Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (author != null) Box {
            Row(Modifier.clip(CircleShape).then(if (canPick) Modifier.clickable(onClickLabel = "Change who is replying") { picking = true } else Modifier)
                .heightIn(min = 48.dp).padding(end = if (canPick) 4.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
                ChatAvatar(author, 32.dp, label = "Replying as ${author.shownName}")
                if (canPick) Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_chevron_down), null, Modifier.size(16.dp), tint = p.ink3)
            }
            DropdownMenu(picking, { picking = false }) {
                for (member in authors) DropdownMenuItem(text = { Text(member.shownName) }, leadingIcon = { ChatAvatar(member, 32.dp) },
                    onClick = { picking = false; onPick?.invoke(member.id) })
            }
        }
        Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(CircleShape).background(p.surface2)
            .clickable(onClickLabel = "Write a reply", onClick = onReply).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
            Text("Write your reply", color = p.ink3, style = MaterialTheme.typography.bodyMedium)
        }
        IconButton(onClick = onReply, modifier = Modifier.size(48.dp)) { ChatIcon(R.drawable.ic_send, "Write a reply", p.accent) }
    }
}

/** A post from the server's thread (another account's, or context above the focused post). One of our
 * members shows their own avatar; anyone else, their colour and initial. [actions] adds the action
 * row with the reply count, which opens the reply as its own thread. */
@Composable
private fun ThreadReplyCard(reply: ThreadReply, chorus: Chorus, model: Model, member: String?, full: Boolean = false,
    onOpen: (() -> Unit)? = null, lineAbove: Boolean = false, lineBelow: Boolean = false, wide: Boolean = false,
    divider: Boolean = !full, actions: Boolean = false, onOpenPost: ((String) -> Unit)? = null) {
    val p = LocalChorusPalette.current
    val ctx = LocalContext.current
    var revealed by rememberSaveable(reply.id) { mutableStateOf(false) }
    val names = reply.authorNames.joinToString(" & ").ifBlank { "Someone" }
    val shown = reply.cw == null || revealed
    JournalPostFrame(names, compactAgo(reply.occurredAt), null, full, onClick = onOpen, lineAbove = lineAbove,
        lineBelow = lineBelow, wide = wide, endsWithActions = actions && shown, avatar = {
        val own = reply.authorIds.firstNotNullOfOrNull(model::member)
        if (own != null) ChatAvatar(own, 40.dp)
        else Avatar(names.take(1).uppercase(), reply.color ?: "#42A397", 40.dp, avatarBlob = reply.avatarBlob, ring = false)
    }) {
        if (reply.cw != null) Surface(color = p.surface2, shape = RoundedCornerShape(10.dp)) {
            Row(Modifier.fillMaxWidth().clickable { revealed = !revealed }.heightIn(min = 48.dp).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Content warning: ${reply.cw}", color = p.ink2, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text(if (revealed) "Hide" else "Show", color = p.accent, style = MaterialTheme.typography.labelMedium)
            }
        }
        if (shown) {
            if (reply.title != null) Text(reply.title, color = p.ink, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = if (full) 24.sp else 16.sp, lineHeight = if (full) 30.sp else 22.sp))
            RichBody(reply.text, reply.entities, maxLines = if (full || onOpen == null) Int.MAX_VALUE else 8,
                onPlainClick = { onOpen?.invoke() }, style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = if (full) 16.sp else 15.sp, lineHeight = if (full) 24.sp else 21.sp))
            PollCard(chorus, model, reply.id, member, reply.poll, reply.pollTally)
            reply.quotePostId?.let { ThreadQuote(it, reply.quoted, model, onOpenPost) }
            if (actions) JournalActions(emptyList(), null, onReply = onOpen, replies = reply.replyCount,
                onShare = JournalSocial.shareText(reply.title, reply.cw, reply.text).takeIf { it.isNotBlank() }?.let { text ->
                    { shareJournalText(ctx, reply.title, text) }
                })
            // context above the focused post leads into it, so its own count would only repeat it
            else if (reply.replyCount > 0 && !full && !lineBelow && onOpen != null) Text(
                if (reply.replyCount == 1) "1 reply" else "${reply.replyCount} replies", color = p.accent,
                style = MaterialTheme.typography.labelMedium)
        }
    }
    if (divider) HorizontalDivider(color = p.line, thickness = .5.dp)
}
