package garden.vayne.chorus.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.InspectionDialogContent
import garden.vayne.chorus.designsystem.HankenGrotesk
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import java.util.Calendar
import java.util.TimeZone

/*
 * Drawers instead of pop-ups (D-124). Every question, menu, picker and short form on Android opens
 * as a drawer from the bottom (Figma F3.3, F5.20): a grabber, a title with an optional face and a
 * close button, and the content in grouped cards. A drawer opened from a drawer lies over it, a
 * step shorter, so the one underneath still shows at the top and closing goes back to it.
 * Forms share the same parts on full screens (F3.5, F6.7): section labels, filled fields with the
 * label inside, grouped rows with their value in the accent, notes and one wide button.
 */

/** How many drawers lie under this one: 0 outside any drawer, 1 in the first. Every
 * [ChorusSheet] counts, so a field or card in an older sheet still steps up from it. */
internal val LocalDrawerDepth = staticCompositionLocalOf { 0 }

/** A grouped card's fill: a step up from whatever it sits on. */
@Composable
internal fun cardColor(): Color {
    val p = LocalChorusPalette.current
    return if (LocalDrawerDepth.current > 0) p.surface2 else p.surface
}

/**
 * A drawer: [title] (with [subtitle] and a [leading] face) over [content], which scrolls; [footer]
 * stays below it (the main button). [dismissible] false keeps it open while something is saving.
 */
@Composable
internal fun Drawer(onDismiss: () -> Unit, title: String? = null, subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null, dismissible: Boolean = true, scroll: Boolean = true,
    footer: (@Composable ColumnScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalChorusPalette.current
    val depth = LocalDrawerDepth.current
    // each layer stops a little lower than the one under it, so the stack shows. screenHeightDp
    // counts the system bars on Android 15+, and Material adds its 48 dp grabber above the content:
    // a long drawer reached the status bar at 92% (seen on the A56, 2026-11-07)
    val density = LocalDensity.current
    val bars = with(density) { (WindowInsets.statusBars.getTop(this) + WindowInsets.navigationBars.getBottom(this)).toDp() }
    val tallest = LocalConfiguration.current.screenHeightDp.dp - maxOf(bars, 48.dp) - 72.dp - (depth * 28).dp
    ChorusSheet(onDismissRequest = { if (dismissible) onDismiss() }, skipPartiallyExpanded = true, containerColor = p.surface) {
        InspectionDialogContent {
            run {
                Column(Modifier.fillMaxWidth().heightIn(max = tallest).imePadding()) {
                    if (title != null) DrawerHeader(title, subtitle, leading, if (dismissible) onDismiss else null)
                    Column(Modifier.weight(1f, fill = false).then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                        .padding(horizontal = 16.dp).padding(bottom = if (footer == null) 20.dp else 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
                    if (footer != null) Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp), content = footer)
                }
            }
        }
    }
}

@Composable
private fun DrawerHeader(title: String, subtitle: String?, leading: (@Composable () -> Unit)?, onClose: (() -> Unit)?) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, color = p.ink, fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            subtitle?.let { Text(it, color = p.ink3, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        if (onClose != null) BarIcon(R.drawable.ic_close, "Close", p.ink2, 20.dp, onClose)
    }
}

/** A question before something that can't be taken back, or that reaches other people. */
@Composable
internal fun ConfirmDrawer(title: String, message: String?, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit,
    danger: Boolean = false, cancel: String = "Cancel", busy: Boolean = false, enabled: Boolean = true, icon: Int? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null) {
    val p = LocalChorusPalette.current
    Drawer(onDismiss, dismissible = !busy, footer = {
        DrawerButton(confirm, onConfirm, if (danger) ButtonKind.Danger else ButtonKind.Primary, enabled = enabled && !busy, busy = busy)
        DrawerButton(cancel, onDismiss, ButtonKind.Quiet, enabled = !busy)
    }) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val tone = if (danger) p.danger else p.accent
            Box(Modifier.size(44.dp).background(tone.copy(alpha = .16f), CircleShape), contentAlignment = Alignment.Center) {
                Glyph(icon ?: if (danger) R.drawable.ic_delete else R.drawable.ic_check, tone, 22.dp)
            }
            Text(title, color = p.ink, fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).semantics { heading() })
        }
        message?.let { Text(it, color = p.ink2, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(horizontal = 4.dp)) }
        content?.invoke(this)
    }
}

