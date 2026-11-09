package garden.vayne.chorus.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.ForeignAuthor
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.SavedStage
import garden.vayne.chorus.data.StagePlan
import garden.vayne.chorus.designsystem.ChorusPalette
import garden.vayne.chorus.designsystem.LocalChorusDark
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import garden.vayne.chorus.designsystem.Tokens
import java.text.DateFormat
import java.util.Date
import org.json.JSONObject

/*
 * Stage (F8.2–F8.9), the pieces a chat's stage and a post's share: the bar with Capture, the five
 * tool tabs over a panel, round checks, steppers, member chips, preset tiles, the capture pills and
 * the save drawer. What a stage shows is decided by core (`StagePlan`); these only draw it.
 */

internal enum class StageTab(val label: String, val icon: Int) {
    Select("Select", R.drawable.ic_select), Filters("Filters", R.drawable.ic_filter), Style("Style", R.drawable.ic_type),
    Redact("Redact", R.drawable.ic_eye_off), Names("Names & times", R.drawable.ic_edit)
}

/** A stage's settings survive rotation as the saved definition they become. */
internal val StageSaver = Saver<StagePlan.Settings, String>(
    save = { StagePlan.definition("", it).toString() },
    restore = { StagePlan.supported(JSONObject(it)) })

internal val STAGE_STYLES = listOf("chorus" to "Chorus", "discord" to "Discord-ish", "bubbles" to "Bubbles",
    "card" to "Card", "transcript" to "Transcript", "minimal" to "Minimal")

internal fun stageStyleLabel(style: String) = STAGE_STYLES.firstOrNull { it.first == style }?.second ?: "Chorus"

/** Someone who appears on a stage, as this account knows them. */
internal data class StageAuthor(val id: String, val name: String, val glyph: String, val color: String, val avatarBlob: String?)

internal fun stageAuthor(id: String, model: Model, foreign: Map<String, ForeignAuthor>): StageAuthor {
    val member = model.member(id) ?: foreign[id]?.member
    return StageAuthor(id, member?.shownName ?: foreign[id]?.name ?: "Someone", member?.glyph ?: "?",
        member?.color ?: "#A09184", member?.avatarBlob)
}

