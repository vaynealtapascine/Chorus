package garden.vayne.chorus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.JournalPost
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.PostCompose
import garden.vayne.chorus.data.PostThreads
import garden.vayne.chorus.data.Reply
import garden.vayne.chorus.data.ThreadReply
import garden.vayne.chorus.designsystem.LocalChorusPalette
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/** Own posts and switches remain readable from the local replica while offline. */
@Composable
fun Journal(chorus: Chorus, model: Model, externalReplyPost: String? = null,
    onExternalReplyConsumed: () -> Unit = {}) {
    val p = LocalChorusPalette.current
    var editing by rememberSaveable { mutableStateOf(false) }
    var profileId by rememberSaveable { mutableStateOf<String?>(null) }
    var threadPostId by rememberSaveable { mutableStateOf<String?>(null) }
    var replyTo by rememberSaveable { mutableStateOf<String?>(null) }
    var kind by rememberSaveable { mutableStateOf("note") }
    var authorId by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var cw by rememberSaveable { mutableStateOf("") }
    var mood by rememberSaveable { mutableStateOf("") }
    var tags by rememberSaveable { mutableStateOf("") }
    var audience by rememberSaveable { mutableStateOf("private") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val actions = rememberCoroutineScope()
    val mine = model.active.filter { it.createdByAccountId == null || it.createdByAccountId == chorus.device?.accountId }
    val author = mine.find { it.id == authorId } ?: Reply.speaker(model)?.takeIf { it in mine } ?: mine.firstOrNull()

    LaunchedEffect(externalReplyPost) {
        if (externalReplyPost != null) {
            profileId = null; threadPostId = null
            replyTo = externalReplyPost
            kind = "note"; title = ""; body = ""; cw = ""; mood = ""; tags = ""
            audience = "server" // the foreign parent author must be able to read this reply
            authorId = Reply.speaker(model)?.id.orEmpty()
            editing = true
            onExternalReplyConsumed()
        }
    }

    if (!editing && profileId != null) {
        val id = profileId!!
        BackHandler { profileId = null }
        MemberProfile(chorus, model, id, onClose = { profileId = null },
            onWrite = { authorId = id; replyTo = null; editing = true },
            onReply = { postId -> authorId = id; replyTo = postId; editing = true })
        return
    }

    if (!editing && threadPostId != null) {
        val id = threadPostId!!
        BackHandler { threadPostId = null }
        JournalThread(chorus, model, id, onClose = { threadPostId = null },
            onReply = { replyTo = id; editing = true })
        return
    }

    if (editing) {
        Column(Modifier.fillMaxSize().background(p.bg).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { editing = false; replyTo = null }) { Text("Cancel") }
                Text(if (replyTo == null) "Write a post" else "Write a reply", color = p.ink,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
                TextButton(enabled = !busy && author != null && body.isNotBlank(), onClick = {
                    val dev = chorus.device ?: return@TextButton
                    val writer = author ?: return@TextButton
                    busy = true; error = null
                    actions.launch {
                        try {
                            val payload = PostCompose.payload(model, dev.accountId, kind, writer.id, body,
                                title, cw, audience, mood, tags, replyTo)
                            chorus.create("post.create", chorus.newId(), payload)
                            body = ""; title = ""; cw = ""; mood = ""; tags = ""; replyTo = null
                            editing = false
                        } catch (e: Exception) { error = e.message ?: "Could not post." }
                        finally { busy = false }
                    }
                }) { Text(if (busy) "Posting…" else "Post") }
            }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (replyTo != null) item {
                    Text("Replying to a post. Check the audience before posting.", color = p.ink2)
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        JournalChoice("Note", kind == "note") { kind = "note" }
                        JournalChoice("Entry", kind == "entry") { kind = "entry" }
                    }
                }
                item { Text("Writing as", color = p.ink2) }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(mine, key = { it.id }) { member ->
                            JournalChoice(member.shownName, member.id == author?.id) { authorId = member.id }
                        }
                    }
                }
                if (kind == "entry") item {
                    OutlinedTextField(title, { title = it }, label = { Text("Title (optional)") }, modifier = Modifier.fillMaxWidth())
                }
                item {
                    OutlinedTextField(body, { body = it }, label = { Text(if (kind == "entry") "Write your entry" else "Write a note") },
                        minLines = if (kind == "entry") 8 else 4, modifier = Modifier.fillMaxWidth())
                }
                item { Text("Visible to", color = p.ink2) }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { JournalChoice("Only this account", audience == "private") { audience = "private" } }
                        item { JournalChoice("Followers", audience == "followers") { audience = "followers" } }
                        item { JournalChoice("Everyone on server", audience == "server") { audience = "server" } }
                    }
                }
                item { OutlinedTextField(cw, { cw = it }, label = { Text("Content warning (optional)") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(mood, { mood = it }, label = { Text("Mood (optional)") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(tags, { tags = it }, label = { Text("Tags, separated by commas") }, modifier = Modifier.fillMaxWidth()) }
                if (error != null) item { Text(error.orEmpty(), color = p.danger) }
            }
        }
        return
    }

    val events = (model.posts.map { JournalEvent(it.id, it.occurredAt, it, null) } +
        model.switches.map { JournalEvent(it.id, it.occurredAt, null,
            if (it.retracted) "Undone switch" else "Front: " +
                (it.resultingFront.filter { e -> e.level == "front" }.mapNotNull { e -> model.subject(e.subjectType, e.subjectId)?.name }
                    .joinToString(" & ").ifBlank { "no one" })) })
        .sortedWith(compareByDescending<JournalEvent> { it.at }.thenByDescending { it.id })
    LazyColumn(Modifier.fillMaxSize().background(p.bg).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Journal", color = p.ink, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 14.dp))
                TextButton(onClick = { editing = true }) { Text("Write") }
            }
        }
        if (events.isEmpty()) item { Text("No posts or switches yet. Write the first note.", color = p.ink2) }
        for (event in events) item(key = "${if (event.post == null) "switch" else "post"}:${event.id}") {
            if (event.post != null) JournalPostCard(event.post, model,
                onReply = { replyTo = event.post.id; editing = true },
                onThread = { threadPostId = event.post.id },
                onProfile = { profileId = it })
            else Column(Modifier.fillMaxWidth().background(p.surface).padding(12.dp)) {
                Text(event.switchLabel.orEmpty(), color = p.ink2)
                Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(event.at)),
                    color = p.ink3, fontSize = 12.sp)
            }
        }
    }
}

