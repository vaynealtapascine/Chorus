package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Member
import garden.vayne.chorus.data.MemberDirectory
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.Subsystems
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Make [member] a subsystem (a new subsystem group, or one that already exists), choose who is
 * inside them, or stop (D-092). Each change is one queued op, so it works offline. */
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
    Drawer(onDismiss, if (group == null) "Make $name a subsystem" else "Inside $name", group?.let { MemberDirectory.line(model, it) },
        leading = { Avatar(member.glyph, member.color, 40.dp, avatarBlob = member.avatarBlob, ring = false) },
        footer = if (group == null) ({
            DrawerButton("New subsystem", enabled = !busy, onClick = { write("Could not make $name a subsystem. Try again.") {
                val id = chorus.newId()
                chorus.create("group.create", id, Subsystems.groupPayload(model, member))
                chorus.create("pref.set", null, Subsystems.payload(member.id, id))
            } })
        }) else ({ DrawerButton("Done", onDismiss) })) {
        if (group == null) {
            val existing = Subsystems.unlinkedGroups(model)
            if (existing.isNotEmpty()) {
                SectionLabel("Existing")
                FormCard {
                    existing.forEachIndexed { i, g ->
                        if (i > 0) FormDivider()
                        val count = model.membership[g.id].orEmpty().count { id -> id != member.id && model.member(id)?.archived == false }
                        Row(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                            write("Could not link ${g.name}. Try again.") { chorus.create("pref.set", null, Subsystems.payload(member.id, g.id)) }
                        }.heightIn(min = 56.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            GroupTile(g, 36.dp)
                            Column(Modifier.weight(1f)) {
                                Text(model.groupPath(g), color = p.ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("$count ${if (count == 1) "member" else "members"}", color = p.ink3, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        } else {
            val candidates = MemberDirectory.candidates(model, group, chorus.device?.accountId)
                .filter { query.isBlank() || fuzzy(query, listOfNotNull(it.member.name, it.member.displayName).joinToString(" ")) != null }
            if (candidates.size > 8 || query.isNotBlank()) ChorusField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Search members") })
            FormCard {
                candidates.forEachIndexed { i, c ->
                    if (i > 0) FormDivider()
                    val m = c.member
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy && !c.loops, role = Role.Checkbox) {
                        write("Could not update who is inside $name. Try again.") {
                            chorus.create(MemberDirectory.membershipOp(c.inside), group.id, JSONObject().put("member_id", m.id))
                        }
                    }.heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Avatar(m.glyph, m.color, 36.dp, avatarBlob = m.avatarBlob, ring = false)
                        Column(Modifier.weight(1f)) {
                            Text(m.shownName, color = if (c.loops) p.ink3 else p.ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            // someone already holding this member can't also be inside them
                            if (c.loops) Text("$name is inside them", color = p.ink3, fontSize = 12.sp)
                        }
                        CheckMark(c.inside, !c.loops)
                    }
                }
            }
            MenuRow("Stop being a subsystem", R.drawable.ic_close, danger = true, enabled = !busy) {
                write("Could not change $name. Try again.") { chorus.create("pref.set", null, Subsystems.payload(member.id, null)) }
            }
        }
        error?.let { FormNote(it, warning = true) }
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
            Glyph(R.drawable.ic_plus, p.accent, 16.dp)
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
