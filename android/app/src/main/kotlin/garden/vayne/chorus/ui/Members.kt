package garden.vayne.chorus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Member
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.designsystem.LocalChorusPalette
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.launch

@Composable
fun Members(chorus: Chorus, model: Model) {
    val p = LocalChorusPalette.current
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<String?>(null) }
    var archived by remember { mutableStateOf(false) }

    editing?.let { id ->
        BackHandler { editing = null }
        MemberEditor(chorus, model.member(id), onDone = { editing = null })
        return
    }

    val shown = model.members.filter { it.archived == archived }.let { list ->
        if (query.isBlank()) list
        else list.mapNotNull { m -> fuzzy(query, listOfNotNull(m.name, m.displayName, m.pronouns, *m.sigils.toTypedArray()).joinToString(" "))?.let { it to m } }
            .sortedBy { it.first }.map { it.second }
    }
    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SearchField(query, { query = it }, "Search ${model.members.size} members", Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Add", color = p.surface, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(CircleShape).background(p.accent).clickable { editing = "" }.padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
        item {
            Text(
                if (archived) "‹ Back to members" else "Archived",
                color = p.accent, fontSize = 13.sp,
                modifier = Modifier.clickable { archived = !archived }.padding(vertical = 4.dp),
            )
        }
        items(shown, key = { it.id }) { m ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.surface)
                    .border(1.dp, p.line, RoundedCornerShape(14.dp)).clickable { editing = m.id }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(m.glyph, m.color, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.shownName, color = tonesOf(m.color).name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val groups = model.membership.filter { m.id in it.value }.keys.mapNotNull { model.group(it)?.name }
                    val sub = listOfNotNull(m.pronouns, groups.joinToString(", ").ifEmpty { null }).joinToString(" · ")
                    if (sub.isNotEmpty()) Text(sub, color = p.ink3, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** Edit (or create, when [m] is null) the basics; everything else is on the web for now. */
@Composable
private fun MemberEditor(chorus: Chorus, m: Member?, onDone: () -> Unit) {
    val p = LocalChorusPalette.current
    var name by remember { mutableStateOf(m?.name.orEmpty()) }
    var display by remember { mutableStateOf(m?.displayName.orEmpty()) }
    var pronouns by remember { mutableStateOf(m?.pronouns.orEmpty()) }
    var sigil by remember { mutableStateOf(m?.sigils?.firstOrNull().orEmpty()) }
    var color by remember { mutableStateOf(m?.color ?: "#C0694E") }
    var description by remember { mutableStateOf(m?.description.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val actions = rememberCoroutineScope()

    fun save() {
        if (name.isBlank()) { error = "A name is needed."; return }
        val f = JSONObject()
        fun changed(k: String, new: String, old: String?) { if (new.trim() != old.orEmpty()) f.put(k, new.trim().ifEmpty { JSONObject.NULL }) }
        changed("name", name, m?.name)
        changed("display_name", display, m?.displayName)
        changed("pronouns", pronouns, m?.pronouns)
        changed("description", description, m?.description)
        if (Regex("^#[0-9a-fA-F]{6}$").matches(color.trim())) changed("color", color, m?.color) else { error = "Colour is #RRGGBB."; return }
        val oldSigils = m?.sigils.orEmpty()
        if (sigil.trim() != oldSigils.firstOrNull().orEmpty()) {
            f.put("sigils", JSONArray((listOf(sigil.trim()).filter { it.isNotEmpty() } + oldSigils.drop(1))))
        }
        actions.launch {
            try {
                if (m == null) chorus.create("member.create", chorus.newId(), f)
                else if (f.length() > 0) chorus.create("member.set", m.id, f)
                onDone()
            } catch (e: Exception) {
                error = e.message
            }
        }
    }

    Column(
        Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState()).imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ Members", color = p.accent, modifier = Modifier.clickable(onClick = onDone).padding(vertical = 4.dp))
            Spacer(Modifier.weight(1f))
            Avatar(sigil.ifBlank { name.take(1).uppercase().ifEmpty { "·" } }, color.takeIf { it.length == 7 } ?: "#A09184", 44.dp)
        }
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(display, { display = it }, label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pronouns, { pronouns = it }, label = { Text("Pronouns") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(sigil, { sigil = it.take(16) }, label = { Text("Sigil") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(color, { color = it.take(7) }, label = { Text("Colour") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        OutlinedTextField(description, { description = it }, label = { Text("Description") }, minLines = 3, modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = p.danger) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = ::save) { Text(if (m == null) "Add member" else "Save") }
            if (m != null) {
                Text(
                    if (m.archived) "Unarchive" else "Archive", color = p.ink2,
                    modifier = Modifier.clickable {
                        actions.launch {
                            try {
                                chorus.create(if (m.archived) "member.unarchive" else "member.archive", m.id, JSONObject())
                                onDone()
                            } catch (e: Exception) { error = e.message }
                        }
                    }.padding(8.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
