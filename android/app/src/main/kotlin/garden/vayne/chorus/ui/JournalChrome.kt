package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.*
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R

@Composable
internal fun JournalModeChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    Box(Modifier.heightIn(min = 48.dp).clickable(onClick = onClick).padding(3.dp), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, lineHeight = 18.sp),
            color = if (selected) p.ink else p.ink3,
            modifier = Modifier.background(if (selected) p.surface else p.surface2, CircleShape).padding(horizontal = 14.dp, vertical = 6.dp))
    }
}

/** Flat feed rows share an avatar gutter and compact identity line: bold name, muted `· 25m`, a
 * lock when the audience isn't public and an optional overflow. The full reader keeps its body
 * across the width; foreign callers pass only fields already supplied by filtered APIs. The
 * overflow floats over the card's corner so its 48 dp target never grows the header.
 * An action row last ([endsWithActions]) brings its own air, so the card adds almost none below it.
 * In a thread, [lineAbove]/[lineBelow] join this avatar to the post it answers and the one answering
 * it; [wide] gives a compact card the full reader's gutter so that line runs straight into it. */
@Composable
internal fun JournalPostFrame(names: String, time: String, audience: String?, full: Boolean = false,
    avatar: @Composable () -> Unit, overflow: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null,
    endsWithActions: Boolean = false, lineAbove: Boolean = false, lineBelow: Boolean = false, wide: Boolean = false,
    /** One author's handle (D-126): beside the name, or under it on a focused post (Figma F6.4). */
    handle: String? = null,
    content: @Composable ColumnScope.() -> Unit) {
    val p = LocalChorusPalette.current
    val inset = if (full || wide) 20.dp else 16.dp
    val gutter = if (full || wide) 48.dp else 40.dp
    val top = if (full) 16.dp else 10.dp
    @Composable fun Identity() {
        Row(Modifier.fillMaxWidth().padding(end = if (overflow != null) 36.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                val quiet = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp)
                if (full && handle != null) Column(Modifier.weight(1f, fill = false)) {
                    Text(names, color = p.ink, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp, lineHeight = 20.sp),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("@$handle", style = quiet, color = p.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    Text(names, color = p.ink, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp, lineHeight = 20.sp),
                        maxLines = if (handle != null) 1 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (handle != null) Text(" @$handle", style = quiet, color = p.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                }
                if (!full || handle == null) Text(" · $time", style = quiet, color = p.ink3, maxLines = 1, softWrap = false)
            }
            if (audience != null && audience in setOf("private", "friends", "followers", "following")) Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_lock),
                JournalPresentation.audience(audience), Modifier.padding(start = 6.dp).size(14.dp), tint = p.ink3)
        }
    }
    Box(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClickLabel = "Open post", onClick = onClick) else Modifier)
        .then(if (lineAbove || lineBelow) Modifier.drawBehind {
            val x = (inset + gutter / 2).toPx()
            val width = 2.dp.toPx()
            if (lineAbove) drawLine(p.line, Offset(x, 0f), Offset(x, (top - 4.dp).toPx()), width)
            if (lineBelow) drawLine(p.line, Offset(x, (top + 44.dp).toPx()), Offset(x, size.height), width)
        } else Modifier)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = inset).padding(
            top = top, bottom = if (full) 16.dp else if (endsWithActions) 1.dp else 10.dp)) {
            if (full) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.width(gutter), contentAlignment = Alignment.TopCenter) { avatar() }
                    Column(Modifier.weight(1f)) { Identity() }
                }
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
            } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.width(gutter), contentAlignment = Alignment.TopCenter) { avatar() }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Identity()
                    content()
                }
            }
        }
        if (overflow != null) Box(Modifier.align(Alignment.TopEnd).padding(top = if (full) 20.dp else 5.dp, end = 4.dp)) { overflow() }
    }
}

/** The `⋯` on a card: quiet extras that don't deserve a button in the action row. */
@Composable
internal fun JournalOverflow(label: String, items: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = Modifier.size(40.dp)) { ChatIcon(R.drawable.ic_more, label, LocalChorusPalette.current.ink3) }
    MenuDrawer(open, { open = false }, "This post") {
        for ((text, action) in items) MenuRow(text, overflowIcon(text), danger = text.startsWith("Delete")) { open = false; action() }
    }
}

/** A post menu's icon, by what the item says. */
private fun overflowIcon(text: String): Int? = when {
    text.startsWith("Edit history") -> R.drawable.ic_history
    text.startsWith("Edit") -> R.drawable.ic_edit
    text.startsWith("Open") -> R.drawable.ic_chevron_right
    text.startsWith("Copy") -> R.drawable.ic_copy
    text.contains("audience") -> R.drawable.ic_eye
    text.startsWith("Delete") -> R.drawable.ic_delete
    text.startsWith("Pin") || text.startsWith("Unpin") -> R.drawable.ic_pin
    text.startsWith("Save") || text.startsWith("Remove from saved") -> R.drawable.ic_bookmark
    text.startsWith("Mute") || text.startsWith("Unmute") -> R.drawable.ic_bell_off
    text.startsWith("Share") -> R.drawable.ic_share
    text.startsWith("Report") -> R.drawable.ic_format_eye_off
    else -> null
}

/** `25m`, `3h`, `2d`, then a date: the muted time after a name. */
internal fun compactAgo(ms: Long): String = JournalPresentation.shortAgo(ms) {
    java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(it))
}

@Composable
internal fun JournalTextField(value: String, onChange: (String) -> Unit, hint: String, style: TextStyle,
    modifier: Modifier = Modifier, minLines: Int = 1, enabled: Boolean = true) {
    val p = LocalChorusPalette.current
    BasicTextField(value, onChange, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        textStyle = style.copy(color = p.ink), cursorBrush = SolidColor(p.accent), minLines = minLines,
        decorationBox = { inner -> Box(Modifier.padding(vertical = 8.dp)) {
            if (value.isEmpty()) Text(hint, style = style, color = p.ink3)
            inner()
        } })
}

@Composable
internal fun JournalAudiencePicker(audience: String, person: Boolean, onChoose: (String) -> Unit) {
    val p = LocalChorusPalette.current
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            ChatIcon(if (audience == "private") R.drawable.ic_lock else R.drawable.ic_globe, tint = p.accent)
            Spacer(Modifier.width(6.dp))
            Text(JournalPresentation.audience(audience, person), style = MaterialTheme.typography.labelMedium, maxLines = 2)
            ChatIcon(R.drawable.ic_chevron_down, tint = p.accent)
        }
        ChoiceDrawer(open, "Who can see it", JournalPresentation.AUDIENCES, audience, { JournalPresentation.audience(it, person) },
            { onChoose(it); open = false }, { open = false })
    }
}
