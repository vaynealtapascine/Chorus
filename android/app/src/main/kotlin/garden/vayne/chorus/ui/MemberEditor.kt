package garden.vayne.chorus.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.*
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Edit member (UI21, Figma F4.2): picture and colour, Name, Pronouns and Display name, the
 * description, then More rows (Groups, Who can see, Custom fields, Handles, Banner, and the
 * representative characters), and Archive at the foot, which asks first (F4.5). A saved opening
 * snapshot protects every untouched field across sync and recreation; Who can see and Custom
 * fields are pages of the same draft and save with it. Groups and handles write as they change.
 */
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
    var cropPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pickingBanner by rememberSaveable { mutableStateOf(false) }
    // "" the form, "audience" Who can see (F4.3), "fields" Custom fields
    var page by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    val saved = model.member(id)
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
    /** Writes the draft; true when it saved. */
    suspend fun persist(): Boolean = try {
        busy = true; error = null
        bioEditor.capture()
        val draft = requireNotNull(editor.value)
        val patch = withContext(Dispatchers.Default) {
            val f = draft.fields
            MemberCustomization.patch(baseline, f["name"].orEmpty(), f["display"].orEmpty(), f["pronouns"].orEmpty(), f["sigil"].orEmpty(), f["color"].orEmpty(),
                draft.bio.current.text, baselineBio, f["avatar"], f["banner"], f["birthday"].orEmpty(),
                names = ChatEdit.names(baseline?.description.orEmpty(), baseline?.descriptionEntities ?: "[]", model), bioDraft = draft.bio.legacyState(), bioDirty = draft.bio.dirty,
                visibility = f["visibility"], fieldVisibility = f["field_visibility"])
        }
        check(chorus.device?.accountId == account) { "The account changed. Reopen this profile in its account." }
        chorus.saveMemberProfile(id, baseline != null, patch, fields, draft.values, expectedEditorOwner = owner)
        if (avatar != null || banner != null) UploadWork.enqueue(ctx)
        true
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (e: Exception) { error = e.message ?: "Could not save the profile. Try again."; false }
    finally { busy = false }
    fun save() = actions.launch { if (persist()) { editor.finish(); onDone() } }
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
    if (discard) ConfirmDrawer("Discard changes?", null, "Discard changes",
        { try { editor.finish(); onDone() } catch (e: Exception) { error = "Could not discard this draft. Try again."; discard = false } },
        { discard = false }, danger = true, cancel = "Keep editing")

    val shown = display.ifBlank { name }.ifBlank { if (baseline == null) "New member" else "this member" }
    val partsNow = ProfileAudience.parse(parts)
    val profileNow = ProfileAudience.of(ProfileAudience.parse(profileAudience))
    val gone = model.profileDetails.unavailableIds
    val customFields = remember(fields, gone) { MemberSharing.fields(fields, gone.toSet()) }
    if (page == "audience") {
        MemberAudiencePage(chorus, model, shown, profileNow, partsNow, customFields, saved, !busy,
            onProfile = { profileAudience = it.toJson().toString() }, onParts = { parts = it?.toString() },
            onAnnounce = { policy -> actions.launch {
                try { chorus.create("member.set", id, JSONObject().put("notify_policy", policy), expectedEditorOwner = owner); error = null }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { error = e.message }
            } }, onBack = { page = "" })
        return
    }
    if (page == "fields") {
        BackHandler { page = "" }
        Column(Modifier.fillMaxSize().background(p.bg).imePadding()) {
            SubpageBar("Custom fields", shown, { page = "" }) { BarIcon(R.drawable.ic_check, "Done", p.accent) { page = "" } }
            (error ?: editor.error)?.let { Text(it, color = p.danger, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CustomFieldsForm(chorus, model, editor, fields, values, { values = it }, birthday, { birthday = it }, profileAudience, partsNow,
                    { parts = it.toString() }, !busy) { error = it }
            }
        }
        return
    }

    var groupsOpen by remember { mutableStateOf(false) }
    var handlesOpen by remember { mutableStateOf(false) }
    var bannerMenu by remember { mutableStateOf(false) }
    var sigilOpen by remember { mutableStateOf(false) }
    var customColour by remember { mutableStateOf(false) }
    var confirmArchive by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(p.bg).imePadding()) {
        SubpageBar(if (baseline == null) "New member" else "Edit member", display.ifBlank { name }.ifBlank { null }, ::close) {
            BarIcon(R.drawable.ic_check, "Save", if (busy) p.ink3 else p.accent) { if (!busy) save() }
        }
        (error ?: editor.error)?.let { Text(it, color = p.danger, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.clip(CircleShape).clickable(enabled = !busy, onClickLabel = "Change picture") { pickingBanner = false; picker.launch("image/*") }) {
                    Avatar(sigil.ifBlank { name.take(1).uppercase().ifBlank { "?" } }, color, 84.dp, avatarBlob = avatar, ring = false)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton("Change picture", icon = R.drawable.ic_camera, enabled = !busy) { pickingBanner = false; picker.launch("image/*") }
                    if (avatar != null) Text("Remove", color = p.ink3, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !busy, role = Role.Button) { avatar = null }.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
            // F4.2's eight colours; the last circle is any colour
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val palette = BucketControls.swatches.take(7)
                for (c in palette) {
                    val on = color.equals(c, ignoreCase = true)
                    Box(Modifier.size(34.dp).clip(CircleShape).background(tonesOf(c).ring).border(2.dp, if (on) p.ink else p.bg, CircleShape)
                        .clickable(enabled = !busy, role = Role.RadioButton) { color = c }.semantics { contentDescription = "Colour $c${if (on) ", chosen" else ""}" })
                }
                val custom = palette.none { it.equals(color, ignoreCase = true) }
                Box(Modifier.size(34.dp).clip(CircleShape).background(if (custom) ColourValue.rgb(color) else p.surface2)
                    .border(2.dp, if (custom) p.ink else p.line, CircleShape)
                    .clickable(enabled = !busy, role = Role.Button) { customColour = true }.semantics { contentDescription = "Another colour" },
                    contentAlignment = Alignment.Center) { if (!custom) Glyph(R.drawable.ic_plus, p.ink2, 16.dp) }
            }
            ChorusField(name, { name = it }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true, label = { Text("Name") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChorusField(pronouns, { pronouns = it }, Modifier.weight(1f), enabled = !busy, singleLine = true, label = { Text("Pronouns") })
                ChorusField(display, { display = it }, Modifier.weight(1f), enabled = !busy, singleLine = true, label = { Text("Display name") })
            }
            RichEditorField(bioEditor, "Description", enabled = !busy, formatting = true)

            SectionLabel("More", Modifier.padding(top = 4.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))) {
                if (saved != null && !model.isPerson) EditorRow("Groups", MemberDirectory.groupsOf(model, id).joinToString(", ") { model.groupPath(it) }.ifEmpty { "None" },
                    R.drawable.ic_users, !busy) { groupsOpen = true }
                val closed = profileNow.isPrivate(model.sharingBuckets.map { it.first }.toSet())
                val partsShared = MemberSharing.partGroups(customFields).filter { g ->
                    g.keys.any { k -> !MemberSharing.effective(profileNow, partsNow, k, customFields.find { it.id == k }).isPrivate(model.sharingBuckets.map { it.first }.toSet()) }
                }.map { it.title.lowercase() }
                val label = profileNow.label(model.sharingBuckets.toMap()).let { if (model.isPerson && it == "Only us") "Only me" else it }
                EditorRow("Who can see ${shown}", if (closed || partsShared.isEmpty()) label else "$label · ${partsShared.joinToString(", ")} shared",
                    R.drawable.ic_shield, !busy) { page = "audience" }
                val filled = (listOfNotNull("Birthday".takeIf { birthday.isNotBlank() }) + (0 until fields.length()).mapNotNull { i ->
                    fields.getJSONObject(i).takeIf { values.getOrNull(i)?.isNotBlank() == true }?.getString("name") }).joinToString(", ")
                EditorRow("Custom fields", filled.ifEmpty { "Birthday, ${customFields.size} more" }, R.drawable.ic_format_list_ul, !busy) { page = "fields" }
                if (baseline != null) model.memberHandles[id]?.let { h ->
                    EditorRow("Handles", "@${saved?.handles?.firstOrNull() ?: h.default}", R.drawable.ic_at_sign, !busy) { handlesOpen = true }
                }
                EditorRow("Banner", if (banner == null) "None" else "Set", R.drawable.ic_image, !busy) {
                    if (banner == null) { pickingBanner = true; picker.launch("image/*") } else bannerMenu = true
                }
                if (!model.isPerson) EditorRow("Representative characters", sigil.ifBlank { "None" }, R.drawable.ic_hash, !busy) { sigilOpen = true }
            }
            // archiving asks first (F4.5); with unsaved changes it saves them first
            if (baseline != null && !model.isPerson) {
                val restoring = member?.archived == true
                Text(if (restoring) "Restore $shown" else "Archive $shown", color = if (restoring) p.accent else p.danger, fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = !busy, role = Role.Button) {
                        if (restoring) actions.launch {
                            if (!dirty || persist()) try {
                                chorus.create("member.unarchive", id, JSONObject(), expectedEditorOwner = owner); editor.finish(); onDone()
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = e.message }
                        } else confirmArchive = true
                    }.padding(vertical = 12.dp))
            }
            Spacer(Modifier.height(12.dp))
        }
        Box(Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 16.dp, vertical = 10.dp)) {
            DrawerButton(if (busy) "Saving…" else "Save", { save() }, enabled = !busy, busy = busy)
        }
    }

    if (customColour) ColourPicker(color, onDismiss = { customColour = false }, onApply = { color = it; customColour = false })
    if (groupsOpen && saved != null) MemberGroupsDrawer(chorus, model, saved) { groupsOpen = false }
    if (handlesOpen) Drawer({ handlesOpen = false }, "Handles", shown, footer = { DrawerButton("Done", { handlesOpen = false }) }) {
        MemberHandlesEditor(chorus, model, id, !busy) { error = it }
    }
    MenuDrawer(bannerMenu, { bannerMenu = false }, "Banner") {
        MenuRow("Change banner", R.drawable.ic_image) { bannerMenu = false; pickingBanner = true; picker.launch("image/*") }
        MenuRow("Remove banner", R.drawable.ic_delete, danger = true) { bannerMenu = false; banner = null }
    }
    if (sigilOpen) {
        var draft by remember { mutableStateOf(sigil) }
        Drawer({ sigilOpen = false }, "Representative characters", shown, footer = {
            DrawerButton("Done", { sigil = draft.trim(); sigilOpen = false })
        }) {
            ChorusField(draft, { draft = it.take(8) }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("e.g. 🦊") })
        }
    }
    if (confirmArchive) ConfirmDrawer("Archive $shown?",
        "$shown stays in your history, chats and posts, and can be restored any time. They leave the switcher and member list.",
        "Archive", {
            confirmArchive = false
            actions.launch {
                if (!dirty || persist()) try {
                    chorus.create("member.archive", id, JSONObject(), expectedEditorOwner = owner); editor.finish(); onDone()
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = e.message }
            }
        }, { confirmArchive = false }, danger = true, icon = R.drawable.ic_history)
}

