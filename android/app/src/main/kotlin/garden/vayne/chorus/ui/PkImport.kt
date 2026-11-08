package garden.vayne.chorus.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.data.Blobs
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.UploadWork
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Import from PluralKit (D-005): members, groups and switch history from `pk;export`, as on web. */
@Composable
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
    var fileName by remember { mutableStateOf<String?>(null) }
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
                fileName = runCatching {
                    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    }
                }.getOrNull() ?: "pk;export"
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
                        avatarNotes = avatarNotes + "$pkId: avatar could not be fetched (${e.message})"
                    }
                }
                if (queued) UploadWork.enqueue(ctx)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not import. Try again." }
            finally { busy = false }
        }
    }
    Drawer({ if (!busy) onDismiss() }, "Import from PluralKit", dismissible = !busy, footer = {
        if (plan != null) DrawerButton(if (done == null) "Import" else "Import again", ::run, busy = busy)
    }) {
        FormCard {
            FormValueRow("Export file", fileName ?: "Choose", R.drawable.ic_file, enabled = !busy) { picker.launch("*/*") }
        }
        error?.let { FormNote(it, warning = true) }
        plan?.let { pl ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((n, label) in listOf(pl.optInt("members") to "members", pl.optInt("groups") to "groups", pl.optInt("switches") to "switches")) {
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(cardColor()).padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$n", color = p.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(label, color = p.ink3, fontSize = 13.sp)
                    }
                }
            }
            val warnings = pl.optJSONArray("warnings")
            val notes = List(warnings?.length() ?: 0) { warnings!!.optString(it) }
                .filter { done == null || !it.contains("avatar not imported yet") } + avatarNotes
            if (notes.isNotEmpty()) {
                var open by remember { mutableStateOf(false) }
                FormCard { NavRow("${notes.size} note${if (notes.size == 1) "" else "s"}", R.drawable.ic_alert, chevron = false) { open = !open } }
                if (open) FormNote(notes.joinToString("\n"))
            }
            done?.let { FormNote(if (it == 0) "Everything was already here." else "Imported $it new item${if (it == 1) "" else "s"}.", R.drawable.ic_check) }
        }
    }
}
