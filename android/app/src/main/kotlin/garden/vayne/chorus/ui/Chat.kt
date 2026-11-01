package garden.vayne.chorus.ui

import garden.vayne.chorus.InspectionDialogContent

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import garden.vayne.chorus.data.Status
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Surface
import androidx.compose.material3.IconButton
import androidx.compose.ui.window.Dialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateListOf
import android.net.Uri
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import garden.vayne.chorus.data.Blobs
import garden.vayne.chorus.data.UploadWork
import org.json.JSONObject
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.platform.LocalConfiguration
import garden.vayne.chorus.data.ChatPresentation
import garden.vayne.chorus.designsystem.R
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.ChatMessage
import garden.vayne.chorus.data.MessageRevision
import garden.vayne.chorus.data.ChatSpeaker
import garden.vayne.chorus.data.SpeakerDefault
import garden.vayne.chorus.data.ChannelWindow
import garden.vayne.chorus.data.ChatAttachment
import garden.vayne.chorus.data.SearchDocument
import garden.vayne.chorus.data.ReadTracking
import garden.vayne.chorus.data.HeldMessage
import garden.vayne.chorus.data.PrivateReplies
import garden.vayne.chorus.data.ChatCompose
import garden.vayne.chorus.data.RichTextEdit
import garden.vayne.chorus.data.ChatEdit
import garden.vayne.chorus.data.ChatSpace
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.Polls
import garden.vayne.chorus.data.Entry
import garden.vayne.chorus.data.ForeignAuthor
import garden.vayne.chorus.data.SpaceAccount
import garden.vayne.chorus.data.SpaceInfo
import garden.vayne.chorus.data.Spaces
import garden.vayne.chorus.data.accountVisible
import garden.vayne.chorus.data.memberVisible
import garden.vayne.chorus.SharedDraft
import garden.vayne.chorus.designsystem.LocalChorusPalette
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import garden.vayne.chorus.data.ChatReaction
import garden.vayne.chorus.data.ChatReactions
import garden.vayne.chorus.data.ChatForward
import garden.vayne.chorus.data.AccountPrefs
import garden.vayne.chorus.data.ChatOrganize
import garden.vayne.chorus.data.ChatManage
import garden.vayne.chorus.data.ChatThreads
import garden.vayne.chorus.data.ThreadSummary
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.selected

private data class StageRows(val channelId: String, val accountId: String, val spaceKind: String,
    val viewingAs: String?, val front: List<Entry>, val window: ChannelWindow)
private data class PendingPrivateReply(val message: ChatMessage, val channelId: String? = null,
    val spaceId: String? = null, val speakerId: String? = null)
private data class ChatPaneSnapshot(val channel: garden.vayne.chorus.data.ChatChannel?, val space: ChatSpace?,
    val messages: List<ChatMessage>, val library: List<ChatMessage>, val parent: ChatMessage?,
    val parentChannel: garden.vayne.chorus.data.ChatChannel?, val reading: ChatReadingPosition)

