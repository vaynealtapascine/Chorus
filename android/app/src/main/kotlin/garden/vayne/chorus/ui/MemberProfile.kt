package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.ProfileApi
import garden.vayne.chorus.data.ProfileBundle
import garden.vayne.chorus.designsystem.LocalChorusPalette

/** Own member profile: local posts stay visible offline; server adds stats and readable highlights. */
@Composable
internal fun MemberProfile(chorus: Chorus, model: Model, memberId: String,
    onClose: () -> Unit, onWrite: () -> Unit, onReply: (String) -> Unit) {
    val p = LocalChorusPalette.current
    val dev = chorus.device
    val member = model.member(memberId)?.takeIf { it.createdByAccountId == null || it.createdByAccountId == dev?.accountId }
    var tab by rememberSaveable(memberId) { mutableStateOf("Posts") }
    var bundle by remember(memberId, dev?.accountId) { mutableStateOf<ProfileBundle?>(null) }
    var error by remember(memberId, dev?.accountId) { mutableStateOf<String?>(null) }

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
    val selectedPosts = when (tab) {
        "Replies" -> ownPosts.filter { it.replyTo != null }
        "Journal" -> ownPosts.filter { it.kind == "entry" && it.replyTo == null }
        else -> ownPosts.filter { it.replyTo == null }
    }
    LazyColumn(Modifier.fillMaxSize().background(p.bg).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onClose) { Text("‹ Journal") }
                TextButton(onClick = onWrite) { Text("Post as ${member.shownName}") }
            }
            Text(member.shownName, color = p.ink, fontWeight = FontWeight.SemiBold)
            if (member.displayName != null) Text(member.name, color = p.ink2)
            if (member.pronouns != null) Text(member.pronouns, color = p.ink2)
            if (member.sigils.isNotEmpty()) Text(member.sigils.joinToString(" · "), color = p.ink2)
            if (member.description != null) Text(member.description, color = p.ink)
            val groups = model.membership.filter { memberId in it.value }.keys.mapNotNull { model.group(it)?.name }
            if (groups.isNotEmpty()) Text(groups.joinToString(" · "), color = p.ink2)
            bundle?.let { details ->
                Text("${details.posts} posts · ${details.entries} entries · ${details.notes} notes", color = p.ink2)
            }
            if (error != null) Text(error.orEmpty(), color = p.ink3)
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
                val highlights = bundle?.highlights.orEmpty()
                if (highlights.isEmpty()) item { Text(if (bundle == null) "Highlights need a connection." else "No highlights yet.", color = p.ink2) }
                for (post in highlights) item(key = "highlight:${post.id}") {
                    SharedPostPreview(post, member.shownName)
                }
            }
            "Relationships" -> {
                val relations = bundle?.relations.orEmpty()
                if (relations.isEmpty()) item { Text(if (bundle == null) "Relationships need a connection." else "No relationships yet.", color = p.ink2) }
                for ((index, relation) in relations.withIndex()) item(key = "relation:$index") {
                    Column(Modifier.fillMaxWidth().background(p.surface).padding(12.dp)) {
                        Text(listOf(relation.name, relation.target).filter { it.isNotBlank() }.joinToString(" · "), color = p.ink)
                        if (relation.note != null) Text(relation.note, color = p.ink2)
                    }
                }
            }
            else -> {
                if (selectedPosts.isEmpty()) item { Text("No ${tab.lowercase()} yet.", color = p.ink2) }
                for (post in selectedPosts) item(key = "profile-post:${post.id}") {
                    JournalPostCard(post, model, onReply = { onReply(post.id) })
                }
            }
        }
        item { Text("", modifier = Modifier.padding(bottom = 16.dp)) }
    }
}
