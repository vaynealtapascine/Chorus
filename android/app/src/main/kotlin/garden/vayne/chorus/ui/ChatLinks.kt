package garden.vayne.chorus.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.ChatMessage
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.ChorusLinks
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.PostThreads
import garden.vayne.chorus.data.ThreadReply
import garden.vayne.chorus.designsystem.LocalChorusPalette
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/** Opens a link to one of our posts or messages in the app (D-109); false for anything else, which
 *  then opens in the browser. Provided by the activity, so every rich text gets it. */
internal val LocalChorusLinks = compositionLocalOf<(String) -> Boolean> { { false } }

/** Other accounts' linked posts, read once per app run through the server's audience check. */
private val remotePosts = ConcurrentHashMap<String, Result<ThreadReply?>>()

/** What a message links to (D-109), under its text like a quote, at most three: a post from the
 *  replica or the server (as its audience allows), or a message only where [linked] says this
 *  reader may see it now. Anything else is named unavailable and reveals nothing. */
@Composable
internal fun ChatLinkEmbeds(text: String, entities: String, chorus: Chorus, model: Model,
    linked: (String) -> ChatMessage?, nameOf: (String) -> String, onJump: ((String) -> Unit)?) {
    val base = chorus.device?.base ?: return
    val targets = remember(text, entities, base) { ChorusLinks.linksIn(text, entities, base).take(3) }
    val open = LocalChorusLinks.current
    for (t in targets) when (t.kind) {
        "post" -> {
            val local = model.posts.find { it.id == t.id }?.takeIf { it.repostOf == null }
            if (local != null) QuoteEmbed(local, model, onOpen = { open(ChorusLinks.linkTo(base, "post", t.id)) }, gone = "Post unavailable")
            else {
                val dev = chorus.device
                val remote by produceState<Result<ThreadReply?>?>(remotePosts[t.id], t.id, dev?.session) {
                    if (value == null && dev != null) value = runCatching { PostThreads.load(dev, t.id).root }.also { remotePosts[t.id] = it }
                }
                remote?.let { r -> ThreadQuote(t.id, r.getOrNull(), model, { open(ChorusLinks.linkTo(base, "post", it)) }, gone = "Post unavailable") }
            }
        }
        else -> LinkedMessage(linked(t.id), model, nameOf, onJump)
    }
}

@Composable
private fun LinkedMessage(message: ChatMessage?, model: Model, nameOf: (String) -> String, onJump: ((String) -> Unit)?) {
    val p = LocalChorusPalette.current
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, p.line, shape)
        .then(if (message != null && onJump != null) Modifier.clickable(onClickLabel = "Show the linked message") { onJump(message.id) } else Modifier)
        .padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (message == null) Text("Message unavailable", color = p.ink3, style = MaterialTheme.typography.bodySmall)
        else {
            val channel = model.channels.find { it.id == message.channelId }
            val space = channel?.let { c -> model.spaces.find { it.id == c.spaceId } }
            val where = when {
                space?.kind == "dm" -> space.name.ifBlank { "Direct message" }
                channel?.kind == "member_dm" -> channel.name
                space?.kind == "shared" -> "${space.name} · #${channel?.name.orEmpty()}"
                channel != null -> "#${channel.name}"
                else -> null
            }
            Text(listOfNotNull(message.authors.joinToString(" & ") { nameOf(it) }.ifBlank { "Someone" }, where,
                DateFormat.getDateInstance(DateFormat.SHORT).format(Date(message.occurredAt))).joinToString(" · "),
                color = p.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, lineHeight = 18.sp))
            Text(message.cw?.let { "Content warning: $it" } ?: message.text, color = p.ink, maxLines = 3, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, lineHeight = 20.sp))
        }
    }
}
