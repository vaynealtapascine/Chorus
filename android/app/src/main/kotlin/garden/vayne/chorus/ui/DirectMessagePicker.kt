package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Member
import garden.vayne.chorus.data.SpaceAccount
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R

/**
 * Who a direct message is from and to (Figma F5.10's rows: face, name, a line under it, a round
 * check). A system picks who is speaking first, then members or friends; a singlet (one member,
 * the person themselves) has nothing to pick on their side and goes straight to friends.
 */
@Composable
fun ColumnScope.DirectMessagePicker(
    members: List<Member>, accounts: List<SpaceAccount>, busy: Boolean, online: Boolean, error: String?,
    onMembers: (Member, List<Member>) -> Unit, onAccount: (Member, SpaceAccount, Boolean) -> Unit,
    /** Offer "Private to <sender>" for a DM with an account (D-098); not for a singlet. */
    privateChats: Boolean = true,
    /** A singlet's account (D-003): no sender to choose, no members to write to. */
    person: Boolean = false,
) {
    val p = LocalChorusPalette.current
    var query by rememberSaveable { mutableStateOf("") }
    var senderId by rememberSaveable { mutableStateOf(if (person || members.size == 1) members.firstOrNull()?.id else null) }
    var choosingSender by rememberSaveable { mutableStateOf(false) }
    var recipients by rememberSaveable { mutableStateOf(listOf<String>()) }
    var keepPrivate by rememberSaveable { mutableStateOf(false) }
    val sender = members.find { it.id == senderId }
    val pickingSender = !person && (choosingSender || sender == null)
    val shown = remember(members, query) { members.filter { m ->
        listOf(m.shownName, m.name, *m.sigils.toTypedArray()).any { it.contains(query.trim(), true) }
    } }
    val connected = accounts.filter { listOfNotNull(it.shownName, it.handle).any { name -> name.contains(query.trim(), true) } }
    if (!person && sender != null && !pickingSender) Row(Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).background(cardColor())
        .clickable(enabled = !busy, onClickLabel = "Change who is speaking") { choosingSender = true; query = "" }
        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Avatar(sender.glyph, sender.color, 32.dp, avatarBlob = sender.avatarBlob, ring = false)
        Column(Modifier.weight(1f)) {
            Text("Speaking as", color = p.ink3, fontSize = 12.sp)
            Text(sender.shownName, color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("Change", color = p.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
    ChorusField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(when { pickingSender -> "Search members"; person -> "Search friends"; else -> "Search members and friends" }) },
        leadingIcon = { Glyph(R.drawable.ic_search, p.ink3, 20.dp) })
    LazyColumn(Modifier.fillMaxWidth().weight(1f).selectableGroup()) {
        if (!person) {
            item { SectionLabel(if (pickingSender) "Who's speaking" else "Your members") }
            items(shown.filter { pickingSender || it.id != senderId }, key = { "member:${it.id}" }) { member ->
                val selected = if (pickingSender) member.id == senderId else member.id in recipients
                val action = {
                    if (pickingSender) { senderId = member.id; recipients = recipients - member.id; choosingSender = false; query = "" }
                    else recipients = if (selected) recipients - member.id else recipients + member.id
                }
                PickerRow(member.shownName, member.pronouns, selected, busy, pickingSender, action) {
                    Avatar(member.glyph, member.color, 40.dp, avatarBlob = member.avatarBlob, ring = false)
                }
            }
            if (shown.isEmpty()) item { Text("No matching members", color = p.ink3, modifier = Modifier.padding(vertical = 16.dp)) }
        }
        if (!pickingSender) {
            item { SectionLabel(if (person) "Friends" else "Friends' accounts") }
            if (privateChats && !person && sender != null) item(key = "private") {
                FormCard(Modifier.padding(bottom = 6.dp)) {
                    ToggleRow("Private to ${sender.shownName}", keepPrivate,
                        "Only ${sender.shownName} sees and writes in it, while fronting or co-conscious.", enabled = !busy) { keepPrivate = it }
                }
            }
            items(connected, key = { "account:${it.id}" }) { account ->
                PickerRow(account.shownName, if (account.isSystem) "a system · each member writes as themselves" else "one person",
                    false, busy || !online, null, { sender?.let { onAccount(it, account, keepPrivate && privateChats && !person) } }) {
                    Box(Modifier.size(40.dp).background(p.surface3, CircleShape), contentAlignment = Alignment.Center) {
                        Text(account.shownName.removePrefix("@").take(1).uppercase(), color = p.ink, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (connected.isEmpty()) item {
                FormNote(if (online) "Follow someone in Friends to message them here." else
                    if (person) "Connect to load your friends." else "Connect to load friends' accounts. Member DMs work offline.", R.drawable.ic_friends)
            }
        }
    }
    if (error != null) FormNote(error, warning = true)
    val chosen = members.filter { it.id in recipients && it.id != senderId }
    if (!person && !pickingSender) DrawerButton(if (chosen.size > 1) "Start group message (${chosen.size + 1})" else "Start message",
        { sender?.let { onMembers(it, chosen) } }, enabled = sender != null && chosen.isNotEmpty(), busy = busy,
        modifier = Modifier.padding(top = 8.dp))
}

/** One person to pick: a face, their name and a line, then a round check ([radio] null: a plain row that opens). */
@Composable
private fun PickerRow(name: String, line: String?, selected: Boolean, busy: Boolean, radio: Boolean?, onClick: () -> Unit, face: @Composable () -> Unit) {
    val p = LocalChorusPalette.current
    val mod = when (radio) {
        true -> Modifier.selectable(selected, enabled = !busy, role = Role.RadioButton, onClick = onClick)
        false -> Modifier.toggleable(selected, enabled = !busy, role = Role.Checkbox, onValueChange = { onClick() })
        null -> Modifier.clickable(enabled = !busy, onClick = onClick)
    }
    Row(Modifier.fillMaxWidth().then(mod).heightIn(min = 60.dp).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        face()
        Column(Modifier.weight(1f)) {
            Text(name, color = if (busy && radio == null) p.ink3 else p.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            line?.takeIf { it.isNotBlank() }?.let { Text(it, color = p.ink3, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (radio == null) Glyph(R.drawable.ic_chevron_right, p.ink3, 18.dp)
        else Box(Modifier.size(26.dp).then(if (selected) Modifier.background(p.accent, CircleShape) else Modifier.border(1.5.dp, p.ink3, CircleShape)),
            contentAlignment = Alignment.Center) { if (selected) Glyph(R.drawable.ic_check, p.onAccent, 16.dp) }
    }
}
