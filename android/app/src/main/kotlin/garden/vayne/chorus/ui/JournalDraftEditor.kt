package garden.vayne.chorus.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.JournalDraft
import garden.vayne.chorus.data.JournalDraftFile
import garden.vayne.chorus.data.JournalDraftSend
import garden.vayne.chorus.data.Polls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Property bindings keep every composer control on the tested session's write path. */
internal class JournalField<T>(private val read: () -> T, private val write: (T) -> Unit) : ReadWriteProperty<Any?, T> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): T = read()
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) = write(value)
}

internal fun PendingAttachment.durable() = JournalDraftFile(uri.toString(), name, mime, alt, spoiler)
internal fun JournalDraftFile.pending() = PendingAttachment(Uri.parse(uri), name, mime, alt, spoiler)
internal fun JournalDraftFile.readable(ctx: Context): Boolean = try {
    ctx.contentResolver.openInputStream(Uri.parse(uri))?.use { true } ?: false
} catch (_: Exception) { false }

internal suspend fun validateJournalDraft(chorus: Chorus, ctx: Context, owner: String, account: String, draft: JournalDraft) {
    check(chorus.editorOwner == owner) { "The account changed. Reopen this draft in its account." }
    withContext(Dispatchers.IO) { JournalDraftSend.validate(chorus.model.value, account, draft) { it.readable(ctx) } }
}

@Composable
internal fun JournalPollDraftFields(on: Boolean, onToggle: (Boolean) -> Unit, options: List<String>,
    onOptions: (List<String>) -> Unit, multiple: Boolean, onMultiple: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(on, onToggle); Text("Add a poll") }
    if (!on) return
    options.forEachIndexed { index, text ->
        OutlinedTextField(text, { value -> onOptions(options.mapIndexed { i, old -> if (i == index) value.take(200) else old }) },
            label = { Text("Option ${index + 1}") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
    if (options.size < Polls.MAX_OPTIONS) TextButton(onClick = { onOptions(options + "") }) { Text("Add option") }
    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(multiple, onMultiple); Text("Allow more than one choice") }
}
