package garden.vayne.chorus.ui

import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import garden.vayne.chorus.designsystem.LocalChorusPalette
import org.json.JSONArray

/** Render the core's entities rather than reparsing Markdown. Hidden spoiler characters are
 * replaced before accessibility/selection sees them; links inside hidden spoilers cannot open. */
@Composable
internal fun RichBody(text: String, entities: String, modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE, onOverflow: (Boolean) -> Unit = {},
    style: TextStyle = MaterialTheme.typography.bodyLarge, onPlainClick: () -> Unit = {}) {
    val p = LocalChorusPalette.current
    val uri = LocalUriHandler.current
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
                    if (covered) addStyle(SpanStyle(color = p.ink3, background = p.surface3), r.start, r.end)
                } else if (!covered) {
                    val span = when (r.type) {
                        "bold" -> SpanStyle(fontWeight = FontWeight.Bold)
                        "italic" -> SpanStyle(fontStyle = FontStyle.Italic)
                        "underline" -> SpanStyle(textDecoration = TextDecoration.Underline)
                        "strikethrough" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                        "code", "pre" -> SpanStyle(fontFamily = FontFamily.Monospace, background = p.surface2)
                        "blockquote", "expandable_blockquote" -> SpanStyle(color = p.ink2, fontStyle = FontStyle.Italic)
                        "url", "text_link" -> SpanStyle(color = p.accent, textDecoration = TextDecoration.Underline)
                        else -> null
                    }
                    if (span != null) addStyle(span, r.start, r.end)
                    if (r.type == "url" || r.type == "text_link") {
                        val link = if (r.type == "url") text.substring(r.start, r.end) else r.url.orEmpty()
                        if (link.startsWith("https://") || link.startsWith("http://") || link.startsWith("mailto:"))
                            addStringAnnotation("link", link, r.start, r.end)
                    }
                }
            }
        }
    }
    ClickableText(annotated, modifier = modifier, style = style.copy(color = p.ink),
        maxLines = maxLines, overflow = TextOverflow.Ellipsis, onTextLayout = { onOverflow(it.hasVisualOverflow) },
        onClick = { offset ->
            val spoiler = annotated.getStringAnnotations("spoiler", offset, offset).firstOrNull()?.item?.toIntOrNull()
            if (spoiler != null) revealed = if (spoiler in revealed) revealed - spoiler else revealed + spoiler
            else {
                val link = annotated.getStringAnnotations("link", offset, offset).firstOrNull()
                if (link != null) runCatching { uri.openUri(link.item) } else onPlainClick()
            }
        })
}
