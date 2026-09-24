package garden.vayne.chorus.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
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
import garden.vayne.chorus.data.ChatAttachment
import garden.vayne.chorus.data.ChatCompose
import garden.vayne.chorus.data.ChatSpace
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.Reply
import garden.vayne.chorus.data.accountVisible
import garden.vayne.chorus.data.memberVisible
import garden.vayne.chorus.designsystem.LocalChorusPalette
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Local chat view: internal channels, shared spaces and account DMs use the same projection. */
@Composable
fun Chat(chorus: Chorus, model: Model) {
    val p = LocalChorusPalette.current
    var selectedSpace by rememberSaveable { mutableStateOf("") }
    var selectedChannel by rememberSaveable { mutableStateOf("") }
    var viewingAs by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedAuthor by rememberSaveable { mutableStateOf("") }
    var draft by rememberSaveable { mutableStateOf("") }
    var cw by rememberSaveable { mutableStateOf("") }
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    var audience by rememberSaveable { mutableStateOf("all") }
    var visibleTo by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var replyTo by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val actions = rememberCoroutineScope()
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
                    audience = "all"
                    visibleTo = emptyList()
                    replyTo = null
                    moreOpen = false
                }
            }
        }
        if (channel == null) {
            Text("No channels in this space yet.", color = p.ink2, modifier = Modifier.padding(24.dp))
            return@Column
        }
        LazyRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(channels, key = { it.id }) { candidate ->
                ChatChip("#${candidate.name}", candidate.id == channel.id) {
                    selectedChannel = candidate.id
                    replyTo = null
                }
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
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages.asReversed(), key = { it.id }) { message ->
                ChatMessageCard(message, model, chorus) { replyTo = message.id }
            }
            if (messages.isEmpty()) item {
                Text("No messages here yet.", color = p.ink2, modifier = Modifier.padding(16.dp))
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val author = model.active.find { it.id == selectedAuthor } ?: Reply.speaker(model)
            if (model.active.size > 1) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(model.active, key = { it.id }) { member ->
                        ChatChip(member.shownName, author?.id == member.id) { selectedAuthor = member.id }
                    }
                }
            }
            val target = messages.find { it.id == replyTo }
            if (target != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Replying to a message", color = p.ink2, fontSize = 12.sp)
                    Text("Cancel", color = p.accent, fontSize = 12.sp,
                        modifier = Modifier.clickable { replyTo = null })
                }
            } else if (replyTo != null) {
                Text("Reply target unavailable · Cancel", color = p.accent, fontSize = 12.sp,
                    modifier = Modifier.clickable { replyTo = null })
            }
            OutlinedTextField(draft, { draft = it }, label = { Text("Message as ${author?.shownName ?: "choose a member"}") },
                modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4)
            val audienceLabel = when (audience) {
                "members" -> "Chosen members (${visibleTo.size})"
                "system_only" -> "Only my system"
                else -> "Everyone here"
            }
            TextButton(onClick = { moreOpen = !moreOpen }) {
                Text("${if (moreOpen) "Less" else "More"} · $audienceLabel${if (cw.isNotBlank()) " · CW" else ""}")
            }
            if (moreOpen) {
                OutlinedTextField(cw, { cw = it }, label = { Text("Content warning (optional)") },
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
            }
            if (error != null) Text(error.orEmpty(), color = p.accent, fontSize = 12.sp)
            Button(enabled = !busy && draft.isNotBlank() && author != null && (replyTo == null || target != null) &&
                (audience != "members" || visibleTo.isNotEmpty()), onClick = {
                val speakerId = author?.id ?: return@Button
                busy = true
                error = null
                actions.launch {
                    try {
                        val payload = ChatCompose.payload(model, channel.id, speakerId, draft, cw,
                            audience, visibleTo.toSet(), space.kind, replyTo = target?.id)
                        chorus.create("message.send", chorus.newId(), payload, scope = "space:${space.id}")
                        draft = ""
                        cw = ""
                        audience = "all"
                        visibleTo = emptyList()
                        replyTo = null
                        moreOpen = false
                    } catch (e: Exception) {
                        error = e.message ?: "Could not send the message."
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (busy) "Sending…" else "Send · $audienceLabel") }
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
private fun ChatMessageCard(message: ChatMessage, model: Model, chorus: Chorus, onReply: () -> Unit) {
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
        Text("Reply", color = p.accent, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onReply))
        if (message.visibilityMode == "system_only") Text("Only this system", color = p.ink3, fontSize = 12.sp)
        if (message.visibilityMode == "members") Text("Chosen members", color = p.ink3, fontSize = 12.sp)
        if (message.cw != null) {
            Text("Content warning: ${message.cw} · ${if (revealed) "Hide" else "Show"}", color = p.accent,
                modifier = Modifier.clickable { revealed = !revealed })
        }
        if (message.cw == null || revealed) {
            Text(message.text, color = p.ink, fontSize = 16.sp)
            for (attachment in message.attachments) {
                ChatAttachmentView(attachment, chorus)
            }
        }
    }
}

@Composable
private fun ChatAttachmentView(attachment: ChatAttachment, chorus: Chorus) {
    val p = LocalChorusPalette.current
    val ctx = LocalContext.current
    val device = chorus.device
    val image = attachment.mime.startsWith("image/")
    var opened by rememberSaveable(attachment.id) { mutableStateOf(false) }
    if (attachment.spoiler && !opened) {
        Text("Spoiler attachment · Reveal", color = p.accent, fontSize = 13.sp,
            modifier = Modifier.clickable { opened = true })
        return
    }
    Text("Attachment: ${attachment.filename}", color = p.ink2, fontSize = 13.sp)
    if (attachment.altText.isNotBlank()) Text(attachment.altText, color = p.ink3, fontSize = 12.sp)
    if (!image) {
        if (attachment.spoiler) Text("Hide attachment", color = p.accent, fontSize = 12.sp,
            modifier = Modifier.clickable { opened = false })
        return
    }
    if (!opened) {
        Text(if (attachment.spoiler) "Reveal image spoiler" else "View image", color = p.accent,
            modifier = Modifier.clickable { opened = true }.padding(vertical = 4.dp))
        return
    }
    val hash = attachment.thumbHash ?: attachment.blobHash
    val bitmap by produceState<android.graphics.Bitmap?>(null, hash, device?.session) {
        value = if (device == null) null else withContext(Dispatchers.IO) {
            AvatarBlobs.load(ctx.applicationContext, hash, device)
        }
    }
    if (bitmap == null) {
        Text("Loading image, or unavailable offline.", color = p.ink3, fontSize = 12.sp)
    } else {
        Image(bitmap!!.asImageBitmap(), contentDescription = attachment.altText.ifBlank { attachment.filename },
            contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(200.dp))
    }
    Text("Hide image", color = p.accent, fontSize = 12.sp, modifier = Modifier.clickable { opened = false })
}
