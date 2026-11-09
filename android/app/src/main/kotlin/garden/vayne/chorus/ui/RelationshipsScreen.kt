package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Api
import garden.vayne.chorus.data.Audience
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.LocalRelationships
import garden.vayne.chorus.data.Member
import garden.vayne.chorus.data.MemberDms
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.ProfileAudience
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * F6.5: [member]'s relationships: those inside our system (with a Message button) and with people
 * elsewhere, each with its type and note; "+" adds one. Everything is the local projection and
 * queued ops; only the names of other systems' members come from the server.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RelationshipsScreen(chorus: Chorus, model: Model, member: Member, onClose: () -> Unit,
    onMessage: ((String, String) -> Unit)?, onOpenProfile: (String) -> Unit) {
    val p = LocalChorusPalette.current
    val actions = rememberCoroutineScope()
    var remote by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var typesOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(chorus.device?.session) {
        try {
            val dev = checkNotNull(chorus.device)
            val rows = Api.call("GET", dev.base, "/relationship-targets", null, dev.session).getJSONArray("items")
            remote = List(rows.length()) { i -> rows.getJSONObject(i).let { row -> row.getString("id") to
                row.optString("display_name").takeUnless { row.isNull("display_name") || it.isBlank() }.orEmpty().ifBlank { row.optString("name") } } }.toMap()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
    }
    val rows = LocalRelationships.rows(model, member.id, remote)
    val inside = rows.filter { it.inside }
    val outside = rows.filter { !it.inside }
    val shown = if (tab == 0) inside else outside
    fun write(kind: String, id: String, payload: JSONObject, after: () -> Unit = {}) {
        if (busy) return
        busy = true; error = null
        actions.launch {
            try { chorus.create(kind, id, payload); after() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not update relationships." }
            finally { busy = false }
        }
    }
    fun message(other: String) {
        val speaker = member.takeIf { !it.archived } ?: return
        actions.launch {
            try { val (space, channel) = MemberDms.open(chorus, speaker, other); onMessage?.invoke(space, channel) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not start the message." }
        }
    }
    Column(Modifier.fillMaxSize().background(p.bg)) {
        Row(Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ChatIconButton(R.drawable.ic_back, "Back", onClose)
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text("Relationships", color = p.ink, style = MaterialTheme.typography.titleMedium)
                Text("${member.shownName} · ${inside.size} inside, ${outside.size} ${if (outside.size == 1) "friend" else "friends"}",
                    color = p.ink3, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            ChatIconButton(R.drawable.ic_plus_circle, "Add relationship") { adding = true }
        }
        UnderlineTabs(listOf("Inside our system" to 0, "Friends" to 0), tab) { tab = it }
        if (error != null) Text(error.orEmpty(), color = p.danger, modifier = Modifier.padding(16.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (shown.isEmpty()) item { EmptyState(R.drawable.ic_users, "No relationships yet", action = "Add relationship", onAction = { adding = true }) }
            items(shown, key = { it.id }) { row ->
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Edit") { editing = row.id }.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val other = row.otherId?.let { model.member(it) }
                    if (other != null) ChatAvatar(other, 48.dp)
                    else Avatar(row.name.take(1).uppercase(), "#7A93B8", 48.dp, ring = false)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(row.name, color = p.ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        row.otherId?.let { id -> model.handle(id) }?.let { Text("@$it", color = p.ink3, fontSize = 13.sp) }
                        Row(Modifier.clip(CircleShape).background(if (row.inside) p.accentSoft else p.surface3)
                            .padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            if (!row.inside) Glyph(R.drawable.ic_globe, p.ink2, 13.dp)
                            Text(row.type, color = if (row.inside) p.accent else p.ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                        row.note?.let { Text(it, color = p.ink2, fontSize = 14.sp, lineHeight = 20.sp) }
                    }
                    if (row.inside && other != null) Row(Modifier.clip(CircleShape).border(1.dp, p.line, CircleShape)
                        .clickable(role = Role.Button) { if (onMessage != null) message(other.id) else onOpenProfile(other.id) }
                        .padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(if (onMessage != null) "Message" else "View", color = p.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
                androidx.compose.material3.HorizontalDivider(color = p.line, thickness = .5.dp)
            }
            item {
                Row(Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, p.line, RoundedCornerShape(14.dp))
                    .clickable(role = Role.Button) { adding = true }.padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Glyph(R.drawable.ic_user_plus, p.accent, 20.dp)
                    Text("Add relationship", color = p.accent, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
    if (adding) AddRelationshipDrawer(chorus, model, member, remote, busy, onDismiss = { adding = false }, onTypes = { typesOpen = true }) { id, payload ->
        write("relationship.set", id, payload) { adding = false }
    }
    editing?.let { id ->
        val raw = model.relationships.find { it.id == id }
        val row = rows.find { it.id == id }
        if (raw == null || row == null) { editing = null; return@let }
        var note by remember(id) { mutableStateOf(raw.note.orEmpty()) }
        var confirm by remember(id) { mutableStateOf(false) }
        Drawer({ editing = null }, row.name, row.type, footer = {
            DrawerButton("Save", { write("relationship.set", id, JSONObject().put("note", note.trim().ifBlank { null } ?: JSONObject.NULL)) { editing = null } }, busy = busy)
            DrawerButton("Remove relationship", { confirm = true }, ButtonKind.Quiet)
        }) {
            ChorusField(note, { note = it.take(300) }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Who can see it", color = p.ink2, modifier = Modifier.weight(1f))
                AudienceChip("Relationship", ProfileAudience.of(ProfileAudience.parse(raw.visibility)), true, model.sharingBuckets,
                    onChange = { a -> write("relationship.set", id, JSONObject().put("visibility", (a ?: Audience.PRIVATE).toJson())) },
                    enabled = !busy, looks = model.bucketLooks)
            }
        }
        if (confirm) ConfirmDrawer("Remove this relationship?", null, "Remove", {
            confirm = false; write("relationship.delete", id, JSONObject()) { editing = null }
        }, { confirm = false }, danger = true)
    }
    if (typesOpen) RelationshipTypesDrawer(chorus, model, busy, onDismiss = { typesOpen = false }) { kind, id, payload -> write(kind, id, payload) }
}

/** Type, person, note and audience: one drawer; new types are made from the type row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddRelationshipDrawer(chorus: Chorus, model: Model, member: Member, remote: Map<String, String>, busy: Boolean,
    onDismiss: () -> Unit, onTypes: () -> Unit, onSave: (String, JSONObject) -> Unit) {
    val p = LocalChorusPalette.current
    var typeId by remember { mutableStateOf(model.relationshipTypes.firstOrNull()?.id.orEmpty()) }
    var target by remember { mutableStateOf("") }
    var external by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var audience by remember { mutableStateOf(Audience.PRIVATE) }
    val account = chorus.device?.accountId
    val people = model.active.filter { it.id != member.id && (it.createdByAccountId == null || it.createdByAccountId == account) }.map { it.id to it.shownName } +
        remote.filterKeys { id -> model.member(id) == null && id != member.id }.map { it.key to it.value }
    @Composable fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
        Text(text, color = if (on) p.accent else p.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.clip(CircleShape).background(if (on) p.accentSoft else p.surface3).clickable(role = Role.RadioButton, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 8.dp))
    }
    val ready = typeId.isNotBlank() && (if (external) label.isNotBlank() else target.isNotBlank())
    Drawer(onDismiss, "Add relationship", footer = {
        DrawerButton("Add relationship", {
            val to = if (external) null else target
            onSave(chorus.newId(), LocalRelationships.linkPayload(member.id, typeId, to, label.trim().takeIf { external && it.isNotBlank() },
                note.trim().ifBlank { null }, audience))
        }, enabled = ready, busy = busy)
    }) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (type in model.relationshipTypes) Chip(type.name, type.id == typeId) { typeId = type.id }
            Row(Modifier.clip(CircleShape).border(1.dp, p.line, CircleShape).clickable(role = Role.Button, onClick = onTypes)
                .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph(R.drawable.ic_plus_circle, p.accent, 16.dp)
                Text(if (model.relationshipTypes.isEmpty()) "New type" else "Types", color = p.accent, fontSize = 14.sp, modifier = Modifier.padding(start = 6.dp))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((id, name) in people) Chip(name, !external && id == target) { external = false; target = id }
            Chip("Outside Chorus", external) { external = true }
        }
        if (external) ChorusField(label, { label = it.take(100) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        ChorusField(note, { note = it.take(300) }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Who can see it", color = p.ink2, modifier = Modifier.weight(1f))
            AudienceChip("New relationship", audience, true, model.sharingBuckets, onChange = { audience = it ?: Audience.PRIVATE },
                enabled = !busy, looks = model.bucketLooks)
        }
    }
}

/** Types: the existing ones (deletable while unused) and a new one, same in both directions or with a reverse name. */
@Composable
private fun RelationshipTypesDrawer(chorus: Chorus, model: Model, busy: Boolean, onDismiss: () -> Unit,
    write: (String, String, JSONObject) -> Unit) {
    val p = LocalChorusPalette.current
    var name by remember { mutableStateOf("") }
    var inverse by remember { mutableStateOf("") }
    var symmetric by remember { mutableStateOf(false) }
    Drawer(onDismiss, "Relationship types", footer = {
        DrawerButton("Create type", {
            write("reltype.set", chorus.newId(), LocalRelationships.typePayload(name.trim(), inverse.trim().ifBlank { null }, symmetric))
            name = ""; inverse = ""; symmetric = false
        }, enabled = !busy && name.isNotBlank())
    }) {
        for (type in model.relationshipTypes) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(listOfNotNull(type.name, type.inverseName?.takeUnless { type.symmetric }).joinToString(" / "), color = p.ink, modifier = Modifier.weight(1f))
            if (model.relationships.none { it.typeId == type.id }) BarIcon(R.drawable.ic_delete, "Delete ${type.name}", p.ink3, 20.dp) {
                if (!busy) write("reltype.delete", type.id, JSONObject())
            }
        }
        ChorusField(name, { name = it.take(60) }, label = { Text("New type") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().clickable(role = Role.Switch) { symmetric = !symmetric }, verticalAlignment = Alignment.CenterVertically) {
            Text("Same both ways", color = p.ink, modifier = Modifier.weight(1f))
            ChorusSwitch(symmetric, { symmetric = it })
        }
        if (!symmetric) ChorusField(inverse, { inverse = it.take(60) }, label = { Text("Reverse name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
}
