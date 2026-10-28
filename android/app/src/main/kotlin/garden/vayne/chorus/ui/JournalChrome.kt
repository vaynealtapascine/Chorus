package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.data.*
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R


@Composable
internal fun JournalTextField(value: String, onChange: (String) -> Unit, hint: String, style: TextStyle,
    modifier: Modifier = Modifier, minLines: Int = 1, enabled: Boolean = true) {
    val p = LocalChorusPalette.current
    BasicTextField(value, onChange, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        textStyle = style.copy(color = p.ink), cursorBrush = SolidColor(p.accent), minLines = minLines,
        decorationBox = { inner -> Box(Modifier.padding(vertical = 8.dp)) {
            if (value.isEmpty()) Text(hint, style = style, color = p.ink3)
            inner()
        } })
}

@Composable
internal fun JournalAuthorPicker(author: Member?, mine: List<Member>, onChoose: (String) -> Unit) {
    val p = LocalChorusPalette.current
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (author != null) ChatAvatar(author, 40.dp)
        Box(Modifier.weight(1f)) {
            Row(Modifier.clickable { open = true }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(author?.let { "Posting as ${it.shownName}" } ?: "Choose who is writing", color = p.accent,
                    style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                ChatIcon(R.drawable.ic_down, tint = p.accent)
            }
            DropdownMenu(open, { open = false }) {
                for (member in mine) DropdownMenuItem(text = { Text(member.shownName) }, leadingIcon = { ChatAvatar(member, 32.dp) },
                    onClick = { onChoose(member.id); open = false })
            }
        }
    }
}

@Composable
internal fun JournalAudiencePicker(audience: String, person: Boolean, onChoose: (String) -> Unit) {
    val p = LocalChorusPalette.current
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            ChatIcon(if (audience == "private") R.drawable.ic_lock else R.drawable.ic_globe, tint = p.accent)
            Spacer(Modifier.width(6.dp))
            Text(JournalPresentation.audience(audience, person), maxLines = 2)
            ChatIcon(R.drawable.ic_down, tint = p.accent)
        }
        DropdownMenu(open, { open = false }) {
            for (mode in listOf("private", "followers", "server")) DropdownMenuItem(
                text = { Text(JournalPresentation.audience(mode, person)) }, onClick = { onChoose(mode); open = false })
        }
    }
}

@Composable
internal fun JournalPostHeader(post: JournalPost, model: Model, onProfile: ((String) -> Unit)?) {
    val p = LocalChorusPalette.current
    val first = post.authors.firstNotNullOfOrNull(model::member)
    var authorsOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        if (first != null) Box(Modifier.clickable(enabled = onProfile != null) { onProfile?.invoke(first.id) }.size(48.dp), contentAlignment = Alignment.Center) { ChatAvatar(first, 40.dp) }
        Column(Modifier.weight(1f).clickable(enabled = first != null && onProfile != null) {
            if (post.authors.size > 1) authorsOpen = true else first?.let { onProfile?.invoke(it.id) }
        }.heightIn(min = 48.dp)) {
            DropdownMenu(authorsOpen, { authorsOpen = false }) {
                for (id in post.authors) model.member(id)?.let { member -> DropdownMenuItem(text = { Text(member.shownName) },
                    onClick = { authorsOpen = false; onProfile?.invoke(id) }) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(post.authors.mapNotNull { model.member(it)?.shownName }.joinToString(" & ").ifBlank { "Someone" },
                    color = p.ink, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                ChatIcon(if (post.visibility == "private") R.drawable.ic_lock else R.drawable.ic_globe, JournalPresentation.audience(post.visibility, model.isPerson), p.ink3)
            }
            Text(ago(post.occurredAt), color = p.ink3, style = MaterialTheme.typography.bodySmall)
        }
    }
}