/** A list of actions in a drawer, in place of a dropdown menu (F5.6). Fill it with [MenuRow]s. */
@Composable
internal fun MenuDrawer(open: Boolean, onDismiss: () -> Unit, title: String? = null, subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    if (open) Drawer(onDismiss, title, subtitle, leading) {
        Column(Modifier.fillMaxWidth().padding(top = if (title == null) 4.dp else 0.dp), content = content)
    }
}

/**
 * One action: an icon, a label and maybe a line under it. [selected] non-null makes it a choice,
 * with a check on the chosen one; [danger] colours a destructive action.
 */
@Composable
internal fun MenuRow(label: String, icon: Int? = null, subtitle: String? = null, danger: Boolean = false, enabled: Boolean = true,
    selected: Boolean? = null, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    val ink = when { !enabled -> p.ink3; danger -> p.danger; selected == true -> p.accent; else -> p.ink }
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp))
        .background(if (selected == true) p.accentSoft else Color.Transparent)
        .clickable(enabled = enabled, role = if (selected != null) Role.RadioButton else Role.Button, onClick = onClick)
        .then(if (selected != null) Modifier.semantics { this.selected = selected } else Modifier)
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (leading != null) leading()
        else if (icon != null) Glyph(icon, if (danger) p.danger else if (enabled) p.ink2 else p.ink3, 22.dp)
        Column(Modifier.weight(1f)) {
            Text(label, color = ink, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = if (selected == true) FontWeight.SemiBold else FontWeight.Normal)
            subtitle?.let { Text(it, color = p.ink3, fontSize = 13.sp, lineHeight = 17.sp) }
        }
        if (selected == true) Glyph(R.drawable.ic_check, p.accent, 20.dp)
    }
}

/** A thin line between groups of [MenuRow]s. */
@Composable
internal fun MenuDivider() {
    val p = LocalChorusPalette.current
    Spacer(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(p.line))
}

internal enum class ButtonKind { Primary, Danger, Neutral, Quiet }

/** The wide button at the foot of a form or drawer (F3.5 "Save to history", F6.7 "Save feed"). */
@Composable
internal fun DrawerButton(text: String, onClick: () -> Unit, kind: ButtonKind = ButtonKind.Primary, enabled: Boolean = true,
    busy: Boolean = false, modifier: Modifier = Modifier) {
    val p = LocalChorusPalette.current
    val (fill, ink) = when (kind) {
        ButtonKind.Primary -> p.accent to p.onAccent
        ButtonKind.Danger -> p.danger to p.onAccent
        ButtonKind.Neutral -> cardColor() to p.ink
        ButtonKind.Quiet -> Color.Transparent to p.ink2
    }
    val on = enabled && !busy
    Box(modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(14.dp))
        .background(if (on || kind == ButtonKind.Quiet) fill else p.surface3)
        .clickable(enabled = on, role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = ink, strokeWidth = 2.dp)
        else Text(text, color = if (on) ink else p.ink3, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** A rounded card holding rows of a form (F3.5); put [FormDivider]s between the rows. */
@Composable
internal fun FormCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cardColor()), content = content)
}

@Composable
internal fun FormDivider() {
    val p = LocalChorusPalette.current
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(p.line))
}

/** A row that opens a choice: icon, label, the current value in the accent and a chevron (F3.5). */
@Composable
internal fun FormValueRow(label: String, value: String, icon: Int? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        icon?.let { Glyph(it, p.ink2, 20.dp) }
        Text(label, color = if (enabled) p.ink else p.ink3, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = if (enabled) p.accent else p.ink3, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Glyph(R.drawable.ic_chevron_right, p.ink3, 18.dp)
    }
}

