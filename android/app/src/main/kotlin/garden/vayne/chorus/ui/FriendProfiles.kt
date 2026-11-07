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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
 *  server decides; fetched when opened, never stored. Nothing about fronting is here. Opened on
 *  [initialMember] (from a post), back returns to where it was opened. A blank [accountName] is
 *  looked up among the accounts we follow or who follow us. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun FriendProfiles(chorus: Chorus, model: garden.vayne.chorus.data.Model, accountId: String, accountName: String, onClose: () -> Unit,
    initialMember: String? = null, backEnabled: Boolean = true) {
    val p = LocalChorusPalette.current
    var memberId by rememberSaveable(accountId, initialMember) { mutableStateOf(initialMember) }
    var looked by remember(accountId) { mutableStateOf<String?>(null) }
    val shownAccount = accountName.ifBlank { looked ?: "Their system" }
    LaunchedEffect(accountId) {
        val dev = chorus.device ?: return@LaunchedEffect
        if (accountName.isNotBlank()) return@LaunchedEffect
        looked = try {
            val follows = PeopleApi.list(dev)
            (follows.following + follows.followers).firstOrNull { it.account.id == accountId }?.account?.shownName
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
    }
    // back from the member it was opened on leaves, rather than showing the whole list
    fun back() { if (memberId != null && memberId != initialMember) memberId = null else onClose() }
    var list by remember { mutableStateOf<List<JSONObject>?>(null) }
    var profile by remember { mutableStateOf<JSONObject?>(null) }
    var missing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // post alerts for the open member (D-106): none · requested · active
    var alerts by remember(memberId) { mutableStateOf("none") }
    var alertsBusy by remember(memberId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    BackHandler(backEnabled) { back() }
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


    val id = memberId
    if (id != null) {
        FriendMemberProfile(chorus, model, accountId, id, shownAccount, profile, missing, error,
            backLabel = if (id != initialMember) shownAccount else "Back", onBack = ::back,
            alerts = alerts, alertsBusy = alertsBusy, onAlerts = {
                val dev = chorus.device
                if (dev != null) {
                    alertsBusy = true
                    scope.launch {
                        try { alerts = PeopleApi.setPostAlerts(dev, accountId, id, alerts == "none"); error = null }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: "Could not change post alerts." }
                        finally { alertsBusy = false }
                    }
                }
            })
        return
    }
    Column(Modifier.fillMaxSize().background(p.bg).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = ::back, contentPadding = PaddingValues(0.dp)) {
            Text("‹ ${if (memberId != null && memberId != initialMember) shownAccount else if (initialMember != null) "Back" else "Friends"}")
        }
        error?.let { Text(it, color = p.danger) }
        run {
            Text(shownAccount, color = p.ink, style = MaterialTheme.typography.headlineSmall)
            val shared = list
            when {
                shared == null && error == null -> Text("Loading…", color = p.ink3)
                shared != null && shared.isEmpty() -> Text("$shownAccount haven't shared any member profiles with you yet.", color = p.ink3)
                // rows as their Members list shows them (D-100): banner, pronouns and role pills they share
                shared != null -> for (m in shared) Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.surface)
                    .clickable { memberId = m.optString("id") }) {
                    val banner = m.text("banner_blob")
                    val shadow = if (banner != null) TextStyle(shadow = Shadow(p.surface, blurRadius = 8f)) else TextStyle.Default
                    if (banner != null) Box(Modifier.matchParentSize(), contentAlignment = Alignment.BottomEnd) { RowBanner(chorus, banner) }
                    Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(glyph(m), m.text("color") ?: "#A09184", 44.dp, avatarBlob = m.text("avatar_blob"))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(shown(m), color = tonesOf(m.text("color") ?: "#A09184").name, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = shadow, modifier = Modifier.widthIn(max = 220.dp))
                                m.text("pronouns")?.let { Text(it, color = p.ink3, style = MaterialTheme.typography.bodySmall.merge(shadow),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
                            }
                            m.text("handle")?.let { Text("@$it", color = p.ink3, style = MaterialTheme.typography.bodySmall.merge(shadow),
                                maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            val roles = m.optJSONArray("roles")?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String } }.orEmpty()
                            if (roles.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) { for (r in roles) RolePill(r) }
                        }
                        ChorusIcon(AppIcon.Chevron, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

/** One of their members, on the profile every member has (owner, 2026-11-06): the shared hero with
 * Post alerts beside the avatar, name and pronouns, bio, then Posts (theirs we may read, top level)
 * and About (fields and relationships). */
@Composable
private fun FriendMemberProfile(chorus: Chorus, model: garden.vayne.chorus.data.Model, accountId: String, memberId: String,
    shownAccount: String, m: JSONObject?, missing: Boolean, error: String?, backLabel: String, onBack: () -> Unit,
    alerts: String, alertsBusy: Boolean, onAlerts: () -> Unit) {
    val p = LocalChorusPalette.current
    fun JSONObject.text(key: String) = optString(key).takeIf { !isNull(key) && it.isNotBlank() }
    fun shown(x: JSONObject) = x.text("display_name") ?: x.text("name") ?: "Someone"
    var tab by rememberSaveable(memberId) { mutableStateOf("Posts") }
    val posts by produceState<List<garden.vayne.chorus.data.SharedPost>?>(null, accountId, memberId, chorus.device?.session) {
        val dev = chorus.device ?: return@produceState
        value = try { PeopleApi.memberPosts(dev, accountId, memberId) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { emptyList() }
    }
    Column(Modifier.fillMaxSize().background(p.bg).verticalScroll(rememberScrollState())) {
        if (m == null) {
            TextButton(onClick = onBack, modifier = Modifier.padding(8.dp)) { Text("‹ $backLabel") }
            error?.let { Text(it, color = p.danger, modifier = Modifier.padding(horizontal = 16.dp)) }
            Text(if (missing) "This profile isn't shared with you." else "Loading…", color = p.ink3, modifier = Modifier.padding(16.dp))
            return@Column
        }
        val color = m.text("color") ?: "#A09184"
        ProfileHeroFrame(chorus, m.text("banner_blob"), color, m.text("sigil") ?: shown(m).take(1).uppercase(), m.text("avatar_blob"),
            backLabel, onBack) {
            // ask to hear when they post; their system says yes or no (D-106)
            if (m.has("post_alerts")) ProfilePill(when (alerts) { "active" -> "Post alerts on"; "requested" -> "Requested · Cancel"; else -> "Get post alerts" },
                garden.vayne.chorus.designsystem.R.drawable.ic_bell, filled = alerts == "active", onClick = { if (!alertsBusy) onAlerts() })
        }
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(shown(m), color = tonesOf(color).name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            // their handle (D-126), and the full name only when they shared it with us
            m.text("handle")?.let { Text("@$it", color = p.ink2) }
            val fullName = m.text("name")?.takeIf { m.text("display_name") != null && it != m.text("display_name") }
            Text(listOfNotNull(fullName, m.text("pronouns"), shownAccount).joinToString(" · "), color = p.ink3)
            if (alerts == "requested") Text("$shownAccount will see your request.", color = p.ink3, style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = p.danger) }
            m.text("description")?.let { Text(it, color = p.ink) }
        }
        TabRow(if (tab == "Posts") 0 else 1, containerColor = p.bg, contentColor = p.accent) {
            for (name in listOf("Posts", "About")) Tab(selected = tab == name, onClick = { tab = name },
                text = { Text(name, color = if (tab == name) p.ink else p.ink3) })
        }
        if (tab == "Posts") {
            val shownPosts = posts
            when {
                shownPosts == null -> Text("Loading…", color = p.ink3, modifier = Modifier.padding(16.dp))
                shownPosts.isEmpty() -> Text("No posts shared with you yet.", color = p.ink3, modifier = Modifier.padding(16.dp))
                else -> for (post in shownPosts) {
                    SharedPostPreview(post, shownAccount, chorus, model)
                    HorizontalDivider(color = p.line, thickness = .5.dp)
                }
            }
        } else Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val fields = m.optJSONArray("fields") ?: JSONArray()
            val birthday = m.text("birthday")
            if (birthday != null || fields.length() > 0) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.surface).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                birthday?.let { FriendField("Birthday") { Text(garden.vayne.chorus.data.ProfilePresentation.birthday(it), color = p.ink) } }
                for (i in 0 until fields.length()) {
                    val f = fields.getJSONObject(i)
                    FriendField(f.optString("name")) { FriendValue(f.optString("type"), f.opt("value")) }
                }
            }
            val links = m.optJSONArray("relationships") ?: JSONArray()
            if (links.length() > 0) Text("Relationships", color = p.ink, style = MaterialTheme.typography.titleMedium)
            for (i in 0 until links.length()) {
                val link = links.getJSONObject(i)
                Text("${link.optString("type")} · ${shown(link.getJSONObject("target"))}" +
                    (link.getJSONObject("target").text("handle")?.let { "  @$it" } ?: ""), color = p.ink)
                link.text("note")?.let { Text(it, color = p.ink2) }
            }
            Text("You see what $shownAccount chose to share with you.", color = p.ink3, style = MaterialTheme.typography.bodySmall)
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

