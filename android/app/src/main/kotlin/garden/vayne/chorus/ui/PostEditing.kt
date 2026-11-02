package garden.vayne.chorus.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import garden.vayne.chorus.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

@Composable
internal fun PostEditDialog(post: JournalPost, chorus: Chorus, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var controller by remember(post.id) { mutableStateOf<RichEditorController?>(null) }
    var title by remember(post.id) { mutableStateOf(post.title.orEmpty()) }
    var cw by remember(post.id) { mutableStateOf(post.cw.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(post.id) {
        try {
            val history = withContext(Dispatchers.Default) {
                val raw = JSONObject(PostEditing.open(post))
                RichEditHistory.open(post.text, post.entities, raw.getString("markup"))
            }
            controller = RichEditorController(history) {}
        } catch (e: Exception) { error = e.message ?: "Could not open this post." }
    }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Edit post", style = MaterialTheme.typography.titleLarge)
                if (post.kind == "entry") OutlinedTextField(title, { title = it }, label = { Text("Title") }, enabled = !busy)
                controller?.let { RichEditorField(it, "Post text", minLines = 5, enabled = !busy, formatting = true) }
                OutlinedTextField(cw, { cw = it }, label = { Text("Content warning") }, enabled = !busy)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row {
                    TextButton(enabled = !busy && controller?.history?.current?.text?.isNotBlank() == true, onClick = {
                        busy = true; error = null
                        controller!!.capture()
                        val original = controller!!.history.legacyState(); val draft = controller!!.history.current.text; val draftTitle = title; val warning = cw
                        scope.launch {
                            try {
                                val payload = withContext(Dispatchers.Default) { PostEditing.payload(post, original, draft, draftTitle, warning) }
                                chorus.create("post.edit", post.id, payload); onDismiss()
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) { error = e.message ?: "Could not save. Try again." }
                            finally { busy = false }
                        }
                    }) { Text("Save changes") }
                    TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}

/** Read on demand from the server's compact history, using the post's audience check. */
@Composable
internal fun PostHistoryDialog(postId: String, chorus: Chorus, onDismiss: () -> Unit) {
    var items by remember(postId) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var error by remember(postId) { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var retry by remember { mutableStateOf(0) }
    LaunchedEffect(postId, retry) {
        items = emptyList(); loading = true; error = null
        try {
            val dev = checkNotNull(chorus.device)
            val rows = Api.call("GET", dev.base, "/posts/$postId/revisions", null, dev.session).getJSONArray("items")
            items = List(rows.length()) { rows.getJSONObject(it) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { error = "History needs a connection and permission to read this post. Try again." }
        finally { loading = false }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Edit history") },
        text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loading) Text("Loading history…")
            error?.let { Text(it); TextButton(onClick = { retry++ }) { Text("Retry") } }
            for (row in items) key(row.optInt("rev")) {
                var reveal by remember { mutableStateOf(false) }
                val warning = row.optString("cw").takeIf { !row.isNull("cw") && it.isNotBlank() }
                Text("${if (row.optInt("rev") == 0) "Original" else "Edit ${row.optInt("rev")}"} · ${DateFormat.getDateTimeInstance().format(Date(row.optLong("at")))}")
                if (warning != null) TextButton(onClick = { reveal = !reveal }) { Text("Content warning: $warning · ${if (reveal) "Hide" else "Show"}") }
                if (warning == null || reveal) {
                    if (!row.isNull("title")) Text(row.optString("title"), style = MaterialTheme.typography.titleSmall)
                    RichBody(row.optString("text"), (row.optJSONArray("entities") ?: JSONArray()).toString())
                }
                HorizontalDivider()
            }
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}
