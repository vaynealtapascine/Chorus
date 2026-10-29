package garden.vayne.chorus.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.InspectionDialogContent
import garden.vayne.chorus.data.*
import garden.vayne.chorus.designsystem.LocalChorusPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A saved opening snapshot protects every untouched field across sync and recreation. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun MemberEditor(chorus: Chorus, model: Model, member: Member?, onDone: () -> Unit) {
    val p = LocalChorusPalette.current
    val ctx = LocalContext.current
    val actions = rememberCoroutineScope()
    val account = chorus.device?.accountId.orEmpty()
    val id by rememberSaveable { mutableStateOf(member?.id ?: chorus.newId()) }
    val snapshot by rememberSaveable { mutableStateOf(member?.let { m ->
        JSONObject().put("name", m.name).put("display_name", m.displayName).put("pronouns", m.pronouns)
            .put("color", m.color).put("sigils", JSONArray(m.sigils)).put("description", m.description)
            .put("description_entities", JSONArray(m.descriptionEntities)).put("avatar_blob", m.avatarBlob)
            .put("banner_blob", m.bannerBlob).put("birthday", m.birthday).toString()
    }) }
    val baseline = remember(snapshot) { snapshot?.let {
        Model.parse(JSONObject().put("rows", JSONObject().put("member", JSONObject().put(id,
            JSONObject().put("exists", true).put("fields", JSONObject(it))))), account).member(id)
    } }
    val baselineBio by rememberSaveable { mutableStateOf(MemberCustomization.markup(baseline)) }
    val fieldSnapshot by rememberSaveable { mutableStateOf(JSONArray().also { array ->
        for ((name, type) in ProfileDetails.slots) {
            val def = model.profileDetails.field(account, name, type)
            array.put(JSONObject().put("id", def.id).put("name", def.name).put("type", def.type)
                .put("visibility", def.visibility).put("exists", model.profileDetails.definitions.any { it.id == def.id })
                .put("value", model.profileDetails.value(member?.id, def)))
        }
    }.toString()) }
    val fields = remember(fieldSnapshot) { JSONArray(fieldSnapshot) }
    var values by rememberSaveable { mutableStateOf(List(fields.length()) { fields.getJSONObject(it).getString("value") }) }
    var name by rememberSaveable { mutableStateOf(baseline?.name.orEmpty()) }
    var display by rememberSaveable { mutableStateOf(baseline?.displayName.orEmpty()) }
    var pronouns by rememberSaveable { mutableStateOf(baseline?.pronouns.orEmpty()) }
    var sigil by rememberSaveable { mutableStateOf(baseline?.sigils?.firstOrNull().orEmpty()) }
    var color by rememberSaveable { mutableStateOf(baseline?.color ?: "#C0694E") }
    var bio by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(baselineBio)) }
    var bioDraft by rememberSaveable { mutableStateOf(RichTextEdit.open(
        baseline?.description.orEmpty(), baseline?.descriptionEntities ?: "[]", baselineBio)) }
    fun editBio(value: TextFieldValue, formatting: Boolean = false) {
        bioDraft = RichTextEdit.update(bioDraft, value.text, bio.selection.start, bio.selection.end, value.selection.start, preserveReplacement = formatting)
        bio = value
    }
    var birthday by rememberSaveable { mutableStateOf(baseline?.birthday.orEmpty()) }
    var avatar by rememberSaveable { mutableStateOf(baseline?.avatarBlob) }
    var banner by rememberSaveable { mutableStateOf(baseline?.bannerBlob) }
    var cropPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pickingBanner by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    val dirty = name != baseline?.name.orEmpty() || display != baseline?.displayName.orEmpty() ||
        pronouns != baseline?.pronouns.orEmpty() || sigil != baseline?.sigils?.firstOrNull().orEmpty() ||
        color != (baseline?.color ?: "#C0694E") || bio.text != baselineBio || birthday != baseline?.birthday.orEmpty() ||
        avatar != baseline?.avatarBlob || banner != baseline?.bannerBlob || values.indices.any { values[it] != fields.getJSONObject(it).getString("value") }
    fun close() { if (!busy) { if (dirty) discard = true else onDone() } }
    BackHandler { close() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) actions.launch {
            busy = true; error = null
            try {
                cropPath = withContext(Dispatchers.IO) {
                    val file = File.createTempFile("profile-source-", ".image", ctx.cacheDir)
                    try {
                        ctx.contentResolver.openInputStream(uri)?.use { src -> file.outputStream().use { out ->
                            val buffer = ByteArray(16 * 1024)
                            var total = 0
                            while (true) {
                                val n = src.read(buffer)
                                if (n < 0) break
                                total += n
                                require(total <= 10 * 1024 * 1024) { "Choose an image under 10 MB." }
                                out.write(buffer, 0, n)
                            }
                        } } ?: error("Could not open this image. Choose it again.")
                        file.absolutePath
                    } catch (e: Exception) { file.delete(); throw e }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not open this image." }
            finally { busy = false }
        }
    }
    cropPath?.let { path ->
        fun endCrop() { cropPath = null; File(path).delete() }
        ProfileImageCrop(path, pickingBanner, account, onCancel = ::endCrop, onApply = { hash ->
            if (pickingBanner) banner = hash else avatar = hash
            endCrop()
        })
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { InspectionDialogContent { Text("Discard profile changes?") } },
        text = { InspectionDialogContent { Text("Your saved profile will stay as it was.") } },
        confirmButton = { InspectionDialogContent { TextButton(onClick = onDone) { Text("Discard") } } },
        dismissButton = { InspectionDialogContent { TextButton(onClick = { discard = false }) { Text("Keep editing") } } })

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = !busy, onClick = ::close) { Text("Cancel") }
            Text(if (baseline == null) "New member" else "Edit profile", color = p.ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Button(enabled = !busy, onClick = {
                error = null
                actions.launch {
                    try {
                        val patch = MemberCustomization.patch(baseline, name, display, pronouns, sigil, color,
                            bio.text, baselineBio, avatar, banner, birthday,
                            names = ChatEdit.names(baseline?.description.orEmpty(), baseline?.descriptionEntities ?: "[]", model), bioDraft = bioDraft)
                        busy = true
                        chorus.saveMemberProfile(id, baseline != null, patch, fields, values)
                        if (avatar != null || banner != null) UploadWork.enqueue(ctx)
                        onDone()
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "Could not save the profile. Try again." }
                    finally { busy = false }
                }
            }) { Text(if (busy) "Saving…" else "Save") }
        }
        error?.let { Text(it, color = p.danger, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ProfileIdentity(baseline?.copy(name = name.ifBlank { "New member" }, displayName = display.ifBlank { null }, pronouns = pronouns.ifBlank { null },
                color = color, sigils = listOf(sigil).filter(String::isNotBlank), avatarBlob = avatar, bannerBlob = banner)
                ?: Member(id, name.ifBlank { "New member" }, display.ifBlank { null }, pronouns.ifBlank { null }, color, listOf(sigil).filter(String::isNotBlank), null, false,
                    avatarBlob = avatar, bannerBlob = banner), chorus)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = { pickingBanner = false; picker.launch("image/*") }) { Text("Choose avatar") }
                if (avatar != null) TextButton(enabled = !busy, onClick = { avatar = null }) { Text("Remove avatar") }
                OutlinedButton(enabled = !busy, onClick = { pickingBanner = true; picker.launch("image/*") }) { Text("Choose banner") }
                if (banner != null) TextButton(enabled = !busy, onClick = { banner = null }) { Text("Remove banner") }
            }
            Text("Changes stay in this draft until Save. Images queue for upload when connected.", color = p.ink3, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(display, { display = it }, label = { Text("Display name") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pronouns, { pronouns = it }, label = { Text("Pronouns") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(bio, { editBio(it) }, label = { Text("Bio") }, minLines = 3, enabled = !busy, modifier = Modifier.fillMaxWidth())
            JournalFormatToolbar(bio, !busy) { editBio(it, formatting = true) }
            for (i in values.indices) {
                val def = fields.getJSONObject(i)
                OutlinedTextField(values[i], { value -> values = values.toMutableList().apply { set(i, value) } },
                    label = { Text(def.getString("name")) }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text(if (!def.getBoolean("exists")) "New private field · only our account" else "Existing field privacy is preserved") })
            }
            var advanced by rememberSaveable { mutableStateOf(false) }
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide more details" else "More details") }
            if (advanced) {
                OutlinedTextField(birthday, { birthday = it }, label = { Text("Birthday") }, supportingText = { Text("YYYY-MM-DD or --MM-DD") },
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(sigil, { sigil = it }, label = { Text("Representative characters") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                ColourField(color, { color = it }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                Text("Start a message with one or more members' representative characters, then a space.", color = p.ink3, style = MaterialTheme.typography.bodySmall)
                if (baseline != null) OutlinedButton(enabled = !busy && !dirty, onClick = {
                    actions.launch {
                        busy = true
                        try { chorus.create(if (member?.archived == true) "member.unarchive" else "member.archive", id, JSONObject()); onDone() }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { error = e.message }
                        finally { busy = false }
                    }
                }) { Text(if (member?.archived == true) "Unarchive member" else "Archive member") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
