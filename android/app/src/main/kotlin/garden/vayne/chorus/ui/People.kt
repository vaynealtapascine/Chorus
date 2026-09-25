package garden.vayne.chorus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import garden.vayne.chorus.data.FollowList
import garden.vayne.chorus.data.FollowPresets
import garden.vayne.chorus.data.FollowerView
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.PeopleApi
import garden.vayne.chorus.data.PostReactions
import garden.vayne.chorus.data.Reply
import garden.vayne.chorus.data.Spaces
import garden.vayne.chorus.data.SharedPost
import garden.vayne.chorus.designsystem.LocalChorusPalette
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Account relationships. Other accounts' presence is read only through the filtered server view. */
@Composable
fun People(chorus: Chorus, model: Model, onOpenChat: (String) -> Unit, onReplyPost: (String) -> Unit) {
    val p = LocalChorusPalette.current
    val actions = rememberCoroutineScope()
    var follows by remember { mutableStateOf(FollowList(emptyList(), emptyList())) }
    var views by remember { mutableStateOf<Map<String, FollowerView>>(emptyMap()) }
    var sharedPosts by remember { mutableStateOf<Map<String, List<SharedPost>>>(emptyMap()) }
    var target by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var loadedAccount by remember { mutableStateOf<String?>(null) }
    var requestChoices by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var threadPostId by rememberSaveable { mutableStateOf<String?>(null) }
    val reactMember = Reply.speaker(model)?.takeIf { member ->
        member.createdByAccountId == null || member.createdByAccountId == chorus.device?.accountId
    }

    LaunchedEffect(chorus.device?.session, model, refresh) {
        val dev = chorus.device ?: return@LaunchedEffect
        if (loadedAccount != dev.accountId) {
            follows = FollowList(emptyList(), emptyList())
            views = emptyMap()
            sharedPosts = emptyMap()
            loadedAccount = dev.accountId
        }
        sharedPosts = emptyMap()
        views = emptyMap()
        try {
            val next = PeopleApi.list(dev)
            follows = next
            views = next.following.filter { it.status == "active" }.mapNotNull { f ->
                runCatching { f.account.id to PeopleApi.followerView(PeopleApi.view(dev, f.account.id)) }.getOrNull()
            }.toMap()
            sharedPosts = next.following.filter { it.status == "active" }.mapNotNull { f ->
                runCatching { f.account.id to PeopleApi.posts(dev, f.account.id) }.getOrNull()
            }.toMap()
            error = null
        } catch (e: Exception) {
            sharedPosts = emptyMap()
            views = emptyMap()
            error = e.message ?: "Could not load people."
        }
    }

    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        actions.launch {
            try { block(); refresh++ }
            catch (e: Exception) { error = e.message ?: "Could not update this follow." }
            finally { busy = false }
        }
    }

    if (threadPostId != null) {
        val id = threadPostId!!
        BackHandler { threadPostId = null }
        JournalThread(chorus, model, id, onClose = { threadPostId = null },
            onReply = { threadPostId = null; onReplyPost(id) })
        return
    }

    LazyColumn(Modifier.fillMaxSize().background(p.bg).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("People", color = p.ink, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(target, { target = it }, label = { Text("@handle") }, singleLine = true,
                    modifier = Modifier.weight(1f))
                TextButton(enabled = !busy && target.isNotBlank(), onClick = {
                    action {
                        val dev = checkNotNull(chorus.device) { "Not signed in." }
                        PeopleApi.request(dev, target)
                        target = ""
                    }
                }) { Text("Follow") }
            }
            if (error != null) Text(error.orEmpty(), color = p.danger)
            TextButton(onClick = { refresh++ }) { Text("Refresh") }
        }
        val requests = follows.followers.filter { it.status == "requested" }
        if (requests.isNotEmpty()) {
            item { Text("Requests", color = p.ink, fontWeight = FontWeight.SemiBold) }
            for (f in requests) item(key = "request:${f.id}") {
                Column(Modifier.fillMaxWidth().background(p.surface).padding(12.dp)) {
                    Text("${f.account.shownName} wants to follow you", color = p.ink)
                    FollowPresetPicker("They'll see switches", requestChoices[f.id] ?: "inherit") {
                        requestChoices = requestChoices + (f.id to it)
                    }
                    Row {
                        TextButton(enabled = !busy, onClick = { action {
                            chorus.create("follow.accept", f.id, JSONObject())
                            chorus.create("follow.set_ceiling", f.id, JSONObject().put("ceiling",
                                FollowPresets.ceiling(requestChoices[f.id] ?: "inherit", model.followCeilings[f.id] ?: JSONObject())))
                        } }) { Text("Accept") }
                        TextButton(enabled = !busy, onClick = { action {
                            chorus.create("follow.end", f.id, JSONObject())
                        } }) { Text("Decline") }
                    }
                }
            }
        }
        item {
            Text("Followers", color = p.ink, fontWeight = FontWeight.SemiBold)
            Text("Delay hides when you switched as it happens; fuzzing hides the exact time afterwards.", color = p.ink2)
        }
        if (follows.followers.none { it.status == "active" }) item { Text("No followers yet.", color = p.ink2) }
        for (f in follows.followers.filter { it.status == "active" }) item(key = "follower:${f.id}") {
            Column(Modifier.fillMaxWidth().background(p.surface).padding(12.dp)) {
                Text(f.account.shownName, color = p.ink)
                FollowPresetPicker("Sees switches", FollowPresets.choiceOf(model.followCeilings[f.id] ?: JSONObject())) { choice ->
                    action {
                        chorus.create("follow.set_ceiling", f.id, JSONObject().put("ceiling",
                            FollowPresets.ceiling(choice, model.followCeilings[f.id] ?: JSONObject())))
                    }
                }
                FollowAdvanced(model.followCeilings[f.id] ?: JSONObject(), !busy) { key, enabled ->
                    action {
                        chorus.create("follow.set_ceiling", f.id, JSONObject().put("ceiling",
                            FollowPresets.withSharing(model.followCeilings[f.id] ?: JSONObject(), key, enabled)))
                    }
                }
                Row {
                    TextButton(enabled = !busy, onClick = { action {
                        val dev = checkNotNull(chorus.device) { "Not signed in." }
                        onOpenChat(Spaces.openDm(dev, f.account.id))
                    } }) { Text("Message") }
                    TextButton(enabled = !busy, onClick = { action {
                        chorus.create("follow.end", f.id, JSONObject())
                    } }) { Text("Remove") }
                }
            }
        }
        item { Text("Following", color = p.ink, fontWeight = FontWeight.SemiBold) }
        if (follows.following.isEmpty()) item { Text("You aren't following anyone yet.", color = p.ink2) }
        for (f in follows.following) item(key = "following:${f.id}") {
            Column(Modifier.fillMaxWidth().background(p.surface).padding(12.dp)) {
                Text(f.account.shownName, color = p.ink)
                Text(if (f.status == "requested") "Waiting for them to accept"
                    else views[f.account.id]?.frontNames?.takeIf { it.isNotEmpty() }?.joinToString(" & ") ?: "Nothing shared yet",
                    color = p.ink2)
                if (f.status == "active") views[f.account.id]?.let { FollowerViewDetails(it) }
                Row {
                    if (f.status == "active") TextButton(enabled = !busy, onClick = { action {
                        val dev = checkNotNull(chorus.device) { "Not signed in." }
                        onOpenChat(Spaces.openDm(dev, f.account.id))
                    } }) { Text("Message") }
                    TextButton(enabled = !busy, onClick = { action {
                        val dev = checkNotNull(chorus.device) { "Not signed in." }
                        PeopleApi.unfollow(dev, f.id)
                    } }) { Text("Unfollow") }
                }
                sharedPosts[f.account.id]?.takeIf { it.isNotEmpty() }?.let { posts ->
                    Text("Shared posts", color = p.ink2, fontWeight = FontWeight.SemiBold)
                    for (post in posts) SharedPostPreview(post, f.account.shownName, chorus,
                        reactMember?.id, !busy, onThread = { threadPostId = it.id },
                        onReply = { onReplyPost(it.id) }) { selected ->
                        val member = reactMember ?: return@SharedPostPreview
                        if (busy) return@SharedPostPreview
                        busy = true; error = null
                        actions.launch {
                            try {
                                val chosen = selected.reactions.any { it.emoji == PostReactions.HEART && it.memberId == member.id }
                                chorus.create(if (chosen) "post.unreact" else "post.react", selected.id,
                                    PostReactions.payload(selected.id, member.id))
                                sharedPosts = sharedPosts + (f.account.id to posts.map { item ->
                                    if (item.id == selected.id) item.copy(reactions = PostReactions.toggle(item.reactions,
                                        member.id, member.shownName)) else item
                                })
                            } catch (e: Exception) { error = e.message ?: "Could not react to this post." }
                            finally { busy = false }
                        }
                    }
                }
            }
        }
        item { Text("A follower sees your switches only within the sharing limit you choose for them.",
            color = p.ink2, modifier = Modifier.padding(bottom = 20.dp)) }
    }
}

