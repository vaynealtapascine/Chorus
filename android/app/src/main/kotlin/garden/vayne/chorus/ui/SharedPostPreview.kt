package garden.vayne.chorus.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.PostReactions
import garden.vayne.chorus.data.Reply
import garden.vayne.chorus.data.SharedPost
import garden.vayne.chorus.designsystem.LocalChorusPalette

/** A post another account shared with us, as Friends, Journal and profiles show it. */
@Composable
internal fun SharedPostPreview(post: SharedPost, accountName: String, chorus: Chorus, model: Model,
    onThread: ((SharedPost) -> Unit)? = null, onReply: ((SharedPost) -> Unit)? = null,
    /** Opens an author's profile (account, member) when it is shared with us. */
    onProfile: ((String, String) -> Unit)? = null,
    /** Quote it in a new post of ours (D-127); null leaves Quote disabled. */
    onQuote: ((SharedPost) -> Unit)? = null, onOpenPost: ((String) -> Unit)? = null) {
    val p = LocalChorusPalette.current
    var revealed by rememberSaveable(post.id) { mutableStateOf(false) }
    var expanded by rememberSaveable(post.id) { mutableStateOf(false) }
    val preview = remember(post.text, post.entities) { garden.vayne.chorus.data.PostPreviews.of(post.text, post.entities) }
    // the server's hearts; ours answer at once, before the next read
    var reactions by remember(post.id, post.reactions) { mutableStateOf(post.reactions) }
    val names = post.authorNames.joinToString(" & ").ifBlank { accountName }
    JournalPostFrame(names, compactAgo(post.occurredAt), null, endsWithActions = true, handle = post.looks.handle, avatar = {
        val profile = post.looks.profile?.takeIf { onProfile != null && post.accountId.isNotEmpty() }
        Avatar(names.take(1).uppercase(), post.looks.color ?: "#A09184", 40.dp, ring = false, avatarBlob = post.looks.avatarBlob,
            modifier = if (profile == null) Modifier else Modifier.clip(CircleShape)
                .clickable(onClickLabel = "Open their profile") { onProfile?.invoke(post.accountId, profile) })
    }, onClick = onThread?.let { open -> { open(post) } }) {
        // a reply says what it answers: the post itself when we may read it, else only that it replies
        post.replyTo?.let { parentId ->
            val to = post.parent?.authorNames?.joinToString(" & ")?.takeIf { it.isNotBlank() }
                ?: model.posts.find { it.id == parentId }?.authors?.mapNotNull { model.member(it)?.shownName }?.joinToString(" & ")?.takeIf { it.isNotBlank() }
            Text(if (to != null) "Replying to $to" else "Replying to a post", color = p.ink3, style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = if (onOpenPost == null) Modifier else Modifier.clickable(onClickLabel = "Open the post this replies to") { onOpenPost(parentId) })
            if (post.parent != null || model.posts.any { it.id == parentId }) ThreadQuote(parentId, post.parent, model, onOpenPost, chorus)
        }
        if (post.cw != null) TextButton(onClick = { revealed = !revealed }) {
            Text("Content warning: ${post.cw} · ${if (revealed) "Hide" else "Show"}")
        }
        if (post.cw == null || revealed) {
            if (post.title != null) Text(post.title, color = p.ink, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, lineHeight = 20.sp))
            RichBody(if (expanded) post.text else preview.text + if (preview.truncated) "…" else "",
                if (expanded) post.entities else preview.entities, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp))
            if (preview.truncated) TextButton(onClick = {
                expanded = !expanded
            }) { Text(if (expanded) "Show less" else "Show more") }
            PollCard(chorus, model, post.id, Reply.speaker(model)?.id, post.poll, post.pollTally)
            JournalMedia(post.attachments, chorus, names, post.occurredAt)
            post.quotePostId?.let { ThreadQuote(it, post.quoted, model, onOpenPost, chorus) }
            if (post.tags.isNotEmpty()) Text(post.tags.joinToString("  ") { "#$it" }, color = p.accent,
                style = MaterialTheme.typography.bodySmall)
        }
        JournalPostActions(garden.vayne.chorus.data.PostThreads.foreignPost(post.id, post.kind, post.title, post.text, post.occurredAt, post.cw),
            model, chorus, onReply = { (onThread ?: onReply)?.invoke(post) }, foreign = true, replies = post.replyCount,
            onQuote = onQuote?.let { quote -> { quote(post) } },
            reactions = PostReactions.merge(model.postReactions[post.id].orEmpty(), reactions),
            onReacted = { who -> reactions = PostReactions.toggle(reactions, who.id, who.shownName) })
    }
}
