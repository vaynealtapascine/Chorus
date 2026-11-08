package garden.vayne.chorus.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.*
import garden.vayne.chorus.designsystem.LocalChorusDark
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ChatIcon(resource: Int, description: String? = null, tint: Color = LocalChorusPalette.current.ink2) {
    Icon(painterResource(resource), description, Modifier.size(20.dp), tint = tint)
}

@Composable
internal fun ChatIconButton(resource: Int, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) { ChatIcon(resource, description) }
}

/** Presence is labelled for TalkBack; independent configured layers keep their actual names. */
@Composable
internal fun ChatAvatar(member: Member, size: Dp = 48.dp, ring: String? = null, label: String? = null) {
    val p = LocalChorusPalette.current
    Box(Modifier.size(size).semantics { contentDescription = label ?: member.shownName }, contentAlignment = Alignment.Center) {
        Avatar(member.glyph, member.color, if (ring == null) size else size - 8.dp, avatarBlob = member.avatarBlob, ring = false)
        if (ring != null) Canvas(Modifier.fillMaxSize()) {
            drawCircle(p.accent, radius = this.size.minDimension / 2 - 1.dp.toPx(),
                style = Stroke(2.dp.toPx(), pathEffect = if (ring == "dashed") PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null))
        }
    }
}

@Composable
internal fun ChannelDisc(channel: ChatChannel, member: Member? = null, size: Dp = 48.dp) {
    val p = LocalChorusPalette.current
    if (channel.avatarBlob != null || channel.color != null) Avatar(ChannelCompose.glyph(channel),
        channel.color ?: "#42A397", size, avatarBlob = channel.avatarBlob, ring = false)
    else if (member != null) ChatAvatar(member, size)
    else Box(Modifier.size(size).background(p.accentSoft, CircleShape), contentAlignment = Alignment.Center) {
        if (channel.isMemberDm) ChatIcon(R.drawable.ic_members, tint = p.accent)
        else Text(ChannelCompose.glyph(channel), color = p.accent, fontWeight = FontWeight.SemiBold, fontSize = (size.value * .45f).sp)
    }
}

/** A chat's disc in a list: a DM shows whoever is at the other end ([ChatListRow.icon], or [peer]
 * for a DM between our members), unless the chat has a picture of its own; anything else, its own. */
@Composable
internal fun ChatRowDisc(row: ChatListRow, size: Dp, peer: Member? = null) {
    val them = row.icon ?: peer
    if (them != null && row.channel.avatarBlob == null && (row.space.kind == "dm" || row.channel.isMemberDm)) ChatAvatar(them, size)
    else ChannelDisc(if (row.space.kind == "dm") row.channel.copy(name = row.title) else row.channel, peer, size)
}

/** Who a DM between our members is with, seen by [readerId] (else whoever speaks there): the list
 * and the open chat's header show the same face. */
internal fun ChatListRow.peer(model: Model, readerId: String?): Member? =
    if (channel.isMemberDm && space.kind == "internal")
        channel.memberIds.firstOrNull { it != (readerId ?: ChatSpeaker.pick(model, channel.id)) }?.let(model::member)
    else null

