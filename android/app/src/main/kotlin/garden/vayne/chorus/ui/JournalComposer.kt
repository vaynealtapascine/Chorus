package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import garden.vayne.chorus.data.ChatReactions
import garden.vayne.chorus.data.JournalDraft
import garden.vayne.chorus.data.Polls
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R

/** F6.2 top bar: Cancel, Drafts, and the Post pill. */
@Composable
internal fun ComposerTopBar(enabled: Boolean, canPost: Boolean, posting: Boolean, title: String?, onCancel: () -> Unit,
    onDrafts: () -> Unit, onPost: () -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Cancel", color = if (enabled) p.accent else p.ink3, fontSize = 16.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled, role = Role.Button, onClick = onCancel)
                .padding(horizontal = 12.dp, vertical = 12.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            title?.let { Text(it, color = p.ink3, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
        }
        Text("Drafts", color = if (enabled) p.ink2 else p.ink3, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled, role = Role.Button, onClick = onDrafts)
                .padding(horizontal = 10.dp, vertical = 12.dp))
        Box(Modifier.padding(start = 4.dp, end = 8.dp).clip(CircleShape).background(if (canPost) p.accent else p.surface3)
            .clickable(canPost, role = Role.Button, onClick = onPost).padding(horizontal = 18.dp, vertical = 8.dp)) {
            Text(if (posting) "Posting…" else "Post", color = if (canPost) p.onAccent else p.ink3, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** One glyph of a composer toolbar row: fills its share of the width, soft accent when on. */
@Composable
private fun RowScope.ToolGlyph(icon: Int?, label: String, enabled: Boolean, on: Boolean = false, text: String? = null, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    val tint = if (!enabled) p.ink3.copy(alpha = .5f) else if (on) p.accent else p.ink2
    Box(Modifier.weight(1f).height(44.dp).padding(horizontal = 1.dp).clip(RoundedCornerShape(10.dp))
        .background(if (on) p.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
        .clickable(enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label; selected = on }, contentAlignment = Alignment.Center) {
        if (icon != null) Glyph(icon, tint, 22.dp)
        else Text(text.orEmpty(), color = tint, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The line style the caret's line has: "heading1", "heading2", "quote" or "" (body). */
internal fun lineStyle(value: TextFieldValue): String {
    val start = value.text.lastIndexOf('\n', (value.selection.min - 1).coerceAtLeast(-1)) + 1
    val line = value.text.substring(start)
    return when {
        line.startsWith("## ") -> "heading2"
        line.startsWith("# ") -> "heading1"
        line.startsWith("> ") -> "quote"
        else -> ""
    }
}

/**
 * F6.2's first toolbar row: T opens the Formatting drawer; B I S, headings, lists, quote, code,
 * link and spoiler act on the selection. [headings] adds H1/H2 (posts and entries, D-134).
 */
@Composable
internal fun ComposerFormatRow(value: TextFieldValue, enabled: Boolean, headings: Boolean, onStyles: () -> Unit,
    onLink: () -> Unit, onChange: (TextFieldValue) -> Unit) {
    val ready = enabled && value.composition == null
    val style = lineStyle(value)
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        ToolGlyph(R.drawable.ic_type, "Formatting", enabled, onClick = onStyles)
        ToolGlyph(R.drawable.ic_format_bold, "Bold", ready, EditorFormatting.active(value, "**")) { onChange(EditorFormatting.inline(value, "**")) }
        ToolGlyph(R.drawable.ic_format_italic, "Italic", ready, EditorFormatting.active(value, "_")) { onChange(EditorFormatting.inline(value, "_")) }
        ToolGlyph(R.drawable.ic_format_strike, "Strikethrough", ready, EditorFormatting.active(value, "~~")) { onChange(EditorFormatting.inline(value, "~~")) }
        if (headings) ToolGlyph(null, "Heading", ready, style == "heading1" || style == "heading2", text = "H") {
            // H cycles body → H1 → H2 → body
            onChange(EditorFormatting.style(value, when (style) { "heading1" -> "heading2"; "heading2" -> ""; else -> "heading1" }))
        }
        ToolGlyph(R.drawable.ic_format_list_ul, "Bullets", ready) { onChange(EditorFormatting.lines(value, "• ")) }
        ToolGlyph(R.drawable.ic_format_list_ol, "Numbers", ready) { onChange(EditorFormatting.lines(value, "", numbered = true)) }
        ToolGlyph(R.drawable.ic_format_quote, "Quote", ready, style == "quote") { onChange(EditorFormatting.style(value, if (style == "quote") "" else "quote")) }
        ToolGlyph(R.drawable.ic_format_code, "Code", ready, EditorFormatting.active(value, "`")) { onChange(EditorFormatting.inline(value, "`")) }
        ToolGlyph(R.drawable.ic_link, "Link", ready, onClick = onLink)
        ToolGlyph(R.drawable.ic_format_eye_off, "Spoiler", ready, EditorFormatting.active(value, "||")) { onChange(EditorFormatting.inline(value, "||")) }
    }
}

/** F6.2's second toolbar row: what goes with the words, one tap each, and the word count. */
@Composable
internal fun ComposerActionRow(enabled: Boolean, poll: Boolean, cw: Boolean, tags: Boolean, words: Int,
    onImage: () -> Unit, onCamera: () -> Unit, onPoll: () -> Unit, onEmoji: () -> Unit, onCw: () -> Unit, onTags: () -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp).padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        ToolGlyph(R.drawable.ic_image, "Photos and files", enabled, onClick = onImage)
        ToolGlyph(R.drawable.ic_camera, "Camera", enabled, onClick = onCamera)
        ToolGlyph(R.drawable.ic_poll, "Poll", enabled, poll, onClick = onPoll)
        ToolGlyph(R.drawable.ic_emoji, "Emoji", enabled, onClick = onEmoji)
        ToolGlyph(R.drawable.ic_alert, "Content warning", enabled, cw, onClick = onCw)
        ToolGlyph(R.drawable.ic_hash, "Tags and mood", enabled, tags, onClick = onTags)
        Text("$words ${if (words == 1) "word" else "words"}", color = p.ink3, fontSize = 13.sp, maxLines = 1,
            modifier = Modifier.weight(2f).padding(end = 10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

/** A small chip in the composer's audience band ("Replies…" slot of F6.2): icon and a value. */
@Composable
internal fun ComposerChip(icon: Int, text: String, enabled: Boolean, active: Boolean = false, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    val shape = RoundedCornerShape(50)
    Row(Modifier.clip(shape).background(if (active) p.accentSoft else p.surface2).clickable(enabled, onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Glyph(icon, if (active) p.accent else p.ink2, 14.dp)
        if (text.isNotEmpty()) Text(text, color = if (active) p.accent else p.ink2, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/**
 * F6.11's floating pill under a selection: B I S code link spoiler, and T for the drawer. Drawn
 * below the selected line so it never covers the system's copy and paste bar above it.
 */
@Composable
internal fun SelectionToolbar(value: TextFieldValue, layout: TextLayoutResult?, onStyles: () -> Unit, onLink: () -> Unit,
    onChange: (TextFieldValue) -> Unit) {
    val p = LocalChorusPalette.current
    if (value.selection.collapsed || value.composition != null || layout == null) return
    val end = value.selection.max.coerceIn(0, layout.layoutInput.text.length)
    val line = layout.getLineForOffset(end)
    val start = value.selection.min.coerceIn(0, layout.layoutInput.text.length)
    val x = ((layout.getHorizontalPosition(start, true) + layout.getHorizontalPosition(end, true)) / 2).toInt()
    val y = layout.getLineBottom(line).toInt()
    val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.roundToPx() }
    Popup(popupPositionProvider = remember(x, y, gap) { object : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
            IntOffset((anchorBounds.left + x - popupContentSize.width / 2).coerceIn(8, (windowSize.width - popupContentSize.width - 8).coerceAtLeast(8)),
                anchorBounds.top + y + gap)
    } }, properties = PopupProperties(focusable = false)) {
        Row(Modifier.shadow(8.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(p.surface2)
            .padding(horizontal = 4.dp, vertical = 2.dp).width(300.dp)) {
            ToolGlyph(R.drawable.ic_format_bold, "Bold", true, EditorFormatting.active(value, "**")) { onChange(EditorFormatting.inline(value, "**")) }
            ToolGlyph(R.drawable.ic_format_italic, "Italic", true, EditorFormatting.active(value, "_")) { onChange(EditorFormatting.inline(value, "_")) }
            ToolGlyph(R.drawable.ic_format_strike, "Strikethrough", true, EditorFormatting.active(value, "~~")) { onChange(EditorFormatting.inline(value, "~~")) }
            ToolGlyph(R.drawable.ic_format_code, "Code", true, EditorFormatting.active(value, "`")) { onChange(EditorFormatting.inline(value, "`")) }
            ToolGlyph(R.drawable.ic_link, "Link", true, onClick = onLink)
            ToolGlyph(R.drawable.ic_format_eye_off, "Spoiler", true, EditorFormatting.active(value, "||")) { onChange(EditorFormatting.inline(value, "||")) }
            ToolGlyph(R.drawable.ic_type, "Formatting", true, onClick = onStyles)
        }
    }
}

/** F6.11's Formatting drawer: a paragraph style, the inline marks, lists and a cheat sheet. */
@Composable
internal fun FormattingDrawer(value: TextFieldValue, headings: Boolean, onDismiss: () -> Unit, onLink: () -> Unit,
    onChange: (TextFieldValue) -> Unit) {
    val p = LocalChorusPalette.current
    val style = lineStyle(value)
    val inPre = EditorFormatting.inFence(value)
    Drawer(onDismiss, "Formatting") {
        val styles = buildList {
            add("" to "Body")
            if (headings) { add("heading1" to "Heading"); add("heading2" to "Subheading") }
            add("quote" to "Quote"); add("code" to "Code")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((key, label) in styles) {
                val on = if (key == "code") inPre else !inPre && style == key
                Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (on) p.accentSoft else p.surface2)
                    .then(if (on) Modifier.border(1.dp, p.accent, RoundedCornerShape(12.dp)) else Modifier)
                    .clickable(role = Role.Tab) {
                        onChange(if (key == "code") EditorFormatting.fence(value) else EditorFormatting.style(value, key))
                    }.semantics { selected = on }, contentAlignment = Alignment.Center) {
                    Text(label, color = if (on) p.accent else p.ink, maxLines = 1,
                        fontSize = if (key.startsWith("heading")) 15.sp else 13.sp,
                        fontWeight = if (key.startsWith("heading")) FontWeight.Bold else FontWeight.Medium,
                        fontStyle = if (key == "quote") FontStyle.Italic else FontStyle.Normal,
                        fontFamily = if (key == "code") FontFamily.Monospace else null)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            @Composable fun RowScope.mark(icon: Int, label: String, marker: String) {
                val on = EditorFormatting.active(value, marker)
                Box(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(12.dp)).background(if (on) p.accentSoft else p.surface2)
                    .clickable(role = Role.Button) { onChange(EditorFormatting.inline(value, marker)) }
                    .semantics { contentDescription = label; selected = on }, contentAlignment = Alignment.Center) {
                    Glyph(icon, if (on) p.accent else p.ink2, 22.dp)
                }
            }
            mark(R.drawable.ic_format_bold, "Bold", "**")
            mark(R.drawable.ic_format_italic, "Italic", "_")
            mark(R.drawable.ic_format_strike, "Strikethrough", "~~")
            mark(R.drawable.ic_format_code, "Code", "`")
            Box(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(12.dp)).background(p.surface2)
                .clickable(role = Role.Button, onClick = onLink).semantics { contentDescription = "Link" }, contentAlignment = Alignment.Center) {
                Glyph(R.drawable.ic_link, p.ink2, 22.dp)
            }
            mark(R.drawable.ic_format_eye_off, "Spoiler", "||")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            @Composable fun RowScope.block(icon: Int, label: String, action: () -> TextFieldValue) {
                Row(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp)).background(p.surface2)
                    .clickable(role = Role.Button) { onChange(action()) }, verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center) {
                    Glyph(icon, p.ink2, 18.dp)
                    Text(label, color = p.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                        modifier = Modifier.padding(start = 6.dp))
                }
            }
            block(R.drawable.ic_format_list_ul, "Bullets") { EditorFormatting.lines(value, "• ") }
            block(R.drawable.ic_format_list_ol, "Numbers") { EditorFormatting.lines(value, "", numbered = true) }
            block(R.drawable.ic_format_eye_off, "Spoiler block") { EditorFormatting.spoilerBlock(value) }
        }
        // the cheat sheet: what to type, and what it looks like
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, p.line, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Shortcuts", color = p.ink3, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            val mono = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = p.ink3)
            val rows = buildList {
                add(Triple("**bold**", "bold", androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)))
                add(Triple("_italic_", "italic", androidx.compose.ui.text.SpanStyle(fontStyle = FontStyle.Italic)))
                add(Triple("~~strike~~", "strike", androidx.compose.ui.text.SpanStyle(textDecoration = TextDecoration.LineThrough)))
                add(Triple("`code`", "code", androidx.compose.ui.text.SpanStyle(fontFamily = FontFamily.Monospace, color = p.accent)))
                add(Triple("||spoiler||", "spoiler", androidx.compose.ui.text.SpanStyle(color = p.surface3, background = p.surface3)))
                add(Triple("> quote", "quote", androidx.compose.ui.text.SpanStyle(fontStyle = FontStyle.Italic)))
                add(Triple("• list", "• list", androidx.compose.ui.text.SpanStyle()))
                if (headings) {
                    add(Triple("# heading", "Heading", androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp)))
                    add(Triple("## subheading", "Subheading", androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)))
                }
            }
            for (pair in rows.chunked(2)) Row(Modifier.fillMaxWidth()) {
                for ((typed, shown, span) in pair) {
                    Text(typed, style = mono, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(androidx.compose.ui.text.buildAnnotatedString { pushStyle(span); append(shown); pop() }, color = p.ink,
                        fontSize = 13.sp, modifier = Modifier.weight(.8f), maxLines = 1)
                }
                if (pair.size == 1) Spacer(Modifier.weight(1.8f))
            }
        }
    }
}

/** F6.2 "Drafts": the composer's draft and those kept for later. */
@Composable
internal fun DraftsDrawer(current: JournalDraft, shelf: List<JournalDraft>, busy: Boolean, onDismiss: () -> Unit,
    onKeep: () -> Unit, onOpen: (String) -> Unit, onDiscard: (String) -> Unit) {
    val p = LocalChorusPalette.current
    var confirm by remember { mutableStateOf<String?>(null) }
    Drawer(onDismiss, "Drafts", footer = if (current.occupied && !busy) ({
        DrawerButton("New draft", onKeep, ButtonKind.Neutral)
    }) else null) {
        if (shelf.isEmpty()) EmptyState(R.drawable.ic_pencil, "No other drafts")
        for (draft in shelf) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cardColor())
                .clickable(!busy) { onOpen(draft.id) }.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val head = draft.title.ifBlank { draft.body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty() }
                        .trimStart('#', '>', ' ').ifBlank { if (draft.files.isNotEmpty()) "${draft.files.size} files" else "Empty" }
                    Text(head, color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    val words = draft.body.split(Regex("\\s+")).count { it.isNotBlank() }
                    Text(listOfNotNull(if (draft.kind == "entry") "Journal entry" else "Post", "$words words".takeIf { words > 0 },
                        "Poll".takeIf { draft.poll }, "Reply".takeIf { draft.reply != null }).joinToString(" · "),
                        color = p.ink3, fontSize = 12.sp)
                }
                BarIcon(R.drawable.ic_delete, "Discard draft", p.ink3, 20.dp) { confirm = draft.id }
            }
        }
    }
    confirm?.let { id ->
        ConfirmDrawer("Discard this draft?", null, "Discard", { onDiscard(id); confirm = null }, { confirm = null }, danger = true)
    }
}

/** The poll as part of the draft (F6.2's bar-chart button turns it on): options, add and remove, and "more than one". */
@Composable
internal fun ComposerPoll(options: List<String>, onOptions: (List<String>) -> Unit, multiple: Boolean, onMultiple: (Boolean) -> Unit,
    enabled: Boolean, onRemove: () -> Unit) {
    val p = LocalChorusPalette.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, p.line, RoundedCornerShape(16.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(R.drawable.ic_poll, p.accent, 18.dp)
            Text("Poll", color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 8.dp))
            BarIcon(R.drawable.ic_delete, "Remove poll", p.ink3, 20.dp) { if (enabled) onRemove() }
        }
        options.forEachIndexed { index, text ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChorusField(text, { value -> onOptions(options.mapIndexed { i, old -> if (i == index) value.take(200) else old }) },
                    label = { Text("Option ${index + 1}") }, singleLine = true, enabled = enabled, modifier = Modifier.weight(1f))
                if (options.size > 2) BarIcon(R.drawable.ic_delete, "Remove option ${index + 1}", p.ink3, 18.dp) {
                    if (enabled) onOptions(options.filterIndexed { i, _ -> i != index })
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (options.size < Polls.MAX_OPTIONS) Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled) { onOptions(options + "") }
                .padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph(R.drawable.ic_plus_circle, p.accent, 18.dp)
                Text("Add option", color = p.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 6.dp))
            }
            Spacer(Modifier.weight(1f))
            Text("Pick several", color = p.ink2, fontSize = 14.sp, modifier = Modifier.padding(end = 8.dp))
            ChorusSwitch(multiple, if (enabled) onMultiple else null)
        }
    }
}

