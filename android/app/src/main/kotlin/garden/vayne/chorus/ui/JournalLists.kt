package garden.vayne.chorus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import kotlinx.coroutines.launch
import org.json.JSONObject

/** The saved feeds that read from list [name] (`from:list:"name"`): the Feed pill on its row. */
internal fun feedsOfList(model: Model, name: String): List<String> =
    model.savedFeeds.filter { it.query.contains("from:list:\"$name\"", true) || it.query.contains("from:list:$name", true) }.map { it.name }

/**
 * F6.6: private member lists as an index (tile, count, faces, the feeds built on it); a tap opens the
 * list's members and posts. Read from the local replica, so it works offline.
 */
@Composable
internal fun JournalLists(chorus: Chorus, model: Model, selectedId: String, onSelect: (String) -> Unit,
    onTimeline: () -> Unit, onReply: (String) -> Unit, onThread: (String) -> Unit) {
    val p = LocalChorusPalette.current
    val actions = rememberCoroutineScope()
    val list = model.memberLists.find { it.id == selectedId }
    val mine = model.active.filter { it.createdByAccountId == null || it.createdByAccountId == chorus.device?.accountId }
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    BackHandler(list != null) { onSelect("") }

    fun write(kind: String, entityId: String, payload: JSONObject, after: () -> Unit = {}) {
        if (busy) return
        busy = true; error = null
        actions.launch {
            try { chorus.create(kind, entityId, payload); after() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not update this list." }
            finally { busy = false }
        }
    }

    Column(Modifier.fillMaxSize().background(p.bg)) {
        Row(Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ChatIconButton(R.drawable.ic_back, "Back") { if (list != null) onSelect("") else onTimeline() }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(list?.name ?: "Lists", color = p.ink, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                if (list != null) Text("${list.memberIds.size} ${if (list.memberIds.size == 1) "member" else "members"}", color = p.ink3,
                    style = MaterialTheme.typography.bodySmall)
            }
            if (list != null) ChatIconButton(R.drawable.ic_delete, "Delete list") { confirm = true }
            else ChatIconButton(R.drawable.ic_plus_circle, "New list") { creating = true }
        }
        if (error != null) Text(error.orEmpty(), color = p.danger, modifier = Modifier.padding(16.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            if (list == null) {
                items(model.memberLists, key = { it.id }) { row ->
                    val members = row.memberIds.mapNotNull { model.member(it) }
                    Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { onSelect(row.id) }.padding(16.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(p.accentSoft), contentAlignment = Alignment.Center) {
                                Glyph(R.drawable.ic_users, p.accent, 24.dp)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(row.name, color = p.ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                Text("${row.memberIds.size} ${if (row.memberIds.size == 1) "member" else "members"}", color = p.ink3, fontSize = 13.sp)
                                if (members.isNotEmpty()) Row {
                                    members.take(5).forEachIndexed { i, m -> Box(Modifier.offset(x = (-8 * i).dp)) { ChatAvatar(m, 28.dp) } }
                                }
                                for (feed in feedsOfList(model, row.name)) Row(Modifier.clip(CircleShape).background(p.surface3).padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Glyph(R.drawable.ic_hash, p.ink2, 13.dp)
                                    Text("Feed: $feed", color = p.ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                            Glyph(R.drawable.ic_chevron_right, p.ink3, 20.dp)
                        }
                    }
                    HorizontalDivider(color = p.line, thickness = .5.dp)
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, p.line, RoundedCornerShape(14.dp))
                        .clickable(role = Role.Button) { creating = true }.padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Glyph(R.drawable.ic_plus_circle, p.accent, 20.dp)
                        Text("New list", color = p.accent, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            } else {
                items(mine, key = { "member:${it.id}" }) { member ->
                    val included = member.id in list.memberIds
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy, role = Role.Checkbox) {
                        write(if (included) "list.remove" else "list.add", list.id, JSONObject().put("member_id", member.id))
                    }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ChatAvatar(member, 40.dp)
                        Text(member.shownName, color = p.ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        if (included) Glyph(R.drawable.ic_check, p.accent, 22.dp)
                    }
                }
                val posts = model.posts.filter { post -> post.authors.any { it in list.memberIds } }
                items(posts, key = { "post:${it.id}" }) { post ->
                    JournalPostCard(post, model, chorus, onReply = { onReply(it.id) }, onThread = { onThread(it.id) })
                }
            }
        }
    }
    if (creating) Drawer({ creating = false }, "New list", footer = {
        DrawerButton("Create list", {
            val id = chorus.newId()
            write("list.set", id, JSONObject().put("name", name.trim()).put("description", JSONObject.NULL)
                .put("visibility", JSONObject().put("mode", "private"))) { onSelect(id); name = ""; creating = false }
        }, enabled = !busy && name.isNotBlank(), busy = busy)
    }) {
        ChorusField(name, { name = it.take(80) }, label = { Text("List name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
    if (confirm && list != null) ConfirmDrawer("Delete this list?", null, "Delete", {
        confirm = false; write("list.delete", list.id, JSONObject()) { onSelect("") }
    }, { confirm = false }, danger = true)
}
