package garden.vayne.chorus.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.FrontingDisplay
import garden.vayne.chorus.data.FrontingDisplay.Style
import garden.vayne.chorus.data.Subject
import garden.vayne.chorus.designsystem.ChorusPalette
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import kotlin.math.cos
import kotlin.math.sin

/** The colours a layer draws with (Figma F3.1/F3.2/F3.9): solid is the accent-filled innermost
 * box, dashed the accent outline, faint the quiet grey outline, plain a bare dark box. A layer's
 * own colour (when set) stands in for the accent. */
data class LayerInk(val border: Color, val fill: Color, val dashed: Boolean, val label: Color,
    val tagFill: Color, val tagInk: Color, val tagBorder: Color?, val ring: Color)

fun layerInk(style: Style, color: Color?, p: ChorusPalette): LayerInk {
    val tint = color ?: p.accent
    return when (style) {
        Style.Solid -> LayerInk(tint, if (color == null) p.accentSoft else tint.copy(alpha = .16f), false, tint,
            tint, p.onAccent, null, tint)
        Style.Dashed -> LayerInk(tint.copy(alpha = .55f), tint.copy(alpha = .05f), true, tint,
            Color.Transparent, tint, tint.copy(alpha = .7f), tint.copy(alpha = .7f))
        Style.Faint -> LayerInk(p.ink3.copy(alpha = .4f), Color.Transparent, true, color ?: p.ink3,
            Color.Transparent, color ?: p.ink3, (color ?: p.ink3), (color ?: p.ink3).copy(alpha = .5f))
        Style.Plain -> LayerInk(p.line, Color.Transparent, false, color ?: p.ink, p.line, color ?: p.ink, null, p.line)
    }
}

fun parseHex(hex: String?): Color? = hex?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }

/** A rounded border, dashed when asked; Compose's `border` can't dash. */
fun Modifier.layerOutline(color: Color, radius: Dp, dashed: Boolean, width: Dp = 1.5.dp): Modifier = drawBehind {
    val w = width.toPx()
    drawRoundRect(color, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(radius.toPx() - w / 2),
        style = Stroke(w, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())) else null))
}

/** A styled layer box (the containment of F3.1). */
@Composable
fun LayerBox(ink: LayerInk, radius: Dp, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(radius)).background(ink.fill)
        .layerOutline(ink.border, radius, ink.dashed), content = content)
}

/** The small pill naming a layer under a face (F3.2) or on a front card (F3.4). */
@Composable
fun LayerTag(name: String, ink: LayerInk, modifier: Modifier = Modifier, fontSize: Int = 10) {
    Text(name, color = ink.tagInk, fontSize = fontSize.sp, fontWeight = FontWeight.Bold, lineHeight = (fontSize + 3).sp, maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.clip(RoundedCornerShape(8.dp)).background(ink.tagFill)
            .then(if (ink.tagBorder != null) Modifier.layerOutline(ink.tagBorder, 8.dp, ink.dashed, 1.dp) else Modifier)
            .padding(horizontal = 7.dp, vertical = 1.dp))
}

/** The icons a front can wear, by `FrontingDisplay.ICONS` key. */
fun frontIconRes(key: String): Int = when (key) {
    "target" -> R.drawable.ic_target
    "message" -> R.drawable.ic_message
    "book" -> R.drawable.ic_book
    "briefcase" -> R.drawable.ic_briefcase
    "monitor" -> R.drawable.ic_monitor
    "globe" -> R.drawable.ic_globe
    else -> R.drawable.ic_layers
}

@Composable
fun Glyph(res: Int, tint: Color, size: Dp = 18.dp, description: String? = null, modifier: Modifier = Modifier) {
    Icon(painterResource(res), description, modifier.size(size), tint = tint)
}

/** A 40 dp tappable icon, as in the Figma top bars. */
@Composable
fun BarIcon(res: Int, description: String, tint: Color = LocalChorusPalette.current.ink2, size: Dp = 22.dp, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClickLabel = description, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Glyph(res, tint, size)
    }
}

/** Overlapping 24 dp faces (Away, other fronts, separately listed layers). */
@Composable
fun FaceStack(faces: List<Subject>, max: Int = 3, size: Dp = 24.dp, muted: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
        faces.take(max).forEach { s ->
            if (muted) Box(Modifier.size(size).background(LocalChorusPalette.current.line, CircleShape), contentAlignment = Alignment.Center) {
                Text(s.glyph.take(2), color = LocalChorusPalette.current.onAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            } else Avatar(s.glyph, s.color, size, avatarBlob = s.avatarBlob, ring = false)
        }
    }
}

