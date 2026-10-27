package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Front
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.NamedFront
import garden.vayne.chorus.designsystem.LocalChorusPalette
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Purposes a front selects layers for; null keeps the existing default (D-080, D-085). */
private val PURPOSES = listOf(
    "authorship" to "Suggested speakers in chat",
    "notifications" to "Switch notifications",
    "mentions" to "@front mentions",
    "reading" to "Read tracking",
    "chat_alerts" to "“Only while fronting” alerts",
)
private val TEMPLATES = listOf("Online", "Work", "Inner world", "Subsystem")
private val ORIGINAL = setOf("front", "cocon", "present")

/** The sparse `front.define` patch that sets one purpose's selection. */
internal fun purposePatch(purpose: String, value: Set<String>?): JSONObject {
    val v: Any = value?.let { JSONArray(it.sorted()) } ?: JSONObject.NULL
    return when (purpose) {
        "authorship" -> JSONObject().put("authorship_layers", v)
        "notifications" -> JSONObject().put("notification_layers", v)
        else -> JSONObject().put("purpose_layers", JSONObject().put(purpose, v))
    }
}

internal fun selectionOf(front: NamedFront, purpose: String): Set<String>? = when (purpose) {
    "authorship" -> front.authorshipLayers
    "notifications" -> front.notificationLayers
    else -> front.purposeLayers[purpose]
}

/** Fronts and layers (Figma F3.4 / F3.9). Following, overrides and purpose pools are resolved in
 * core; this screen only writes sparse definitions. */
