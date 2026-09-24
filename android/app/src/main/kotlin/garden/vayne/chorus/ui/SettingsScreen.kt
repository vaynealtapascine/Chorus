package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import garden.vayne.chorus.designsystem.LocalChorusPalette
import kotlinx.coroutines.launch

/** Account pref rows mirror web Settings; each change queues one pref.set (D-063). */
@Composable
internal fun SettingsScreen(chorus: Chorus, model: Model) {
    val p = LocalChorusPalette.current
    val actions = rememberCoroutineScope()
    val prefs = model.accountPrefs
    var advanced by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun save(key: String, value: Any) {
        if (busy) return
        busy = true; error = null
        actions.launch {
            try { chorus.create("pref.set", null, AccountPrefs.payload(key, value)) }
            catch (e: Exception) { error = e.message ?: "Could not save this setting." }
            finally { busy = false }
        }
    }

    val ceilingChoice = FollowPresets.choiceOf(prefs.followCeiling).let { if (it == "inherit") "gentle" else it }
    LazyColumn(Modifier.fillMaxSize().background(p.bg).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Settings", color = p.ink, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 12.dp))
            Text("Changes save on this phone and sync with your account.", color = p.ink2)
            if (error != null) Text(error.orEmpty(), color = p.danger)
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

@Composable
private fun SettingToggle(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = p.ink, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