/** A quiet line of context above a form: teal for how it works, red for what leaves this space (F3.5, F5.20). */
@Composable
internal fun FormNote(text: String, icon: Int? = null, warning: Boolean = false, title: String? = null) {
    val p = LocalChorusPalette.current
    val tone = if (warning) p.danger else p.accent
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (warning) p.danger.copy(alpha = .14f) else p.accentSoft)
        .padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        icon?.let { Glyph(it, tone, 18.dp, modifier = Modifier.padding(top = 1.dp)) }
        Column(Modifier.weight(1f)) {
            title?.let { Text(it, color = tone, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold) }
            Text(text, color = p.ink, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

/**
 * A filled field with its label inside (F6.7 "NAME", "DESCRIPTION"): the app's text field, in place
 * of Material's outlined one, with the same parameters. Compact (owner, 2026-11-07): 48 dp with a
 * label, 44 without, where Material's own field is 56.
 */
@Composable
internal fun ChorusField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    readOnly: Boolean = false, label: (@Composable () -> Unit)? = null, placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null, trailingIcon: (@Composable () -> Unit)? = null,
    prefix: (@Composable () -> Unit)? = null, supportingText: (@Composable () -> Unit)? = null, isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false, maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE, minLines: Int = 1) {
    val interaction = remember { MutableInteractionSource() }
    BasicTextField(value, onValueChange, modifier.fieldSize(label != null), enabled, readOnly, fieldText(enabled),
        keyboardOptions, keyboardActions, singleLine, maxLines, minLines, interactionSource = interaction,
        cursorBrush = SolidColor(if (isError) LocalChorusPalette.current.danger else LocalChorusPalette.current.accent)) { inner ->
        FieldDecoration(value, inner, enabled, singleLine, interaction, isError, label, placeholder, leadingIcon, trailingIcon, prefix, supportingText)
    }
}

@Composable
internal fun ChorusField(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    readOnly: Boolean = false, label: (@Composable () -> Unit)? = null, placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null, trailingIcon: (@Composable () -> Unit)? = null,
    prefix: (@Composable () -> Unit)? = null, supportingText: (@Composable () -> Unit)? = null, isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false, maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE, minLines: Int = 1) {
    val interaction = remember { MutableInteractionSource() }
    BasicTextField(value, onValueChange, modifier.fieldSize(label != null), enabled, readOnly, fieldText(enabled),
        keyboardOptions, keyboardActions, singleLine, maxLines, minLines, interactionSource = interaction,
        cursorBrush = SolidColor(if (isError) LocalChorusPalette.current.danger else LocalChorusPalette.current.accent)) { inner ->
        FieldDecoration(value.text, inner, enabled, singleLine, interaction, isError, label, placeholder, leadingIcon, trailingIcon, prefix, supportingText)
    }
}

private fun Modifier.fieldSize(labelled: Boolean) = defaultMinSize(minWidth = 160.dp, minHeight = if (labelled) 48.dp else 44.dp)

// The label sits above the text as a small line, like [ChoiceField]'s: Material's floating label
// keeps an empty field at 56 dp (it pads 16 dp above and below until the label moves up).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldDecoration(text: String, inner: @Composable () -> Unit, enabled: Boolean, singleLine: Boolean,
    interaction: MutableInteractionSource, isError: Boolean, label: (@Composable () -> Unit)?, placeholder: (@Composable () -> Unit)?,
    leadingIcon: (@Composable () -> Unit)?, trailingIcon: (@Composable () -> Unit)?, prefix: (@Composable () -> Unit)?,
    supportingText: (@Composable () -> Unit)?) {
    val p = LocalChorusPalette.current
    val colors = fieldColors()
    val focused by interaction.collectIsFocusedAsState()
    // empty and not being typed in: the label reads as the field's prompt, in the text's place
    val resting = label != null && text.isEmpty() && !focused
    val field: @Composable () -> Unit = if (label == null) inner else if (resting) { {
        Box {
            CompositionLocalProvider(LocalContentColor provides if (isError) p.danger else p.ink3,
                LocalTextStyle provides LocalTextStyle.current.merge(fieldText(enabled).copy(color = Color.Unspecified))) { label!!() }
            inner()
        }
    } } else { {
        Column {
            CompositionLocalProvider(LocalContentColor provides when { isError -> p.danger; focused -> p.accent; else -> p.ink3 },
                LocalTextStyle provides LocalTextStyle.current.merge(TextStyle(fontSize = 12.sp, lineHeight = 16.sp))) { label() }
            Box {
                if (text.isEmpty() && placeholder != null) CompositionLocalProvider(LocalContentColor provides p.ink3,
                    LocalTextStyle provides fieldText(enabled).copy(color = p.ink3)) { placeholder() }
                inner()
            }
        }
    } }
    TextFieldDefaults.DecorationBox(text, field, enabled, singleLine, VisualTransformation.None, interaction, isError,
        null, placeholder.takeIf { label == null }, leadingIcon, trailingIcon, prefix, null, supportingText, RoundedCornerShape(12.dp), colors,
        contentPadding = TextFieldDefaults.contentPaddingWithoutLabel(start = 14.dp, end = 14.dp,
            top = when { label == null -> 11.dp; resting -> 11.dp; else -> 6.dp },
            bottom = when { label == null -> 11.dp; resting -> 12.dp; else -> 5.dp }))
}