/** One tap to put an emoji into the text: the quick row and the rest, no search. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EmojiDrawer(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    Drawer(onDismiss, "Emoji") {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (emoji in ChatReactions.QUICK + ChatReactions.MORE) {
                Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button) { onPick(emoji); onDismiss() }
                    .semantics { contentDescription = emoji }, contentAlignment = Alignment.Center) { Text(emoji, fontSize = 26.sp) }
            }
        }
    }
}

/** Inserts [text] at the caret, or over the selection. */
internal fun insertAtCaret(value: TextFieldValue, text: String): TextFieldValue {
    val start = value.selection.min.coerceIn(0, value.text.length); val end = value.selection.max.coerceIn(0, value.text.length)
    return TextFieldValue(value.text.substring(0, start) + text + value.text.substring(end), TextRange(start + text.length))
}

/** The link drawer behind the composer's link buttons: the URL for the selected text. */
@Composable
internal fun LinkDrawer(selection: TextFieldValue, onDismiss: () -> Unit, onInsert: (MappedFormatting) -> Unit) {
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Drawer(onDismiss, "Insert link", footer = {
        DrawerButton("Insert link", {
            runCatching { EditorFormatting.mappedLink(selection, url.trim()) }.onSuccess { onInsert(it); onDismiss() }
                .onFailure { error = it.message }
        }, enabled = url.isNotBlank())
    }) {
        ChorusField(url, { url = it; error = null }, label = { Text("Link URL") }, placeholder = { Text("https://") }, singleLine = true,
            isError = error != null, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri))
        if (error != null) FormNote(error.orEmpty(), warning = true)
    }
}
