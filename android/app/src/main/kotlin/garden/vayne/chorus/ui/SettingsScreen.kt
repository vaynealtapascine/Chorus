package garden.vayne.chorus.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
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
import garden.vayne.chorus.designsystem.LocalChorusAppearance
import garden.vayne.chorus.designsystem.ThemeMode
import garden.vayne.chorus.designsystem.LocalChorusPalette
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date

/** Account pref rows mirror web Settings; each change queues one pref.set (D-063). */
@Composable
internal fun SettingsScreen(chorus: Chorus, model: Model, onOpenInsights: () -> Unit = {}) {
    val p = LocalChorusPalette.current
    val appearance = LocalChorusAppearance.current
    val actions = rememberCoroutineScope()
    val ctx = LocalContext.current
    val navPrefs = remember { ctx.getSharedPreferences("chorus.navigation", 0) }
    val navAccount = chorus.device?.accountId.orEmpty()
    var navChoices by remember(navAccount, model.isPerson) { mutableStateOf(garden.vayne.chorus.data.DeviceNavigation.resolve(navPrefs.getString(navAccount, null), model.isPerson)) }
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
    val connStatus by chorus.status.collectAsState()
    val files by chorus.fileProgress.collectAsState()
    val recheck by chorus.recheckProgress.collectAsState()
    val issues by chorus.syncIssues.collectAsState()
    var syncBusy by remember { mutableStateOf(false) }
    var syncError by remember { mutableStateOf<String?>(null) }
    var deviceError by remember { mutableStateOf<String?>(null) }
    var spaceUsed by remember { mutableStateOf<Long?>(null) }
    var pushStatus by remember { mutableStateOf<garden.vayne.chorus.data.Push.Status?>(null) }
    var pushBusy by remember { mutableStateOf(false) }
    var pushNote by remember { mutableStateOf<String?>(null) }
    var osNotificationsOn by remember { mutableStateOf(NotificationManagerCompat.from(ctx).areNotificationsEnabled()) }

    LaunchedEffect(chorus, connStatus) {
        osNotificationsOn = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        pushStatus = runCatching { garden.vayne.chorus.data.Push.status(ctx, chorus) }.getOrNull()
    }

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
            Text("Appearance", color = p.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ThemeMode.entries.forEach { mode -> TextButton(onClick = { appearance.change(mode) }) {
                    Text(mode.label, color = if (appearance.mode == mode) p.accent else p.ink2,
                        fontWeight = if (appearance.mode == mode) FontWeight.Bold else FontWeight.Normal)
                } }
            }
        }
        item {
            Text("Settings", color = p.ink, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 12.dp))
            Text("Account settings sync across devices. This device settings stay here.", color = p.ink2)
            if (!model.isPerson) TextButton(onClick = onOpenInsights) { Text("Insights · Front history") }
            if (error != null) Text(error.orEmpty(), color = p.danger)
        }
        if (issues.isNotEmpty()) {
            item { Text("Sync issues · ${issues.size}", color = p.ink, fontWeight = FontWeight.SemiBold) }
            items(issues, key = { it.id }) { issue ->
                Column(Modifier.fillMaxWidth().background(p.surface).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Could not sync ${issue.kind}", color = p.ink, fontWeight = FontWeight.SemiBold)
                    Text(issue.message, color = p.danger)
                    if (issue.at > 0) Text(DateFormat.getDateTimeInstance().format(Date(issue.at)), color = p.ink2)
                    if (issue.text.isNotEmpty()) {
                        Text(issue.text, color = p.ink2)
                        TextButton(onClick = {
                            ctx.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText("Chorus text", issue.text))
                        }) { Text("Copy text") }
                    }
                    TextButton(onClick = {
                        actions.launch {
                            try { chorus.dismissIssue(issue.id); error = null }
                            catch (e: Exception) { error = e.message ?: "Could not dismiss this issue." }
                        }
                    }) { Text("Dismiss") }
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().background(p.surface).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This device", color = p.ink, fontWeight = FontWeight.SemiBold)
                NavigationSettings(navChoices, model.isPerson) { choices ->
                    navChoices = choices
                    navPrefs.edit().putString(navAccount, choices.joinToString(",")).apply()
                }
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
                if (!osNotificationsOn) {
                    Text("Notifications are turned off for Chorus in Android settings.", color = p.danger)
                    TextButton(onClick = {
                        runCatching {
                            ctx.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }) { Text("Open Android notification settings") }
                }
                pushStatus?.let { st ->
                    val how = when (st.delivery) {
                        "fcm" -> "Delivered instantly via Firebase."
                        "distributor" -> "Delivered via ${st.distributor?.let { garden.vayne.chorus.data.Push.distributorLabel(ctx, it) } ?: "UnifiedPush"}."
                        else -> "Checked every few minutes in the background."
                    }
                    Text(how, color = p.ink2)
                }
                TextButton(enabled = !pushBusy, onClick = {
                    pushBusy = true; pushNote = null
                    actions.launch {
                        try {
                            osNotificationsOn = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
                            pushNote = garden.vayne.chorus.data.Push.sendTest(ctx)
                            pushStatus = runCatching { garden.vayne.chorus.data.Push.status(ctx, chorus) }.getOrNull()
                        } finally { pushBusy = false }
                    }
                }) { Text(if (pushBusy) "Sending…" else "Send a test notification") }
                if (pushNote != null) Text(pushNote.orEmpty(), color = p.ink2)
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
                pushStatus?.takeIf { it.fcmConfigured && it.distributors.isNotEmpty() }?.let { st ->
                    Text("Push delivery on this device", color = p.ink2)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        val options = buildList {
                            add("fcm" to "Firebase")
                            for (pkg in st.distributors) add("distributor:$pkg" to garden.vayne.chorus.data.Push.distributorLabel(ctx, pkg))
                        }
                        for ((value, label) in options) {
                            val selected = if (st.choice.startsWith("distributor:")) st.choice == value else value == "fcm"
                            TextButton(enabled = !pushBusy, onClick = {
                                pushBusy = true; pushNote = null
                                actions.launch {
                                    try { pushStatus = garden.vayne.chorus.data.Push.chooseMode(ctx, value) }
                                    finally { pushBusy = false }
                                }
                            }) { Text("${if (selected) "✓ " else ""}$label") }
                        }
                    }
                }
                SettingToggle("Show when we're typing in shared chats", prefs.shareTyping, !busy) {
                    save("share_typing", it)
                }
                if (!model.isPerson) {
                    // D-097: DMs between members and members-only channels, only while one of them is here
                    SettingToggle("Show members' private chats only while they're fronting", prefs.hidePrivate, !busy) {
                        save(garden.vayne.chorus.data.ChatOrganize.HIDE_PRIVATE, it)
                    }
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
                if (!model.isPerson) SettingToggle("Track reading per member", prefs.readPerMember, !busy) {
                    save("chat.read_per_member", it)
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