@Composable
private fun FollowerViewDetails(view: FollowerView) {
    val p = LocalChorusPalette.current
    view.stats?.let { stats ->
        val shown = stats.members.filter { it.second > 0 }.joinToString(" · ") { "${it.first} ${it.second}%" }
        Text(if (shown.isEmpty()) "No complete days of shared fronting yet"
            else "Most often, last ${stats.days} days: $shown", color = p.ink2)
    }
    view.history?.takeIf { it.size > 1 }?.let { history ->
        var open by rememberSaveable { mutableStateOf(false) }
        TextButton(onClick = { open = !open }) { Text(if (open) "Hide earlier fronts" else "Earlier fronts") }
        if (open) for (front in history.drop(1)) {
            Text(front.names.joinToString(" & ").ifBlank { "Nobody shared" } +
                front.time.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(), color = p.ink2)
        }
    }
}

@Composable
private fun FollowAdvanced(ceiling: JSONObject, enabled: Boolean, onChange: (String, Boolean) -> Unit) {
    val p = LocalChorusPalette.current
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text(if (open) "Advanced sharing ▴" else "Advanced sharing ▾") }
    if (open) {
        Text("History shows only already revealed, fuzzed fronts; stats use rounded whole days.", color = p.ink2)
        for ((key, label) in listOf("share_history" to "Can look back at who fronted",
            "share_stats" to "Can see fronting stats")) {
            TextButton(enabled = enabled, onClick = { onChange(key, !ceiling.optBoolean(key, false)) }) {
                Text("${if (ceiling.optBoolean(key, false)) "✓" else "○"} $label")
            }
        }
    }
}

