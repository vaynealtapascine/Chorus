package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.FollowInfo
import garden.vayne.chorus.data.Friends
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.PeopleApi
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.TimeZone

private fun JSONObject.text(key: String) = optString(key).takeIf { has(key) && !isNull(key) && it.isNotBlank() }

/**
 * F7.12: what we hear from one friend, our own prefs (NOTIFICATIONS §4) that can only make it
 * quieter than they allow: their switches (who, which layers, switch-outs), how they arrive, quiet
 * hours, a mute, and post alerts per member (D-106). Each change saves as it's made.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FriendNotifyPage(chorus: Chorus, model: Model, f: FollowInfo, cache: SnapshotStateMap<String, List<JSONObject>>, onBack: () -> Unit) {
    val p = LocalChorusPalette.current
    val scope = rememberCoroutineScope()
    val name = f.account.shownName
    var prefs by remember(f.id) { mutableStateOf(f.prefs) }
    var error by remember { mutableStateOf<String?>(null) }
    val members = rememberFriendMembers(chorus, f.account.id, cache).orEmpty()
    val now = System.currentTimeMillis()
    fun save(next: JSONObject) {
        val before = prefs
        prefs = next
        scope.launch {
            try {
                val offset = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
                PeopleApi.setPrefs(checkNotNull(chorus.device) { "Not signed in." }, f.id, Friends.forSaving(next, offset)); error = null
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { prefs = before; error = e.message ?: "Couldn’t save that." }
        }
    }
    // post alerts per shared member: none · requested · active
    val alerts = remember(f.id) { mutableStateMapOf<String, String>() }
    LaunchedEffect(f.id, members.size, chorus.device?.session) {
        val dev = chorus.device ?: return@LaunchedEffect
        if (!f.account.isSystem) { PeopleApi.personAlerts(dev, f.account.id)?.let { alerts["person"] = it }; return@LaunchedEffect }
        for (m in members) {
            val id = m.optString("id")
            if (id !in alerts) runCatching { PeopleApi.memberProfile(dev, f.account.id, id) }.getOrNull()?.let { alerts[id] = it.optString("post_alerts", "none") }
        }
    }
    fun toggleAlert(key: String, on: Boolean) {
        val dev = chorus.device ?: return
        scope.launch {
            try {
                alerts[key] = if (key == "person") PeopleApi.setPersonAlerts(dev, f.account.id, on) else PeopleApi.setPostAlerts(dev, f.account.id, key, on)
                error = null
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Couldn’t change post alerts." }
        }
    }
    var quietOpen by remember { mutableStateOf(false) }
    var muteOpen by remember { mutableStateOf(false) }
    var digestOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(p.bg)) {
        SubpageBar("Notifications from $name", null, onBack) {
            BarIcon(R.drawable.ic_check, "Done", p.accent, onClick = onBack)
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            error?.let { Text(it, color = p.danger, fontSize = 13.sp, modifier = Modifier.padding(16.dp)) }
            if (f.account.isSystem) {
                SectionLabel("Switches", Modifier.padding(horizontal = 12.dp))
                val on = Friends.enabled(prefs)
                ToggleRow("Tell me when they switch", on, "As $name allow") { save(Friends.withEnabled(prefs, it)) }
                if (members.isNotEmpty()) {
                    Text("About", color = p.ink3, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp))
                    val picked = Friends.subjects(prefs)
                    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (m in members) {
                            val id = m.optString("id")
                            val chosen = picked == null || id in picked
                            PickChip(shownMember(m), chosen, on) {
                                save(Friends.withSubject(prefs, id, members.map { it.optString("id") }))
                            }
                        }
                    }
                }
                Text("Layers", color = p.ink3, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 6.dp))
                val levels = Friends.levels(prefs)
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((id, label) in listOf("front" to "Front", "cocon" to "Co-conscious", "present" to "Present"))
                        PickChip(label, id in levels, on) { save(Friends.withLevel(prefs, id)) }
                }
                ToggleRow("Switch-outs", Friends.switchOuts(prefs), "When someone stops fronting", on) { save(Friends.withSwitchOuts(prefs, it)) }
                SectionLabel("How they arrive", Modifier.padding(horizontal = 12.dp))
                val delivery = Friends.delivery(prefs)
                Segmented(listOf("Each one", "Hourly", "Daily at ${Friends.digestTime(prefs)}"), Friends.DELIVERIES.indexOf(delivery), Modifier.padding(horizontal = 16.dp), accent = true) { i ->
                    if (Friends.DELIVERIES[i] == "digest_daily" && delivery == "digest_daily") digestOpen = true
                    else save(Friends.withDelivery(prefs, Friends.DELIVERIES[i]))
                }
                Spacer(Modifier.height(6.dp))
            }
            val quiet = Friends.quiet(prefs)
            LinkRow("Quiet hours", quiet?.let { "${it.first}–${it.second} · ${if (Friends.quietHold(prefs)) "hold until they end" else "skip them"}" } ?: "Off", null) { quietOpen = true }
            val muted = Friends.mutedUntil(prefs, now)
            LinkRow("Mute for a while", muted?.let { "Until ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(it))}" } ?: "Off", null) { muteOpen = true }
            // a system that shares no member profiles has nothing to ask post alerts for
            if (!f.account.isSystem || members.isNotEmpty()) SectionLabel("New posts", Modifier.padding(horizontal = 12.dp))
            if (!f.account.isSystem) alerts["person"]?.let { s -> AlertRow("$name’s posts", s) { toggleAlert("person", it) } }
            else for (m in members) {
                val id = m.optString("id")
                alerts[id]?.let { s -> AlertRow("${shownMember(m)}’s posts", s) { toggleAlert(id, it) } }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (quietOpen) {
        val quiet = Friends.quiet(prefs)
        var on by remember { mutableStateOf(quiet != null) }
        var from by remember { mutableStateOf(quiet?.first ?: "23:00") }
        var to by remember { mutableStateOf(quiet?.second ?: "08:00") }
        var hold by remember { mutableStateOf(Friends.quietHold(prefs)) }
        val ok = Friends.CLOCK.matches(from) && Friends.CLOCK.matches(to)
        Drawer({ quietOpen = false }, "Quiet hours", footer = {
            DrawerButton("Save", { save(Friends.withQuiet(prefs, if (on) from to to else null, hold)); quietOpen = false }, enabled = !on || ok)
        }) {
            FormCard { ToggleRow("Quiet hours", on) { on = it } }
            if (on) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChorusField(from, { from = it.take(5) }, Modifier.weight(1f), singleLine = true, label = { Text("From (HH:MM)") }, isError = !Friends.CLOCK.matches(from))
                    ChorusField(to, { to = it.take(5) }, Modifier.weight(1f), singleLine = true, label = { Text("Until (HH:MM)") }, isError = !Friends.CLOCK.matches(to))
                }
                Segmented(listOf("Hold until morning", "Skip them"), if (hold) 0 else 1, accent = true) { hold = it == 0 }
            }
        }
    }
    MenuDrawer(muteOpen, { muteOpen = false }, "Mute $name") {
        val hour = 60 * 60 * 1000L
        for ((label, span) in listOf("Off" to 0L, "For an hour" to hour, "For 8 hours" to 8 * hour, "For a day" to 24 * hour, "For a week" to 7 * 24 * hour)) {
            MenuRow(label, selected = if (span == 0L) Friends.mutedUntil(prefs, now) == null else null) {
                muteOpen = false; save(Friends.withMute(prefs, if (span == 0L) null else System.currentTimeMillis() + span))
            }
        }
    }
    if (digestOpen) {
        var at by remember { mutableStateOf(Friends.digestTime(prefs)) }
        Drawer({ digestOpen = false }, "Daily summary at", "Your time", footer = {
            DrawerButton("Save", { save(Friends.withDigestTime(prefs, at)); digestOpen = false }, enabled = Friends.CLOCK.matches(at))
        }) {
            ChorusField(at, { at = it.take(5) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Time (HH:MM)") }, isError = !Friends.CLOCK.matches(at))
        }
    }
}

/** One member's post alerts: on, asked and waiting, or off (D-106). */
@Composable
private fun AlertRow(title: String, state: String, onChange: (Boolean) -> Unit) {
    val p = LocalChorusPalette.current
    when (state) {
        "requested" -> Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClickLabel = "Cancel the request") { onChange(false) }
            .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = p.ink, fontSize = 15.sp)
                Text("Asked · waiting for an answer", color = p.ink3, fontSize = 13.sp)
            }
            Text("Asked", color = p.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        else -> ToggleRow(title, state == "active", if (state == "active") "They said yes" else "Ask to hear when they post") { onChange(it) }
    }
}
