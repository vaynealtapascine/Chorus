package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.ChatMessage
import garden.vayne.chorus.data.ChatSpace
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.accountVisible
import garden.vayne.chorus.data.memberVisible
import garden.vayne.chorus.designsystem.LocalChorusPalette
import java.text.DateFormat
import java.util.Date

/** Local chat view: internal channels, shared spaces and account DMs use the same projection. */
@Composable
fun Chat(chorus: Chorus, model: Model) {
    val p = LocalChorusPalette.current
    var selectedSpace by rememberSaveable { mutableStateOf("") }
    var selectedChannel by rememberSaveable { mutableStateOf("") }
    var viewingAs by rememberSaveable { mutableStateOf<String?>(null) }
    val space = model.spaces.find { it.id == selectedSpace } ?: model.spaces.firstOrNull()
    val channels = model.channels.filter { it.spaceId == space?.id }
    val channel = channels.find { it.id == selectedChannel } ?: channels.firstOrNull()
    val messages = model.chatMessages[channel?.id].orEmpty().filter {
        space != null && memberVisible(it, space.kind, model.current, viewingAs) &&
            accountVisible(it, space.kind, chorus.device?.accountId.orEmpty())
    }

    Column(Modifier.fillMaxSize().background(p.bg)) {
        if (space == null) {
            Text("No spaces yet. Shared spaces and DMs will appear here when you join them.",
                color = p.ink2, modifier = Modifier.padding(24.dp))
            return@Column
        }
        LazyRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(model.spaces, key = { it.id }) { candidate ->
                ChatChip(spaceLabel(candidate), candidate.id == space.id) {
                    selectedSpace = candidate.id
                    selectedChannel = ""
                    viewingAs = null
                }
            }
        }
        if (channel == null) {
            Text("No channels in this space yet.", color = p.ink2, modifier = Modifier.padding(24.dp))
            return@Column
        }
        LazyRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(channels, key = { it.id }) { candidate ->
                ChatChip("#${candidate.name}", candidate.id == channel.id) { selectedChannel = candidate.id }
            }
        }
        if (space.kind == "internal") {
            Text("Member visibility is a soft view setting. Choose whose view to see:", color = p.ink2,
                fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            LazyRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { ChatChip("Present", viewingAs == null) { viewingAs = null } }
                items(model.active, key = { it.id }) { member ->
                    ChatChip(member.shownName, viewingAs == member.id) { viewingAs = member.id }
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages.asReversed(), key = { it.id }) { message ->
                ChatMessageCard(message, model)
            }
            if (messages.isEmpty()) item {
                Text("No messages here yet.", color = p.ink2, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

private fun spaceLabel(space: ChatSpace): String = when (space.kind) {
    "internal" -> "Home · ${space.name}"
    "dm" -> "DM · ${space.name.takeIf { it.isNotBlank() } ?: "Direct message"}"
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
private fun ChatMessageCard(message: ChatMessage, model: Model) {
    val p = LocalChorusPalette.current
    var revealed by rememberSaveable(message.id) { mutableStateOf(false) }
    val authors = message.authors.map { model.member(it)?.shownName ?: "Someone" }.joinToString(" & ").ifEmpty { "Someone" }
    Column(Modifier.fillMaxWidth().background(p.surface, RoundedCornerShape(12.dp))
        .border(1.dp, p.line, RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(authors, color = p.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(message.occurredAt)),
                color = p.ink3, fontSize = 11.sp)
        }
        if (message.replyTo != null) Text("↪ Reply", color = p.ink3, fontSize = 12.sp)
        if (message.visibilityMode == "system_only") Text("Only this system", color = p.ink3, fontSize = 12.sp)
        if (message.visibilityMode == "members") Text("Chosen members", color = p.ink3, fontSize = 12.sp)
        if (message.cw != null) {
            Text("Content warning: ${message.cw} · ${if (revealed) "Hide" else "Show"}", color = p.accent,
                modifier = Modifier.clickable { revealed = !revealed })
        }
        if (message.cw == null || revealed) {
            Text(message.text, color = p.ink, fontSize = 16.sp)
            for (attachment in message.attachments) {
                Text("Attachment: ${attachment.filename}${if (attachment.spoiler) " · spoiler" else ""}", color = p.ink2, fontSize = 13.sp)
                if (attachment.altText.isNotBlank()) Text(attachment.altText, color = p.ink3, fontSize = 12.sp)
            }
        }
    }
}