/** A settings row with a trailing switch: the whole row toggles (F3.3, F3.9). */
@Composable
fun ToggleRow(title: String, checked: Boolean, subtitle: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
        .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) p.ink else p.ink3, fontSize = 15.sp, lineHeight = 20.sp)
            subtitle?.let { Text(it, color = p.ink3, fontSize = 13.sp, lineHeight = 17.sp) }
        }
        Switch(checked, null, enabled = enabled, colors = SwitchDefaults.colors(checkedTrackColor = p.accent,
            checkedThumbColor = p.bg, uncheckedTrackColor = p.surface3, uncheckedThumbColor = p.ink3, uncheckedBorderColor = p.surface3))
    }
}

/** A two-or-more-way segmented control (F3.2 Replace / Add, F3.7 Day / Week / Month). */
@Composable
fun Segmented(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val p = LocalChorusPalette.current
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.surface2).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        options.forEachIndexed { i, label ->
            Box(Modifier.weight(1f).heightIn(min = 36.dp).clip(RoundedCornerShape(9.dp))
                .background(if (i == selected) p.surface else Color.Transparent)
                .clickable(role = Role.Tab) { onSelect(i) }.semantics { contentDescription = label + if (i == selected) ", selected" else "" },
                contentAlignment = Alignment.Center) {
                Text(label, color = if (i == selected) p.ink else p.ink3, fontSize = 13.sp,
                    fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Medium)
            }
        }
    }
}

/** A titled group of rows on a panel (F3.9 "Reduce clutter"). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    val p = LocalChorusPalette.current
    Row(modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        trailing?.invoke(this)
    }
}

/** A back-arrow top bar with a title and subtitle (F3.4, F3.5, F3.7, F3.9). */
@Composable
fun SubpageBar(title: String, subtitle: String?, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().background(p.surface).drawBehind {
        drawLine(p.line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
    }.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        BarIcon(R.drawable.ic_back, "Back", p.ink, onClick = onBack)
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(title, color = p.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        actions()
    }
}

/**
 * The orbit picture of a front (F3.4 cards, F3.8 widget): the innermost layer is a solid ring,
 * the next a dashed accent ring, the outer ones faint dashed rings; each face sits on its ring.
 * `rings` holds the faces of each nested layer, inner first.
 */
@Composable
fun Orbit(rings: List<List<Subject>>, size: Dp = 76.dp, modifier: Modifier = Modifier) {
    val p = LocalChorusPalette.current
    val count = rings.size.coerceIn(1, 3)
    val face = (size.value * 0.22f).dp
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val c = Offset(this.size.width / 2, this.size.height / 2)
            for (i in 0 until count) {
                val r = radius(i, count, this.size.minDimension / 2)
                drawCircle(if (i == 0) p.accent else if (i == 1) p.accent.copy(alpha = .55f) else p.ink3.copy(alpha = .45f), r, c,
                    style = Stroke(1.2.dp.toPx(), pathEffect = if (i == 0) null else PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))))
            }
        }
        rings.take(3).forEachIndexed { i, faces ->
            val r = radius(i, count, size.value / 2)
            faces.take(6).forEachIndexed { n, s ->
                // the innermost faces cluster in the middle; outer ones spread round their ring
                val angle = if (i == 0) Math.toRadians(180.0 + n * 360.0 / faces.size.coerceAtLeast(1)) else Math.toRadians(-60.0 + i * 90 + n * 50.0)
                val d = if (i == 0) (if (faces.size == 1) 0f else face.value * .45f) else r
                Avatar(s.glyph, s.color, face, avatarBlob = s.avatarBlob, ring = false,
                    modifier = Modifier.offset((d * cos(angle)).toFloat().dp, (d * sin(angle)).toFloat().dp))
            }
        }
    }
}

private fun radius(i: Int, count: Int, outer: Float): Float = when (count) {
    1 -> outer * .5f
    2 -> if (i == 0) outer * .45f else outer * .92f
    else -> listOf(.32f, .62f, .95f)[i] * outer
}

/** The default style of each nested layer and the separate ones, from the display pref. */
fun layerStyle(layerId: String, nestedIndex: Int, s: FrontingDisplay.Settings): Style =
    s.layer(layerId).style ?: if (s.layer(layerId).separate) Style.Plain else FrontingDisplay.defaultStyle(nestedIndex)
