package garden.vayne.chorus.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Entry
import garden.vayne.chorus.data.Front
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.Subject
import garden.vayne.chorus.designsystem.LocalChorusPalette
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.launch

/** Full switch editor. A fresh sheet snapshots the current front so live deltas do not erase edits. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Switcher(chorus: Chorus, model: Model, frontId: String? = null, onClose: () -> Unit) {
    val p = LocalChorusPalette.current
    val actions = rememberCoroutineScope()
    // D-080: a configured account switches one named front, through its own independent layers
    val named = model.namedFronts
    val target = frontId ?: named?.bodyId
    val layers = named?.liveLayers?.map { it.id to it.name }?.ifEmpty { null }
        ?: listOf("front" to "Front", "cocon" to "Co-con", "present" to "Present")
    val inner = layers.first().first
    val layerName = { id: String -> layers.firstOrNull { it.first == id }?.second ?: id }
    val selected = remember { mutableStateListOf<Entry>().also {
        it.addAll(if (named != null && target != null) named.entries(target) else model.current)
    } }
    var query by remember { mutableStateOf("") }
    var groupId by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var whenText by remember { mutableStateOf("") }
    var notify by remember { mutableStateOf("default") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val candidates = remember(model, query, groupId) {
        val allowed = groupId?.let { model.membership[it].orEmpty() }
        val recents = model.recents(20)
        val members = model.active.filter { allowed == null || it.id in allowed }
            .map { Subject("member", it.id, it.shownName, it.color, it.glyph, it.avatarBlob) }
        val subsystems = if (allowed == null) model.groups.filter { it.isSubsystem && garden.vayne.chorus.data.Subsystems.memberOf(model, it.id) == null }
            .map { Subject("group", it.id, it.name, it.color ?: "#A09184", "◌") } else emptyList()
        (members + subsystems).mapNotNull { s -> fuzzy(query, s.name)?.let { score -> s to score } }
            .sortedWith(compareBy<Pair<Subject, Int>> { it.second }.thenBy { recents.indexOf(it.first.id).let { n -> if (n < 0) Int.MAX_VALUE else n } }.thenBy { it.first.name })
            .map { it.first }
    }

    fun repairPrimary() {
        if (selected.any { it.isPrimary && it.level == inner }) return
        val first = selected.indexOfFirst { it.level == inner }
        if (first >= 0) selected[first] = selected[first].copy(isPrimary = true)
    }

    fun toggle(s: Subject) {
        val existing = selected.indexOfFirst { it.subjectType == s.type && it.subjectId == s.id }
        if (existing >= 0) { selected.removeAt(existing); repairPrimary() }
        else selected.add(Entry(s.type, s.id, inner, selected.none { it.level == inner }))
    }

    fun commit(entries: List<Entry>) {
        val at = if (whenText.isBlank()) null else parseSwitchTime(whenText)
        if (whenText.isNotBlank() && at == null) { error = "Use YYYY-MM-DD HH:MM for the time."; return }
        busy = true
        val names = entries.filter { it.level == inner }.mapNotNull { model.subject(it.subjectType, it.subjectId)?.name }
        actions.launch {
            try {
                Front.switch(chorus, entries, if (names.isEmpty()) "Switched out" else "Switched to ${names.joinToString(" & ")}", at, note, notify,
                    frontId = if (named != null) target else null)
                onClose()
            } catch (e: Exception) { error = e.message; busy = false }
        }
    }

    ChorusSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().heightIn(max = 700.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val frontName = target?.takeIf { it != named?.bodyId }?.let { named?.fronts?.get(it)?.name }
            Text(if (frontName != null) "Who's in $frontName?" else "Who's here?", fontWeight = FontWeight.SemiBold, color = p.ink)
            selected.forEachIndexed { index, e ->
                val s = model.subject(e.subjectType, e.subjectId)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s?.name ?: "Unknown", modifier = Modifier.weight(1f), color = p.ink)
                    TextButton(onClick = {
                        val ids = layers.map { it.first }
                        val next = ids[(ids.indexOf(e.level) + 1).mod(ids.size)]
                        selected[index] = e.copy(level = next, isPrimary = next == inner && e.isPrimary)
                        repairPrimary()
                    }) { Text(layerName(e.level)) }
                    TextButton(onClick = {
                        selected.indices.forEach { n -> selected[n] = selected[n].copy(isPrimary = n == index, level = if (n == index) inner else selected[n].level) }
                    }) { Text(if (e.isPrimary) "★" else "☆", Modifier.semantics {
                        contentDescription = if (e.isPrimary) "${s?.name ?: "Unknown"} is primary" else "Make ${s?.name ?: "Unknown"} primary"
                    }) }
                    TextButton(onClick = { selected.removeAt(index); repairPrimary() }) {
                        Text("×", Modifier.semantics { contentDescription = "Remove ${s?.name ?: "Unknown"}" })
                    }
                }
            }
            OutlinedTextField(query, { query = it }, label = { Text("Search members and subsystems") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(groupId == null, onClick = { groupId = null }, label = { Text("All") })
                model.groups.forEach { group ->
                    FilterChip(groupId == group.id, onClick = { groupId = group.id }, label = { Text(group.name) })
                }
            }
            candidates.take(30).forEach { s ->
                val chosen = selected.any { it.subjectType == s.type && it.subjectId == s.id }
                Row(Modifier.fillMaxWidth().clickable { toggle(s) }.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (chosen) "✓" else "+", color = p.accent)
                    Text(s.name, color = p.ink, modifier = Modifier.weight(1f))
                    if (s.type == "group") Text("subsystem", color = p.ink3)
                }
            }
            if (candidates.size > 30) Text("Showing 30. Search to narrow the list.", color = p.ink3)
            OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(whenText, { whenText = it }, label = { Text("Happened at (YYYY-MM-DD HH:MM)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("default" to "Usual", "silent" to "Silent", "now" to "Now", "extra_delay" to "Delay").forEach { (value, label) ->
                    FilterChip(notify == value, onClick = { notify = value }, label = { Text(label) })
                }
            }
            error?.let { Text(it, color = p.danger) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = { commit(emptyList()) }, enabled = !busy) { Text("Switch out") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { commit(selected.toList()) }, enabled = !busy && selected.isNotEmpty()) { Text("Switch") }
            }
        }
    }
}

/** Strict local wall-time parse; user_time itself travels to core for time correction. */
fun parseSwitchTime(input: String): Long? {
    val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { isLenient = false }
    val position = ParsePosition(0)
    val date = format.parse(input.trim(), position) ?: return null
    return date.time.takeIf { position.index == input.trim().length }
}