/** One of F4.2's More rows: an icon, a title, the value under it, a chevron. */
@Composable
private fun EditorRow(title: String, value: String, icon: Int, enabled: Boolean, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Glyph(icon, p.ink2, 22.dp)
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) p.ink else p.ink3, fontSize = 16.sp, lineHeight = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, color = p.ink3, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Glyph(R.drawable.ic_chevron_right, p.ink3, 18.dp)
    }
}

/** The member's birthday and custom fields, each with its own audience (D-101), and adding a field. */
@Composable
private fun CustomFieldsForm(chorus: Chorus, model: Model, editor: DurableEditor<ProfileEditorDraft>, fields: JSONArray, values: List<String>,
    onValues: (List<String>) -> Unit, birthday: String, onBirthday: (String) -> Unit, profileAudience: String?, partsNow: Any?,
    onParts: (JSONObject) -> Unit, enabled: Boolean, onError: (String?) -> Unit) {
    val p = LocalChorusPalette.current
    val liveBuckets = model.sharingBuckets
    val closed = ProfileAudience.of(ProfileAudience.parse(profileAudience)).isPrivate(liveBuckets.map { it.first }.toSet())
    fun setPart(key: String, a: Audience?) = onParts(ProfileAudience.with(partsNow, key, a))
    PartHeader("Birthday", {
        AudienceChip("Birthday", ProfileAudience.effective(ProfileAudience.parse(profileAudience), partsNow, "birthday"),
            own = ProfileAudience.own(partsNow, "birthday") != null, buckets = liveBuckets, onChange = { setPart("birthday", it) },
            inherit = "Same as profile", limited = closed, enabled = enabled, looks = model.bucketLooks)
    })
    ChorusField(birthday, onBirthday, placeholder = { Text("YYYY-MM-DD", color = p.ink3) }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
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
                buckets = liveBuckets, onChange = { setPart(id, it) }, inherit = "Field default", limited = closed, enabled = enabled, looks = model.bucketLooks)
        }, onSettings = if (live != null) ({ settingsFor = id }) else null)
        FieldValueEditor(chorus, def, row.getString("type"), values[i], enabled,
            onValue = { value -> onValues(values.toMutableList().apply { set(i, value) }) }, onError = onError)
    }
    DashedAddRow("Add a field") { addingField = true }
    if (addingField) AddFieldDialog(chorus, model, onAdded = { def ->
        editor.value?.let { editor.change(it.withField(def)) }
        addingField = false
    }, onDismiss = { addingField = false })
    settingsFor?.let { id -> model.profileDetails.definitions.find { it.id == id }?.let { def ->
        FieldSettingsDialog(chorus, model, def) { settingsFor = null }
    } ?: run { settingsFor = null } }
}