internal fun countLabel(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

/** What a stage hides, in a few words, for its subtitle and its saved row. */
internal fun stageRedacted(s: StagePlan.Settings) =
    s.redactNames || s.blurAvatars || s.timeMode == "hide" || s.hideHeader || s.blurAttachments || s.blackout.isNotEmpty()

/** The palette a stage's preview is drawn in, and whether it is dark: its theme, a custom one as
 * core derived it, or the app's own. */
@Composable
internal fun stagePalette(s: StagePlan.Settings, custom: StagePlan.Palette?): Pair<ChorusPalette, Boolean> {
    val host = LocalChorusPalette.current
    val hostDark = LocalChorusDark.current
    return when {
        s.theme == "light" -> Tokens.Light to false
        s.theme == "dark" -> Tokens.Dark to true
        s.theme == "custom" && custom != null -> {
            val base = if (custom.dark) Tokens.Dark else Tokens.Light
            fun c(key: String, fallback: Color) = custom.colors[key]?.let { ColourValue.normalize(it) }?.let(ColourValue::rgb) ?: fallback
            base.copy(bg = c("bg", base.bg), surface = c("surface", base.surface), surface2 = c("surface2", base.surface2),
                surface3 = c("surface3", base.surface3), line = c("line", base.line), ink = c("ink", base.ink), ink2 = c("ink2", base.ink2),
                ink3 = c("ink3", base.ink3), accent = c("accent", base.accent), accentSoft = c("accent_soft", base.accentSoft),
                onAccent = c("on_accent", base.onAccent), bubbleOut = c("bubble_out", base.bubbleOut)) to custom.dark
        }
        else -> host to hostDark
    }
}

/** Lays [content] out at a stage width (F8.4: phone, square, wide) and shrinks it to fit the
 * screen, so a wide stage keeps the web's proportions on a phone. */
internal fun Modifier.stageWidth(width: String): Modifier = layout { measurable, constraints ->
    val target = (StagePlan.WIDTHS[width] ?: 390).dp.roundToPx()
    if (width == "phone" || !constraints.hasBoundedWidth || target <= constraints.maxWidth) {
        val w = minOf(constraints.maxWidth, target)
        val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
        return@layout layout(constraints.maxWidth, placeable.height) { placeable.place((constraints.maxWidth - placeable.width) / 2, 0) }
    }
    val scale = constraints.maxWidth.toFloat() / target
    val height = if (constraints.hasBoundedHeight) (constraints.maxHeight / scale).toInt() else constraints.maxHeight
    val placeable = measurable.measure(constraints.copy(minWidth = target, maxWidth = target,
        minHeight = (constraints.minHeight / scale).toInt(), maxHeight = height))
    layout(constraints.maxWidth, (placeable.height * scale).toInt()) {
        placeable.placeWithLayer(0, 0) {
            scaleX = scale; scaleY = scale
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
        }
    }
}

/** The word around [index] in [text], or null on a space. */
internal fun stageWordAt(text: String, index: Int): IntRange? {
    if (text.isEmpty()) return null
    var k = index.coerceIn(0, text.length - 1)
    if (!text[k].isLetterOrDigit()) { if (k > 0 && text[k - 1].isLetterOrDigit()) k-- else return null }
    var a = k
    while (a > 0 && text[a - 1].isLetterOrDigit()) a--
    var b = k
    while (b < text.length - 1 && text[b + 1].isLetterOrDigit()) b++
    return a..b
}

/**
 * A message's text with its blacked-out [shown] ranges (core's, merged) drawn as solid bars. While
 * [editing], a tap blacks out a word or gives one back, and a press-and-drag blacks out what it
 * passes over (F8.6). [prefix] is drawn before the text and is never blacked out.
 */
@Composable
internal fun StageText(text: String, shown: List<IntRange>, own: List<IntRange>, editing: Boolean, style: TextStyle,
    onChange: (List<IntRange>) -> Unit, prefix: String = "") {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var dragging by remember { mutableStateOf<IntRange?>(null) }
    val annotated = buildAnnotatedString {
        append(prefix); append(text)
        for (r in shown + listOfNotNull(dragging)) {
            val a = r.first.coerceIn(0, text.length)
            val b = (r.last + 1).coerceIn(0, text.length)
            if (a < b) addStyle(SpanStyle(color = Color.Black, background = Color.Black), prefix.length + a, prefix.length + b)
        }
    }
    fun at(pos: Offset): Int? = layout?.getOffsetForPosition(pos)?.minus(prefix.length)?.takeIf { it >= 0 && it < text.length }
    var start by remember { mutableStateOf<Int?>(null) }
    Text(annotated, style = style, onTextLayout = { layout = it }, modifier = if (!editing) Modifier else Modifier
        .pointerInput(text, own) {
            detectTapGestures { pos ->
                val i = at(pos) ?: return@detectTapGestures
                val hit = own.filter { i in it }
                if (hit.isNotEmpty()) onChange(own - hit.toSet())
                else if (shown.none { i in it }) stageWordAt(text, i)?.let { onChange(own + listOf(it)) }
            }
        }
        .pointerInput(text, own) {
            detectDragGesturesAfterLongPress(onDragStart = { pos -> start = at(pos); dragging = start?.let { stageWordAt(text, it) } },
                onDrag = { change, _ ->
                    val from = start ?: return@detectDragGesturesAfterLongPress
                    val to = at(change.position) ?: return@detectDragGesturesAfterLongPress
                    val a = minOf(from, to); val b = maxOf(from, to)
                    dragging = (stageWordAt(text, a)?.first ?: a)..(stageWordAt(text, b)?.last ?: b)
                },
                onDragEnd = { dragging?.let { onChange(own + listOf(it)) }; dragging = null; start = null },
                onDragCancel = { dragging = null; start = null })
        })
}

/** F8.2: close, the title over what is staged, save and the teal Capture pill. */
@Composable
internal fun StageBar(subtitle: String, onClose: () -> Unit, onSave: () -> Unit, onCapture: () -> Unit, captureEnabled: Boolean) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().background(p.surface).padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        BarIcon(R.drawable.ic_close, "Close stage", p.ink, onClick = onClose)
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text("Stage", color = p.ink, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        BarIcon(R.drawable.ic_bookmark, "Save stage", p.ink2, onClick = onSave)
        val tone = if (captureEnabled) p.onAccent else p.ink3
        Row(Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(18.dp)).background(if (captureEnabled) p.accent else p.surface3)
            .clickable(enabled = captureEnabled, role = Role.Button, onClick = onCapture).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Glyph(R.drawable.ic_camera, tone, 16.dp)
            Text("Capture", color = tone, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** The five tools under the preview (F8.2). */
@Composable
internal fun StageTabs(selected: StageTab, onSelect: (StageTab) -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 4.dp, vertical = 4.dp)) {
        StageTab.entries.forEach { tab ->
            val on = tab == selected
            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Tab) { onSelect(tab) }
                .semantics { this.selected = on }.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(Modifier.size(48.dp, 26.dp).clip(RoundedCornerShape(13.dp)).background(if (on) p.accentSoft else Color.Transparent),
                    contentAlignment = Alignment.Center) { Glyph(tab.icon, if (on) p.accent else p.ink2, 20.dp) }
                Text(tab.label, color = if (on) p.accent else p.ink2, fontSize = 11.sp, lineHeight = 14.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

/** The tool panel above the tabs: rounded on top, scrolling when a tool is long. */
@Composable
internal fun StagePanel(content: @Composable ColumnScope.() -> Unit) {
    val p = LocalChorusPalette.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(p.surface)
        .heightIn(max = 330.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

@Composable
internal fun StageLabel(text: String) {
    Text(text, color = LocalChorusPalette.current.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
}

/** A round check beside a message (F8.2): filled teal when picked. */
@Composable
internal fun StageCheck(on: Boolean) {
    val p = LocalChorusPalette.current
    Box(Modifier.size(22.dp).then(if (on) Modifier.background(p.accent, CircleShape) else Modifier.border(1.5.dp, p.ink3, CircleShape)),
        contentAlignment = Alignment.Center) { if (on) Glyph(R.drawable.ic_check, p.onAccent, 14.dp) }
}

/** A quiet chip; [on] non-null makes it a toggle. [leading] is a face. */
@Composable
internal fun StageChip(text: String, on: Boolean? = null, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    val lit = on == true
    Row(Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(if (lit) p.accentSoft else p.surface2)
        .then(if (on != null) Modifier.toggleable(on, role = Role.Checkbox) { onClick() } else Modifier.clickable(role = Role.Button, onClick = onClick))
        .padding(start = if (leading != null) 4.dp else 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        leading?.invoke()
        Text(text, color = if (lit) p.accent else p.ink2, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** "Only these members" (F8.3, F8.5): a scrolling row of faces that narrow what shows. */
@Composable
internal fun StageMemberChips(authors: List<StageAuthor>, only: Set<String>, onChange: (Set<String>) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (a in authors) StageChip(a.name, a.id in only, leading = {
            Avatar(a.glyph, a.color, 24.dp, avatarBlob = a.avatarBlob, ring = false)
        }) { onChange(if (a.id in only) only - a.id else only + a.id) }
    }
}

/** − value + (F8.3 Crop reply chains, F8.5 Reply levels). */
@Composable
internal fun StageStepper(value: String, label: String, minus: (() -> Unit)?, plus: (() -> Unit)?) {
    val p = LocalChorusPalette.current
    Row(Modifier.clip(RoundedCornerShape(20.dp)).background(p.surface2).semantics { contentDescription = "$label, $value" },
        verticalAlignment = Alignment.CenterVertically) {
        @Composable fun Step(symbol: String, description: String, action: (() -> Unit)?) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(enabled = action != null, onClickLabel = description, role = Role.Button) {
                action?.invoke()
            }, contentAlignment = Alignment.Center) {
                Text(symbol, color = if (action != null) p.ink else p.ink3, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Step("−", "Fewer", minus)
        Text(value, color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 28.dp))
        Step("+", "More", plus)
    }
}

/** A labelled row with a control at its end (F8.3 "Crop reply chains  − 2 +"). */
@Composable
internal fun StageRow(title: String, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = LocalChorusPalette.current.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** Six presets as small drawings (F8.4). */
@Composable
internal fun StagePresets(style: String, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        STAGE_STYLES.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((value, label) in row) PresetTile(value, label, value == style, Modifier.weight(1f)) { onPick(value) }
            }
        }
    }
}

@Composable
private fun PresetTile(value: String, label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.clip(shape).background(p.surface2).then(if (on) Modifier.border(1.5.dp, p.accent, shape) else Modifier)
        .selectable(on, role = Role.RadioButton, onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PresetSketch(value)
        Text(label, color = if (on) p.accent else p.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun PresetSketch(value: String) {
    val p = LocalChorusPalette.current
    @Composable fun Bar(fraction: Float, height: Dp, color: Color, end: Boolean = false) {
        Box(Modifier.fillMaxWidth(), contentAlignment = if (end) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.fillMaxWidth(fraction).height(height).clip(RoundedCornerShape(3.dp)).background(color))
        }
    }
    val faint = p.ink3.copy(alpha = .45f)
    Column(Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(8.dp)).background(p.bg).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
        when (value) {
            "discord", "transcript" -> { Bar(.8f, 3.dp, faint); Bar(.6f, 3.dp, faint); Bar(.7f, 3.dp, faint) }
            "bubbles" -> { Bar(.5f, 8.dp, p.surface3); Bar(.5f, 8.dp, p.accent, end = true) }
            "card" -> Box(Modifier.fillMaxWidth().height(20.dp).clip(RoundedCornerShape(4.dp)).background(p.surface3))
            "minimal" -> for (tick in listOf(Color(0xFFB98AE0), Color(0xFFE07A7A))) Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(3.dp, 8.dp).background(tick))
                Bar(.8f, 3.dp, faint)
            }
            else -> { Bar(.55f, 7.dp, p.surface3); Bar(.45f, 7.dp, p.accent.copy(alpha = .6f), end = true) }
        }
    }
}

/** F8.6: the redaction switches every stage offers. */
@Composable
internal fun StageRedactPanel(s: StagePlan.Settings, onChange: (StagePlan.Settings) -> Unit, chatName: Boolean = true) {
    StageLabel("Hide what identifies you")
    Column {
        ToggleRow("Replace names with Member A, B…", s.redactNames, inset = 0.dp) { onChange(s.copy(redactNames = it)) }
        ToggleRow("Plain grey pictures", s.blurAvatars, inset = 0.dp) { onChange(s.copy(blurAvatars = it)) }
        ToggleRow("Hide times", s.timeMode == "hide", inset = 0.dp) { onChange(s.copy(timeMode = if (it) "hide" else "real")) }
        if (chatName) ToggleRow("Hide the chat name", s.hideHeader, inset = 0.dp) { onChange(s.copy(hideHeader = it)) }
        ToggleRow("Blur photos and files", s.blurAttachments, inset = 0.dp) { onChange(s.copy(blurAttachments = it)) }
    }
    if (s.blackout.isEmpty()) FormNote("To black out words, press and drag over them in the preview.", R.drawable.ic_select)
    else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StageChip("Clear black-outs") { onChange(s.copy(blackout = emptyMap())) }
    }
}

/** F8.4: preset and theme. */
@Composable
internal fun StageStylePanel(s: StagePlan.Settings, hostDark: Boolean, widths: Boolean = true, onChange: (StagePlan.Settings) -> Unit) {
    StageLabel("Preset")
    StagePresets(s.style) { onChange(s.copy(style = it)) }
    StageLabel("Theme")
    val dark = s.theme == "dark" || s.theme == "auto" && hostDark
    Segmented(listOf("Dark", "Light", "Custom"), if (s.theme == "custom") 2 else if (dark) 0 else 1, accent = true) {
        onChange(when (it) {
            0 -> s.copy(theme = "dark")
            1 -> s.copy(theme = "light")
            else -> s.copy(theme = "custom", palette = s.palette.takeIf { p -> p.size == 3 } ?: DEFAULT_CUSTOM)
        })
    }
    if (s.theme == "custom") {
        for ((key, label) in listOf("bg" to "Background", "ink" to "Text", "accent" to "Accent"))
            ColourField(s.palette[key] ?: DEFAULT_CUSTOM.getValue(key), { onChange(s.copy(palette = s.palette + (key to it))) }, label = label)
    }
    if (widths) {
        StageLabel("Width")
        val values = listOf("phone", "square", "wide")
        Segmented(listOf("Phone", "Square", "Wide"), values.indexOf(s.width), accent = true) { onChange(s.copy(width = values[it])) }
    }
}

private val DEFAULT_CUSTOM = mapOf("bg" to "#F4EFE6", "ink" to "#2B211C", "accent" to "#247D73")

/** F8.7: a fake name for each author and the shown times, for this stage only. */
@Composable
internal fun StageNamesPanel(s: StagePlan.Settings, authors: List<StageAuthor>, firstAt: Long?, onChange: (StagePlan.Settings) -> Unit) {
    val p = LocalChorusPalette.current
    if (authors.isNotEmpty()) {
        StageLabel("Names")
        for (a in authors) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Avatar(a.glyph, a.color, 32.dp, avatarBlob = a.avatarBlob, ring = false)
            Text(a.name, color = p.ink2, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Glyph(R.drawable.ic_chevron_right, p.ink3, 16.dp)
            var picking by remember { mutableStateOf(false) }
            val fake = s.fakeNames[a.id].orEmpty()
            ChorusField(fake, { value ->
                onChange(s.copy(fakeNames = s.fakeNames.toMutableMap().apply { if (value.isBlank()) remove(a.id) else put(a.id, value) }))
            }, Modifier.weight(1.2f), singleLine = true, placeholder = { Text(a.name, color = p.ink3) }, trailingIcon = {
                val color = s.fakeColors[a.id]
                Box(Modifier.size(32.dp).clip(CircleShape).clickable(enabled = fake.isNotBlank(), onClickLabel = "Colour for ${fake.ifBlank { a.name }}") {
                    picking = true
                }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(14.dp).background(color?.let(ColourValue::rgb) ?: p.ink3.copy(alpha = if (fake.isBlank()) .3f else .7f), CircleShape))
                }
            })
            if (picking) ColourPicker(s.fakeColors[a.id] ?: a.color, onDismiss = { picking = false }, onApply = {
                onChange(s.copy(fakeColors = s.fakeColors + (a.id to it))); picking = false
            })
        }
    }
    StageLabel("Times")
    val modes = listOf("shift", "start", "manual")
    Segmented(listOf("Shift all", "Start at", "By hand"), modes.indexOf(s.timeMode), accent = true) { i ->
        val mode = modes[i]
        onChange(if (s.timeMode == mode) s.copy(timeMode = "real")
            else s.copy(timeMode = mode, startAt = if (mode == "start" && s.startAt == 0L) firstAt ?: System.currentTimeMillis() else s.startAt))
    }
    when (s.timeMode) {
        "shift" -> StageStepper(shiftLabel(s.shiftMinutes), "Shift all times", { onChange(s.copy(shiftMinutes = s.shiftMinutes - 15)) },
            { onChange(s.copy(shiftMinutes = s.shiftMinutes + 15)) })
        "start" -> {
            var picking by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StageClockChip(s.startAt) { picking = true }
                Text("Gaps between messages are kept.", color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp)
            }
            if (picking) DateTimeDrawer(s.startAt, { onChange(s.copy(startAt = it)); picking = false }, { picking = false }, "Start at")
        }
        "manual" -> if (s.manualAt.isNotEmpty()) StageChip("Reset ${countLabel(s.manualAt.size, "time")}") { onChange(s.copy(manualAt = emptyMap())) }
    }
    Column { ToggleRow("Hide dates", s.hideDates, inset = 0.dp) { onChange(s.copy(hideDates = it)) } }
}

/** A time to tap and change (F8.7 "09:00"). */
@Composable
internal fun StageClockChip(at: Long, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp)).background(p.surface2).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Glyph(R.drawable.ic_clock, p.ink2, 18.dp)
        Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at)), color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun shiftLabel(minutes: Int): String {
    if (minutes == 0) return "0 min"
    val sign = if (minutes < 0) "−" else "+"
    val m = kotlin.math.abs(minutes)
    return sign + listOfNotNull((m / 60).takeIf { it > 0 }?.let { "$it h" }, (m % 60).takeIf { it > 0 }?.let { "$it min" }).joinToString(" ")
}

/** F8.8: "take a screenshot now" on top and Done below; both fade after two seconds and come back on a tap. */
@Composable
internal fun BoxScope.StageCapturePills(visible: Boolean, onShow: () -> Unit, onDone: () -> Unit) {
    val p = LocalChorusPalette.current
    if (!visible) Box(Modifier.matchParentSize().clickable(remember { MutableInteractionSource() }, null, onClickLabel = "Show Done", onClick = onShow))
    AnimatedVisibility(visible, Modifier.align(Alignment.TopCenter).padding(top = 12.dp, start = 16.dp, end = 16.dp), enter = fadeIn(), exit = fadeOut()) {
        Row(Modifier.clip(RoundedCornerShape(18.dp)).background(p.surface2).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Glyph(R.drawable.ic_camera, p.accent, 16.dp)
            Text("Take a screenshot now · long screenshots work too", color = p.ink, fontSize = 13.sp)
        }
    }
    AnimatedVisibility(visible, Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp), enter = fadeIn(), exit = fadeOut()) {
        Row(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).background(p.surface2).clickable(role = Role.Button, onClick = onDone)
            .padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Glyph(R.drawable.ic_check, p.accent, 18.dp)
            Text("Done", color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** One saved stage in the save drawer: its icon, name and what it holds. */
internal data class StageSavedRow(val stage: SavedStage, val icon: Int, val summary: String, val loadable: Boolean)

/** F8.9: name this stage and save it; the saved ones below reopen with a tap. Saving under a name
 * already used here replaces that stage. */
@Composable
internal fun StageSaveDrawer(initialName: String, saved: List<StageSavedRow>, busy: Boolean, error: String?,
    onSave: (String) -> Unit, onOpen: (SavedStage) -> Unit, onDelete: (SavedStage) -> Unit, onDismiss: () -> Unit) {
    val p = LocalChorusPalette.current
    var name by remember { mutableStateOf(initialName) }
    var menuFor by remember { mutableStateOf<StageSavedRow?>(null) }
    Drawer(onDismiss, "Save this stage", "Saves the setup, not a copy of the messages. Deleted messages drop out.",
        dismissible = !busy, footer = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DrawerButton("Cancel", onDismiss, ButtonKind.Neutral, enabled = !busy, modifier = Modifier.weight(1f))
                DrawerButton("Save stage", { onSave(name.trim()) }, enabled = name.isNotBlank(), busy = busy, modifier = Modifier.weight(2f))
            }
        }) {
        ChorusField(name, { name = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("Name") }, singleLine = true)
        error?.let { Text(it, color = p.danger, fontSize = 13.sp) }
        if (saved.isNotEmpty()) {
            Text("Saved stages", color = p.ink3, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
            FormCard {
                saved.forEachIndexed { i, row ->
                    if (i > 0) FormDivider()
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = row.loadable && !busy) { onOpen(row.stage) }
                        .padding(start = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Glyph(row.icon, p.ink2, 20.dp)
                        Column(Modifier.weight(1f)) {
                            Text(row.stage.name, color = if (row.loadable) p.ink else p.ink3, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(row.summary, color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        BarIcon(R.drawable.ic_more_vert, "Options for ${row.stage.name}", p.ink3, 20.dp) { menuFor = row }
                    }
                }
            }
        }
    }
    MenuDrawer(menuFor != null, { menuFor = null }, menuFor?.stage?.name) {
        val row = menuFor ?: return@MenuDrawer
        if (row.loadable) MenuRow("Open", R.drawable.ic_chevron_right) { menuFor = null; onOpen(row.stage) }
        MenuRow("Delete", R.drawable.ic_delete, danger = true, enabled = !busy) { menuFor = null; onDelete(row.stage) }
    }
}

/** A thin bar and a quiet line in place of messages folded away (F8.3 "Nairon · 1 message"). */
@Composable
internal fun StageContextRow(names: List<String>, count: Int, what: String = "message", many: String = what + "s") {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(18.dp).background(p.line))
        Spacer(Modifier.width(8.dp))
        val who = names.distinct().let { if (it.size > 2) it.take(2) + "…" else it }.joinToString(", ")
        Text(listOf(who, countLabel(count, what, many)).filter { it.isNotBlank() }.joinToString(" · "), color = p.ink3, fontSize = 12.sp,
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