@Composable
private fun fieldText(enabled: Boolean = true) = TextStyle(color = LocalChorusPalette.current.let { if (enabled) it.ink else it.ink3 },
    fontSize = 15.sp, lineHeight = 21.sp, fontFamily = HankenGrotesk)

/** A field's fill: a wash of ink over whatever it sits on, so it shows on a page, in a drawer and in
 * any card (a fixed step-up colour vanished inside cards of the same colour). */
@Composable
internal fun fieldFill(): Color = LocalChorusPalette.current.ink.copy(alpha = .05f)

@Composable
internal fun fieldColors(): androidx.compose.material3.TextFieldColors {
    val p = LocalChorusPalette.current
    val fill = fieldFill()
    return TextFieldDefaults.colors(
        focusedContainerColor = fill, unfocusedContainerColor = fill, disabledContainerColor = fill.copy(alpha = .6f), errorContainerColor = fill,
        focusedIndicatorColor = p.accent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
        errorIndicatorColor = p.danger, focusedLabelColor = p.accent, unfocusedLabelColor = p.ink3, disabledLabelColor = p.ink3,
        errorLabelColor = p.danger, cursorColor = p.accent, focusedTextColor = p.ink, unfocusedTextColor = p.ink, disabledTextColor = p.ink3,
        focusedPlaceholderColor = p.ink3, unfocusedPlaceholderColor = p.ink3, focusedSupportingTextColor = p.ink3,
        unfocusedSupportingTextColor = p.ink3, errorSupportingTextColor = p.danger,
        focusedLeadingIconColor = p.ink2, unfocusedLeadingIconColor = p.ink2, focusedTrailingIconColor = p.ink2, unfocusedTrailingIconColor = p.ink2,
        focusedPrefixColor = p.ink3, unfocusedPrefixColor = p.ink3,
    )
}

