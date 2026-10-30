package garden.vayne.chorus.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.JournalPost
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.designsystem.LocalChorusPalette

/** A quoted post inside another: a bordered mini card (20 dp avatar, name, three lines of text)
 * that opens the quoted post. A quoted post the replica doesn't hold is named as unavailable
 * rather than guessed at; a content warning is shown instead of the text. */
@Composable
internal fun QuoteEmbed(quoted: JournalPost?, model: Model, modifier: Modifier = Modifier, onOpen: (() -> Unit)? = null) {
    val p = LocalChorusPalette.current
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.fillMaxWidth().clip(shape).border(1.dp, p.line, shape)
        .then(if (onOpen != null && quoted != null) Modifier.clickable(onClickLabel = "Open quoted post", onClick = onOpen) else Modifier)
        .padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (quoted == null) Text("Quoted post unavailable", color = p.ink3, style = MaterialTheme.typography.bodySmall)
        else {
            val names = quoted.authors.mapNotNull { model.member(it)?.shownName }.joinToString(" & ").ifBlank { "Someone" }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                quoted.authors.firstNotNullOfOrNull(model::member)?.let { ChatAvatar(it, 20.dp) }
                Text(names, color = p.ink, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, lineHeight = 18.sp), modifier = Modifier.weight(1f, fill = false))
                Text("· ${compactAgo(quoted.occurredAt)}", color = p.ink3, style = MaterialTheme.typography.bodySmall, maxLines = 1, softWrap = false)
            }
            if (quoted.cw != null) Text("Content warning: ${quoted.cw}", color = p.ink2, style = MaterialTheme.typography.bodySmall)
            else RichBody(quoted.text, quoted.entities, maxLines = 3, onPlainClick = { onOpen?.invoke() },
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, lineHeight = 20.sp))
        }
    }
}
