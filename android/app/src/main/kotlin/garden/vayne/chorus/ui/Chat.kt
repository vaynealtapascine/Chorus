package garden.vayne.chorus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
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
import garden.vayne.chorus.data.PrivateReplies
import garden.vayne.chorus.data.ChatCompose
import garden.vayne.chorus.data.ChatSpace
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Model
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class StageRows(val channelId: String, val accountId: String, val spaceKind: String,
    val viewingAs: String?, val front: List<Entry>, val window: ChannelWindow)
private data class PendingPrivateReply(val message: ChatMessage, val channelId: String? = null,
    val spaceId: String? = null, val speakerId: String? = null)

/** Local chat view: internal channels, shared spaces and account DMs use the same projection. */
@Composable
fun Chat(chorus: Chorus, model: Model, requestedSpace: String? = null,
    requestedChannel: String? = null, searchHit: SearchDocument? = null,
    onStageCapture: (Boolean) -> Unit = {},
    onDismissSearchHit: () -> Unit = {}, sharedDraft: SharedDraft? = null,
    sharedChannel: String? = null, onShareConsumed: () -> Unit = {}) {
    val p = LocalChorusPalette.current
    var selectedSpace by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(requestedSpace) {
        if (requestedSpace != null) selectedSpace = requestedSpace
    }
    var selectedChannel by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(requestedChannel) {
        if (requestedChannel != null) selectedChannel = requestedChannel
    }
    var viewingAs by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedAuthor by rememberSaveable { mutableStateOf("") }
    var draft by rememberSaveable { mutableStateOf("") }
    var cw by rememberSaveable { mutableStateOf("") }
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    var speakerSettingsOpen by rememberSaveable { mutableStateOf(false) }
    var followNext by remember { mutableStateOf(false) }
    var audience by rememberSaveable { mutableStateOf("all") }
    var visibleTo by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var replyTo by rememberSaveable { mutableStateOf<String?>(null) }
    var carriedReply by remember { mutableStateOf<ChatMessage?>(null) }
    var pendingPrivateReply by remember { mutableStateOf<PendingPrivateReply?>(null) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var directory by remember { mutableStateOf<Map<String, SpaceInfo>>(emptyMap()) }
    var connections by remember { mutableStateOf<List<SpaceAccount>>(emptyList()) }
    var foreignAuthors by remember { mutableStateOf<Map<String, ForeignAuthor>>(emptyMap()) }
    var refresh by remember { mutableStateOf(0) }
    var spaceAction by rememberSaveable { mutableStateOf("") }
    var newSpaceName by rememberSaveable { mutableStateOf("") }
    var invitedAccounts by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var staging by rememberSaveable { mutableStateOf(false) }
    var capturing by rememberSaveable { mutableStateOf(false) }
    var stageLimit by rememberSaveable { mutableStateOf(100) }
    var stageWindow by remember { mutableStateOf<StageRows?>(null) }
    var stageLoading by remember { mutableStateOf(false) }
    val actions = rememberCoroutineScope()
    val ctx = LocalContext.current
    // picked files waiting to be sent: staged (copied, hashed) only on send
    val attachments = remember { mutableStateListOf<PendingAttachment>() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        for (uri in uris) attachments.add(PendingAttachment.of(ctx, uri))
    }
    LaunchedEffect(chorus.device?.session, model.spaces.map { it.id }, refresh) {
        directory = emptyMap()
        connections = emptyList()
        val dev = chorus.device ?: return@LaunchedEffect
        try {
            directory = Spaces.list(dev)
            connections = Spaces.connections(dev).filter { it.id != dev.accountId }
        } catch (e: Exception) {
            error = e.message ?: "Could not load spaces."
        }
    }
    val space = model.spaces.find { it.id == selectedSpace } ?: model.spaces.firstOrNull()
    LaunchedEffect(space?.id, chorus.device?.session, refresh) {
        foreignAuthors = emptyMap()
        val dev = chorus.device ?: return@LaunchedEffect
        val id = space?.id?.takeIf { space.kind != "internal" } ?: return@LaunchedEffect
        try { foreignAuthors = Spaces.authorCards(dev, id) }
        catch (_: Exception) { /* Own members still have projection names; retry on next refresh. */ }
    }
    val channels = model.channels.filter { it.spaceId == space?.id }
    val channel = channels.find { it.id == selectedChannel } ?: channels.firstOrNull()
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
    val defaultAuthorId = remember(model, channel?.id) { channel?.id?.let { ChatSpeaker.pick(model, it) } }
    val replySpeakerId = selectedAuthor.takeIf { id -> model.active.any { it.id == id } } ?: defaultAuthorId
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
                } else {
                    val account = message.accountId?.takeIf { it != dev.accountId }
                        ?: throw IllegalArgumentException("This message is from your account.")
                    val targetSpace = Spaces.openDm(dev, account)
                    pendingPrivateReply = PendingPrivateReply(message, spaceId = targetSpace, speakerId = replySpeakerId)
                    selectedSpace = targetSpace
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
    val messages = model.chatMessages[channel?.id].orEmpty().filter {
        space != null && memberVisible(it, space.kind, model.current, viewingAs) &&
            accountVisible(it, space.kind, chorus.device?.accountId.orEmpty())
    }
    val readMarks = model.readMarks[channel?.id].orEmpty()
    LaunchedEffect(channel?.id, space?.id, messages.lastOrNull()?.id, readMarks,
        model.current, model.accountPrefs.readPerMember, chorus.device?.accountId, staging) {
        val latest = messages.lastOrNull() ?: return@LaunchedEffect
        val chosen = channel ?: return@LaunchedEffect
        val home = space ?: return@LaunchedEffect
        if (staging || chorus.device == null) return@LaunchedEffect
        try {
            for (reader in ReadTracking.readers(model.accountPrefs.readPerMember, model.current)) {
                if (!ReadTracking.needsMark(latest.occurredAt, latest.id, readMarks, reader)) continue
                chorus.create("read.mark", null, JSONObject().put("channel_id", chosen.id)
                    .put("message_id", latest.id).put("message_at", latest.occurredAt)
                    .put("reader_member_id", reader), scope = "space:${home.id}")
            }
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
    LaunchedEffect(channel?.id, chorus.device?.accountId) {
        selectedAuthor = ""
        replyTo = null
        carriedReply = null
        speakerSettingsOpen = false
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
        if (!staging || channel == null || space == null) { stageWindow = null; return@LaunchedEffect }
        stageLoading = true
        try { stageWindow = StageRows(channel.id, chorus.device?.accountId.orEmpty(), space.kind,
            viewingAs, model.current, chorus.stageWindow(channel.id, stageLimit, space.kind, model.current, viewingAs)) }
        finally { stageLoading = false }
    }
    LaunchedEffect(capturing) { onStageCapture(capturing) }
    DisposableEffect(Unit) { onDispose { onStageCapture(false) } }
    BackHandler(staging) {
        if (capturing) capturing = false else staging = false
    }
    if (staging && channel != null) {
        val window = stageWindow?.takeIf { it.channelId == channel.id && it.accountId == chorus.device?.accountId &&
            it.spaceKind == space?.kind && it.viewingAs == viewingAs && it.front == model.current }?.window
        val stageMessages = window?.messages?.filter {
            space != null && memberVisible(it, space.kind, model.current, viewingAs) &&
                accountVisible(it, space.kind, chorus.device?.accountId.orEmpty())
        } ?: messages
        ChatStage(chorus, channel, stageMessages, model, foreignAuthors, capturing,
            hasOlder = window?.hasOlder == true, loadingOlder = stageLoading,
            onLoadOlder = { stageLimit += 100 },
            onCapture = { capturing = it }, onClose = { capturing = false; staging = false })
        return
    }

    Column(Modifier.fillMaxSize().background(p.bg).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Chat", color = p.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (channel != null) ChatChip("Stage", false) { staging = true }
            if (connections.isNotEmpty()) ChatChip("New chat", false) { spaceAction = "new" }
            val canLeave = space != null && space.kind != "internal" &&
                directory[space.id]?.let { space.kind != "shared" || it.ownerAccountId != chorus.device?.accountId } == true
            if (canLeave) ChatChip("Leave", false) { spaceAction = "leave" }
        }
        if (error != null) Text(error.orEmpty(), color = p.accent, fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 16.dp))
        if (space == null) {
            Text("No spaces yet. Start a chat with someone you follow.",
                color = p.ink2, modifier = Modifier.padding(24.dp))
        } else {
        // One header row: space picker, channels, and (internal spaces) whose view to show
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            var spacesOpen by remember { mutableStateOf(false) }
            Box {
                ChatChip("${spaceLabel(space, directory[space.id], chorus.device?.accountId.orEmpty())} ▾", true) { spacesOpen = true }
                DropdownMenu(spacesOpen, { spacesOpen = false }) {
                    for (candidate in model.spaces) {
                        DropdownMenuItem(text = { Text(spaceLabel(candidate, directory[candidate.id], chorus.device?.accountId.orEmpty())) }, onClick = {
                            spacesOpen = false
                            selectedSpace = candidate.id
                            selectedChannel = ""
                            viewingAs = null
                            audience = "all"
                            visibleTo = emptyList()
                            replyTo = null
                            carriedReply = null
                            pendingPrivateReply = null
                            moreOpen = false
                        })
                    }
                }
            }
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(channels, key = { it.id }) { candidate ->
                    ChatChip("#${candidate.name}", candidate.id == channel?.id) {
                        selectedChannel = candidate.id
                        replyTo = null
                        carriedReply = null
                        pendingPrivateReply = null
                        replyTo = null
                    }
                }
            }
            if (space.kind == "internal") {
                var viewOpen by remember { mutableStateOf(false) }
                Box {
                    val viewer = model.active.find { it.id == viewingAs }?.shownName ?: "Present"
                    Text("👁 $viewer", color = p.ink2, fontSize = 13.sp,
                        modifier = Modifier.clickable { viewOpen = true }.padding(horizontal = 6.dp, vertical = 8.dp))
                    DropdownMenu(viewOpen, { viewOpen = false }) {
                        Text("Member visibility is a soft view setting. Whose view?", color = p.ink3, fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                        DropdownMenuItem(text = { Text("Everyone present") }, onClick = { viewingAs = null; viewOpen = false })
                        for (member in model.active) {
                            DropdownMenuItem(text = { Text(member.shownName) }, onClick = { viewingAs = member.id; viewOpen = false })
                        }
                    }
                }
            }
        }
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
                    color = p.accent, modifier = Modifier.clickable { revealed = !revealed })
                if (searchHit.cw == null || revealed) Text(searchHit.text, color = p.ink)
            }
        }
        if (channel == null) {
            Text("No channels in this space yet.", color = p.ink2, modifier = Modifier.padding(24.dp))
        } else {
        if (pendingPrivateReply != null) Text("Opening private chat · Cancel", color = p.accent,
            modifier = Modifier.clickable { pendingPrivateReply = null }.padding(horizontal = 16.dp))
        // A reversed list keeps its place on the message that was newest, so a new one would land
        // just out of view: follow it while the reader is at (or near) the bottom, and after sending.
        val listState = rememberLazyListState()
        val newest = messages.lastOrNull()?.id
        LaunchedEffect(newest) {
            if (newest != null && (listState.firstVisibleItemIndex <= 2 || followNext)) listState.animateScrollToItem(0)
            followNext = false
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), state = listState, reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages.asReversed(), key = { it.id }) { message ->
                val canPrivate = if (space.kind == "internal") replySpeakerId != null &&
                    message.authors.firstOrNull()?.let { it != replySpeakerId && model.member(it) != null } == true
                else message.accountId != null && message.accountId != chorus.device?.accountId
                ChatMessageCard(message, model, foreignAuthors, chorus, unseenByMessage[message.id].orEmpty(),
                    onReply = { replyTo = message.id; carriedReply = null },
                    onReplyPrivately = if (canPrivate) ({ replyPrivately(message) }) else null)
            }
            if (messages.isEmpty()) item {
                Text("No messages here yet.", color = p.ink2, modifier = Modifier.padding(16.dp))
            }
        }
        // Composer: one line (who's speaking, the text, options, send); options open above it
        Column(Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val author = model.active.find { it.id == selectedAuthor }
                ?: defaultAuthorId?.let(model::member)
            val target = messages.find { it.id == replyTo } ?: carriedReply?.takeIf { it.id == replyTo }
            if (target != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (carriedReply?.id == replyTo) "↪ Replying privately to a message" else "↪ Replying to a message",
                        color = p.ink2, fontSize = 12.sp)
                    Text("Cancel", color = p.accent, fontSize = 12.sp,
                        modifier = Modifier.clickable { replyTo = null; carriedReply = null })
                }
            } else if (replyTo != null) {
                Text("Reply target unavailable · Cancel", color = p.accent, fontSize = 12.sp,
                    modifier = Modifier.clickable { replyTo = null; carriedReply = null })
            }
            val audienceLabel = when (audience) {
                "members" -> "Chosen members (${visibleTo.size})"
                "system_only" -> "Only my system"
                else -> "Everyone here"
            }
            for ((i, a) in attachments.withIndex()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("📎 ${a.name}", color = p.ink, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    ChatChip(if (a.spoiler) "Spoiler ✓" else "Spoiler", a.spoiler) { attachments[i] = a.copy(spoiler = !a.spoiler) }
                    Text("✕", color = p.ink2, modifier = Modifier.clickable { attachments.removeAt(i) }.padding(6.dp)
                        .semantics { contentDescription = "Remove ${a.name}" })
                }
                if (a.mime.startsWith("image/")) {
                    OutlinedTextField(a.alt, { attachments[i] = a.copy(alt = it) }, placeholder = { Text("Describe the image (alt text)") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
            }
            if (moreOpen) {
                Text("📎 Attach images or files", color = p.accent, fontSize = 14.sp,
                    modifier = Modifier.clickable { picker.launch("*/*") }.padding(vertical = 6.dp))
                OutlinedTextField(cw, { cw = it }, placeholder = { Text("Content warning (optional)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
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
                    Text("Everyone in this space can see who is speaking.", color = p.ink3, fontSize = 12.sp)
                }
                if (!model.isPerson) {
                    Text(if (speakerSettingsOpen) "Advanced · Hide speaker default" else "Advanced · Speaker default ›",
                        color = p.accent, fontSize = 13.sp,
                        modifier = Modifier.clickable { speakerSettingsOpen = !speakerSettingsOpen }.padding(vertical = 4.dp))
                    if (speakerSettingsOpen) {
                        val setting = model.speakerDefaults[channel.id] ?: SpeakerDefault()
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            item { ChatChip("Front", setting.mode == "front") { setSpeakerDefault("front") } }
                            item { ChatChip("Last speaker", setting.mode == "latch") { setSpeakerDefault("latch") } }
                            item { ChatChip("Ask me", setting.mode == "off") { setSpeakerDefault("off") } }
                            item { ChatChip("One member", setting.mode == "member") {
                                (setting.memberId ?: author?.id ?: model.active.firstOrNull()?.id)?.let {
                                    setSpeakerDefault("member", it)
                                }
                            } }
                        }
                        if (setting.mode == "member") {
                            Text("Always speak as", color = p.ink2, fontSize = 12.sp)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(model.active, key = { it.id }) { member ->
                                    ChatChip(member.shownName, member.id == setting.memberId) {
                                        setSpeakerDefault("member", member.id)
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (audience != "all" || cw.isNotBlank()) {
                Text(listOfNotNull(audienceLabel.takeIf { audience != "all" }, "CW: ${cw.trim()}".takeIf { cw.isNotBlank() })
                    .joinToString(" · "), color = p.ink2, fontSize = 12.sp)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                var authorsOpen by remember { mutableStateOf(false) }
                Box {
                    val tone = author?.let { tonesOf(it.color) }
                    Text(author?.glyph ?: "?", fontSize = 18.sp, color = tone?.name ?: p.ink2,
                        modifier = Modifier.size(40.dp).clip(CircleShape).background(tone?.tint ?: p.surface2)
                            .clickable(enabled = model.active.isNotEmpty()) { authorsOpen = true }
                            .wrapContentSize(Alignment.Center)
                            .semantics { contentDescription = "Speaking as ${author?.shownName ?: "nobody yet"}" })
                    DropdownMenu(authorsOpen, { authorsOpen = false }) {
                        Text("Who's speaking", color = p.ink3, fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                        for (member in model.active) {
                            DropdownMenuItem(text = { Text(member.shownName) }, onClick = {
                                selectedAuthor = member.id
                                authorsOpen = false
                            })
                        }
                    }
                }
                OutlinedTextField(draft, { draft = it },
                    placeholder = {
                        Text(if (author == null) "Who's speaking? Tap ?" else "${author.shownName} in #${channel.name}", maxLines = 1)
                    },
                    modifier = Modifier.weight(1f), minLines = 1, maxLines = 4)
                Text(if (moreOpen) "✕" else "⋯",
                    color = if (moreOpen || audience != "all" || cw.isNotBlank()) p.accent else p.ink2,
                    fontSize = 20.sp,
                    modifier = Modifier.clickable { moreOpen = !moreOpen }.padding(8.dp)
                        .semantics { contentDescription = if (moreOpen) "Fewer options" else "Content warning and audience" })
                val canSend = !busy && pendingPrivateReply == null && (draft.isNotBlank() || attachments.isNotEmpty()) &&
                    author != null && (replyTo == null || target != null) &&
                    (audience != "members" || visibleTo.isNotEmpty())
                Text(if (busy) "…" else "↑", color = if (canSend) p.bg else p.ink3, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(if (canSend) p.accent else p.surface2)
                        .clickable(enabled = canSend) {
                            val speakerId = author?.id ?: return@clickable
                            busy = true
                            error = null
                            actions.launch {
                                try {
                                    val scope = "space:${space.id}"
                                    val attachmentIds = attachments.map { a -> sendAttachment(chorus, ctx, a, scope) }
                                    val payload = ChatCompose.payload(model, channel.id, speakerId, draft, cw,
                                        audience, visibleTo.toSet(), space.kind, replyTo = target?.id, attachmentIds = attachmentIds)
                                    followNext = true
                                    chorus.create("message.send", chorus.newId(), payload, scope = scope)
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
                        .semantics { contentDescription = "Send to $audienceLabel" })
            }
        }
        }
        }
    }
    if (spaceAction == "new") {
        AlertDialog(onDismissRequest = { spaceAction = "" }, title = { Text("Start a chat") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Messages in a shared space show who is speaking as they are sent.")
                    OutlinedTextField(newSpaceName, { newSpaceName = it.take(80) }, label = { Text("Shared space name") }, singleLine = true)
                    for (account in connections) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            ChatChip("DM", false) {
                                val dev = chorus.device ?: return@ChatChip
                                busy = true; error = null; spaceAction = ""
                                actions.launch {
                                    try { selectedSpace = Spaces.openDm(dev, account.id); selectedChannel = ""; refresh++ }
                                    catch (e: Exception) { error = e.message ?: "Could not start the DM." }
                                    finally { busy = false }
                                }
                            }
                            ChatChip(if (account.id in invitedAccounts) "✓ ${account.shownName}" else account.shownName,
                                account.id in invitedAccounts) {
                                invitedAccounts = if (account.id in invitedAccounts) invitedAccounts - account.id else invitedAccounts + account.id
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && newSpaceName.isNotBlank() && invitedAccounts.isNotEmpty(), onClick = {
                    val dev = chorus.device ?: return@TextButton
                    busy = true; error = null; spaceAction = ""
                    actions.launch {
                        try {
                            selectedSpace = Spaces.createShared(dev, newSpaceName, invitedAccounts)
                            selectedChannel = ""; newSpaceName = ""; invitedAccounts = emptyList(); refresh++
                        } catch (e: Exception) { error = e.message ?: "Could not create the space." }
                        finally { busy = false }
                    }
                }) { Text("Create shared space") }
            }, dismissButton = { TextButton(onClick = { spaceAction = "" }) { Text("Cancel") } })
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
}

private fun spaceLabel(space: ChatSpace, info: SpaceInfo?, me: String): String = when (space.kind) {
    "internal" -> if (space.name.isBlank() || space.name.equals("Home", ignoreCase = true)) "Home" else "Home · ${space.name}"
    "dm" -> "DM · ${info?.accounts?.firstOrNull { it.id != me }?.shownName ?: "Direct message"}"
    else -> space.name
}

@Composable
private fun ChatChip(label: String, selected: Boolean, action: () -> Unit) {
    val p = LocalChorusPalette.current
    Text(label, color = if (selected) p.accent else p.ink2, fontSize = 14.sp,
        modifier = Modifier.background(if (selected) p.surface2 else p.surface, RoundedCornerShape(20.dp))
            .clickable(onClick = action).padding(horizontal = 12.dp, vertical = 8.dp))
}

@Composable
private fun ChatMessageCard(message: ChatMessage, model: Model, foreignAuthors: Map<String, ForeignAuthor>,
    chorus: Chorus, unseenBy: List<String>, onReply: () -> Unit, onReplyPrivately: (() -> Unit)?) {
    val p = LocalChorusPalette.current
    val actions = rememberCoroutineScope()
    var revealed by rememberSaveable(message.id) { mutableStateOf(false) }
    var history by remember(message.id, message.text, message.cw) { mutableStateOf<List<MessageRevision>?>(null) }
    var historyError by remember(message.id) { mutableStateOf<String?>(null) }
    var showUnseen by rememberSaveable(message.id) { mutableStateOf(false) }
    val authors = message.authors.map { model.member(it)?.shownName ?: foreignAuthors[it]?.name ?: "Someone" }
        .joinToString(" & ").ifEmpty { "Someone" }
    Column(Modifier.fillMaxWidth().background(p.surface, RoundedCornerShape(12.dp))
        .border(1.dp, p.line, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(authors, color = p.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(message.occurredAt)),
                color = p.ink3, fontSize = 11.sp)
            if (unseenBy.isNotEmpty()) {
                val names = unseenBy.map { model.member(it)?.shownName ?: "Someone" }.joinToString(", ")
                Text("●", color = p.accent, fontSize = 12.sp,
                    modifier = Modifier.clickable { showUnseen = !showUnseen }
                        .semantics { contentDescription = "Not seen by $names" })
            }
            Text("↩", color = p.accent, fontSize = 16.sp,
                modifier = Modifier.clickable(onClick = onReply).padding(horizontal = 4.dp)
                    .semantics { contentDescription = "Reply" })
        }
        if (showUnseen && unseenBy.isNotEmpty()) Text("Not seen by " + unseenBy.map {
            model.member(it)?.shownName ?: "Someone"
        }.joinToString(", "), color = p.ink3, fontSize = 12.sp)
        val tags = listOfNotNull(
            "↪ reply".takeIf { message.replyTo != null },
            "Only this system".takeIf { message.visibilityMode == "system_only" },
            "Chosen members".takeIf { message.visibilityMode == "members" },
        )
        if (tags.isNotEmpty()) Text(tags.joinToString(" · "), color = p.ink3, fontSize = 12.sp)
        if (onReplyPrivately != null) Text("Reply privately", color = p.accent, fontSize = 12.sp,
            modifier = Modifier.clickable(onClick = onReplyPrivately).padding(vertical = 3.dp))
        if (message.cw != null) {
            Text("Content warning: ${message.cw} · ${if (revealed) "Hide" else "Show"}", color = p.accent,
                modifier = Modifier.clickable { revealed = !revealed })
        }
        if (message.cw == null || revealed) {
            Text(message.text, color = p.ink, fontSize = 16.sp)
            for (attachment in message.attachments) {
                ChatAttachmentView(attachment, chorus)
            }
            if (message.edited) {
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
                            color = p.ink3, fontSize = 12.sp)
                        if (revision.contentWarning != null) Text(
                            "Content warning: ${revision.contentWarning} · ${if (revisionRevealed) "Hide" else "Show"}",
                            color = p.accent, modifier = Modifier.clickable { revisionRevealed = !revisionRevealed })
                        if (revisionRevealed) Text(revision.text, color = p.ink)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ChatAttachmentView(attachment: ChatAttachment, chorus: Chorus) {
    val p = LocalChorusPalette.current
    val ctx = LocalContext.current
    val device = chorus.device
    val image = attachment.mime.startsWith("image/")
    val scope = rememberCoroutineScope()
    var opening by remember(attachment.id) { mutableStateOf(false) }
    var openError by remember(attachment.id) { mutableStateOf<String?>(null) }
    var opened by rememberSaveable(attachment.id) { mutableStateOf(false) }
    if (attachment.spoiler && !opened) {
        Text("Spoiler attachment · Reveal", color = p.accent, fontSize = 13.sp,
            modifier = Modifier.clickable { opened = true })
        return
    }
    Text("Attachment: ${attachment.filename}", color = p.ink2, fontSize = 13.sp)
    if (attachment.altText.isNotBlank()) Text(attachment.altText, color = p.ink3, fontSize = 12.sp)
    if (image) {
        if (!opened) {
            Text(if (attachment.spoiler) "Reveal image spoiler" else "View image", color = p.accent,
                modifier = Modifier.clickable { opened = true }.padding(vertical = 4.dp))
        } else {
            val hash = attachment.thumbHash ?: attachment.blobHash
            val bitmap by produceState<android.graphics.Bitmap?>(null, hash, device?.session) {
                value = withContext(Dispatchers.IO) { Blobs.image(ctx.applicationContext, hash, device) }
            }
            if (bitmap == null) {
                Text("Loading image, or unavailable offline.", color = p.ink3, fontSize = 12.sp)
            } else {
                Image(bitmap!!.asImageBitmap(), contentDescription = attachment.altText.ifBlank { attachment.filename },
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(200.dp))
            }
            Text("Hide image", color = p.accent, fontSize = 12.sp, modifier = Modifier.clickable { opened = false })
        }
    }
    TextButton(enabled = !opening, onClick = {
        opening = true
        openError = null
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                Blobs.file(ctx.applicationContext, attachment.blobHash, device)
            }
            if (file == null) {
                openError = "File unavailable offline or download failed."
            } else {
                try {
                    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, attachment.mime.ifBlank { "application/octet-stream" })
                        clipData = ClipData.newUri(ctx.contentResolver, attachment.filename, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    ctx.startActivity(Intent.createChooser(intent, "Open ${attachment.filename}"))
                } catch (_: ActivityNotFoundException) {
                    openError = "No app can open this file type."
                } catch (_: SecurityException) {
                    openError = "Could not share this file."
                } catch (_: IllegalArgumentException) {
                    openError = "Could not share this file."
                }
            }
            opening = false
        }
    }) { Text(if (opening) "Opening…" else "Open file") }
    if (openError != null) Text(openError.orEmpty(), color = p.danger, fontSize = 12.sp)
    if (attachment.spoiler && !image) {
        Text("Hide attachment", color = p.accent, fontSize = 12.sp,
            modifier = Modifier.clickable { opened = false })
    }
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
