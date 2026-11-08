package garden.vayne.chorus.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.FollowPresets
import garden.vayne.chorus.data.FollowerView
import garden.vayne.chorus.data.Friends
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.PeopleApi
import garden.vayne.chorus.data.SharedMember
import garden.vayne.chorus.data.SharedPost
import garden.vayne.chorus.data.SpaceInfo
import garden.vayne.chorus.data.Spaces
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import org.json.JSONArray
import org.json.JSONObject

private fun JSONObject.text(key: String) = optString(key).takeIf { has(key) && !isNull(key) && it.isNotBlank() }
internal fun shownMember(m: JSONObject) = m.text("display_name") ?: m.text("name") ?: "Someone"

/** A friend's shared members (D-101), read once per screen visit into [cache]. */
@Composable
internal fun rememberFriendMembers(chorus: Chorus, account: String, cache: SnapshotStateMap<String, List<JSONObject>>): List<JSONObject>? {
    LaunchedEffect(account, chorus.device?.session) {
        val dev = chorus.device ?: return@LaunchedEffect
        runCatching { PeopleApi.members(dev, account).optJSONArray("items") ?: JSONArray() }.getOrNull()?.let { items ->
            cache[account] = List(items.length()) { items.getJSONObject(it) }
        }
    }
    return cache[account]
}

/**
 * One friend (UI20): a system (F7.2, and F7.5 as a person sees it) or a person (F7.3). The hero
 * with their kind and the bucket we put them in, Message / Notifying / Chats, who's around now as
 * they share it, the members they share, and what's between us. A person shows their posts, journal
 * and about instead of fronting.
 */
