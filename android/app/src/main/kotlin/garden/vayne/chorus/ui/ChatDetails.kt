package garden.vayne.chorus.ui

import android.graphics.Bitmap
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.*
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SendAsSheet(model: Model, channelId: String, members: List<Member>, current: String?,
    onChoose: (String) -> Unit, onDismiss: () -> Unit) {
    val p = LocalChorusPalette.current
    val presence = ChatPresentation.presence(model, channelId).filter { it.subjectType == "member" }
    val frontId = ChatSpeaker.frontOf(model, channelId)
    val presentIds = if (model.namedFronts == null) presence.filter { it.level == "front" || it.level == "cocon" }.map { it.subjectId }.toSet()
        else model.namedFronts.resolved[frontId]?.authorship.orEmpty().map { it.displayEntry() }
            .filter { it.subjectType == "member" }.map { it.subjectId }.toSet()
    val frontName = model.namedFronts?.fronts?.get(frontId)?.name ?: "Body"
    val present = presence.filter { it.subjectId in presentIds }.mapNotNull { entry -> members.find { it.id == entry.subjectId } }
    ChorusSheet(onDismissRequest = onDismiss, containerColor = p.surface) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text("Send message as…", color = p.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            for ((heading, candidates) in listOf("Fronting · $frontName" to present, "Not fronting" to members.filter { it.id !in presentIds })) {
                if (candidates.isNotEmpty()) {
                    Text(heading, color = p.accent, fontSize = 13.sp, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
                    for (member in candidates) {
                        val entry = presence.firstOrNull { it.subjectId == member.id }
                        Row(Modifier.fillMaxWidth().background(if (current == member.id) p.accentSoft else p.surface)
                            .clickable { onChoose(member.id) }.heightIn(min = 64.dp).padding(horizontal = 20.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            ChatAvatar(member, 40.dp)
                            Column(Modifier.weight(1f)) {
                                Text(member.shownName, color = p.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                val detail = listOfNotNull(ChatPresentation.layer(model, entry), member.pronouns?.takeIf { it.isNotBlank() }).joinToString(" · ")
                                if (detail.isNotEmpty()) Text(detail, color = p.ink3, fontSize = 13.sp)
                            }
                            if (current == member.id) ChatIcon(R.drawable.ic_check, "Current speaker", p.accent)
                        }
                    }
                }
            }
        }
    }
}


/** F5.26 chat info: who is here and what the channel has shared, from local data only. */
@Composable
internal fun ChatInfoPane(chorus: Chorus, channel: ChatChannel, space: ChatSpace, model: Model, messages: List<ChatMessage>,
    library: List<ChatMessage>, directory: SpaceInfo?, onSearch: () -> Unit, onClose: (() -> Unit)? = null,
    notifyLevel: String = "mentions", onNotify: (String) -> Unit = {},
    onShowMessage: (String) -> Unit = {}, modifier: Modifier = Modifier,
    title: String = channel.name, subtitle: String? = null, connections: List<SpaceAccount> = emptyList(),
    foreign: Map<String, ForeignAuthor> = emptyMap(), speakerId: String? = null,
    onSpaceChanged: () -> Unit = {}, onOpenChannel: (String) -> Unit = {}, onLeft: () -> Unit = {}) {
    val p = LocalChorusPalette.current
    val presence = ChatPresentation.presence(model, channel.id)
    // a members' chat, or a channel kept to some members, lists just them (D-097, D-099), with
    // everyone inside a subsystem among them
    val included = remember(model, channel.memberIds) { garden.vayne.chorus.data.Subsystems.including(model, channel.memberIds).toSet() }
    val members = if (space.kind == "internal") model.active.filter { channel.memberIds.isEmpty() || it.id in included } else emptyList()
    var editChannel by rememberSaveable(channel.id) { mutableStateOf(false) }
    var editGroup by rememberSaveable(channel.id) { mutableStateOf(false) }
    val me = chorus.device?.accountId.orEmpty()
    val role = ChatManage.roleIn(model.spaceRoles, space.id, directory, me)
    val groupEditable = ChatManage.canEditSpace(space.kind, role)
    val spaceChats = model.channels.filter { it.spaceId == space.id && it.kind != "thread" }
    val ownPrivate = ChatManage.sides(channel, space, model.members.mapTo(HashSet()) { it.id })?.mine?.isNotEmpty() == true
    // who may edit this chat: our own space's chats, a group's for its managers, our private chat in a DM
    val channelEditable = channel.kind != "thread" && when (space.kind) {
        "internal" -> !model.isPerson
        "dm" -> channel.kind == "member_dm" && ownPrivate
        else -> groupEditable
    }
    val asGroup = groupEditable && spaceChats.size <= 1
    if (editChannel) ChannelEditor(chorus, model, space.id, channel, { editChannel = false }, { _, _ -> editChannel = false },
        canDelete = space.kind == "internal" || groupEditable || ownPrivate)
    if (editGroup) ChannelEditor(chorus, model, space.id, spaceChats.singleOrNull(), { editGroup = false }, { _, _ -> editGroup = false },
        group = space)
    var tab by rememberSaveable(channel.id) { mutableStateOf("members") }
    var shown by remember(channel.id, tab) { mutableStateOf(60) }
    var viewing by remember(channel.id) { mutableStateOf<ChatFileItem?>(null) }
    val media = remember(library) { ChatLibrary.media(library) }
    val files = remember(library) { ChatLibrary.files(library) }
    val links = remember(library) { ChatLibrary.links(library) }
    Column(modifier.fillMaxHeight().background(p.surface).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onClose != null) ChatIconButton(R.drawable.ic_back, "Back to conversation", onClose)
            else Text("Chat info", color = p.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ChannelDisc(channel.copy(name = title), size = 72.dp)
            Text(title, color = p.ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
            val kind = subtitle ?: when { space.kind == "dm" || channel.isMemberDm -> "Direct"; space.kind == "internal" -> "Internal"
                spaceChats.size <= 1 -> "Group"; else -> "Shared · ${space.name}" }
            val count = when {
                space.kind == "internal" && !model.isPerson -> "${members.size} ${if (members.size == 1) "member" else "members"}"
                directory != null && space.kind != "internal" -> "${directory.accounts.size} ${if (directory.accounts.size == 1) "account" else "accounts"}"
                else -> null
            }
            Text(listOfNotNull(kind, count).joinToString(" · "), color = p.ink3, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (notifyLevel == "none") InfoAction(R.drawable.ic_bell, "Unmute") { onNotify(model.accountPrefs.defaultNotify(space.kind)) }
                else InfoAction(R.drawable.ic_bell_off, "Mute") { onNotify("none") }
                InfoAction(R.drawable.ic_search, "Search", onSearch)
                if (asGroup) InfoAction(R.drawable.ic_edit, "Edit") { editGroup = true }
                else if (channelEditable) InfoAction(R.drawable.ic_edit, "Edit") { editChannel = true }
            }
            channel.topic?.takeIf { it.isNotBlank() }?.let { topic ->
                Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    Text(topic, color = p.ink, fontSize = 15.sp)
                    Text("Description", color = p.ink3, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        InfoTabs(listOf("members" to "Members", "media" to "Media", "files" to "Files", "links" to "Links"), tab) { tab = it }
        HorizontalDivider(color = p.line)
        when (tab) {
            "media" -> {
                if (media.isEmpty()) EmptyState(R.drawable.ic_image, "Nothing yet")
                for (row in media.take(shown).chunked(3)) Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 1.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (item in row) MediaTile(item, chorus, Modifier.weight(1f).aspectRatio(1f)) { viewing = item }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                if (media.size > shown) TextButton(onClick = { shown += 60 }, modifier = Modifier.padding(8.dp)) { Text("Show more") }
            }
            "files" -> {
                if (files.isEmpty()) EmptyState(R.drawable.ic_file, "Nothing yet")
                for (item in files.take(shown)) FileRow(item, chorus, ChatLibrary.who(item.message, model))
                if (files.size > shown) TextButton(onClick = { shown += 60 }, modifier = Modifier.padding(8.dp)) { Text("Show more") }
            }
            "links" -> {
                if (links.isEmpty()) EmptyState(R.drawable.ic_link, "Nothing yet")
                for (link in links.take(shown)) LinkRow(link, ChatLibrary.who(link.message, model))
                if (links.size > shown) TextButton(onClick = { shown += 60 }, modifier = Modifier.padding(8.dp)) { Text("Show more") }
            }
            else -> Column(Modifier.padding(16.dp)) {
                if (space.kind == "internal" && !model.isPerson) {
                    for (member in members) {
                        val entry = presence.firstOrNull { it.subjectType == "member" && it.subjectId == member.id }
                        val mark = model.readMarks[channel.id].orEmpty().firstOrNull { it.member == member.id }
                        val latest = messages.lastOrNull()
                        val caughtUp = mark != null && latest != null && !ReadTracking.needsMark(latest.occurredAt, latest.id, listOf(mark), member.id)
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            ChatAvatar(member, 36.dp)
                            Column(Modifier.weight(1f)) {
                                Text(member.shownName, color = p.ink, fontSize = 15.sp)
                                val detail = listOfNotNull(ChatPresentation.layer(model, entry), "Caught up".takeIf { caughtUp }).joinToString(" · ")
                                if (detail.isNotBlank()) Text(detail, color = if (caughtUp) p.accent else p.ink3, fontSize = 12.sp)
                            }
                        }
                    }
                } else if (space.kind != "internal") {
                    ChatSpacePanel(chorus, model, space, channel, directory, connections, foreign, speakerId,
                        onEditGroup = if (groupEditable) ({ editGroup = true }) else null, onChanged = onSpaceChanged,
                        onOpenChannel = onOpenChannel, onLeft = onLeft)
                }
                // Advanced (DESIGN §6): who may do what here, and slow mode; only for those who manage it
                if (space.kind != "dm" && channel.kind != "thread" &&
                    ChannelPermissions.canManage(model.channelPermissionState, channel, me, directory?.ownerAccountId)) {
                    var advanced by rememberSaveable(channel.id) { mutableStateOf(false) }
                    TextButton(onClick = { advanced = !advanced }, modifier = Modifier.padding(top = 8.dp)) {
                        Text(if (advanced) "Advanced · Hide" else "Advanced · Permissions and slow mode")
                    }
                    if (advanced) ChatPermissions(chorus, model, channel, directory, connections)
                }
            }
        }
    }
    viewing?.let { item ->
        MediaViewer(item.attachment, chorus, ChatLibrary.who(item.message, model), item.message.occurredAt,
            item.message.cw?.let { "Content warning: $it" } ?: "Spoiler".takeIf { item.attachment.spoiler }, onClose = { viewing = null },
            onShowMessage = if (messages.any { it.id == item.message.id }) ({ viewing = null; onShowMessage(item.message.id) }) else null)
    }
}

@Composable
private fun InfoAction(icon: Int, label: String, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    Column(Modifier.width(72.dp).heightIn(min = 64.dp).clip(RoundedCornerShape(12.dp)).background(p.surface2)
        .clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ChatIcon(icon, tint = p.accent)
        Text(label, color = p.ink, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun InfoTabs(tabs: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().padding(top = 16.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
        for ((id, label) in tabs) {
            val on = id == selected
            Box(Modifier.heightIn(min = 48.dp).clickable { onSelect(id) }.semantics { this.selected = on }.padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center) {
                Text(label, color = if (on) p.accent else p.ink2, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.drawBehind {
                        if (on) drawRect(p.accent, topLeft = Offset(0f, size.height + 10.dp.toPx()), size = Size(size.width, 2.dp.toPx()))
                    })
            }
        }
    }
}

/** One shared file: what, how big, from whom and when; covered ones ask before opening. */
@Composable
private fun FileRow(item: ChatFileItem, chorus: Chorus, who: String) {
    val p = LocalChorusPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val a = item.attachment
    var revealed by remember(a.id) { mutableStateOf(!ChatLibrary.covered(item)) }
    var opening by remember(a.id) { mutableStateOf(false) }
    var openError by remember(a.id) { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxWidth().clickable(enabled = !opening, onClickLabel = if (revealed) "Open file" else "Show file") {
        if (!revealed) revealed = true
        else { opening = true; openError = null; scope.launch { openError = openAttachment(ctx, chorus, a); opening = false } }
    }.heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).background(p.surface2, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            ChatIcon(if (revealed) R.drawable.ic_file else R.drawable.ic_lock, tint = p.accent)
        }
        Column(Modifier.weight(1f)) {
            Text(if (revealed) a.filename else item.message.cw?.let { "Content warning: $it" } ?: "Spoiler file",
                color = p.ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(ChatLibrary.size(a.size), who, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.message.occurredAt)))
                .joinToString(" · "), color = p.ink3, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (opening) Text("Opening…", color = p.ink3, fontSize = 12.sp)
            if (openError != null) Text(openError.orEmpty(), color = p.danger, fontSize = 12.sp)
        }
    }
}

/** A shared link opens in the browser; one from behind a content warning asks first. */
@Composable
private fun LinkRow(link: ChatLink, who: String) {
    val p = LocalChorusPalette.current
    val ctx = LocalContext.current
    var revealed by remember(link.message.id, link.url) { mutableStateOf(link.message.cw == null) }
    var openError by remember(link.url) { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxWidth().clickable(onClickLabel = if (revealed) "Open link" else "Show link") {
        if (!revealed) revealed = true
        else try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url))); openError = null }
        catch (_: ActivityNotFoundException) { openError = "No app can open this link." }
    }.heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).background(p.surface2, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            ChatIcon(if (revealed) R.drawable.ic_link else R.drawable.ic_lock, tint = p.accent)
        }
        Column(Modifier.weight(1f)) {
            if (revealed) {
                Text(ChatLibrary.domain(link.url), color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(link.url, color = p.accent, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else Text("Content warning: ${link.message.cw}", color = p.ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$who · " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(link.message.occurredAt)),
                color = p.ink3, fontSize = 12.sp, maxLines = 1)
            if (openError != null) Text(openError.orEmpty(), color = p.danger, fontSize = 12.sp)
        }
    }
}

@Composable
private fun MediaTile(item: ChatFileItem, chorus: Chorus, modifier: Modifier, onOpen: () -> Unit) {
    val p = LocalChorusPalette.current
    val ctx = LocalContext.current
    val a = item.attachment
    val covered = ChatLibrary.covered(item)
    val bitmap by produceState<Bitmap?>(null, a.id, covered, chorus.device?.session) {
        value = null
        if (!covered && a.mime.startsWith("image/")) value = withContext(Dispatchers.IO) {
            Blobs.image(ctx.applicationContext, a.thumbHash ?: a.blobHash, chorus.device, maxPx = 360)
        }
    }
    val label = when {
        item.message.cw != null -> "Content warning: ${item.message.cw}"
        a.spoiler -> "Spoiler"
        else -> a.altText.ifBlank { a.filename }
    }
    Box(modifier.clip(RoundedCornerShape(4.dp)).background(p.surface3).clickable(onClick = onOpen)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        val shown = bitmap
        if (shown != null) Image(shown.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else ChatIcon(if (covered) R.drawable.ic_lock else R.drawable.ic_image, tint = p.ink3)
        if (covered) Text(if (item.message.cw != null) "CW" else "Spoiler", color = p.ink2, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(4.dp))
        if (a.mime.startsWith("video/")) Text("▶", color = Color.White, fontSize = 20.sp)
    }
}

/** Full-screen photo: covered content ([cover]) asks first; the original opens in another app. */
@Composable
internal fun MediaViewer(a: ChatAttachment, chorus: Chorus, who: String, at: Long, cover: String?,
    onClose: () -> Unit, onShowMessage: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var revealed by remember(a.id) { mutableStateOf(cover == null) }
    var openError by remember(a.id) { mutableStateOf<String?>(null) }
    val bitmap by produceState<Bitmap?>(null, a.id, revealed) {
        if (revealed && a.mime.startsWith("image/")) value = withContext(Dispatchers.IO) {
            Blobs.image(ctx.applicationContext, a.blobHash, chorus.device, maxPx = 2048)
        }
    }
    var menu by remember(a.id) { mutableStateOf(false) }
    // Everything the reader can act on sits in the top bar: a full-width dialog's window starts
    // below the status bar on Android 15+, so its lowest strip can land under the gesture bar.
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { ChatIcon(R.drawable.ic_close, "Close photo", Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(who.ifBlank { a.filename }, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    if (at > 0) Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(at)),
                        color = Color.White.copy(alpha = .7f), fontSize = 12.sp)
                }
                if (revealed || onShowMessage != null) {
                    IconButton(onClick = { menu = true }) { ChatIcon(R.drawable.ic_more, "Photo options", Color.White) }
                    MenuDrawer(menu, { menu = false }, a.filename.ifBlank { "Photo" }) {
                        if (onShowMessage != null) MenuRow("Show in chat", R.drawable.ic_chat) { menu = false; onShowMessage() }
                        if (revealed) MenuRow("Open in another app", R.drawable.ic_share) {
                            menu = false; scope.launch { openError = openAttachment(ctx, chorus, a) }
                        }
                    }
                }
            }
            if (revealed && a.altText.isNotBlank()) Text(a.altText, color = Color.White.copy(alpha = .85f), fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            if (openError != null) Text(openError.orEmpty(), color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val shown = bitmap
                when {
                    !revealed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(cover.orEmpty(), color = Color.White)
                        TextButton(onClick = { revealed = true }) { Text("Show") }
                    }
                    !a.mime.startsWith("image/") -> Text(a.filename, color = Color.White)
                    shown == null -> Text("Loading, or unavailable offline.", color = Color.White.copy(alpha = .7f))
                    else -> Image(shown.asImageBitmap(), contentDescription = a.altText.ifBlank { a.filename },
                        contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}
