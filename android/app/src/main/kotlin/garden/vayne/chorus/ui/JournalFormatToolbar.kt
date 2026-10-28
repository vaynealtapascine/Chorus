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
internal fun JournalFormatToolbar(value: TextFieldValue, enabled: Boolean, onChange: (TextFieldValue) -> Unit) {
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
            FormatButton(icon, name, ready, EditorFormatting.active(value, marker)) { onChange(EditorFormatting.inline(value, marker)) }
        }
        item { FormatButton(R.drawable.ic_format_quote, "Quote", ready) { onChange(EditorFormatting.lines(value, "> ")) } }
        item { FormatButton(R.drawable.ic_format_list_ul, "Bulleted text", ready) { onChange(EditorFormatting.lines(value, "• ")) } }
        item { FormatButton(R.drawable.ic_format_list_ol, "Numbered text", ready) { onChange(EditorFormatting.lines(value, "", numbered = true)) } }
        item { FormatButton(R.drawable.ic_link, "Insert link", ready) { linkSelection = value; url = ""; linkError = null; linkOpen = true } }
    }
    if (linkOpen) AlertDialog(onDismissRequest = { linkOpen = false }, title = { Text("Insert link") }, text = {
        Column {
            OutlinedTextField(url, { url = it; linkError = null }, label = { Text("Link URL") }, singleLine = true,
                isError = linkError != null, modifier = Modifier.fillMaxWidth())
            if (linkError != null) Text(linkError.orEmpty(), color = p.danger)
        }
    }, confirmButton = { TextButton(enabled = url.isNotBlank(), onClick = {
        runCatching { EditorFormatting.link(linkSelection, url.trim()) }.onSuccess { onChange(it); linkOpen = false }
            .onFailure { linkError = it.message }
    }) { Text("Insert") } }, dismissButton = { TextButton(onClick = { linkOpen = false }) { Text("Cancel") } })
}

@Composable
private fun FormatButton(icon: Int, label: String, enabled: Boolean, selected: Boolean = false, action: () -> Unit) {
    val p = LocalChorusPalette.current
    IconButton(onClick = action, enabled = enabled, modifier = Modifier.size(48.dp)
        .background(if (selected) p.accentSoft else p.surface, RoundedCornerShape(10.dp))) {
        ChatIcon(icon, label, if (!enabled) p.ink3.copy(alpha = .5f) else if (selected) p.accent else p.ink2)
    }
}
