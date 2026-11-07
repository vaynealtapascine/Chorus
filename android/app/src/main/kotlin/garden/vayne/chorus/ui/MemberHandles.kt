package garden.vayne.chorus.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Handles
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.designsystem.LocalChorusPalette
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * A member's handles (D-126; web MemberEditor's Handles section): the reserved default, the ones
 * they claim (the first is shown), and a box to claim another. Saved as chosen, like the announce
 * setting; the server says if another account took a handle first (a sync issue).
 */
@Composable
internal fun MemberHandlesEditor(chorus: Chorus, model: Model, memberId: String, enabled: Boolean, onError: (String?) -> Unit) {
    val p = LocalChorusPalette.current
    val actions = rememberCoroutineScope()
    val owner = chorus.editorOwner
    val claimed = model.member(memberId)?.handles.orEmpty()
    val handles = model.memberHandles[memberId] ?: return
    var claim by remember(memberId) { mutableStateOf("") }
    var problem by remember(memberId) { mutableStateOf<String?>(null) }
    fun save(list: List<String>) = actions.launch {
        try { chorus.create("member.set", memberId, JSONObject().put("handles", JSONArray(list)), expectedEditorOwner = owner); onError(null) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { onError(e.message) }
    }
    fun add() {
        val h = Handles.normalize(claim)
        problem = when {
            h.isEmpty() -> return
            !Handles.valid(h) -> "Use 2–32 lowercase letters, digits or _."
            h in claimed -> "@$h is already theirs."
            Handles.claimedHere(model, h, memberId) != null -> "@$h belongs to ${Handles.claimedHere(model, h, memberId)}."
            claimed.size >= Handles.MAX_CLAIMS -> "A member can claim up to ${Handles.MAX_CLAIMS} handles."
            else -> null
        }
        if (problem == null) { save(claimed + h); claim = "" }
    }
    HorizontalDivider(color = p.line)
    Text("Handles", color = p.ink, style = MaterialTheme.typography.titleMedium)
    @Composable fun HandleRow(handle: String, note: String?, shown: Boolean, trailing: @Composable RowScope.() -> Unit = {}) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("@$handle", color = p.ink, fontWeight = FontWeight.SemiBold)
                if (note != null || shown) Text(listOfNotNull(if (shown) "Shown" else null, note).joinToString(" · "),
                    color = if (shown) p.accent else p.ink3, style = MaterialTheme.typography.bodySmall)
            }
            trailing()
        }
    }
    claimed.forEachIndexed { i, h ->
        HandleRow(h, null, i == 0) {
            if (i > 0) TextButton(enabled = enabled, onClick = { save(listOf(h) + claimed.filter { it != h }) }) { Text("Show first") }
            TextButton(enabled = enabled, onClick = { save(claimed.filter { it != h }) }) { Text("Remove") }
        }
    }
    HandleRow(handles.default, if (model.isPerson) "your account" else "reserved for them", claimed.isEmpty())
    if (claimed.size < Handles.MAX_CLAIMS) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChorusField(claim, { claim = it; problem = null }, Modifier.weight(1f), enabled = enabled, singleLine = true,
            label = { Text("Claim a handle") }, prefix = { Text("@") }, isError = problem != null,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }))
        Button(enabled = enabled && claim.isNotBlank(), onClick = ::add) { Text("Claim") }
    }
    problem?.let { Text(it, color = p.danger, style = MaterialTheme.typography.bodySmall) }
    Text(if (model.isPerson) "A handle you claim is yours alone on this server; the first one is what friends see."
        else "@${handles.default} is made from their display name, subsystem and your system, and stays reserved for them. " +
            "A claimed handle is theirs alone on this server; the first one is what friends see, so claiming one also keeps their subsystem out of sight.",
        color = p.ink3, style = MaterialTheme.typography.bodySmall)
}