@Composable
internal fun FriendPage(chorus: Chorus, model: Model, data: FriendsData, accountId: String,
    members: SnapshotStateMap<String, List<JSONObject>>, onBack: () -> Unit, onMessage: () -> Unit,
    onShare: (String) -> Unit, onNotify: (String) -> Unit, onMember: (String) -> Unit, onThread: (String) -> Unit,
    onReplyPost: (String) -> Unit, onOpenChat: (String) -> Unit, act: (String, suspend () -> Unit) -> Unit) {
    val p = LocalChorusPalette.current
    val row = data.row(accountId)
    if (row == null) {
        Column(Modifier.fillMaxSize().background(p.bg)) {
            SubpageBar("Friend", null, onBack)
            Text(if (data.loaded) "You’re no longer connected to this account." else "Loading…", color = p.ink3, modifier = Modifier.padding(24.dp))
        }
        return
    }
    val account = row.account
    val name = account.shownName
    val now = remember(data) { System.currentTimeMillis() }
    val shared = rememberFriendMembers(chorus, accountId, members)
    val spaces by produceState<List<SpaceInfo>>(emptyList(), accountId, chorus.device?.session) {
        val dev = chorus.device ?: return@produceState
        value = runCatching { Spaces.list(dev).values.filter { s -> s.kind == "shared" && s.accounts.any { it.id == accountId } } }.getOrDefault(emptyList())
    }
    var menu by remember { mutableStateOf(false) }
    var chats by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val self = if (!account.isSystem) shared?.firstOrNull() else null
    val buckets = model.privacyBuckets.filter { accountId in model.bucketAssignments[it.id].orEmpty() }

    // between us: what we share, what we hear, where we talk (a person's page too)
    val between: @Composable () -> Unit = {
        val follower = row.follower?.takeIf { it.status == "active" }
        val following = row.following?.takeIf { it.status == "active" }
        if (follower != null || following != null || spaces.isNotEmpty()) {
            SectionLabel("Between you", Modifier.padding(horizontal = 12.dp, vertical = 0.dp))
            follower?.let { f ->
                val override = model.followCeilings[f.id] ?: JSONObject()
                val choice = FollowPresets.choiceOf(override)
                val how = if (choice == "inherit") buckets.firstOrNull()?.let { FollowPresets.choiceOf(it.ceiling).takeIf { c -> c != "inherit" }?.let(Friends::presetName) }
                    ?: Friends.presetName(Friends.everyoneElse(model.accountPrefs.followCeiling)) else Friends.presetName(choice)
                LinkRow("What you share with them", listOfNotNull(buckets.joinToString(", ") { it.name }.ifEmpty { null },
                    if (model.isPerson) null else how).joinToString(" · ").ifEmpty { "In no bucket" }, R.drawable.ic_shield) { onShare(f.id) }
            }
            following?.let { f ->
                val names = shared.orEmpty().associate { it.optString("id") to shownMember(it) }
                LinkRow("Notifications from them", Friends.prefsSummary(f.prefs, names, now), R.drawable.ic_bell) { onNotify(f.id) }
            }
            if (spaces.isNotEmpty()) LinkRow("Shared spaces", spaces.joinToString(" · ") { spaceName(it, model) }, R.drawable.ic_members) { chats = true }
        }
    }
    Column(Modifier.fillMaxSize().background(p.bg)) {
        SubpageBar(name, listOfNotNull(if (account.isSystem) "System" else "Person",
            if (!account.isSystem) account.handle?.let { "@$it" } else Friends.friendsSince(row.since, now)
                ?: if (row.follows) "you follow them" else if (row.waiting) "request sent" else "follows you").joinToString(" · "), onBack) {
            BarIcon(R.drawable.ic_more_vert, "More", p.ink) { menu = true }
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            // hero (F7.2): face, name, handle and how much we see, kind and bucket pills
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AccountAvatar(account, 88.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(name, color = p.ink, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val line = listOfNotNull(account.handle?.let { "@$it" },
                        if (account.isSystem) shared?.size?.takeIf { it > 0 }?.let { n -> if (n == 1) "1 member visible" else "$n members visible" }
                        else self?.text("pronouns"))
                    if (line.isNotEmpty()) Text(line.joinToString(" · "), color = p.ink3, fontSize = 14.sp, lineHeight = 19.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        KindPill(account)
                        for (b in buckets) Row(Modifier.clip(RoundedCornerShape(8.dp)).background(p.accentSoft).padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            BucketGlyph(b.icon, b.color, 13.dp)
                            Text(b.name, color = p.accent, fontSize = 12.sp, lineHeight = 16.sp)
                        }
                    }
                }
            }
            self?.text("description")?.let { Text(it, color = p.ink, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) }
            // actions (F7.2: Message · Notifying · Chats; F7.3: Message · Tell me about posts)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val connected = row.follows || row.follower?.status == "active"
                SmallButton("Message", primary = true, icon = R.drawable.ic_message, enabled = connected, onClick = onMessage)
                if (account.isSystem) row.following?.takeIf { row.follows }?.let { f ->
                    val on = Friends.enabled(f.prefs) && Friends.mutedUntil(f.prefs, now) == null
                    SmallButton(if (on) "Notifying" else "Notifications off", soft = on, icon = if (on) R.drawable.ic_bell else R.drawable.ic_bell_off) { onNotify(f.id) }
                } else if (row.follows) PersonAlertsButton(chorus, accountId, act)
                if (account.isSystem && spaces.isNotEmpty()) SmallButton("Chats", icon = R.drawable.ic_members) { chats = true }
                if (row.following == null) SmallButton("Follow back", icon = R.drawable.ic_user_plus) {
                    act("Couldn’t send the request.") { PeopleApi.request(checkNotNull(chorus.device), accountId) }
                }
            }
            if (!account.isSystem) {
                between()
                PersonTabs(chorus, model, accountId, name, self, onThread, onReplyPost)
                return@Column
            }
            if (row.follows) AroundNowCard(name, row.view, now)
            row.view?.let { SharedHistory(it) }
            // the members they share with us (F7.2 faces; F7.5 rows, as a person sees a system)
            if (!shared.isNullOrEmpty()) {
                SectionLabel("Members you can see", Modifier.padding(horizontal = 12.dp)) { Text("${shared.size}", color = p.ink3, fontSize = 12.sp) }
                if (model.isPerson) for (m in shared) MemberRow(m) { onMember(m.optString("id")) }
                else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    for (m in shared) MemberFace(m) { onMember(m.optString("id")) }
                }
            }
            between()
            Spacer(Modifier.height(24.dp))
        }
    }

    MenuDrawer(menu, { menu = false }, name, account.handle?.let { "@$it" }) {
        account.handle?.let { h -> MenuRow("Copy @$h", R.drawable.ic_copy) {
            menu = false
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("Handle", "@$h"))
        } }
        if (row.following == null) MenuRow("Follow back", R.drawable.ic_user_plus) {
            menu = false; act("Couldn’t send the request.") { PeopleApi.request(checkNotNull(chorus.device), accountId) }
        }
        row.following?.let { MenuRow(if (row.waiting) "Cancel request" else "Unfollow", R.drawable.ic_close, danger = true) { menu = false; confirm = "unfollow" } }
        row.follower?.takeIf { it.status == "active" }?.let { MenuRow("Remove follower", R.drawable.ic_delete, danger = true) { menu = false; confirm = "remove" } }
    }
    when (confirm) {
        "unfollow" -> ConfirmDrawer(if (row.waiting) "Cancel your request?" else "Unfollow $name?",
            if (row.waiting) null else "They aren’t told.",
            if (row.waiting) "Cancel request" else "Unfollow", danger = true, onDismiss = { confirm = null }, onConfirm = {
                confirm = null
                act("Couldn’t unfollow.") { PeopleApi.unfollow(checkNotNull(chorus.device), row.following!!.id) }
            })
        "remove" -> ConfirmDrawer("Remove $name as a follower?", "They stop hearing your switches and seeing what you share with followers.",
            "Remove", danger = true, onDismiss = { confirm = null }, onConfirm = {
                confirm = null
                act("Couldn’t remove them.") { chorus.create("follow.end", row.follower!!.id, JSONObject()) }
            })
    }
    MenuDrawer(chats, { chats = false }, "Shared spaces") {
        for (s in spaces) MenuRow(spaceName(s, model), R.drawable.ic_members, "${s.accounts.size} accounts") { chats = false; onOpenChat(s.id) }
    }
}

