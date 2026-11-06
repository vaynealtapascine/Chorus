package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R

/** No heading/list entities are invented. Lists insert ordinary readable bullet/number text;
 * quote and inline actions use exactly the syntax understood by chorus-core::text. */
@Composable
internal fun JournalFormatToolbar(value: TextFieldValue, enabled: Boolean, onMappedChange: ((MappedFormatting) -> Unit)? = null, onChange: (TextFieldValue) -> Unit) {
    fun change(edit: MappedFormatting) { onMappedChange?.invoke(edit) ?: onChange(edit.value) }
    val p = LocalChorusPalette.current
    var linkOpen by remember { mutableStateOf(false) }
    var linkSelection by remember { mutableStateOf(value) }
    var url by remember { mutableStateOf("") }
    var linkError by remember { mutableStateOf<String?>(null) }
    val ready = enabled && value.composition == null
    LazyRow(Modifier.fillMaxWidth().background(p.surface), contentPadding = PaddingValues(horizontal = 4.dp)) {
        for ((icon, name, marker) in listOf(
            Triple(R.drawable.ic_format_bold, "Bold", "**"), Triple(R.drawable.ic_format_italic, "Italic", "_"),
            Triple(R.drawable.ic_format_strike, "Strikethrough", "~~"), Triple(R.drawable.ic_format_code, "Code", "`"),
            Triple(R.drawable.ic_format_eye_off, "Spoiler", "||"))) item {
            FormatButton(icon, name, ready, EditorFormatting.active(value, marker)) { change(EditorFormatting.mappedInline(value, marker)) }
        }
        item { FormatButton(R.drawable.ic_format_quote, "Quote", ready) { change(EditorFormatting.mappedLines(value, "> ")) } }
        item { FormatButton(R.drawable.ic_format_list_ul, "Bulleted text", ready) { change(EditorFormatting.mappedLines(value, "• ")) } }
        item { FormatButton(R.drawable.ic_format_list_ol, "Numbered text", ready) { change(EditorFormatting.mappedLines(value, "", numbered = true)) } }
        item { FormatButton(R.drawable.ic_link, "Insert link", ready) { linkSelection = value; url = ""; linkError = null; linkOpen = true } }
    }
    if (linkOpen) Drawer({ linkOpen = false }, "Insert link", "On the selected words, or as its own text", footer = {
        DrawerButton("Insert link", {
            runCatching { EditorFormatting.mappedLink(linkSelection, url.trim()) }.onSuccess { change(it); linkOpen = false }
                .onFailure { linkError = it.message }
        }, enabled = url.isNotBlank())
    }) {
        ChorusField(url, { url = it; linkError = null }, label = { Text("Link URL") }, placeholder = { Text("https://") }, singleLine = true,
            isError = linkError != null, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri))
        if (linkError != null) FormNote(linkError.orEmpty(), warning = true)
    }
}

@Composable
private fun FormatButton(icon: Int, label: String, enabled: Boolean, selected: Boolean = false, action: () -> Unit) {
    val p = LocalChorusPalette.current
    IconButton(onClick = action, enabled = enabled, modifier = Modifier.size(48.dp)
        .background(if (selected) p.accentSoft else p.surface, RoundedCornerShape(10.dp))) {
        ChatIcon(icon, label, if (!enabled) p.ink3.copy(alpha = .5f) else if (selected) p.accent else p.ink2)
    }
}
