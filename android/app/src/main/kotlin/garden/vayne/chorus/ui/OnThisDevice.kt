package garden.vayne.chorus.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.DeviceSpace
import garden.vayne.chorus.data.Status
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import kotlinx.coroutines.launch

/** F9.6 On this device: space used in three parts, keeping everything offline, a full sync, clearing files. */
@Composable
internal fun OnThisDeviceScreen(chorus: Chorus, onBack: () -> Unit) {
    val p = LocalChorusPalette.current
    val scope = rememberCoroutineScope()
    val keepEverything by chorus.keepEverything.collectAsState()
    val files by chorus.fileProgress.collectAsState()
    val recheck by chorus.recheckProgress.collectAsState()
    val status by chorus.status.collectAsState()
    val upToDate by chorus.upToDate.collectAsState()
    val lastSynced by chorus.lastSynced.collectAsState()
    var usage by remember { mutableStateOf<DeviceSpace.Usage?>(null) }
    var syncBusy by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var measured by remember { mutableIntStateOf(0) }
    LaunchedEffect(chorus, files?.done == files?.total, measured) { usage = runCatching { chorus.spaceUsage() }.getOrNull() }
    val device = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
    Column(Modifier.fillMaxSize().background(p.bg)) {
        SubpageBar("On this device", listOf(device, "Android").filter { it.isNotBlank() }.joinToString(" · "), onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            usage?.let { SpaceCard(it, Modifier.padding(top = 16.dp)) }
            SectionLabel("Offline")
            FormCard {
                ToggleRow("Keep everything on this device", keepEverything, subtitle = "Read old chats and posts without a connection",
                    enabled = !syncBusy && !clearing) { on ->
                    scope.launch {
                        try { chorus.setKeepEverything(on); error = null }
                        catch (e: Exception) { error = e.message ?: "Could not save this setting." }
                    }
                }
                FormDivider()
                val synced = when {
                    syncBusy -> "Syncing…"
                    status == Status.Live && upToDate -> "Up to date"
                    else -> lastSynced?.let { ago(it).let { a -> if (a == "just now") "Last synced just now" else "Last synced $a ago" } }
                }
                NavRow("Sync everything now", R.drawable.ic_refresh,
                    subtitle = listOfNotNull(synced, files?.takeIf { it.missing > 0 }?.let { "${it.missing} not available" }).joinToString(" · ").ifEmpty { null },
                    enabled = !syncBusy && status == Status.Live) {
                    syncBusy = true; error = null
                    scope.launch {
                        try { chorus.recheckAll(); measured++ }
                        catch (e: Exception) { error = e.message ?: "Could not finish syncing." }
                        finally { syncBusy = false }
                    }
                }
                val progress = recheck?.takeIf { syncBusy && it.total > 0 }?.let { it.checked.toFloat() / it.total }
                    ?: files?.takeIf { it.total > 0 && it.done < it.total }?.let { it.done.toFloat() / it.total }
                progress?.let {
                    LinearProgressIndicator({ it }, Modifier.fillMaxWidth().padding(start = 54.dp, end = 16.dp, bottom = 10.dp),
                        color = p.accent, trackColor = p.surface3)
                }
            }
            val downloaded = usage?.files ?: 0L
            SectionLabel("Free up space")
            FormCard {
                NavRow(if (clearing) "Clearing…" else "Clear downloaded files", R.drawable.ic_delete,
                    subtitle = "Keeps messages; frees about ${formatBytes(downloaded)}", enabled = !clearing && downloaded > 0) { asking = true }
            }
            FormNote(chorus.device?.let { "Clearing only removes copies on this phone. Everything stays on ${serverName(it)}." }
                ?: "Clearing only removes copies on this phone.", R.drawable.ic_shield)
            error?.let { Text(it, color = p.danger, fontSize = 14.sp) }
        }
    }
    if (asking) ConfirmDrawer("Clear downloaded files?", "Frees about ${formatBytes(usage?.files ?: 0L)}", "Clear", onConfirm = {
        asking = false; clearing = true; error = null
        scope.launch {
            try { chorus.clearDownloaded(); measured++ }
            catch (e: Exception) { error = e.message ?: "Could not clear the files." }
            finally { clearing = false }
        }
    }, onDismiss = { asking = false }, icon = R.drawable.ic_delete)
}

/** "Chorus Home" for a pinned home install (D-071), else the server's host. */
internal fun serverName(device: garden.vayne.chorus.data.DeviceRecord): String =
    if (device.pin != null) "Chorus Home" else runCatching { java.net.URI(device.base).host }.getOrNull() ?: device.base

private val FilesBlue = Color(0xFF9DB8F0)

/** F9.6's usage block: the total, a three-part bar and its legend. */
@Composable
private fun SpaceCard(usage: DeviceSpace.Usage, modifier: Modifier = Modifier) {
    val p = LocalChorusPalette.current
    val parts = listOf(Triple("Messages and posts", usage.messages, FilesBlue), Triple("Photos and files", usage.files, p.sun),
        Triple("Other", usage.other, p.ink3))
    FormCard(modifier) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Space used", color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(formatBytes(usage.total), color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(p.surface3),
                horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                val total = usage.total.coerceAtLeast(1)
                for ((_, bytes, color) in parts) {
                    if (bytes <= 0) continue
                    Box(Modifier.weight((bytes.toFloat() / total).coerceAtLeast(.01f)).fillMaxHeight().background(color))
                }
            }
            for ((label, bytes, color) in parts) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(10.dp).background(color, CircleShape))
                    Text(label, color = p.ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(formatBytes(bytes), color = p.ink3, fontSize = 13.sp)
                }
            }
        }
    }
}
