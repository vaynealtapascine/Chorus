package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.LocalRelationships
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.designsystem.LocalChorusPalette
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Own-account relationship editor backed by the local projection and queued ops. */
@Composable
internal fun ProfileRelationships(chorus: Chorus, model: Model, memberId: String) {
    val p = LocalChorusPalette.current
    val actions = rememberCoroutineScope()
    val links = LocalRelationships.forMember(model, memberId)
    val candidates = model.active.filter { it.id != memberId &&
        (it.createdByAccountId == null || it.createdByAccountId == chorus.device?.accountId) }
    var typeId by rememberSaveable(memberId) { mutableStateOf("") }
    var targetId by rememberSaveable(memberId) { mutableStateOf("") }
    var external by rememberSaveable(memberId) { mutableStateOf(false) }
    var label by rememberSaveable(memberId) { mutableStateOf("") }
    var note by rememberSaveable(memberId) { mutableStateOf("") }
    var typeName by rememberSaveable(memberId) { mutableStateOf("") }
    var inverse by rememberSaveable(memberId) { mutableStateOf("") }
    var symmetric by rememberSaveable(memberId) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by rememberSaveable(memberId) { mutableStateOf<String?>(null) }
    val chosenType = model.relationshipTypes.find { it.id == typeId } ?: model.relationshipTypes.firstOrNull()
    val chosenTarget = candidates.find { it.id == targetId } ?: candidates.firstOrNull()

    fun write(kind: String, id: String, payload: JSONObject, after: () -> Unit = {}) {
        if (busy) return
        busy = true; error = null
        actions.launch {
            try { chorus.create(kind, id, payload); after() }
            catch (e: Exception) { error = e.message ?: "Could not update relationships." }
            finally { busy = false }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (links.isEmpty()) Text("No relationships yet.", color = p.ink2)
        for (link in links) Row(Modifier.fillMaxWidth().background(p.surface).padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("${link.type} · ${link.target}", color = p.ink)
                if (link.note != null) Text(link.note, color = p.ink2)
            }
            TextButton(enabled = !busy, onClick = { write("relationship.delete", link.id, JSONObject()) }) {
                Text("Remove")
            }
        }
        if (error != null) Text(error.orEmpty(), color = p.danger)

        Column(Modifier.fillMaxWidth().background(p.surface).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Add relationship", color = p.ink, fontWeight = FontWeight.SemiBold)
            if (model.relationshipTypes.isEmpty()) Text("Create a relationship type first.", color = p.ink2)
            else {
                Text("Type", color = p.ink2)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(model.relationshipTypes, key = { it.id }) { type ->
                        JournalChoice(type.name, type.id == chosenType?.id) { typeId = type.id }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { external = false }) { Text(if (external) "○ Member" else "✓ Member") }
                    TextButton(onClick = { external = true }) { Text(if (external) "✓ Outside Chorus" else "○ Outside Chorus") }
                }
                if (external) OutlinedTextField(label, { label = it.take(100) },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                else if (candidates.isEmpty()) Text("Add another member to link a relationship.", color = p.ink2)
                else LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(candidates, key = { it.id }) { member ->
                        JournalChoice(member.shownName, member.id == chosenTarget?.id) { targetId = member.id }
                    }
                }
                OutlinedTextField(note, { note = it.take(300) }, label = { Text("Note (optional)") },
                    modifier = Modifier.fillMaxWidth())
                Text("Relationships are private to this account.", color = p.ink2)
                TextButton(enabled = !busy && chosenType != null && (if (external) label.isNotBlank() else chosenTarget != null),
                    onClick = {
                        val type = chosenType ?: return@TextButton
                        val target = if (external) null else chosenTarget?.id
                        write("relationship.set", chorus.newId(), LocalRelationships.linkPayload(memberId, type.id,
                            target, label.trim().takeIf { external && it.isNotBlank() }, note.trim().ifBlank { null })) {
                            label = ""; note = ""
                        }
                    }) { Text("Add relationship") }
            }
        }

        Column(Modifier.fillMaxWidth().background(p.surface).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Relationship types", color = p.ink, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(typeName, { typeName = it.take(60) }, label = { Text("Type name") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { symmetric = !symmetric }) { Text(if (symmetric) "✓ Same in both directions" else "○ Same in both directions") }
            if (!symmetric) OutlinedTextField(inverse, { inverse = it.take(60) },
                label = { Text("Reverse name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton(enabled = !busy && typeName.isNotBlank(), onClick = {
                val name = typeName.trim()
                write("reltype.set", chorus.newId(), LocalRelationships.typePayload(name,
                    inverse.trim().ifBlank { null }, symmetric)) {
                    typeName = ""; inverse = ""; symmetric = false
                }
            }) { Text("Create type") }
            for (type in model.relationshipTypes) Row(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(listOfNotNull(type.name, type.inverseName?.takeUnless { type.symmetric }).joinToString(" / "),
                    color = p.ink, modifier = Modifier.weight(1f))
                TextButton(enabled = !busy && model.relationships.none { it.typeId == type.id },
                    onClick = { write("reltype.delete", type.id, JSONObject()) }) { Text("Delete") }
            }
        }
    }
}
