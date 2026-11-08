package garden.vayne.chorus.ui

import garden.vayne.chorus.designsystem.R
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
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

/**
 * How the Members list looks (D-100), behind F4.1's sliders icon: folders or one list, role pills,
 * a bio line, banners, the folders themselves, and the list's less used actions.
 */
@Composable
internal fun MembersViewDrawer(chorus: Chorus, model: Model, onError: (String) -> Unit, onNewFolder: () -> Unit,
    onSelect: (() -> Unit)?, onReorder: (() -> Unit)?, onImport: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val settings = model.memberList
    val view = settings.view
    fun send(kind: String, id: String?, payload: JSONObject) = scope.launch {
        try { chorus.create(kind, id, payload) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { onError("Could not save that. Try again.") }
    }
    fun setView(v: garden.vayne.chorus.data.MembersView) = send("pref.set", null, MemberListPrefs.viewPayload(v))
    var choosingPills by remember { mutableStateOf(false) }
    var choosingField by remember { mutableStateOf(false) }
    var folders by remember { mutableStateOf(false) }
    val pillLabels = listOf("off" to "Off", "first" to "First only", "all" to "All")
    Drawer(onDismiss, "View") {
        Segmented(listOf("Folders", "List"), if (view.flat) 1 else 0) { setView(view.copy(flat = it == 1)) }
        FormCard {
            FormValueRow("Roles", pillLabels.first { it.first == view.pills }.second, R.drawable.ic_members) { choosingPills = true }
            if (view.pills != "off" && settings.roleFields.isNotEmpty()) {
                FormDivider()
                FormValueRow("Roles come from", settings.roleField?.name ?: "Choose", R.drawable.ic_format_list_ul) { choosingField = true }
            }
            FormDivider()
            ToggleRow("Bio line", view.bio) { setView(view.copy(bio = it)) }
            FormDivider()
            ToggleRow("Banners", view.banners) { setView(view.copy(banners = it)) }
        }
        if (view.pills != "off" && !settings.hasRolesField) DrawerButton("Set up Roles", {
            val id = chorus.newId()
            scope.launch {
                try {
                    chorus.create("field.define", id, JSONObject().put("name", "Roles").put("type", "multi_select").put("options", JSONObject().put("choices", JSONArray())))
                    chorus.create("pref.set", null, MemberListPrefs.viewPayload(view.copy(roleField = id)))
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { onError("Could not set up roles. Try again.") }
            }
        }, ButtonKind.Neutral)
        Column {
            if (!view.flat) MenuRow("Folders", R.drawable.ic_folder, subtitle = "${settings.folders.size}") { folders = true }
            onSelect?.let { MenuRow("Select several", R.drawable.ic_check, onClick = it) }
            onReorder?.let { MenuRow("Reorder", R.drawable.ic_grip, onClick = it) }
            MenuRow("Import from PluralKit", R.drawable.ic_forward, onClick = onImport)
        }
    }
    ChoiceDrawer(choosingPills, "Roles", pillLabels, pillLabels.first { it.first == view.pills }, { it.second },
        { choosingPills = false; setView(view.copy(pills = it.first)) }, { choosingPills = false })
    ChoiceDrawer(choosingField, "Roles come from", settings.roleFields, settings.roleField, { it.name },
        { d -> choosingField = false; setView(view.copy(roleField = d.id)) }, { choosingField = false })
    if (folders) FoldersDrawer(model, onNewFolder = { folders = false; onNewFolder() },
        onSave = { send("pref.set", null, MemberListPrefs.foldersPayload(it)) }) { folders = false }
}

/** The folders the list is sorted into: rename, move up or down, remove (the people stay). */
@Composable
private fun FoldersDrawer(model: Model, onNewFolder: () -> Unit, onSave: (List<garden.vayne.chorus.data.MemberFolder>) -> Unit, onDismiss: () -> Unit) {
    val p = LocalChorusPalette.current
    val settings = model.memberList
    var deleting by remember { mutableStateOf<garden.vayne.chorus.data.MemberFolder?>(null) }
    Drawer(onDismiss, "Folders", footer = { DrawerButton("Done", onDismiss) }) {
        if (settings.folders.isNotEmpty()) FormCard {
            settings.folders.forEachIndexed { i, f ->
                if (i > 0) FormDivider()
                var name by remember(f.id, f.name) { mutableStateOf(f.name) }
                Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    ChorusField(name, { name = it }, singleLine = true, modifier = Modifier.weight(1f))
                    if (name.trim() != f.name && name.isNotBlank()) BarIcon(R.drawable.ic_check, "Rename ${f.name}", p.accent, 18.dp) {
                        onSave(MemberListPrefs.renameFolder(settings.folders, f.id, name)) }
                    else Text("${model.members.count { !it.archived && settings.folderOf[it.id] == f.id }}", color = p.ink3, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 8.dp))
                    if (i > 0) Box(Modifier.rotate(180f)) { BarIcon(R.drawable.ic_down, "Move ${f.name} up", p.ink2, 18.dp) {
                        onSave(MemberListPrefs.moveFolder(settings.folders, f.id, -1)) } }
                    if (i < settings.folders.size - 1) BarIcon(R.drawable.ic_down, "Move ${f.name} down", p.ink2, 18.dp) {
                        onSave(MemberListPrefs.moveFolder(settings.folders, f.id, 1)) }
                    BarIcon(R.drawable.ic_close, "Delete ${f.name}", p.ink3, 18.dp) { deleting = f }
                }
            }
        }
        DashedAddRow("New folder", onNewFolder)
    }
    deleting?.let { f ->
        ConfirmDrawer("Delete “${f.name}”?", "The people in it stay.", "Delete folder",
            { onSave(MemberListPrefs.removeFolder(settings.folders, f.id)); deleting = null }, { deleting = null },
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
        if (field == null) DrawerButton("Set up Roles", {
            send {
                val id = chorus.newId()
                chorus.create("field.define", id, JSONObject().put("name", "Roles").put("type", "multi_select").put("options", JSONObject().put("choices", JSONArray())))
                chorus.create("pref.set", null, MemberListPrefs.viewPayload(settings.view.copy(roleField = id)))
            }
        })
        else {
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
