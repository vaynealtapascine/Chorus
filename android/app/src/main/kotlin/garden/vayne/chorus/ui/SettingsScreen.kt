package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.data.AccountPrefs
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.FollowPresets
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.FollowList
import garden.vayne.chorus.data.PeopleApi
import garden.vayne.chorus.data.QuietHours
import garden.vayne.chorus.data.QuietWindow
import garden.vayne.chorus.designsystem.LocalChorusPalette
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/** Account pref rows mirror web Settings; each change queues one pref.set (D-063). */
@Composable
internal fun SettingsScreen(chorus: Chorus, model: Model) {
    val p = LocalChorusPalette.current
    val actions = rememberCoroutineScope()
    val prefs = model.accountPrefs
    var advanced by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var follows by remember { mutableStateOf(FollowList(emptyList(), emptyList())) }
    var followError by remember { mutableStateOf<String?>(null) }
    var followBusy by remember { mutableStateOf(false) }
    var followRefresh by remember { mutableStateOf(0) }
    var quietFrom by rememberSaveable { mutableStateOf("23:00") }
    var quietTo by rememberSaveable { mutableStateOf("08:00") }
    val keepEverything by chorus.keepEverything.collectAsState()
    val files by chorus.fileProgress.collectAsState()
    val recheck by chorus.recheckProgress.collectAsState()
    var syncBusy by remember { mutableStateOf(false) }
    var syncError by remember { mutableStateOf<String?>(null) }
    var deviceError by remember { mutableStateOf<String?>(null) }
    var spaceUsed by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(chorus, files?.done == files?.total) {
        spaceUsed = runCatching { chorus.spaceUsedBytes() }.getOrNull()
    }

    LaunchedEffect(chorus.device?.session, followRefresh) {
        follows = FollowList(emptyList(), emptyList())
        val dev = chorus.device ?: return@LaunchedEffect
        try {
            follows = PeopleApi.list(dev)
            follows.following.firstNotNullOfOrNull { QuietHours.read(it.prefs) }?.let {
                quietFrom = it.from; quietTo = it.to
            }
            followError = null
        } catch (e: Exception) {
            follows = FollowList(emptyList(), emptyList())
            followError = "Quiet hours need a connection."
        }
    }

    fun save(key: String, value: Any) {
        if (busy) return
        busy = true; error = null
        actions.launch {
            try { chorus.create("pref.set", null, AccountPrefs.payload(key, value)) }
            catch (e: Exception) { error = e.message ?: "Could not save this setting." }
            finally { busy = false }
        }
    }

    val active = follows.following.filter { it.status == "active" }
    val quietOn = active.any { QuietHours.read(it.prefs) != null }
    fun saveQuiet(window: QuietWindow?) {
        if (followBusy || active.isEmpty()) return
        followBusy = true; followError = null
        actions.launch {
            try {
                val offset = ZoneId.systemDefault().rules.getOffset(Instant.now()).totalSeconds / 60
                val dev = checkNotNull(chorus.device) { "Not signed in." }
                for (follow in active) PeopleApi.setPrefs(dev, follow.id, QuietHours.updated(follow.prefs, window, offset))
                followRefresh++
            } catch (e: Exception) { followError = e.message ?: "Could not update quiet hours." }
            finally { followBusy = false }
        }
    }

    val ceilingChoice = FollowPresets.choiceOf(prefs.followCeiling).let { if (it == "inherit") "gentle" else it }
    LazyColumn(Modifier.fillMaxSize().background(p.bg).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Settings", color = p.ink, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 12.dp))
            Text("Account settings sync across devices. This device settings stay here.", color = p.ink2)
            if (error != null) Text(error.orEmpty(), color = p.danger)
        }
        item {
            Column(Modifier.fillMaxWidth().background(p.surface).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This device", color = p.ink, fontWeight = FontWeight.SemiBold)
                SettingToggle("Keep everything on this device", keepEverything, !syncBusy) { on ->
                    actions.launch {
                        try { chorus.setKeepEverything(on); deviceError = null }
                        catch (e: Exception) { deviceError = e.message ?: "Could not save this setting." }
                    }
                }
                Text("Keeps available files for offline use. Files over 20 MB are opened online.", color = p.ink2)
                TextButton(enabled = !syncBusy, onClick = {
                    syncBusy = true; syncError = null
                    actions.launch {
                        try { chorus.recheckAll(); spaceUsed = chorus.spaceUsedBytes() }
                        catch (e: Exception) { syncError = e.message ?: "Could not finish syncing." }
                        finally { syncBusy = false }
                    }
                }) { Text(if (syncBusy) "Syncing…" else "Sync everything now") }
                if (syncBusy && recheck != null) Text("Checked ${recheck!!.checked} of ${recheck!!.total} scopes", color = p.ink2)
                if (files != null) Text("Files: ${files!!.done} of ${files!!.total} (${files!!.missing} not available)", color = p.ink2)
                if (spaceUsed != null) Text("Space used: ${formatBytes(spaceUsed!!)}", color = p.ink2)
                if (syncError != null) Text(syncError.orEmpty(), color = p.danger)
                if (deviceError != null) Text(deviceError.orEmpty(), color = p.danger)
            }
        }
        item {
            Column(Modifier.fillMaxWidth().background(p.surface).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Notifications", color = p.ink, fontWeight = FontWeight.SemiBold)
                SettingToggle("Mentions", prefs.chatEnabled("mention"), !busy) {
                    save("notify_chat", prefs.withChatKind("mention", it))
                }
                SettingToggle("Direct messages", prefs.chatEnabled("dm"), !busy) {
                    save("notify_chat", prefs.withChatKind("dm", it))
                }
                SettingToggle("Replies", prefs.chatEnabled("reply"), !busy) {
                    save("notify_chat", prefs.withChatKind("reply", it))
                }
                if (active.isNotEmpty()) {
                    Text("Quiet hours for people you follow", color = p.ink2)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(quietFrom, { quietFrom = it.take(5) }, label = { Text("From") },
                            singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(quietTo, { quietTo = it.take(5) }, label = { Text("Until") },
                            singleLine = true, modifier = Modifier.weight(1f))
                    }
                    Text("Use 24-hour time, in your local time zone.", color = p.ink2)
                    Row {
                        TextButton(enabled = !followBusy && QuietHours.valid(quietFrom, quietTo),
                            onClick = { saveQuiet(QuietWindow(quietFrom, quietTo)) }) {
                            Text(if (quietOn) "Update quiet hours" else "Turn on quiet hours")
                        }
                        if (quietOn) TextButton(enabled = !followBusy, onClick = { saveQuiet(null) }) { Text("Turn off") }
                    }
                }
                if (followError != null) Text(followError.orEmpty(), color = p.ink2)
            }
        }
        if (!model.isPerson) item {
            Column(Modifier.fillMaxWidth().background(p.surface).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Sharing", color = p.ink, fontWeight = FontWeight.SemiBold)
                Text("Default for new followers", color = p.ink2)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (choice in listOf("close", "gentle", "private")) {
                        TextButton(enabled = !busy, onClick = {
                            save("follow_ceiling", FollowPresets.ceiling(choice, prefs.followCeiling))
                        }) { Text("${if (ceilingChoice == choice) "✓ " else ""}${choice.replaceFirstChar { it.uppercase() }}") }
                    }
                }
                if (ceilingChoice == "custom") Text("Custom ceiling is active. Choose a preset to replace it.", color = p.ink2)
                Text("Set each follower's access in People.", color = p.ink2)
            }
        }
        item {
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Advanced · Hide" else "Advanced ›") }
        }
        if (advanced) item {
            Column(Modifier.fillMaxWidth().background(p.surface).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Advanced", color = p.ink, fontWeight = FontWeight.SemiBold)
                if (!model.isPerson) {
                    SettingToggle("Ping my other devices when the front changes", prefs.chatEnabled("own_switch"), !busy) {
                        save("notify_chat", prefs.withChatKind("own_switch", it))
                    }
                    SettingToggle("Replies from a notification speak as the mentioned member",
                        prefs.chatEnabled("reply_as_mentioned"), !busy) {
                        save("notify_chat", prefs.withChatKind("reply_as_mentioned", it))
                    }
                    SettingToggle("Followers can look back at who fronted",
                        prefs.followCeiling.optBoolean("share_history"), !busy) {
                        save("follow_ceiling", FollowPresets.withSharing(prefs.followCeiling, "share_history", it))
                    }
                    SettingToggle("Followers can see fronting stats",
                        prefs.followCeiling.optBoolean("share_stats"), !busy) {
                        save("follow_ceiling", FollowPresets.withSharing(prefs.followCeiling, "share_stats", it))
                    }
                }
                SettingToggle("Expand content warnings automatically", prefs.cwAutoExpand, !busy) {
                    save("chat.cw_auto_expand", it)
                }
                SettingToggle("Parse speaker annotations in chat", prefs.segmentParsing, !busy) {
                    save("chat.segment_parsing", it)
                }
            }
        }
        item { Text("", modifier = Modifier.padding(bottom = 16.dp)) }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.0f KB".format(bytes / 1024.0)
}

@Composable
private fun SettingToggle(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = p.ink, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
