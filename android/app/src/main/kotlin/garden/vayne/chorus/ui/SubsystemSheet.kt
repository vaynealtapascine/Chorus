package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Member
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.Subsystems
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Make [member] a subsystem (a new subsystem group, or one that already exists), choose who is
 * inside them, or stop (D-092). Each change is one queued op, so it works offline. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubsystemSheet(chorus: Chorus, model: Model, member: Member, onDismiss: () -> Unit) {
    val p = LocalChorusPalette.current
    val actions = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable(member.id) { mutableStateOf("") }
    val group = Subsystems.groupOf(model, member.id)
    val name = member.shownName
    fun write(failure: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        actions.launch {
            try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { error = failure }
            finally { busy = false }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = p.surface) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(bottom = 16.dp)) {
            Text(if (group == null) "Make $name a subsystem" else "Inside $name", color = p.ink, fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp))
            Text(if (group == null) "Choose who is inside $name. While $name fronts, everyone inside shows as fronting with them. Switches stay exactly as you record them."
                else "While $name fronts, these members show as fronting with them.",
                color = p.ink2, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 12.dp))
            if (group == null) {
                Button(enabled = !busy, onClick = { write("Could not make $name a subsystem. Try again.") {
                    val id = chorus.newId()
                    chorus.create("group.create", id, Subsystems.groupPayload(model, member))
                    chorus.create("pref.set", null, Subsystems.payload(member.id, id))
                } }, modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
                    Icon(painterResource(R.drawable.ic_layers), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("New subsystem")
                }
                val existing = Subsystems.unlinkedGroups(model)
                if (existing.isNotEmpty()) {
                    Text("Or use a subsystem you already have", color = p.accent, fontSize = 13.sp,
                        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp))
                    LazyColumn {
                        items(existing, key = { it.id }) { g ->
                            val count = model.membership[g.id].orEmpty().count { id -> id != member.id && model.member(id)?.archived == false }
                            Row(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                                write("Could not link ${g.name}. Try again.") { chorus.create("pref.set", null, Subsystems.payload(member.id, g.id)) }
                            }.heightIn(min = 56.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(painterResource(R.drawable.ic_layers), null, Modifier.size(20.dp), tint = p.ink2)
                                Column(Modifier.weight(1f)) {
                                    Text(model.groupPath(g), color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("$count ${if (count == 1) "member" else "members"}", color = p.ink3, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            } else {
                SearchField(query, { query = it }, "Search members", Modifier.padding(horizontal = 16.dp).fillMaxWidth())
                val inside = model.membership[group.id].orEmpty()
                val account = chorus.device?.accountId
                val candidates = model.active.filter { it.id != member.id && (it.createdByAccountId == null || it.createdByAccountId == account) }
                    .mapNotNull { m -> if (query.isBlank()) 0 to m else fuzzy(query, listOfNotNull(m.name, m.displayName).joinToString(" "))?.let { it to m } }
                    .sortedWith(compareBy<Pair<Int, Member>> { it.second.id !in inside }.thenBy { it.first }.thenBy { it.second.shownName.lowercase() })
                    .map { it.second }
                LazyColumn(Modifier.weight(1f, fill = false).padding(top = 8.dp)) {
                    items(candidates, key = { it.id }) { m ->
                        val checked = m.id in inside
                        // someone already holding this member can't also be inside them
                        val loops = !checked && Subsystems.wouldLoop(model, member.id, m.id)
                        Row(Modifier.fillMaxWidth().clickable(enabled = !busy && !loops, role = Role.Checkbox) {
                            write("Could not update who is inside $name. Try again.") {
                                chorus.create(if (checked) "group.remove_member" else "group.add_member", group.id, JSONObject().put("member_id", m.id))
                            }
                        }.heightIn(min = 56.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Avatar(m.glyph, m.color, 36.dp, avatarBlob = m.avatarBlob, ring = false)
                            Column(Modifier.weight(1f)) {
                                Text(m.shownName, color = if (loops) p.ink3 else p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (loops) Text("$name is inside them", color = p.ink3, style = MaterialTheme.typography.bodySmall)
                            }
                            Checkbox(checked, onCheckedChange = null, enabled = !loops)
                        }
                    }
                }
                TextButton(enabled = !busy, onClick = { write("Could not change $name. Try again.") {
                    chorus.create("pref.set", null, Subsystems.payload(member.id, null))
                } }, modifier = Modifier.padding(start = 12.dp, top = 4.dp)) { Text("Stop being a subsystem") }
                Text("${group.name} stays as a subsystem group, with everyone still in it.", color = p.ink3,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp))
            }
            if (error != null) Text(error.orEmpty(), color = p.danger, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
    }
}

/** "Kai, Wren and 2 more inside" under a subsystem member's bio, with their faces. */
@Composable
internal fun InsideRow(model: Model, member: Member, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    val parts = Subsystems.parts(model, member.id)
    Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = "Choose who is inside ${member.shownName}", onClick = onClick)
        .padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (parts.isEmpty()) {
            Icon(painterResource(R.drawable.ic_plus), null, Modifier.size(16.dp), tint = p.accent)
            Spacer(Modifier.width(6.dp))
            Text("Add who is inside ${member.shownName}", color = p.accent, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp))
            return@Row
        }
        Box(Modifier.width((24 + 16 * (parts.size.coerceAtMost(4) - 1)).dp).height(24.dp)) {
            parts.take(4).forEachIndexed { i, part ->
                Box(Modifier.padding(start = (16 * i).dp).size(24.dp).background(p.bg, CircleShape), contentAlignment = Alignment.Center) {
                    Avatar(part.glyph, part.color, 21.dp, avatarBlob = part.avatarBlob, ring = false)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        val names = parts.take(2).joinToString(", ") { it.shownName }
        Text(if (parts.size > 2) "$names and ${parts.size - 2} more inside" else "$names inside", color = p.ink2,
            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp))
    }
}