private fun spaceName(s: SpaceInfo, model: Model) = model.spaces.find { it.id == s.id }?.name?.takeIf { it.isNotBlank() }
    ?: s.accounts.joinToString(" + ") { it.shownName }

/** "Tell me about posts" for a person we follow (D-106, by account). */
@Composable
private fun PersonAlertsButton(chorus: Chorus, accountId: String, act: (String, suspend () -> Unit) -> Unit) {
    var state by remember(accountId) { mutableStateOf<String?>(null) }
    LaunchedEffect(accountId, chorus.device?.session) {
        val dev = chorus.device ?: return@LaunchedEffect
        state = runCatching { PeopleApi.personAlerts(dev, accountId) }.getOrNull()
    }
    val s = state ?: return
    SmallButton(when (s) { "active" -> "Told about posts"; "requested" -> "Asked about posts"; else -> "Tell me about posts" },
        soft = s != "none", icon = R.drawable.ic_bell) {
        act("Couldn’t change post alerts.") { state = PeopleApi.setPersonAlerts(checkNotNull(chorus.device), accountId, s == "none") }
    }
}

/** F7.2 "Around now": who they show, ringed by layer, since when, and how precise their times are. */
@Composable
private fun AroundNowCard(name: String, view: FollowerView?, now: Long) {
    val p = LocalChorusPalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp)).background(p.surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Around now", color = p.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Friends.sinceLine(view, now)?.takeIf { view?.entries?.isNotEmpty() == true }?.let { Text(it, color = p.ink3, fontSize = 13.sp) }
        }
        val shown = view?.entries.orEmpty()
        when {
            view == null -> Text("Couldn’t load who’s around.", color = p.ink3, fontSize = 14.sp, lineHeight = 19.sp)
            !view.shared -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph(R.drawable.ic_eye_off, p.ink3, 16.dp); Text("$name haven’t shared who’s around with you.", color = p.ink2, fontSize = 14.sp, lineHeight = 19.sp)
            }
            shown.isEmpty() -> Text(if (view.since == null) "$name share who’s around after their next switch." else "Nobody’s around right now.",
                color = p.ink2, fontSize = 14.sp)
            else -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for (m in shown) LayerFace(m)
            }
        }
        for ((front, names) in view?.fronts.orEmpty()) Text("$front: ${names.joinToString(" & ").ifBlank { "nobody right now" }}", color = p.ink2, fontSize = 13.sp)
        Friends.timeNote(name, view?.time)?.let { note ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Glyph(R.drawable.ic_clock, p.ink3, 14.dp, modifier = Modifier.padding(top = 2.dp))
                Text(note, color = p.ink3, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }
}

/** A face ringed the way Fronting draws its layer: solid for the front, dashed for co-conscious, faint beyond. */
@Composable
private fun LayerFace(m: SharedMember) {
    val p = LocalChorusPalette.current
    val ring = when (m.level) { "front" -> p.accent; "cocon" -> p.accent.copy(alpha = .7f); else -> p.ink3.copy(alpha = .6f) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.width(72.dp)) {
        Box(Modifier.size(64.dp).layerOutline(ring, 32.dp, m.level != "front", 2.dp), contentAlignment = Alignment.Center) {
            Avatar(m.glyph ?: m.name.take(1).uppercase(), m.color ?: "#A09184", 54.dp, avatarBlob = m.avatarBlob, ring = false)
        }
        Text(m.name, color = p.ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        if (m.level != "front") Text(Friends.levelWord(m.level), color = p.ink3, fontSize = 11.sp, lineHeight = 13.sp)
    }
}

/** Their history and stats, when they share them (share_history / share_stats). */
@Composable
private fun SharedHistory(view: FollowerView) {
    val p = LocalChorusPalette.current
    val stats = view.stats?.members?.filter { it.second > 0 }.orEmpty()
    val history = view.history?.drop(1).orEmpty()
    if (stats.isEmpty() && history.isEmpty()) return
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (stats.isNotEmpty()) Text("Most often, last ${view.stats!!.days} days: " + stats.joinToString(" · ") { "${it.first} ${it.second}%" },
            color = p.ink2, fontSize = 13.sp)
        if (history.isNotEmpty()) {
            Text(if (open) "Hide earlier fronts" else "Earlier fronts (${history.size})", color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { open = !open }.padding(vertical = 6.dp))
            if (open) for (h in history) Text(h.names.joinToString(" & ").ifBlank { "Nobody shared" } +
                h.time.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(), color = p.ink3, fontSize = 13.sp)
        }
    }
}

