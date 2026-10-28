package garden.vayne.chorus.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
    val latest: ChatMessage?, val unread: Int, val sender: String? = null, val muted: Boolean = false)

@Composable
internal fun ChatListPane(model: Model, rows: List<ChatListRow>, selected: String?, reader: String?,
    onOpen: (ChatListRow) -> Unit, onNew: (() -> Unit)?, onSwitch: () -> Unit,
    onSearch: () -> Unit, modifier: Modifier = Modifier, readerId: String? = null, onNewChannel: (() -> Unit)? = null) {
    val p = LocalChorusPalette.current
    var folder by rememberSaveable { mutableStateOf(ChatPresentation.Folder.All) }
    var menu by remember { mutableStateOf(false) }
    var showReader by remember { mutableStateOf(false) }
    val system = model.spaces.firstOrNull { it.kind == "internal" }?.name?.takeUnless { it == "Home" }
    Column(modifier.fillMaxHeight().background(p.bg)) {
        Row(Modifier.fillMaxWidth().background(p.surface).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                Text("Chats", color = p.ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(listOfNotNull(system, reader?.let { "reading as $it" }).joinToString(" · ").ifEmpty { "Your conversations" },
                    color = p.ink3, fontSize = 12.sp, maxLines = 2)
            }
            ChatIconButton(R.drawable.ic_search, "Search chats", onSearch)
            Box {
                ChatIconButton(R.drawable.ic_more, "Chat options") { menu = true }
                DropdownMenu(menu, { menu = false }) {
                    if (onNewChannel != null) DropdownMenuItem(text = { Text("New channel") }, onClick = { menu = false; onNewChannel() })
                    if (onNew != null) DropdownMenuItem(text = { Text("New message") }, onClick = { menu = false; onNew() })
                    if (!model.isPerson) DropdownMenuItem(text = { Text("Per-member read tracking is in Settings") }, onClick = { menu = false; showReader = true })
                }
            }
        }
        if (showReader) Text("Each member keeps their own read position when per-member read tracking is enabled.",
            color = p.ink2, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
        if (!model.isPerson) {
            val presence = ChatPresentation.presence(model, selected).filter { it.subjectType == "member" }
            Column(Modifier.fillMaxWidth().background(p.surface)) {
                Text("Who's here", color = p.ink3, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(presence, key = { it.subjectId }) { entry ->
                        model.member(entry.subjectId)?.let { member ->
                            val label = ChatPresentation.layer(model, entry)
                            // Legacy front/co-conscious have defined ring semantics. Custom layers do not.
                            val ring = if (model.namedFronts == null) when (entry.level) { "front" -> "solid"; "cocon" -> "dashed"; else -> null }
                                else if (entry.isPrimary) "solid" else null
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
                                ChatAvatar(member, 56.dp, ring, "${member.shownName}, ${label.orEmpty()}")
                                Text(member.shownName, color = p.ink2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                    item {
                        Column(Modifier.width(64.dp).clickable(onClick = onSwitch), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                                Canvas(Modifier.fillMaxSize()) { drawCircle(p.line, radius = size.minDimension / 2 - 1.dp.toPx(),
                                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))) }
                                ChatIcon(R.drawable.ic_swap, "Switch who's here", p.accent)
                            }
                            Text("Switch", color = p.accent, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
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
                        Text(candidate.label, fontSize = 13.sp, color = if (folder == candidate) p.ink else p.ink3)
                        val count = inFolder.sumOf { it.unread }
                        if (count > 0) ChatCount(count, folder == candidate)
                    }
                })
            }
        }
        Box(Modifier.weight(1f)) {
            val filtered = rows.filter { folder == ChatPresentation.Folder.All || ChatPresentation.folder(it.channel, it.space) == folder }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                items(filtered, key = { it.channel.id }) { row ->
                    val direct = row.channel.isMemberDm && row.space.kind == "internal"
                    val peer = if (direct) row.channel.memberIds.firstOrNull { it != (readerId ?: ChatSpeaker.pick(model, row.channel.id)) }?.let(model::member) else null
                    Row(Modifier.fillMaxWidth().background(if (selected == row.channel.id) p.accentSoft else p.bg)
                        .clickable { onOpen(row) }.padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ChannelDisc(row.channel, peer)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (row.latest?.id in model.polls.polls) ChatIcon(R.drawable.ic_poll)
                                else if (row.space.kind != "internal") ChatIcon(R.drawable.ic_globe)
                                else if (row.channel.parentMessageId != null) ChatIcon(R.drawable.ic_reply)
                                else if (!direct) Text("#", color = p.ink3, fontSize = 16.sp)
                                Text(row.title, color = p.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            val sender = row.sender
                            val preview = ChatPresentation.preview(row.latest, model.polls.polls[row.latest?.id])
                            Text(listOfNotNull(sender?.takeIf { it.isNotBlank() }, preview).joinToString(": "),
                                color = p.ink3, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.latest?.let { Text(ChatPresentation.listTimeLabel(it.occurredAt), color = p.ink3, fontSize = 12.sp) }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (row.muted) Icon(painterResource(R.drawable.ic_bell_off), "Muted", Modifier.size(16.dp), tint = p.ink3)
                                if (row.unread > 0) ChatCount(row.unread, !row.muted)
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(start = 72.dp), color = p.line, thickness = .5.dp)
                }
                if (filtered.isEmpty()) item { Text("No chats here yet.", color = p.ink3, modifier = Modifier.padding(24.dp)) }
                if (folder == ChatPresentation.Folder.Shared || folder == ChatPresentation.Folder.Direct) item {
                    Text(if (folder == ChatPresentation.Folder.Shared) "Shared spaces include other systems. Messages show who is speaking; system asides stay inside your system."
                        else "One-to-one chats between your members and with other accounts.", color = p.ink3, fontSize = 12.sp,
                        modifier = Modifier.padding(24.dp))
                }
            }
            if (onNew != null) FloatingActionButton(onClick = onNew, containerColor = p.accent, contentColor = p.onAccent,
                shape = CircleShape, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).size(56.dp)) {
                ChatIcon(if (folder == ChatPresentation.Folder.Direct) R.drawable.ic_members else R.drawable.ic_edit,
                    "New message", p.onAccent)
            }
        }
    }
}

@Composable
internal fun ChatCount(count: Int, unread: Boolean) {
    val p = LocalChorusPalette.current
    Text(count.toString(), color = if (unread) { if (LocalChorusDark.current) p.onAccent else p.ink } else p.ink2, fontSize = 12.sp,
        modifier = Modifier.background(if (unread) p.warn else p.surface3, CircleShape).padding(horizontal = 6.dp, vertical = 1.dp))
}

@Composable
internal fun ChatActionRow(resource: Int, label: String, danger: Boolean = false, action: () -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = action).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ChatIcon(resource, tint = if (danger) p.danger else p.ink2)
        Text(label, color = if (danger) p.danger else p.ink, fontSize = 15.sp)
    }
}

@Composable
internal fun ChatJumpButton(symbol: String?, count: Int, label: String, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    Box {
        FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(48.dp).semantics { contentDescription = label },
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = p.surface2, contentColor = p.ink)) {
            if (symbol != null) Text(symbol, color = p.ink, fontSize = 22.sp)
            else ChatIcon(R.drawable.ic_down, tint = p.ink)
        }
        if (count > 0) Box(Modifier.align(Alignment.TopEnd)) { ChatCount(count, true) }
    }
}