@Composable
internal fun SharedPostPreview(post: SharedPost, accountName: String, chorus: Chorus, reactMemberId: String? = null,
    enabled: Boolean = true, onThread: ((SharedPost) -> Unit)? = null,
    onReply: ((SharedPost) -> Unit)? = null, onReact: (SharedPost) -> Unit = {}) {
    val p = LocalChorusPalette.current
    var revealed by rememberSaveable(post.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(p.surface2).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${post.authorNames.joinToString(" & ").ifBlank { accountName }} · ${post.kind}",
            color = p.ink, fontWeight = FontWeight.SemiBold)
        Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(post.occurredAt)),
            color = p.ink3, fontSize = 12.sp)
        if (post.cw != null) Text("Content warning: ${post.cw} · ${if (revealed) "Hide" else "Show"}",
            color = p.accent, modifier = Modifier.clickable { revealed = !revealed })
        if (post.cw == null || revealed) {
            if (post.title != null) Text(post.title, color = p.ink, fontWeight = FontWeight.SemiBold)
            Text(post.text, color = p.ink)
            for (attachment in post.attachments) ChatAttachmentView(attachment, chorus)
            if (post.reactions.isNotEmpty()) Text(post.reactions.joinToString(" · ") { "${it.emoji} ${it.memberName}" }, color = p.ink2)
            if (onThread != null) TextButton(enabled = enabled, onClick = { onThread(post) }) { Text("Thread") }
            if (reactMemberId != null) {
                val selected = post.reactions.any { it.emoji == PostReactions.HEART && it.memberId == reactMemberId }
                TextButton(enabled = enabled, onClick = { onReact(post) }) {
                    Text(if (selected) "Remove 💜 reaction" else "React 💜")
                }
                Text("Reacting shows this member to post readers right away.", color = p.ink3, fontSize = 12.sp)
                if (onReply != null) TextButton(enabled = enabled, onClick = { onReply(post) }) { Text("Reply") }
            }
        }
    }
}

@Composable
private fun FollowPresetPicker(label: String, choice: String, onChoice: (String) -> Unit) {
    val p = LocalChorusPalette.current
    var open by remember { mutableStateOf(false) }
    val labels = mapOf(
        "inherit" to "Account default", "close" to "Close · right away, exact time",
        "gentle" to "Gentle · 5–20 min, rounded", "private" to "Private · 30–90 min, part of day",
        "digest" to "Digest · daily summary", "off" to "Off · no switch alerts",
        "custom" to "Custom (Advanced)",
    )
    Column {
        Text(label, color = p.ink2)
        TextButton(onClick = { open = true }) { Text("${labels[choice] ?: labels.getValue("custom")} ▾") }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            for (option in FollowPresets.choices) DropdownMenuItem(text = { Text(labels.getValue(option)) }, onClick = {
                open = false
                onChoice(option)
            })
        }
    }
}
