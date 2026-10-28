package garden.vayne.chorus.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Selection edits only. Chorus core still parses/serializes all rich-text entities on Post.
 * A composing IME owns its range until committed; toolbar actions never discard it. */
internal object EditorFormatting {
    fun active(value: TextFieldValue, marker: String): Boolean {
        val start = value.selection.min
        val end = value.selection.max
        return start >= marker.length && value.text.substring(start - marker.length, start) == marker &&
            value.text.substring(end).startsWith(marker)
    }

    fun inline(value: TextFieldValue, marker: String): TextFieldValue {
        if (value.composition != null) return value
        val start = value.selection.min
        val end = value.selection.max
        val remove = active(value, marker)
        val text = if (remove) value.text.removeRange(end, end + marker.length).removeRange(start - marker.length, start)
            else value.text.substring(0, start) + marker + value.text.substring(start, end) + marker + value.text.substring(end)
        val shift = if (remove) -marker.length else marker.length
        return TextFieldValue(text, TextRange(value.selection.start + shift, value.selection.end + shift))
    }

    fun lines(value: TextFieldValue, prefix: String, numbered: Boolean = false): TextFieldValue {
        if (value.composition != null) return value
        val start = value.text.lastIndexOf('\n', (value.selection.min - 1).coerceAtLeast(-1)) + 1
        val end = value.text.indexOf('\n', value.selection.max).takeIf { it >= 0 } ?: value.text.length
        val selected = value.text.substring(start, end).split('\n')
        val added = selected.mapIndexed { i, line -> (if (numbered) "${i + 1}. " else prefix) + line }.joinToString("\n")
        val text = value.text.replaceRange(start, end, added)
        return TextFieldValue(text, TextRange(start, start + added.length))
    }

    fun link(value: TextFieldValue, url: String): TextFieldValue {
        if (value.composition != null) return value
        require(url.startsWith("https://") || url.startsWith("http://") || url.startsWith("mailto:")) { "Use an https, http or mailto link." }
        require(url.none { it.isWhitespace() || it == ')' || it == '\\' }) { "Use a link without spaces or closing brackets." }
        val start = value.selection.min
        val end = value.selection.max
        val label = value.text.substring(start, end).ifEmpty { url }
        val added = "[$label]($url)"
        return TextFieldValue(value.text.replaceRange(start, end, added), TextRange(start + 1, start + 1 + label.length))
    }
}
