package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.ChatForward
import garden.vayne.chorus.data.Member
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R

/**
 * Forward to… (Figma F5.20), also Reply in… and a bundle: a search, a grid of chats' faces, the red
 * card when the copy leaves where it was written (in place of a second drawer), "Show who originally
 * wrote it" and a comment with send. With [onPick], one tap on a chat is the whole choice (Reply in).
 */
@Composable
internal fun ForwardSheet(rows: List<ChatListRow>, model: Model, speaker: Member?, busy: Boolean, onDismiss: () -> Unit,
    placeholder: String = "Forward to…", exclude: String? = null,
    /** title and text of the red card for a chat, or null when nothing leaves. */
    warning: (ChatListRow) -> Pair<String, String>? = { null },
    onPick: ((ChatListRow) -> Unit)? = null,
    onSend: (ChatListRow, String, Boolean) -> Unit = { _, _, _ -> }) {
    val p = LocalChorusPalette.current
    var query by rememberSaveable { mutableStateOf("") }
    var chosenId by rememberSaveable { mutableStateOf<String?>(null) }
    var comment by rememberSaveable { mutableStateOf("") }
    var showAuthors by rememberSaveable { mutableStateOf(true) }
    val shown = remember(rows, query, exclude) {
        ChatForward.destinations(rows, query, { listOf(it.title, it.space.name, it.channel.name) }, { it.latest?.occurredAt ?: 0L },
            exclude = { it.channel.id == exclude })
    }
    val chosen = rows.find { it.channel.id == chosenId }
    Drawer(onDismiss, dismissible = !busy, footer = if (onPick != null) null else ({
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            speaker?.let { ChatAvatar(it, 36.dp) }
            Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).background(fieldFill())
                .padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
                if (comment.isEmpty()) Text("Add a comment…", color = p.ink3, fontSize = 15.sp)
                BasicTextField(comment, { comment = it.take(4000) }, maxLines = 4, cursorBrush = SolidColor(p.accent),
                    textStyle = TextStyle(color = p.ink, fontSize = 15.sp, lineHeight = 20.sp),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Comment" })
            }
            val ready = chosen != null && !busy
            Box(Modifier.size(44.dp).clip(CircleShape).background(if (ready) p.accent else p.surface3)
                .clickable(enabled = ready, role = Role.Button, onClickLabel = "Forward") { chosen?.let { onSend(it, comment, showAuthors) } }
                .semantics { contentDescription = "Forward" }, contentAlignment = Alignment.Center) {
                Glyph(R.drawable.ic_send, if (ready) p.onAccent else p.ink3, 20.dp)
            }
        }
    })) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Glyph(R.drawable.ic_search, p.ink3, 20.dp)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Text(placeholder, color = p.ink3, fontSize = 15.sp)
                BasicTextField(query, { query = it }, singleLine = true, cursorBrush = SolidColor(p.accent),
                    textStyle = TextStyle(color = p.ink, fontSize = 15.sp),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search chats" })
            }
        }
        for (line in shown.chunked(4)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (row in line) ForwardFace(row, model, row.channel.id == chosenId, Modifier.weight(1f), enabled = !busy) {
                if (onPick != null) onPick(row) else chosenId = if (chosenId == row.channel.id) null else row.channel.id
            }
            repeat(4 - line.size) { Spacer(Modifier.weight(1f)) }
        }
        if (shown.isEmpty()) EmptyState(R.drawable.ic_search, "No chats found")
        if (onPick == null) {
            chosen?.let(warning)?.let { (title, text) -> FormNote(text, R.drawable.ic_globe, warning = true, title = title) }
            Row(Modifier.fillMaxWidth().clickable(role = Role.Switch) { showAuthors = !showAuthors }.heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Show who originally wrote it", color = p.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                ChorusSwitch(showAuthors, { showAuthors = it })
            }
        }
    }
}

/** A chat in the forward grid: its face, ringed with a check when chosen, and its name under it. */
@Composable
private fun ForwardFace(row: ChatListRow, model: Model, chosen: Boolean, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    Column(modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .semantics { selected = chosen }.padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(60.dp)) {
            Box(Modifier.fillMaxSize().then(if (chosen) Modifier.border(2.dp, p.accent, CircleShape) else Modifier).padding(4.dp)) {
                ChatRowDisc(row, 52.dp, row.peer(model, null))
            }
            if (chosen) Box(Modifier.align(Alignment.BottomEnd).size(22.dp).background(p.accent, CircleShape)
                .border(2.dp, p.surface, CircleShape), contentAlignment = Alignment.Center) {
                Glyph(R.drawable.ic_check, p.onAccent, 14.dp)
            }
        }
        Text(row.title, color = if (chosen) p.accent else p.ink2, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal)
    }
}
