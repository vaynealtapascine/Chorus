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
                RichEditHistory.open(post.text, post.entities, raw.getString("markup"), RichTextEdit.POST_NAMES)
            }
            controller = RichEditorController(history) {}
        } catch (e: Exception) { error = e.message ?: "Could not open this post." }
    }
    Drawer(onDismiss, if (post.kind == "entry") "Edit entry" else "Edit post", "Readers can see that it was edited, and its history.",
        dismissible = !busy, footer = {
            DrawerButton("Save changes", {
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
            }, enabled = controller?.history?.current?.text?.isNotBlank() == true, busy = busy)
        }) {
        if (post.kind == "entry") ChorusField(title, { title = it }, label = { Text("Title") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
        controller?.let { RichEditorField(it, "Post text", minLines = 5, enabled = !busy, formatting = true) }
        ChorusField(cw, { cw = it }, label = { Text("Content warning (optional)") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
        error?.let { FormNote(it, warning = true) }
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
    val p = garden.vayne.chorus.designsystem.LocalChorusPalette.current
    Drawer(onDismiss, "Edit history", if (items.isEmpty()) null else "${items.size} ${if (items.size == 1) "version" else "versions"}") {
        if (loading) Text("Loading history…", color = p.ink2)
        error?.let { FormNote(it, warning = true); DrawerButton("Retry", { retry++ }, ButtonKind.Neutral) }
        for (row in items) key(row.optInt("rev")) {
            var reveal by remember { mutableStateOf(false) }
            val warning = row.optString("cw").takeIf { !row.isNull("cw") && it.isNotBlank() }
            FormCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${if (row.optInt("rev") == 0) "Original" else "Edit ${row.optInt("rev")}"} · " +
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(row.optLong("at"))), color = p.ink3,
                        style = MaterialTheme.typography.labelMedium)
                    if (warning != null) TextButton(onClick = { reveal = !reveal }) { Text("Content warning: $warning · ${if (reveal) "Hide" else "Show"}") }
                    if (warning == null || reveal) {
                        if (!row.isNull("title")) Text(row.optString("title"), style = MaterialTheme.typography.titleSmall)
                        RichBody(row.optString("text"), (row.optJSONArray("entities") ?: JSONArray()).toString())
                    }
                }
            }
        }
    }
}