/** Local chat view: internal channels, shared spaces and account DMs use the same projection. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Chat(chorus: Chorus, model: Model, requestedSpace: String? = null,
    requestedChannel: String? = null, searchHit: SearchDocument? = null,
    onStageCapture: (Boolean) -> Unit = {},
    onDismissSearchHit: () -> Unit = {}, sharedDraft: SharedDraft? = null,
    sharedChannel: String? = null, onShareConsumed: () -> Unit = {}, onSearch: () -> Unit = {},
    active: Boolean = true, onFocusedRoute: (Boolean) -> Unit = {}, onOpenConsumed: () -> Unit = {}) {
    val p = LocalChorusPalette.current
    val heldMessages by chorus.heldMessages.collectAsState()
    val syncStatus by chorus.status.collectAsState()
    var infoOpen by rememberSaveable { mutableStateOf(false) }
    var readerOpen by remember { mutableStateOf(false) }
    var conversationOpen by rememberSaveable { mutableStateOf(false) }
    var switchOpen by remember { mutableStateOf(false) }
    var headerMenu by remember { mutableStateOf(false) }
    // a message to bring into view (from chat info), consumed by the list
    var jumpTo by remember { mutableStateOf<String?>(null) }
    val expanded = LocalConfiguration.current.screenWidthDp >= 840
    var selectedSpace by rememberSaveable { mutableStateOf("") }
    var selectedChannel by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(requestedSpace, requestedChannel, searchHit?.id, sharedDraft?.id) {
        if (requestedSpace != null || requestedChannel != null || searchHit != null || sharedDraft != null) {
            if (requestedSpace != null) selectedSpace = requestedSpace
            if (requestedChannel != null) selectedChannel = requestedChannel
            else if (requestedSpace != null) selectedChannel = ""
            conversationOpen = true
            if (requestedSpace != null || requestedChannel != null) onOpenConsumed()
        }
    }
    val space = model.spaces.find { it.id == selectedSpace } ?: model.spaces.firstOrNull()
    val channels = model.channels.filter { it.spaceId == space?.id }
    // a space opened without a channel: its open one first (a DM's, not a private chat in it, D-098)
    val channel = channels.find { it.id == selectedChannel } ?: channels.firstOrNull { it.kind != "thread" && it.memberIds.isEmpty() }
        ?: channels.firstOrNull { it.kind != "thread" }
    val drafts = rememberDurableChatDrafts(chorus) ?: return
    val composer = drafts.getOrPut(channel?.id ?: selectedChannel) { ChatDraft() }
    var viewingAs by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedAuthor by composer.author
    var draft by composer.text
    var cw by composer.cw
    // F5.7: a thread reply can also land in the parent channel, as a reply to the thread's parent
    var alsoSend by rememberSaveable { mutableStateOf(false) }
    // an optional poll on the next message (D-081)
    var pollOn by composer.poll
    val pollOptions = composer.options
    var pollMultiple by composer.multiple
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    var speakerSettingsOpen by rememberSaveable { mutableStateOf(false) }
    var followNext by remember { mutableStateOf(false) }
    var audience by composer.audience
    var visibleTo by composer.visibleTo
    var replyTo by composer.replyTo
    var carriedReply by remember { mutableStateOf<ChatMessage?>(null) }
    var pendingPrivateReply by remember { mutableStateOf<PendingPrivateReply?>(null) }
    var editingMessageId by rememberSaveable { mutableStateOf<String?>(null) }
    var forwarding by remember { mutableStateOf<ChatMessage?>(null) }
    var forwardConfirm by remember { mutableStateOf<ChatListRow?>(null) }
    var deleting by remember { mutableStateOf<ChatMessage?>(null) }
    var undoDelete by remember { mutableStateOf<ChatMessage?>(null) }
    var notifyOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var directory by remember { mutableStateOf<Map<String, SpaceInfo>>(emptyMap()) }
    var connections by remember { mutableStateOf<List<SpaceAccount>>(emptyList()) }
    var foreignAuthors by remember { mutableStateOf<Map<String, ForeignAuthor>>(emptyMap()) }
    var refresh by remember { mutableStateOf(0) }
    var channelEditor by rememberSaveable { mutableStateOf(false) }
    var editChannel by rememberSaveable { mutableStateOf(false) }
    var spaceAction by rememberSaveable { mutableStateOf("") }
    var directRequest by remember { mutableStateOf<Job?>(null) }
    var pendingDmParticipants by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var pendingDmId by rememberSaveable { mutableStateOf("") }
    var newSpaceName by rememberSaveable { mutableStateOf("") }
    var invitedAccounts by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var staging by rememberSaveable { mutableStateOf(false) }
    var stageSelection by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    BackHandler(active && conversationOpen && !staging && !infoOpen) { conversationOpen = false }
    androidx.compose.runtime.SideEffect { if (active) onFocusedRoute(conversationOpen || infoOpen || staging) }
    var capturing by rememberSaveable { mutableStateOf(false) }
    var stageLimit by rememberSaveable { mutableStateOf(100) }
    var stageWindow by remember { mutableStateOf<StageRows?>(null) }
    var stageLoading by remember { mutableStateOf(false) }
    val actions = rememberCoroutineScope()
    val ctx = LocalContext.current
    // picked files waiting to be sent: staged (copied, hashed) only on send
    val attachments = composer.files
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        for (uri in uris) attachments.add(PendingAttachment.of(ctx, uri))
        if (uris.isNotEmpty()) moreOpen = false
    }
    LaunchedEffect(chorus.device?.session, model.spaces.map { it.id }, refresh, syncStatus == Status.Live) {
        directory = emptyMap()
        connections = emptyList()
        val dev = chorus.device ?: return@LaunchedEffect
        if (syncStatus != Status.Live) return@LaunchedEffect
        try {
            directory = Spaces.list(dev)
            connections = Spaces.connections(dev).filter { it.id != dev.accountId }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            error = "Chat details could not be loaded. Open Chat again to retry."
        }
    }
    var cardsFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(space?.id, chorus.device?.session, refresh) {
        foreignAuthors = emptyMap()
        cardsFor = null
        val dev = chorus.device ?: return@LaunchedEffect
        val id = space?.id?.takeIf { space.kind != "internal" } ?: return@LaunchedEffect
        try { foreignAuthors = Spaces.authorCards(dev, id); cardsFor = id }
        catch (_: Exception) { /* Own members still have projection names; retry on next refresh. */ }
    }
    val threads = remember(model) { ChatThreads.summaries(model) }
    val threadParentChannel = remember(model, channel?.id) { channel?.takeIf { it.kind == "thread" }?.let { ChatThreads.parentChannel(model, it) } }
    // the message a thread hangs from, as this reader may see it (older ones come from the replica)
    val threadParent by produceState<ChatMessage?>(null, model, channel?.id, threadParentChannel?.id, viewingAs) {
        val thread = channel?.takeIf { it.kind == "thread" }
        val home = threadParentChannel
        val s = space
        value = null
        if (thread == null || home == null || s == null) return@produceState
        value = model.chatMessages[home.id].orEmpty().find {
            it.id == thread.parentMessageId && memberVisible(it, s.kind, model.current, viewingAs) &&
                accountVisible(it, s.kind, chorus.device?.accountId.orEmpty())
        } ?: chorus.stageWindow(home.id, 10_000, s.kind, model.current, viewingAs).messages.find { it.id == thread.parentMessageId }
    }
    BackHandler(active && conversationOpen && !infoOpen && channel?.kind == "thread" && threadParentChannel != null) {
        threadParentChannel?.let { selectedChannel = it.id }
    }
    BackHandler(active && infoOpen) { infoOpen = false }
    LaunchedEffect(sharedDraft?.id, channel?.id, chorus.device?.accountId) {
        val incoming = sharedDraft ?: return@LaunchedEffect
        if (channel?.id != sharedChannel || chorus.device == null) return@LaunchedEffect
        if (incoming.text.isNotBlank()) draft = listOf(draft, incoming.text).filter { it.isNotBlank() }.joinToString("\n")
        for (uri in incoming.uris) {
            try { attachments.add(PendingAttachment.of(ctx, uri)) }
            catch (_: Exception) { error = "One shared file could not be opened. Please attach it again." }
        }
        onShareConsumed()
    }
    LaunchedEffect(chorus.device?.accountId) { pendingPrivateReply = null; carriedReply = null }
    val here = model.current.filter { it.subjectType == "member" }.map { it.subjectId }
    val keptTo = remember(model.members, channel, space) { channel?.let { ChatManage.inheritedSides(it, model, model.members.mapTo(HashSet()) { m -> m.id }) }?.mine }
    val defaultAuthorId = remember(model, channel?.id) { channel?.id?.let { ChatSpeaker.pick(model, it) } }.let { ChatManage.keptSpeaker(keptTo, it, here) }
    val replySpeakerId = ChatManage.keptSpeaker(keptTo, selectedAuthor.takeIf { id -> model.active.any { it.id == id } } ?: defaultAuthorId, here)
    fun replyPrivately(message: ChatMessage) {
        val source = space ?: return
        val dev = chorus.device ?: return
        if (busy) return
        busy = true; error = null
        actions.launch {
            try {
                if (source.kind == "internal") {
                    val speaker = replySpeakerId?.let(model::member)
                        ?: throw IllegalArgumentException("Choose who is speaking first.")
                    val author = message.authors.firstOrNull()?.let(model::member)
                        ?: throw IllegalArgumentException("This message has no available member to reply to.")
                    val existing = PrivateReplies.existing(model.channels, source.id, speaker.id, author.id)
                    val target = existing?.id ?: chorus.newId().also { id ->
                        chorus.create("channel.create", id, PrivateReplies.createPayload(source.id, speaker, author),
                            scope = "space:${source.id}")
                    }
                    pendingPrivateReply = PendingPrivateReply(message, channelId = target, speakerId = speaker.id)
                    selectedChannel = target
                    conversationOpen = true
                } else {
                    val account = message.accountId?.takeIf { it != dev.accountId }
                        ?: throw IllegalArgumentException("This message is from your account.")
                    val targetSpace = Spaces.openDm(dev, account)
                    pendingPrivateReply = PendingPrivateReply(message, spaceId = targetSpace, speakerId = replySpeakerId)
                    selectedSpace = targetSpace
                    conversationOpen = true
                    selectedChannel = ""
                    refresh++
                }
                replyTo = null
                carriedReply = null
                viewingAs = null
            } catch (e: Exception) { error = e.message ?: "Could not open a private reply." }
            finally { busy = false }
        }
    }
    // add and remove carry the identical element (LWW element set), as the acting speaker
    fun react(message: ChatMessage, emoji: String, on: Boolean) {
        val member = replySpeakerId ?: run { error = "Choose who is speaking first."; return }
        val home = space ?: return
        actions.launch {
            try {
                chorus.create(if (on) "reaction.add" else "reaction.remove", message.id,
                    ChatReactions.payload(message.id, emoji, member), scope = "space:${home.id}")
            } catch (e: Exception) { error = e.message ?: "Could not save the reaction." }
        }
    }
    fun setChannelFront(frontId: String) {
        val id = channel?.id ?: return
        if (busy) return
        busy = true; error = null
        actions.launch {
            try { chorus.create("pref.set", null, ChatSpeaker.frontPayload(id, frontId)); selectedAuthor = "" }
            catch (e: Exception) { error = e.message ?: "Could not save the channel's front." }
            finally { busy = false }
        }
    }
    fun setSpeakerDefault(mode: String, memberId: String? = null) {
        val id = channel?.id ?: return
        if (busy) return
        busy = true; error = null
        actions.launch {
            try {
                chorus.create("pref.set", null, ChatSpeaker.payload(id, mode, memberId))
                selectedAuthor = ""
            }
            catch (e: Exception) { error = e.message ?: "Could not save the speaker default." }
            finally { busy = false }
        }
    }
    val clearedAt = channel?.id?.let { model.accountPrefs.hiddenChats[it] }
    // someone's private chat that isn't ours to look at now (D-097, D-098): its words stay out and
    // nothing is marked read
    val shownNow = remember(model, viewingAs) { ChatOrganize.shown(model, viewingAs) }
    val locked = channel != null && !shownNow(channel.id)
    val messages = if (locked) emptyList() else model.chatMessages[channel?.id].orEmpty().filter {
        space != null && memberVisible(it, space.kind, model.current, viewingAs) &&
            accountVisible(it, space.kind, chorus.device?.accountId.orEmpty()) && (clearedAt == null || it.occurredAt > clearedAt)
    }
    // someone from another account spoke here after the cards were fetched: fetch them again (once
    // per new set of speakers, merged so names already shown stay), else they read "Someone" until
    // the chat is reopened. A member the server keeps hidden just stays unnamed.
    val strangers = messages.flatMap { it.authors }.filter { model.member(it) == null }.distinct().sorted()
    LaunchedEffect(cardsFor, strangers) {
        val id = cardsFor ?: return@LaunchedEffect
        val dev = chorus.device ?: return@LaunchedEffect
        if (id != space?.id || strangers.all { it in foreignAuthors }) return@LaunchedEffect
        try { foreignAuthors = foreignAuthors + Spaces.authorCards(dev, id) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* the next newcomer, refresh or reopen asks again */ }
    }
    // chat info tabs read every locally synced message the reader can see, not only the newest 100
    val library by produceState(messages, channel?.id, space?.kind, viewingAs, model.chatMessages[channel?.id],
        model.current, infoOpen, expanded, locked) {
        val c = channel; val s = space
        value = messages
        if (!locked && c != null && s != null && (infoOpen || expanded)) {
            value = chorus.stageWindow(c.id, 10_000, s.kind, model.current, viewingAs).messages
                .filter { clearedAt == null || it.occurredAt > clearedAt }
        }
    }
    val readMarks = model.readMarks[channel?.id].orEmpty()
    val readerId = viewingAs ?: channel?.id?.let { ReadTracking.readersFor(model, it, model.accountPrefs.readPerMember).firstOrNull() }
    val readerName = readerId?.let(model::member)?.shownName
    val openingRead = remember(channel?.id, readerId, conversationOpen, expanded, model.accountPrefs.readPerMember) {
        ChatPresentation.catchUp(messages, readMarks, if (model.accountPrefs.readPerMember) readerId.orEmpty() else "")
    }
    val catchUp = remember(openingRead, messages) { openingRead.copy(unreadIds = messages.filter { it.id in openingRead.unreadIds }.mapTo(linkedSetOf()) { it.id }) }
    val unreadCount = if (!model.isPerson && model.accountPrefs.readPerMember && readerName != null) catchUp.unreadIds.size else 0
    val firstUnread = catchUp.firstUnread.takeIf { unreadCount > 0 }
    val unreadMentions = remember(messages, catchUp, readerId, model.accountPrefs.readPerMember) {
        if (!model.accountPrefs.readPerMember || readerId.isNullOrEmpty()) emptyList() else messages.filter { it.id in catchUp.unreadIds && ChatPresentation.mentionsMember(it, readerId) }
    }

    LaunchedEffect(channel?.id, space?.id, messages.lastOrNull()?.id, readMarks,
        model.current, model.accountPrefs.readPerMember, chorus.device?.accountId, staging,
        model.namedFronts, model.speakerDefaults[channel?.id], conversationOpen, expanded, active, requestedSpace, requestedChannel) {
        val latest = messages.lastOrNull() ?: return@LaunchedEffect
        val chosen = channel ?: return@LaunchedEffect
        val home = space ?: return@LaunchedEffect
        if (!active || requestedSpace != null || requestedChannel != null || staging ||
            (!conversationOpen && !expanded) || chorus.device == null) return@LaunchedEffect
        try {
            for (reader in ReadTracking.readersFor(model, chosen.id, model.accountPrefs.readPerMember)) {
                if (!ReadTracking.needsMark(latest.occurredAt, latest.id, readMarks, reader)) continue
                chorus.create("read.mark", null, JSONObject().put("channel_id", chosen.id)
                    .put("message_id", latest.id).put("message_at", latest.occurredAt)
                    .put("reader_member_id", reader), scope = "space:${home.id}")
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { error = e.message ?: "Could not mark this channel read." }
    }
    val unseenByMessage by produceState<Map<String, List<String>>>(emptyMap(), messages, readMarks,
        model.accountPrefs.readPerMember) {
        value = emptyMap()
        if (model.accountPrefs.readPerMember && readMarks.any { it.member.isNotEmpty() }) {
            value = withContext(Dispatchers.Default) { messages.associate { message ->
                message.id to ReadTracking.unseen(message.occurredAt, message.id, readMarks)
            } }
        }
    }
    var previousChannel by rememberSaveable { mutableStateOf(channel?.id) }
    LaunchedEffect(channel?.id, chorus.device?.accountId) {
        if (previousChannel == channel?.id) return@LaunchedEffect
        previousChannel = channel?.id
        editingMessageId = null
        alsoSend = false
        carriedReply = null
        speakerSettingsOpen = false
        infoOpen = false
        staging = false
        capturing = false
        stageLimit = 100
        stageWindow = null
    }
    LaunchedEffect(channel?.id, space?.id, pendingPrivateReply) {
        val pending = pendingPrivateReply ?: return@LaunchedEffect
        val destination = channel ?: return@LaunchedEffect
        if (pending.channelId == destination.id || pending.spaceId == destination.spaceId && pending.spaceId == space?.id) {
            replyTo = pending.message.id
            carriedReply = pending.message
            selectedAuthor = pending.speakerId.orEmpty()
            pendingPrivateReply = null
        }
    }
    LaunchedEffect(staging, channel?.id, space?.kind, viewingAs, model, stageLimit, chorus.device?.accountId) {
        if (!staging || locked || channel == null || space == null) { stageWindow = null; return@LaunchedEffect }
        stageLoading = true
        try { stageWindow = StageRows(channel.id, chorus.device?.accountId.orEmpty(), space.kind,
            viewingAs, model.current, chorus.stageWindow(channel.id, stageLimit, space.kind, model.current, viewingAs)) }
        finally { stageLoading = false }
    }
    LaunchedEffect(capturing) { onStageCapture(capturing) }
    DisposableEffect(Unit) { onDispose { onStageCapture(false) } }
    BackHandler(active && staging) {
        if (capturing) capturing = false else staging = false
    }
    if (staging && channel != null && !locked) {
        val window = stageWindow?.takeIf { it.channelId == channel.id && it.accountId == chorus.device?.accountId &&
            it.spaceKind == space?.kind && it.viewingAs == viewingAs && it.front == model.current }?.window
        val stageMessages = window?.messages?.filter {
            space != null && memberVisible(it, space.kind, model.current, viewingAs) &&
                accountVisible(it, space.kind, chorus.device?.accountId.orEmpty()) && (clearedAt == null || it.occurredAt > clearedAt)
        } ?: messages
        ChatStage(chorus, channel, stageMessages, model, foreignAuthors, capturing,
            hasOlder = window?.hasOlder == true, loadingOlder = stageLoading,
            onLoadOlder = { stageLimit += 100 },
            onCapture = { capturing = it }, onClose = { capturing = false; staging = false }, initialSelected = stageSelection)
        return
    }

    val ownIds = remember(model.members) { model.members.mapTo(HashSet()) { it.id } }
    // names for other accounts' members a private chat is kept to (D-098), from those DMs' author cards
    var privateCards by remember { mutableStateOf<Map<String, ForeignAuthor>>(emptyMap()) }
    val needCards = model.channels.filter { c -> c.kind == "member_dm" && c.memberIds.any { it !in ownIds } &&
        model.spaces.find { it.id == c.spaceId }?.kind == "dm" }.map { it.spaceId }.distinct().sorted()
    LaunchedEffect(needCards, chorus.device?.session, syncStatus == Status.Live) {
        val dev = chorus.device ?: return@LaunchedEffect
        if (syncStatus != Status.Live) return@LaunchedEffect
        for (id in needCards) {
            try { privateCards = privateCards + Spaces.authorCards(dev, id) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* the partner's name stands in until the next refresh */ }
        }
    }
    val listRows = remember(model, viewingAs, chorus.device?.accountId, directory, foreignAuthors, privateCards) {
        val prefs = model.accountPrefs
        model.channels.mapNotNull { candidate ->
            if (candidate.kind == "thread") return@mapNotNull null // threads live under their parent
            val home = model.spaces.find { it.id == candidate.spaceId } ?: return@mapNotNull null
            // a member's private chats only while one of them is here (D-097, D-098)
            if (!ChatOrganize.privateVisible(candidate, home, model.current, viewingAs, prefs.hidePrivate, ownIds)) return@mapNotNull null
            val clearedAt = prefs.hiddenChats[candidate.id]
            val visible = model.chatMessages[candidate.id].orEmpty().filter {
                memberVisible(it, home.kind, model.current, viewingAs) && accountVisible(it, home.kind, chorus.device?.accountId.orEmpty()) &&
                    (clearedAt == null || it.occurredAt > clearedAt)
            }
            // cleared for us: gone until something newer arrives
            if (clearedAt != null && visible.isEmpty()) return@mapNotNull null
            val reader = if (!model.accountPrefs.readPerMember) "" else viewingAs ?: ReadTracking.readersFor(model, candidate.id, true).firstOrNull().orEmpty()
            val marks = model.readMarks[candidate.id].orEmpty()
            val unread = visible.count { ReadTracking.needsMark(it.occurredAt, it.id, marks, reader) }
            val partner = directory[home.id]?.accounts?.firstOrNull { it.id != chorus.device?.accountId }?.shownName
            // a private chat (D-098): titled by their member on their side, by the friend on ours
            val sides = ChatManage.sides(candidate, home, ownIds)
            val title = if (sides != null) sides.theirs.mapNotNull { privateCards[it]?.name ?: foreignAuthors[it]?.name }
                    .joinToString(" & ").ifEmpty { partner ?: candidate.name }
                else if (home.kind == "dm") partner ?: home.name
                else if (home.kind == "shared" && model.channels.count { it.spaceId == home.id && it.kind != "thread" } == 1) home.name else candidate.name
            val latest = visible.lastOrNull()
            val sender = latest?.authors?.joinToString(" & ") { id -> model.member(id)?.shownName ?: foreignAuthors[id]?.name
                ?: directory[home.id]?.accounts?.find { it.id == latest.accountId }?.shownName ?: "Someone" }
            ChatListRow(candidate, home, title, latest, unread, sender,
                muted = model.accountPrefs.channelNotify(candidate.id, home.kind) == "none", pinned = candidate.id in prefs.chatPins,
                private = sides != null || (home.kind == "internal" && candidate.kind == "text" && candidate.memberIds.isNotEmpty()))
        }.let { rows -> ChatOrganize.order(rows, prefs.chatPins, { it.channel.id }, { it.latest?.occurredAt ?: 0L }, { it.title }) }
    }
    var arrangingPins by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<ChatListRow?>(null) }
    fun savePins(pins: List<String>) {
        actions.launch {
            try { chorus.create("pref.set", null, AccountPrefs.payload(ChatOrganize.PINS, ChatOrganize.pinsValue(pins, model.channels.map { it.id }.toSet()))) }
            catch (e: Exception) { error = e.message ?: "Could not save pinned chats." }
        }
    }
    val rowAction: (ChatListRow, String) -> Unit = { row, action ->
        when (action) {
            "pin" -> savePins(ChatOrganize.togglePin(model.accountPrefs.chatPins, row.channel.id))
            "arrange" -> arrangingPins = true
            "remove" -> removing = row
        }
    }
    val openChat: (ChatListRow) -> Unit = { row ->
        selectedSpace = row.space.id; selectedChannel = row.channel.id; conversationOpen = true
        carriedReply = null; pendingPrivateReply = null; moreOpen = false
    }
    // a snapshot plus copies of its files in the target scope, then the reader follows it there
    fun forward(message: ChatMessage, row: ChatListRow) {
        val from = channel ?: return
        val speaker = replySpeakerId ?: run { error = "Choose who is speaking first."; return }
        if (busy) return
        busy = true; error = null
        actions.launch {
            try {
                val scope = "space:${row.space.id}"
                val ids = message.attachments.map { a ->
                    chorus.newId().also { id -> chorus.create("attachment.create", id, ChatForward.attachmentCopy(a), scope = scope) }
                }
                chorus.create("message.forward", chorus.newId(), ChatForward.payload(row.channel.id, speaker, message, from.name, ids), scope = scope)
                forwarding = null; forwardConfirm = null
                openChat(row)
                followNext = true
            } catch (e: Exception) { error = e.message ?: "Could not forward the message." }
            finally { busy = false }
        }
    }
    fun delete(message: ChatMessage, restore: Boolean = false) {
        val home = space ?: return
        actions.launch {
            try {
                chorus.create(if (restore) "message.restore" else "message.delete", message.id, JSONObject(), scope = "space:${home.id}")
                undoDelete = if (restore) null else message
            } catch (e: Exception) { error = e.message ?: if (restore) "Could not restore the message." else "Could not delete the message." }
        }
    }
    LaunchedEffect(undoDelete) { if (undoDelete != null) { delay(8_000); undoDelete = null } }
    // pins carry who pinned (the acting speaker), like web
    fun pin(message: ChatMessage) {
        val home = space ?: return
        actions.launch {
            try {
                chorus.create(if (message.pinned) "message.unpin" else "message.pin", message.id, JSONObject(),
                    scope = "space:${home.id}", memberId = replySpeakerId)
            } catch (e: Exception) { error = e.message ?: "Could not change the pin." }
        }
    }
    // a channel's notifications are an account pref the server reads (NOTIFICATIONS §7)
    fun setNotify(level: String) {
        val id = channel?.id ?: return
        actions.launch {
            try { chorus.create("pref.set", null, AccountPrefs.payload("notify_channel:$id", level)) }
            catch (e: Exception) { error = e.message ?: "Could not save notifications for this chat." }
        }
    }
    // one thread per parent, whose id is the parent's, so every device opens the same one
    fun openThread(message: ChatMessage) {
        val home = space ?: return
        val existing = model.channels.find { it.kind == "thread" && it.spaceId == home.id && it.parentMessageId == message.id }
        if (existing != null) { selectedChannel = existing.id; conversationOpen = true; return }
        if (busy) return
        busy = true; error = null
        actions.launch {
            try {
                chorus.create("channel.create", message.id, JSONObject().put("space_id", home.id).put("kind", "thread")
                    .put("name", "Thread").put("parent_message_id", message.id), scope = "space:${home.id}")
                selectedChannel = message.id; conversationOpen = true
            } catch (e: Exception) { error = e.message ?: "Could not start a thread." }
            finally { busy = false }
        }
    }
    val readingPositions = rememberSaveable(saver = ChatReadPositionsSaver) {
        androidx.compose.runtime.mutableStateMapOf<String, ChatReadingPosition>()
    }
    val idleReading = remember { ChatReadingPosition(androidx.compose.foundation.lazy.LazyListState(), null) }
    val reading = if (!conversationOpen && !infoOpen && !expanded) idleReading else readingPositions.getOrPut("${channel?.id}:$readerId") {
        val initial = messages.asReversed().indexOfFirst { it.id == firstUnread }.coerceAtLeast(0)
        ChatReadingPosition(androidx.compose.foundation.lazy.LazyListState(initial, 0), messages.lastOrNull()?.id)
    }
    val typing by chorus.typing.collectAsState()
    val typingLabel = if (syncStatus == Status.Live) garden.vayne.chorus.data.TypingText.label(model, typing[channel?.id].orEmpty()) else null
    val chatRoute = if (expanded) "wide/${channel?.id}" else if (infoOpen) "info/${channel?.id}" else if (conversationOpen) "conversation/${channel?.id}" else "list"
    val snapshots = remember { mutableMapOf<String, ChatPaneSnapshot>() }
    val latestRoute by androidx.compose.runtime.rememberUpdatedState(chatRoute)
    snapshots[chatRoute] = ChatPaneSnapshot(channel, space, messages, library, threadParent, threadParentChannel, reading)
    val depth = if (expanded) 1 else (if (conversationOpen) 1 else 0) + (if (channel?.kind == "thread") 1 else 0) + (if (infoOpen) 1 else 0)
    ChorusScene(chatRoute, depth) { shown ->
    DisposableEffect(shown) { onDispose { if (shown != latestRoute) snapshots.remove(shown) } }
    val pane = shown.substringBefore('/')
    val currentPane = active && shown == chatRoute
    val snapshot = snapshots.getValue(shown)
    // The exiting pane keeps its channel, messages and scroll state until its animation ends.
    val channel = snapshot.channel
    val space = snapshot.space
    val messages = snapshot.messages
    val library = snapshot.library
    val threadParent = snapshot.parent
    val threadParentChannel = snapshot.parentChannel
    val paneComposer = drafts[channel?.id] ?: composer
    var draft by paneComposer.text
    var selectedAuthor by paneComposer.author
    var cw by paneComposer.cw
    var audience by paneComposer.audience
    var visibleTo by paneComposer.visibleTo
    var replyTo by paneComposer.replyTo
    var pollOn by paneComposer.poll
    var pollMultiple by paneComposer.multiple
    val pollOptions = paneComposer.options
    val attachments = paneComposer.files
    // a private chat in a DM (D-098): whose it is on each side; someone else's, not here now, is locked
    val paneSides = channel?.let { ChatManage.inheritedSides(it, model, ownIds) }
    val paneLocked = channel != null && !shownNow(channel.id)
    val defaultAuthorId = ChatManage.keptSpeaker(paneSides?.mine, channel?.id?.let { ChatSpeaker.pick(model, it) }, here)
    val replySpeakerId = ChatManage.keptSpeaker(paneSides?.mine, selectedAuthor.takeIf { id -> model.active.any { it.id == id } } ?: defaultAuthorId, here)
    val paneEditable = channel != null && channel.kind != "thread" && !paneLocked && when (space?.kind) {
        "internal" -> !model.isPerson
        "dm" -> channel.kind == "member_dm" && paneSides?.mine?.isNotEmpty() == true
        else -> ChatManage.canEditSpace(space?.kind, ChatManage.roleIn(model.spaceRoles, space?.id.orEmpty(), space?.id?.let { directory[it] }, chorus.device?.accountId.orEmpty()))
    }
    fun nameOf(id: String) = model.member(id)?.shownName ?: foreignAuthors[id]?.name ?: privateCards[id]?.name ?: "Someone"
    // a DM with someone you follow: who's fronting there, as far as they share it (FriendFronts)
    val dmPartner = space?.takeIf { it.kind == "dm" }?.let { s -> directory[s.id]?.accounts?.firstOrNull { it.id != chorus.device?.accountId } }
    fun infoTitle(c: garden.vayne.chorus.data.ChatChannel, s: ChatSpace): String = when {
        paneLocked -> "Private chat"
        c.kind == "thread" -> "Thread"
        paneSides?.theirs?.isNotEmpty() == true -> paneSides!!.theirs.joinToString(" & ", transform = ::nameOf)
        s.kind == "dm" -> dmPartner?.shownName ?: "Direct message"
        s.kind == "shared" && model.channels.count { it.spaceId == s.id && it.kind != "thread" } <= 1 -> s.name
        else -> c.name
    }
    fun infoSubtitle(c: garden.vayne.chorus.data.ChatChannel, s: ChatSpace): String? = when {
        paneLocked -> "Opens while its member is here"
        paneSides != null -> if (paneSides.mine.isNotEmpty()) "Private · ${paneSides.mine.joinToString(" & ", transform = ::nameOf)}"
            else "Private chat · ${dmPartner?.shownName ?: "Direct message"}"
        else -> null
    }
    val friendViews by garden.vayne.chorus.data.FriendFronts.views.collectAsState()
    LaunchedEffect(dmPartner?.id, chorus.device?.accountId) {
        val other = dmPartner ?: return@LaunchedEffect
        while (true) {
            chorus.device?.let { garden.vayne.chorus.data.FriendFronts.refresh(it, other.id) }
            delay(60_000)
        }
    }
    val partnerFront = dmPartner?.let { other -> chorus.device?.let { friendViews[garden.vayne.chorus.data.FriendFronts.key(it.accountId, other.id)] } }
        ?.let { garden.vayne.chorus.data.FriendFronts.line(it.frontNames) }
    Row(Modifier.fillMaxSize().background(p.bg).imePadding()) {
        if (arrangingPins) ArrangePins(listRows.filter { it.pinned }, if (expanded) Modifier.width(320.dp) else Modifier.weight(1f),
            { arrangingPins = false }) { savePins(it); arrangingPins = false }
        else if (expanded || pane == "list") ChatListPane(model, listRows, if (expanded) channel?.id else null, readerName,
            openChat, { spaceAction = "new" },
            { switchOpen = true }, onSearch, if (expanded) Modifier.width(240.dp) else Modifier.weight(1f), readerId = readerId,
            onNewChannel = { editChannel = false; channelEditor = true }, onRowAction = rowAction)
        if (expanded) VerticalDivider(Modifier.fillMaxHeight(), color = p.line)
        if (pane == "info" && !expanded && channel != null && space != null && !paneLocked) {
            ChatInfoPane(chorus, channel, space, model, messages, library, directory[space.id], { infoOpen = false; onSearch() },
                { infoOpen = false }, model.accountPrefs.channelNotify(channel.id, space.kind), ::setNotify,
                onShowMessage = { infoOpen = false; jumpTo = it }, modifier = Modifier.weight(1f), title = infoTitle(channel, space),
                subtitle = infoSubtitle(channel, space), connections = connections, foreign = foreignAuthors + privateCards,
                speakerId = replySpeakerId, onSpaceChanged = { refresh++ }, onOpenChannel = { id -> selectedChannel = id; infoOpen = false; conversationOpen = true },
                onLeft = { selectedSpace = ""; selectedChannel = ""; infoOpen = false; conversationOpen = false; refresh++ })
        } else if (expanded || pane == "conversation") Column(Modifier.weight(1f).fillMaxHeight()) {
        Row(Modifier.fillMaxWidth().background(p.surface), verticalAlignment = Alignment.CenterVertically) {
            val inThread = channel?.kind == "thread"
            if (inThread && threadParentChannel != null) ChatIconButton(R.drawable.ic_back, "Back to ${threadParentChannel.name}") {
                selectedChannel = threadParentChannel.id
            } else if (!expanded) ChatIconButton(R.drawable.ic_back, "Back to chats") { conversationOpen = false }
            // an account DM's channel is just "dm": it is shown as the account at the other end
            val dmName = (if (paneLocked) "Private chat" else paneSides?.theirs?.takeIf { it.isNotEmpty() }?.joinToString(" & ", transform = ::nameOf)
                ?: dmPartner?.shownName)?.takeIf { !inThread }
            channel?.let { ChannelDisc(if (dmName != null) it.copy(name = dmName) else it,
                member = threadParent?.authors?.firstOrNull()?.let(model::member).takeIf { inThread }, size = 36.dp) }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 10.dp)) {
                Text(if (inThread) "Thread" else dmName ?: channel?.name ?: "Chat", color = p.ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(typingLabel ?: if (paneLocked) "Opens while its member is here"
                    else if (paneSides != null && !inThread) (if (paneSides.mine.isNotEmpty()) "Private · ${paneSides.mine.joinToString(" & ", transform = ::nameOf)}"
                        else "Private chat · ${dmPartner?.shownName ?: "Direct message"}")
                    else if (inThread && syncStatus == Status.Live) "in ${threadParentChannel?.name ?: "a channel"} · ${ChatThreads.repliesLabel(messages.size)}"
                    else ChatPresentation.subtitle(syncStatus != Status.Live, readerName, unreadCount,
                    partnerFront ?: space?.let { spaceLabel(it, directory[it.id], chorus.device?.accountId.orEmpty()) }.orEmpty()),
                    color = if (typingLabel != null || syncStatus != Status.Live) p.accent else p.ink3, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2)
            }
            ChatIconButton(R.drawable.ic_search, "Search messages", onSearch)
            Box {
                ChatIconButton(R.drawable.ic_more, "Conversation options") { headerMenu = true }
                DropdownMenu(headerMenu, { headerMenu = false }) {
                    if (channel != null && !expanded) DropdownMenuItem(text = { Text("Chat info") }, onClick = { headerMenu = false; infoOpen = true })
                    if (paneEditable) DropdownMenuItem(text = { Text(if (channel?.kind == "member_dm") "Edit chat" else "Edit channel") },
                        onClick = { headerMenu = false; editChannel = true; channelEditor = true })
                    DropdownMenuItem(text = { Text("New chat…") }, onClick = { headerMenu = false; spaceAction = "new" })
                    if (channel != null) DropdownMenuItem(text = { Text("Notifications…") }, onClick = { headerMenu = false; notifyOpen = true })
                    if (channel != null) DropdownMenuItem(text = { Text("Stage mode") }, onClick = { headerMenu = false; stageSelection = emptyList(); staging = true })
                    DropdownMenuItem(text = { Text("Message options") }, onClick = { headerMenu = false; moreOpen = !moreOpen })
                    if (space?.kind == "internal" && !model.isPerson) {
                        DropdownMenuItem(text = { Text("Read as…") }, onClick = { headerMenu = false; readerOpen = true })
                    }
                    val canLeave = space != null && space.kind != "internal" && directory[space.id]?.let {
                        space.kind != "shared" || it.ownerAccountId != chorus.device?.accountId } == true
                    if (canLeave) DropdownMenuItem(text = { Text("Leave space") }, onClick = { headerMenu = false; spaceAction = "leave" })
                }
            }
        }
        // F5.2: the pinned banner; tapping shows the pin and steps to the next one
        val pins = if (paneLocked) emptyList() else model.pinnedMessages[channel?.id].orEmpty().filter {
            space != null && memberVisible(it, space.kind, model.current, viewingAs) &&
                accountVisible(it, space.kind, chorus.device?.accountId.orEmpty())
        }
        var pinStep by remember(channel?.id) { mutableStateOf(0) }
        if (pins.isNotEmpty()) {
            val shown = pins[pins.size - 1 - pinStep % pins.size]
            Row(Modifier.fillMaxWidth().background(p.surface).clickable(onClickLabel = "Show pinned message") {
                jumpTo = shown.id; pinStep = (pinStep + 1) % pins.size
            }.heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(2.dp).height(32.dp).background(p.accent))
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(if (pins.size > 1) "Pinned message ${pinStep % pins.size + 1} of ${pins.size}" else "Pinned message",
                        color = p.accent, fontSize = 12.sp, lineHeight = 16.sp)
                    Text(ChatPresentation.preview(shown), color = p.ink2, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1)
                }
                ChatIcon(R.drawable.ic_pin, tint = p.ink3)
            }
            HorizontalDivider(color = p.line)
        }
        if (error != null) Text(error.orEmpty(), color = p.danger, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(16.dp))
        if (space == null) Text("No spaces yet. Start a chat with someone you follow.", color = p.ink2, modifier = Modifier.padding(24.dp))
        else {
        if (searchHit != null && searchHit.channelId == channel?.id) {
            var revealed by rememberSaveable(searchHit.id) { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                .background(p.surface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Search match · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(searchHit.occurredAt))}",
                        color = p.ink2, modifier = Modifier.weight(1f), maxLines = 2)
                    TextButton(onClick = onDismissSearchHit) { Text("Dismiss", maxLines = 1) }
                }
                if (searchHit.cw != null) Text("Content warning: ${searchHit.cw} · ${if (revealed) "Hide" else "Show"}",
                    color = p.accent, modifier = Modifier.clickable { revealed = !revealed }.heightIn(min = 48.dp).wrapContentSize(Alignment.CenterStart))
                if (searchHit.cw == null || revealed) Text(searchHit.text, color = p.ink)
            }
        }
        if (channel == null) {
            Text("No channels in this space yet.", color = p.ink2, modifier = Modifier.padding(24.dp))
        } else if (paneLocked) {
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
                Box(Modifier.size(64.dp).background(p.surface2, CircleShape), contentAlignment = Alignment.Center) { ChatIcon(R.drawable.ic_lock, tint = p.ink2) }
                Text("This chat is private", color = p.ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text("It belongs to one member of your system, and opens while they're fronting or co-conscious.",
                    color = p.ink3, fontSize = 14.sp, textAlign = TextAlign.Center)
            }
        } else {
        if (!model.isPerson && readerName != null && model.accountPrefs.readPerMember) Row(Modifier.fillMaxWidth().background(p.accentSoft).padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            readerId?.let(model::member)?.let { ChatAvatar(it, 24.dp) }
            Text("Reading as $readerName · " + (catchUp.mark?.let { "read through ${ChatPresentation.dateLabel(it.at).lowercase()}" }
                ?: "no saved read position"), color = p.ink2, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { readerOpen = true }) { Text("Change", fontSize = 12.sp, lineHeight = 16.sp) }
        }
        if (pendingPrivateReply != null) Text("Opening private chat · Cancel", color = p.accent,
            modifier = Modifier.clickable { pendingPrivateReply = null }.heightIn(min = 48.dp).wrapContentSize(Alignment.CenterStart).padding(horizontal = 16.dp))
        // A reversed list keeps its place on the message that was newest, so a new one would land
        // just out of view: follow it while the reader is at (or near) the bottom, and after sending.
        val listState = snapshot.reading.list
        val newest = messages.lastOrNull()?.id
        LaunchedEffect(newest, readerId, currentPane) {
            if (!currentPane) return@LaunchedEffect
            if (newest != null && ((newest != snapshot.reading.newest && listState.firstVisibleItemIndex <= 2) || followNext))
                listState.animateScrollToItem(0)
            snapshot.reading.newest = newest
            followNext = false
        }
        // after the opening position, so coming back from chat info lands on the chosen message
        LaunchedEffect(jumpTo, currentPane) {
            if (!currentPane) return@LaunchedEffect
            val id = jumpTo ?: return@LaunchedEffect
            val at = messages.asReversed().indexOfFirst { it.id == id }
            if (at >= 0) listState.animateScrollToItem(at)
            jumpTo = null
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), state = listState, reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(messages.asReversed(), key = { _, message -> message.id }) { index, message ->
                val older = messages.getOrNull(messages.lastIndex - index - 1)
                val newer = messages.getOrNull(messages.lastIndex - index + 1)
                val groupStart = !ChatPresentation.grouped(older, message) || message.id == firstUnread
                val groupEnd = !ChatPresentation.grouped(message, newer) || newer?.id == firstUnread
                Column(Modifier.padding(top = if (groupStart) 8.dp else 0.dp)) {
                if (readerName != null && message.id == firstUnread) Text("${unreadCount} unread for $readerName",
                    color = p.accent, fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).background(p.surface).padding(vertical = 6.dp))
                if (older == null || ChatPresentation.day(older.occurredAt) != ChatPresentation.day(message.occurredAt)) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        Text(ChatPresentation.dateLabel(message.occurredAt), color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp,
                            modifier = Modifier.background(p.surface, RoundedCornerShape(20.dp)).padding(horizontal = 12.dp, vertical = 4.dp))
                    }
                }
                val canPrivate = if (space.kind == "internal") replySpeakerId != null &&
                    message.authors.firstOrNull()?.let { it != replySpeakerId && model.member(it) != null } == true
                else message.accountId != null && message.accountId != chorus.device?.accountId
                ChatMessageCard(message, model, foreignAuthors, chorus, unseenByMessage[message.id].orEmpty(),
                    heldMessages[message.id], actingMemberId = replySpeakerId,
                    reactions = model.messageReactions[message.id].orEmpty(),
                    onForward = if (heldMessages[message.id] == null) ({ forwarding = message }) else null,
                    onPin = if (heldMessages[message.id] == null) ({ pin(message) }) else null,
                    thread = threads[message.id],
                    onThread = if (channel.kind != "thread" && heldMessages[message.id] == null) ({ openThread(message) }) else null,
                    onDelete = if (chorus.device?.accountId?.let { it == message.accountId } == true &&
                        heldMessages[message.id] == null) ({ deleting = message }) else null,
                    onReact = { emoji, on -> react(message, emoji, on) },
                    showSender = groupStart, showAvatar = groupEnd, onStage = { stageSelection = listOf(message.id); staging = true },
                    quote = messages.find { it.id == message.replyTo },
                    onReply = { replyTo = message.id; carriedReply = null },
                    onReplyPrivately = if (canPrivate) ({ replyPrivately(message) }) else null,
                    onEdit = if (chorus.device?.accountId?.let { it == message.accountId } == true &&
                        heldMessages[message.id] == null)
                        ({ editingMessageId = message.id }) else null)
                }
            }
            // F5.7: the parent heads the thread, then how many replies follow
            if (channel.kind == "thread") item(key = "thread-parent") {
                Column(Modifier.padding(top = 8.dp)) {
                    val parent = threadParent
                    if (parent != null) ChatMessageCard(parent, model, foreignAuthors, chorus, emptyList(), null,
                        actingMemberId = replySpeakerId, onReply = {}, onReplyPrivately = null, onEdit = null,
                        reactions = model.messageReactions[parent.id].orEmpty(), interactive = false, onStage = {})
                    else Text("Original message unavailable in this view", color = p.ink3, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(16.dp))
                    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        Text(ChatThreads.repliesLabel(messages.size), color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp,
                            modifier = Modifier.background(p.surface, RoundedCornerShape(20.dp)).padding(horizontal = 12.dp, vertical = 4.dp))
                    }
                }
            }
            if (messages.isEmpty() && channel.kind != "thread") item {
                Text("No messages here yet.", color = p.ink2, modifier = Modifier.padding(16.dp))
            }
        }
        if (listState.firstVisibleItemIndex > 1 || unreadCount > 0) Column(
            Modifier.align(Alignment.BottomEnd).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (unreadMentions.isNotEmpty()) ChatJumpButton("@", unreadMentions.size, "${unreadMentions.size} unread mentions for $readerName") {
                actions.launch { val at = messages.asReversed().indexOfFirst { it.id == unreadMentions.last().id }; if (at >= 0) listState.animateScrollToItem(at) }
            }
            ChatJumpButton(null, unreadCount, if (unreadCount > 0) "Jump to latest message · $unreadCount unread" else "Jump to latest message") { actions.launch { listState.animateScrollToItem(0) } }
        }
        }
        undoDelete?.let { gone ->
            Row(Modifier.fillMaxWidth().background(p.surface2).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Message deleted", color = p.ink, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { delete(gone, restore = true) }) { Text("Undo") }
            }
        }
        // Composer: one line (who's speaking, the text, options, send); options open above it
        Column(Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val author = model.active.find { it.id == selectedAuthor && (paneSides?.mine.isNullOrEmpty() || it.id in paneSides!!.mine) }
                ?: defaultAuthorId?.let(model::member)
            val senderAccount = chorus.device?.accountId.orEmpty()
            val ownSenders = remember(model, senderAccount, paneSides) {
                ChatCompose.members(model, senderAccount).filter { paneSides?.mine.isNullOrEmpty() || it.id in paneSides!!.mine } }
            // The last preview stays up while the next one is parsed, so the "As …" line and the send
            // button don't blink on every keystroke; sending parses the draft again itself.
            val authorPreview by produceState<ChatCompose.Preview?>(null, draft, author?.id, model, senderAccount) {
                value = withContext(Dispatchers.Default) {
                    runCatching { ChatCompose.preview(model, draft, author?.id, senderAccount) }.getOrNull()
                }
            }
            val currentPreview = authorPreview
            if (draft.isNotBlank() && currentPreview != null) {
                val keptOut = paneSides?.mine?.takeIf { it.isNotEmpty() }?.let { mine -> currentPreview.authors.any { it !in mine } } == true
                Text(if (keptOut) "Only ${paneSides!!.mine.joinToString(" & ", transform = ::nameOf)} writes here."
                    else if (currentPreview.authors.isEmpty()) "Choose a sender, or type their symbols followed by a space."
                    else "As " + currentPreview.authors.joinToString(" & ") { model.member(it)?.shownName ?: "Someone" },
                    color = p.ink2, fontSize = 13.sp, lineHeight = 18.sp)
            }
            val mentionStart = ChatPresentation.frontMentionStart(draft)
            if (mentionStart != null) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                draft = draft.substring(0, mentionStart) + "@front "
            }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("@front", color = p.accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("Channel front when it arrives", color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp)
            }
            val target = messages.find { it.id == replyTo } ?: carriedReply?.takeIf { it.id == replyTo }
            if (target != null) {
                Row(Modifier.fillMaxWidth().background(p.surface2, RoundedCornerShape(10.dp)).padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChatIcon(R.drawable.ic_reply, tint = p.accent)
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        val name = target.authors.joinToString(" & ") { model.member(it)?.shownName ?: foreignAuthors[it]?.name ?: "Someone" }
                        Text(if (carriedReply?.id == replyTo) "Private reply to $name" else "Reply to $name", color = p.accent, fontSize = 13.sp, lineHeight = 18.sp)
                        Text(ChatPresentation.preview(target), color = p.ink3, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2)
                    }
                    ChatIconButton(R.drawable.ic_close, "Cancel reply") { replyTo = null; carriedReply = null }
                }
            } else if (replyTo != null) {
                TextButton(onClick = { replyTo = null; carriedReply = null }) { Text("Reply target unavailable · Cancel") }
            }
            val audienceLabel = when (audience) {
                "members" -> "Chosen members (${visibleTo.size})"
                "system_only" -> "Only my system"
                else -> "Everyone here"
            }
            for ((i, a) in attachments.withIndex()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChatIcon(R.drawable.ic_attach)
                    Text(a.name, color = p.ink, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    ChatChip(if (a.spoiler) "Spoiler ✓" else "Spoiler", a.spoiler) { attachments[i] = a.copy(spoiler = !a.spoiler) }
                    ChatIconButton(R.drawable.ic_close, "Remove ${a.name}") { attachments.removeAt(i) }
                }
                if (a.mime.startsWith("image/")) {
                    OutlinedTextField(a.alt, { attachments[i] = a.copy(alt = it) }, placeholder = { Text("Describe the image (alt text)") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
            }
            if (moreOpen) ModalBottomSheet(onDismissRequest = { moreOpen = false }, containerColor = p.surface) {
              Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Add to message", color = p.ink, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp)
                Text("Attach images or files", color = p.accent, fontSize = 14.sp, lineHeight = 20.sp,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { picker.launch("*/*") }.wrapContentSize(Alignment.CenterStart))
                OutlinedTextField(cw, { cw = it }, placeholder = { Text("Content warning (optional)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                PollDraftFields(pollOn, { pollOn = it }, pollOptions, pollMultiple) { pollMultiple = it }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { ChatChip("Everyone here", audience == "all") { audience = "all" } }
                    if (space.kind == "internal") item {
                        ChatChip("Chosen members", audience == "members") { audience = "members" }
                    }
                    if (space.kind != "internal") item {
                        ChatChip("Only my system", audience == "system_only") { audience = "system_only" }
                    }
                }
                if (audience == "members" && space.kind == "internal") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(model.active, key = { it.id }) { member ->
                            ChatChip(member.shownName, member.id in visibleTo) {
                                visibleTo = if (member.id in visibleTo) visibleTo - member.id else visibleTo + member.id
                            }
                        }
                    }
                }
                if (space.kind != "internal" && audience == "all") {
                    Text("Everyone in this space can see who is speaking.", color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp)
                }
                if (!model.isPerson) {
                    Text(if (speakerSettingsOpen) "Advanced · Hide speaker default" else "Advanced · Speaker default ›",
                        color = p.accent, fontSize = 13.sp, lineHeight = 18.sp,
                        modifier = Modifier.clickable { speakerSettingsOpen = !speakerSettingsOpen }.heightIn(min = 48.dp).wrapContentSize(Alignment.CenterStart))
                    if (speakerSettingsOpen) {
                        val setting = model.speakerDefaults[channel.id] ?: SpeakerDefault()
                        val fronts = model.namedFronts?.channelChoices.orEmpty()
                        if (fronts.size > 1) {
                            val selected = ChatSpeaker.frontOf(model, channel.id)
                            Text("Front for this channel", color = p.ink2, fontSize = 12.sp, lineHeight = 16.sp)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(fronts, key = { it.id }) { front ->
                                    ChatChip(front.name.ifBlank { "Body" }, front.id == selected) { setChannelFront(front.id) }
                                }
                                if (fronts.none { it.id == selected }) item { ChatChip("Unavailable front", true) {} }
                            }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            item { ChatChip("Front", setting.mode == "front") { setSpeakerDefault("front") } }
                            item { ChatChip("Last speaker", setting.mode == "latch") { setSpeakerDefault("latch") } }
                            item { ChatChip("Ask me", setting.mode == "off") { setSpeakerDefault("off") } }
                            item { ChatChip("One member", setting.mode == "member") {
                                (setting.memberId ?: author?.id ?: ownSenders.firstOrNull()?.id)?.let {
                                    setSpeakerDefault("member", it)
                                }
                            } }
                        }
                        if (setting.mode == "member") {
                            Text("Always speak as", color = p.ink2, fontSize = 12.sp, lineHeight = 16.sp)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(ownSenders, key = { it.id }) { member ->
                                    ChatChip(member.shownName, member.id == setting.memberId) {
                                        setSpeakerDefault("member", member.id)
                                    }
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = { moreOpen = false }, modifier = Modifier.align(Alignment.End)) { Text("Done") }
              }
            }
            if (audience != "all" || cw.isNotBlank() || pollOn) {
                Text(listOfNotNull(audienceLabel.takeIf { audience != "all" }, "CW: ${cw.trim()}".takeIf { cw.isNotBlank() }, "Poll".takeIf { pollOn })
                    .joinToString(" · "), color = p.ink2, fontSize = 12.sp, lineHeight = 16.sp)
            }
            if (channel.kind == "thread" && threadParentChannel != null && threadParent != null) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { alsoSend = !alsoSend },
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(alsoSend, { alsoSend = it })
                    Text("Also send to ${threadParentChannel.name}", color = p.ink, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                var authorsOpen by remember { mutableStateOf(false) }
                Box {
                    Box(Modifier.size(48.dp).clickable(enabled = ownSenders.isNotEmpty() && !model.isPerson) { authorsOpen = true }
                        .semantics { contentDescription = "Send message as ${author?.shownName ?: "choose a sender"}" }, contentAlignment = Alignment.Center) {
                        if (author != null) ChatAvatar(author, 40.dp, "solid")
                        else Box(Modifier.size(32.dp).background(p.surface2, CircleShape), contentAlignment = Alignment.Center) { Text("?", color = p.ink3) }
                    }
                    if (authorsOpen) SendAsSheet(model, channel.id, ownSenders, author?.id,
                        onChoose = { selectedAuthor = it; authorsOpen = false }, onDismiss = { authorsOpen = false })
                }
                BasicTextField(draft, { draft = it }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    .background(p.surface2, RoundedCornerShape(24.dp)).semantics { contentDescription = "Message" },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = p.ink), maxLines = 4,
                    cursorBrush = SolidColor(p.accent), decorationBox = { inner ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.padding(start = 12.dp, end = 8.dp)) { ChatIcon(R.drawable.ic_emoji) }
                            Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                                if (draft.isEmpty()) Text(if (channel.kind == "thread") "Reply in thread" else "Message",
                                    color = p.ink3, style = MaterialTheme.typography.bodyLarge)
                                inner()
                            }
                            ChatIconButton(R.drawable.ic_attach, "Attachments, poll, content warning and audience") { moreOpen = true }
                        }
                    })
                val canSend = !busy && pendingPrivateReply == null && (draft.isNotBlank() || attachments.isNotEmpty()) &&
                    (if (draft.isBlank()) author != null && attachments.isNotEmpty()
                        else currentPreview?.authors?.isNotEmpty() == true && (currentPreview.text.isNotBlank() || attachments.isNotEmpty())) && (replyTo == null || target != null) &&
                    (audience != "members" || visibleTo.isNotEmpty()) &&
                    // in a private chat a sigil can't hand the message to someone it isn't kept to
                    (paneSides?.mine.isNullOrEmpty() || draft.isBlank() || currentPreview?.authors?.all { it in paneSides!!.mine } == true)
                Box(modifier = Modifier.size(48.dp).clip(CircleShape).background(if (canSend) p.accent else p.surface2)
                        .clickable(enabled = canSend) {
                            val speakerId = author?.id
                            busy = true
                            error = null
                            actions.launch {
                                try {
                                    val scope = "space:${space.id}"
                                    val attachmentIds = attachments.map { a -> sendAttachment(chorus, ctx, a, scope) }
                                    val payload = ChatCompose.payload(model, channel.id, speakerId, draft, cw,
                                        audience, visibleTo.toSet(), space.kind, replyTo = target?.id, attachmentIds = attachmentIds,
                                        accountId = senderAccount)
                                    if (pollOn) payload.put("poll", Polls.draft(pollOptions.toList(), pollMultiple).getOrThrow())
                                    followNext = true
                                    chorus.create("message.send", chorus.newId(), payload, scope = scope)
                                    val alsoTo = threadParentChannel?.takeIf { alsoSend && channel.kind == "thread" }
                                    val parent = threadParent
                                    if (alsoTo != null && parent != null) chorus.create("message.send", chorus.newId(),
                                        ChatCompose.payload(model, alsoTo.id, speakerId, draft, cw, audience, visibleTo.toSet(), space.kind,
                                            replyTo = parent.id, attachmentIds = attachmentIds, accountId = senderAccount), scope = scope)
                                    alsoSend = false
                                    pollOn = false; pollMultiple = false
                                    pollOptions.clear(); pollOptions.addAll(listOf("", ""))
                                    if (attachmentIds.isNotEmpty()) UploadWork.enqueue(ctx)
                                    attachments.clear()
                                    draft = ""
                                    cw = ""
                                    audience = "all"
                                    visibleTo = emptyList()
                                    replyTo = null
                                    carriedReply = null
                                    moreOpen = false
                                } catch (e: Exception) {
                                    error = e.message ?: "Could not send the message."
                                } finally {
                                    busy = false
                                }
                            }
                        }
                        .wrapContentSize(Alignment.Center)
                        .semantics { contentDescription = if (busy) "Sending message" else "Send to $audienceLabel" },
                    contentAlignment = Alignment.Center) { ChatIcon(R.drawable.ic_send, tint = if (canSend) p.onAccent else p.ink3) }
            }
        }
        }
        }
    }
        if (expanded && channel != null && space != null && !paneLocked) {
            VerticalDivider(Modifier.fillMaxHeight(), color = p.line)
            ChatInfoPane(chorus, channel, space, model, messages, library, directory[space.id], onSearch,
                notifyLevel = model.accountPrefs.channelNotify(channel.id, space.kind), onNotify = ::setNotify,
                onShowMessage = { jumpTo = it }, modifier = Modifier.width(260.dp), title = infoTitle(channel, space),
                subtitle = infoSubtitle(channel, space), connections = connections, foreign = foreignAuthors + privateCards,
                speakerId = replySpeakerId, onSpaceChanged = { refresh++ }, onOpenChannel = { id -> selectedChannel = id },
                onLeft = { selectedSpace = ""; selectedChannel = ""; refresh++ })
        }
    }
    }
    if (readerOpen) {
        var readerQuery by rememberSaveable { mutableStateOf("") }
        val readers = remember(model.active, readerQuery) {
            model.active.filter { member -> listOf(member.shownName, member.name, *member.sigils.toTypedArray()).any { it.contains(readerQuery.trim(), ignoreCase = true) } }
        }
        ModalBottomSheet(onDismissRequest = { readerOpen = false }, containerColor = p.surface,
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            InspectionDialogContent {
                Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).imePadding().padding(horizontal = 20.dp)) {
                    Text("Read as…", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(value = readerQuery, onValueChange = { readerQuery = it },
                        label = { Text("Search members") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
                    LazyColumn(Modifier.fillMaxWidth().weight(1f).selectableGroup()) {
                        item(key = "everyone") {
                            androidx.compose.material3.ListItem(
                                colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = p.surface),
                                headlineContent = { Text("Everyone present") },
                                trailingContent = { androidx.compose.material3.RadioButton(selected = viewingAs == null, onClick = null) },
                                modifier = Modifier.fillMaxWidth().selectable(selected = viewingAs == null, role = Role.RadioButton) { viewingAs = null; readerOpen = false })
                        }
                        items(readers, key = { it.id }) { member ->
                            androidx.compose.material3.ListItem(
                                colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = p.surface),
                                leadingContent = { Avatar(member.glyph, member.color, 36.dp, avatarBlob = member.avatarBlob) },
                                headlineContent = { Text(member.shownName) },
                                trailingContent = { androidx.compose.material3.RadioButton(selected = viewingAs == member.id, onClick = null) },
                                modifier = Modifier.fillMaxWidth().selectable(selected = viewingAs == member.id, role = Role.RadioButton) { viewingAs = member.id; readerOpen = false })
                        }
                        if (readers.isEmpty()) item { Text("No matching members", Modifier.padding(16.dp)) }
                    }
                    TextButton(onClick = { readerOpen = false }, modifier = Modifier.align(Alignment.End)) { Text("Done") }
                }
            }
        }
    }
    if (switchOpen) Switcher(chorus, model, if (expanded) channel?.id?.let { ChatSpeaker.frontOf(model, it) } else model.namedFronts?.bodyId) { switchOpen = false }
    editingMessageId?.let { id -> MessageEditDialog(chorus, model, id) { editingMessageId = null } }
    forwarding?.let { message ->
        ModalBottomSheet(onDismissRequest = { forwarding = null }, containerColor = p.surface) {
            Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                Text("Forward to…", color = p.ink, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                Text(ChatPresentation.preview(message), color = p.ink3, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2,
                    modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp))
                for (row in listRows.sortedByDescending { it.latest?.occurredAt ?: 0L }) {
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                        val source = space ?: return@clickable
                        val from = channel ?: return@clickable
                        if (ChatForward.asksFirst(source, row.space.id, row.channel.id, from.id, message)) forwardConfirm = row
                        else forward(message, row)
                    }.heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        ChannelDisc(row.channel, size = 40.dp)
                        Column(Modifier.weight(1f)) {
                            Text(row.title, color = p.ink, fontSize = 16.sp, lineHeight = 22.sp, maxLines = 1)
                            Text(spaceLabel(row.space, directory[row.space.id], chorus.device?.accountId.orEmpty()), color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
    forwardConfirm?.let { row ->
        AlertDialog(onDismissRequest = { forwardConfirm = null }, title = { Text("Forward outside this chat?") },
            text = { Text("This shares a snapshot of the message with ${row.title}. Its original audience doesn't follow it there.") },
            confirmButton = { TextButton(enabled = !busy, onClick = { forwarding?.let { forward(it, row) } }) { Text("Forward") } },
            dismissButton = { TextButton(onClick = { forwardConfirm = null }) { Text("Cancel") } })
    }
    deleting?.let { message ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete this message?") },
            text = { Text("It disappears for everyone in this chat. You can undo right after.") },
            confirmButton = { TextButton(onClick = { deleting = null; delete(message) }) { Text("Delete", color = p.danger) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
    if (notifyOpen && channel != null && space != null) {
        val level = model.accountPrefs.channelNotify(channel.id, space.kind)
        AlertDialog(onDismissRequest = { notifyOpen = false }, title = { Text("Notifications for ${channel.name}") }, text = {
            Column {
                for ((value, label) in listOf("all" to "All messages", "mentions" to "Mentions only", "none" to "Off (muted)")) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { setNotify(value); notifyOpen = false },
                        verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(level == value, { setNotify(value); notifyOpen = false })
                        Text(label, color = p.ink)
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { notifyOpen = false }) { Text("Done") } })
    }
    if (channelEditor) ChannelEditor(chorus, model, space?.id?.takeIf { space?.kind != "dm" },
        channel.takeIf { editChannel }, onDismiss = { channelEditor = false }, onSaved = { home, id ->
            selectedSpace = home; selectedChannel = id; conversationOpen = true; infoOpen = false; channelEditor = false
        })
    if (spaceAction == "new") {
        var choice by rememberSaveable { mutableStateOf("choose") }
        ModalBottomSheet(onDismissRequest = { directRequest?.cancel(); spaceAction = "" }, containerColor = p.surface,
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            InspectionDialogContent {
                Column(Modifier.fillMaxWidth().then(if (choice == "direct") Modifier.fillMaxHeight(0.9f) else Modifier)
                    .imePadding().then(if (choice == "direct") Modifier else Modifier.verticalScroll(rememberScrollState()))
                    .padding(horizontal = 20.dp).padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (choice != "choose") ChatIconButton(R.drawable.ic_back, "Back to new chat choices") { directRequest?.cancel(); choice = "choose" }
                        Text(when (choice) { "direct" -> "Direct message"; "shared" -> "New shared space"; else -> "New chat" },
                            style = MaterialTheme.typography.titleLarge)
                    }
                    if (choice == "choose") {
                        ChatCreationChoice(R.drawable.ic_thread, "New channel", "A place for a topic, plans or shared notes", prominent = true) {
                            spaceAction = ""; editChannel = false; channelEditor = true
                        }
                        ChatCreationChoice(R.drawable.ic_members, "Direct message", "Write to a member or a connected account") { choice = "direct" }
                        ChatCreationChoice(R.drawable.ic_globe, "New shared space", "Invite connected accounts to a group chat") { choice = "shared" }
                    } else if (choice == "direct") {
                        DirectMessagePicker(model.active.filter { it.createdByAccountId == null || it.createdByAccountId == chorus.device?.accountId },
                            connections, busy, syncStatus == Status.Live, error,
                            onMembers = { speaker, recipients ->
                                if (!busy) {
                                    busy = true; error = null
                                    directRequest = actions.launch {
                                        try {
                                            val latest = chorus.model.value
                                            val home = latest.spaces.firstOrNull { it.kind == "internal" }
                                                ?: throw IllegalStateException("Your internal space is not available yet.")
                                            val participants = garden.vayne.chorus.data.MemberDms.selected(latest.active,
                                                (listOf(speaker) + recipients).map { it.id }, chorus.device?.accountId.orEmpty())
                                            val existing = garden.vayne.chorus.data.MemberDms.existing(latest.channels, home.id, participants.map { it.id })
                                            val requestKey = listOf(home.id) + participants.map { it.id }.sorted()
                                            // A timeout/cancel may follow a durable write before its model refresh.
                                            // Retry that entity ID so a slow projection cannot create another DM.
                                            if (existing == null && pendingDmParticipants != requestKey) {
                                                pendingDmParticipants = requestKey; pendingDmId = chorus.newId()
                                            }
                                            val target = existing?.id ?: pendingDmId.also { id ->
                                                chorus.create("channel.create", id, garden.vayne.chorus.data.MemberDms.payload(home.id, participants), scope = "space:${home.id}")
                                            }
                                            withTimeout(15_000) { chorus.model.first { next -> next.channels.any { it.id == target && it.spaceId == home.id } } }
                                            // Seed the destination draft before opening; the source draft stays untouched.
                                            drafts.getOrPut(target) { ChatDraft() }.author.value = speaker.id
                                            selectedSpace = home.id; selectedChannel = target; viewingAs = speaker.id
                                            conversationOpen = true; infoOpen = false; spaceAction = ""
                                        } catch (_: TimeoutCancellationException) { error = "The conversation is not available yet. Try again after syncing."
                                        } catch (e: CancellationException) { throw e
                                        } catch (e: Exception) { error = e.message ?: "Could not start the message." }
                                        finally { busy = false }
                                    }
                                }
                            }, privateChats = !model.isPerson, onAccount = { speaker, account, keepPrivate ->
                                val dev = chorus.device
                                if (!busy && dev != null) {
                                    busy = true; error = null
                                    directRequest = actions.launch {
                                        try {
                                            garden.vayne.chorus.data.MemberDms.selected(chorus.model.value.active, listOf(speaker.id), dev.accountId)
                                            val targetSpace = Spaces.openDm(dev, account.id)
                                            val synced = withTimeout(15_000) { chorus.model.first { next ->
                                                next.spaces.any { it.id == targetSpace } && next.channels.any { it.spaceId == targetSpace && it.kind != "thread" }
                                            } }
                                            // a private chat lives in the DM (D-098): ours as this member, or a new one
                                            val targetChannel = if (keepPrivate) {
                                                val own = synced.members.mapTo(HashSet()) { it.id }
                                                ChatManage.existingPrivate(synced.channels, targetSpace, speaker.id, own) ?: chorus.newId().let { id ->
                                                    chorus.create("channel.create", id, ChatManage.privatePayload(targetSpace, speaker), scope = "space:$targetSpace")
                                                    withTimeout(15_000) { chorus.model.first { next -> next.channels.any { it.id == id } } }.channels.first { it.id == id }
                                                }
                                            } else synced.channels.first { it.spaceId == targetSpace && it.kind != "thread" && it.memberIds.isEmpty() }
                                            garden.vayne.chorus.data.MemberDms.selected(chorus.model.value.active, listOf(speaker.id), dev.accountId)
                                            drafts.getOrPut(targetChannel.id) { ChatDraft() }.author.value = speaker.id
                                            selectedSpace = targetSpace; selectedChannel = targetChannel.id
                                            viewingAs = null; conversationOpen = true; infoOpen = false; spaceAction = ""; refresh++
                                        } catch (_: TimeoutCancellationException) { error = "The conversation is not available yet. Try again after syncing."
                                        } catch (e: CancellationException) { throw e
                                        } catch (_: Exception) { error = "Could not start the message. Try again when connected." }
                                        finally { busy = false }
                                    }
                                }
                            })
                    } else {
                        Text("Messages show who is speaking to everyone in the space.", style = MaterialTheme.typography.bodySmall, color = p.ink3)
                        OutlinedTextField(newSpaceName, { newSpaceName = it.take(80) }, label = { Text("Shared space name") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth())
                        if (connections.isEmpty()) Text("Connect with an account in Friends before inviting them.", style = MaterialTheme.typography.bodyMedium, color = p.ink2)
                        for (account in connections) Row(Modifier.fillMaxWidth().clickable {
                            invitedAccounts = if (account.id in invitedAccounts) invitedAccounts - account.id else invitedAccounts + account.id
                        }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(account.id in invitedAccounts, onCheckedChange = { checked ->
                                invitedAccounts = if (checked) invitedAccounts + account.id else invitedAccounts - account.id
                            })
                            Text(account.shownName, style = MaterialTheme.typography.bodyMedium)
                        }
                        Button(enabled = !busy && newSpaceName.isNotBlank() && invitedAccounts.isNotEmpty(), onClick = {
                            val dev = chorus.device ?: return@Button
                            busy = true; error = null; spaceAction = ""
                            actions.launch {
                                try {
                                    selectedSpace = Spaces.createShared(dev, newSpaceName, invitedAccounts)
                                    selectedChannel = ""; conversationOpen = true; newSpaceName = ""; invitedAccounts = emptyList(); refresh++
                                } catch (_: Exception) { error = "Could not create the space. Try again when connected." }
                                finally { busy = false }
                            }
                        }) { Text("Create shared space") }
                    }
                    TextButton(onClick = { directRequest?.cancel(); spaceAction = "" }) { Text("Cancel") }
                }
            }
        }
    }
    if (spaceAction == "leave" && space != null) {
        AlertDialog(onDismissRequest = { spaceAction = "" }, title = { Text("Leave this space?") },
            text = { Text("You can be added back later.") },
            confirmButton = { TextButton(onClick = {
                val dev = chorus.device ?: return@TextButton
                busy = true; error = null; spaceAction = ""
                actions.launch {
                    try { Spaces.leave(dev, space.id); selectedSpace = ""; selectedChannel = ""; refresh++ }
                    catch (e: Exception) { error = e.message ?: "Could not leave the space." }
                    finally { busy = false }
                }
            }) { Text("Leave") } },
            dismissButton = { TextButton(onClick = { spaceAction = "" }) { Text("Cancel") } })
    }
    removing?.let { row ->
        // our own channels and members' DMs are deleted for the system; a DM with another account or a
        // shared channel is only cleared for us (they keep it; it comes back when someone writes)
        val own = row.space.kind == "internal"
        val name = if (row.channel.isMemberDm) "this chat" else if (row.space.kind == "dm") "your chat with ${row.title}" else "#${row.title}"
        AlertDialog(onDismissRequest = { removing = null },
            title = { Text(if (own) "Delete $name?" else "Clear $name?") },
            text = { Text(if (own) "It's deleted for everyone in your system, messages and all." else
                "It leaves your chats and its messages leave your view, on all your devices. It comes back if someone writes again; they keep everything.") },
            confirmButton = { TextButton(onClick = {
                removing = null; error = null
                actions.launch {
                    try {
                        if (own) chorus.create("channel.delete", row.channel.id, JSONObject(), scope = "space:${row.space.id}")
                        else chorus.create("pref.set", null, AccountPrefs.payload(ChatOrganize.HIDDEN + row.channel.id,
                            maxOf(System.currentTimeMillis(), row.latest?.occurredAt ?: 0L)))
                        if (row.channel.id in model.accountPrefs.chatPins) savePins(model.accountPrefs.chatPins - row.channel.id)
                        if (selectedChannel == row.channel.id) { selectedChannel = ""; conversationOpen = false }
                    } catch (e: Exception) { error = e.message ?: "Could not remove the chat." }
                }
            }) { Text(if (own) "Delete" else "Clear", color = LocalChorusPalette.current.danger) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } })
    }
}

private fun spaceLabel(space: ChatSpace, info: SpaceInfo?, me: String): String = when (space.kind) {
    "internal" -> if (space.name.isBlank() || space.name.equals("Home", ignoreCase = true)) "Home" else "Home · ${space.name}"
    "dm" -> "DM · ${info?.accounts?.firstOrNull { it.id != me }?.shownName ?: "Direct message"}"
    else -> space.name
}

@Composable
private fun ChatChip(label: String, selected: Boolean, action: () -> Unit) {
    val p = LocalChorusPalette.current
    Text(label, color = if (selected) p.accent else p.ink2, fontSize = 14.sp, lineHeight = 20.sp,
        modifier = Modifier.background(if (selected) p.surface2 else p.surface, RoundedCornerShape(20.dp))
            .clickable(onClick = action).heightIn(min = 48.dp).wrapContentSize(Alignment.Center).padding(horizontal = 12.dp, vertical = 8.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatMessageCard(message: ChatMessage, model: Model, foreignAuthors: Map<String, ForeignAuthor>,
    chorus: Chorus, unseenBy: List<String>, held: HeldMessage?, actingMemberId: String?, onReply: () -> Unit,
    onReplyPrivately: (() -> Unit)?, onEdit: (() -> Unit)?,
    reactions: List<ChatReaction> = emptyList(), onReact: ((String, Boolean) -> Unit)? = null,
    onForward: (() -> Unit)? = null, onDelete: (() -> Unit)? = null, onPin: (() -> Unit)? = null,
    thread: ThreadSummary? = null, onThread: (() -> Unit)? = null, interactive: Boolean = true,
    showSender: Boolean = true, showAvatar: Boolean = true, quote: ChatMessage? = null, onStage: () -> Unit) {
    val p = LocalChorusPalette.current
    val actions = rememberCoroutineScope()
    var revealed by rememberSaveable(message.id) { mutableStateOf(false) }
    var history by remember(message.id, message.text, message.cw) { mutableStateOf<List<MessageRevision>?>(null) }
    var historyError by remember(message.id) { mutableStateOf<String?>(null) }
    var showUnseen by rememberSaveable(message.id) { mutableStateOf(false) }
    var cancelBusy by remember(message.id) { mutableStateOf(false) }
    var cancelError by remember(message.id) { mutableStateOf<String?>(null) }
    var now by remember(held?.until) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(held?.until) {
        while (held != null) { delay(1_000); now = System.currentTimeMillis() }
    }
    val authors = message.authors.map { model.member(it)?.shownName ?: foreignAuthors[it]?.name ?: "Someone" }
        .joinToString(" & ").ifEmpty { "Someone" }
    val own = ChatPresentation.own(message, chorus.device?.accountId.orEmpty(), actingMemberId)
    val member = message.authors.firstOrNull()?.let(model::member)
    val tone = member?.let { tonesOf(it.color) }
    val presence = ChatPresentation.presence(model, message.channelId).firstOrNull { it.subjectId == member?.id && it.subjectType == "member" }
    val layer = ChatPresentation.layer(model, presence)
    var menuOpen by remember(message.id) { mutableStateOf(false) }
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(start = if (own) 36.dp else 0.dp),
        horizontalArrangement = if (own) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
        if (!own) {
            Box(Modifier.width(28.dp), contentAlignment = Alignment.BottomCenter) {
                if (showAvatar) {
                    if (member != null) ChatAvatar(member, 28.dp)
                    else Box(Modifier.size(28.dp).background(p.surface3, CircleShape), contentAlignment = Alignment.Center) {
                        Text(authors.take(1), color = p.ink2, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f, fill = false).widthIn(min = 64.dp, max = 360.dp)
            .then(if (reactions.isNotEmpty()) Modifier.width(IntrinsicSize.Max) else Modifier).heightIn(min = 48.dp)
            .background(if (own) p.accentSoft else p.surface2, RoundedCornerShape(
                topStart = if (!own && !showSender) 6.dp else 16.dp, topEnd = if (own && !showSender) 6.dp else 16.dp,
                bottomEnd = if (own && showAvatar) 6.dp else 16.dp, bottomStart = if (!own && showAvatar) 6.dp else 16.dp))
            .then(if (interactive) Modifier.combinedClickable(onClick = { menuOpen = true }, onLongClick = { menuOpen = true },
                onLongClickLabel = "Message actions") else Modifier)
            .padding(horizontal = 11.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (showSender) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(authors, color = tone?.name ?: p.ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp,
                modifier = Modifier.weight(1f, fill = false))
            if (layer != null) Text(layer, color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp)
        }
        if (message.replyTo != null) {
            val quoted = quote?.authors?.firstOrNull()?.let(model::member)
            val quoteColor = quoted?.let { tonesOf(it.color).name } ?: p.accent
            Row(Modifier.fillMaxWidth().background(quoteColor.copy(alpha = .10f), RoundedCornerShape(6.dp))) {
                Box(Modifier.width(2.dp).heightIn(min = 38.dp).background(quoteColor))
                Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(quote?.authors?.joinToString(" & ") { model.member(it)?.shownName ?: foreignAuthors[it]?.name ?: "Someone" }
                        ?: "Reply", color = quoteColor, fontSize = 12.sp, lineHeight = 16.sp)
                    Text(if (quote != null) ChatPresentation.preview(quote) else "Message unavailable in this view",
                        color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2)
                }
            }
        }
        for (item in message.forward) {
            val source = item.authors.firstOrNull()?.let(model::member)
            val sourceColor = source?.let { tonesOf(it.color).name } ?: p.accent
            Row(Modifier.fillMaxWidth().background(sourceColor.copy(alpha = .10f), RoundedCornerShape(6.dp))) {
                Box(Modifier.width(2.dp).heightIn(min = 38.dp).background(sourceColor))
                Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text("Forwarded from " + item.authors.joinToString(" & ") { model.member(it)?.shownName ?: foreignAuthors[it]?.name ?: "Someone" }
                        .ifEmpty { "Someone" }, color = sourceColor, fontSize = 12.sp, lineHeight = 16.sp)
                    Text(listOfNotNull(item.channelName, ChatPresentation.dateLabel(item.occurredAt)).joinToString(" · "),
                        color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp)
                }
            }
            if (item.text.isNotBlank() && (message.cw == null || revealed)) RichBody(item.text, item.entities,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 20.sp),
                onPlainClick = { if (interactive && held == null) menuOpen = true },
                onLongClick = if (interactive && held == null) ({ menuOpen = true }) else null)
        }
        if (held != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Slow mode: sending in ${((held.until - now).coerceAtLeast(0L) + 999L) / 1000L} s",
                    color = p.ink2, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f))
                Text(if (cancelBusy) "Canceling…" else "Cancel", color = p.accent, fontSize = 12.sp, lineHeight = 16.sp,
                    modifier = Modifier.clickable(enabled = !cancelBusy) {
                        cancelBusy = true; cancelError = null
                        actions.launch {
                            try {
                                if (!chorus.cancelHeld(message.id)) cancelError = "This message is already sending."
                            } catch (e: Exception) { cancelError = e.message ?: "Could not cancel this message." }
                            finally { cancelBusy = false }
                        }
                    }.heightIn(min = 48.dp).wrapContentSize(Alignment.Center).semantics { contentDescription = "Cancel held message" })
            }
            if (cancelError != null) Text(cancelError.orEmpty(), color = p.danger, fontSize = 12.sp, lineHeight = 16.sp)
        }
        if (showUnseen && unseenBy.isNotEmpty()) Text("Not seen by " + unseenBy.map {
            model.member(it)?.shownName ?: "Someone"
        }.joinToString(", "), color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp)
        val tags = listOfNotNull(

            "Only this system".takeIf { message.visibilityMode == "system_only" },
            "Chosen members".takeIf { message.visibilityMode == "members" },
        )
        if (tags.isNotEmpty()) Text(tags.joinToString(" · "), color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp)
        if (message.cw != null) {
            Text("Content warning: ${message.cw} · ${if (revealed) "Hide" else "Show"}", color = p.accent,
                modifier = Modifier.clickable { revealed = !revealed }.heightIn(min = 48.dp).wrapContentSize(Alignment.CenterStart))
        }
        if (message.cw == null || revealed) {
            if (message.text.isNotBlank() && message.text != message.forward.joinToString("\n") { it.text })
                RichBody(message.text, message.entities, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 20.sp),
                    onPlainClick = { if (interactive && held == null) menuOpen = true },
                    onLongClick = if (interactive && held == null) ({ menuOpen = true }) else null)
            for (attachment in message.attachments) {
                ChatAttachmentView(attachment, chorus, authors, message.occurredAt)
            }
            // Same resolved acting speaker as the composer: explicit selection or channel default.
            PollCard(chorus, model, message.id, garden.vayne.chorus.data.Polls.chatMember(model, actingMemberId))
            if (held == null && message.edited) {
                TextButton(onClick = {
                    if (history != null) history = null else actions.launch {
                        try { history = chorus.revisions(message.id); historyError = null }
                        catch (e: Exception) { historyError = e.message ?: "Could not load edit history." }
                    }
                }) { Text(if (history == null) "(edited) · View history" else "Hide edit history") }
                if (historyError != null) Text(historyError.orEmpty(), color = p.danger)
                history?.forEach { revision ->
                    var revisionRevealed by rememberSaveable(message.id, revision.rev) {
                        mutableStateOf(revision.contentWarning == null)
                    }
                    Column(Modifier.fillMaxWidth().background(p.surface2).padding(8.dp)) {
                        Text("${if (revision.original) "Original" else "Edit ${revision.rev}"} · " +
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(revision.at)),
                            color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp)
                        if (revision.contentWarning != null) Text(
                            "Content warning: ${revision.contentWarning} · ${if (revisionRevealed) "Hide" else "Show"}",
                            color = p.accent, modifier = Modifier.clickable { revisionRevealed = !revisionRevealed })
                        if (revisionRevealed) Text(revision.text, color = p.ink)
                    }
                }
            }
        }
        if (thread != null && thread.replies > 0) Row(Modifier.heightIn(min = 48.dp)
            .then(if (onThread != null) Modifier.clickable(onClickLabel = "Open thread", onClick = onThread) else Modifier),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                for (id in thread.lastRepliers) model.member(id)?.let { ChatAvatar(it, 20.dp) }
            }
            Text(ChatThreads.repliesLabel(thread.replies), color = p.accent, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(Modifier.then(if (reactions.isNotEmpty()) Modifier.fillMaxWidth() else Modifier.align(Alignment.End)),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (reactions.isNotEmpty()) ReactionChips(reactions, actingMemberId, Modifier.weight(1f),
                { id -> model.member(id)?.shownName ?: foreignAuthors[id]?.name ?: "Someone" }, onReact.takeIf { held == null })
            if (message.pinned) androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_pin), "Pinned",
                Modifier.size(14.dp), tint = p.ink3)
            Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.occurredAt)), color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp)
            if (held != null) ChatIcon(R.drawable.ic_history, "Message held for slow mode")
        }
        }
    }
    if (menuOpen) Dialog(onDismissRequest = { menuOpen = false }) {
        var allEmoji by remember(message.id) { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState())
            .semantics { contentDescription = "Message actions" },
            horizontalAlignment = if (own) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (held == null && onReact != null) ReactionPicker(reactions, actingMemberId, allEmoji, { allEmoji = !allEmoji }) { emoji, on ->
                menuOpen = false; onReact(emoji, on)
            }
            Column(Modifier.widthIn(min = 120.dp, max = 300.dp).background(if (own) p.accentSoft else p.surface2, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(authors, color = tone?.name ?: p.ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp)
                Text(ChatPresentation.preview(message), color = p.ink, maxLines = 4, fontSize = 15.sp, lineHeight = 20.sp)
            }
        Surface(shape = RoundedCornerShape(12.dp), color = p.surface, modifier = Modifier.widthIn(min = 220.dp, max = 300.dp)) {
            Column(Modifier.padding(vertical = 4.dp)) {
                val seen = model.readMarks[message.channelId].orEmpty().filter { mark ->
                    mark.member.isNotEmpty() && !ReadTracking.needsMark(message.occurredAt, message.id, listOf(mark), mark.member)
                }.mapNotNull { model.member(it.member) }
                if (held == null && seen.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (reader in seen.take(3)) ChatAvatar(reader, 24.dp)
                        Text("Seen by " + seen.joinToString(", ") { it.shownName }, color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                }
                HorizontalDivider(color = p.line)
                if (held == null) ChatActionRow(R.drawable.ic_reply, "Reply") { menuOpen = false; onReply() }
                if (onThread != null) ChatActionRow(R.drawable.ic_thread, if (thread != null) "Open thread" else "Reply in thread") {
                    menuOpen = false; onThread()
                }
                if (held == null && onReplyPrivately != null) ChatActionRow(R.drawable.ic_lock, "Reply privately") { menuOpen = false; onReplyPrivately() }
                if (message.cw == null || revealed) ChatActionRow(R.drawable.ic_copy, "Copy text") {
                    val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Message", message.text)); menuOpen = false
                }
                if (onPin != null) ChatActionRow(if (message.pinned) R.drawable.ic_unpin else R.drawable.ic_pin,
                    if (message.pinned) "Unpin" else "Pin") { menuOpen = false; onPin() }
                if (onForward != null) ChatActionRow(R.drawable.ic_forward, "Forward") { menuOpen = false; onForward() }
                if (onEdit != null && (message.cw == null || revealed)) ChatActionRow(R.drawable.ic_edit, "Edit") { menuOpen = false; onEdit() }
                if (held == null) ChatActionRow(R.drawable.ic_layers, "Select in stage mode") { menuOpen = false; onStage() }
                if (held == null && unseenBy.isNotEmpty()) ChatActionRow(R.drawable.ic_members, "Not seen by…") { showUnseen = !showUnseen; menuOpen = false }
                if (onDelete != null) ChatActionRow(R.drawable.ic_delete, "Delete", danger = true) { menuOpen = false; onDelete() }
            }
        }
        }
    }
}

