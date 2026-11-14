package garden.vayne.chorus.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Place inside Chat info's Advanced section. No controls appear without current manage access. */
@Composable
internal fun ChatPermissions(chorus: Chorus, model: Model, channel: ChatChannel,
    info: SpaceInfo?, connections: List<SpaceAccount> = emptyList()) {
    val me = chorus.device?.accountId.orEmpty()
    if (!ChannelPermissions.canManage(model.channelPermissionState, channel, me, info?.ownerAccountId)) return
    val actions = rememberCoroutineScope()
    var busy by remember(channel.id) { mutableStateOf(false) }
    var error by remember(channel.id) { mutableStateOf<String?>(null) }
    val state = model.channelPermissionState
    val latestModel by rememberUpdatedState(model)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider()
        Text("Permissions", style = MaterialTheme.typography.titleMedium)
        OverrideEditor(chorus, model, channel.spaceId, channel.id, "channel_permission", "channel.set_permission",
            ChannelPermissions.labels, info, connections) { ChannelPermissions.canManage(it.channelPermissionState, channel, me, info?.ownerAccountId) }
        HorizontalDivider()
        Text("Slow mode", style = MaterialTheme.typography.titleMedium)
        val settings = ChannelPermissions.settings(state, channel.id)
        val savedSeconds = settings.optInt("slow_mode_s", 0).toString()
        var slowBaseline by remember(channel.id) { mutableStateOf(savedSeconds) }
        var slow by remember(channel.id) { mutableStateOf(savedSeconds) }
        LaunchedEffect(savedSeconds) { if (slow == slowBaseline) { slowBaseline = savedSeconds; slow = savedSeconds } }
        val seconds = ChannelPermissions.seconds(slow)
        ChorusField(value = slow, onValueChange = { slow = it }, label = { Text("Seconds between messages") },
            modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy, isError = seconds == null,
            supportingText = if (seconds == null) ({ Text("0 to 21600") }) else null)
        DrawerButton("Save slow mode", enabled = !busy && seconds != null && slow != slowBaseline, onClick = {
            val current = latestModel.channelPermissionState
            if (!ChannelPermissions.canManage(current, channel, me, info?.ownerAccountId)) return@DrawerButton
            val value = ChannelPermissions.seconds(slow) ?: return@DrawerButton
            val patch = ChannelPermissions.slowModePatch(ChannelPermissions.settings(current, channel.id), value)
            actions.launch {
                busy = true; error = null
                try {
                    chorus.create("channel.set", channel.id, patch, "space:${channel.spaceId}")
                    slowBaseline = slow
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "Could not save slow mode." }
                finally { busy = false }
            }
        }, kind = ButtonKind.Neutral)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

/** The space's own overrides (D-133, the permissions form's Exceptions): the same editor over `space_permission`. */
@Composable
internal fun SpaceExceptions(chorus: Chorus, model: Model, space: ChatSpace, info: SpaceInfo?, connections: List<SpaceAccount>) {
    val me = chorus.device?.accountId.orEmpty()
    OverrideEditor(chorus, model, space.id, space.id, "space_permission", "space.set_permission",
        ChannelPermissions.spaceLabels, info, connections) { ChannelPermissions.manages(it.channelPermissionState, space.id, me, info?.ownerAccountId) }
}

/**
 * Who it is for, then each permission as inherit, allow or deny; a save writes only what was edited, on top of
 * the newest lists (names this form doesn't draw, another client's, stay). [entity] is a channel or the space.
 */
@Composable
private fun OverrideEditor(chorus: Chorus, model: Model, spaceId: String, entity: String, table: String, op: String,
    labels: Map<String, String>, info: SpaceInfo?, connections: List<SpaceAccount>, canSave: (Model) -> Boolean) {
    val actions = rememberCoroutineScope()
    var busy by remember(entity) { mutableStateOf(false) }
    var error by remember(entity) { mutableStateOf<String?>(null) }
    val state = model.channelPermissionState
    val people = (info?.accounts.orEmpty() + connections).distinctBy { it.id }
    val targets = ChannelPermissions.targets(state, spaceId, entity, table, people)
    var selected by remember(entity) { mutableStateOf("role|everyone") }
    val target = targets.firstOrNull { "${it.type}|${it.id}" == selected } ?: targets.first()
    val saved = ChannelPermissions.choices(ChannelPermissions.override(state, entity, target, table), labels.keys)
    // Unrelated sync updates never erase unsaved edits. At save, rebase only edited permissions.
    var baseline by remember(entity, selected) { mutableStateOf(saved) }
    var draft by remember(entity, selected) { mutableStateOf(saved) }
    LaunchedEffect(saved) { if (draft == baseline) { baseline = saved; draft = saved } }
    var targetOpen by remember { mutableStateOf(false) }
    val latestModel by rememberUpdatedState(model)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChoiceField("For", target.label, enabled = !busy) { targetOpen = true }
        ChoiceDrawer(targetOpen, "Whose permissions", targets, target, { it.label },
            { item -> selected = "${item.type}|${item.id}"; targetOpen = false }, { targetOpen = false })
        FormCard {
            labels.entries.forEachIndexed { i, (permission, label) ->
                var open by remember(selected, permission) { mutableStateOf(false) }
                if (i > 0) FormDivider()
                FormValueRow(label, draft[permission]?.name ?: "Inherit", enabled = !busy) { open = true }
                ChoiceDrawer(open, label, ChannelPermissions.Choice.entries, draft[permission] ?: ChannelPermissions.Choice.Inherit, { it.name },
                    { choice -> draft = draft + (permission to choice); open = false }, { open = false }, subtitle = "For ${target.label}")
            }
        }
        DrawerButton("Use inherited settings", kind = ButtonKind.Quiet,
            enabled = !busy && draft.values.any { it != ChannelPermissions.Choice.Inherit }, onClick = {
                draft = labels.keys.associateWith { ChannelPermissions.Choice.Inherit }
            })
        DrawerButton("Save permissions", enabled = !busy && draft != baseline, onClick = {
            val current = latestModel
            if (!canSave(current)) return@DrawerButton
            val patch = ChannelPermissions.patch(ChannelPermissions.override(current.channelPermissionState, entity, target, table), target, baseline, draft)
                ?: return@DrawerButton
            actions.launch {
                busy = true; error = null
                try {
                    chorus.create(op, entity, patch, "space:$spaceId")
                    baseline = draft
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "Could not save permissions." }
                finally { busy = false }
            }
        })
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
