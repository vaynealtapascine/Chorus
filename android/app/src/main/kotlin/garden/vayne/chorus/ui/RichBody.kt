package garden.vayne.chorus.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.lerp
import garden.vayne.chorus.designsystem.ChorusPalette
import garden.vayne.chorus.designsystem.LocalChorusPalette
import org.json.JSONArray

/** A heading (D-134): bold and larger than the body it sits in; level 1 or 2, anything else is body. */
internal fun headingSpan(level: Int, body: TextStyle): SpanStyle? {
    val size = if (body.fontSize.isSp) body.fontSize.value else 16f
    return when (level) {
        1 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = (size * 1.3f).sp)
        2 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = (size * 1.12f).sp)
        else -> null
    }
}

/** A hidden spoiler is one solid bar: its placeholder glyphs share the background's opaque colour. */
internal fun spoilerBar(p: ChorusPalette): SpanStyle = lerp(p.surface3, p.ink3, .45f).let { SpanStyle(color = it, background = it) }

/** Plain previews keep [garden.vayne.chorus.data.RichTextPresentation]'s ▪ placeholders; draw each
 * hidden run (with the spaces between its words) as a solid bar instead of squares. */
@Composable
internal fun spoilerPreview(text: String): AnnotatedString {
    val p = LocalChorusPalette.current
    return remember(text, p) {
        buildAnnotatedString {
            append(text)
            Regex("▪+(?:\\s+▪+)*").findAll(text).forEach { addStyle(spoilerBar(p), it.range.first, it.range.last + 1) }
        }
    }
}

/** Render the core's entities rather than reparsing Markdown. Hidden spoiler characters are
 * replaced before accessibility/selection sees them; links inside hidden spoilers cannot open. */
@Composable
internal fun RichBody(text: String, entities: String, modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE, onOverflow: (Boolean) -> Unit = {},
    style: TextStyle = MaterialTheme.typography.bodyLarge, onPlainClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null, onDoubleClick: (() -> Unit)? = null) {
    val p = LocalChorusPalette.current
    val uri = LocalUriHandler.current
    // our own posts and messages open in the app (D-109)
    val openOwn = LocalChorusLinks.current
    var revealed by rememberSaveable(text, entities) { mutableStateOf(listOf<Int>()) }
    val annotated = remember(text, entities, revealed, p) {
        val prepared = garden.vayne.chorus.data.RichTextPresentation.prepare(text, entities, revealed)
        val ranges = prepared.spans
        buildAnnotatedString {
            append(prepared.text)
            for (r in ranges) {
                val covered = r.start in prepared.hidden
                if (r.type == "spoiler") {
                    addStringAnnotation("spoiler", r.start.toString(), r.start, r.end)
                    if (covered) addStyle(spoilerBar(p), r.start, r.end)
                } else if (!covered) {
                    val span = when (r.type) {
                        "bold" -> SpanStyle(fontWeight = FontWeight.Bold)
                        "italic" -> SpanStyle(fontStyle = FontStyle.Italic)
                        "underline" -> SpanStyle(textDecoration = TextDecoration.Underline)
                        "strikethrough" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                        "code", "pre" -> SpanStyle(fontFamily = FontFamily.Monospace, background = p.surface2)
                        "blockquote", "expandable_blockquote" -> SpanStyle(color = p.ink2, fontStyle = FontStyle.Italic)
                        "url", "text_link" -> SpanStyle(color = p.accent, textDecoration = TextDecoration.Underline)
                        // D-134: posts and entries only; the level decides the size
                        "heading" -> headingSpan(r.level, style)
                        else -> null
                    }
                    if (span != null) addStyle(span, r.start, r.end)
                    // a quote keeps a rule down its left edge (F6.12): indent its lines, draw the rule over the gap
                    if (r.type == "blockquote" || r.type == "expandable_blockquote") {
                        addStringAnnotation("quote", "", r.start, r.end)
                        addStyle(ParagraphStyle(textIndent = TextIndent(14.sp, 14.sp)), r.start, r.end)
                    }
                    if (r.type == "url" || r.type == "text_link") {
                        val link = if (r.type == "url") text.substring(r.start, r.end) else r.url.orEmpty()
                        if (link.startsWith("https://") || link.startsWith("http://") || link.startsWith("mailto:"))
                            addStringAnnotation("link", link, r.start, r.end)
                    }
                }
            }
        }
    }
    val plainClick by rememberUpdatedState(onPlainClick)
    val longClick by rememberUpdatedState(onLongClick)
    val doubleClick by rememberUpdatedState(onDoubleClick)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    fun activate(offset: Int) {
            val spoiler = annotated.getStringAnnotations("spoiler", offset, offset).firstOrNull()?.item?.toIntOrNull()
            if (spoiler != null) revealed = if (spoiler in revealed) revealed - spoiler else revealed + spoiler
            else {
                val link = annotated.getStringAnnotations("link", offset, offset).firstOrNull()
                if (link != null) { if (!openOwn(link.item)) runCatching { uri.openUri(link.item) } } else plainClick()
            }
    }
    val rule = p.accent
    BasicText(annotated, modifier = modifier.drawBehind {
        val l = layout ?: return@drawBehind
        for (q in annotated.getStringAnnotations("quote", 0, annotated.length)) {
            if (q.end <= q.start || q.end > l.layoutInput.text.length) continue
            val top = l.getLineTop(l.getLineForOffset(q.start)); val bottom = l.getLineBottom(l.getLineForOffset(q.end - 1))
            drawLine(rule, androidx.compose.ui.geometry.Offset(1.5.dp.toPx(), top), androidx.compose.ui.geometry.Offset(1.5.dp.toPx(), bottom), 3.dp.toPx())
        }
    }.semantics {
        if (onLongClick != null) {
            onClick("Message actions") { plainClick(); true }
            onLongClick("Message actions") { longClick?.invoke(); true }
        }
        customActions = annotated.getStringAnnotations(0, annotated.length).distinctBy { it.tag to it.item }.map { annotation ->
            CustomAccessibilityAction(if (annotation.tag == "spoiler") "Reveal or hide spoiler" else "Open ${annotation.item}") {
                activate(annotation.start); true
            }
        }
    }.pointerInput(annotated, onLongClick != null, onDoubleClick != null) {
        detectTapGestures(onDoubleTap = if (onDoubleClick != null) ({ doubleClick?.invoke() }) else null, onLongPress = if (onLongClick != null) ({ longClick?.invoke() }) else null,
            onTap = { position -> layout?.let { activate(it.getOffsetForPosition(position)) } })
    }, style = style.copy(color = p.ink), maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout = it; onOverflow(it.hasVisualOverflow) })
}
