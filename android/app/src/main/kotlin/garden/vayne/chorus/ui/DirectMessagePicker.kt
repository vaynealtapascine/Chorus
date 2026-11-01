package garden.vayne.chorus.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.data.Member
import garden.vayne.chorus.data.SpaceAccount
import garden.vayne.chorus.designsystem.LocalChorusPalette

@Composable
fun ColumnScope.DirectMessagePicker(
    members: List<Member>, accounts: List<SpaceAccount>, busy: Boolean, online: Boolean, error: String?,
    onMembers: (Member, List<Member>) -> Unit, onAccount: (Member, SpaceAccount, Boolean) -> Unit,
    /** Offer "Private to <sender>" for a DM with an account (D-098); not for a singlet. */
    privateChats: Boolean = true,
) {
    val p = LocalChorusPalette.current
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var query by rememberSaveable { mutableStateOf("") }
    var senderId by rememberSaveable { mutableStateOf<String?>(null) }
    var choosingSender by rememberSaveable { mutableStateOf(false) }
    var recipients by rememberSaveable { mutableStateOf(listOf<String>()) }
    var keepPrivate by rememberSaveable { mutableStateOf(false) }
    val sender = members.find { it.id == senderId }
    val pickingSender = choosingSender || sender == null
    val shown = remember(members, query) { members.filter { m ->
        listOf(m.shownName, m.name, *m.sigils.toTypedArray()).any { it.contains(query.trim(), true) }
    } }
    val connected = accounts.filter { listOfNotNull(it.shownName, it.handle).any { name -> name.contains(query.trim(), true) } }
    if (!keyboardOpen) Text("Choose who is speaking, then one or more recipients.", style = MaterialTheme.typography.bodySmall, color = p.ink3)
    TextButton(enabled = !busy, onClick = { choosingSender = !choosingSender; query = "" }) {
        Text(if (choosingSender) "Choose sender below" else "From: ${sender?.shownName ?: "Choose a member"}",
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(if (pickingSender) "Search senders" else "Search recipients") }, modifier = Modifier.fillMaxWidth())
    LazyColumn(Modifier.fillMaxWidth().weight(1f).selectableGroup()) {
        item { Text(if (pickingSender) "Speaking as" else "Your members", color = p.ink3, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = 12.dp)) }
        items(shown.filter { pickingSender || it.id != senderId }, key = { "member:${it.id}" }) { member ->
            val selected = if (pickingSender) member.id == senderId else member.id in recipients
            val action = {
                if (pickingSender) { senderId = member.id; recipients = recipients - member.id; choosingSender = false; query = "" }
                else recipients = if (selected) recipients - member.id else recipients + member.id
            }
            Row(Modifier.fillMaxWidth().then(if (pickingSender) Modifier.selectable(selected, enabled = !busy, role = Role.RadioButton, onClick = action)
                else Modifier.toggleable(selected, enabled = !busy, role = Role.Checkbox, onValueChange = { action() }))
                .heightIn(min = 56.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(member.glyph, member.color, 36.dp, avatarBlob = member.avatarBlob)
                Text(member.shownName, modifier = Modifier.weight(1f).padding(horizontal = 12.dp), color = p.ink)
                if (pickingSender) RadioButton(selected, onClick = null) else Checkbox(selected, onCheckedChange = null)
            }
        }
        if (shown.isEmpty()) item { Text("No matching members", color = p.ink3, modifier = Modifier.padding(vertical = 16.dp)) }
        if (!pickingSender) {
            item { Text("Connected accounts", color = p.ink3, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = 12.dp)) }
            if (privateChats && sender != null) item(key = "private") {
                Row(Modifier.fillMaxWidth().toggleable(keepPrivate, enabled = !busy, role = Role.Switch) { keepPrivate = it }
                    .heightIn(min = 56.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Private to ${sender.shownName}", color = p.ink)
                        Text("Only ${sender.shownName} sees and writes in it, while fronting or co-conscious.",
                            color = p.ink3, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(keepPrivate, onCheckedChange = null)
                }
            }
            items(connected, key = { "account:${it.id}" }) { account ->
                TextButton(enabled = !busy && online, onClick = { sender?.let { onAccount(it, account, keepPrivate && privateChats) } }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text((if (keepPrivate && privateChats) "🔒 " else "") + account.shownName, modifier = Modifier.fillMaxWidth())
                }
            }
            if (connected.isEmpty()) item { Text(if (online) "Connect with an account in Friends to message them." else "Connect to load account recipients. Member DMs work offline.", color = p.ink3, modifier = Modifier.padding(vertical = 12.dp)) }
        }
    }
    if (error != null) Text(error, color = p.danger, style = MaterialTheme.typography.bodySmall)
    val chosen = members.filter { it.id in recipients && it.id != senderId }
    Button(enabled = !busy && sender != null && chosen.isNotEmpty(), onClick = { sender?.let { onMembers(it, chosen) } }, modifier = Modifier.fillMaxWidth()) {
        Text(if (busy) "Opening…" else if (chosen.size > 1) "Start group message (${chosen.size + 1})" else "Start message")
    }
}
