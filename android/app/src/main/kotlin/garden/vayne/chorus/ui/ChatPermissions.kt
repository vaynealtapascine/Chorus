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
    val state = model.channelPermissionState
    if (!ChannelPermissions.canManage(state, channel, me, info?.ownerAccountId)) return
    val actions = rememberCoroutineScope()
    var busy by remember(channel.id) { mutableStateOf(false) }
    var error by remember(channel.id) { mutableStateOf<String?>(null) }
    val people = (info?.accounts.orEmpty() + connections).distinctBy { it.id }
    val targets = ChannelPermissions.targets(state, channel, people)
    var selected by remember(channel.id) { mutableStateOf("role|everyone") }
    val target = targets.firstOrNull { "${it.type}|${it.id}" == selected } ?: targets.first()
    val saved = ChannelPermissions.choices(ChannelPermissions.override(state, channel.id, target))
    // Unrelated sync updates never erase unsaved edits. At save, rebase only edited permissions.
    var baseline by remember(channel.id, selected) { mutableStateOf(saved) }
    var draft by remember(channel.id, selected) { mutableStateOf(saved) }
    LaunchedEffect(saved) { if (draft == baseline) { baseline = saved; draft = saved } }
    var targetOpen by remember { mutableStateOf(false) }
    val settings = ChannelPermissions.settings(state, channel.id)
    val savedSeconds = settings.optInt("slow_mode_s", 0).toString()
    var slowBaseline by remember(channel.id) { mutableStateOf(savedSeconds) }
    var slow by remember(channel.id) { mutableStateOf(savedSeconds) }
    LaunchedEffect(savedSeconds) { if (slow == slowBaseline) { slowBaseline = savedSeconds; slow = savedSeconds } }
    val seconds = ChannelPermissions.seconds(slow)
    val latestModel by rememberUpdatedState(model)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider()
        Text("Permissions", style = MaterialTheme.typography.titleMedium)
        Text("A person's setting beats their role's, then Everyone's. Deny wins at the same level. Owners, admins and DM participants always have access.",
            style = MaterialTheme.typography.bodySmall)
        ChoiceField("For", target.label, enabled = !busy) { targetOpen = true }
        ChoiceDrawer(targetOpen, "Whose permissions", targets, target, { it.label },
            { item -> selected = "${item.type}|${item.id}"; targetOpen = false }, { targetOpen = false })
        FormCard {
            ChannelPermissions.labels.entries.forEachIndexed { i, (permission, label) ->
                var open by remember(selected, permission) { mutableStateOf(false) }
                if (i > 0) FormDivider()
                FormValueRow(label, draft[permission]?.name ?: "Inherit", enabled = !busy) { open = true }
                ChoiceDrawer(open, label, ChannelPermissions.Choice.entries, draft[permission] ?: ChannelPermissions.Choice.Inherit, { it.name },
                    { choice -> draft = draft + (permission to choice); open = false }, { open = false }, subtitle = "For ${target.label}")
            }
        }
        if (target.type == "account" && info?.accounts?.none { it.id == target.id } == true &&
            draft["view"] == ChannelPermissions.Choice.Allow) Text("This shares just this channel with them.",
            style = MaterialTheme.typography.bodySmall)
        Text("Private chats still use their chosen members. Permission overrides do not change who may speak as them.",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = !busy && draft.values.any { it != ChannelPermissions.Choice.Inherit }, onClick = {
                draft = ChannelPermissions.labels.keys.associateWith { ChannelPermissions.Choice.Inherit }
            }) { Text("Use inherited settings") }
            Button(enabled = !busy && draft != baseline, onClick = {
                val current = latestModel.channelPermissionState
                if (!ChannelPermissions.canManage(current, channel, me, info?.ownerAccountId)) return@Button
                val patch = ChannelPermissions.patch(ChannelPermissions.override(current, channel.id, target), target, baseline, draft)
                    ?: return@Button
                actions.launch {
                    busy = true; error = null
                    try {
                        chorus.create("channel.set_permission", channel.id, patch, "space:${channel.spaceId}")
                        baseline = draft
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "Could not save permissions." }
                    finally { busy = false }
                }
            }) { Text("Save permissions") }
        }
        HorizontalDivider()
        Text("Slow mode", style = MaterialTheme.typography.titleMedium)
        ChorusField(value = slow, onValueChange = { slow = it }, label = { Text("Seconds between messages") },
            modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy, isError = seconds == null,
            supportingText = { Text(if (seconds == null) "Enter a whole number from 0 to 21600." else "0 turns slow mode off. Managers are exempt.") })
        Text("Messages sent too soon wait on this device until they can send. You can cancel a waiting message.",
            style = MaterialTheme.typography.bodySmall)
        Button(enabled = !busy && seconds != null && slow != slowBaseline, onClick = {
            val current = latestModel.channelPermissionState
            if (!ChannelPermissions.canManage(current, channel, me, info?.ownerAccountId)) return@Button
            val value = ChannelPermissions.seconds(slow) ?: return@Button
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
        }) { Text("Save slow mode") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
