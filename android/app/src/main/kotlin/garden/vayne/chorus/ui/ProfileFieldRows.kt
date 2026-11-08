package garden.vayne.chorus.ui

import garden.vayne.chorus.designsystem.R
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.InspectionDialogContent
import garden.vayne.chorus.data.Audience
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.ProfileAudience
import garden.vayne.chorus.data.ProfileDetail
import garden.vayne.chorus.designsystem.LocalChorusPalette
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// The rows of the one profile editor (D-101): a label with its audience chip over the value's editor.

internal val FIELD_TYPES = listOf("text" to "Text", "long_text" to "Long text", "multi_select" to "Several choices",
    "select" to "One choice", "date" to "Date", "number" to "Number", "boolean" to "Yes / no", "url" to "Link", "color" to "Colour")

internal fun typeLabel(type: String) = FIELD_TYPES.firstOrNull { it.first == type }?.second ?: type

/** A part's label, its field menu (custom fields), and its audience chip. */
@Composable
internal fun PartHeader(label: String, chip: @Composable () -> Unit, onSettings: (() -> Unit)? = null) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = p.ink2, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        if (onSettings != null) TextButton(onClick = onSettings, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("⋯", color = p.ink3) }
        chip()
    }
}

/** The editor for one custom field's draft text ([MemberCustomization.jsonValue] turns it into the stored value). */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun FieldValueEditor(chorus: Chorus, def: ProfileDetail, type: String, value: String, enabled: Boolean, onValue: (String) -> Unit,
    onError: (String) -> Unit) {
    val p = LocalChorusPalette.current
    val scope = rememberCoroutineScope()
    when (type) {
        "select", "multi_select" -> {
            val chosen = if (type == "select") listOf(value).filter(String::isNotBlank)
                else runCatching { JSONArray(value) }.getOrNull()?.let { a -> List(a.length()) { a.optString(it) }.filter(String::isNotBlank) }.orEmpty()
            fun toggle(c: String) {
                if (type == "select") onValue(if (chosen.firstOrNull() == c) "" else c)
                else onValue(JSONArray(if (c in chosen) chosen - c else chosen + c).toString().let { if (it == "[]") "" else it })
            }
            var adding by remember { mutableStateOf("") }
            fun add() {
                val c = adding.trim()
                adding = ""
                if (c.isEmpty()) return
                val existing = def.choices.find { it.equals(c, ignoreCase = true) }
                if (existing == null) scope.launch {
                    // choices belong to the field, for every member, so they're kept right away
                    try { chorus.create("field.set_def", def.id, JSONObject().put("options", JSONObject(def.options).put("choices", JSONArray(def.choices + c)))) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { onError("Could not add that choice. Try again.") }
                }
                val pick = existing ?: c
                if (pick !in chosen) toggle(pick)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in (def.choices + chosen).distinct()) RolePill(c, on = c in chosen) { if (enabled) toggle(c) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChorusField(adding, { adding = it }, singleLine = true, enabled = enabled, label = { Text("New choice") }, modifier = Modifier.weight(1f))
                TextButton(enabled = enabled && adding.isNotBlank(), onClick = ::add) { Text("Add") }
            }
        }
        "boolean" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(value == "true", { onValue(if (value == "true") "" else "true") }, label = { Text("Yes") }, enabled = enabled)
            FilterChip(value == "false", { onValue(if (value == "false") "" else "false") }, label = { Text("No") }, enabled = enabled)
        }
        "color" -> Row(verticalAlignment = Alignment.CenterVertically) {
            ColourField(value.ifBlank { "#888888" }, onValue, label = def.name, enabled = enabled, modifier = Modifier.weight(1f))
            if (value.isNotBlank()) TextButton(enabled = enabled, onClick = { onValue("") }) { Text("Clear") }
        }
        else -> ChorusField(value, onValue, enabled = enabled, modifier = Modifier.fillMaxWidth(),
            singleLine = type != "long_text", minLines = if (type == "long_text") 3 else 1,
            placeholder = { Text(when (type) { "url" -> "https://…"; "date" -> "YYYY-MM-DD"; else -> "Add ${def.name.lowercase()}" }, color = p.ink3) },
            keyboardOptions = KeyboardOptions(keyboardType = when (type) { "number" -> KeyboardType.Decimal; "url" -> KeyboardType.Uri; else -> KeyboardType.Text }))
    }
}

