package garden.vayne.chorus.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Blobs
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.ProfileApi
import garden.vayne.chorus.data.ProfileBundle
import garden.vayne.chorus.data.ProfileHighlights
import garden.vayne.chorus.data.ProfileMetrics
import garden.vayne.chorus.designsystem.LocalChorusPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Own member profile: local details stay visible offline; server adds readable outside highlights. */
@Composable
internal fun MemberProfile(chorus: Chorus, model: Model, memberId: String,
    onClose: () -> Unit, onWrite: () -> Unit, onReply: (String) -> Unit) {
    val p = LocalChorusPalette.current
    val dev = chorus.device
    val ctx = LocalContext.current
    val actions = rememberCoroutineScope()
    val member = model.member(memberId)?.takeIf { it.createdByAccountId == null || it.createdByAccountId == dev?.accountId }
    var tab by rememberSaveable(memberId) { mutableStateOf("Posts") }
    var bundle by remember(memberId, dev?.accountId) { mutableStateOf<ProfileBundle?>(null) }
    var error by remember(memberId, dev?.accountId) { mutableStateOf<String?>(null) }
    var pinBusy by remember(memberId) { mutableStateOf(false) }
    var highlightBusy by remember(memberId) { mutableStateOf(false) }
    var removedHighlights by remember(memberId) { mutableStateOf<Set<String>>(emptySet()) }
    val banner = produceState<android.graphics.Bitmap?>(initialValue = null, member?.bannerBlob, dev?.session) {
        value = if (member?.bannerBlob != null && dev != null) withContext(Dispatchers.IO) {
            Blobs.image(ctx.applicationContext, member.bannerBlob, dev, kind = "profile", maxPx = 1400,
                maxBytes = 10L * 1024 * 1024)
        } else null
    }

    LaunchedEffect(dev?.session, memberId) {
        bundle = null
        if (dev != null && member != null) {
            try { bundle = ProfileApi.load(dev, memberId); error = null }
            catch (e: Exception) { error = "Profile details unavailable offline." }
        }
    }

    if (member == null) {
        Column(Modifier.fillMaxSize().background(p.bg).padding(16.dp)) {
            TextButton(onClick = onClose) { Text("‹ Journal") }
            Text("Member unavailable", color = p.ink2)
        }
        return
    }

    val ownPosts = model.posts.filter { memberId in it.authors }
    val metrics = ProfileMetrics.compute(model, memberId)
    val pinned = model.posts.find { it.id == member.pinnedPostId }
    val highlightedIds = model.highlights[memberId].orEmpty()
    val localHighlights = model.posts.filter { it.id in highlightedIds }
    fun setHighlight(postId: String, add: Boolean) {
        if (highlightBusy) return
        highlightBusy = true; error = null
        actions.launch {
            try {
                chorus.create(if (add) "highlight.add" else "highlight.remove", memberId,
                    ProfileHighlights.payload(memberId, postId))
                removedHighlights = if (add) removedHighlights - postId else removedHighlights + postId
            } catch (e: Exception) { error = e.message ?: "Could not update this highlight." }
            finally { highlightBusy = false }
        }
    }
    val selectedPosts = when (tab) {
        "Replies" -> ownPosts.filter { it.replyTo != null }
        "Journal" -> ownPosts.filter { it.kind == "entry" && it.replyTo == null }
        else -> ownPosts.filter { it.replyTo == null }
    }
    LazyColumn(Modifier.fillMaxSize().background(p.bg).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            if (banner.value != null) Image(banner.value!!.asImageBitmap(), contentDescription = "Profile banner",
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(150.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onClose) { Text("‹ Journal") }
                TextButton(onClick = onWrite) { Text("Post as ${member.shownName}") }
            }
            Avatar(member.glyph, member.color, size = 64.dp, avatarBlob = member.avatarBlob)
            Text(member.shownName, color = p.ink, fontWeight = FontWeight.SemiBold)
            if (member.displayName != null) Text(member.name, color = p.ink2)
            if (member.pronouns != null) Text(member.pronouns, color = p.ink2)
            if (member.sigils.isNotEmpty()) Text(member.sigils.joinToString(" · "), color = p.ink2)
            if (member.description != null) Text(member.description, color = p.ink)
            val groups = model.membership.filter { memberId in it.value }.keys.mapNotNull { model.group(it)?.name }
            if (groups.isNotEmpty()) Text(groups.joinToString(" · "), color = p.ink2)
            for (field in model.profileFields[memberId].orEmpty()) Text("${field.name}: ${field.value}", color = p.ink2)
            Text("Front: ${"%.1f".format(metrics.weekHours)} h in 7 days · ${"%.1f".format(metrics.monthHours)} h in 28 days",
                color = p.ink2)
            Text(metrics.lastFrontAt?.let { "Last fronted ${DateFormat.getDateInstance().format(Date(it))}" }
                ?: "No front recorded", color = p.ink2)
            Text("${metrics.messages} messages", color = p.ink2)
            bundle?.let { details ->
                Text("${details.posts} posts · ${details.entries} entries · ${details.notes} notes", color = p.ink2)
            }
            if (error != null) Text(error.orEmpty(), color = p.ink3)
        }
        if (pinned != null) item(key = "pinned:${pinned.id}") {
            Text("Pinned post", color = p.ink, fontWeight = FontWeight.SemiBold)
            JournalPostCard(pinned, model, onReply = { onReply(pinned.id) })
            TextButton(enabled = !pinBusy, onClick = {
                pinBusy = true
                actions.launch {
                    try { chorus.create("member.set", memberId, org.json.JSONObject().put("pinned_post_id", org.json.JSONObject.NULL)) }
                    catch (e: Exception) { error = e.message ?: "Could not unpin this post." }
                    finally { pinBusy = false }
                }
            }) { Text("Unpin") }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (choice in listOf("Posts", "Replies", "Journal", "Highlights", "Relationships")) item {
                    Text(choice, color = if (tab == choice) p.accent else p.ink2,
                        modifier = Modifier.background(if (tab == choice) p.surface2 else p.surface)
                            .clickable { tab = choice }.padding(horizontal = 10.dp, vertical = 8.dp))
                }
            }
        }
        when (tab) {
            "Highlights" -> {
                val foreign = bundle?.highlights.orEmpty().filter { post ->
                    model.posts.none { it.id == post.id } && post.id !in removedHighlights
                }
                if (localHighlights.isEmpty() && foreign.isEmpty()) item {
                    Text(if (bundle == null && localHighlights.isEmpty()) "No saved highlights on this device. Connect to load others."
                        else "No highlights yet.", color = p.ink2)
                }
                for (post in localHighlights) item(key = "local-highlight:${post.id}") {
                    JournalPostCard(post, model, onReply = { onReply(post.id) })
                    TextButton(enabled = !highlightBusy, onClick = { setHighlight(post.id, false) }) { Text("Remove highlight") }
                }
                for (post in foreign) item(key = "remote-highlight:${post.id}") {
                    SharedPostPreview(post, member.shownName)
                    TextButton(enabled = !highlightBusy, onClick = { setHighlight(post.id, false) }) { Text("Remove highlight") }
                }
            }
            "Relationships" -> {
                item { ProfileRelationships(chorus, model, memberId) }
            }
            else -> {
                if (selectedPosts.isEmpty()) item { Text("No ${tab.lowercase()} yet.", color = p.ink2) }
                for (post in selectedPosts) item(key = "profile-post:${post.id}") {
                    JournalPostCard(post, model, onReply = { onReply(post.id) })
                    if (post.id != member.pinnedPostId) TextButton(enabled = !pinBusy, onClick = {
                        pinBusy = true
                        actions.launch {
                            try { chorus.create("member.set", memberId, org.json.JSONObject().put("pinned_post_id", post.id)) }
                            catch (e: Exception) { error = e.message ?: "Could not pin this post." }
                            finally { pinBusy = false }
                        }
                    }) { Text("Pin to profile") }
                    TextButton(enabled = !highlightBusy, onClick = {
                        setHighlight(post.id, post.id !in highlightedIds)
                    }) { Text(if (post.id in highlightedIds) "Remove highlight" else "Highlight post") }
                }
            }
        }
        item { Text("", modifier = Modifier.padding(bottom = 16.dp)) }
    }
}
