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
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.InspectionDialogContent
import garden.vayne.chorus.data.*
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
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
    val owner = chorus.editorOwner
    val editor = rememberDurableEditor(chorus, "profile:${member?.id ?: "new"}", ProfileEditorDraft::encode, ProfileEditorDraft::decode) {
        ProfileEditorDraft.open(member?.id ?: chorus.newId(), member, model, account)
    }
    val opening = editor.value
    if (opening == null) { DraftLoading(editor.error, editor::retry, onDone); return }
    val id = opening.id
    val baseline = opening.openingMember
    val baselineBio = opening.bio.opening.text
    val fields = remember(opening.fieldSnapshot) { JSONArray(opening.fieldSnapshot) }
    var values by editor.lens({ it.values }, { draft, value -> draft.copy(values = value) })
    fun field(key: String) = editor.lens({ it.fields[key].orEmpty() }, { draft, value -> draft.copy(fields = draft.fields + (key to value)) })
    fun nullableField(key: String) = editor.lens({ it.fields[key] }, { draft, value -> draft.copy(fields = draft.fields + (key to value)) })
    var name by field("name")
    var display by field("display")
    var pronouns by field("pronouns")
    var sigil by field("sigil")
    var color by field("color")
    var birthday by field("birthday")
    var avatar by nullableField("avatar")
    var banner by nullableField("banner")
    // who can open the profile, and each part's own audience (D-101), as JSON text
    var profileAudience by nullableField("visibility")
    var parts by nullableField("field_visibility")
    val bioEditor = rememberRichEditor(editor, opening.bio, "bio") { draft, history -> draft.copy(bio = history) }
    val bio = bioEditor.value
    var cropPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pickingBanner by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    val dirty = name != baseline?.name.orEmpty() || display != baseline?.displayName.orEmpty() ||
        pronouns != baseline?.pronouns.orEmpty() || sigil != baseline?.sigils?.firstOrNull().orEmpty() ||
        color != (baseline?.color ?: "#C0694E") || bioEditor.history.dirty || birthday != baseline?.birthday.orEmpty() ||
        avatar != baseline?.avatarBlob || banner != baseline?.bannerBlob || values.indices.any { values[it] != fields.getJSONObject(it).getString("value") } ||
        ProfileAudience.parse(profileAudience)?.toString() != ProfileAudience.parse(baseline?.visibility)?.toString() && profileAudience != null ||
        ProfileAudience.parse(parts)?.toString() != ProfileAudience.parse(baseline?.fieldVisibility)?.toString() && parts != null
    fun close() { if (!busy) { if (dirty) discard = true else {
        try { editor.finish(); onDone() } catch (e: Exception) { error = "Could not close this draft. Try again." }
    } } }
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
    if (discard) ConfirmDrawer("Discard profile changes?", "Your saved profile will stay as it was.", "Discard changes",
        { try { editor.finish(); onDone() } catch (e: Exception) { error = "Could not discard this draft. Try again."; discard = false } },
        { discard = false }, danger = true, cancel = "Keep editing")

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = !busy, onClick = ::close) { Text("Cancel") }
            Text(if (baseline == null) "New member" else "Edit profile", color = p.ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Button(enabled = !busy, onClick = {
                error = null
                actions.launch {
                    try {
                        busy = true
                        bioEditor.capture()
                        val saved = requireNotNull(editor.value)
                        val patch = withContext(Dispatchers.Default) {
                            val f = saved.fields
                            MemberCustomization.patch(baseline, f["name"].orEmpty(), f["display"].orEmpty(), f["pronouns"].orEmpty(), f["sigil"].orEmpty(), f["color"].orEmpty(),
                                saved.bio.current.text, baselineBio, f["avatar"], f["banner"], f["birthday"].orEmpty(),
                                names = ChatEdit.names(baseline?.description.orEmpty(), baseline?.descriptionEntities ?: "[]", model), bioDraft = saved.bio.legacyState(), bioDirty = saved.bio.dirty,
                                visibility = f["visibility"], fieldVisibility = f["field_visibility"])
                        }
                        check(chorus.device?.accountId == account) { "The account changed. Reopen this profile in its account." }
                        chorus.saveMemberProfile(id, baseline != null, patch, fields, saved.values, expectedEditorOwner = owner)
                        if (avatar != null || banner != null) UploadWork.enqueue(ctx)
                        editor.finish()
                        onDone()
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "Could not save the profile. Try again." }
                    finally { busy = false }
                }
            }) { Text(if (busy) "Saving…" else "Save") }
        }
        (error ?: editor.error)?.let { Text(it, color = p.danger, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ProfileIdentity(baseline?.copy(name = name.ifBlank { "New member" }, displayName = display.ifBlank { null }, pronouns = pronouns.ifBlank { null },
                color = color, sigils = listOf(sigil).filter(String::isNotBlank), avatarBlob = avatar, bannerBlob = banner)
                ?: Member(id, name.ifBlank { "New member" }, display.ifBlank { null }, pronouns.ifBlank { null }, color, listOf(sigil).filter(String::isNotBlank), null, false,
                    avatarBlob = avatar, bannerBlob = banner), chorus)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = { pickingBanner = false; picker.launch("image/*") }) { Text("Choose avatar") }
                if (avatar != null) TextButton(enabled = !busy, onClick = { avatar = null }) { Text("Remove avatar") }
            }

            // ─── the profile: every part with its own audience (D-101) ───
            val liveBuckets = model.sharingBuckets
            val profileNow = ProfileAudience.of(ProfileAudience.parse(profileAudience))
            val closed = profileNow.isPrivate(liveBuckets.map { it.first }.toSet())
            val partsNow = ProfileAudience.parse(parts)
            fun setPart(key: String, a: Audience?) { parts = ProfileAudience.with(partsNow, key, a).toString() }
            @Composable fun partChip(key: String, label: String) = AudienceChip(label, ProfileAudience.effective(ProfileAudience.parse(profileAudience), partsNow, key),
                own = ProfileAudience.own(partsNow, key) != null, buckets = liveBuckets, onChange = { setPart(key, it) },
                inherit = "Same as profile", limited = closed, enabled = !busy, looks = model.bucketLooks)
            HorizontalDivider(color = p.line)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Profile", color = p.ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("Who can see ", color = p.ink3, style = MaterialTheme.typography.bodySmall)
                AudienceChip("${name.ifBlank { "This member" }}'s profile", profileNow, own = true, buckets = liveBuckets,
                    onChange = { profileAudience = (it ?: Audience.PRIVATE).toJson().toString() }, enabled = !busy, looks = model.bucketLooks)
            }
            // the display name follows the profile; the full name is private unless shared (D-126).
            // Following the profile shows no label (UI21): a chip appears where a part has its own audience.
            PartHeader("Display name", {})
            ChorusField(display, { display = it }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Add a display name", color = p.ink3) })
            PartHeader("Full name", {
                if (display.isNotBlank()) AudienceChip("Full name", ProfileAudience.effective(ProfileAudience.parse(profileAudience), partsNow, "name"),
                    own = ProfileAudience.own(partsNow, "name") != null, buckets = liveBuckets, onChange = { setPart("name", it) },
                    limited = closed, enabled = !busy, looks = model.bucketLooks)
            })
            ChorusField(name, { name = it }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            if (baseline != null) MemberHandlesEditor(chorus, model, id, !busy) { error = it }
            PartHeader("Pronouns", { partChip("pronouns", "Pronouns") })
            ChorusField(pronouns, { pronouns = it }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Add pronouns", color = p.ink3) })
            PartHeader("Bio", { partChip("description", "Bio") })
            RichEditorField(bioEditor, "Bio", enabled = !busy, formatting = true)
            PartHeader("Birthday", { partChip("birthday", "Birthday") })
            ChorusField(birthday, { birthday = it }, placeholder = { Text("YYYY-MM-DD", color = p.ink3) },
                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            PartHeader("Banner", { partChip("banner", "Banner") })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = { pickingBanner = true; picker.launch("image/*") }) { Text(if (banner == null) "Choose banner" else "Change banner") }
                if (banner != null) TextButton(enabled = !busy, onClick = { banner = null }) { Text("Remove banner") }
            }
            var settingsFor by remember { mutableStateOf<String?>(null) }
            var addingField by rememberSaveable { mutableStateOf(false) }
            for (i in values.indices) {
                val row = fields.getJSONObject(i)
                val id = row.getString("id")
                // a field removed since opening only stays while it still holds an unsaved change
                if (id in model.profileDetails.unavailableIds && values[i] == row.getString("value")) continue
                // "Website 2" and "3" only once the website before them is filled in (up to three, D-090)
                if (i in 2 until ProfileDetails.slots.size && !row.getBoolean("exists") && values[i].isBlank() && values[i - 1].isBlank()) continue
                val live = model.profileDetails.definitions.find { it.id == id }
                val def = live ?: ProfileDetail(id, row.getString("name"), row.getString("type"), row.optString("visibility", "private"),
                    row.optJSONObject("default_visibility")?.toString() ?: JSONObject().put("mode", "private").toString(),
                    row.optJSONArray("choices")?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty())
                val fallback = ProfileAudience.of(JSONObject(def.defaultVisibility))
                PartHeader(def.name, {
                    AudienceChip(def.name, ProfileAudience.own(partsNow, id) ?: fallback, own = ProfileAudience.own(partsNow, id) != null,
                        buckets = liveBuckets, onChange = { setPart(id, it) }, inherit = "Field default", limited = closed, enabled = !busy, looks = model.bucketLooks)
                }, onSettings = if (live != null) ({ settingsFor = id }) else null)
                FieldValueEditor(chorus, def, row.getString("type"), values[i], !busy,
                    onValue = { value -> values = values.toMutableList().apply { set(i, value) } }, onError = { error = it })
            }
            TextButton(enabled = !busy, onClick = { addingField = true }) { Text("+ Add a field") }
            if (addingField) AddFieldDialog(chorus, model, onAdded = { def ->
                editor.value?.let { editor.change(it.withField(def)) }
                addingField = false
            }, onDismiss = { addingField = false })
            settingsFor?.let { id -> model.profileDetails.definitions.find { it.id == id }?.let { def ->
                FieldSettingsDialog(chorus, model, def) { settingsFor = null }
            } ?: run { settingsFor = null } }
            HorizontalDivider(color = p.line)
            var advanced by rememberSaveable { mutableStateOf(false) }
              if (model.isPerson) ColourField(color, { color = it }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
            if (!model.isPerson) TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide more details" else "More details") }
            if (advanced && !model.isPerson) {
                ChorusField(sigil, { sigil = it }, label = { Text("Representative characters") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                if (!model.isPerson) ColourField(color, { color = it }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                // who hears when this member fronts (NOTIFICATIONS §2.4): saved as it's chosen, like the web
                model.member(id)?.let { saved ->
                    MemberAnnounce(model, saved, !busy) { policy ->
                        actions.launch {
                            try { chorus.create("member.set", id, JSONObject().put("notify_policy", policy), expectedEditorOwner = owner) }
                            catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) { error = e.message }
                        }
                    }
                }
            }
            // archiving sits at the foot of the editor, not under More details (UI21); it asks first
            if (baseline != null && !model.isPerson) {
                var confirmArchive by remember { mutableStateOf(false) }
                fun archive() = actions.launch {
                    busy = true
                    try { chorus.create(if (member?.archived == true) "member.unarchive" else "member.archive", id, JSONObject(), expectedEditorOwner = owner); editor.finish(); onDone() }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { error = e.message }
                    finally { busy = false }
                }
                val shown = display.ifBlank { name }.ifBlank { "this member" }
                MenuRow(if (member?.archived == true) "Restore $shown" else "Archive $shown", R.drawable.ic_archive,
                    danger = member?.archived != true, enabled = !busy && !dirty) {
                    if (member?.archived == true) archive() else confirmArchive = true
                }
                if (confirmArchive) ConfirmDrawer("Archive $shown?", null, "Archive", { confirmArchive = false; archive() },
                    { confirmArchive = false }, danger = true, icon = R.drawable.ic_archive)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