/** Adding a field asks twice: what it is, then "Add it to every member?" (D-101). */
@Composable
internal fun AddFieldDialog(chorus: Chorus, model: Model, onAdded: (ProfileDetail) -> Unit, onDismiss: () -> Unit) {
    val p = LocalChorusPalette.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("text") }
    var audience by remember { mutableStateOf(Audience.PRIVATE) }
    var confirming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val taken = model.profileDetails.definitions.any { it.name.trim().equals(name.trim(), ignoreCase = true) }
    val person = model.isPerson
    Drawer(onDismiss, "Add a field", if (person) "A detail on your profile" else "A detail on every member's profile", footer = {
        DrawerButton("Next", { confirming = true }, enabled = name.isNotBlank() && !taken)
    }) {
        ChorusField(name, { name = it.take(60) }, singleLine = true, label = { Text("Name") }, placeholder = { Text("e.g. Favourite food") },
            isError = taken, supportingText = if (taken) { { Text("There's already a field called that.") } } else null, modifier = Modifier.fillMaxWidth())
        var open by remember { mutableStateOf(false) }
        ChoiceField("Kind", typeLabel(type)) { open = true }
        ChoiceDrawer(open, "What kind of field", FIELD_TYPES.toList(), FIELD_TYPES.toList().firstOrNull { it.first == type }, { it.second },
            { type = it.first; open = false }, { open = false })
        FormCard {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Starts as", color = p.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                AudienceChip("New field", audience, own = true, buckets = model.sharingBuckets, person = person,
                    onChange = { audience = it ?: Audience.PRIVATE }, looks = model.bucketLooks)
            }
        }
        if (confirming) ConfirmDrawer(if (person) "Add “${name.trim()}”?" else "Add “${name.trim()}” to every member?",
            if (person) null else "Every member gets an empty one.", "Add field", busy = busy, cancel = "Not yet", onDismiss = { confirming = false }, icon = R.drawable.ic_plus, content = {
                error?.let { FormNote(it, warning = true) }
            }, onConfirm = {
            busy = true
            scope.launch {
                try {
                    val id = chorus.newId()
                    val payload = JSONObject().put("name", name.trim()).put("type", type).put("default_visibility", audience.toJson())
                    if (type == "select" || type == "multi_select") payload.put("options", JSONObject().put("choices", JSONArray()))
                    chorus.create("field.define", id, payload)
                    onAdded(ProfileDetail(id, name.trim(), type, audience.mode, audience.toJson().toString(), options = payload.optJSONObject("options")?.toString() ?: "{}"))
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { error = "Could not add the field. Try again."; busy = false }
            }
        })
    }
}

/** A field's own settings, for every member: its name, who sees it on members who haven't chosen, or removing it. */
@Composable
internal fun FieldSettingsDialog(chorus: Chorus, model: Model, def: ProfileDetail, onDismiss: () -> Unit) {
    val p = LocalChorusPalette.current
    val scope = rememberCoroutineScope()
    var name by remember(def.id) { mutableStateOf(def.name) }
    var audience by remember(def.id) { mutableStateOf(ProfileAudience.of(JSONObject(def.defaultVisibility))) }
    var removing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val taken = model.profileDetails.definitions.any { it.id != def.id && it.name.trim().equals(name.trim(), ignoreCase = true) }
    fun send(payload: JSONObject, kind: String = "field.set_def") = scope.launch {
        try { chorus.create(kind, def.id, payload); onDismiss() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = "Could not save that. Try again." }
    }
    val person = model.isPerson
    Drawer(onDismiss, def.name, if (person) "${typeLabel(def.type)} field on your profile" else "${typeLabel(def.type)} field, on every member", footer = {
        DrawerButton("Save", {
            val payload = JSONObject()
            if (name.trim() != def.name) payload.put("name", name.trim())
            if (!audience.same(ProfileAudience.of(JSONObject(def.defaultVisibility)))) payload.put("default_visibility", audience.toJson())
            if (payload.length() == 0) onDismiss() else send(payload)
        }, enabled = name.isNotBlank() && !taken)
        DrawerButton(if (person) "Remove field…" else "Remove from every member…", { removing = true }, ButtonKind.Quiet)
    }) {
        ChorusField(name, { name = it.take(60) }, singleLine = true, label = { Text("Name") }, isError = taken,
            supportingText = if (taken) { { Text("Another field already has that name.") } } else null, modifier = Modifier.fillMaxWidth())
        FormCard {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (person) "Who sees it" else "For members who haven't chosen", color = p.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                AudienceChip("${def.name} by default", audience, own = true, buckets = model.sharingBuckets, person = person,
                    onChange = { audience = it ?: Audience.PRIVATE }, looks = model.bucketLooks)
            }
        }
        error?.let { FormNote(it, warning = true) }
        if (removing) ConfirmDrawer(if (person) "Remove “${def.name}”?" else "Remove “${def.name}” from every member?",
            "Nothing is erased.",
            "Remove field", { send(JSONObject(), "field.delete_def") }, { removing = false }, danger = true, cancel = "Keep it")
    }
}
