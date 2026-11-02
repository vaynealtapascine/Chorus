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
    content: @Composable ColumnScope.() -> Unit) {
    val p = LocalChorusPalette.current
    val inset = if (full || wide) 20.dp else 16.dp
    val gutter = if (full || wide) 48.dp else 40.dp
    val top = if (full) 16.dp else 10.dp
    @Composable fun Identity() {
        Row(Modifier.fillMaxWidth().padding(end = if (overflow != null) 36.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(names, color = p.ink, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp, lineHeight = 20.sp),
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(" · $time", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                    color = p.ink3, maxLines = 1, softWrap = false)
            }
            if (audience == "private" || audience == "followers") Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_lock),
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
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(40.dp)) { ChatIcon(R.drawable.ic_more, label, LocalChorusPalette.current.ink3) }
        DropdownMenu(open, { open = false }) {
            for ((text, action) in items) DropdownMenuItem(text = { Text(text) }, onClick = { open = false; action() })
        }
    }
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
        DropdownMenu(open, { open = false }) {
            for (mode in JournalPresentation.AUDIENCES) DropdownMenuItem(
                text = { Text(JournalPresentation.audience(mode, person)) }, onClick = { onChoose(mode); open = false })
        }
    }
}
