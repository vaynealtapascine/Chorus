package garden.vayne.chorus.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Entry
import garden.vayne.chorus.data.Front
import garden.vayne.chorus.data.FrontScene
import garden.vayne.chorus.data.FrontingDisplay
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.NamedHistory
import garden.vayne.chorus.data.Subject
import garden.vayne.chorus.designsystem.HankenGrotesk
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Log a past switch (Figma F3.5): who was there, on which layer, from when until when. It is
 * written as history only: the stretch starts at From and, unless something was already recorded
 * in between, the earlier state comes back at Until, so who's fronting now never changes.
 */
@Composable
fun LogPastSwitch(chorus: Chorus, model: Model, initialFront: String, history: NamedHistory?, onClose: () -> Unit) {
    val p = LocalChorusPalette.current
    val ctx = LocalContext.current
    val actions = rememberCoroutineScope()
    val account = chorus.device?.accountId.orEmpty()
    val named = model.namedFronts
    val configured = named != null
    var frontId by remember { mutableStateOf(initialFront) }
    val scene = FrontScene.build(model, frontId, emptyList(), account)
    val layers = scene.all
    val now = System.currentTimeMillis()
    var from by remember { mutableStateOf(now - 2 * 3_600_000L) }
    var until by remember { mutableStateOf(now - 3_600_000L) }
    val who = remember { mutableStateListOf<Entry>() }
    var note by remember { mutableStateOf("") }
    var frontMenu by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val loaded by produceState(history?.takeIf { it.frontId == frontId }, frontId) {
        if (configured && value?.frontId != frontId) value = runCatching { chorus.frontHistory(frontId) }.getOrNull()
    }
    val ready = !configured || loaded?.frontId == frontId
    // the saved states of this front, oldest first: what was recorded at or before a time
    val states = if (configured) loaded?.takeIf { it.frontId == frontId }?.saved.orEmpty().filter { !it.retracted }
        .map { it.occurredAt to it.resultingFront } else model.switches.filter { !it.retracted }.map { it.occurredAt to it.resultingFront }
    fun stateAt(t: Long) = states.filter { it.first <= t }.maxByOrNull { it.first }?.second.orEmpty()
    val time = DateFormat.getTimeInstance(DateFormat.SHORT)
    fun label(t: Long): String {
        val today = Calendar.getInstance(); val c = Calendar.getInstance().apply { timeInMillis = t }
        val sameDay = today.get(Calendar.YEAR) == c.get(Calendar.YEAR) && today.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)
        return if (sameDay) "Today · ${time.format(Date(t))}" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(t))
    }
    fun pick(t: Long, set: (Long) -> Unit) {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        DatePickerDialog(ctx, { _, y, m, d ->
            TimePickerDialog(ctx, { _, h, min -> c.set(y, m, d, h, min, 0); c.set(Calendar.MILLISECOND, 0); set(c.timeInMillis) },
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(ctx)).show()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }
    fun save() {
        error = when {
            who.isEmpty() -> "Add who was there."
            from >= until -> "From has to be before Until."
            until > System.currentTimeMillis() -> "Until can't be in the future."
            !ready -> "Still loading this front's history. Try again in a moment."
            else -> null
        }
        if (error != null) return
        busy = true
        actions.launch {
            try {
                val target = if (configured) frontId else null
                val inner = layers.firstOrNull { !it.separate }?.id
                val entries = who.mapIndexed { i, e -> e.copy(isPrimary = e.level == inner && who.indexOfFirst { it.level == inner } == i) }
                val between = states.any { it.first in (from + 1)..until }
                val restore = stateAt(until)
                if (target != null) {
                    chorus.create("front.change", target, Front.changePayload(entries, note, at = from, historyOnly = true))
                    if (!between) chorus.create("front.change", target, Front.changePayload(restore, at = until, historyOnly = true))
                } else {
                    chorus.create("front.switch", chorus.newId(), Front.switchPayload(entries, note, "silent"), userTime = from)
                    if (!between) chorus.create("front.switch", chorus.newId(), Front.switchPayload(restore, notify = "silent"), userTime = until)
                }
                onClose()
            } catch (e: Exception) { error = e.message ?: "Could not save."; busy = false }
        }
    }
    if (picking) PickSubjects(model, account, who.map { it.subjectType to it.subjectId }.toSet()) { s ->
        picking = false
        if (s != null) who.add(Entry(s.type, s.id, layers.firstOrNull { !it.separate }?.id ?: "front", false))
    }

    Column(Modifier.fillMaxSize().background(p.bg)) {
        SubpageBar("Log a past switch", frontName(named?.fronts?.get(frontId)), onClose) {
            BarIcon(R.drawable.ic_check, "Save to history", p.accent) { save() }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.accentSoft).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Glyph(R.drawable.ic_history, p.accent, 18.dp)
                Text("Logging the past doesn't change who's fronting now.", color = p.ink, fontSize = 13.sp, lineHeight = 18.sp)
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface)) {
                Box {
                    PastRow(R.drawable.ic_target, "Front", frontName(named?.fronts?.get(frontId))) { if (configured) frontMenu = true }
                    DropdownMenu(frontMenu, { frontMenu = false }) {
                        shownFronts(model, account).forEach { f -> DropdownMenuItem({ Text(frontName(f)) }, { frontId = f.id; who.clear(); frontMenu = false }) }
                    }
                }
                Spacer(Modifier.fillMaxWidth().height(1.dp).background(p.line))
                PastRow(R.drawable.ic_clock, "From", label(from)) { pick(from) { from = it } }
                Spacer(Modifier.fillMaxWidth().height(1.dp).background(p.line))
                PastRow(R.drawable.ic_clock, "Until", label(until)) { pick(until) { until = it } }
            }
            SectionLabel("Who was there")
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface)) {
                who.forEachIndexed { i, e -> androidx.compose.runtime.key(e.subjectType, e.subjectId) {
                    val s = model.subject(e.subjectType, e.subjectId) ?: return@key
                    val layer = layers.firstOrNull { it.id == e.level }
                    var open by remember(e.subjectId) { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Avatar(s.glyph, s.color, 40.dp, avatarBlob = s.avatarBlob, ring = false)
                        Text(s.name, color = p.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Box {
                            val ink = layerInk(layer?.style ?: FrontingDisplay.Style.Faint, parseHex(layer?.color), p)
                            Row(Modifier.clip(RoundedCornerShape(8.dp)).background(if (layer?.style == FrontingDisplay.Style.Solid) p.accentSoft else p.surface)
                                .layerOutline(ink.border, 8.dp, ink.dashed, 1.dp).clickable(role = Role.DropdownList) { open = true }
                                .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(layer?.name ?: e.level, color = ink.label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Glyph(R.drawable.ic_chevron_down, ink.label, 14.dp)
                            }
                            DropdownMenu(open, { open = false }) {
                                layers.forEach { l -> DropdownMenuItem({ Text(l.name) }, { who[i] = e.copy(level = l.id); open = false }) }
                            }
                        }
                        BarIcon(R.drawable.ic_close, "Remove ${s.name}", p.ink3, 18.dp) { who.removeAt(i) }
                    }
                    Spacer(Modifier.fillMaxWidth().height(1.dp).background(p.line))
                } }
                Row(Modifier.fillMaxWidth().clickable { picking = true }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Glyph(R.drawable.ic_plus, p.accent, 20.dp)
                    Text("Add member", color = p.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
            SectionLabel("Note")
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(p.surface).padding(14.dp)) {
                if (note.isEmpty()) Text("Quiet morning, woke up early.", color = p.ink3, fontSize = 15.sp)
                BasicTextField(note, { note = it }, textStyle = TextStyle(color = p.ink, fontSize = 15.sp, fontFamily = HankenGrotesk),
                    cursorBrush = SolidColor(p.accent), modifier = Modifier.fillMaxWidth())
            }
            error?.let { Text(it, color = p.danger) }
            Box(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(14.dp)).background(if (busy) p.surface3 else p.accent)
                .clickable(enabled = !busy, role = Role.Button) { save() }.padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                Text("Save to history", color = p.onAccent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PastRow(icon: Int, label: String, value: String, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Glyph(icon, p.ink2, 20.dp)
        Text(label, color = p.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = p.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Glyph(R.drawable.ic_chevron_right, p.ink3, 18.dp)
    }
}

/** Pick one own member, subsystem or state (null when dismissed). */
@Composable
fun PickSubjects(model: Model, account: String, taken: Set<Pair<String, String>>, onPick: (Subject?) -> Unit) {
    val p = LocalChorusPalette.current
    var query by remember { mutableStateOf("") }
    val all = model.active.filter { it.createdByAccountId == null || it.createdByAccountId == account }
        .map { Subject("member", it.id, it.shownName, it.color, it.glyph, it.avatarBlob) } +
        model.groups.filter { it.isSubsystem }.map { Subject("group", it.id, it.name, it.color ?: "#A09184", "◌") } +
        model.states.map { Subject("state", it.id, it.name, it.color ?: "#A39E92", it.icon ?: "?") }
    val shown = all.filter { (it.type to it.id) !in taken }.mapNotNull { s -> fuzzy(query, s.name)?.let { s to it } }.sortedBy { it.second }.map { it.first }
    ChorusSheet(onDismissRequest = { onPick(null) }, skipPartiallyExpanded = true, containerColor = p.surface) {
        SearchField(query, { query = it }, "Search", Modifier.padding(horizontal = 16.dp).fillMaxWidth())
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            shown.take(100).forEach { s ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(s) }.padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Avatar(s.glyph, s.color, 32.dp, avatarBlob = s.avatarBlob, ring = false)
                    Text(s.name, color = p.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    if (s.type != "member") Text(if (s.type == "group") "subsystem" else "state", color = p.ink3, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
