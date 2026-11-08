package garden.vayne.chorus.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.AccountPrefs
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.FollowList
import garden.vayne.chorus.data.FollowPresets
import garden.vayne.chorus.data.Friends
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.PeopleApi
import garden.vayne.chorus.data.Push
import garden.vayne.chorus.data.QuietHours
import garden.vayne.chorus.data.QuietWindow
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Calendar
import java.util.Date

/** The pages under More's *Settings* (F10.1); a path like `Appearance>Advanced` is one stack. */
internal enum class SettingsPage(val title: String) {
    Appearance("Appearance"), Advanced("Advanced"), Notifications("Notification settings"), Privacy("Privacy and sharing"),
    Data("Your data and devices"), System("System settings"), Trash("Trash"),
}

/** Where the settings pages lead outside themselves. */
internal class SettingsRoutes(val onLink: () -> Unit, val onFriends: (List<String>) -> Unit)

/** One settings page; [onOpen] goes a level deeper, [onBack] a level up. */
@Composable
internal fun SettingsScreen(chorus: Chorus, model: Model, page: SettingsPage, onOpen: (SettingsPage) -> Unit, onBack: () -> Unit,
    routes: SettingsRoutes) {
    val p = LocalChorusPalette.current
    when (page) {
        SettingsPage.Appearance -> AppearanceScreen(model, onBack) { onOpen(SettingsPage.Advanced) }
        SettingsPage.Trash -> TrashScreen(chorus, model, onBack)
        else -> Column(Modifier.fillMaxSize().background(p.bg)) {
            SubpageBar(if (page == SettingsPage.System && model.isPerson) "Account" else page.title, null, onBack)
            val saver = rememberPrefSaver(chorus)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (page) {
                    SettingsPage.Advanced -> AdvancedPage(model, saver)
                    SettingsPage.Notifications -> NotificationsPage(chorus, model, saver)
                    SettingsPage.Privacy -> PrivacyPage(model, saver, routes)
                    SettingsPage.Data -> DataPage(chorus, model, routes) { onOpen(SettingsPage.Trash) }
                    SettingsPage.System -> SystemPage(chorus, model)
                    else -> {}
                }
                saver.error?.let { Text(it, color = p.danger, fontSize = 14.sp) }
            }
        }
    }
}

/** Account prefs (D-063): each change queues one `pref.set`. */
internal class PrefSaver(val busy: Boolean, val error: String?, val save: (String, Any) -> Unit)

@Composable
private fun rememberPrefSaver(chorus: Chorus): PrefSaver {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    return PrefSaver(busy, error) { key, value ->
        if (!busy) {
            busy = true; error = null
            scope.launch {
                try { chorus.create("pref.set", null, AccountPrefs.payload(key, value)) }
                catch (e: Exception) { error = e.message ?: "Could not save this setting." }
                finally { busy = false }
            }
        }
    }
}

/** The Advanced settings (DESIGN.md §6), grouped as the Figma forms are. */
@Composable
private fun AdvancedPage(model: Model, saver: PrefSaver) {
    val prefs = model.accountPrefs
    val on = !saver.busy
    SectionLabel("Chat")
    FormCard {
        ToggleRow("Show when we're typing in shared chats", prefs.shareTyping, enabled = on) { saver.save("share_typing", it) }
        FormDivider()
        ToggleRow("Expand content warnings automatically", prefs.cwAutoExpand, enabled = on) { saver.save("chat.cw_auto_expand", it) }
        FormDivider()
        ToggleRow("Parse speaker annotations in chat", prefs.segmentParsing, enabled = on) { saver.save("chat.segment_parsing", it) }
        if (!model.isPerson) {
            FormDivider()
            // D-097: DMs between members and members-only channels, only while one of them is here
            ToggleRow("Private chats only while their member is here", prefs.hidePrivate, enabled = on) {
                saver.save(garden.vayne.chorus.data.ChatOrganize.HIDE_PRIVATE, it)
            }
            FormDivider()
            ToggleRow("Track reading per member", prefs.readPerMember, enabled = on) { saver.save("chat.read_per_member", it) }
        }
    }
    if (!model.isPerson) {
        SectionLabel("Notifications")
        FormCard {
            ToggleRow("Ping my other devices when the front changes", prefs.chatEnabled("own_switch"), enabled = on) {
                saver.save("notify_chat", prefs.withChatKind("own_switch", it))
            }
            FormDivider()
            ToggleRow("Reply as the mentioned member", prefs.chatEnabled("reply_as_mentioned"), enabled = on) {
                saver.save("notify_chat", prefs.withChatKind("reply_as_mentioned", it))
            }
        }
    }
}

