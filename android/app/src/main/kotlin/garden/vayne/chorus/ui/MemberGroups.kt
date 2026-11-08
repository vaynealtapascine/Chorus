package garden.vayne.chorus.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.BucketControls
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.CustomState
import garden.vayne.chorus.data.Group
import garden.vayne.chorus.data.Member
import garden.vayne.chorus.data.MemberDirectory
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import kotlinx.coroutines.launch
import org.json.JSONObject

/** A group's tile: its icon (people for a plain group, layers for a subsystem) in its colour (F4.4). */
@Composable
internal fun GroupTile(g: Group, size: Dp) =
    IconTile(g.icon?.let(::frontIconRes)?.takeIf { it != R.drawable.ic_layers } ?: if (g.isSubsystem) R.drawable.ic_layers else R.drawable.ic_users,
        g.color ?: "#42A397", size)

/** F4.4's dashed "+ New group" row. */
@Composable
internal fun DashedAddRow(label: String, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(16.dp))
        .drawBehind {
            drawRoundRect(p.line, cornerRadius = CornerRadius(16.dp.toPx()),
                style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
        }.clickable(role = Role.Button, onClick = onClick), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center) {
        Glyph(R.drawable.ic_plus, p.accent, 18.dp)
        Spacer(Modifier.width(8.dp))
        Text(label, color = p.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun Chorus.send(scope: kotlinx.coroutines.CoroutineScope, onError: (String?) -> Unit, failure: String, block: suspend Chorus.() -> Unit) =
    scope.launch {
        try { block(); onError(null) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { onError(e.message ?: failure) }
    }

/** F4.4: every group as a card that opens on its members; any group can be filled and emptied here. */
@Composable
internal fun GroupsTab(chorus: Chorus, model: Model, query: String, creating: Boolean, onCreating: (Boolean) -> Unit, onOpen: (String) -> Unit) {
    val p = LocalChorusPalette.current
    val scope = rememberCoroutineScope()
    var open by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var adding by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val q = query.trim().lowercase()
    val shown = model.groups.filter { q.isEmpty() || model.groupPath(it).lowercase().contains(q) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        error?.let { item(key = "error") { Text(it, color = p.danger, fontSize = 13.sp) } }
        if (shown.isEmpty()) item(key = "empty") { EmptyState(R.drawable.ic_users, if (q.isEmpty()) "No groups yet" else "No matches") }
        items(shown, key = { it.id }) { g ->
            GroupCard(chorus, model, g, g.id in open, onToggle = { open = if (g.id in open) open - g.id else open + g.id },
                onOpen = onOpen, onAdd = { adding = g.id }, onEdit = { editing = g.id },
                onRemove = { m -> chorus.send(scope, { error = it }, "Could not take ${m.shownName} out. Try again.") {
                    create("group.remove_member", g.id, JSONObject().put("member_id", m.id)) } })
        }
        if (q.isEmpty()) item(key = "new") { DashedAddRow("New group") { onCreating(true) } }
    }
    if (creating) NewGroupDrawer(chorus, model, onDismiss = { onCreating(false) }) { id -> onCreating(false); open = open + id; adding = id }
    adding?.let { id -> model.group(id)?.let { GroupMembersDrawer(chorus, model, it) { adding = null } } ?: run { adding = null } }
    editing?.let { id -> model.group(id)?.let { EditGroupDrawer(chorus, model, it) { editing = null } } ?: run { editing = null } }
}

@Composable
private fun GroupCard(chorus: Chorus, model: Model, g: Group, open: Boolean, onToggle: () -> Unit, onOpen: (String) -> Unit,
    onAdd: () -> Unit, onEdit: () -> Unit, onRemove: (Member) -> Unit) {
    val p = LocalChorusPalette.current
    val inside = MemberDirectory.members(model, g.id)
    val settings = model.memberList
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface).animateContentSize()) {
        Row(Modifier.fillMaxWidth().clickable(onClickLabel = if (open) "Fold up ${g.name}" else "Open ${g.name}", onClick = onToggle)
            .padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GroupTile(g, 44.dp)
            Column(Modifier.weight(1f)) {
                Text(model.groupPath(g), color = p.ink, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(MemberDirectory.line(model, g), color = p.ink3, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Glyph(R.drawable.ic_chevron_down, p.ink2, 20.dp, modifier = Modifier.rotate(if (open) 180f else 0f))
        }
        if (open) {
            for (m in inside) Row(Modifier.fillMaxWidth().clickable { onOpen(m.id) }.padding(start = 30.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(m.glyph, m.color, 32.dp, avatarBlob = m.avatarBlob, ring = false)
                Column(Modifier.weight(1f)) {
                    Text(m.shownName, color = p.ink, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    (settings.roles(m.id).firstOrNull() ?: m.pronouns)?.let {
                        Text(it, color = p.ink3, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                BarIcon(R.drawable.ic_close, "Take ${m.shownName} out of ${g.name}", p.ink3, 18.dp) { onRemove(m) }
            }
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onAdd).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Glyph(R.drawable.ic_user_plus, p.accent, 18.dp)
                    Text(if (inside.isEmpty()) "Add members" else "Add or remove", color = p.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.weight(1f))
                BarIcon(R.drawable.ic_pencil, "Edit ${g.name}", p.ink2, 18.dp, onClick = onEdit)
            }
        }
    }
}

/** Who is in [group]: tap to put someone in or take them out; each tap is one op, so it works offline. */
@Composable
internal fun GroupMembersDrawer(chorus: Chorus, model: Model, group: Group, onDismiss: () -> Unit) {
    val p = LocalChorusPalette.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable(group.id) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val all = MemberDirectory.candidates(model, group, chorus.device?.accountId)
    val shown = if (query.isBlank()) all else all.filter { fuzzy(query, listOfNotNull(it.member.name, it.member.displayName).joinToString(" ")) != null }
    Drawer(onDismiss, model.groupPath(group), MemberDirectory.line(model, group), leading = { GroupTile(group, 40.dp) },
        footer = { DrawerButton("Done", onDismiss) }) {
        if (all.size > 8) ChorusField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Search members") })
        FormCard {
            shown.forEachIndexed { i, c ->
                if (i > 0) FormDivider()
                PickRow(c.member, c.inside, enabled = !c.loops) {
                    chorus.send(scope, { error = it }, "Could not change ${group.name}. Try again.") {
                        create(MemberDirectory.membershipOp(c.inside), group.id, JSONObject().put("member_id", c.member.id))
                    }
                }
            }
        }
        error?.let { FormNote(it, warning = true) }
    }
}

/** The groups [member] is in (F4.2 "Groups" and the row's held menu). */
@Composable
internal fun MemberGroupsDrawer(chorus: Chorus, model: Model, member: Member, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    val account = chorus.device?.accountId
    Drawer(onDismiss, "Groups", member.shownName, leading = { Avatar(member.glyph, member.color, 40.dp, avatarBlob = member.avatarBlob, ring = false) },
        footer = { DrawerButton("Done", onDismiss) }) {
        if (model.groups.isNotEmpty()) FormCard {
            model.groups.forEachIndexed { i, g ->
                if (i > 0) FormDivider()
                val c = MemberDirectory.candidates(model, g, account).find { it.member.id == member.id }
                val inside = member.id in model.membership[g.id].orEmpty()
                GroupPickRow(model, g, inside, enabled = c != null && !c.loops) {
                    chorus.send(scope, { error = it }, "Could not change ${g.name}. Try again.") {
                        create(MemberDirectory.membershipOp(inside), g.id, JSONObject().put("member_id", member.id))
                    }
                }
            }
        }
        DashedAddRow("New group") { creating = true }
        error?.let { FormNote(it, warning = true) }
    }
    if (creating) NewGroupDrawer(chorus, model, onDismiss = { creating = false }) { id ->
        creating = false
        chorus.send(scope, { error = it }, "Could not add ${member.shownName}. Try again.") {
            create("group.add_member", id, JSONObject().put("member_id", member.id))
        }
    }
}

@Composable
private fun PickRow(m: Member, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = enabled, role = Role.Checkbox, onClick = onClick)
        .semantics { contentDescription = "${m.shownName}${if (on) ", in" else ""}" }
        .padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Avatar(m.glyph, m.color, 36.dp, avatarBlob = m.avatarBlob, ring = false)
        Text(m.shownName, color = if (enabled) p.ink else p.ink3, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        CheckMark(on, enabled)
    }
}

@Composable
private fun GroupPickRow(model: Model, g: Group, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = enabled, role = Role.Checkbox, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        GroupTile(g, 36.dp)
        Column(Modifier.weight(1f)) {
            Text(model.groupPath(g), color = if (enabled) p.ink else p.ink3, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(MemberDirectory.line(model, g), color = p.ink3, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        CheckMark(on, enabled)
    }
}

/** A name, group or subsystem, and where a subsystem sits; [onCreated] gets the new group's id. */
@Composable
internal fun NewGroupDrawer(chorus: Chorus, model: Model, onDismiss: () -> Unit, onCreated: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var subsystem by rememberSaveable { mutableStateOf(false) }
    var parent by rememberSaveable { mutableStateOf<String?>(null) }
    var color by rememberSaveable { mutableStateOf(BucketControls.swatches.first()) }
    var error by remember { mutableStateOf<String?>(null) }
    var choosingParent by remember { mutableStateOf(false) }
    Drawer(onDismiss, "New group", footer = {
        DrawerButton("Add ${if (subsystem) "subsystem" else "group"}", enabled = name.isNotBlank(), onClick = {
            val id = chorus.newId()
            val payload = JSONObject().put("name", name.trim()).put("kind", if (subsystem) "subsystem" else "group").put("color", color)
                .apply { if (subsystem) parent?.let { put("parent_id", it) } }
            chorus.send(scope, { error = it }, "Could not add the group. Try again.") { create("group.create", id, payload); onCreated(id) }
        })
    }) {
        ChorusField(name, { name = it.take(60) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Name") })
        Swatches(color) { color = it }
        Segmented(listOf("Group", "Subsystem"), if (subsystem) 1 else 0) { subsystem = it == 1 }
        val subsystems = model.groups.filter { it.isSubsystem }
        if (subsystem && subsystems.isNotEmpty()) {
            ChoiceField("Where", parent?.let(model::group)?.let { "Inside ${model.groupPath(it)}" } ?: "Top level") { choosingParent = true }
            ChoiceDrawer(choosingParent, "Where it goes", listOf<Group?>(null) + subsystems, parent?.let(model::group),
                { g -> if (g == null) "Top level" else "Inside ${model.groupPath(g)}" }, { g -> parent = g?.id; choosingParent = false }, { choosingParent = false })
        }
        error?.let { FormNote(it, warning = true) }
    }
}

/** Rename, recolour or remove a group (its members stay). */
@Composable
private fun EditGroupDrawer(chorus: Chorus, model: Model, g: Group, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by rememberSaveable(g.id) { mutableStateOf(g.name) }
    var color by rememberSaveable(g.id) { mutableStateOf(g.color ?: BucketControls.swatches.first()) }
    var removing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Drawer(onDismiss, g.name, MemberDirectory.line(model, g), leading = { GroupTile(g.copy(color = color), 40.dp) }, footer = {
        DrawerButton("Save", enabled = name.isNotBlank() && (name.trim() != g.name || color != g.color), onClick = {
            val payload = JSONObject().apply { if (name.trim() != g.name) put("name", name.trim()); if (color != g.color) put("color", color) }
            chorus.send(scope, { error = it }, "Could not save ${g.name}. Try again.") { create("group.set", g.id, payload); onDismiss() }
        })
    }) {
        ChorusField(name, { name = it.take(60) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Name") })
        Swatches(color) { color = it }
        MenuRow("Remove ${g.name}", R.drawable.ic_delete, danger = true) { removing = true }
        error?.let { FormNote(it, warning = true) }
    }
    if (removing) ConfirmDrawer("Remove ${g.name}?", "Its members stay.", "Remove", {
        removing = false
        chorus.send(scope, { error = it }, "Could not remove ${g.name}. Try again.") { create("group.delete", g.id, JSONObject()); onDismiss() }
    }, { removing = false }, danger = true, cancel = "Keep")
}

/** A row of colour circles (F4.2's swatches, F7.10's bucket colours). */
@Composable
internal fun Swatches(current: String?, colors: List<String> = BucketControls.swatches.take(8), size: Dp = 32.dp, onPick: (String) -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (c in colors) {
            val on = current.equals(c, ignoreCase = true)
            Box(Modifier.size(size).clip(CircleShape).background(tonesOf(c).ring).border(2.dp, if (on) p.ink else p.bg, CircleShape)
                .clickable(role = Role.RadioButton) { onPick(c) }.semantics { contentDescription = "Colour $c${if (on) ", chosen" else ""}" })
        }
    }
}

/** Custom fronts (F4.1's third tab): the states that can front instead of a member, e.g. "Blurry". */
@Composable
internal fun CustomFrontsTab(chorus: Chorus, model: Model, query: String, creating: Boolean, onCreating: (Boolean) -> Unit) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val q = query.trim().lowercase()
    val shown = model.states.filter { q.isEmpty() || it.name.lowercase().contains(q) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        if (shown.isEmpty()) item(key = "empty") { EmptyState(R.drawable.ic_layers, if (q.isEmpty()) "None yet" else "No matches") }
        items(shown, key = { it.id }) { st ->
            TileRow({ StateTile(st, 40.dp) }, st.name, st.description?.takeIf { it.isNotBlank() }, null) { editing = st.id }
        }
        if (q.isEmpty()) item(key = "new") { Box(Modifier.padding(16.dp)) { DashedAddRow("New custom front") { onCreating(true) } } }
    }
    if (creating) StateDrawer(chorus, null, onDismiss = { onCreating(false) })
    editing?.let { id -> model.states.find { it.id == id }?.let { StateDrawer(chorus, it) { editing = null } } ?: run { editing = null } }
}

@Composable
private fun StateTile(st: CustomState, size: Dp) {
    val p = LocalChorusPalette.current
    val tones = st.color?.takeIf { parseHex(it) != null }?.let { tonesOf(it) }
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(tones?.tint ?: p.surface2), contentAlignment = Alignment.Center) {
        val icon = st.icon?.takeIf { it.isNotBlank() && it != "?" }
        if (icon != null) Text(icon, color = tones?.name ?: p.ink2, fontSize = 18.sp)
        else Glyph(R.drawable.ic_layers, tones?.name ?: p.ink2, size * .5f)
    }
}

/** Make or change a custom front: a name, a line about it and a colour; removing one keeps its history. */
@Composable
private fun StateDrawer(chorus: Chorus, st: CustomState?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by rememberSaveable(st?.id) { mutableStateOf(st?.name.orEmpty()) }
    var about by rememberSaveable(st?.id) { mutableStateOf(st?.description.orEmpty()) }
    var color by rememberSaveable(st?.id) { mutableStateOf(st?.color) }
    var removing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Drawer(onDismiss, st?.name ?: "New custom front", footer = {
        DrawerButton(if (st == null) "Add" else "Save", enabled = name.isNotBlank(), onClick = {
            val payload = JSONObject()
            if (st == null || name.trim() != st.name) payload.put("name", name.trim())
            if (about.trim() != st?.description.orEmpty()) payload.put("description", about.trim().ifEmpty { JSONObject.NULL })
            if (color != st?.color) color?.let { payload.put("color", it) }
            if (st == null) payload.put("icon", "?")
            chorus.send(scope, { error = it }, "Could not save that. Try again.") {
                if (st == null) create("state.create", newId(), payload) else if (payload.length() > 0) create("state.set", st.id, payload)
                onDismiss()
            }
        })
    }) {
        ChorusField(name, { name = it.take(40) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Name") })
        ChorusField(about, { about = it.take(200) }, Modifier.fillMaxWidth(), label = { Text("Description") })
        Swatches(color) { color = it }
        if (st != null) MenuRow("Remove ${st.name}", R.drawable.ic_delete, danger = true) { removing = true }
        error?.let { FormNote(it, warning = true) }
    }
    if (removing && st != null) ConfirmDrawer("Remove ${st.name}?", "Its history stays.", "Remove", {
        removing = false
        chorus.send(scope, { error = it }, "Could not remove ${st.name}. Try again.") { create("state.delete", st.id, JSONObject()); onDismiss() }
    }, { removing = false }, danger = true, cancel = "Keep")
}
