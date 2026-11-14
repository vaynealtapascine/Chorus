package garden.vayne.chorus.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Permissions of a shared space (F5.11, D-133, D-160; web's SpacePermissions). Admins set what everyone in the
 * space may do (`space.set_permission`, the `everyone` role; per-person and per-role exceptions open the override
 * editor); every present member sets what its own system shares here (`space.set_sharing`: member names, fronting
 * status and its delay). One Save writes only what changed.
 */
@Composable
internal fun SpacePermissionsDrawer(chorus: Chorus, model: Model, space: ChatSpace, info: SpaceInfo?,
    connections: List<SpaceAccount>, onDismiss: () -> Unit) {
    val me = chorus.device?.accountId.orEmpty()
    val role = ChatManage.roleIn(model.spaceRoles, space.id, info, me)
    val manager = SpacePerms.canSetPermissions(space.kind, role)
    val person = model.isPerson
    // a person has no members or fronting to share (D-093): their own rows are for systems
    val sharing = SpacePerms.canShare(space.kind, role) && !person
    val actions = rememberCoroutineScope()
    val scope = "space:${space.id}"
    // the form edits a copy of what it opened with; Save writes only what changed
    val switches0 = remember(space.id) { SpacePerms.memberSwitches(model.channelPermissionState, space.id) }
    val share0 = remember(space.id) { Sharing.of(model.channelPermissionState, space.id, me) }
    var switches by remember(space.id) { mutableStateOf(switches0) }
    var share by remember(space.id) { mutableStateOf(share0) }
    var delayOpen by remember { mutableStateOf(false) }
    var exceptions by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val everyone = if (manager) SpacePerms.everyonePayload(model.channelPermissionState, space.id, switches0, switches) else null
    val own = if (sharing) Sharing.changes(share0, share) else null
    val changed = everyone != null || own != null
    fun toggle(permission: String, on: Boolean) { switches = switches + (permission to on) }
    @Composable fun SwitchRow(permission: String, label: String, subtitle: String? = null) =
        ToggleRow(label, switches[permission] == true, subtitle, enabled = !busy) { toggle(permission, it) }

    Drawer({ onDismiss() }, if (manager) "Permissions" else "What we share",
        if (manager) "${space.name} · you manage this space" else space.name, dismissible = !busy, footer = {
            DrawerButton(if (changed) "Cancel" else "Close", onDismiss, ButtonKind.Neutral, enabled = !busy)
            DrawerButton(if (manager) "Save permissions" else "Save", {
                // the newest lists, so a sync since the form opened isn't written over
                val latest = chorus.model.value.channelPermissionState
                val permission = if (manager) SpacePerms.everyonePayload(latest, space.id, switches0, switches) else null
                actions.launch {
                    busy = true; error = null
                    try {
                        if (permission != null) chorus.create("space.set_permission", space.id, permission, scope)
                        if (own != null) chorus.create("space.set_sharing", space.id, own, scope)
                        onDismiss()
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "Could not save." }
                    finally { busy = false }
                }
            }, enabled = changed, busy = busy)
        }) {
        if (manager) {
            SectionLabel(if (person) "What others can do" else "What friends’ systems can do")
            FormCard {
                SpacePerms.EVERYONE_ROWS.filter { it.first != "aside" }.forEachIndexed { i, (permission, label) ->
                    if (i > 0) FormDivider()
                    SwitchRow(permission, if (person && permission == "invite") "Invite others" else label)
                }
            }
        }
        if (sharing) {
            SectionLabel("What they can see")
            FormCard {
                ToggleRow("Member names on each message", share.memberNames, enabled = !busy,
                    subtitle = if (share.memberNames) null
                    else "Others see only our system, though anyone who sees our members elsewhere can still match them") {
                    share = share.copy(memberNames = it)
                }
                FormDivider()
                ToggleRow("Our fronting status", share.fronts, enabled = !busy,
                    subtitle = if (share.fronts) "Everyone here sees who is fronting after the delay" else null) { share = share.copy(fronts = it) }
                if (share.fronts) {
                    FormDivider()
                    FormValueRow("Delay", Sharing.delayLabel(share.frontsDelayMin), enabled = !busy) { delayOpen = true }
                    ChoiceDrawer(delayOpen, "Delay", Sharing.delayOptions(share0.frontsDelayMin), share.frontsDelayMin, Sharing::delayLabel,
                        { share = share.copy(frontsDelayMin = it); delayOpen = false }, { delayOpen = false })
                }
                if (manager) {
                    FormDivider()
                    SwitchRow(SpacePerms.HISTORY, "History before they joined", if (switches[SpacePerms.HISTORY] == true) "Everything" else "From when they join")
                }
            }
        }
        if (manager) {
            SectionLabel("Inside ${space.name}")
            FormCard {
                if (!person) {
                    SwitchRow("aside", "Allow system asides")
                    FormDivider()
                }
                if (!sharing) {
                    SwitchRow(SpacePerms.HISTORY, "History before they joined", if (switches[SpacePerms.HISTORY] == true) "Everything" else "From when they join")
                    FormDivider()
                }
                FormValueRow("Exceptions", SpacePerms.exceptions(model.channelPermissionState, space.id).takeIf { it > 0 }?.toString().orEmpty(),
                    enabled = !busy) { exceptions = true }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) }
        // inside this drawer's content, so it stacks over it
        if (exceptions) Drawer({ exceptions = false }, "Exceptions", space.name) {
            SpaceExceptions(chorus, model, space, info, connections)
        }
    }
}
