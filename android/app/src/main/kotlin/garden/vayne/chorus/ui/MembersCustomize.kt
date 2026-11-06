package garden.vayne.chorus.ui

import garden.vayne.chorus.designsystem.R
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.InspectionDialogContent
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Member
import garden.vayne.chorus.data.MemberListPrefs
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.designsystem.LocalChorusPalette
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** What each Members row shows and the folders it is sorted into (D-100). */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun MembersCustomizeSheet(chorus: Chorus, model: Model, onError: (String) -> Unit, onNewFolder: () -> Unit, onDismiss: () -> Unit) {
    val p = LocalChorusPalette.current
    val scope = rememberCoroutineScope()
    val settings = model.memberList
    val view = settings.view
    fun send(kind: String, id: String?, payload: JSONObject) = scope.launch {
        try { chorus.create(kind, id, payload) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { onError("Could not save that. Try again.") }
    }
    fun setView(v: garden.vayne.chorus.data.MembersView) = send("pref.set", null, MemberListPrefs.viewPayload(v))
    fun setFolders(f: List<garden.vayne.chorus.data.MemberFolder>) = send("pref.set", null, MemberListPrefs.foldersPayload(f))
    var deleting by remember { mutableStateOf<garden.vayne.chorus.data.MemberFolder?>(null) }

    ChorusSheet(onDismissRequest = onDismiss, containerColor = p.surface, skipPartiallyExpanded = true) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("On each member", color = p.ink3, style = MaterialTheme.typography.labelMedium)
            Text("Role pills", color = p.ink)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("off" to "Off", "first" to "First only", "all" to "All").forEachIndexed { i, (id, label) ->
                    SegmentedButton(view.pills == id, { setView(view.copy(pills = id)) }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(label) }
                }
            }
            if (view.pills != "off") {
                val from = settings.roleField
                if (settings.roleFields.isNotEmpty()) {
                    var open by remember { mutableStateOf(false) }
                    ChoiceField("Roles come from", from?.name ?: "Choose a field") { open = true }
                    ChoiceDrawer(open, "Roles come from", settings.roleFields, from, { it.name },
                        { d -> open = false; setView(view.copy(roleField = d.id)) }, { open = false })
                }
                if (!settings.hasRolesField) TextButton(onClick = {
                    val id = chorus.newId()
                    scope.launch {
                        try {
                            chorus.create("field.define", id, JSONObject().put("name", "Roles").put("type", "multi_select").put("options", JSONObject().put("choices", JSONArray())))
                            chorus.create("pref.set", null, MemberListPrefs.viewPayload(view.copy(roleField = id)))
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { onError("Could not set up roles. Try again.") }
                    }
                }) { Text("Set up a Roles field") }
                Text("Long-press someone and choose Roles… to give them roles.", color = p.ink3, style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("A line of their bio", color = p.ink, modifier = Modifier.weight(1f))
                Switch(view.bio, { setView(view.copy(bio = it)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Their banner behind the row", color = p.ink, modifier = Modifier.weight(1f))
                Switch(view.banners, { setView(view.copy(banners = it)) })
            }
            HorizontalDivider(color = p.line)
            Text("Folders", color = p.ink3, style = MaterialTheme.typography.labelMedium)
            Text("Folders only sort this list; they don't change groups, fronting or who sees what.", color = p.ink3, style = MaterialTheme.typography.bodySmall)
            settings.folders.forEachIndexed { i, f ->
                var name by remember(f.id, f.name) { mutableStateOf(f.name) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ChorusField(name, { name = it }, singleLine = true, modifier = Modifier.weight(1f), label = { Text("Folder") },
                        supportingText = { Text("${model.members.count { !it.archived && settings.folderOf[it.id] == f.id }} members") })
                    if (name.trim() != f.name && name.isNotBlank()) TextButton(onClick = { setFolders(MemberListPrefs.renameFolder(settings.folders, f.id, name)) }) { Text("Rename") }
                    TextButton(enabled = i > 0, onClick = { setFolders(MemberListPrefs.moveFolder(settings.folders, f.id, -1)) }) { Text("↑") }
                    TextButton(enabled = i < settings.folders.size - 1, onClick = { setFolders(MemberListPrefs.moveFolder(settings.folders, f.id, 1)) }) { Text("↓") }
                    IconButton(onClick = { deleting = f }) { ChorusIcon(AppIcon.Close, "Delete ${f.name}") }
                }
            }
            OutlinedButton(onClick = onNewFolder) { Text("New folder") }
        }
    }
    deleting?.let { f ->
        ConfirmDrawer("Delete “${f.name}”?", "The people in it stay; they just move out of the folder.", "Delete folder",
            { setFolders(MemberListPrefs.removeFolder(settings.folders, f.id)); deleting = null }, { deleting = null },
            danger = true, cancel = "Keep", icon = R.drawable.ic_folder)
    }
}

/** A member's roles as pills to toggle, plus a new one (writes right away, as on web). */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun RolesDialog(chorus: Chorus, model: Model, member: Member, onDismiss: () -> Unit) {
    val p = LocalChorusPalette.current
    val scope = rememberCoroutineScope()
    val settings = model.memberList
    val field = settings.roleField
    val chosen = settings.roles(member.id)
    var adding by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    fun send(block: suspend () -> Unit) = scope.launch {
        try { block(); error = null }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = "Could not save that. Try again." }
    }
    fun setValue(value: Any) = send {
        chorus.create("field.set_value", null, JSONObject().put("member_id", member.id).put("field_id", field!!.id).put("value", value))
    }
    fun add() {
        val c = adding.trim()
        adding = ""
        if (c.isEmpty() || field == null) return
        val existing = field.choices.find { it.equals(c, ignoreCase = true) }
        send {
            if (existing == null && field.type != "text") chorus.create("field.set_def", field.id,
                JSONObject().put("options", JSONObject(field.options.toString()).put("choices", JSONArray(field.choices + c))))
            val pick = existing ?: c
            if (pick !in chosen) chorus.create("field.set_value", null, JSONObject().put("member_id", member.id).put("field_id", field.id)
                .put("value", MemberListPrefs.toggled(field, chosen, pick)))
        }
    }
    Drawer(onDismiss, field?.name ?: "Roles", member.shownName,
        leading = { Avatar(member.glyph, member.color, 40.dp, avatarBlob = member.avatarBlob, ring = false) },
        footer = { DrawerButton("Done", onDismiss) }) {
        if (field == null) FormNote("Set up a Roles field in Customize first.", R.drawable.ic_members)
        else {
            SectionLabel(if (field.type == "select") "Pick one" else "Tap to add or remove")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in (field.choices + chosen).distinct()) RolePill(c, on = c in chosen) { setValue(MemberListPrefs.toggled(field, chosen, c)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChorusField(adding, { adding = it }, singleLine = true, label = { Text("New role") }, modifier = Modifier.weight(1f))
                DrawerButton("Add", ::add, ButtonKind.Neutral, enabled = adding.isNotBlank(), modifier = Modifier.width(88.dp))
            }
        }
        error?.let { FormNote(it, warning = true) }
    }
}

/** Subsystems and groups: add, nest and remove (web's "Groups…"). */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun GroupsSheet(chorus: Chorus, model: Model, onDismiss: () -> Unit) {
    val p = LocalChorusPalette.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var subsystem by remember { mutableStateOf(true) }
    var parent by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<garden.vayne.chorus.data.Group?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun send(block: suspend () -> Unit) = scope.launch {
        try { block(); error = null }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = "Could not save that. Try again." }
    }
    Drawer(onDismiss, "Subsystems & groups", "${model.groups.size} so far") {
        if (model.groups.isEmpty()) FormNote("No subsystems or groups yet. A subsystem holds members of its own; a group just gathers members.", R.drawable.ic_layers)
        else FormCard {
            model.groups.forEachIndexed { i, g ->
                if (i > 0) FormDivider()
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(model.groupPath(g), color = p.ink, fontSize = 15.sp)
                        Text("${if (g.isSubsystem) "Subsystem" else "Group"} · ${model.membership[g.id]?.size ?: 0}", color = p.ink3, fontSize = 12.sp)
                    }
                    BarIcon(R.drawable.ic_close, "Remove ${g.name}", p.ink3, 18.dp) { removing = g }
                }
            }
        }
        SectionLabel("Add one")
        ChorusField(name, { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        Segmented(listOf("Subsystem", "Group"), if (subsystem) 0 else 1) { subsystem = it == 0 }
        val subsystems = model.groups.filter { it.isSubsystem }
        if (subsystems.isNotEmpty()) {
            var open by remember { mutableStateOf(false) }
            ChoiceField("Where", parent?.let(model::group)?.let { "Inside ${model.groupPath(it)}" } ?: "Top level") { open = true }
            ChoiceDrawer(open, "Where it goes", listOf<garden.vayne.chorus.data.Group?>(null) + subsystems, parent?.let(model::group),
                { g -> if (g == null) "Top level" else "Inside ${model.groupPath(g)}" }, { g -> parent = g?.id; open = false }, { open = false })
        }
        DrawerButton("Add ${if (subsystem) "subsystem" else "group"}", {
            val payload = JSONObject().put("name", name.trim()).put("kind", if (subsystem) "subsystem" else "group").apply { parent?.let { put("parent_id", it) } }
            send { chorus.create("group.create", chorus.newId(), payload) }
            name = ""
        }, enabled = name.isNotBlank())
        error?.let { FormNote(it, warning = true) }
        removing?.let { g ->
            ConfirmDrawer("Remove ${g.name}?", "Its members stay; they just leave the ${if (g.isSubsystem) "subsystem" else "group"}.", "Remove",
                { send { chorus.create("group.delete", g.id, JSONObject()) }; removing = null }, { removing = null }, danger = true, cancel = "Keep")
        }
    }
}
