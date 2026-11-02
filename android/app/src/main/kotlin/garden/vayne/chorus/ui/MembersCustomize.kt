package garden.vayne.chorus.ui

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
                    Box {
                        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("Roles come from: ${from?.name ?: "choose a field"}") }
                        DropdownMenu(open, { open = false }) {
                            for (d in settings.roleFields) DropdownMenuItem({ Text(d.name, fontWeight = if (d.id == from?.id) FontWeight.SemiBold else null) },
                                { open = false; setView(view.copy(roleField = d.id)) })
                        }
                    }
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
                    OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.weight(1f), label = { Text("Folder") },
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
        AlertDialog(onDismissRequest = { deleting = null }, title = { InspectionDialogContent { Text("Delete “${f.name}”?") } },
            text = { InspectionDialogContent { Text("The people in it stay; they just move out of the folder.") } },
            confirmButton = { InspectionDialogContent { TextButton(onClick = { setFolders(MemberListPrefs.removeFolder(settings.folders, f.id)); deleting = null }) { Text("Delete") } } },
            dismissButton = { InspectionDialogContent { TextButton(onClick = { deleting = null }) { Text("Keep") } } })
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
    AlertDialog(onDismissRequest = onDismiss, title = { InspectionDialogContent { Text("${member.shownName}'s ${field?.name?.lowercase() ?: "roles"}") } },
        text = { InspectionDialogContent { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (field == null) Text("Set up a Roles field in Customize first.", color = p.ink2)
            else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in (field.choices + chosen).distinct()) RolePill(c, on = c in chosen) { setValue(MemberListPrefs.toggled(field, chosen, c)) }
                }
                Text(if (field.type == "select") "One at a time." else "Tap to add or remove.", color = p.ink3, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(adding, { adding = it }, singleLine = true, label = { Text("New role") }, modifier = Modifier.weight(1f))
                    TextButton(enabled = adding.isNotBlank(), onClick = ::add) { Text("Add") }
                }
            }
            error?.let { Text(it, color = p.danger) }
        } } },
        confirmButton = { InspectionDialogContent { TextButton(onClick = onDismiss) { Text("Done") } } })
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
    ChorusSheet(onDismissRequest = onDismiss, containerColor = p.surface, skipPartiallyExpanded = true) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Subsystems & groups", color = p.ink, style = MaterialTheme.typography.titleMedium)
            if (model.groups.isEmpty()) Text("No subsystems or groups yet.", color = p.ink3)
            for (g in model.groups) Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(model.groupPath(g), color = p.ink)
                    Text("${if (g.isSubsystem) "Subsystem" else "Group"} · ${model.membership[g.id]?.size ?: 0}", color = p.ink3, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { removing = g }) { Text("Remove") }
            }
            HorizontalDivider(color = p.line)
            OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(subsystem, { subsystem = true }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Subsystem") }
                SegmentedButton(!subsystem, { subsystem = false }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Group") }
            }
            val subsystems = model.groups.filter { it.isSubsystem }
            if (subsystems.isNotEmpty()) {
                var open by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(parent?.let(model::group)?.let { "Inside ${model.groupPath(it)}" } ?: "Top level")
                    }
                    DropdownMenu(open, { open = false }) {
                        DropdownMenuItem({ Text("Top level") }, { parent = null; open = false })
                        for (g in subsystems) DropdownMenuItem({ Text("Inside ${model.groupPath(g)}") }, { parent = g.id; open = false })
                    }
                }
            }
            Button(enabled = name.isNotBlank(), onClick = {
                val payload = JSONObject().put("name", name.trim()).put("kind", if (subsystem) "subsystem" else "group").apply { parent?.let { put("parent_id", it) } }
                send { chorus.create("group.create", chorus.newId(), payload) }
                name = ""
            }) { Text("Add") }
            error?.let { Text(it, color = p.danger) }
        }
    }
    removing?.let { g ->
        AlertDialog(onDismissRequest = { removing = null }, title = { InspectionDialogContent { Text("Remove ${g.name}?") } },
            text = { InspectionDialogContent { Text("Its members stay; they just leave the ${if (g.isSubsystem) "subsystem" else "group"}.") } },
            confirmButton = { InspectionDialogContent { TextButton(onClick = { send { chorus.create("group.delete", g.id, JSONObject()) }; removing = null }) { Text("Remove") } } },
            dismissButton = { InspectionDialogContent { TextButton(onClick = { removing = null }) { Text("Keep") } } })
    }
}
