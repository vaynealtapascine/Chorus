package garden.vayne.chorus.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.data.Blobs
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.UploadWork
import garden.vayne.chorus.designsystem.LocalChorusPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Import from PluralKit (D-005): members, groups and switch history from `pk;export`, as on web. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun PkImportSheet(chorus: Chorus, onDismiss: () -> Unit) {
    val p = LocalChorusPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf<String?>(null) }
    var plan by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var avatarNotes by remember { mutableStateOf(emptyList<String>()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            error = null; done = null; plan = null
            try {
                val read = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readBytes()
                        require(bytes.size <= 64 * 1024 * 1024) { "This file is too large for a PluralKit export." }
                        bytes.toString(Charsets.UTF_8)
                    } ?: error("Could not open this file.")
                }
                plan = JSONObject(withContext(Dispatchers.Default) { uniffi.chorus_ffi.planPluralkit(read, chorus.accountScope) })
                text = read
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "This doesn't look like a PluralKit export." }
        }
    }
    fun run() {
        val export = text ?: return
        busy = true; avatarNotes = emptyList()
        scope.launch {
            try {
                val (added, byPkId) = chorus.importPluralkit(export)
                done = added
                // avatars live on PluralKit's servers: fetch each once and keep it like any picked image
                val members = JSONObject(export).optJSONArray("members")
                val account = chorus.device?.accountId.orEmpty()
                var queued = false
                for (i in 0 until (members?.length() ?: 0)) {
                    val m = members?.optJSONObject(i) ?: continue
                    val pkId = m.optString("id")
                    val url = m.optString("avatar_url")
                    if (pkId.isEmpty() || url.isEmpty()) continue
                    try {
                        val id = byPkId[pkId] ?: error("member not found after import")
                        val staged = withContext(Dispatchers.IO) {
                            val u = URL(url)
                            require(u.protocol == "https" || u.protocol == "http") { "unsupported URL" }
                            val c = u.openConnection() as HttpURLConnection
                            c.connectTimeout = 10_000; c.readTimeout = 20_000
                            try {
                                check(c.responseCode in 200..299) { "HTTP ${c.responseCode}" }
                                val mime = c.contentType?.substringBefore(';')?.trim().orEmpty()
                                require(mime.startsWith("image/")) { "not an image" }
                                c.inputStream.use { Blobs.stage(ctx, it, mime, account) }
                            } finally { c.disconnect() }
                        }
                        chorus.create("member.set", id, JSONObject().put("avatar_blob", staged.hash))
                        queued = true
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) {
                        avatarNotes = avatarNotes + "$pkId: avatar could not be fetched (${e.message}). You can add it in the member editor."
                    }
                }
                if (queued) UploadWork.enqueue(ctx)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not import. Try again." }
            finally { busy = false }
        }
    }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, containerColor = p.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Import from PluralKit", color = p.ink, style = MaterialTheme.typography.titleMedium)
            Text("In Discord, run pk;export and download the file PluralKit sends you. Importing again later only adds what's new.",
                color = p.ink2, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(enabled = !busy, onClick = { picker.launch("*/*") }) { Text(if (plan == null) "Choose the export file" else "Choose another file") }
            error?.let { Text(it, color = p.danger) }
            plan?.let { pl ->
                Text("${pl.optInt("members")} members · ${pl.optInt("groups")} groups · ${pl.optInt("switches")} switches (imported silently, no notifications)",
                    color = p.ink, fontWeight = FontWeight.Medium)
                val warnings = pl.optJSONArray("warnings")
                val notes = List(warnings?.length() ?: 0) { warnings!!.optString(it) }
                    .filter { done == null || !it.contains("avatar not imported yet") } + avatarNotes
                if (notes.isNotEmpty()) {
                    var open by remember { mutableStateOf(false) }
                    TextButton(onClick = { open = !open }) { Text("${notes.size} notes") }
                    if (open) for (n in notes) Text("• $n", color = p.ink3, style = MaterialTheme.typography.bodySmall)
                }
                done?.let { Text("Imported $it new item${if (it == 1) "" else "s"}.${if (it == 0) " Everything was already here." else ""}", color = p.ok) }
                Button(enabled = !busy, onClick = ::run) { Text(if (busy) "Importing…" else if (done == null) "Import" else "Import again") }
            }
        }
    }
}
