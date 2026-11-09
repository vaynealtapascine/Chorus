package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.FrontingDisplay
import garden.vayne.chorus.data.MemberStatus
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.Subject
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import kotlinx.coroutines.launch

/** A member's state (F3.10): any of the common ones, the system's own states or their own words, and a note. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemberStatePage(chorus: Chorus, model: Model, s: Subject, onClose: () -> Unit) {
    val p = LocalChorusPalette.current
    val actions = rememberCoroutineScope()
    val current = model.accountPrefs.memberStatus[s.id]
    var picked by remember { mutableStateOf(current?.tags.orEmpty()) }
    var offered by remember { mutableStateOf(FrontingDisplay.statusChoices(model.states, current)) }
    var note by remember { mutableStateOf(current?.note.orEmpty()) }
    var clear by remember { mutableStateOf(current?.clearOnLeave ?: true) }
    var typing by remember { mutableStateOf(false) }
    var words by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val ready = picked.isNotEmpty() || note.isNotBlank()
    fun write(next: MemberStatus?) {
        if (busy) return
        busy = true
        actions.launch {
            try { chorus.create("pref.set", null, FrontingDisplay.statusPayload(s.id, next, model.states)); onClose() }
            catch (e: Exception) { error = e.message ?: "Couldn’t save. Try again." } finally { busy = false }
        }
    }
    fun set() { if (ready) write(MemberStatus(picked, note.trim().ifEmpty { null }, System.currentTimeMillis(), clear)) }
    fun addWords() {
        val tag = words.trim().take(FrontingDisplay.MAX_STATUS_TAG)
        if (tag.isNotEmpty()) { if (tag !in offered) offered = offered + tag; if (tag !in picked) picked = picked + tag }
        words = ""; typing = false
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(p.bg).imePadding()) {
            SubpageBar("${s.name}’s state", null, onClose) { BarIcon(R.drawable.ic_check, "Set state", if (ready) p.accent else p.ink3, onClick = ::set) }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Avatar(s.glyph, s.color, 56.dp, avatarBlob = s.avatarBlob, ring = false)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(s.name, color = p.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        if (picked.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            picked.forEach { Text(FrontingDisplay.statusLabel(it, model.states), color = p.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.clip(CircleShape).background(p.accentSoft).padding(horizontal = 10.dp, vertical = 3.dp)) }
                        }
                    }
                }
                SectionLabel("How are things?")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    offered.forEach { tag ->
                        val on = tag in picked
                        Row(Modifier.clip(CircleShape).background(if (on) p.accentSoft else p.surface2)
                            .clickable(role = Role.Checkbox) { picked = if (on) picked - tag else picked + tag }
                            .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (on) Glyph(R.drawable.ic_check, p.accent, 14.dp)
                            Text(FrontingDisplay.statusLabel(tag, model.states), color = if (on) p.accent else p.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    Row(Modifier.clip(CircleShape).background(p.surface2).clickable(role = Role.Button) { typing = true }
                        .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Glyph(R.drawable.ic_plus, p.ink3, 14.dp)
                        Text("Custom", color = p.ink2, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
                if (typing) ChorusField(words, { words = it.take(FrontingDisplay.MAX_STATUS_TAG) }, Modifier.fillMaxWidth(), label = { Text("Custom") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { addWords() }))
                ChorusField(note, { note = it.take(FrontingDisplay.MAX_STATUS_NOTE) }, Modifier.fillMaxWidth(), label = { Text("Note (optional)") }, minLines = 1, maxLines = 3)
                SectionLabel("Options")
                FormCard { ToggleRow("Clear when ${s.name} stops fronting", clear) { clear = it } }
                error?.let { Text(it, color = p.danger, fontSize = 14.sp) }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DrawerButton("Clear", { write(null) }, ButtonKind.Neutral, enabled = current != null, modifier = Modifier.weight(.3f))
                DrawerButton("Set state", ::set, enabled = ready, busy = busy, modifier = Modifier.weight(.7f))
            }
        }
    }
}