@Composable
private fun MemberFace(m: JSONObject, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    Column(Modifier.width(64.dp).clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Open ${shownMember(m)}", onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Avatar(m.text("sigil") ?: shownMember(m).take(1).uppercase(), m.text("color") ?: "#A09184", 52.dp, avatarBlob = m.text("avatar_blob"), ring = false)
        Text(shownMember(m), color = p.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        m.text("pronouns")?.let { Text(it, color = p.ink3, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun MemberRow(m: JSONObject, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Avatar(m.text("sigil") ?: shownMember(m).take(1).uppercase(), m.text("color") ?: "#A09184", 40.dp, avatarBlob = m.text("avatar_blob"), ring = false)
        Column(Modifier.weight(1f)) {
            Text(shownMember(m), color = p.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            val roles = m.optJSONArray("roles")?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String } }.orEmpty()
            val line = listOfNotNull(m.text("pronouns"), roles.firstOrNull()?.lowercase())
            if (line.isNotEmpty()) Text(line.joinToString(" · "), color = p.ink3, fontSize = 13.sp)
        }
        Glyph(R.drawable.ic_chevron_right, p.ink3, 18.dp)
    }
}

/** F7.3: a person's posts, journal and about, as far as they share them with us. */
@Composable
private fun PersonTabs(chorus: Chorus, model: Model, accountId: String, name: String, self: JSONObject?,
    onThread: (String) -> Unit, onReplyPost: (String) -> Unit) {
    val p = LocalChorusPalette.current
    var tab by rememberSaveable(accountId) { mutableStateOf(0) }
    val posts by produceState<List<SharedPost>?>(null, accountId, chorus.device?.session) {
        val dev = chorus.device ?: return@produceState
        value = runCatching { PeopleApi.posts(dev, accountId).filter { it.replyTo == null && it.repostOf == null } }.getOrDefault(emptyList())
    }
    val about by produceState<JSONObject?>(null, accountId, self?.optString("id"), chorus.device?.session) {
        val dev = chorus.device ?: return@produceState
        val id = self?.optString("id") ?: return@produceState
        value = runCatching { PeopleApi.memberProfile(dev, accountId, id) }.getOrNull()
    }
    Spacer(Modifier.height(10.dp))
    UnderlineTabs(listOf("Posts" to 0, "Journal" to 0, "About" to 0), tab, onPage = true) { tab = it }
    val shown = posts
    if (tab < 2) {
        val list = shown?.filter { if (tab == 0) it.kind != "entry" else it.kind == "entry" }
        when {
            list == null -> Text("Loading…", color = p.ink3, modifier = Modifier.padding(16.dp))
            list.isEmpty() -> Text(if (tab == 0) "No posts shared with you yet." else "No journal entries shared with you yet.",
                color = p.ink3, modifier = Modifier.padding(16.dp))
            else -> for (post in list) {
                SharedPostPreview(post, name, chorus, model, onThread = { onThread(it.id) }, onReply = { onReplyPost(it.id) })
                HorizontalDivider(color = p.line, thickness = .5.dp)
            }
        }
        return
    }
    val a = about
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (a == null) { Text("Nothing more shared with you.", color = p.ink3); return@Column }
        a.text("description")?.let { Text(it, color = p.ink, fontSize = 15.sp) }
        val fields = a.optJSONArray("fields") ?: JSONArray()
        val birthday = a.text("birthday")
        if (birthday != null || fields.length() > 0) FormCard {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                birthday?.let { FriendField("Birthday") { Text(garden.vayne.chorus.data.ProfilePresentation.birthday(it), color = p.ink) } }
                for (i in 0 until fields.length()) fields.getJSONObject(i).let { f -> FriendField(f.optString("name")) { FriendValue(f.optString("type"), f.opt("value")) } }
            }
        }
    }
}

