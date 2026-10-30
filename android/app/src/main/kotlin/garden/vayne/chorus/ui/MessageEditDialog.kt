package garden.vayne.chorus.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun MessageEditDialog(chorus: Chorus, model: Model, id: String, onDone: () -> Unit) {
    val account = chorus.device?.accountId
    val owner = chorus.editorOwner
    val actions = rememberCoroutineScope()
    val editor = rememberDurableEditor(chorus, "message-edit:$id", MessageEditorDraft::encode, MessageEditorDraft::decode) {
        MessageEditorDraft.open(requireNotNull(model.chatMessages.values.flatten().find { it.id == id }) { "This message is unavailable. Reopen Edit." })
    }
    val draft = editor.value
    if (draft == null) { DraftLoading(editor.error, editor::retry, onDone); return }
    val original = draft.original
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val controllers = draft.parts.mapIndexed { i, part -> rememberRichEditor(editor, part, "part:$i") { saved, history ->
        saved.copy(parts = saved.parts.toMutableList().apply { set(i, history) })
    } }
    fun close() {
        if (!busy) try { editor.finish(); onDone() }
        catch (e: Exception) { error = "Could not discard this draft. Try again." }
    }
    AlertDialog(onDismissRequest = ::close, title = { Text("Edit message") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (controllers.size > 1) Text("Each part keeps its original speaker.")
            for ((i, controller) in controllers.withIndex()) {
                RichEditorField(controller, if (controllers.size == 1) "Message" else "Part ${i + 1} · " +
                    original.segments[i].authors.map { model.member(it)?.shownName ?: "Someone" }.joinToString(" & "),
                    enabled = !busy, minLines = 2, maxLines = 6)
            }
            (error ?: editor.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !busy && controllers.any { it.value.text.isNotBlank() }, onClick = {
        val current = model.chatMessages[original.channelId]?.find { it.id == original.id }
        if (account == null || original.accountId != account || current == null || current.text != original.text ||
            current.entities != original.entities || current.segments != original.segments || current.accountId != account) {
            error = "The message changed. Close Edit and reopen it."
            return@TextButton
        }
        val space = model.channels.find { it.id == original.channelId }?.spaceId
        busy = true; error = null
        controllers.forEach { it.capture() }
        val snapshot = requireNotNull(editor.value)
        actions.launch {
            try {
                val payload = withContext(Dispatchers.Default) {
                    ChatEdit.payload(original, snapshot.parts.map { it.current.text }, ChatEdit.names(original, model), snapshot.parts.map { it.legacyState() })
                }
                check(chorus.device?.accountId == account) { "The account changed. Reopen Edit in its account." }
                chorus.create("message.edit", original.id, payload, scope = "space:${requireNotNull(space) { "Space unavailable" }}", expectedEditorOwner = owner)
                editor.finish(); onDone()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not edit the message." }
            finally { busy = false }
        }
    }) { Text(if (busy) "Saving…" else "Save") } }, dismissButton = {
        TextButton(enabled = !busy, onClick = ::close) { Text("Cancel") }
    })
}
