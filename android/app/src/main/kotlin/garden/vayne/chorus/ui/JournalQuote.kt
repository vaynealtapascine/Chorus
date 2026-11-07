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
import garden.vayne.chorus.data.ThreadReply
import garden.vayne.chorus.designsystem.LocalChorusPalette

/** A quoted post inside another: a bordered mini card (20 dp avatar, name, three lines of text)
 * that opens the quoted post. A quoted post the replica doesn't hold is named as unavailable
 * rather than guessed at; a content warning is shown instead of the text. */
@Composable
internal fun QuoteEmbed(quoted: JournalPost?, model: Model, modifier: Modifier = Modifier, onOpen: (() -> Unit)? = null,
    names: String? = null, gone: String = "Quoted post unavailable") {
    val p = LocalChorusPalette.current
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.fillMaxWidth().clip(shape).border(1.dp, p.line, shape)
        .then(if (onOpen != null && quoted != null) Modifier.clickable(onClickLabel = "Open quoted post", onClick = onOpen) else Modifier)
        .padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (quoted == null) Text(gone, color = p.ink3, style = MaterialTheme.typography.bodySmall)
        else {
            // another account's post names its authors from the server's author cards ([names])
            val names = names ?: quoted.authors.mapNotNull { model.member(it)?.shownName }.joinToString(" & ").ifBlank { "Someone" }
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

/** The quote inside a thread post: the device's own copy when it has one, else the server's. */
@Composable
internal fun ThreadQuote(quotePostId: String, quoted: ThreadReply?, model: Model, onOpen: ((String) -> Unit)?, chorus: garden.vayne.chorus.data.Chorus? = null,
    gone: String = "Quoted post unavailable") {
    val local = model.posts.find { it.id == quotePostId }?.takeIf { it.repostOf == null }
    val open = onOpen?.let { { it(quotePostId) } }
    val fetched = if (local == null && quoted == null) rememberRemotePost(chorus, quotePostId) else null
    val remote = quoted ?: fetched
    if (local != null || remote == null) QuoteEmbed(local, model, onOpen = open, gone = gone)
    else QuoteEmbed(JournalPost(remote.id, "note", remote.authorIds, remote.title, remote.text, remote.occurredAt, remote.cw,
        "server", null, null, emptyList(), entities = remote.entities), model, onOpen = open,
        names = remote.authorNames.joinToString(" & ").ifBlank { "Someone" }, gone = gone)
}

/** Another account's post, read once per process through the server's audience check; null while
 * loading, offline, or when this reader may not read it. */
@Composable
internal fun rememberRemotePost(chorus: garden.vayne.chorus.data.Chorus?, id: String): ThreadReply? {
    val state = androidx.compose.runtime.produceState(garden.vayne.chorus.data.RemotePosts.cached(id), id, chorus?.device?.session) {
        val dev = chorus?.device ?: return@produceState
        value = garden.vayne.chorus.data.RemotePosts.get(dev, id)
    }
    return state.value
}
