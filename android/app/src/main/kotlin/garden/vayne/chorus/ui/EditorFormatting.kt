package garden.vayne.chorus.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Exact source maps for toolbar operations: linear even for thousands of formatted lines. */
internal data class MappedFormatting(val value: TextFieldValue, val sources: IntArray, val original: TextFieldValue)
internal object EditorFormatting {
    private data class Replacement(val start: Int, val end: Int, val text: String, val sources: List<Int>)
    private fun apply(value: TextFieldValue, edits: List<Replacement>, selection: TextRange): MappedFormatting {
        if (value.composition != null) return MappedFormatting(value, IntArray(value.text.length) { it }, value)
        val text = StringBuilder(); val map = mutableListOf<Int>(); var at = 0
        for (edit in edits) {
            text.append(value.text.substring(at, edit.start)); map.addAll(at until edit.start)
            text.append(edit.text); map.addAll(edit.sources); at = edit.end
        }
        text.append(value.text.substring(at)); map.addAll(at until value.text.length)
        return MappedFormatting(TextFieldValue(text.toString(), selection), map.toIntArray(), value)
    }
    fun active(value: TextFieldValue, marker: String): Boolean {
        val start = value.selection.min; val end = value.selection.max
        return start >= marker.length && value.text.substring(start - marker.length, start) == marker && value.text.substring(end).startsWith(marker)
    }
    fun mappedInline(value: TextFieldValue, marker: String): MappedFormatting {
        val start = value.selection.min; val end = value.selection.max; val remove = active(value, marker)
        val edits = if (remove) listOf(Replacement(start - marker.length, start, "", emptyList()), Replacement(end, end + marker.length, "", emptyList()))
            else listOf(Replacement(start, start, marker, List(marker.length) { -1 }), Replacement(end, end, marker, List(marker.length) { -1 }))
        val shift = if (remove) -marker.length else marker.length
        return apply(value, edits, TextRange(value.selection.start + shift, value.selection.end + shift))
    }
    fun inline(value: TextFieldValue, marker: String) = mappedInline(value, marker).value
    fun mappedLines(value: TextFieldValue, prefix: String, numbered: Boolean = false): MappedFormatting {
        val start = value.text.lastIndexOf('\n', (value.selection.min - 1).coerceAtLeast(-1)) + 1
        val end = value.text.indexOf('\n', value.selection.max).takeIf { it >= 0 } ?: value.text.length
        var at = start
        val edits = value.text.substring(start, end).split('\n').mapIndexed { i, line ->
            val added = if (numbered) "${i + 1}. " else prefix
            Replacement(at, at, added, List(added.length) { -1 }).also { at += line.length + 1 }
        }
        return apply(value, edits, TextRange(start, end + edits.sumOf { it.text.length }))
    }
    fun lines(value: TextFieldValue, prefix: String, numbered: Boolean = false) = mappedLines(value, prefix, numbered).value
    fun mappedLink(value: TextFieldValue, url: String): MappedFormatting {
        require(url.startsWith("https://") || url.startsWith("http://") || url.startsWith("mailto:")) { "Use an https, http or mailto link." }
        require(url.none { it.isWhitespace() || it == ')' || it == '\\' }) { "Use a link without spaces or closing brackets." }
        val start = value.selection.min; val end = value.selection.max
        val label = value.text.substring(start, end).ifEmpty { url }
        val added = "[$label]($url)"
        val map = listOf(-1) + (if (start == end) List(label.length) { -1 } else (start until end).toList()) + List(url.length + 3) { -1 }
        return apply(value, listOf(Replacement(start, end, added, map)), TextRange(start + 1, start + 1 + label.length))
    }
    fun link(value: TextFieldValue, url: String) = mappedLink(value, url).value
}