/** F5.6: the quick reactions above a long-pressed message, and every other emoji behind the chevron. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReactionPicker(reactions: List<ChatReaction>, member: String?, expanded: Boolean,
    onExpand: () -> Unit, onPick: (String, Boolean) -> Unit) {
    val p = LocalChorusPalette.current
    @Composable fun EmojiButton(emoji: String) {
        val chosen = ChatReactions.chosen(reactions, emoji, member)
        Box(Modifier.size(44.dp).clip(CircleShape).background(if (chosen) p.accentSoft else Color.Transparent)
            .clickable(enabled = member != null) { onPick(emoji, !chosen) }
            .semantics { contentDescription = if (chosen) "Remove $emoji" else "React $emoji"; selected = chosen },
            contentAlignment = Alignment.Center) { Text(emoji, fontSize = 24.sp, lineHeight = 28.sp) }
    }
    Surface(shape = RoundedCornerShape(24.dp), color = p.surface) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                for (emoji in ChatReactions.QUICK) EmojiButton(emoji)
                Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onExpand)
                    .semantics { contentDescription = if (expanded) "Fewer reactions" else "More reactions" },
                    contentAlignment = Alignment.Center) {
                    Box(Modifier.size(28.dp).background(p.surface2, CircleShape).rotate(if (expanded) 180f else 0f),
                        contentAlignment = Alignment.Center) { ChatIcon(R.drawable.ic_down, tint = p.ink2) }
                }
            }
            if (expanded) FlowRow(Modifier.width(308.dp)) { for (emoji in ChatReactions.MORE) EmojiButton(emoji) }
            if (member == null) Text("Choose who is speaking to react.", color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp,
                modifier = Modifier.padding(8.dp))
        }
    }
}

/** "❤️ 2" under a message: tap adds or removes the acting member's reaction, hold shows who. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun ReactionChips(reactions: List<ChatReaction>, member: String?, modifier: Modifier,
    nameOf: (String) -> String, onToggle: ((String, Boolean) -> Unit)?) {
    val p = LocalChorusPalette.current
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (r in reactions) {
            val mine = member != null && member in r.memberIds
            var who by remember(r.emoji) { mutableStateOf(false) }
            val names = r.memberIds.joinToString(", ", transform = nameOf)
            Box(Modifier.heightIn(min = 48.dp).combinedClickable(
                onClickLabel = if (mine) "Remove your reaction" else "Add this reaction",
                onClick = { if (onToggle != null && member != null) onToggle(r.emoji, !mine) else who = true },
                onLongClick = { who = true }, onLongClickLabel = "Who reacted")
                .semantics { contentDescription = "${r.emoji} ${r.memberIds.size}: $names"; selected = mine },
                contentAlignment = Alignment.Center) {
                Text("${r.emoji} ${r.memberIds.size}", color = if (mine) p.accent else p.ink2, fontSize = 13.sp, lineHeight = 18.sp,
                    modifier = Modifier.background(if (mine) p.accentSoft else p.surface3, RoundedCornerShape(12.dp))
                        .then(if (mine) Modifier.border(1.dp, p.accent, RoundedCornerShape(12.dp)) else Modifier)
                        .padding(horizontal = 8.dp, vertical = 2.dp))
                DropdownMenu(who, { who = false }) {
                    Text("${r.emoji}  $names", color = p.ink, fontSize = 14.sp, lineHeight = 20.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
internal fun ChatAttachmentView(attachment: ChatAttachment, chorus: Chorus, who: String = "", at: Long = 0L) {
    val p = LocalChorusPalette.current
    val ctx = LocalContext.current
    val device = chorus.device
    val image = attachment.mime.startsWith("image/")
    val scope = rememberCoroutineScope()
    var opening by remember(attachment.id) { mutableStateOf(false) }
    var openError by remember(attachment.id) { mutableStateOf<String?>(null) }
    var opened by rememberSaveable(attachment.id) { mutableStateOf(false) }
    var viewing by remember(attachment.id) { mutableStateOf(false) }
    if (attachment.spoiler && !opened) {
        Text("Spoiler attachment · Reveal", color = p.accent, fontSize = 13.sp, lineHeight = 18.sp,
            modifier = Modifier.clickable { opened = true }.heightIn(min = 48.dp).wrapContentSize(Alignment.CenterStart))
        return
    }
    // photos sit in the bubble (F5.2); the original opens full screen
    if (image) {
        val hash = attachment.thumbHash ?: attachment.blobHash
        val bitmap by produceState<android.graphics.Bitmap?>(null, hash, device?.session) {
            value = withContext(Dispatchers.IO) { Blobs.image(ctx.applicationContext, hash, device) }
        }
        val shown = bitmap
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.surface3)
            .clickable(onClickLabel = "Open photo") { viewing = true }
            .semantics { contentDescription = attachment.altText.ifBlank { attachment.filename } },
            contentAlignment = Alignment.Center) {
            if (shown == null) Text("Loading image, or unavailable offline.", color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp,
                modifier = Modifier.padding(24.dp))
            else Image(shown.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio((shown.width.toFloat() / shown.height.coerceAtLeast(1)).coerceIn(1f, 2.5f)))
        }
        if (attachment.altText.isNotBlank()) Text(attachment.altText, color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp)
        if (attachment.spoiler) Text("Hide image", color = p.accent, fontSize = 12.sp, lineHeight = 16.sp,
            modifier = Modifier.clickable { opened = false }.heightIn(min = 48.dp).wrapContentSize(Alignment.CenterStart))
        if (viewing) MediaViewer(attachment, chorus, who, at, null, onClose = { viewing = false })
        return
    }
    Row(Modifier.fillMaxWidth().background(p.surface3, RoundedCornerShape(10.dp)).clickable(enabled = !opening, onClickLabel = "Open file") {
        opening = true
        openError = null
        scope.launch {
            openError = openAttachment(ctx, chorus, attachment)
            opening = false
        }
    }.heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        ChatIcon(R.drawable.ic_file, tint = p.accent)
        Column(Modifier.weight(1f)) {
            Text(attachment.filename, color = p.ink, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1)
            Text(if (opening) "Opening…" else garden.vayne.chorus.data.ChatLibrary.size(attachment.size), color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
    if (attachment.altText.isNotBlank()) Text(attachment.altText, color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp)
    if (openError != null) Text(openError.orEmpty(), color = p.danger, fontSize = 12.sp, lineHeight = 16.sp)
    if (attachment.spoiler && !image) {
        Text("Hide attachment", color = p.accent, fontSize = 12.sp, lineHeight = 16.sp,
            modifier = Modifier.clickable { opened = false })
    }
}

/** Fetch (or reuse) an attachment and hand it to another app: an error to show, or null once it opened. */
internal suspend fun openAttachment(ctx: android.content.Context, chorus: Chorus, attachment: ChatAttachment): String? {
    val file = withContext(Dispatchers.IO) { Blobs.file(ctx.applicationContext, attachment.blobHash, chorus.device) }
        ?: return "File unavailable offline or download failed."
    return try {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, attachment.mime.ifBlank { "application/octet-stream" })
            clipData = ClipData.newUri(ctx.contentResolver, attachment.filename, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(intent, "Open ${attachment.filename}"))
        null
    } catch (_: ActivityNotFoundException) { "No app can open this file type."
    } catch (_: SecurityException) { "Could not share this file."
    } catch (_: IllegalArgumentException) { "Could not share this file." }
}

