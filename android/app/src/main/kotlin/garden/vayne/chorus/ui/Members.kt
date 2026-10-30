package garden.vayne.chorus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.MemberOrdering
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.designsystem.LocalChorusPalette

@Composable
fun Members(chorus: Chorus, model: Model, onJournal: (String?, String?) -> Unit,
    active: Boolean = true, onFocusedRoute: (Boolean) -> Unit = {}) {
    val p = LocalChorusPalette.current
    var query by rememberSaveable { mutableStateOf("") }
    var adding by rememberSaveable { mutableStateOf(false) }
    var profile by rememberSaveable { mutableStateOf<String?>(null) }
    var archived by rememberSaveable { mutableStateOf(false) }
    var reordering by rememberSaveable { mutableStateOf(false) }
    SideEffect { if (active) onFocusedRoute(adding || profile != null || reordering) }
    BackHandler(active && (profile != null || adding || reordering)) {
        when { adding -> adding = false; reordering -> reordering = false; else -> profile = null }
    }
    if (adding) { MemberEditor(chorus, model, null) { adding = false }; return }
    if (reordering) { MemberReorder(chorus, model) { reordering = false }; return }
    profile?.let { id ->
        MemberProfile(chorus, model, id, onClose = { profile = null }, onWrite = { onJournal(id, null) },
            onReply = { onJournal(null, it) }, closeLabel = "Members", writeLabel = "Journal")
        return
    }
    val eligible = remember(model.members, chorus.device?.accountId, archived) {
        MemberOrdering.eligible(model.members, chorus.device?.accountId, archived)
    }
    val shown = if (query.isBlank()) eligible else eligible.mapNotNull { m ->
        fuzzy(query, listOfNotNull(m.name, m.displayName, m.pronouns, *m.sigils.toTypedArray()).joinToString(" "))?.let { it to m }
    }.sortedWith(compareBy<Pair<Int, garden.vayne.chorus.data.Member>> { it.first }
        .thenBy { it.second.shownName.lowercase(java.util.Locale.ROOT) }.thenBy { it.second.id }).map { it.second }
    val list = rememberLazyListState()
    LazyColumn(Modifier.padding(horizontal = 16.dp), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it }, label = { Text("Search members") },
                    placeholder = { Text("${eligible.size} members") }, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Button(onClick = { adding = true }) { Text("Add") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { archived = !archived }) { Text(if (archived) "Back to members" else "Archived") }
                TextButton(enabled = !archived && query.isBlank() && eligible.size > 1, onClick = { reordering = true }) { Text("Reorder") }
            }
            if (query.isNotBlank() || archived) TextButton(onClick = { query = ""; archived = false }) { Text("Show all active members to reorder") }
            if (shown.isEmpty()) Text(if (query.isNotBlank()) "No members match. Try another name." else "No ${if (archived) "archived " else ""}members yet.",
                color = p.ink3, modifier = Modifier.padding(vertical = 16.dp))
        }
        items(shown, key = { it.id }) { m ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.surface)
                .clickable { profile = m.id }.padding(horizontal = 12.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(m.glyph, m.color, 44.dp, avatarBlob = m.avatarBlob)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.shownName, color = tonesOf(m.color).name, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val groups = model.membership.filter { m.id in it.value }.keys.mapNotNull { model.group(it)?.name }
                    val sub = listOfNotNull(m.pronouns, groups.joinToString(", ").ifEmpty { null }).joinToString(" · ")
                    if (sub.isNotEmpty()) Text(sub, color = p.ink3, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                model.current.find { it.subjectId == m.id && it.subjectType == "member" }?.let { entry ->
                    Text(if (entry.level == "front") "Fronting" else if (entry.level == "cocon") "Co-conscious" else "Present",
                        color = p.accent, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 8.dp))
                }
                ChorusIcon(AppIcon.Chevron, modifier = Modifier.padding(start = 8.dp))
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
