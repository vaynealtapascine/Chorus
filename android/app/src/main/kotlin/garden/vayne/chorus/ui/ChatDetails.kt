package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
        else model.namedFronts.resolved[frontId]?.authorship.orEmpty().filter { it.subjectType == "member" }.map { it.subjectId }.toSet()
    val frontName = model.namedFronts?.fronts?.get(frontId)?.name ?: "Body"
    val present = presence.filter { it.subjectId in presentIds }.mapNotNull { entry -> members.find { it.id == entry.subjectId } }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = p.surface) {
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
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = p.line)
            Text("Speaking as someone doesn't change who's fronting.", color = p.ink3, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
    }
}


/** Read-only context over existing local data; no implied typing or online-presence state. */
@Composable
internal fun ChatInfoPane(channel: ChatChannel, space: ChatSpace, model: Model, messages: List<ChatMessage>,
    directory: SpaceInfo?, onSearch: () -> Unit, onClose: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val p = LocalChorusPalette.current
    val presence = ChatPresentation.presence(model, channel.id)
    val members = if (space.kind == "internal") model.active.filter { channel.kind != "dm" || it.id in channel.memberIds } else emptyList()
    Column(modifier.fillMaxHeight().background(p.surface).verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Chat info", color = p.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (onClose != null) ChatIconButton(R.drawable.ic_close, "Close chat info", onClose)
        }
        Spacer(Modifier.height(16.dp))
        ChannelDisc(channel, size = 72.dp)
        Text(channel.name, color = p.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        Text(when { space.kind == "dm" || channel.kind == "dm" -> "Direct"; space.kind == "internal" -> "Internal"; else -> "Shared · ${space.name}" },
            color = p.ink3, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        TextButton(onClick = onSearch) {
            ChatIcon(R.drawable.ic_search, tint = p.accent)
            Text("Search", modifier = Modifier.padding(start = 8.dp))
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = p.line)
        if (space.kind == "internal" && !model.isPerson) {
            Text(if (channel.kind == "dm") "Members" else "Your members", color = p.accent, fontSize = 13.sp, modifier = Modifier.align(Alignment.Start))
            Text("Each member keeps their own read position when enabled.", color = p.ink3, fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 8.dp))
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
        } else if (space.kind == "internal") {
            Text("Private to your account", color = p.ink3, fontSize = 13.sp)
        } else {
            Text("Accounts in this space", color = p.accent, fontSize = 13.sp, modifier = Modifier.align(Alignment.Start))
            for (account in directory?.accounts.orEmpty()) Text(account.shownName, color = p.ink,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
            if (directory == null) Text("Space details are unavailable offline.", color = p.ink3, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
        }
    }
}
