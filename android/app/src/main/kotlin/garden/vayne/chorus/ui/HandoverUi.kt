package garden.vayne.chorus.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.FrontSlice
import garden.vayne.chorus.data.Handover
import garden.vayne.chorus.data.HandoverDef
import garden.vayne.chorus.data.HandoverItem
import garden.vayne.chorus.data.HandoverOut
import garden.vayne.chorus.data.HandoverView
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R

/**
 * Core's view of the Handover channel (`handover_view`): delivery, acknowledgements, checklist ticks and what is
 * unread, for [member] (the speaker, for "since X last fronted"). Null off the Handover chat, or until the front's
 * history is known (a configured account's is read from the replica).
 */
@Composable
internal fun rememberHandoverView(chorus: Chorus, model: Model, channelId: String?, member: String?): HandoverView? {
    val revision by chorus.frontHistoryRevision.collectAsState()
    val account = chorus.device?.accountId.orEmpty()
    val named = model.namedFronts != null
    val slices by produceState<List<FrontSlice>?>(if (named) null else Handover.slices(model), account, named, revision, model.frontIntervals) {
        value = if (!named) Handover.slices(model) else runCatching { chorus.frontHistory() }.getOrNull()?.let(Handover::slices).orEmpty()
    }
    val known = slices
    return remember(model.handoverMessages, model.handoverAcks, model.checklistChecks, known, member, channelId) {
        if (channelId == null || known == null) null else Handover.view(model, account, channelId, known, member)
    }
}

/** The chips over the Handover composer (F5.15): Acknowledge, Checklist, At next switch. */
@Composable
internal fun HandoverBar(canAcknowledge: Boolean, items: Int, nextSwitch: Boolean, checklistOpen: Boolean,
    onAcknowledge: () -> Unit, onChecklist: () -> Unit, onNextSwitch: () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HandoverChip("Acknowledge", R.drawable.ic_check, on = false, enabled = canAcknowledge, onClick = onAcknowledge)
        HandoverChip(if (items > 0) "Checklist · $items" else "Checklist", R.drawable.ic_format_list_ul, on = checklistOpen || items > 0, onClick = onChecklist)
        HandoverChip("At next switch", R.drawable.ic_switch_arrows, on = nextSwitch, onClick = onNextSwitch, role = Role.Switch)
    }
}

@Composable
private fun HandoverChip(label: String, icon: Int, on: Boolean, enabled: Boolean = true, role: Role = Role.Button, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    val ink = if (!enabled) p.ink3 else if (on) p.accent else p.ink
    Row(Modifier.clip(RoundedCornerShape(20.dp)).background(if (on) p.accentSoft else p.surface2)
        .border(BorderStroke(1.dp, if (on) p.accent else p.line), RoundedCornerShape(20.dp))
        .clickable(enabled = enabled, role = role, onClick = onClick).heightIn(min = 40.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(painterResource(icon), null, Modifier.size(16.dp), tint = ink)
        Text(label, color = ink, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1)
    }
}

/** The checklist items being written into the next handover: one row each, and a row to add another. */
@Composable
internal fun ChecklistEditor(items: List<String>, onChange: (List<String>) -> Unit) {
    val p = LocalChorusPalette.current
    Column(Modifier.fillMaxWidth().background(p.surface2, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 4.dp)) {
        items.forEachIndexed { i, text ->
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(painterResource(R.drawable.ic_format_list_ul), null, Modifier.size(18.dp), tint = p.ink3)
                BasicTextField(text, { v -> onChange(items.toMutableList().also { it[i] = v.replace('\n', ' ').take(Handover.MAX_TEXT) }) },
                    singleLine = true, cursorBrush = SolidColor(p.accent),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = p.ink, fontSize = 15.sp),
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Checklist item ${i + 1}" },
                    decorationBox = { inner -> if (text.isEmpty()) Text("Item", color = p.ink3, fontSize = 15.sp); inner() })
                ChatIconButton(R.drawable.ic_close, "Remove item ${i + 1}") { onChange(items.filterIndexed { n, _ -> n != i }) }
            }
        }
        if (items.size < Handover.MAX_ITEMS) Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button) { onChange(items + "") },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(R.drawable.ic_plus_circle), null, Modifier.size(18.dp), tint = p.accent)
            Text("Add an item", color = p.accent, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

/** What a handover bubble carries besides its text: "waiting for next switch" and its checklist as tappable boxes. */
@Composable
internal fun HandoverBubble(def: HandoverDef, out: HandoverOut?, waiting: Boolean, enabled: Boolean, onTick: (HandoverItem) -> Unit) {
    val p = LocalChorusPalette.current
    val items = out?.checklist ?: def.checklist.map { HandoverItem(it.id, it.text, false, null) }
    Column(Modifier.fillMaxWidth()) {
        if (waiting) Row(Modifier.heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(R.drawable.ic_clock), null, Modifier.size(14.dp), tint = p.ink3)
            Text("Waiting for next switch", color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp)
        }
        for (item in items) Row(Modifier.fillMaxWidth().heightIn(min = 40.dp)
            .clickable(enabled = enabled, role = Role.Checkbox, onClickLabel = if (item.done) "Untick" else "Tick") { onTick(item) },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Checkbox(item.done, null, enabled = enabled)
            Text(item.text, color = if (item.done) p.ink3 else p.ink, fontSize = 15.sp, lineHeight = 20.sp,
                textDecoration = if (item.done) TextDecoration.LineThrough else null, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
}