@Composable
private fun NotificationsPage(chorus: Chorus, model: Model, saver: PrefSaver) {
    val p = LocalChorusPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = model.accountPrefs
    val connStatus by chorus.status.collectAsState()
    var osOn by remember { mutableStateOf(NotificationManagerCompat.from(ctx).areNotificationsEnabled()) }
    var push by remember { mutableStateOf<Push.Status?>(null) }
    var pushBusy by remember { mutableStateOf(false) }
    var pushNote by remember { mutableStateOf<String?>(null) }
    var choosingDelivery by remember { mutableStateOf(false) }
    var follows by remember { mutableStateOf(FollowList(emptyList(), emptyList())) }
    var followsLoaded by remember { mutableStateOf(false) }
    var followRefresh by remember { mutableIntStateOf(0) }
    var followBusy by remember { mutableStateOf(false) }
    var followError by remember { mutableStateOf<String?>(null) }
    var quiet by remember { mutableStateOf(false) }
    LaunchedEffect(chorus, connStatus) {
        osOn = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        push = runCatching { Push.status(ctx, chorus) }.getOrNull()
    }
    LaunchedEffect(chorus.device?.session, followRefresh) {
        val dev = chorus.device ?: return@LaunchedEffect
        try { follows = PeopleApi.list(dev); followsLoaded = true; followError = null }
        catch (e: Exception) { followError = if (followsLoaded) e.message ?: "Could not update quiet hours." else null }
    }
    val active = follows.following.filter { it.status == "active" }
    val window = active.firstNotNullOfOrNull { QuietHours.read(it.prefs) }
    fun saveQuiet(next: QuietWindow?) {
        if (followBusy || active.isEmpty()) return
        followBusy = true; followError = null
        scope.launch {
            try {
                val offset = ZoneId.systemDefault().rules.getOffset(Instant.now()).totalSeconds / 60
                val dev = checkNotNull(chorus.device) { "Not signed in." }
                for (follow in active) PeopleApi.setPrefs(dev, follow.id, QuietHours.updated(follow.prefs, next, offset))
                followRefresh++
            } catch (e: Exception) { followError = e.message ?: "Could not update quiet hours." }
            finally { followBusy = false }
        }
    }
    FormCard(Modifier.padding(top = 16.dp)) {
        FormValueRow("Android notifications", if (osOn) "On" else "Off") {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        push?.let { st ->
            FormDivider()
            val how = when (st.delivery) {
                "fcm" -> "Instant"
                "distributor" -> st.distributor?.let { Push.distributorLabel(ctx, it) } ?: "UnifiedPush"
                else -> "Every few minutes"
            }
            // the choice between Firebase and a UnifiedPush app exists only where both do (Advanced)
            if (st.fcmConfigured && st.distributors.isNotEmpty()) FormValueRow("How this phone gets them", how, enabled = !pushBusy) { choosingDelivery = true }
            else FormInfoRow("How this phone gets them", how)
        }
        FormDivider()
        NavRow(if (pushBusy) "Sending…" else "Send a test", enabled = !pushBusy, chevron = false, subtitle = pushNote) {
            pushBusy = true; pushNote = null
            scope.launch {
                try {
                    osOn = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
                    pushNote = Push.sendTest(ctx)
                    push = runCatching { Push.status(ctx, chorus) }.getOrNull()
                } finally { pushBusy = false }
            }
        }
    }
    SectionLabel("What rings")
    FormCard {
        ToggleRow("Mentions", prefs.chatEnabled("mention"), enabled = !saver.busy) { saver.save("notify_chat", prefs.withChatKind("mention", it)) }
        FormDivider()
        ToggleRow("Direct messages", prefs.chatEnabled("dm"), enabled = !saver.busy) { saver.save("notify_chat", prefs.withChatKind("dm", it)) }
        FormDivider()
        ToggleRow("Replies", prefs.chatEnabled("reply"), enabled = !saver.busy) { saver.save("notify_chat", prefs.withChatKind("reply", it)) }
    }
    if (active.isNotEmpty()) {
        SectionLabel("Friends")
        FormCard {
            FormValueRow("Quiet hours", window?.let { "${it.from}–${it.to}" } ?: "Off", enabled = !followBusy) { quiet = true }
        }
    }
    followError?.let { Text(it, color = p.ink2, fontSize = 14.sp) }
    push?.takeIf { choosingDelivery }?.let { st ->
        val options = buildList { add("fcm" to "Instant (Firebase)"); for (pkg in st.distributors) add("distributor:$pkg" to Push.distributorLabel(ctx, pkg)) }
        val chosen = options.firstOrNull { (value, _) -> if (st.choice.startsWith("distributor:")) st.choice == value else value == "fcm" }
        ChoiceDrawer(true, "How this phone gets them", options, chosen, { it.second }, { (value, _) ->
            choosingDelivery = false; pushBusy = true; pushNote = null
            scope.launch { try { push = Push.chooseMode(ctx, value) } finally { pushBusy = false } }
        }, { choosingDelivery = false })
    }
    if (quiet) QuietHoursDrawer(window, onDismiss = { quiet = false }) { next -> quiet = false; saveQuiet(next) }
}

/** Quiet hours for friends' switches: From and Until in the time drawer (D-124), or off. */
@Composable
private fun QuietHoursDrawer(window: QuietWindow?, onDismiss: () -> Unit, onSave: (QuietWindow?) -> Unit) {
    var from by remember { mutableStateOf(window?.from ?: "23:00") }
    var until by remember { mutableStateOf(window?.to ?: "08:00") }
    var picking by remember { mutableStateOf<String?>(null) }
    Drawer(onDismiss, "Quiet hours", footer = {
        DrawerButton("Save", { onSave(QuietWindow(from, until)) }, enabled = QuietHours.valid(from, until))
        if (window != null) DrawerButton("Turn off", { onSave(null) }, ButtonKind.Quiet)
    }) {
        FormCard {
            FormValueRow("From", from) { picking = "from" }
            FormDivider()
            FormValueRow("Until", until) { picking = "until" }
        }
    }
    picking?.let { which ->
        val (h, m) = (if (which == "from") from else until).split(':').map { it.toIntOrNull() ?: 0 }
        val initial = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m) }.timeInMillis
        DateTimeDrawer(initial, { t ->
            val c = Calendar.getInstance().apply { timeInMillis = t }
            val text = "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
            if (which == "from") from = text else until = text
            picking = null
        }, { picking = null }, title = if (which == "from") "From" else "Until", timeOnly = true)
    }
}