@Composable
fun FrontsScreen(chorus: Chorus, model: Model, onClose: () -> Unit) {
    val p = LocalChorusPalette.current
    val actions = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var newLayer by remember { mutableStateOf("") }
    val account = chorus.device?.accountId.orEmpty()
    val named = model.namedFronts
    val layers = named?.liveLayers?.map { Triple(it.id, it.name, it.level) }
        ?: listOf(Triple("front", "Fronting", 0), Triple("cocon", "Co-conscious", 1), Triple("present", "Present", 2))
    val fronts = named?.fronts?.values?.sortedWith(compareBy<NamedFront> { it.id != account }.thenBy { it.name.lowercase() })
        ?: listOf(NamedFront(account, "Body", emptyList(), null, false, false, null, null, emptyMap()))
    fun act(block: suspend () -> Unit) = actions.launch {
        try { block(); error = null } catch (e: Exception) { error = e.message ?: "Could not save." }
    }
    fun entriesOf(id: String) = named?.entries(id) ?: model.current

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Fronts", color = p.ink, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text("Separate places someone can be present", color = p.ink3, fontSize = 12.sp)
                }
                TextButton(onClick = onClose) { Text("Done") }
            }
        }
        error?.let { item { Text(it, color = p.danger) } }
        items(fronts.filter { !it.deleted }, key = { it.id }) { f ->
            val resolved = named?.resolved?.get(f.id)
            val source = resolved?.source?.let { named.fronts[it]?.name }
            val badge = when (resolved?.state) {
                "following" -> "Follows ${source ?: "another front"}"
                "overridden" -> "Changed by hand"
                "unavailable" -> if (resolved.issue == "cycle") "Following loops" else "Source unavailable"
                else -> if (f.id == account) "Main" else null
            }
            val entries = entriesOf(f.id)
            Column(Modifier.fillMaxWidth().background(p.surface, RoundedCornerShape(18.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(f.name.ifBlank { "Body" }, color = p.ink, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        badge?.let { Text(it, color = p.ink2, fontSize = 12.sp,
                            modifier = Modifier.background(p.surface2, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp)) }
                    }
                    TextButton(onClick = { editing = if (editing == f.id) null else f.id }) { Text(if (editing == f.id) "Close" else "Edit") }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    layers.forEachIndexed { i, (id, name, _) ->
                        val n = entries.count { it.level == id }
                        if (n > 0) Text("$name $n", fontSize = 12.sp, color = if (i == 0) p.ink else p.ink2,
                            modifier = Modifier.background(if (i == 0) p.accentSoft else p.surface2, RoundedCornerShape(50))
                                .padding(horizontal = 10.dp, vertical = 3.dp))
                    }
                    if (entries.isEmpty()) Text("No one here", color = p.ink3, fontSize = 12.sp)
                }
                if (resolved?.state == "overridden") TextButton(onClick = { act { Front.resume(chorus, f.id) } }) {
                    Text("Resume following ${source.orEmpty()}")
                }
                if (editing == f.id) FrontEditor(f, fronts.filter { !it.deleted && it.id != f.id }, layers.map { it.first to it.second },
                    isBody = f.id == account, onChange = { patch -> act { Front.define(chorus, f.id, patch) } },
                    onRemove = { act { Front.define(chorus, f.id, JSONObject().put("deleted", true)) }; editing = null })
            }
        }
        item {
            Column(Modifier.fillMaxWidth().border(1.dp, p.line, RoundedCornerShape(18.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("New front", color = p.accent, fontWeight = FontWeight.SemiBold)
                fun create(name: String) {
                    val n = name.trim()
                    if (n.isEmpty()) return
                    act {
                        val id = chorus.newId()
                        Front.define(chorus, id, JSONObject().put("name", n).put("follow_source", JSONObject.NULL))
                        editing = id
                    }
                    newName = ""
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(newName, { newName = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(1f))
                    Button(onClick = { create(newName) }, enabled = newName.isNotBlank()) { Text("Add") }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TEMPLATES.forEach { t -> FilterChip(false, onClick = { create(t) }, label = { Text(t) }) }
                }
            }
        }
        val removed = fronts.filter { it.deleted }
        if (removed.isNotEmpty()) item {
            Column {
                Text("Removed fronts", color = p.ink2, fontWeight = FontWeight.SemiBold)
                removed.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(f.name, color = p.ink2, modifier = Modifier.weight(1f))
                        TextButton(onClick = { act { Front.define(chorus, f.id, JSONObject().put("deleted", false)) } }) { Text("Restore") }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Layers · inner to outer", color = p.accent, fontWeight = FontWeight.SemiBold)
                Text("Shared by every front. Renaming or reordering never rewrites history; a custom layer never stands in for Fronting, Co-conscious or Present.",
                    color = p.ink3, fontSize = 12.sp)
            }
        }
        items(layers.size, key = { layers[it].first }) { i ->
            val (id, name, level) = layers[i]
            var text by remember(id, name) { mutableStateOf(name) }
            Row(Modifier.fillMaxWidth().background(p.surface, RoundedCornerShape(12.dp)).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                    label = { Text(if (i == 0) "Innermost" else "Layer") })
                if (text.isNotBlank() && text.trim() != name) TextButton(onClick = {
                    act { Front.layer(chorus, id, JSONObject().put("name", text.trim())) }
                }) { Text("Save") }
                TextButton(enabled = i > 0, onClick = { act {
                    val other = layers[i - 1]
                    val (mine, theirs) = if (other.third == level) (level - 1) to level else other.third to level
                    Front.layer(chorus, id, JSONObject().put("level", mine))
                    Front.layer(chorus, other.first, JSONObject().put("level", theirs))
                } }) { Text("↑") }
                TextButton(enabled = i < layers.lastIndex, onClick = { act {
                    val other = layers[i + 1]
                    val (mine, theirs) = if (other.third == level) (level + 1) to level else other.third to level
                    Front.layer(chorus, id, JSONObject().put("level", mine))
                    Front.layer(chorus, other.first, JSONObject().put("level", theirs))
                } }) { Text("↓") }
                if (id !in ORIGINAL) TextButton(onClick = { act { Front.layer(chorus, id, JSONObject().put("deleted", true)) } }) { Text("✕") }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(newLayer, { newLayer = it }, label = { Text("Add a layer") }, singleLine = true, modifier = Modifier.weight(1f))
                Button(enabled = newLayer.isNotBlank(), onClick = {
                    val n = newLayer.trim()
                    val top = layers.maxOfOrNull { it.third } ?: -1
                    act { Front.layer(chorus, chorus.newId(), JSONObject().put("name", n).put("level", top + 1)) }
                    newLayer = ""
                }) { Text("Add") }
            }
        }
    }
}

@Composable
private fun FrontEditor(
    front: NamedFront, others: List<NamedFront>, layers: List<Pair<String, String>>, isBody: Boolean,
    onChange: (JSONObject) -> Unit, onRemove: () -> Unit,
) {
    val p = LocalChorusPalette.current
    var name by remember(front.id, front.name) { mutableStateOf(front.name) }
    var purposesOpen by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(1f))
            if (name.isNotBlank() && name.trim() != front.name) TextButton(onClick = { onChange(JSONObject().put("name", name.trim())) }) { Text("Save") }
        }
        Text("Follows", color = p.ink2, fontSize = 13.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(front.followSource == null, onClick = { onChange(JSONObject().put("follow_source", JSONObject.NULL)) },
                label = { Text("Nothing") })
            others.forEach { o ->
                FilterChip(front.followSource == o.id, onClick = { onChange(JSONObject().put("follow_source", o.id)) },
                    label = { Text(o.name.ifBlank { "Body" }) })
            }
        }
        Text("A following front mirrors its source. Changing it by hand keeps your change until you choose Resume following.",
            color = p.ink3, fontSize = 12.sp)
        Text(if (purposesOpen) "Who counts for what ▴" else "Who counts for what ▾", color = p.accent,
            modifier = Modifier.clickable { purposesOpen = !purposesOpen }.padding(vertical = 6.dp))
        if (purposesOpen) PURPOSES.forEach { (key, label) ->
            val sel = selectionOf(front, key)
            Column(Modifier.fillMaxWidth().border(1.dp, p.line, RoundedCornerShape(12.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, color = p.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(sel == null, onClick = { onChange(purposePatch(key, if (sel == null) emptySet() else null)) },
                        label = { Text("Default") })
                    layers.forEach { (id, layerName) ->
                        val on = sel?.contains(id) == true
                        FilterChip(on, onClick = {
                            val now = sel.orEmpty()
                            onChange(purposePatch(key, if (on) now - id else now + id))
                        }, label = { Text(layerName) })
                    }
                }
                if (sel != null && sel.isEmpty()) Text("Nobody is selected.", color = p.ink3, fontSize = 12.sp)
            }
        }
        if (!isBody) OutlinedButton(onClick = onRemove) { Text("Remove ${front.name}", color = p.danger) }
    }
}