private data class JournalEvent(val id: String, val at: Long, val post: JournalPost?, val switchLabel: String?)

@Composable
private fun JournalChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    Text(label, color = if (selected) p.accent else p.ink2,
        modifier = Modifier.background(if (selected) p.surface2 else p.surface)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp))
}

@Composable
internal fun JournalPostCard(post: JournalPost, model: Model, onReply: () -> Unit,
    onThread: (() -> Unit)? = null,
    onProfile: (String) -> Unit = {}) {
    val p = LocalChorusPalette.current
    var revealed by rememberSaveable(post.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(p.surface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (post.authors.isEmpty()) Text("Someone", color = p.ink, fontWeight = FontWeight.SemiBold)
        else Row {
            for (id in post.authors) model.member(id)?.let { member ->
                TextButton(onClick = { onProfile(id) }) { Text(member.shownName) }
            }
        }
        Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(post.occurredAt)),
            color = p.ink3, fontSize = 12.sp)
        if (post.replyTo != null) Text("↪ Reply", color = p.ink3)
        if (post.cw != null) Text("Content warning: ${post.cw} · ${if (revealed) "Hide" else "Show"}",
            color = p.accent, modifier = Modifier.clickable { revealed = !revealed })
        if (post.cw == null || revealed) {
            if (post.title != null) Text(post.title, color = p.ink, fontWeight = FontWeight.SemiBold)
            Text(post.text, color = p.ink)
            if (post.mood != null) Text(post.mood, color = p.ink2)
            if (post.tags.isNotEmpty()) Text(post.tags.joinToString(" ") { "#$it" }, color = p.ink2)
        }
        Row {
            TextButton(onClick = onReply) { Text("Reply") }
            if (onThread != null) TextButton(onClick = onThread) { Text("Thread") }
        }
    }
}

/** Server replies can come from other accounts, while own replies remain available offline. */
@Composable
internal fun JournalThread(chorus: Chorus, model: Model, postId: String, onClose: () -> Unit, onReply: () -> Unit) {
    val p = LocalChorusPalette.current
    var remote by remember(postId) { mutableStateOf<List<ThreadReply>?>(null) }
    var error by remember(postId) { mutableStateOf<String?>(null) }
    var refresh by remember(postId) { mutableStateOf(0) }
    val local = PostThreads.ownReplies(model, postId)
    LaunchedEffect(postId, chorus.device?.session, refresh) {
        val dev = chorus.device ?: return@LaunchedEffect
        try {
            remote = PostThreads.load(dev, postId)
            error = null
        } catch (e: Exception) {
            remote = null
            error = e.message ?: "Could not load replies."
        }
    }
    val replies = PostThreads.merge(local, remote.orEmpty())
    LazyColumn(Modifier.fillMaxSize().background(p.bg).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onClose) { Text("Back") }
                Text("Replies", color = p.ink, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 14.dp))
                TextButton(onClick = { refresh++ }) { Text("Refresh") }
            }
        }
        if (error != null) item { Text("Showing replies on this device. ${error.orEmpty()}", color = p.ink2) }
        if (replies.isEmpty()) item {
            Text(if (remote == null && error == null) "Loading replies…" else "No replies yet.", color = p.ink2)
        }
        items(replies, key = { it.id }) { reply -> ThreadReplyCard(reply) }
        item { TextButton(onClick = onReply) { Text("Write a reply") } }
    }
}

@Composable
private fun ThreadReplyCard(reply: ThreadReply) {
    val p = LocalChorusPalette.current
    var revealed by rememberSaveable(reply.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(p.surface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(reply.authorNames.joinToString(" & ").ifBlank { "Someone" }, color = p.ink,
            fontWeight = FontWeight.SemiBold)
        Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(reply.occurredAt)),
            color = p.ink3, fontSize = 12.sp)
        if (reply.cw != null) Text("Content warning: ${reply.cw} · ${if (revealed) "Hide" else "Show"}",
            color = p.accent, modifier = Modifier.clickable { revealed = !revealed })
        if (reply.cw == null || revealed) {
            if (reply.title != null) Text(reply.title, color = p.ink, fontWeight = FontWeight.SemiBold)
            Text(reply.text, color = p.ink)
        }
    }
}
