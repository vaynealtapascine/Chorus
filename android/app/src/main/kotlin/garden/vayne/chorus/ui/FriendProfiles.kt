package garden.vayne.chorus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.data.Blobs
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.PeopleApi
import garden.vayne.chorus.designsystem.LocalChorusPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A friend's members and their profiles (D-101): only what that account shares with us, as the
 *  server decides; fetched when opened, never stored. Nothing about fronting is here. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun FriendProfiles(chorus: Chorus, accountId: String, accountName: String, onClose: () -> Unit) {
    val p = LocalChorusPalette.current
    var memberId by rememberSaveable { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf<List<JSONObject>?>(null) }
    var profile by remember { mutableStateOf<JSONObject?>(null) }
    var missing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // post alerts for the open member (D-106): none · requested · active
    var alerts by remember(memberId) { mutableStateOf("none") }
    var alertsBusy by remember(memberId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    BackHandler { if (memberId != null) memberId = null else onClose() }
    LaunchedEffect(accountId, chorus.device?.session) {
        val dev = chorus.device ?: return@LaunchedEffect
        try {
            val items = PeopleApi.members(dev, accountId).optJSONArray("items") ?: JSONArray()
            list = List(items.length()) { items.getJSONObject(it) }
            error = null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Could not load their members." }
    }
    LaunchedEffect(accountId, memberId, chorus.device?.session) {
        val dev = chorus.device ?: return@LaunchedEffect
        val id = memberId ?: return@LaunchedEffect
        profile = null; missing = false
        try {
            profile = PeopleApi.memberProfile(dev, accountId, id)
            missing = profile == null
            alerts = profile?.optString("post_alerts", "none") ?: "none"
            error = null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Could not load this profile." }
    }
    fun JSONObject.text(key: String) = optString(key).takeIf { !isNull(key) && it.isNotBlank() }
    fun shown(m: JSONObject) = m.text("display_name") ?: m.text("name") ?: "Someone"
    fun glyph(m: JSONObject) = m.text("sigil") ?: shown(m).take(1).uppercase()

    Column(Modifier.fillMaxSize().background(p.bg).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = { if (memberId != null) memberId = null else onClose() }, contentPadding = PaddingValues(0.dp)) {
            Text("‹ ${if (memberId != null) accountName else "Friends"}")
        }
        error?.let { Text(it, color = p.danger) }
        val id = memberId
        if (id == null) {
            Text(accountName, color = p.ink, style = MaterialTheme.typography.headlineSmall)
            val shared = list
            when {
                shared == null && error == null -> Text("Loading…", color = p.ink3)
                shared != null && shared.isEmpty() -> Text("$accountName haven't shared any member profiles with you yet.", color = p.ink3)
                shared != null -> for (m in shared) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.surface)
                    .clickable { memberId = m.optString("id") }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(glyph(m), m.text("color") ?: "#A09184", 44.dp, avatarBlob = m.text("avatar_blob"))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(shown(m), color = tonesOf(m.text("color") ?: "#A09184").name, fontWeight = FontWeight.SemiBold)
                        m.text("pronouns")?.let { Text(it, color = p.ink3, style = MaterialTheme.typography.bodySmall) }
                    }
                    ChorusIcon(AppIcon.Chevron)
                }
            }
        } else {
            val m = profile
            when {
                missing -> Text("This profile isn't shared with you.", color = p.ink3)
                m == null -> Text("Loading…", color = p.ink3)
                else -> {
                    m.text("banner_blob")?.let { FriendBanner(chorus, it) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(glyph(m), m.text("color") ?: "#A09184", 72.dp, avatarBlob = m.text("avatar_blob"))
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(shown(m), color = tonesOf(m.text("color") ?: "#A09184").name, style = MaterialTheme.typography.headlineSmall)
                            Text(listOfNotNull(m.text("pronouns"), accountName).joinToString(" · "), color = p.ink3)
                        }
                    }
                    // ask to hear when they post; their system says yes or no (D-106)
                    if (m.has("post_alerts")) OutlinedButton(enabled = !alertsBusy, onClick = {
                        val dev = chorus.device ?: return@OutlinedButton
                        alertsBusy = true
                        scope.launch {
                            try { alerts = PeopleApi.setPostAlerts(dev, accountId, id, alerts == "none"); error = null }
                            catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) { error = e.message ?: "Could not change post alerts." }
                            finally { alertsBusy = false }
                        }
                    }) {
                        Icon(painterResource(garden.vayne.chorus.designsystem.R.drawable.ic_bell),
                            null, Modifier.size(18.dp), tint = if (alerts == "active") p.accent else p.ink2)
                        Spacer(Modifier.width(8.dp))
                        Text(when (alerts) { "active" -> "Post alerts on"; "requested" -> "Requested · Cancel"; else -> "Get post alerts" }, color = p.ink)
                    }
                    if (alerts == "requested") Text("$accountName will see your request.", color = p.ink3, style = MaterialTheme.typography.bodySmall)
                    m.text("description")?.let { Text(it, color = p.ink) }
                    val fields = m.optJSONArray("fields") ?: JSONArray()
                    val birthday = m.text("birthday")
                    if (birthday != null || fields.length() > 0) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.surface).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        birthday?.let { FriendField("Birthday") { Text(if (it.startsWith("--")) it.drop(2) else it, color = p.ink) } }
                        for (i in 0 until fields.length()) {
                            val f = fields.getJSONObject(i)
                            FriendField(f.optString("name")) { FriendValue(f.optString("type"), f.opt("value")) }
                        }
                    }
                    val links = m.optJSONArray("relationships") ?: JSONArray()
                    if (links.length() > 0) Text("Relationships", color = p.ink, style = MaterialTheme.typography.titleMedium)
                    for (i in 0 until links.length()) {
                        val link = links.getJSONObject(i)
                        Text("${link.optString("type")} · ${shown(link.getJSONObject("target"))}", color = p.ink)
                        link.text("note")?.let { Text(it, color = p.ink2) }
                    }
                    Text("You see what $accountName chose to share with you.", color = p.ink3, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun FriendField(name: String, value: @Composable () -> Unit) {
    val p = LocalChorusPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(name, color = p.ink2, style = MaterialTheme.typography.labelMedium)
        value()
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun FriendValue(type: String, value: Any?) {
    val p = LocalChorusPalette.current
    val uri = LocalUriHandler.current
    when {
        value is JSONArray -> FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in 0 until value.length()) (value.opt(i) as? String)?.let { RolePill(it) }
        }
        value is Boolean -> Text(if (value) "Yes" else "No", color = p.ink)
        type == "url" && value is String && garden.vayne.chorus.data.MemberCustomization.validWebsite(value) ->
            Text(value.removePrefix("https://").removePrefix("http://"), color = p.accent, modifier = Modifier.clickable { runCatching { uri.openUri(value) } })
        type == "color" && value is String && Regex("^#[0-9a-fA-F]{6}$").matches(value) -> Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(Color(android.graphics.Color.parseColor(value))))
            Spacer(Modifier.width(6.dp)); Text(value, color = p.ink)
        }
        else -> Text(value?.toString().orEmpty(), color = p.ink)
    }
}

@Composable
private fun FriendBanner(chorus: Chorus, hash: String) {
    val ctx = LocalContext.current
    val dev = chorus.device
    val image by produceState<android.graphics.Bitmap?>(null, hash, dev?.session) {
        value = withContext(Dispatchers.IO) { runCatching { Blobs.image(ctx, hash, dev, kind = "profile", maxPx = 1500, maxBytes = 10L * 1024 * 1024) }.getOrNull() }
    }
    image?.let { Image(it.asImageBitmap(), "Profile banner", Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop) }
}