/** A file picked for the next message: named and described, not yet copied. */
internal data class PendingAttachment(val uri: Uri, val name: String, val mime: String, val alt: String = "", val spoiler: Boolean = false) {
    companion object {
        fun of(ctx: android.content.Context, uri: Uri): PendingAttachment {
            val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: uri.lastPathSegment ?: "file"
            return PendingAttachment(uri, name, ctx.contentResolver.getType(uri) ?: "application/octet-stream")
        }
    }
}

/**
 * Copy one picked file into the upload queue (with a thumbnail for images) and record its
 * attachment; the message that lists it is written right after, and [UploadWork] sends the bytes.
 */
internal suspend fun sendAttachment(chorus: Chorus, ctx: android.content.Context, a: PendingAttachment,
    scope: String? = null, opKind: String = "attachment.create"): String {
    val account = chorus.device?.accountId ?: throw IllegalStateException("Not signed in.")
    val staged = withContext(Dispatchers.IO) {
        val input = ctx.contentResolver.openInputStream(a.uri) ?: throw IllegalStateException("Can't read ${a.name}.")
        Blobs.stage(ctx, input, a.mime, account)
    }
    val thumb = if (a.mime.startsWith("image/")) withContext(Dispatchers.IO) {
        Blobs.pendingFile(ctx, staged.hash)?.let { Blobs.thumbnail(ctx, it, account) }
    } else null
    val id = chorus.newId()
    val payload = JSONObject().put("blob_hash", staged.hash).put("filename", a.name).put("mime", staged.mime)
        .put("size", staged.size).put("alt_text", a.alt.trim()).put("is_spoiler", a.spoiler)
    if (thumb != null) payload.put("thumb_blob_hash", thumb.hash)
    if (scope == null) chorus.create(opKind, id, payload)
    else chorus.create(opKind, id, payload, scope = scope)
    return id
}
