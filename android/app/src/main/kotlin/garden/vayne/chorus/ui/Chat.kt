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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Surface
import androidx.compose.material3.IconButton
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
import garden.vayne.chorus.data.SearchAncestors
import garden.vayne.chorus.data.SearchJump
import garden.vayne.chorus.data.SearchVisibility
import garden.vayne.chorus.data.Api
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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun Chat(chorus: Chorus, model: Model, requestedSpace: String? = null,
    requestedChannel: String? = null, searchHit: SearchDocument? = null,
    onStageCapture: (Boolean) -> Unit = {},
    onDismissSearchHit: () -> Unit = {}, sharedDraft: SharedDraft? = null,
    sharedChannel: String? = null, onShareConsumed: () -> Unit = {}, onSearch: () -> Unit = {},
    active: Boolean = true, onFocusedRoute: (Boolean) -> Unit = {}, onOpenConsumed: () -> Unit = {},
    /** A link to a message (D-109): open its chat at it. */
    requestedMessage: String? = null,
    /** A face in a message opens its profile in the Journal (owner, 2026-11-06): our member's id, or
     * "<account> <member>" for another account's, whose server read decides what shows (D-101). */
    onOpenProfile: (String) -> Unit = {}) {
    val p = LocalChorusPalette.current
    val heldMessages by chorus.heldMessages.collectAsState()
    val syncStatus by chorus.status.collectAsState()
    var searchHere by rememberSaveable { mutableStateOf(false) }
    var infoOpen by rememberSaveable { mutableStateOf(false) }
    var readerOpen by remember { mutableStateOf(false) }
    var conversationOpen by rememberSaveable { mutableStateOf(false) }
    var switchOpen by remember { mutableStateOf(false) }
    var headerMenu by remember { mutableStateOf(false) }
    // a message to bring into view (from chat info, a reply or a quote), consumed by the list
    var jumpTo by remember { mutableStateOf<String?>(null) }
    var flashId by remember { mutableStateOf<String?>(null) }
    val expanded = LocalConfiguration.current.screenWidthDp >= 840
    var selectedSpace by rememberSaveable { mutableStateOf("") }
    var selectedChannel by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(requestedSpace, requestedChannel, searchHit?.id, sharedDraft?.id, requestedMessage) {
        if (requestedSpace != null || requestedChannel != null || searchHit != null || sharedDraft != null) {
            if (requestedSpace != null) selectedSpace = requestedSpace
            if (requestedChannel != null) selectedChannel = requestedChannel
            else if (requestedSpace != null) selectedChannel = ""
            if (requestedMessage != null) jumpTo = requestedMessage
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
    // the attach sheet's page: "" for photos and tiles, or "poll", "cw", "audience", "speaker"
    var attachPage by rememberSaveable { mutableStateOf("") }
    var editingFile by remember { mutableStateOf<Int?>(null) }
    var followNext by remember { mutableStateOf(false) }
    var audience by composer.audience
    var visibleTo by composer.visibleTo
    var replyTo by composer.replyTo
    var carriedReply by remember { mutableStateOf<ChatMessage?>(null) }
    var pendingPrivateReply by remember { mutableStateOf<PendingPrivateReply?>(null) }
    var editingMessageId by rememberSaveable { mutableStateOf<String?>(null) }
    var forwarding by remember { mutableStateOf<ChatMessage?>(null) }
    // quote, reply elsewhere and select several (SPEC §5.3, D-048; web parity)
    var quoting by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    // a part of one quoted message (UTF-16 indices); null: all of it
    var quotePart by remember { mutableStateOf<IntRange?>(null) }
    var quotePicking by remember { mutableStateOf<ChatMessage?>(null) }
    var replyIn by remember { mutableStateOf<ChatMessage?>(null) }
    var selectedIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var bundleForward by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
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
    fun setSpeakerDefault(mode: String, memberId: String? = null, members: List<String> = emptyList()) {
        val id = channel?.id ?: return
        if (busy) return
        busy = true; error = null
        actions.launch {
            try {
                chorus.create("pref.set", null, ChatSpeaker.payload(id, mode, memberId, members))
                selectedAuthor = ""
            }
            catch (e: Exception) { error = e.message ?: "Could not save the speaker default." }
            finally { busy = false }
        }
    }
    val clearedAt = channel?.id?.let { model.accountPrefs.hiddenChats[it] }
    // someone's private chat that isn't ours to look at now (D-097, D-098): its words stay out and
    // nothing is marked read
    val jumpSession=chorus.device?.session
    val history = remember(channel?.id,jumpSession) { ChatHistory() }
    history.bind(model,syncStatus,chorus.device?.accountId.orEmpty())
    DisposableEffect(history) { onDispose { history.latest() } }
    val jumpTarget=jumpTo ?: history.target
    LaunchedEffect(jumpTarget,model,channel?.id,jumpSession,syncStatus) {
        val id=jumpTarget ?: return@LaunchedEffect
        val c=channel ?: return@LaunchedEffect
        val dev=chorus.device ?: return@LaunchedEffect
        val request=history.begin(id)
        try {
            // Ordinary/local targets never wait for an unrelated thread's missing parent.
            val dependencies=if(syncStatus==Status.Live) SearchAncestors.load(model,dev,listOf(c.id)) else emptyMap()
            val rows=SearchJump.load(model,dev.accountId,c.id,id,dependencies,
                local={view->chorus.searchWindow(c.id,id,view)},remote={
                    if(syncStatus!=Status.Live) emptyList() else {
                        val target=SearchVisibility.parseCard(Api.call("GET",dev.base,"/messages/$id",null,dev.session))
                        val page=Api.call("GET",dev.base,"/channels/${c.id}/messages?around=$id&limit=99",null,dev.session).getJSONArray("items")
                        (0 until page.length()).map {SearchVisibility.parseCard(page.getJSONObject(it))}.filter {it.id!=target.id} + target
                    }
                })
            if(history.owns(request)) {
                if(rows.messages.isNotEmpty()) history.loaded(request,rows)
                else {error="That message isn't available here.";jumpTo=null;history.latest()}
            }
        } catch(e:CancellationException) {throw e}
        catch(_:Exception) {if(history.owns(request)) {error="That message isn't available here.";jumpTo=null;history.latest()}}
    }
    val loadedHistory=history.window(model)
    val jumpVisibility=remember(model,loadedHistory) {SearchVisibility(model,chorus.device?.accountId.orEmpty(),ancestors=loadedHistory?.ancestors.orEmpty())}
    val shownNow = remember(model, viewingAs, loadedHistory) {
        val base=ChatOrganize.openable(model,viewingAs,chorus.device?.accountId.orEmpty())
        val byChannel=model.channels.associateBy {it.id}
        val guard:(String?)->Boolean={id -> base(id) ||
            (loadedHistory!=null && byChannel[id]?.kind=="thread" && loadedHistory.messages.any {it.channelId==id && jumpVisibility.message(it)})}
        guard
    }
    val locked = channel != null && !shownNow(channel.id)
    val messages = if (locked) emptyList() else history.messages(model,model.chatMessages[channel?.id].orEmpty()).filter {
        (loadedHistory==null || jumpVisibility.message(it)) &&
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
        selectedIds = emptyList()
        quoting = emptyList(); quotePart = null; quotePicking = null
        alsoSend = false
        carriedReply = null
        attachPage = ""; editingFile = null
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

    val ownIds = model.knownMemberIds
    // other accounts' members in our DMs, from those DMs' author cards: the names and pictures of
    // whom a private chat is kept to (D-098), and the person at the other end of each DM
    var privateCards by remember { mutableStateOf<Map<String, ForeignAuthor>>(emptyMap()) }
    val needCards = model.spaces.filter { it.kind == "dm" }.map { it.id }.sorted()
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
            if (!ChatOrganize.privateVisible(candidate, home, model.current, viewingAs, prefs.hidePrivate, ownIds,
                    inside = { garden.vayne.chorus.data.Subsystems.including(model, it) })) return@mapNotNull null
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
            val partnerAccount = directory[home.id]?.accounts?.firstOrNull { it.id != chorus.device?.accountId }
            val partner = partnerAccount?.shownName
            // a private chat (D-098): titled by their member on their side, by the friend on ours
            val sides = ChatManage.sides(candidate, home, ownIds)
            val siblings = model.channels.count { it.spaceId == home.id && it.kind != "thread" }
            val title = if (sides != null) sides.theirs.mapNotNull { privateCards[it]?.name ?: foreignAuthors[it]?.name }
                    .joinToString(" & ").ifEmpty { partner ?: candidate.name }
                else if (home.kind == "dm") partner ?: home.name
                else if (home.kind == "shared" && siblings == 1) home.name else candidate.name
            val latest = visible.lastOrNull()
            val sender = latest?.authors?.joinToString(" & ") { id -> model.member(id)?.shownName ?: foreignAuthors[id]?.name
                ?: directory[home.id]?.accounts?.find { it.id == latest.accountId }?.shownName ?: "Someone" }
            ChatListRow(candidate, home, title, latest, unread, sender,
                muted = model.accountPrefs.channelNotify(candidate.id, home.kind) == "none", pinned = candidate.id in prefs.chatPins,
                private = sides != null || (home.kind == "internal" && candidate.kind == "text" && candidate.memberIds.isNotEmpty()),
                whose = sides?.mine?.mapNotNull { model.member(it)?.shownName }?.joinToString(" & ")?.ifEmpty { null },
                archived = ChatOrganize.archived(prefs.archivedChats[candidate.id], latest?.occurredAt),
                icon = if (sides != null) sides.theirs.firstNotNullOfOrNull { privateCards[it] ?: foreignAuthors[it] }?.member
                    else if (home.kind == "dm" && partnerAccount != null) ChatPresentation.dmIcon(partnerAccount, privateCards.values) else null,
                grouped = sides == null && ChatOrganize.groupsInto(home.kind, siblings))
        }.let { rows -> ChatOrganize.order(rows, prefs.chatPins, { it.channel.id }, { it.latest?.occurredAt ?: 0L }, { it.title }) }
    }
    var arrangingPins by rememberSaveable { mutableStateOf(false) }
    // the chat folders screen (D-113): "" for its list, or "new", "new:<chat>" or a folder to edit
    var foldersAt by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<ChatListRow?>(null) }
    fun saveFolders(settings: garden.vayne.chorus.data.ChatFolders.Settings) {
        actions.launch {
            try { chorus.create("pref.set", null, garden.vayne.chorus.data.ChatFolders.payload(settings)) }
            catch (e: Exception) { error = e.message ?: "Could not save chat folders." }
        }
    }
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
            // archiving is when, so a newer message brings the chat back on every device (D-111)
            "archive" -> actions.launch {
                val value: Any = if (row.archived) org.json.JSONObject.NULL
                    else ChatOrganize.archiveAt(System.currentTimeMillis(), row.latest?.occurredAt)
                try { chorus.create("pref.set", null, AccountPrefs.payload(ChatOrganize.ARCHIVED + row.channel.id, value)) }
                catch (e: Exception) { error = e.message ?: "Could not archive this chat." }
            }
        }
    }
    // a space's entry acts on all its channels at once: one pins write, an archive per channel (D-111)
    val spaceRowsAction: (List<ChatListRow>, String) -> Unit = { rows, action ->
        val ids = rows.map { it.channel.id }
        when (action) {
            "pin" -> savePins(model.accountPrefs.chatPins + ids.filter { it !in model.accountPrefs.chatPins })
            "unpin" -> savePins(model.accountPrefs.chatPins - ids.toSet())
            "archive" -> actions.launch {
                try { for (row in rows) chorus.create("pref.set", null, AccountPrefs.payload(ChatOrganize.ARCHIVED + row.channel.id,
                    ChatOrganize.archiveAt(System.currentTimeMillis(), row.latest?.occurredAt))) }
                catch (e: Exception) { error = e.message ?: "Could not archive this space." }
            }
        }
    }
    val openChat: (ChatListRow) -> Unit = { row ->
        selectedSpace = row.space.id; selectedChannel = row.channel.id; conversationOpen = true
        carriedReply = null; pendingPrivateReply = null; moreOpen = false
    }
    // a snapshot plus copies of its files in the target scope, then the reader follows it there
    // several messages as one bundle (D-048), each still ours to see when it goes
    fun forwardBundle(items: List<ChatMessage>, row: ChatListRow) {
        val live = chorus.model.value
        val account = chorus.device?.accountId.orEmpty()
        fun stillHere(m: List<ChatMessage>) = m.all { ChatOrganize.actionMessage(chorus.model.value, it.id, viewingAs, account) == it } &&
            ChatOrganize.shown(chorus.model.value, viewingAs)(row.channel.id)
        if (items.isEmpty() || !stillHere(items)) { bundleForward = emptyList(); error = "These messages or that chat are no longer available."; return }
        val from = live.channels.firstOrNull { it.id == items.first().channelId } ?: return
        val speaker = replySpeakerId ?: run { error = "Choose who is speaking first."; return }
        if (busy) return
        busy = true; error = null
        actions.launch {
            try {
                val scope = "space:${row.space.id}"
                val ids = items.flatMap { it.attachments }.map { a ->
                    check(stillHere(items)) { "These messages or that chat are no longer available." }
                    chorus.newId().also { id -> chorus.create("attachment.create", id, ChatForward.attachmentCopy(a), scope = scope) }
                }
                check(stillHere(items)) { "These messages or that chat are no longer available." }
                chorus.create("message.forward", chorus.newId(), ChatForward.bundle(row.channel.id, speaker, items, from.name, ids), scope = scope)
                bundleForward = emptyList()
                openChat(row)
                history.sent(); jumpTo = null; followNext = true
            } catch (e: Exception) { error = e.message ?: "Could not forward the messages." }
            finally { busy = false }
        }
    }
    fun forward(message: ChatMessage, row: ChatListRow) {
        val live = chorus.model.value
        val current = ChatOrganize.actionMessage(live, message.id, viewingAs, chorus.device?.accountId.orEmpty())
        if (current != message || !ChatOrganize.shown(live, viewingAs)(row.channel.id)) {
            forwarding = null; forwardConfirm = null; error = "This message or chat is no longer available."
            return
        }
        val from = live.channels.firstOrNull { it.id == message.channelId } ?: return
        val speaker = replySpeakerId ?: run { error = "Choose who is speaking first."; return }
        if (busy) return
        busy = true; error = null
        actions.launch {
            try {
                val scope = "space:${row.space.id}"
                fun checkAccess() {
                    val latest = chorus.model.value
                    check(ChatOrganize.actionMessage(latest, message.id, viewingAs, chorus.device?.accountId.orEmpty()) == message &&
                        ChatOrganize.shown(latest, viewingAs)(row.channel.id)) { "This message or chat is no longer available." }
                }
                checkAccess()
                val ids = message.attachments.map { a ->
                    checkAccess()
                    chorus.newId().also { id -> chorus.create("attachment.create", id, ChatForward.attachmentCopy(a), scope = scope) }
                }
                checkAccess()
                chorus.create("message.forward", chorus.newId(), ChatForward.payload(row.channel.id, speaker, message, from.name, ids), scope = scope)
                forwarding = null; forwardConfirm = null
                openChat(row)
                history.sent(); jumpTo = null; followNext = true
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
    val listPreviews = listRows.mapNotNull { row ->
        ChatPresentation.activityPreview(drafts[row.channel.id]?.text?.value,
            if (syncStatus == Status.Live) garden.vayne.chorus.data.TypingText.label(model, typing[row.channel.id].orEmpty()) else null)
            ?.let { row.channel.id to it }
    }.toMap()
    val typingLabel = if (syncStatus == Status.Live && channel?.let { shownNow(it.id) } == true) garden.vayne.chorus.data.TypingText.label(model, typing[channel?.id].orEmpty()) else null
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
    // which bubbles sit on our side (D-103): the room's speakers and whoever speaks now, and everyone
    // inside any of them that is a subsystem (D-102: the only place they count as fronting with it)
    val alignedIds = remember(model, channel?.id, replySpeakerId, paneSides) {
        val writable = ChatCompose.members(model, chorus.device?.accountId.orEmpty()).map { it.id }
            .filter { paneSides?.mine.isNullOrEmpty() || it in paneSides!!.mine }.toSet()
        ChatPresentation.aligned(model, channel?.id?.let { ChatSpeaker.speakers(model, it, writable) }.orEmpty() + listOfNotNull(replySpeakerId))
    }
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
    if (searchHere && channel != null) {
        androidx.activity.compose.BackHandler { searchHere = false }
        ContentSearch(chorus, model, syncStatus == garden.vayne.chorus.data.Status.Live,
            onClose = {searchHere=false}, onOpenMessage = {jumpTo=it.id; searchHere=false},
            onOpenPost = {}, channelId = channel.id)
        return@ChorusScene
    }
    Row(Modifier.fillMaxSize().background(p.bg).imePadding()) {
        if (arrangingPins) ArrangePins(listRows.filter { it.pinned }, if (expanded) Modifier.width(320.dp) else Modifier.weight(1f),
            { arrangingPins = false }) { savePins(it); arrangingPins = false }
        else if (foldersAt != null) ChatFoldersScreen(model, listRows, { saveFolders(it) },
            onReadPerMember = { on ->
                actions.launch {
                    try { chorus.create("pref.set", null, AccountPrefs.payload("chat.read_per_member", on)) }
                    catch (e: Exception) { error = e.message ?: "Could not save that setting." }
                }
            }, newId = { chorus.newId() }, onClose = { foldersAt = null },
            modifier = if (expanded) Modifier.width(320.dp) else Modifier.weight(1f), startWith = foldersAt?.ifEmpty { null })
        else if (expanded || pane == "list") ChatListPane(model, listRows, if (expanded) channel?.id else null, readerName,
            openChat, { spaceAction = "new" },
            { switchOpen = true }, onSearch, if (expanded) Modifier.width(240.dp) else Modifier.weight(1f), readerId = readerId,
            onNewChannel = { editChannel = false; channelEditor = true }, onRowAction = rowAction, previews = listPreviews,
            onEditFolders = { foldersAt = it ?: "" }, onFolders = { saveFolders(it) }, opened = channel?.id, onSpaceAction = spaceRowsAction)
        if (expanded) VerticalDivider(Modifier.fillMaxHeight(), color = p.line)
        if (pane == "info" && !expanded && channel != null && space != null && !paneLocked) {
            ChatInfoPane(chorus, channel, space, model, messages, library, directory[space.id], { infoOpen = false; searchHere = true },
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
            // a DM with another account shows who it is with, as in the list
            val listed = listRows.find { it.channel.id == channel?.id }?.takeIf { !inThread && it.icon != null }
            if (listed != null) ChatRowDisc(listed, 36.dp)
            else channel?.let { ChannelDisc(if (dmName != null) it.copy(name = dmName) else it,
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
            ChatIconButton(R.drawable.ic_search, "Search this chat", { searchHere = true })
            ChatIconButton(R.drawable.ic_more, "Conversation options") { headerMenu = true }
            MenuDrawer(headerMenu, { headerMenu = false }, channel?.name ?: "Chats",
                space?.let { spaceLabel(it, directory[it.id], chorus.device?.accountId.orEmpty()) }) {
                if (channel != null && !expanded) MenuRow("Chat info", R.drawable.ic_chat) { headerMenu = false; infoOpen = true }
                if (paneEditable) MenuRow(if (channel?.kind == "member_dm") "Edit chat" else "Edit channel", R.drawable.ic_edit) {
                    headerMenu = false; editChannel = true; channelEditor = true
                }
                if (channel != null) MenuRow("Notifications", R.drawable.ic_bell,
                    subtitle = notifyLabel(model.accountPrefs.channelNotify(channel.id, space?.kind.orEmpty()))) { headerMenu = false; notifyOpen = true }
                if (channel != null) MenuRow("Stage mode", R.drawable.ic_layers, "Pick messages to show as a scene") {
                    headerMenu = false; stageSelection = emptyList(); staging = true
                }
                MenuRow("Message options", R.drawable.ic_sliders) { headerMenu = false; attachPage = ""; moreOpen = !moreOpen }
                if (space?.kind == "internal" && !model.isPerson) {
                    MenuRow("Read as…", R.drawable.ic_eye, "Whose unread messages to show") { headerMenu = false; readerOpen = true }
                }
                MenuDivider()
                MenuRow("New chat", R.drawable.ic_plus) { headerMenu = false; spaceAction = "new" }
                val canLeave = space != null && space.kind != "internal" && directory[space.id]?.let {
                    space.kind != "shared" || it.ownerAccountId != chorus.device?.accountId } == true
                if (canLeave) MenuRow("Leave space", R.drawable.ic_back, danger = true) { headerMenu = false; spaceAction = "leave" }
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
                    Text(spoilerPreview(ChatPresentation.preview(shown)), color = p.ink2, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1)
                }
                ChatIcon(R.drawable.ic_pin, tint = p.ink3)
            }
            HorizontalDivider(color = p.line)
        }
        if (error != null) Text(error.orEmpty(), color = p.danger, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(16.dp))
        if (space == null) Text("No spaces yet. Start a chat with someone you follow.", color = p.ink2, modifier = Modifier.padding(24.dp))
        else {
        if (searchHit != null && searchHit.channelId == channel?.id && SearchVisibility(model,chorus.device?.accountId.orEmpty()).document(searchHit)) {
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
        // replies to a message in another chat (Reply in…) show it, if we may see that chat
        // front-change lines between our own chat's messages (web parity, SPEC §5)
        val frontRevision by chorus.frontHistoryRevision.collectAsState()
        val timelineFront = ChatSpeaker.frontOf(model, channel.id)
        val timelineHistory by androidx.compose.runtime.key(chorus.device?.accountId, channel.id, timelineFront, space.kind, viewingAs) { produceState<garden.vayne.chorus.data.NamedHistory?>(null, frontRevision) {
            if (space.kind == "internal" && timelineFront != null) {
                try { value = chorus.frontHistory(timelineFront) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) {}
            }
        } }
        val timelineIntervals = remember(model, timelineHistory, timelineFront) { if (model.namedFronts == null) model.frontIntervals else
            timelineHistory?.takeIf { it.frontId == timelineFront }?.intervals.orEmpty().map {
                garden.vayne.chorus.data.FrontInterval(it.id, it.presence.subjectType, it.presence.subjectId,
                    it.presence.layerId, it.presence.isPrimary, it.startAt, it.endAt)
            } }
        val frontLines = remember(model, timelineIntervals, messages.firstOrNull()?.occurredAt, space.kind) { if (space.kind != "internal" || messages.isEmpty()) emptyList() else
            ChatPresentation.frontEvents(timelineIntervals, messages.first().occurredAt, System.currentTimeMillis(),
                { type, id -> model.subject(type, id)?.name },
                { level -> ChatPresentation.layer(model, Entry("member", "", level, false)) }) }
        val everywhere = remember(model, viewingAs) {
            model.chatMessages.filterKeys { shownNow(it) }.values.flatten().associateBy { it.id }
        }
        LaunchedEffect(newest, readerId, currentPane, history.target, followNext) {
            if (!currentPane) return@LaunchedEffect
            if (newest != null && ((newest != snapshot.reading.newest && listState.firstVisibleItemIndex <= 2) || followNext))
                listState.animateScrollToItem(0)
            snapshot.reading.newest = newest
            followNext = false
        }
        // where each reply jump here started, newest last (D-112): the down arrow first goes back
        // one jump at a time, then to the latest message; reaching the latest forgets the trail
        var jumpBack by remember(channel.id) { mutableStateOf(listOf<String>()) }
        // an older window from search (history.target) isn't the latest, even at its newest message
        val atLatest = listState.firstVisibleItemIndex == 0 && history.target == null
        LaunchedEffect(atLatest) { if (atLatest) jumpBack = emptyList() }
        // after the opening position, so coming back from chat info lands on the chosen message
        LaunchedEffect(jumpTo, messages, currentPane) {
            if (!currentPane) return@LaunchedEffect
            val id = jumpTo ?: return@LaunchedEffect
            val at = messages.asReversed().indexOfFirst { it.id == id }
            // a third of the way up, not against the composer
            if (at < 0) return@LaunchedEffect // The guarded containing window is still loading.
            listState.animateScrollToItem(at, -listState.layoutInfo.viewportSize.height / 3)
            flashId = id
            jumpTo = null
        }
        LaunchedEffect(flashId) { if (flashId != null) { delay(1600); flashId = null } }
        // a reply's, quote's or forward's original (owner, 2026-11-02): scroll to it here, or open its chat at it
        fun showMessage(id: String, from: String? = null) {
            when (val where = garden.vayne.chorus.data.ChatJump.channelOf(id, messages, everywhere)) {
                null -> android.widget.Toast.makeText(ctx, "That message isn't available here.", android.widget.Toast.LENGTH_SHORT).show()
                channel.id -> { jumpBack = garden.vayne.chorus.data.ChatJump.push(jumpBack, from, id); jumpTo = id }
                else -> model.channels.find { it.id == where }?.let { c ->
                    selectedSpace = c.spaceId; selectedChannel = c.id; jumpTo = id
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(messages.asReversed(), key = { _, message -> message.id }) { index, message ->
                val older = messages.getOrNull(messages.lastIndex - index - 1)
                val newer = messages.getOrNull(messages.lastIndex - index + 1)
                // who came to the front since the message before (our own chats; after the newest, until now)
                val timelinePlan = ChatPresentation.timelinePlan(older?.occurredAt, message.occurredAt, newer == null, frontLines)
                val groupStart = !ChatPresentation.grouped(older, message) || message.id == firstUnread
                val groupEnd = !ChatPresentation.grouped(message, newer) || newer?.id == firstUnread
                Column(Modifier.padding(top = if (groupStart) 8.dp else 0.dp)) {
                if (readerName != null && message.id == firstUnread) Text("${unreadCount} unread for $readerName",
                    color = p.accent, fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).background(p.surface).padding(vertical = 6.dp))
                for (marker in timelinePlan.before) TimelineMarker(marker)
                val canPrivate = if (space.kind == "internal") replySpeakerId != null &&
                    message.authors.firstOrNull()?.let { it != replySpeakerId && model.member(it) != null } == true
                else message.accountId != null && message.accountId != chorus.device?.accountId
                ChatMessageCard(message, model, foreignAuthors, chorus, unseenByMessage[message.id].orEmpty(),
                    heldMessages[message.id], actingMemberId = replySpeakerId, aligned = alignedIds,
                    reactions = model.messageReactions[message.id].orEmpty(),
                    onForward = if (heldMessages[message.id] == null) ({ forwarding = message }) else null,
                    onPin = if (heldMessages[message.id] == null) ({ pin(message) }) else null,
                    thread = threads[message.id],
                    onThread = if (channel.kind != "thread" && heldMessages[message.id] == null) ({ openThread(message) }) else null,
                    onDelete = if (chorus.device?.accountId?.let { it == message.accountId } == true &&
                        heldMessages[message.id] == null) ({ deleting = message }) else null,
                    onReact = { emoji, on -> react(message, emoji, on) },
                    showSender = groupStart, showAvatar = groupEnd, onStage = { stageSelection = listOf(message.id); staging = true },
                    quote = message.replyTo?.let { id -> messages.find { it.id == id } ?: everywhere[id] },
                    quoteElsewhere = message.replyTo?.let { id -> everywhere[id]?.takeIf { it.channelId != message.channelId } }
                        ?.let { q -> model.channels.find { it.id == q.channelId }?.name },
                    onReply = { replyTo = message.id; carriedReply = null },
                    onQuote = if (heldMessages[message.id] == null) ({ quotePicking = message; selectedIds = emptyList() }) else null,
                    onReplyIn = if (heldMessages[message.id] == null) ({ replyIn = message }) else null,
                    selecting = selectedIds.isNotEmpty(), selected = message.id in selectedIds,
                    onSelect = if (heldMessages[message.id] == null) ({
                        selectedIds = if (message.id in selectedIds) selectedIds - message.id else selectedIds + message.id }) else null,
                    onReplyPrivately = if (canPrivate) ({ replyPrivately(message) }) else null,
                    onEdit = if (chorus.device?.accountId?.let { it == message.accountId } == true &&
                        heldMessages[message.id] == null)
                        ({ editingMessageId = message.id }) else null,
                    onJump = { target -> showMessage(target, message.id) }, flash = message.id == flashId,
                    onProfile = { account, id -> onOpenProfile(if (account == null || account == chorus.device?.accountId) id else "$account $id") },
                    linked = { id -> garden.vayne.chorus.data.ChatOrganize.actionMessage(model, id, viewingAs, chorus.device?.accountId.orEmpty()) })
                for (marker in timelinePlan.after) TimelineMarker(marker)
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
        val returnTo = garden.vayne.chorus.data.ChatJump.back(jumpBack, messages.mapTo(HashSet()) { it.id })
        if (history.target != null || jumpTo != null || listState.firstVisibleItemIndex > 1 || unreadCount > 0 || returnTo != null) Column(
            Modifier.align(Alignment.BottomEnd).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (unreadMentions.isNotEmpty()) ChatJumpButton("@", unreadMentions.size, "${unreadMentions.size} unread mentions for $readerName") {
                actions.launch { val at = messages.asReversed().indexOfFirst { it.id == unreadMentions.last().id }; if (at >= 0) listState.animateScrollToItem(at) }
            }
            ChatJumpButton(null, unreadCount, if (returnTo != null) "Back to where you jumped from"
                else if (unreadCount > 0) "Jump to latest message · $unreadCount unread" else "Jump to latest message") {
                if (returnTo != null) { jumpBack = returnTo.rest; jumpTo = returnTo.id }
                else { jumpBack = emptyList(); history.latest(); jumpTo = null; followNext = true }
            }
        }
        }
        undoDelete?.let { gone ->
            Row(Modifier.fillMaxWidth().background(p.surface2).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Message deleted", color = p.ink, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { delete(gone, restore = true) }) { Text("Undo") }
            }
        }
        if (selectedIds.isNotEmpty()) {
            val picked = messages.filter { it.id in selectedIds }
            Row(Modifier.fillMaxWidth().background(p.surface2).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ChatIconButton(R.drawable.ic_close, "Cancel selection") { selectedIds = emptyList() }
                Text("${picked.size} selected", color = p.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                TextButton(enabled = picked.isNotEmpty(), onClick = { quoting = picked; quotePart = null; selectedIds = emptyList() }) { Text("Quote") }
                TextButton(enabled = picked.isNotEmpty(), onClick = { bundleForward = picked; selectedIds = emptyList() }) { Text("Forward") }
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
            // @ suggestions: the channel's front, our members and groups (core turns them into mentions)
            val mentionQuery = garden.vayne.chorus.data.ChatMentions.typing(draft)
            val mentionMatches = remember(model, mentionQuery) { mentionQuery?.let { garden.vayne.chorus.data.ChatMentions.matches(model, it) }.orEmpty() }
            if (mentionMatches.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(mentionMatches, key = { "${it.type}:${it.id}" }) { choice ->
                    Row(Modifier.clip(RoundedCornerShape(20.dp)).background(p.surface2)
                        .clickable(onClickLabel = "Mention ${choice.name}") { draft = garden.vayne.chorus.data.ChatMentions.insert(draft, choice) }
                        .heightIn(min = 40.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        choice.id?.let(model::member)?.let { ChatAvatar(it, 24.dp) }
                        Text("@" + choice.name, color = p.accent, fontSize = 14.sp, lineHeight = 20.sp)
                        Text(when (choice.type) { "front" -> "who's fronting"; "group" -> "group"; else -> "" }, color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                }
            }
            if (quoting.isNotEmpty()) Row(Modifier.fillMaxWidth().background(p.surface2, RoundedCornerShape(10.dp)).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChatIcon(R.drawable.ic_format_quote, tint = p.accent)
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(if (quoting.size == 1) "Quote " + quoting[0].authors.joinToString(" & ", transform = ::nameOf) else "Quote ${quoting.size} messages",
                        color = p.accent, fontSize = 13.sp, lineHeight = 18.sp)
                    Text(spoilerPreview(quotePart?.let { r -> quoting.singleOrNull()?.text?.let { t -> "“" + t.substring(r.first, (r.last + 1).coerceAtMost(t.length)) + "”" } }
                        ?: quoting.joinToString(" · ") { ChatPresentation.preview(it) }), color = p.ink3, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2)
                }
                ChatIconButton(R.drawable.ic_close, "Cancel quote") { quoting = emptyList(); quotePart = null }
            }
            val target = messages.find { it.id == replyTo } ?: carriedReply?.takeIf { it.id == replyTo }
            if (target != null) {
                Row(Modifier.fillMaxWidth().background(p.surface2, RoundedCornerShape(10.dp)).padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChatIcon(R.drawable.ic_reply, tint = p.accent)
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        val name = target.authors.joinToString(" & ") { model.member(it)?.shownName ?: foreignAuthors[it]?.name ?: "Someone" }
                        Text(if (carriedReply?.id == replyTo) "Reply to $name · from another chat" else "Reply to $name", color = p.accent, fontSize = 13.sp, lineHeight = 18.sp)
                        Text(spoilerPreview(ChatPresentation.preview(target)), color = p.ink3, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2)
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
            if (attachments.isNotEmpty()) AttachmentTray(attachments, onEdit = { editingFile = it }, onRemove = { i ->
                attachments.getOrNull(i)?.let { DeviceGallery.discard(ctx, it.uri); attachments.removeAt(i) }
            })
            editingFile?.let { i -> attachments.getOrNull(i)?.let { i to it } }?.let { (i, a) ->
                AttachmentEditor(a, onSave = { attachments[i] = it; editingFile = null },
                    onRemove = { DeviceGallery.discard(ctx, a.uri); attachments.removeAt(i); editingFile = null },
                    onDismiss = { editingFile = null })
            }
            val sheetOptions = buildList {
                add(AttachOption("poll", "Poll", R.drawable.ic_poll, pollOn) {
                    PollDraftFields(pollOn, { pollOn = it }, pollOptions, pollMultiple) { pollMultiple = it }
                })
                add(AttachOption("cw", "Warning", R.drawable.ic_format_eye_off, cw.isNotBlank()) {
                    Text("Readers see the warning first and tap to show the message.", color = p.ink2, fontSize = 13.sp, lineHeight = 18.sp)
                    ChorusField(cw, { cw = it }, label = { Text("Content warning") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    if (cw.isNotBlank()) TextButton(onClick = { cw = "" }) { Text("Remove the warning") }
                })
                add(AttachOption("audience", "Who sees", R.drawable.ic_lock, audience != "all") {
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
                })
                // Advanced: who speaks by default here (D-103)
                if (!model.isPerson) add(AttachOption("speaker", "Speaker", R.drawable.ic_front, false) {
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
                        item { ChatChip("Several members", setting.mode == "members") {
                            setSpeakerDefault("members", members = setting.members.ifEmpty { listOfNotNull(author?.id ?: ownSenders.firstOrNull()?.id) })
                        } }
                    }
                    if (setting.mode == "members") {
                        Text("Speakers here (the first is the default)", color = p.ink2, fontSize = 12.sp, lineHeight = 16.sp)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(ownSenders, key = { it.id }) { member ->
                                ChatChip(member.shownName, member.id in setting.members) {
                                    setSpeakerDefault("members", members = if (member.id in setting.members) setting.members - member.id else setting.members + member.id)
                                }
                            }
                        }
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
                })
            }
            if (moreOpen) AttachSheet(attachPage, { attachPage = it }, attachments.size, sheetOptions,
                onAdd = { uris ->
                    for (uri in uris) attachments.add(PendingAttachment.of(ctx, uri))
                    moreOpen = false; attachPage = ""
                },
                onDismiss = { moreOpen = false; attachPage = "" })
            // what the message carries besides its text: tap one to change it
            if (audience != "all" || cw.isNotBlank() || pollOn) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (audience != "all") item { ComposerOptionChip(audienceLabel, R.drawable.ic_lock) { attachPage = "audience"; moreOpen = true } }
                if (cw.isNotBlank()) item { ComposerOptionChip("CW: ${cw.trim()}", R.drawable.ic_format_eye_off) { attachPage = "cw"; moreOpen = true } }
                if (pollOn) item { ComposerOptionChip("Poll", R.drawable.ic_poll) { attachPage = "poll"; moreOpen = true } }
            }
            if (channel.kind == "thread" && threadParentChannel != null && threadParent != null) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { alsoSend = !alsoSend },
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(alsoSend, { alsoSend = it })
                    Text("Also send to ${threadParentChannel.name}", color = p.ink, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
            // the room's speakers (D-103): one tap picks who is speaking
            val roomSpeakers = remember(model, channel.id, ownSenders) { ChatSpeaker.speakers(model, channel.id, ownSenders.mapTo(HashSet()) { it.id }) }
            if (roomSpeakers.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(roomSpeakers, key = { it }) { id ->
                    val m = model.member(id) ?: return@items
                    val on = id == author?.id
                    Row(Modifier.clip(RoundedCornerShape(20.dp)).background(if (on) p.accentSoft else p.surface2)
                        .selectable(on, role = Role.RadioButton) { selectedAuthor = id }
                        .heightIn(min = 40.dp).padding(start = 4.dp, end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChatAvatar(m, 28.dp)
                        Text(m.shownName, color = if (on) p.ink else p.ink2, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                var authorsOpen by remember { mutableStateOf(false) }
                var emojiOpen by remember { mutableStateOf(false) }
                // the composer's emoji: the reaction set, tapped into the draft (Android has no custom emoji yet)
                if (emojiOpen) ChorusSheet(onDismissRequest = { emojiOpen = false }, containerColor = p.surface) {
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 24.dp)) {
                        for (emoji in ChatReactions.QUICK + ChatReactions.MORE) Box(Modifier.size(48.dp).clip(CircleShape)
                            .clickable(onClickLabel = "Add $emoji") { draft += emoji },
                            contentAlignment = Alignment.Center) { Text(emoji, fontSize = 26.sp, lineHeight = 30.sp) }
                    }
                }
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
                            Box(Modifier.padding(start = 4.dp).size(40.dp).clip(CircleShape).clickable(onClickLabel = "Add an emoji") { emojiOpen = true },
                                contentAlignment = Alignment.Center) { ChatIcon(R.drawable.ic_emoji, "Emoji") }
                            Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                                if (draft.isEmpty()) Text(if (channel.kind == "thread") "Reply in thread" else "Message",
                                    color = p.ink3, style = MaterialTheme.typography.bodyLarge)
                                inner()
                            }
                            ChatIconButton(R.drawable.ic_attach, "Attach photos or files, or add a poll, warning or audience") { attachPage = ""; moreOpen = true }
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
                                    // a quote from a chat that has since locked doesn't go
                                    val quoted = quoting.filter { ChatOrganize.actionMessage(chorus.model.value, it.id, viewingAs, senderAccount) == it }
                                    check(quoted.size == quoting.size) { "A quoted message is no longer available." }
                                    val payload = ChatCompose.payload(model, channel.id, speakerId, draft, cw,
                                        audience, visibleTo.toSet(), space.kind, replyTo = target?.id, attachmentIds = attachmentIds,
                                        accountId = senderAccount,
                                        quote = quoted.takeIf { it.isNotEmpty() }?.let { q -> ChatForward.quote(q, model.channels.find { it.id == q.first().channelId }?.name ?: channel.name, quotePart) })
                                    if (pollOn) payload.put("poll", Polls.draft(pollOptions.toList(), pollMultiple).getOrThrow())
                                    chorus.create("message.send", chorus.newId(), payload, scope = scope)
                                    history.sent(); jumpTo = null; followNext = true
                                    val alsoTo = threadParentChannel?.takeIf { alsoSend && channel.kind == "thread" }
                                    val parent = threadParent
                                    if (alsoTo != null && parent != null) chorus.create("message.send", chorus.newId(),
                                        ChatCompose.payload(model, alsoTo.id, speakerId, draft, cw, audience, visibleTo.toSet(), space.kind,
                                            replyTo = parent.id, attachmentIds = attachmentIds, accountId = senderAccount), scope = scope)
                                    alsoSend = false
                                    pollOn = false; pollMultiple = false
                                    pollOptions.clear(); pollOptions.addAll(listOf("", ""))
                                    if (attachmentIds.isNotEmpty()) UploadWork.enqueue(ctx)
                                    // the bytes are staged in the upload queue now; our own camera shots can go
                                    for (a in attachments) DeviceGallery.discard(ctx, a.uri)
                                    attachments.clear()
                                    draft = ""
                                    cw = ""
                                    audience = "all"
                                    visibleTo = emptyList()
                                    replyTo = null
                                    carriedReply = null
                                    quoting = emptyList(); quotePart = null
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
            ChatInfoPane(chorus, channel, space, model, messages, library, directory[space.id], { searchHere = true },
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
        ChorusSheet(onDismissRequest = { readerOpen = false }, containerColor = p.surface,
            skipPartiallyExpanded = true) {
            InspectionDialogContent {
                Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).imePadding().padding(horizontal = 20.dp)) {
                    Text("Read as…", style = MaterialTheme.typography.titleLarge)
                    ChorusField(value = readerQuery, onValueChange = { readerQuery = it },
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
    val editableMessage = ChatOrganize.actionMessage(model, editingMessageId, viewingAs, chorus.device?.accountId.orEmpty())
    val forwardableMessage = ChatOrganize.actionMessage(model, forwarding?.id, viewingAs, chorus.device?.accountId.orEmpty())
    LaunchedEffect(model, viewingAs, editingMessageId, forwarding?.id, deleting?.id) {
        if (editingMessageId != null && editableMessage == null) editingMessageId = null
        if (forwarding != null && forwardableMessage == null) { forwarding = null; forwardConfirm = null }
        if (deleting != null && ChatOrganize.actionMessage(model, deleting?.id, viewingAs, chorus.device?.accountId.orEmpty()) == null) deleting = null
    }
    editingMessageId?.takeIf { editableMessage != null }?.let { id ->
        MessageEditDialog(chorus, model, id, viewingAs) { editingMessageId = null }
    }
    forwarding?.takeIf { forwardableMessage != null }?.let { message ->
        ChorusSheet(onDismissRequest = { forwarding = null }, containerColor = p.surface) {
            Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                Text("Forward to…", color = p.ink, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                Text(spoilerPreview(ChatPresentation.preview(message)), color = p.ink3, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2,
                    modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp))
                for (row in listRows.sortedByDescending { it.latest?.occurredAt ?: 0L }) {
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                        val source = space ?: return@clickable
                        val from = channel ?: return@clickable
                        if (ChatForward.asksFirst(source, row.space.id, row.channel.id, from.id, message, ChatOrganize.restricted(model, from.id))) forwardConfirm = row
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
    // Quote: pick a part of the text (long-press to select), or all of it (SPEC §5.3 partial quotes)
    quotePicking?.let { message ->
        var picked by remember(message.id) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(message.text)) }
        ChorusSheet(onDismissRequest = { quotePicking = null }, containerColor = p.surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Quote", color = p.ink, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
                Text("Select the part to quote, or quote it all.", color = p.ink3, fontSize = 13.sp, lineHeight = 18.sp)
                BasicTextField(picked, { next -> picked = next.copy(text = message.text) }, readOnly = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = p.ink),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).background(p.surface2, RoundedCornerShape(12.dp)).padding(12.dp)
                        .semantics { contentDescription = "Message text to quote from" })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { quoting = listOf(message); quotePart = null; quotePicking = null }) { Text("Quote all") }
                    val sel = picked.selection
                    Button(enabled = !sel.collapsed, onClick = {
                        quoting = listOf(message); quotePart = ChatForward.safeRange(message.text, sel.min until sel.max); quotePicking = null
                    }) { Text("Quote selection") }
                }
            }
        }
    }
    replyIn?.let { message ->
        ChorusSheet(onDismissRequest = { replyIn = null }, containerColor = p.surface) {
            Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                Text("Reply in…", color = p.ink, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                for (row in listRows.filter { it.channel.id != message.channelId }) Row(Modifier.fillMaxWidth().clickable {
                    // the reply carries over and lands in that chat's composer
                    replyIn = null
                    selectedSpace = row.space.id; selectedChannel = row.channel.id; conversationOpen = true
                    pendingPrivateReply = PendingPrivateReply(message, channelId = row.channel.id, speakerId = replySpeakerId)
                }.heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ChatRowDisc(row, 40.dp)
                    Text(row.title, color = p.ink, fontSize = 16.sp, lineHeight = 22.sp, maxLines = 1)
                }
            }
        }
    }
    if (bundleForward.isNotEmpty()) ChorusSheet(onDismissRequest = { bundleForward = emptyList() }, containerColor = p.surface) {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text("Forward ${bundleForward.size} messages to…", color = p.ink, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            val source = model.spaces.find { s -> s.id == model.channels.find { it.id == bundleForward.first().channelId }?.spaceId }
            if (source?.kind == "internal" || bundleForward.any { it.visibilityMode != "all" } || ChatOrganize.restricted(model, bundleForward.first().channelId))
                Text("This shares a snapshot outside where it was written; its audience doesn't follow it.", color = p.ink3, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp))
            for (row in listRows.sortedByDescending { it.latest?.occurredAt ?: 0L }) Row(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                forwardBundle(bundleForward, row)
            }.heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ChatRowDisc(row, 40.dp)
                Text(row.title, color = p.ink, fontSize = 16.sp, lineHeight = 22.sp, maxLines = 1)
            }
        }
    }
    forwardConfirm?.takeIf { forwardableMessage != null && shownNow(it.channel.id) }?.let { row ->
        ConfirmDrawer("Forward outside this chat?", "This shares a snapshot of the message with ${row.title}. Its original audience doesn't follow it there.",
            "Forward", { forwarding?.let { forward(it, row) } }, { forwardConfirm = null }, busy = busy, icon = R.drawable.ic_forward) {
            FormNote("${row.title} will see it.", R.drawable.ic_globe, warning = true, title = "This leaves ${space?.name?.takeIf { space.kind != "dm" } ?: "this chat"}")
        }
    }
    deleting?.takeIf { ChatOrganize.actionMessage(model, it.id, viewingAs, chorus.device?.accountId.orEmpty()) != null }?.let { message ->
        ConfirmDrawer("Delete this message?", "It disappears for everyone in this chat. You can undo right after.", "Delete",
            { deleting = null; delete(message) }, { deleting = null }, danger = true)
    }
    if (notifyOpen && channel != null && space != null) {
        val level = model.accountPrefs.channelNotify(channel.id, space.kind)
        ChoiceDrawer(true, "Notifications", listOf("all", "mentions", "none"), level, ::notifyLabel,
            { setNotify(it); notifyOpen = false }, { notifyOpen = false }, subtitle = channel.name,
            describe = { when (it) { "all" -> "Every new message"; "mentions" -> "Only when someone names you"; else -> "Nothing from this chat" } })
    }
    if (channelEditor) ChannelEditor(chorus, model, space?.id?.takeIf { space?.kind != "dm" },
        channel.takeIf { editChannel }, onDismiss = { channelEditor = false }, onSaved = { home, id ->
            selectedSpace = home; selectedChannel = id; conversationOpen = true; infoOpen = false; channelEditor = false
        })
    if (spaceAction == "new") {
        var choice by rememberSaveable { mutableStateOf("choose") }
        ChorusSheet(onDismissRequest = { directRequest?.cancel(); spaceAction = "" }, containerColor = p.surface,
            skipPartiallyExpanded = true) {
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
                        ChatCreationChoice(R.drawable.ic_members, "Direct message",
                            if (model.isPerson) "Write to a friend, or a system you follow" else "Write to a member or a connected account") { choice = "direct" }
                        ChatCreationChoice(R.drawable.ic_globe, "New shared space",
                            if (model.isPerson) "A group chat with friends and their systems" else "Invite connected accounts to a group chat") { choice = "shared" }
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
                            }, privateChats = !model.isPerson, person = model.isPerson, onAccount = { speaker, account, keepPrivate ->
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
                        ChorusField(newSpaceName, { newSpaceName = it.take(80) }, label = { Text("Shared space name") }, singleLine = true,
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
        ConfirmDrawer("Leave ${space.name.ifBlank { "this space" }}?", "You can be added back later.", "Leave", {
            val dev = chorus.device
            if (dev != null) {
                busy = true; error = null; spaceAction = ""
                actions.launch {
                    try { Spaces.leave(dev, space.id); selectedSpace = ""; selectedChannel = ""; refresh++ }
                    catch (e: Exception) { error = e.message ?: "Could not leave the space." }
                    finally { busy = false }
                }
            }
        }, { spaceAction = "" }, danger = true, icon = R.drawable.ic_back)
    }
    removing?.let { row ->
        // our own channels and members' DMs are deleted for the system; a DM with another account or a
        // shared channel is only cleared for us (they keep it; it comes back when someone writes)
        val own = row.space.kind == "internal"
        val name = if (row.channel.isMemberDm) "this chat" else if (row.space.kind == "dm") "your chat with ${row.title}" else "#${row.title}"
        ConfirmDrawer(if (own) "Delete $name?" else "Clear $name?",
            if (own) (if (model.isPerson) "It's deleted on all your devices, messages and all." else "It's deleted for everyone in your system, messages and all.") else
                "It leaves your chats and its messages leave your view, on all your devices. It comes back if someone writes again; they keep everything.",
            if (own) "Delete" else "Clear", danger = true, onDismiss = { removing = null }, onConfirm = {
                removing = null; error = null
                actions.launch {
                    try {
                        if (own) chorus.create("channel.delete", row.channel.id, JSONObject(), scope = "space:${row.space.id}")
                        else chorus.create("pref.set", null, AccountPrefs.payload(ChatOrganize.HIDDEN + row.channel.id,
                            maxOf(System.currentTimeMillis(), row.latest?.occurredAt ?: 0L)))
                        if (row.channel.id in model.accountPrefs.chatPins) {
                            savePins(model.accountPrefs.chatPins - row.channel.id)
                        }
                        if (selectedChannel == row.channel.id) { selectedChannel = ""; conversationOpen = false }
                    } catch (e: Exception) { error = e.message ?: "Could not remove the chat." }
                }
            })
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

/** The small "edited" beside a bubble, on the side away from the avatar. */
@Composable
private fun EditedMark() {
    Text("edited", color = LocalChorusPalette.current.ink3, fontSize = 11.sp, lineHeight = 14.sp,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatMessageCard(message: ChatMessage, model: Model, foreignAuthors: Map<String, ForeignAuthor>,
    chorus: Chorus, unseenBy: List<String>, held: HeldMessage?, actingMemberId: String?, onReply: () -> Unit,
    /** Authors whose messages sit on our side (D-103); null: just the acting speaker's. */
    aligned: Set<String>? = null,
    onReplyPrivately: (() -> Unit)?, onEdit: (() -> Unit)?,
    reactions: List<ChatReaction> = emptyList(), onReact: ((String, Boolean) -> Unit)? = null,
    onForward: (() -> Unit)? = null, onDelete: (() -> Unit)? = null, onPin: (() -> Unit)? = null,
    thread: ThreadSummary? = null, onThread: (() -> Unit)? = null, interactive: Boolean = true,
    showSender: Boolean = true, showAvatar: Boolean = true, quote: ChatMessage? = null, onStage: () -> Unit,
    /** The replied-to message's chat when it is another one (Reply in…). */
    quoteElsewhere: String? = null,
    onQuote: (() -> Unit)? = null, onReplyIn: (() -> Unit)? = null,
    /** Select several (D-048): while [selecting], a tap picks or drops this message. */
    selecting: Boolean = false, selected: Boolean = false, onSelect: (() -> Unit)? = null,
    /** Show a replied-to, quoted or forwarded original by its message id. */
    onJump: ((String) -> Unit)? = null,
    /** What its links point at (D-109): a message this reader may see here now, else null. */
    linked: ((String) -> ChatMessage?)? = null,
    /** Just jumped to: a moment's ring. */
    flash: Boolean = false,
    /** Opens an author's profile: (their account when not ours, member id). */
    onProfile: ((String?, String) -> Unit)? = null) {
    val p = LocalChorusPalette.current
    val actions = rememberCoroutineScope()
    var revealed by rememberSaveable(message.id) { mutableStateOf(false) }
    val motion = rememberChorusMotion()
    val ring = remember(message.id) { Animatable(0f) }
    LaunchedEffect(flash) {
        if (!flash) return@LaunchedEffect
        ring.snapTo(1f)
        if (motion) ring.animateTo(0f, tween(1400)) else { delay(1200); ring.snapTo(0f) }
    }
    var showUnseen by rememberSaveable(message.id) { mutableStateOf(false) }
    var cancelBusy by remember(message.id) { mutableStateOf(false) }
    var cancelError by remember(message.id) { mutableStateOf<String?>(null) }
    var now by remember(held?.until) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(held?.until) {
        while (held != null) { delay(1_000); now = System.currentTimeMillis() }
    }
    val authors = message.authors.map { model.member(it)?.shownName ?: foreignAuthors[it]?.name ?: message.authorCards[it]?.name ?: "Someone" }
        .joinToString(" & ").ifEmpty { "Someone" }
    // another account's members come from the space's author cards (their picture, colour and sigil)
    fun author(id: String) = model.member(id) ?: foreignAuthors[id]?.member ?: message.authorCards[id]?.member
    val own = if (aligned != null) ChatPresentation.own(message, chorus.device?.accountId.orEmpty(), aligned)
        else ChatPresentation.own(message, chorus.device?.accountId.orEmpty(), actingMemberId)
    val member = message.authors.firstOrNull()?.let(::author)
    val tone = member?.let { tonesOf(it.color) }
    val presence = ChatPresentation.presence(model, message.channelId).firstOrNull { it.subjectId == member?.id && it.subjectType == "member" }
    val layer = ChatPresentation.layer(model, presence)
    var menuOpen by remember(message.id) { mutableStateOf(false) }
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val settled = interactive && held == null
    val swipe = rememberReplySwipe(settled) { onReply() }
    var hearts by remember(message.id) { mutableStateOf(0) }
    val heart: (() -> Unit)? = if (settled && onReact != null && actingMemberId != null) ({
        val add = !ChatReactions.chosen(reactions, "❤️", actingMemberId)
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onReact("❤️", add)
        if (add) hearts++
    }) else null
    val openActions = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); menuOpen = true }
    Box(Modifier.fillMaxWidth().swipeToReply(swipe)) {
    if (swipe.active) ReplySwipeHint(swipe)
    Row(Modifier.fillMaxWidth().graphicsLayer { translationX = swipe.translation }.padding(start = if (own) 36.dp else 0.dp),
        horizontalArrangement = if (own) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
        if (!own) {
            Box(Modifier.width(28.dp), contentAlignment = Alignment.BottomCenter) {
                if (showAvatar) {
                    if (member != null) Box(Modifier.clip(CircleShape).then(if (onProfile != null) Modifier.clickable(onClickLabel = "Open ${member.shownName}’s profile") {
                        onProfile(if (model.member(member.id) != null) null else foreignAuthors[member.id]?.accountId ?: message.accountId, member.id)
                    } else Modifier)) { ChatAvatar(member, 28.dp) }
                    else Box(Modifier.size(28.dp).background(p.surface3, CircleShape), contentAlignment = Alignment.Center) {
                        Text(authors.take(1), color = p.ink2, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
        }
        // "edited" stays in sight beside the bubble; the time waits in the actions (owner, 2026-11-02)
        val edited = held == null && message.edited
        if (own && edited) EditedMark()
        Box(Modifier.weight(1f, fill = false)) {
        Column(Modifier.widthIn(min = 64.dp, max = 360.dp)
            .then(if (reactions.isNotEmpty()) Modifier.width(IntrinsicSize.Max) else Modifier).heightIn(min = 48.dp)
            .background(if (own) p.accentSoft else p.surface2, RoundedCornerShape(
                topStart = if (!own && !showSender) 6.dp else 16.dp, topEnd = if (own && !showSender) 6.dp else 16.dp,
                bottomEnd = if (own && showAvatar) 6.dp else 16.dp, bottomStart = if (!own && showAvatar) 6.dp else 16.dp))
            .then(if (settled) Modifier.semantics { customActions = listOf(CustomAccessibilityAction("Reply") { onReply(); true }) } else Modifier)
            .then(if (selected) Modifier.border(2.dp, p.accent, RoundedCornerShape(16.dp))
                else if (ring.value > 0f) Modifier.border(2.dp, p.accent.copy(alpha = ring.value), RoundedCornerShape(16.dp)) else Modifier)
            // a tap or long press opens the actions, which also show the time and edit history (owner, 2026-11-02)
            .then(if (interactive) Modifier.combinedClickable(onClick = { if (selecting && onSelect != null) onSelect() else menuOpen = true },
                onClickLabel = "Message actions",
                onLongClick = if (selecting && onSelect != null) onSelect else openActions,
                onLongClickLabel = "Message actions", onDoubleClick = heart) else Modifier)
            .padding(horizontal = 11.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (showSender) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(authors, color = tone?.name ?: p.ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp,
                modifier = Modifier.weight(1f, fill = false))
            if (layer != null) Text(layer, color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp)
        }
        if (message.replyTo != null) {
            val quoted = quote?.authors?.firstOrNull()?.let(::author)
            val quoteColor = quoted?.let { tonesOf(it.color).name } ?: p.accent
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(quoteColor.copy(alpha = .10f))
                .then(if (quote != null && onJump != null && interactive) Modifier.clickable(onClickLabel = "Show the message this replies to") { onJump(quote.id) } else Modifier)) {
                Box(Modifier.width(2.dp).heightIn(min = 38.dp).background(quoteColor))
                Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text((quote?.authors?.joinToString(" & ") { model.member(it)?.shownName ?: foreignAuthors[it]?.name ?: message.authorCards[it]?.name ?: "Someone" }
                        ?: "Reply") + (quoteElsewhere?.let { " · in $it" } ?: ""), color = quoteColor, fontSize = 12.sp, lineHeight = 16.sp)
                    Text(spoilerPreview(if (quote != null) ChatPresentation.preview(quote) else "Message unavailable in this view"),
                        color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2)
                }
            }
        }
        for (item in message.forward) {
            val source = item.authors.firstOrNull()?.let(::author)
            val sourceColor = source?.let { tonesOf(it.color).name } ?: p.accent
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(sourceColor.copy(alpha = .10f))
                .then(if (onJump != null && interactive) Modifier.clickable(onClickLabel = "Show the original message") { onJump(item.messageId) } else Modifier)) {
                Box(Modifier.width(2.dp).heightIn(min = 38.dp).background(sourceColor))
                Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text("Forwarded from " + item.authors.joinToString(" & ") { model.member(it)?.shownName ?: foreignAuthors[it]?.name ?: message.authorCards[it]?.name ?: "Someone" }
                        .ifEmpty { "Someone" }, color = sourceColor, fontSize = 12.sp, lineHeight = 16.sp)
                    Text(listOfNotNull(item.channelName, ChatPresentation.dateLabel(item.occurredAt)).joinToString(" · "),
                        color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp)
                }
            }
            if (item.text.isNotBlank() && (message.cw == null || revealed)) RichBody(item.text, item.entities,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 20.sp),
                onPlainClick = { if (interactive) { if (selecting && onSelect != null) onSelect() else menuOpen = true } },
                onLongClick = if (settled) openActions else null, onDoubleClick = heart)
        }
        // what it quotes: part of one message or a bundle (SPEC §5.3), shown as the snapshot it carries
        if (message.cw == null || revealed) for (item in message.quote) {
            val source = item.authors.firstOrNull()?.let(::author)
            val tint = source?.let { tonesOf(it.color).name } ?: p.ink3
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(tint.copy(alpha = .08f))
                .then(if (onJump != null && interactive) Modifier.clickable(onClickLabel = "Show the quoted message") { onJump(item.messageId) } else Modifier)) {
                Box(Modifier.width(2.dp).heightIn(min = 24.dp).background(tint))
                Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    if (item.authors.isNotEmpty()) Text(item.authors.joinToString(" & ") { model.member(it)?.shownName ?: foreignAuthors[it]?.name ?: message.authorCards[it]?.name ?: "Someone" } +
                        (item.channelName?.let { " · #$it" } ?: ""), color = tint, fontSize = 12.sp, lineHeight = 16.sp)
                    Text(item.text, color = p.ink2, fontSize = 14.sp, lineHeight = 19.sp, maxLines = 8, overflow = TextOverflow.Ellipsis)
                }
            }
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
                    onPlainClick = { if (interactive) { if (selecting && onSelect != null) onSelect() else menuOpen = true } },
                    onLongClick = if (settled) openActions else null, onDoubleClick = heart)
            if (linked != null) ChatLinkEmbeds(message.text, message.entities, chorus, model, linked,
                { model.member(it)?.shownName ?: foreignAuthors[it]?.name ?: message.authorCards[it]?.name ?: "Someone" }, onJump.takeIf { interactive })
            for (attachment in message.attachments) {
                ChatAttachmentView(attachment, chorus, authors, message.occurredAt)
            }
            // Same resolved acting speaker as the composer: explicit selection or channel default.
            PollCard(chorus, model, message.id, garden.vayne.chorus.data.Polls.chatMember(model, actingMemberId),
                definition=model.polls.polls[message.id] ?: message.poll, remoteTally=message.pollTally)
        }
        if (thread != null && thread.replies > 0) Row(Modifier.heightIn(min = 48.dp)
            .then(if (onThread != null) Modifier.clickable(onClickLabel = "Open thread", onClick = onThread) else Modifier),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                for (id in thread.lastRepliers) author(id)?.let { ChatAvatar(it, 20.dp) }
            }
            Text(ChatThreads.repliesLabel(thread.replies), color = p.accent, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        if (reactions.isNotEmpty() || message.pinned || held != null) Row(Modifier.then(if (reactions.isNotEmpty()) Modifier.fillMaxWidth() else Modifier.align(Alignment.End)),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (reactions.isNotEmpty()) ReactionChips(reactions, actingMemberId, Modifier.weight(1f),
                { id -> model.member(id)?.shownName ?: foreignAuthors[id]?.name ?: "Someone" }, onReact.takeIf { held == null })
            if (message.pinned) androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_pin), "Pinned",
                Modifier.size(14.dp), tint = p.ink3)
            if (held != null) ChatIcon(R.drawable.ic_history, "Message held for slow mode")
        }
        }
        HeartPop(hearts, Modifier.align(Alignment.Center))
        }
        if (!own && edited) EditedMark()
    }
    }
    if (menuOpen) Drawer({ menuOpen = false }) {
        var allEmoji by remember(message.id) { mutableStateOf(false) }
        // what it said before (SPEC §5.3), fetched when asked and shown under the message here
        var history by remember(message.id, message.text, message.cw) { mutableStateOf<List<MessageRevision>?>(null) }
        var historyError by remember(message.id) { mutableStateOf<String?>(null) }
        Column(Modifier.fillMaxWidth().semantics { contentDescription = "Message actions" },
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (held == null && onReact != null) ReactionPicker(reactions, actingMemberId, allEmoji, { allEmoji = !allEmoji }) { emoji, on ->
                menuOpen = false; onReact(emoji, on)
            }
            Column(Modifier.fillMaxWidth().background(if (own) p.accentSoft else p.surface2, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(authors, color = tone?.name ?: p.ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp)
                Text(spoilerPreview(ChatPresentation.preview(message)), color = p.ink, maxLines = 4, fontSize = 15.sp, lineHeight = 20.sp)
                Text(ChatPresentation.dateLabel(message.occurredAt) + ", " +
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.occurredAt)) +
                    if (held == null && message.edited) " · edited" else "", color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 2.dp))
                if (historyError != null) Text(historyError.orEmpty(), color = p.danger, fontSize = 12.sp, lineHeight = 16.sp)
                history?.forEach { revision ->
                    var revisionRevealed by rememberSaveable(message.id, revision.rev) {
                        mutableStateOf(revision.contentWarning == null)
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = p.line)
                    Text("${if (revision.original) "Original" else "Edit ${revision.rev}"} · " +
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(revision.at)),
                        color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp)
                    if (revision.contentWarning != null) Text(
                        "Content warning: ${revision.contentWarning} · ${if (revisionRevealed) "Hide" else "Show"}",
                        color = p.accent, fontSize = 14.sp, lineHeight = 19.sp,
                        modifier = Modifier.clickable { revisionRevealed = !revisionRevealed }.heightIn(min = 48.dp).wrapContentSize(Alignment.CenterStart))
                    if (revisionRevealed) Text(revision.text, color = p.ink, fontSize = 14.sp, lineHeight = 19.sp)
                }
            }
        run {
            Column(Modifier.fillMaxWidth()) {
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
                // a link that opens this message, and shows it as a preview in a chat (D-109)
                val linkBase = chorus.device?.base
                if (held == null && linkBase != null) ChatActionRow(R.drawable.ic_copy, "Copy link") {
                    val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Link", garden.vayne.chorus.data.ChorusLinks.linkTo(linkBase, "message", message.id)))
                    menuOpen = false
                }
                if (onPin != null) ChatActionRow(if (message.pinned) R.drawable.ic_unpin else R.drawable.ic_pin,
                    if (message.pinned) "Unpin" else "Pin") { menuOpen = false; onPin() }
                if (onReplyIn != null) ChatActionRow(R.drawable.ic_reply, "Reply in another chat") { menuOpen = false; onReplyIn() }
                if (onQuote != null && (message.cw == null || revealed)) ChatActionRow(R.drawable.ic_format_quote, "Quote") { menuOpen = false; onQuote() }
                if (onForward != null) ChatActionRow(R.drawable.ic_forward, "Forward") { menuOpen = false; onForward() }
                if (onSelect != null) ChatActionRow(R.drawable.ic_check, "Select") { menuOpen = false; onSelect() }
                if (onEdit != null && (message.cw == null || revealed)) ChatActionRow(R.drawable.ic_edit, "Edit") { menuOpen = false; onEdit() }
                if (held == null && message.edited) ChatActionRow(R.drawable.ic_history, if (history == null) "Edit history" else "Hide edit history") {
                    if (history != null) history = null else actions.launch {
                        try { history = chorus.revisions(message.id); historyError = null }
                        catch (e: Exception) { historyError = e.message ?: "Could not load edit history." }
                    }
                }
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
                MenuDrawer(who, { who = false }, "Reacted with ${r.emoji}", "${r.memberIds.size} ${if (r.memberIds.size == 1) "reaction" else "reactions"}") {
                    for (id in r.memberIds) Text(nameOf(id), color = p.ink, fontSize = 16.sp, lineHeight = 22.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp))
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
    scope: String? = null, opKind: String = "attachment.create", expectedEditorOwner: String? = null): String {
    check(expectedEditorOwner == null || expectedEditorOwner == chorus.editorOwner) { "The account changed. Reopen this draft in its account." }
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
    chorus.create(opKind, id, payload, scope = scope ?: "account:$account", expectedEditorOwner = expectedEditorOwner)
    return id
}

/** "Foxglove started fronting · 10:30" between messages in our own chats. */
@Composable
private fun FrontLine(line: ChatPresentation.FrontEvent) {
    val p = LocalChorusPalette.current
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(line.text + " · " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(line.at)), color = p.ink3,
            fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.Center,
            modifier = Modifier.background(p.surface, RoundedCornerShape(20.dp)).padding(horizontal = 12.dp, vertical = 4.dp))
    }
}

@Composable
private fun TimelineMarker(marker: ChatPresentation.TimelineMarker) {
    val event = marker.event
    if (event != null) FrontLine(event)
    else {
        val p = LocalChorusPalette.current
        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text(ChatPresentation.dateLabel(marker.at), color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp,
                modifier = Modifier.background(p.surface, RoundedCornerShape(20.dp)).padding(horizontal = 12.dp, vertical = 4.dp))
        }
    }
}

private fun notifyLabel(level: String) = when (level) { "all" -> "All messages"; "mentions" -> "Mentions only"; else -> "Off (muted)" }
