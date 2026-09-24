package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.designsystem.LocalChorusPalette
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Private member lists are read from the local replica and work while offline. */
@Composable
internal fun JournalLists(chorus: Chorus, model: Model, selectedId: String, onSelect: (String) -> Unit,
    onTimeline: () -> Unit, onReply: (String) -> Unit, onThread: (String) -> Unit) {
    val p = LocalChorusPalette.current
    val actions = rememberCoroutineScope()
    val list = model.memberLists.find { it.id == selectedId } ?: model.memberLists.firstOrNull()
    val mine = model.active.filter { it.createdByAccountId == null || it.createdByAccountId == chorus.device?.accountId }
    val posts = if (list == null) emptyList() else model.posts.filter { post ->
        post.authors.any { it in list.memberIds }
    }
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    fun write(kind: String, entityId: String, payload: JSONObject, after: () -> Unit = {}) {
        if (busy) return
        busy = true; error = null
        actions.launch {
            try { chorus.create(kind, entityId, payload); after() }
            catch (e: Exception) { error = e.message ?: "Could not update this list." }
            finally { busy = false }
        }
    }

    LazyColumn(Modifier.fillMaxSize().background(p.bg).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onTimeline) { Text("Timeline") }
                Text("Lists", color = p.ink, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 14.dp))
            }
            Text("Gather posts from members in this account. Lists stay private.", color = p.ink2)
        }
        item {
            Column(Modifier.fillMaxWidth().background(p.surface).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("List name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it }, label = { Text("Description (optional)") },
                    modifier = Modifier.fillMaxWidth())
                TextButton(enabled = !busy && name.isNotBlank(), onClick = {
                    val id = chorus.newId()
                    write("list.set", id, JSONObject().put("name", name.trim())
                        .put("description", description.trim().ifBlank { null } ?: JSONObject.NULL)
                        .put("visibility", JSONObject().put("mode", "private"))) {
                        onSelect(id); name = ""; description = ""
                    }
                }) { Text("Create list") }
            }
        }
        if (error != null) item { Text(error.orEmpty(), color = p.danger) }
        if (model.memberLists.isEmpty()) item { Text("Create a list to start collecting posts.", color = p.ink2) }
        else {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(model.memberLists, key = { it.id }) { row ->
                        JournalChoice(row.name, row.id == list?.id) { onSelect(row.id) }
                    }
                }
            }
            if (list != null) {
                item {
                    Column(Modifier.fillMaxWidth().background(p.surface).padding(12.dp)) {
                        Text(list.name, color = p.ink, fontWeight = FontWeight.SemiBold)
                        if (list.description != null) Text(list.description, color = p.ink2)
                        TextButton(enabled = !busy, onClick = {
                            write("list.delete", list.id, JSONObject()) { onSelect("") }
                        }) { Text("Delete list") }
                    }
                }
                item { Text("Members", color = p.ink, fontWeight = FontWeight.SemiBold) }
                items(mine, key = { "member:${it.id}" }) { member ->
                    val included = member.id in list.memberIds
                    TextButton(enabled = !busy, onClick = {
                        write(if (included) "list.remove" else "list.add", list.id,
                            JSONObject().put("member_id", member.id))
                    }) { Text("${if (included) "✓" else "○"} ${member.shownName}") }
                }
                item { Text("Posts", color = p.ink, fontWeight = FontWeight.SemiBold) }
                if (posts.isEmpty()) item { Text("No posts from these members yet.", color = p.ink2) }
                items(posts, key = { "post:${it.id}" }) { post ->
                    JournalPostCard(post, model, onReply = { onReply(post.id) },
                        onThread = { onThread(post.id) })
                }
            }
        }
    }
}