/** How much new friends hear (the accept sheet's presets, F7.7), buckets, and what followers may look back at. */
@Composable
private fun PrivacyPage(model: Model, saver: PrefSaver, routes: SettingsRoutes) {
    val p = LocalChorusPalette.current
    val prefs = model.accountPrefs
    val chosen = Friends.everyoneElse(prefs.followCeiling)
    SectionLabel("New friends")
    FormCard {
        Friends.PRESETS.forEachIndexed { i, choice ->
            if (i > 0) FormDivider()
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .then(Modifier.clickableRadio(chosen == choice, !saver.busy) { saver.save("follow_ceiling", FollowPresets.ceiling(choice, prefs.followCeiling)) })
                .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Radio(chosen == choice)
                Column(Modifier.weight(1f)) {
                    Text(Friends.presetName(choice), color = p.ink, fontSize = 16.sp)
                    Text(Friends.presetAbout(choice), color = p.ink3, fontSize = 13.sp, lineHeight = 17.sp)
                }
            }
        }
    }
    FormCard {
        FormValueRow("Buckets", model.privacyBuckets.size.toString(), icon = R.drawable.ic_users) { routes.onFriends(listOf("tab:2")) }
        FormDivider()
        FormValueRow("Everyone else", Friends.presetName(chosen), icon = R.drawable.ic_sliders) { routes.onFriends(listOf("tab:2", "advanced")) }
    }
    SectionLabel("Followers can also")
    FormCard {
        ToggleRow("Look back at who fronted", prefs.followCeiling.optBoolean("share_history"), enabled = !saver.busy) {
            saver.save("follow_ceiling", FollowPresets.withSharing(prefs.followCeiling, "share_history", it))
        }
        FormDivider()
        ToggleRow("See fronting stats", prefs.followCeiling.optBoolean("share_stats"), enabled = !saver.busy) {
            saver.save("follow_ceiling", FollowPresets.withSharing(prefs.followCeiling, "share_stats", it))
        }
    }
}

private fun Modifier.clickableRadio(selected: Boolean, enabled: Boolean, onClick: () -> Unit): Modifier =
    selectable(selected, enabled = enabled, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = onClick)

