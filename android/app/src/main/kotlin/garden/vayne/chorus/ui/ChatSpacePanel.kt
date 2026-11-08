package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.*
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The space part of chat info (D-098, D-099; web's SpacePanel): a group's description and people
 * with their roles, Add / Remove, Leave or Delete group; in a DM, the private chats kept to one of
 * our members and a way to start one.
 */
@Composable
internal fun ChatSpacePanel(chorus: Chorus, model: Model, space: ChatSpace, channel: ChatChannel, directory: SpaceInfo?,
    connections: List<SpaceAccount>, foreign: Map<String, ForeignAuthor>, speakerId: String?,
    onEditGroup: (() -> Unit)?, onChanged: () -> Unit, onOpenChannel: (String) -> Unit, onLeft: () -> Unit) {
    val p = LocalChorusPalette.current
    val actions = rememberCoroutineScope()
    val me = chorus.device?.accountId.orEmpty()
    val role = ChatManage.roleIn(model.spaceRoles, space.id, directory, me)
    val editable = ChatManage.canEditSpace(space.kind, role)
    val owner = ChatManage.canInvite(space.kind, role)
    val own = remember(model.members) { model.members.mapTo(HashSet()) { it.id } }
    var error by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<SpaceAccount?>(null) }
    var leaving by remember { mutableStateOf(false) }
    val scope = "space:${space.id}"
    fun nameOf(id: String) = model.member(id)?.shownName ?: foreign[id]?.name ?: "Someone"

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (space.kind == "shared") {
            space.description?.takeIf { it.isNotBlank() }?.let { Text(it, color = p.ink2, fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp)) }
            if (editable && onEditGroup != null) PanelLink(R.drawable.ic_edit, "Edit group", onEditGroup)
            Text("${directory?.accounts?.size ?: 0} ${if (directory?.accounts?.size == 1) "person" else "people"}",
                color = p.accent, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            if (owner) PanelLink(R.drawable.ic_plus, "Add people") { adding = true }
            if (directory == null) Text("Offline", color = p.ink3, fontSize = 13.sp)
            for (account in directory?.accounts.orEmpty()) {
                val theirs = if (account.id == directory?.ownerAccountId) "owner" else model.spaceRoles["${space.id}|${account.id}"] ?: "member"
                var roleMenu by remember(account.id) { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(36.dp).background(p.surface2, CircleShape), contentAlignment = Alignment.Center) {
                        Text(account.shownName.removePrefix("@").take(1).uppercase(), color = p.ink2)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(account.shownName + if (account.id == me) " (you)" else "", color = p.ink, fontSize = 15.sp)
                        Text(if (account.isSystem) "system" else "person", color = p.ink3, fontSize = 12.sp)
                    }
                    Box {
                        val changeable = editable && theirs != "owner" && account.id != me
                        Text(ChatManage.roleLabel(theirs) + if (changeable) " ▾" else "", color = if (changeable) p.accent else p.ink3, fontSize = 13.sp,
                            modifier = Modifier.then(if (changeable) Modifier.clickable { roleMenu = true } else Modifier).padding(8.dp))
                        MenuDrawer(roleMenu, { roleMenu = false }, "${account.shownName}'s role", space.name) {
                            for ((id, label) in ChatManage.ROLES) MenuRow(label, selected = id == theirs) {
                                roleMenu = false
                                actions.launch {
                                    try { chorus.create("space.set_role", space.id, JSONObject().put("account_id", account.id).put("role", id), scope = scope) }
                                    catch (e: Exception) { error = e.message ?: "Could not change the role." }
                                }
                            }
                        }
                    }
                    if (owner && account.id != me) ChatIconButton(R.drawable.ic_close, "Remove ${account.shownName}") { removing = account }
                }
            }
        } else if (space.kind == "dm") {
            val sides = ChatManage.sides(channel, space, own)
            if (sides != null) Row(Modifier.fillMaxWidth().background(p.surface2, RoundedCornerShape(12.dp)).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChatIcon(R.drawable.ic_lock, tint = p.ink2)
                Text("Private · " + (sides.mine.ifEmpty { sides.theirs }).joinToString(" & ", transform = ::nameOf), color = p.ink2, fontSize = 13.sp)
            }
            val shown = remember(model) { ChatOrganize.shown(model) }
            val privates = model.channels.filter { it.spaceId == space.id && it.kind == "member_dm" && shown(it.id) }
            if (privates.isNotEmpty()) Text("Private chats", color = p.accent, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            for (c in privates) {
                val s = ChatManage.sides(c, space, own)
                PanelLink(R.drawable.ic_lock, if (s?.mine?.isNotEmpty() == true) "As ${s.mine.joinToString(" & ", transform = ::nameOf)}"
                    else "With ${s?.theirs?.joinToString(" & ", transform = ::nameOf) ?: c.name}") { onOpenChannel(c.id) }
            }
            val writers = ChatCompose.members(model, me)
            if (!model.isPerson && sides == null && writers.isNotEmpty()) {
                var choosing by remember { mutableStateOf(false) }
                PanelLink(R.drawable.ic_lock, "Start a private chat as…") { choosing = true }
                MenuDrawer(choosing, { choosing = false }, "Start a private chat") {
                    for (m in writers.sortedByDescending { it.id == speakerId }) MenuRow(m.shownName, subtitle = m.pronouns?.takeIf { it.isNotBlank() },
                        leading = { Avatar(m.glyph, m.color, 36.dp, avatarBlob = m.avatarBlob, ring = false) }) {
                        choosing = false
                        val found = ChatManage.existingPrivate(model.channels, space.id, m.id, own)
                        if (found != null) onOpenChannel(found.id) else actions.launch {
                            try {
                                val id = chorus.newId()
                                chorus.create("channel.create", id, ChatManage.privatePayload(space.id, m), scope = scope)
                                onOpenChannel(id)
                            } catch (e: Exception) { error = e.message ?: "Could not start the private chat." }
                        }
                    }
                }
            }
        }
        if (error != null) Text(error.orEmpty(), color = p.danger, fontSize = 13.sp)
        if (space.kind != "internal" && directory != null) {
            HorizontalDivider(color = p.line, modifier = Modifier.padding(vertical = 8.dp))
            val deletes = space.kind == "shared" && role == "owner"
            TextButton(onClick = { leaving = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (deletes) "Delete group" else if (space.kind == "dm") "Leave conversation" else "Leave group", color = p.danger)
            }
        }
    }

    if (adding) {
        val inside = directory?.accounts.orEmpty().mapTo(HashSet()) { it.id }
        var chosen by remember { mutableStateOf(listOf<String>()) }
        var busy by remember { mutableStateOf(false) }
        val candidates = connections.filter { it.id !in inside }
        Drawer({ adding = false }, "Add people", space.name, dismissible = !busy, footer = {
            DrawerButton(if (chosen.size > 1) "Add ${chosen.size}" else "Add", {
                val dev = chorus.device
                if (dev != null) {
                    busy = true
                    actions.launch {
                        try { Spaces.add(dev, space.id, chosen); adding = false; onChanged() }
                        catch (e: Exception) { error = e.message ?: "Could not add them. Try again when connected." }
                        finally { busy = false }
                    }
                }
            }, enabled = chosen.isNotEmpty(), busy = busy)
        }) {
            if (candidates.isEmpty()) EmptyState(R.drawable.ic_friends, "No one to add")
            else FormCard {
                candidates.forEachIndexed { i, a ->
                    if (i > 0) FormDivider()
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .toggleable(a.id in chosen, role = Role.Checkbox) { on -> chosen = if (on) chosen + a.id else chosen - a.id }
                        .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(36.dp).background(p.surface3, CircleShape), contentAlignment = Alignment.Center) {
                            Text(a.shownName.removePrefix("@").take(1).uppercase(), color = p.ink2)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(a.shownName, color = p.ink, fontSize = 15.sp)
                            Text(if (a.isSystem) "a system" else "one person", color = p.ink3, fontSize = 12.sp)
                        }
                        Checkbox(a.id in chosen, onCheckedChange = null)
                    }
                }
            }
        }
    }
    removing?.let { account ->
        ConfirmDrawer("Remove ${account.shownName}?", null, "Remove", {
            removing = null
            actions.launch {
                try { chorus.create("space.leave", space.id, JSONObject().put("account_id", account.id), scope = scope); onChanged() }
                catch (e: Exception) { error = e.message ?: "Could not remove them." }
            }
        }, { removing = null }, danger = true, icon = R.drawable.ic_close)
    }
    if (leaving) {
        val deletes = space.kind == "shared" && role == "owner"
        ConfirmDrawer(if (deletes) "Delete ${space.name}?" else if (space.kind == "dm") "Leave this conversation?" else "Leave ${space.name}?",
            if (deletes) null else "You can be added back later.",
            if (deletes) "Delete" else "Leave", {
                leaving = false
                val dev = chorus.device
                if (dev != null) actions.launch {
                    try {
                        if (deletes) chorus.create("space.delete", space.id, JSONObject(), scope = scope) else Spaces.leave(dev, space.id)
                        onLeft()
                    } catch (e: Exception) { error = e.message ?: "Could not do that. Try again when connected." }
                }
            }, { leaving = false }, danger = true, icon = if (deletes) null else R.drawable.ic_back)
    }
}

@Composable
private fun PanelLink(icon: Int, label: String, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ChatIcon(icon, tint = p.accent)
        Text(label, color = p.accent, fontSize = 15.sp)
    }
}