/**
 * A day and a time, picked in a drawer (in place of the platform's two dialogs): Date and Time
 * tabs and one Done. [latest] keeps it from going past that moment (null: no limit).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateTimeDrawer(initial: Long, onPick: (Long) -> Unit, onDismiss: () -> Unit, title: String = "Pick a time",
    latest: Long? = null, timeOnly: Boolean = false) {
    val p = LocalChorusPalette.current
    val ctx = LocalContext.current
    val start = remember { Calendar.getInstance().apply { timeInMillis = initial } }
    // the date picker speaks UTC midnights; carry the local day across
    val utcDay = remember {
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear(); set(start.get(Calendar.YEAR), start.get(Calendar.MONTH), start.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
    }
    val lastDay = latest?.let { l ->
        val c = Calendar.getInstance().apply { timeInMillis = l }
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)) }.timeInMillis
    }
    val date = rememberDatePickerState(initialSelectedDateMillis = utcDay, selectableDates = object : androidx.compose.material3.SelectableDates {
        override fun isSelectableDate(utcTimeMillis: Long) = lastDay == null || utcTimeMillis <= lastDay
    })
    val time = rememberTimePickerState(start.get(Calendar.HOUR_OF_DAY), start.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(ctx))
    var tab by remember { mutableIntStateOf(if (timeOnly) 1 else 0) }
    fun chosen(): Long {
        val c = Calendar.getInstance().apply { timeInMillis = initial }
        date.selectedDateMillis?.takeIf { !timeOnly }?.let { d ->
            val u = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = d }
            c.set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH))
        }
        c.set(Calendar.HOUR_OF_DAY, time.hour); c.set(Calendar.MINUTE, time.minute); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return if (latest != null) minOf(c.timeInMillis, latest) else c.timeInMillis
    }
    Drawer(onDismiss, title, java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(chosen())),
        footer = {
            if (tab == 0 && !timeOnly) DrawerButton("Next: time", { tab = 1 }, ButtonKind.Neutral)
            DrawerButton("Done", { onPick(chosen()) })
        }) {
        if (!timeOnly) Segmented(listOf("Date", "Time"), tab) { tab = it }
        if (tab == 0 && !timeOnly) DatePicker(date, title = null, headline = null, showModeToggle = false,
            colors = DatePickerDefaults.colors(containerColor = p.surface, selectedDayContainerColor = p.accent,
                selectedDayContentColor = p.onAccent, todayDateBorderColor = p.accent, todayContentColor = p.accent,
                dayContentColor = p.ink, weekdayContentColor = p.ink3, navigationContentColor = p.ink2,
                yearContentColor = p.ink, selectedYearContainerColor = p.accent, selectedYearContentColor = p.onAccent,
                subheadContentColor = p.ink2, disabledDayContentColor = p.ink3.copy(alpha = .5f)))
        else Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            TimePicker(time, colors = TimePickerDefaults.colors(clockDialColor = cardColor(), selectorColor = p.accent,
                clockDialSelectedContentColor = p.onAccent, clockDialUnselectedContentColor = p.ink,
                timeSelectorSelectedContainerColor = p.accentSoft, timeSelectorUnselectedContainerColor = cardColor(),
                timeSelectorSelectedContentColor = p.ink, timeSelectorUnselectedContentColor = p.ink2,
                periodSelectorSelectedContainerColor = p.accentSoft, periodSelectorUnselectedContainerColor = Color.Transparent,
                periodSelectorSelectedContentColor = p.ink, periodSelectorUnselectedContentColor = p.ink2, periodSelectorBorderColor = p.line))
        }
    }
}

/** A drawer that picks one of [options] (in place of a dropdown of choices). */
@Composable
internal fun <T> ChoiceDrawer(open: Boolean, title: String, options: List<T>, selected: T?, label: (T) -> String,
    onPick: (T) -> Unit, onDismiss: () -> Unit, subtitle: String? = null, describe: ((T) -> String?)? = null) {
    MenuDrawer(open, onDismiss, title, subtitle) {
        options.forEach { o -> MenuRow(label(o), subtitle = describe?.invoke(o), selected = o == selected) { onPick(o) } }
    }
}

/** A field-like row that opens a [ChoiceDrawer]: its label above, the chosen value and a chevron. */
@Composable
internal fun ChoiceField(label: String, value: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(fieldFill())
        .clickable(enabled = enabled, role = Role.DropdownList, onClick = onClick).padding(horizontal = 14.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp)
            Text(value, color = if (enabled) p.ink else p.ink3, fontSize = 15.sp, lineHeight = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Glyph(R.drawable.ic_chevron_down, p.ink3, 18.dp)
    }
}

/** An outlined chip that opens a choice (F3.5 layer chips "Steering ⌄"). */
@Composable
internal fun ChoiceChip(text: String, onClick: () -> Unit, active: Boolean = false) {
    val p = LocalChorusPalette.current
    Row(Modifier.clip(RoundedCornerShape(8.dp)).background(if (active) p.accentSoft else Color.Transparent)
        .border(1.dp, if (active) p.accent else p.line, RoundedCornerShape(8.dp))
        .clickable(role = Role.DropdownList, onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text, color = if (active) p.accent else p.ink2, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Glyph(R.drawable.ic_chevron_down, if (active) p.accent else p.ink3, 14.dp)
    }
}

/**
 * A switch the size of the web's (40 × 24, owner, 2026-11-07: Material's is 52 × 32); the thumb
 * slides and the track fades. [onCheckedChange] null when the row around it toggles.
 */
@Composable
internal fun ChorusSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val p = LocalChorusPalette.current
    val thumb by animateDpAsState(if (checked) 19.dp else 3.dp, tween(150), label = "switch thumb")
    val track by animateColorAsState(if (checked) p.accent else p.surface3, tween(150), label = "switch track")
    val knob by animateColorAsState(if (checked) p.bg else p.ink3, tween(150), label = "switch knob")
    Box(modifier.then(if (onCheckedChange != null) Modifier.minimumInteractiveComponentSize()
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange) else Modifier),
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(40.dp, 24.dp).clip(CircleShape).background(track.copy(alpha = if (enabled) 1f else .5f))) {
            Box(Modifier.offset(x = thumb, y = 3.dp).size(18.dp).background(knob, CircleShape))
        }
    }
}