/** Trash, linked devices, PluralKit import, and what this phone keeps (F9.6's rows). */
@Composable
private fun DataPage(chorus: Chorus, model: Model, routes: SettingsRoutes, onTrash: () -> Unit) {
    val p = LocalChorusPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val keepEverything by chorus.keepEverything.collectAsState()
    val files by chorus.fileProgress.collectAsState()
    val recheck by chorus.recheckProgress.collectAsState()
    val issues by chorus.syncIssues.collectAsState()
    var syncBusy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var spaceUsed by remember { mutableStateOf<Long?>(null) }
    var importing by remember { mutableStateOf(false) }
    LaunchedEffect(chorus, files?.done == files?.total) { spaceUsed = runCatching { chorus.spaceUsedBytes() }.getOrNull() }
    FormCard(Modifier.padding(top = 16.dp)) {
        NavRow("Link a device", R.drawable.ic_link, onClick = routes.onLink)
        if (!model.isPerson) {
            FormDivider()
            NavRow("Import from PluralKit", R.drawable.ic_down) { importing = true }
        }
        FormDivider()
        NavRow("Trash", R.drawable.ic_delete, onClick = onTrash)
    }
    SectionLabel("This phone")
    FormCard {
        ToggleRow("Keep everything on this device", keepEverything, enabled = !syncBusy) { on ->
            scope.launch {
                try { chorus.setKeepEverything(on); error = null }
                catch (e: Exception) { error = e.message ?: "Could not save this setting." }
            }
        }
        FormDivider()
        NavRow(if (syncBusy) "Syncing…" else "Sync everything now", enabled = !syncBusy, chevron = false,
            subtitle = files?.takeIf { it.missing > 0 }?.let { "${it.missing} not available" }) {
            syncBusy = true; error = null
            scope.launch {
                try { chorus.recheckAll(); spaceUsed = chorus.spaceUsedBytes() }
                catch (e: Exception) { error = e.message ?: "Could not finish syncing." }
                finally { syncBusy = false }
            }
        }
        val progress = recheck?.takeIf { syncBusy && it.total > 0 }?.let { it.checked.toFloat() / it.total }
            ?: files?.takeIf { it.total > 0 && it.done < it.total }?.let { it.done.toFloat() / it.total }
        progress?.let { LinearProgressIndicator({ it }, Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), color = p.accent, trackColor = p.surface3) }
        spaceUsed?.let { FormDivider(); FormInfoRow("Space used", formatBytes(it)) }
    }
    if (issues.isNotEmpty()) {
        SectionLabel("Sync issues · ${issues.size}")
        issues.forEach { issue ->
            FormCard {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Could not sync ${issue.kind}", color = p.ink, fontWeight = FontWeight.SemiBold)
                    Text(issue.message, color = p.danger, fontSize = 14.sp)
                    if (issue.at > 0) Text(DateFormat.getDateTimeInstance().format(Date(issue.at)), color = p.ink3, fontSize = 13.sp)
                    if (issue.text.isNotEmpty()) Text(issue.text, color = p.ink2, fontSize = 14.sp)
                }
                FormDivider()
                Row {
                    if (issue.text.isNotEmpty()) Box(Modifier.weight(1f)) {
                        NavRow("Copy text", chevron = false) {
                            ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Chorus text", issue.text))
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        NavRow("Discard", danger = true, chevron = false) {
                            scope.launch {
                                try { chorus.dismissIssue(issue.id); error = null }
                                catch (e: Exception) { error = e.message ?: "Could not discard this." }
                            }
                        }
                    }
                }
            }
        }
    }
    error?.let { Text(it, color = p.danger, fontSize = 14.sp) }
    if (importing) PkImportSheet(chorus) { importing = false }
}

/** The system's name (`system.set`), its handle and the server it lives on. */
@Composable
private fun SystemPage(chorus: Chorus, model: Model) {
    val p = LocalChorusPalette.current
    val scope = rememberCoroutineScope()
    var naming by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    FormCard(Modifier.padding(top = 16.dp)) {
        if (!model.isPerson) {
            FormValueRow("Name", model.systemName?.takeIf { it.isNotBlank() } ?: "Add a name") { naming = true }
            FormDivider()
        }
        model.accountHandle?.let { FormInfoRow("Handle", "@$it"); FormDivider() }
        FormInfoRow("Server", chorus.device?.base?.let { runCatching { java.net.URI(it).host }.getOrNull() ?: it }.orEmpty())
    }
    error?.let { Text(it, color = p.danger, fontSize = 14.sp) }
    if (naming) {
        var name by remember { mutableStateOf(model.systemName.orEmpty()) }
        var busy by remember { mutableStateOf(false) }
        Drawer({ naming = false }, "System name", dismissible = !busy, footer = {
            DrawerButton("Save", {
                busy = true
                scope.launch {
                    try {
                        chorus.create("system.set", chorus.device?.accountId, org.json.JSONObject().put("name", name.trim()))
                        error = null; naming = false
                    } catch (e: Exception) { error = e.message ?: "Could not rename." }
                    finally { busy = false }
                }
            }, enabled = name.isNotBlank() && name.trim() != model.systemName, busy = busy)
        }) {
            ChorusField(name, { name = it.take(100) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Name") })
        }
    }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.0f KB".format(bytes / 1024.0)
}
