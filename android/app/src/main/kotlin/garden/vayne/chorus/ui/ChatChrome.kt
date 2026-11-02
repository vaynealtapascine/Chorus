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

internal data class ChatListRow(val channel: ChatChannel, val space: ChatSpace, val title: String,
    val latest: ChatMessage?, val unread: Int, val sender: String? = null, val muted: Boolean = false, val pinned: Boolean = false,
    /** A private chat in a DM (D-098), or a channel kept to some of our members (D-097): a lock, not a globe or #. */
    val private: Boolean = false,
    /** Whose a private chat with another account is on our side ("• Fox"), so one member's chat with
     * someone isn't mistaken for another's. */
    val whose: String? = null)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ChatListPane(model: Model, rows: List<ChatListRow>, selected: String?, reader: String?,
    onOpen: (ChatListRow) -> Unit, onNew: (() -> Unit)?, onSwitch: () -> Unit,
    onSearch: () -> Unit, modifier: Modifier = Modifier, readerId: String? = null, onNewChannel: (() -> Unit)? = null,
    onRowAction: ((ChatListRow, String) -> Unit)? = null,
    previews: Map<String, String> = emptyMap()) {
    val p = LocalChorusPalette.current
    var folder by rememberSaveable { mutableStateOf(ChatPresentation.Folder.All) }
    var menu by remember { mutableStateOf(false) }
    // the chat whose long-press menu is open (pin, arrange pins, delete or clear)
    var rowMenu by remember { mutableStateOf<String?>(null) }
    val pinnedCount = rows.count { it.pinned }
    val system = model.spaces.firstOrNull { it.kind == "internal" }?.name?.takeUnless { it == "Home" }
    Column(modifier.fillMaxHeight().background(p.bg)) {
        Row(Modifier.fillMaxWidth().background(p.surface).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text("Chats", color = p.ink, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
                Text(listOfNotNull(system, reader?.let { "reading as $it" }).joinToString(" · ").ifEmpty { "Your conversations" },
                    color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2)
            }
            ChatIconButton(R.drawable.ic_search, "Search chats", onSearch)
            Box {
                ChatIconButton(R.drawable.ic_more, "Chat options") { menu = true }
                DropdownMenu(menu, { menu = false }) {
                    if (onNewChannel != null) DropdownMenuItem(text = { Text("New channel") }, onClick = { menu = false; onNewChannel() })
                    if (onNew != null) DropdownMenuItem(text = { Text("New message") }, onClick = { menu = false; onNew() })
                    if (onRowAction != null && pinnedCount > 1) DropdownMenuItem(text = { Text("Arrange pinned chats") },
                        onClick = { menu = false; rows.firstOrNull { it.pinned }?.let { onRowAction(it, "arrange") } })
                }
            }
        }
        if (!model.isPerson) {
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
        ScrollableTabRow(folder.ordinal, containerColor = p.surface, contentColor = p.accent, edgePadding = 8.dp,
            indicator = { positions -> TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(positions[folder.ordinal]), color = p.accent) }) {
            for (candidate in ChatPresentation.Folder.entries) {
                val inFolder = rows.filter { candidate == ChatPresentation.Folder.All || ChatPresentation.folder(it.channel, it.space) == candidate }
                Tab(selected = folder == candidate, onClick = { folder = candidate }, text = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(candidate.label, fontSize = 13.sp, lineHeight = 18.sp, color = if (folder == candidate) p.ink else p.ink3)
                        val count = inFolder.sumOf { it.unread }
                        if (count > 0) ChatCount(count, folder == candidate)
                    }
                })
            }
        }
        Box(Modifier.weight(1f)) {
            val filtered = rows.filter { folder == ChatPresentation.Folder.All || ChatPresentation.folder(it.channel, it.space) == folder }
            // a chat that moves to the top (pinned, or a new message) is shown, not left just above the
            // view: lazy lists keep the old first row in place
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            val first = filtered.firstOrNull()?.channel?.id
            LaunchedEffect(first) { if (listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0) }
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 88.dp)) {
                items(filtered, key = { it.channel.id }) { row ->
                    val direct = row.channel.isMemberDm && row.space.kind == "internal"
                    val peer = if (direct) row.channel.memberIds.firstOrNull { it != (readerId ?: ChatSpeaker.pick(model, row.channel.id)) }?.let(model::member) else null
                    Box { Row(Modifier.fillMaxWidth().background(if (selected == row.channel.id) p.accentSoft else if (row.pinned) p.surface else p.bg)
                        .combinedClickable(onClick = { onOpen(row) }, onLongClick = onRowAction?.let { { rowMenu = row.channel.id } },
                            onLongClickLabel = "Chat actions").heightIn(min = 70.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        // an account DM's channel is just "dm": its disc shows the account
                        ChannelDisc(if (row.space.kind == "dm") row.channel.copy(name = row.title) else row.channel, peer)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (row.latest?.id in model.polls.polls) ChatIcon(R.drawable.ic_poll)
                                else if (row.private) ChatIcon(R.drawable.ic_lock, "Private")
                                else if (row.space.kind != "internal") ChatIcon(R.drawable.ic_globe)
                                else if (row.channel.parentMessageId != null) ChatIcon(R.drawable.ic_reply)
                                else if (!direct) Text("#", color = p.ink3, fontSize = 16.sp, lineHeight = 22.sp)
                                Text(row.title, color = p.ink, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                row.whose?.let { Text("• $it", color = p.ink3, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
                            }
                            val sender = row.sender
                            val special = previews[row.channel.id]
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
                    if (onRowAction != null) DropdownMenu(rowMenu == row.channel.id, { rowMenu = null }) {
                        DropdownMenuItem(text = { Text(if (row.pinned) "Unpin" else "Pin to top") }, onClick = { rowMenu = null; onRowAction(row, "pin") },
                            leadingIcon = { Icon(painterResource(if (row.pinned) R.drawable.ic_unpin else R.drawable.ic_pin), null, Modifier.size(20.dp)) })
                        if (row.pinned && pinnedCount > 1) DropdownMenuItem(text = { Text("Arrange pinned chats") },
                            onClick = { rowMenu = null; onRowAction(row, "arrange") })
                        DropdownMenuItem(text = { Text(if (row.space.kind == "internal") "Delete chat" else "Clear chat", color = p.danger) },
                            onClick = { rowMenu = null; onRowAction(row, "remove") },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_delete), null, Modifier.size(20.dp), tint = p.danger) })
                    }
                    }
                    HorizontalDivider(Modifier.padding(start = 72.dp), color = p.line, thickness = .5.dp)
                }
                if (filtered.isEmpty()) item { Column(Modifier.padding(24.dp)) {
                    Text("No chats here yet.", color = p.ink3)
                    if (onNewChannel != null) TextButton(onClick = onNewChannel) { Text("Create a channel") }
                    if (onNew != null) TextButton(onClick = onNew) { Text("Start a chat") }
                } }
                if (folder == ChatPresentation.Folder.Shared || folder == ChatPresentation.Folder.Direct) item {
                    Text(if (folder == ChatPresentation.Folder.Shared) "Shared spaces include other systems. Messages show who is speaking; system asides stay inside your system."
                        else "One-to-one chats between your members and with other accounts.", color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp,
                        modifier = Modifier.padding(24.dp))
                }
            }
            if (onNew != null) ExtendedFloatingActionButton(onClick = onNew, containerColor = p.accent, contentColor = p.onAccent,
                shape = CircleShape, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                icon = { ChatIcon(R.drawable.ic_edit, "New chat or channel", p.onAccent) }, text = { Text("New chat") })
        }
    }
}

@Composable
internal fun ChatCount(count: Int, unread: Boolean) {
    val p = LocalChorusPalette.current
    Text(count.toString(), color = if (unread) { if (LocalChorusDark.current) p.onAccent else p.ink } else p.ink2, fontSize = 12.sp, lineHeight = 16.sp,
        modifier = Modifier.background(if (unread) p.warn else p.surface3, CircleShape).padding(horizontal = 6.dp, vertical = 1.dp))
}

@Composable
internal fun ChatActionRow(resource: Int, label: String, danger: Boolean = false, action: () -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = action).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ChatIcon(resource, tint = if (danger) p.danger else p.ink2)
        Text(label, color = if (danger) p.danger else p.ink, fontSize = 15.sp, lineHeight = 20.sp)
    }
}

@Composable
internal fun ChatCreationChoice(resource: Int, title: String, description: String, prominent: Boolean = false, action: () -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().background(if (prominent) p.accentSoft else p.surface, RoundedCornerShape(16.dp))
        .clickable(onClick = action).heightIn(min = 72.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ChatIcon(resource, tint = p.accent)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = p.ink)
            Text(description, style = MaterialTheme.typography.bodySmall, color = p.ink2)
        }
    }
}

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