internal data class ChatListRow(val channel: ChatChannel, val space: ChatSpace, val title: String,
    val latest: ChatMessage?, val unread: Int, val sender: String? = null, val muted: Boolean = false, val pinned: Boolean = false,
    /** A private chat in a DM (D-098), or a channel kept to some of our members (D-097): a lock, not a globe or #. */
    val private: Boolean = false,
    /** Whose a private chat with another account is on our side ("• Fox"), so one member's chat with
     * someone isn't mistaken for another's. */
    val whose: String? = null,
    /** Archived (`chat_archived:`), with nothing newer since: listed under Archived chats. */
    val archived: Boolean = false,
    /** Who a DM with another account is with: the member a private chat is kept to on their side,
     * or the person (their own member) or account at the other end. */
    val icon: Member? = null,
    /** In a shared space with several channels: listed under the space's entry (owner, 2026-11-06). */
    val grouped: Boolean = false)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ChatListPane(model: Model, rows: List<ChatListRow>, selected: String?, reader: String?,
    onOpen: (ChatListRow) -> Unit, onNew: (() -> Unit)?, onSwitch: () -> Unit,
    onSearch: () -> Unit, modifier: Modifier = Modifier, readerId: String? = null, onNewChannel: (() -> Unit)? = null,
    onRowAction: ((ChatListRow, String) -> Unit)? = null,
    previews: Map<String, String> = emptyMap(),
    /** Chat folders (D-113): open their settings, or save a change made here. */
    onEditFolders: ((String?) -> Unit)? = null, onFolders: ((ChatFolders.Settings) -> Unit)? = null,
    /** The open conversation, on every layout: its space's channels show around it when it folds. */
    opened: String? = null,
    /** Pin, unpin or archive every channel of a space's entry at once ("pin", "unpin", "archive"). */
    onSpaceAction: ((List<ChatListRow>, String) -> Unit)? = null,
    /** The round button on a folder of shared spaces starts one (F5.22). */
    onNewShared: (() -> Unit)? = null) {
    val p = LocalChorusPalette.current
    val folders = model.accountPrefs.chatFolders
    // which folder is open stays on this device; a hidden or deleted one falls back to the first shown
    var chosenFolder by rememberSaveable { mutableStateOf("all") }
    val folder = chosenFolder.takeIf { it in folders.visible } ?: folders.visible.firstOrNull()
    // the chat whose folders are being chosen from its long-press menu
    var foldersFor by remember { mutableStateOf<ChatListRow?>(null) }
    var menu by remember { mutableStateOf(false) }
    // the chat whose long-press menu is open (pin, arrange pins, delete or clear)
    var rowMenu by remember { mutableStateOf<String?>(null) }
    // archived chats wait under "Archived chats" (overflow menu) until a new message brings them back
    var showArchived by rememberSaveable { mutableStateOf(false) }
    val archivedRows = rows.filter { it.archived }
    val archiveView = showArchived && archivedRows.isNotEmpty()
    androidx.activity.compose.BackHandler(archiveView) { showArchived = false }
    val listed = if (archiveView) archivedRows else rows.filter { !it.archived }
    // a shared space with several channels is one entry, like a Telegram forum; opening it lists its
    // channels (owner, 2026-11-06). Archived chats stay flat.
    var openSpace by rememberSaveable { mutableStateOf<String?>(null) }
    val inside = openSpace?.takeIf { !archiveView }?.let { id -> ChatOrganize.order(listed.filter { it.space.id == id },
        model.accountPrefs.chatPins, { it.channel.id }, { it.latest?.occurredAt ?: 0L }, { it.title }) }?.takeIf { it.isNotEmpty() }
    androidx.activity.compose.BackHandler(inside != null) { openSpace = null }
    // opening one of its channels from elsewhere shows the space's channels around it
    LaunchedEffect(opened) { rows.firstOrNull { it.channel.id == opened }?.takeIf { it.grouped }?.let { openSpace = it.space.id } }
    val pinnedCount = listed.count { it.pinned }
    val system = model.spaces.firstOrNull { it.kind == "internal" }?.name?.takeUnless { it == "Home" }
    Column(modifier.fillMaxHeight().background(p.bg)) {
        Row(Modifier.fillMaxWidth().background(p.surface).padding(start = if (archiveView) 4.dp else 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (archiveView) ChatIconButton(R.drawable.ic_back, "Back to chats") { showArchived = false }
            else if (inside != null) ChatIconButton(R.drawable.ic_back, "Back to chats") { openSpace = null }
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(if (archiveView) "Archived chats" else inside?.firstOrNull()?.space?.name ?: "Chats", color = p.ink, fontSize = 20.sp,
                    lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = if (archiveView) null
                    else if (inside != null) "${inside.size} ${if (inside.size == 1) "channel" else "channels"}"
                    else listOfNotNull(system, reader?.let { "reading as $it" }).joinToString(" · ").ifEmpty { null }
                sub?.let { Text(it, color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2) }
            }
            ChatIconButton(R.drawable.ic_search, "Search chats", onSearch)
            ChatIconButton(R.drawable.ic_more, "Chat options") { menu = true }
            MenuDrawer(menu, { menu = false }, "Chats") {
                if (onNew != null) MenuRow("New message", R.drawable.ic_edit) { menu = false; onNew() }
                if (onNewChannel != null) MenuRow("New channel", R.drawable.ic_plus) { menu = false; onNewChannel() }
                if (onRowAction != null && pinnedCount > 1) MenuRow("Arrange pinned chats", R.drawable.ic_pin) {
                    menu = false; listed.firstOrNull { it.pinned }?.let { onRowAction(it, "arrange") }
                }
                if (!archiveView && archivedRows.isNotEmpty()) MenuRow("Archived chats", R.drawable.ic_archive,
                    "${archivedRows.size} ${if (archivedRows.size == 1) "chat" else "chats"}") { menu = false; showArchived = true }
                if (onEditFolders != null) MenuRow("Chat folders", R.drawable.ic_folder) { menu = false; onEditFolders(null) }
            }
        }
        if (!model.isPerson && !archiveView && inside == null && folders.showHere) {
            val presence = ChatPresentation.presence(model, selected).filter { it.subjectType == "member" }
            Column(Modifier.fillMaxWidth().background(p.surface)) {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(presence, key = { it.subjectId }) { entry ->
                        model.member(entry.subjectId)?.let { member ->
                            val label = ChatPresentation.layer(model, entry)
                            // Legacy front/co-conscious have defined ring semantics. Custom layers do not.
                            val ring = if (model.namedFronts == null) when (entry.level) { "front" -> "solid"; "cocon" -> "dashed"; else -> null }
                                else if (entry.isPrimary) "solid" else null
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
                                ChatAvatar(member, 56.dp, ring, "${member.shownName}, ${label.orEmpty()}")
                                Text(member.shownName, color = p.ink2, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                    item {
                        Column(Modifier.width(64.dp).clickable(onClick = onSwitch), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                                Canvas(Modifier.fillMaxSize()) { drawCircle(p.line, radius = size.minDimension / 2 - 1.dp.toPx(),
                                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))) }
                                ChatIcon(R.drawable.ic_switch_arrows, "Switch who's here", p.accent)
                            }
                            Text("Switch", color = p.accent, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
        // the folders shown, in their order (D-113); all hidden: no tabs. They are arranged from the ⋮
        if (!archiveView && inside == null && folder != null) {
            val selectedIndex = folders.visible.indexOf(folder)
            ScrollableTabRow(selectedIndex, containerColor = p.surface, contentColor = p.accent, edgePadding = 8.dp,
                indicator = { positions -> TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(positions[selectedIndex]), color = p.accent) }) {
                for (candidate in folders.visible) {
                    val inFolder = listed.filter { inFolder(folders, candidate, it) }
                    Tab(selected = folder == candidate, onClick = { chosenFolder = candidate }, text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(ChatFolders.label(folders, candidate), fontSize = 13.sp, lineHeight = 18.sp, color = if (folder == candidate) p.ink else p.ink3,
                                maxLines = 1)
                            val count = inFolder.sumOf { it.unread }
                            if (count > 0) ChatCount(count, folder == candidate)
                        }
                    })
                }
            }
        }
        Box(Modifier.weight(1f)) {
            val filtered = inside ?: listed.filter { archiveView || folder == null || inFolder(folders, folder, it) }
            val entries: List<Any> = if (archiveView || inside != null) filtered
                else ChatOrganize.withSpaces(filtered, model.accountPrefs.chatPins, { it.channel.id }, { it.latest?.occurredAt ?: 0L },
                    { it.title }, { it.unread }, { it.muted }, { r -> if (r.grouped) r.space.id to r.space.name else null })
            // a chat that moves to the top (pinned, or a new message) is shown, not left just above the
            // view: lazy lists keep the old first row in place
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            val motion = rememberChorusMotion()
            val first = entries.firstOrNull()?.let { (it as? ChatOrganize.SpaceEntry<*>)?.spaceId ?: (it as ChatListRow).channel.id }
            LaunchedEffect(first) { if (listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0) }
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 88.dp)) {
                items(entries, key = { (it as? ChatOrganize.SpaceEntry<*>)?.let { e -> "space:${e.spaceId}" } ?: (it as ChatListRow).channel.id }) { entry ->
                    // a chat that moves (a new message, a pin) glides to its place
                    val rowMotion = Modifier.animateItem(fadeInSpec = if (motion) ChorusMotion.tween(true) else null,
                        placementSpec = if (motion) ChorusMotion.settle() else null, fadeOutSpec = if (motion) ChorusMotion.tween(true, ChorusMotion.Quick) else null)
                    @Suppress("UNCHECKED_CAST")
                    if (entry is ChatOrganize.SpaceEntry<*>) { Box(rowMotion) { SpaceEntryRow(entry as ChatOrganize.SpaceEntry<ChatListRow>, folders.compact,
                        onOpen = { openSpace = entry.spaceId }, onAction = onSpaceAction) }; return@items }
                    val row = entry as ChatListRow
                    val peer = row.peer(model, readerId)
                    Box(rowMotion) { Row(Modifier.fillMaxWidth().background(if (selected == row.channel.id) p.accentSoft else if (row.pinned) p.surface else p.bg)
                        .combinedClickable(onClick = { onOpen(row) }, onLongClick = onRowAction?.let { { rowMenu = row.channel.id } },
                            onLongClickLabel = "Chat actions").heightIn(min = if (folders.compact) 56.dp else 70.dp)
                            .padding(horizontal = 12.dp, vertical = if (folders.compact) 4.dp else 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ChatRowBody(model, row, peer, folders.compact, if (inside != null) row.channel.name else row.title, previews[row.channel.id])
                    }
                    if (onRowAction != null) MenuDrawer(rowMenu == row.channel.id, { rowMenu = null }, row.title,
                        leading = { ChatRowDisc(row, 40.dp) }) {
                        MenuRow(if (row.pinned) "Unpin" else "Pin to top", if (row.pinned) R.drawable.ic_unpin else R.drawable.ic_pin) {
                            rowMenu = null; onRowAction(row, "pin")
                        }
                        if (row.pinned && pinnedCount > 1) MenuRow("Arrange pinned chats", R.drawable.ic_grip) { rowMenu = null; onRowAction(row, "arrange") }
                        if (onFolders != null) MenuRow("Folders…", R.drawable.ic_folder) { rowMenu = null; foldersFor = row }
                        MenuRow(if (row.muted) "Unmute" else "Mute", if (row.muted) R.drawable.ic_bell else R.drawable.ic_bell_off) {
                            rowMenu = null; onRowAction(row, "mute")
                        }
                        MenuRow(if (row.archived) "Unarchive" else "Archive", R.drawable.ic_archive) { rowMenu = null; onRowAction(row, "archive") }
                        MenuRow(if (row.space.kind == "internal") "Delete chat" else "Clear chat", R.drawable.ic_delete, danger = true) {
                            rowMenu = null; onRowAction(row, "remove")
                        }
                    }
                    }
                    HorizontalDivider(Modifier.padding(start = if (folders.compact) 64.dp else 72.dp), color = p.line, thickness = .5.dp)
                }
                if (entries.isEmpty()) item {
                    EmptyState(R.drawable.ic_chat, "No chats yet", action = if (onNew != null) "New message" else onNewChannel?.let { "New channel" },
                        onAction = onNew ?: onNewChannel)
                }
            }
            val shared = onNewShared != null && NewChat.startFrom(folder, folders) == "shared"
            if (onNew != null && !archiveView && inside == null) FloatingActionButton(onClick = if (shared) onNewShared!! else onNew,
                containerColor = p.accent, contentColor = p.onAccent, shape = CircleShape, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
                if (shared) ChatIcon(R.drawable.ic_users, "New shared space", p.onAccent)
                else ChatIcon(R.drawable.ic_edit, "New message", p.onAccent)
            }
        }
    }
    // a chat's folders, from its long-press menu: tick the folders of our own it belongs in
    foldersFor?.let { row ->
        val kind = ChatFolders.kindOf(row.channel, row.space)
        Drawer({ foldersFor = null }, "Folders", row.title, footer = { DrawerButton("Done", { foldersFor = null }) }) {
            if (folders.custom.isEmpty()) Text("You have no folders of your own yet.", color = p.ink3, modifier = Modifier.padding(horizontal = 4.dp))
            else FormCard {
                folders.custom.forEachIndexed { i, f ->
                    if (i > 0) FormDivider()
                    val inside = ChatFolders.holds(f, row.channel.id, kind)
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = androidx.compose.ui.semantics.Role.Checkbox) {
                        onFolders?.invoke(ChatFolders.withCustom(folders, ChatFolders.setChat(f, row.channel.id, kind, !inside)))
                    }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Glyph(R.drawable.ic_folder, p.ink2, 20.dp)
                        Text(f.name, color = p.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Checkbox(inside, null)
                    }
                }
            }
            if (onEditFolders != null) MenuRow("New folder with this chat", R.drawable.ic_plus) { foldersFor = null; onEditFolders("new:${row.channel.id}") }
        }
    }
}

/** One chat in a list (the chat list, Home's *Waiting for you*): its disc, [title] with the whose-side
 * or [context] after it ("# general • Garden"), who said what last ([special] in its place, in the
 * accent), the time and the unread count. */
@Composable
internal fun RowScope.ChatRowBody(model: Model, row: ChatListRow, peer: Member?, compact: Boolean, title: String,
    special: String? = null, context: String? = null) {
    val p = LocalChorusPalette.current
    val direct = row.channel.isMemberDm && row.space.kind == "internal"
    // a DM's disc shows who it is with
    ChatRowDisc(row, if (compact) 40.dp else 48.dp, peer)
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (row.latest?.id in model.polls.polls) ChatIcon(R.drawable.ic_poll)
            else if (row.private) ChatIcon(R.drawable.ic_lock, "Private")
            else if (row.space.kind != "internal") ChatIcon(R.drawable.ic_globe)
            else if (row.channel.parentMessageId != null) ChatIcon(R.drawable.ic_reply)
            else if (!direct) Text("#", color = p.ink3, fontSize = 16.sp, lineHeight = 22.sp)
            Text(title, color = p.ink, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            (row.whose ?: context)?.let { Text("• $it", color = p.ink3, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
        }
        val sender = row.sender
        val preview = ChatPresentation.preview(row.latest, model.polls.polls[row.latest?.id])
        Text(spoilerPreview(special ?: listOfNotNull(sender?.takeIf { it.isNotBlank() }, preview).joinToString(": ")),
            color = if (special != null) p.accent else p.ink3, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        row.latest?.let { Text(ChatPresentation.listTimeLabel(it.occurredAt), color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (row.muted) Icon(painterResource(R.drawable.ic_bell_off), "Muted", Modifier.size(16.dp), tint = p.ink3)
            if (row.unread > 0) ChatCount(row.unread, !row.muted)
            else if (row.pinned) Icon(painterResource(R.drawable.ic_pin), "Pinned", Modifier.size(16.dp), tint = p.ink3)
        }
    }
}

/** A shared space's entry (owner, 2026-11-06): a rounded-square disc, its name, and its newest
 * channel's message under it; a tap lists its channels, a long press pins or archives them all. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SpaceEntryRow(entry: ChatOrganize.SpaceEntry<ChatListRow>, compact: Boolean, onOpen: () -> Unit,
    onAction: ((List<ChatListRow>, String) -> Unit)?) {
    val p = LocalChorusPalette.current
    val latest = entry.rows.first()
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().background(if (entry.pinned) p.surface else p.bg)
            .combinedClickable(onClickLabel = "Show its channels", onClick = onOpen,
                onLongClick = onAction?.let { { menu = true } }, onLongClickLabel = "Space actions")
            .heightIn(min = if (compact) 56.dp else 70.dp).padding(horizontal = 12.dp, vertical = if (compact) 4.dp else 8.dp)
            .semantics { contentDescription = "${entry.name}, ${entry.rows.size} channels" + if (entry.unread > 0) ", ${entry.unread} unread" else "" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val size = if (compact) 40.dp else 48.dp
            Box(Modifier.size(size).background(p.accentSoft, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Text(entry.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#", color = p.accent, fontWeight = FontWeight.SemiBold, fontSize = (size.value * .42f).sp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ChatIcon(R.drawable.ic_globe)
                    Text(entry.name, color = p.ink, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
                val preview = ChatPresentation.preview(latest.latest, null)
                Text(androidx.compose.ui.text.buildAnnotatedString {
                    withStyle(androidx.compose.ui.text.SpanStyle(color = p.accent)) { append("# ${latest.channel.name}  ") }
                    append(spoilerPreview(listOfNotNull(latest.sender?.takeIf { it.isNotBlank() }, preview).joinToString(": ")))
                }, color = p.ink3, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (entry.at > 0) Text(ChatPresentation.listTimeLabel(entry.at), color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (entry.muted) Icon(painterResource(R.drawable.ic_bell_off), "Muted", Modifier.size(16.dp), tint = p.ink3)
                    if (entry.unread > 0) ChatCount(entry.unread, !entry.muted)
                    else if (entry.pinned) Icon(painterResource(R.drawable.ic_pin), "Pinned", Modifier.size(16.dp), tint = p.ink3)
                }
            }
        }
        if (onAction != null) MenuDrawer(menu, { menu = false }, entry.name) {
            MenuRow("Show channels", R.drawable.ic_chat) { menu = false; onOpen() }
            MenuRow(if (entry.pinned) "Unpin" else "Pin to top", if (entry.pinned) R.drawable.ic_unpin else R.drawable.ic_pin) {
                menu = false; onAction(entry.rows, if (entry.pinned) "unpin" else "pin")
            }
            MenuRow("Archive all", R.drawable.ic_archive) { menu = false; onAction(entry.rows, "archive") }
        }
    }
    HorizontalDivider(Modifier.padding(start = if (compact) 64.dp else 72.dp), color = p.line, thickness = .5.dp)
}

/** Whether [row] shows in folder [id] right now (D-113). */
private fun inFolder(folders: ChatFolders.Settings, id: String, row: ChatListRow): Boolean =
    ChatFolders.contains(folders, id, row.channel.id, ChatFolders.kindOf(row.channel, row.space), row.unread, row.muted)

@Composable
internal fun ChatCount(count: Int, unread: Boolean) {
    val p = LocalChorusPalette.current
    Text(count.toString(), color = if (unread) { if (LocalChorusDark.current) p.onAccent else p.ink } else p.ink2, fontSize = 12.sp, lineHeight = 16.sp,
        modifier = Modifier.background(if (unread) p.warn else p.surface3, CircleShape).padding(horizontal = 6.dp, vertical = 1.dp))
}

@Composable
internal fun ChatActionRow(resource: Int, label: String, danger: Boolean = false, action: () -> Unit) =
    MenuRow(label, resource, danger = danger, onClick = action)

@Composable
internal fun ChatJumpButton(symbol: String?, count: Int, label: String, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    Box {
        FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(48.dp).semantics { contentDescription = label },
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = p.surface2, contentColor = p.ink)) {
            if (symbol != null) Text(symbol, color = p.ink, fontSize = 22.sp, lineHeight = 28.sp)
            else ChatIcon(R.drawable.ic_down, tint = p.ink)
        }
        if (count > 0) Box(Modifier.align(Alignment.TopEnd)) { ChatCount(count, true) }
    }
}
