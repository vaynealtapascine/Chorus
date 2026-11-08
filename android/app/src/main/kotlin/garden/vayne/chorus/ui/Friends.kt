package garden.vayne.chorus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.BucketControls
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.FollowInfo
import garden.vayne.chorus.data.FollowList
import garden.vayne.chorus.data.FollowPresets
import garden.vayne.chorus.data.FollowerView
import garden.vayne.chorus.data.FriendFronts
import garden.vayne.chorus.data.FriendRow
import garden.vayne.chorus.data.Friends
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.PeopleApi
import garden.vayne.chorus.data.SharedMember
import garden.vayne.chorus.data.SharedPost
import garden.vayne.chorus.data.SpaceAccount
import garden.vayne.chorus.data.Spaces
import garden.vayne.chorus.data.Subject
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/** What Friends reads from the server, refreshed together: never stored, the server filters it all. */
internal data class FriendsData(val follows: FollowList = FollowList(emptyList(), emptyList()),
    val views: Map<String, FollowerView> = emptyMap(), val latest: Map<String, SharedPost?> = emptyMap(),
    /** Post alerts on our members (D-106): requested or active. */
    val postAlerts: List<JSONObject> = emptyList(), val loaded: Boolean = false,
    /** Each followed account's newest page of posts (Home's *New from friends*). */
    val pages: Map<String, List<SharedPost>> = emptyMap()) {
    val rows: List<FriendRow> by lazy { Friends.rows(follows, views, latest) }
    fun row(account: String): FriendRow? = rows.find { it.id == account }
    val requests: List<FollowInfo> get() = follows.followers.filter { it.status == "requested" }
    val followers: List<FollowInfo> get() = follows.followers.filter { it.status == "active" }
}

/** Read Friends from the server (Friends, Home): follows, what each followed system shares of who's
 * here, each followed account's newest posts and post alerts ([alerts] stands when they fail). */
internal suspend fun loadFriendsData(dev: garden.vayne.chorus.data.DeviceRecord, alerts: List<JSONObject> = emptyList()): FriendsData {
    val follows = PeopleApi.list(dev)
    val active = follows.following.filter { it.status == "active" }
    val (views, pages) = coroutineScope {
        val v = active.filter { it.account.isSystem }.map { f -> async {
            runCatching { f.account.id to PeopleApi.followerView(PeopleApi.view(dev, f.account.id)) }.getOrNull()
        } }
        val l = active.map { f -> async {
            runCatching { f.account.id to PeopleApi.posts(dev, f.account.id) }.getOrNull()
        } }
        v.awaitAll().filterNotNull().toMap() to l.awaitAll().filterNotNull().toMap()
    }
    for ((account, view) in views) FriendFronts.put(dev.accountId, account, view)
    val postAlerts = runCatching { PeopleApi.postAlerts(dev) }.getOrDefault(alerts)
    return FriendsData(follows, views, pages.mapValues { it.value.firstOrNull() }, postAlerts, loaded = true, pages = pages)
}

/** An account's colour where it has none of its own: one of the bucket swatches, by id. */
internal fun accountColor(id: String): String = BucketControls.swatches[Math.floorMod(id.hashCode(), BucketControls.swatches.size)]

@Composable
internal fun AccountAvatar(account: SpaceAccount, size: Dp) {
    Avatar(account.shownName.removePrefix("@").take(1).uppercase(), accountColor(account.id), size, avatarBlob = account.avatarBlob, ring = false)
}

/** "System" / "Person": the quiet pill after a friend's name. */
@Composable
internal fun KindPill(account: SpaceAccount) {
    val p = LocalChorusPalette.current
    Text(if (account.isSystem) "System" else "Person", color = p.ink2, fontSize = 12.sp, lineHeight = 16.sp,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(p.surface2).padding(horizontal = 8.dp, vertical = 2.dp))
}

/** A grey card of context (F7.1 footer, F7.8, F7.12): an icon and a line or two. */
@Composable
internal fun QuietNote(text: String, icon: Int, modifier: Modifier = Modifier) {
    val p = LocalChorusPalette.current
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.surface).padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Glyph(icon, p.ink3, 16.dp, modifier = Modifier.padding(top = 2.dp))
        Text(text, color = p.ink2, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
    }
}

/** A row on a page: an icon, a title and a line under it, a chevron (F7.2 "Between you", F7.8). */
@Composable
internal fun LinkRow(title: String, subtitle: String?, icon: Int?, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        icon?.let { Glyph(it, p.ink2, 22.dp) }
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) p.ink else p.ink3, fontSize = 16.sp, lineHeight = 21.sp)
            subtitle?.let { Text(it, color = p.ink3, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Glyph(R.drawable.ic_chevron_right, p.ink3, 18.dp)
    }
}

/** Tabs under a title, Figma style: the chosen one in ink with an accent underline, a count badge. */
@Composable
internal fun UnderlineTabs(tabs: List<Pair<String, Int>>, selected: Int, onPage: Boolean = false, onSelect: (Int) -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().background(if (onPage) p.bg else p.surface).padding(horizontal = 4.dp)) {
        tabs.forEachIndexed { i, (label, count) ->
            val on = i == selected
            Row(Modifier.clickable(role = Role.Tab) { onSelect(i) }.semantics { this.selected = on }
                .drawBehind { if (on) drawLine(p.accent, Offset(12.dp.toPx(), size.height - 1.5.dp.toPx()),
                    Offset(size.width - 12.dp.toPx(), size.height - 1.5.dp.toPx()), 3.dp.toPx()) }
                .padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, color = if (on) p.ink else p.ink3, fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium)
                if (count > 0) Text("$count", color = p.ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, lineHeight = 14.sp,
                    modifier = Modifier.clip(CircleShape).background(p.surface3).padding(horizontal = 6.dp, vertical = 1.dp))
            }
        }
    }
    HorizontalDivider(color = p.line)
}

internal fun SharedMember.subject() = Subject("member", name, name, color ?: "#A09184", glyph ?: name.take(1).uppercase(), avatarBlob)

/**
 * Friends (UI20, Figma F7): a system sees Friends · Requests · Sharing (F7.1, F7.7, F7.8), a person
 * Following · Followers · Requests (F7.4). A friend opens on their own page (F7.2, F7.3, F7.5), and
 * from there what we share with them (F7.9), a bucket (F7.10) and their notifications (F7.12).
 * Everything about other accounts is the server's filtered view, read when the screen opens.
 */
@Composable
internal fun FriendsScreen(chorus: Chorus, model: Model, onOpenChat: (String) -> Unit, onReplyPost: (String) -> Unit,
    onFocusedRoute: (Boolean) -> Unit = {},
    /** Open at these pages (from a notification), then [onRouteConsumed]. */
    openRoute: List<String>? = null, onRouteConsumed: () -> Unit = {}) {
    val p = LocalChorusPalette.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf(FriendsData()) }
    var refresh by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    // a friend's shared members (D-101), as their pages need them
    val members = remember { mutableStateMapOf<String, List<JSONObject>>() }
    // routes over the tabs, newest last: friend:<account>, share:<follow>, notify:<follow>,
    // bucket:<id or empty>, member:<account>:<member>, thread:<post>, advanced
    var stack by rememberSaveable { mutableStateOf(listOf<String>()) }
    fun push(route: String) { stack = stack + route }
    fun pop() { stack = stack.dropLast(1) }
    // kept above the routes, so coming back from a page returns to the same tab and search
    var tab by rememberSaveable { mutableStateOf(0) }
    // a route may start with "tab:<n>" (Privacy and sharing opens the Sharing tab)
    LaunchedEffect(openRoute) { if (openRoute != null) {
        openRoute.firstOrNull { it.startsWith("tab:") }?.substringAfter(':')?.toIntOrNull()?.let { tab = it }
        stack = openRoute.filterNot { it.startsWith("tab:") }; onRouteConsumed()
    } }
    LaunchedEffect(stack.isNotEmpty()) { onFocusedRoute(stack.isNotEmpty()) }
    BackHandler(stack.isNotEmpty()) { pop() }

    LaunchedEffect(chorus.device?.session, refresh) {
        val dev = chorus.device ?: return@LaunchedEffect
        loading = true
        try {
            data = loadFriendsData(dev, data.postAlerts)
            error = null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Couldn’t load your friends." }
        finally { loading = false }
    }
    fun reload() { refresh++ }
    fun act(failure: String, block: suspend () -> Unit) {
        scope.launch {
            try { block(); reload() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: failure }
        }
    }
    fun message(account: String) = act("Couldn’t open the chat.") {
        onOpenChat(Spaces.openDm(checkNotNull(chorus.device) { "Not signed in." }, account))
    }

    var adding by remember { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val route = stack.lastOrNull()
    if (route != null) {
        val (kind, rest) = route.substringBefore(':') to route.substringAfter(':', "")
        when (kind) {
            "friend" -> FriendPage(chorus, model, data, rest, members, onBack = ::pop, onMessage = { message(rest) },
                onShare = { push("share:$it") }, onNotify = { push("notify:$it") }, onMember = { push("member:$rest:$it") },
                onThread = { push("thread:$it") }, onReplyPost = onReplyPost, onOpenChat = onOpenChat, act = ::act)
            "share" -> data.followers.find { it.id == rest }?.let { f ->
                FriendSharingPage(chorus, model, f, onBack = ::pop, onBucket = { push("bucket:$it") }, act = ::act,
                    onRemoved = { stack = stack.filterNot { it == "share:$rest" || it == "friend:${f.account.id}" }; reload() })
            } ?: LaunchedEffect(route) { pop() }
            "notify" -> data.follows.following.find { it.id == rest }?.let { f ->
                FriendNotifyPage(chorus, model, f, members, onBack = { pop(); reload() })
            } ?: run { if (data.loaded) LaunchedEffect(route) { pop() } }
            "bucket" -> BucketPage(chorus, model, rest.ifEmpty { null }, data.followers, onBack = ::pop)
            "advanced" -> AdvancedSharingPage(chorus, model, onBack = ::pop)
            "member" -> FriendProfiles(chorus, model, rest.substringBefore(':'), data.row(rest.substringBefore(':'))?.account?.shownName.orEmpty(),
                onClose = ::pop, initialMember = rest.substringAfter(':'), backEnabled = false)
            "thread" -> JournalThread(chorus, model, rest, onClose = ::pop,
                onProfile = { account, member -> push("member:$account:$member") },
                onReply = { pop(); onReplyPost(rest) })
            else -> LaunchedEffect(route) { pop() }
        }
        return
    }

    val person = model.isPerson
    val tabs = if (person) listOf("Following", "Followers", "Requests") else listOf("Friends", "Requests", "Sharing")
    var accepting by remember { mutableStateOf<FollowInfo?>(null) }
    var offerBack by remember { mutableStateOf<SpaceAccount?>(null) }
    fun followBack(id: String) = act("Couldn’t send the request.") { PeopleApi.request(checkNotNull(chorus.device), id) }
    val requestCount = data.requests.size + data.postAlerts.count { it.optString("status") == "requested" }
    val reader = inboxReader(model, null)
    val ownName = model.systemName?.takeIf { it.isNotBlank() } ?: model.accountHandle?.let { "@$it" } ?: if (person) "you" else "your system"

    Column(Modifier.fillMaxSize().background(p.bg)) {
        // F7.1 header: who's reading, add a friend, search
        Row(Modifier.fillMaxWidth().background(p.surface).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            reader?.let { ChatAvatar(it, 36.dp) }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("Friends", color = p.ink, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
                Text(if (person) "${reader?.shownName ?: ownName} · just me" else "$ownName · reading as ${reader?.shownName ?: "nobody"}",
                    color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BarIcon(R.drawable.ic_user_plus, "Follow someone", p.ink) { adding = true }
            BarIcon(if (searching) R.drawable.ic_close else R.drawable.ic_search,
                if (searching) "Close search" else "Search friends", p.ink) { searching = !searching; query = "" }
        }
        UnderlineTabs(tabs.map { it to if (it == "Requests") requestCount else 0 }, tab) { tab = it }
        if (searching) Box(Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 16.dp, vertical = 8.dp)) {
            ChorusField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Search friends") })
        }
        if (loading && !data.loaded) LinearProgressIndicator(Modifier.fillMaxWidth())
        val now = remember(data) { System.currentTimeMillis() }
        val q = query.trim().lowercase()
        val rows = data.rows.filter { q.isEmpty() || it.account.shownName.lowercase().contains(q) || it.account.handle.orEmpty().lowercase().contains(q) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            error?.let { item(key = "error") { Text(it, color = p.danger, fontSize = 13.sp, modifier = Modifier.padding(16.dp)) } }
            when (tabs[tab]) {
                "Friends" -> systemFriends(rows, now, onOpen = { push("friend:$it") }, onMessage = ::message, onFollow = { adding = true }, onFollowBack = ::followBack)
                "Following" -> personFollowing(rows, now, onOpen = { push("friend:$it") }, onMessage = ::message, onFollow = { adding = true })
                "Followers" -> personFollowers(model, rows, onOpen = { push("friend:$it") }, onBucket = { push("bucket:$it") }, onFollowBack = ::followBack)
                "Requests" -> requestItems(model, data, ownName, onOpen = { push("friend:$it") },
                    onAccept = { f -> if (person) act("Couldn’t accept.") {
                        chorus.create("follow.accept", f.id, JSONObject())
                        if (data.row(f.account.id)?.following == null) offerBack = f.account
                    } else accepting = f },
                    onDecline = { f -> act("Couldn’t decline.") { chorus.create("follow.end", f.id, JSONObject()) } },
                    onCancel = { f -> act("Couldn’t cancel.") { PeopleApi.unfollow(checkNotNull(chorus.device), f.id) } },
                    onAlert = { a, yes -> act("Couldn’t answer.") {
                        PeopleApi.decidePostAlert(checkNotNull(chorus.device), a.optString("member_id"), a.optJSONObject("account")?.optString("id").orEmpty(), yes)
                    } })
                "Sharing" -> sharingItems(model, data.followers, onBucket = { push("bucket:$it") }, onNewBucket = { push("bucket:") },
                    onAdvanced = { push("advanced") }, chorus = chorus)
            }
        }
    }

    if (adding) FollowDrawer(onDismiss = { adding = false }) { handle ->
        act("Couldn’t send the request.") { PeopleApi.request(checkNotNull(chorus.device) { "Not signed in." }, handle) }
        adding = false
    }
    accepting?.let { f ->
        AcceptDrawer(model, f, onDismiss = { accepting = null },
            onDecline = { accepting = null; act("Couldn’t decline.") { chorus.create("follow.end", f.id, JSONObject()) } },
            onAccept = { preset, bucket ->
                accepting = null
                act("Couldn’t accept.") {
                    acceptFollow(chorus, model, f, preset, bucket)
                    if (data.row(f.account.id)?.following == null) offerBack = f.account
                }
            })
    }
    offerBack?.let { them -> FollowBackDrawer(them.shownName, onDismiss = { offerBack = null }) {
        offerBack = null; followBack(them.id)
    } }
}

/** After accepting a request (D-129): they see what we share, not the other way round. */
@Composable
internal fun FollowBackDrawer(name: String, onDismiss: () -> Unit, onFollowBack: () -> Unit) =
    ConfirmDrawer("Follow $name back?", "$name can now see what you share with followers. You won’t see their posts or profiles until you follow back and they accept. Then you’re friends.",
        "Follow back", onConfirm = onFollowBack, onDismiss = onDismiss)

/** Accept a request (F7.7): into [bucket] when one is picked, with an override only where it differs. */
internal suspend fun acceptFollow(chorus: Chorus, model: Model, f: FollowInfo, preset: String?, bucket: String?) {
    chorus.create("follow.accept", f.id, JSONObject())
    if (bucket != null) chorus.create("bucket.assign", bucket, JSONObject().put("follower_account_id", f.account.id))
    if (preset != null) Friends.acceptCeiling(preset, model.privacyBuckets.find { it.id == bucket }?.ceiling, model.accountPrefs.followCeiling)?.let {
        chorus.create("follow.set_ceiling", f.id, JSONObject().put("ceiling", it))
    }
}

/** F7.1: friends (both ways, D-129) around right now first, then the other friends, then one-way follows. */
private fun LazyListScope.systemFriends(rows: List<FriendRow>, now: Long, onOpen: (String) -> Unit, onMessage: (String) -> Unit,
    onFollow: () -> Unit, onFollowBack: (String) -> Unit) {
    val (around, rest) = rows.filter { it.mutual }.partition { Friends.around(it, now) }
    val out = rows.filter { it.following != null && !it.mutual }
    val back = rows.filter { it.following == null }
    if (rows.isEmpty()) item(key = "empty") { EmptyFriends(onFollow) }
    if (around.isNotEmpty()) {
        item(key = "around") { SectionLabel("Around right now", Modifier.padding(horizontal = 12.dp)) {
            Text("as each friend shares it", color = LocalChorusPalette.current.ink3, fontSize = 12.sp) } }
        for (r in around) item(key = "a:${r.id}") { FriendListRow(r, now, true, onOpen, onMessage) }
    }
    if (rest.isNotEmpty()) {
        item(key = "rest") { SectionLabel("Not sharing who’s around", Modifier.padding(horizontal = 12.dp)) }
        for (r in rest) item(key = "r:${r.id}") { FriendListRow(r, now, false, onOpen, onMessage) }
    }
    if (out.isNotEmpty()) {
        item(key = "out") { SectionLabel("You follow", Modifier.padding(horizontal = 12.dp)) {
            Text("not following you back", color = LocalChorusPalette.current.ink3, fontSize = 12.sp) } }
        for (r in out) item(key = "o:${r.id}") { FriendListRow(r, now, Friends.around(r, now), onOpen, onMessage) }
    }
    if (back.isNotEmpty()) {
        item(key = "back") { SectionLabel("Follow you", Modifier.padding(horizontal = 12.dp)) {
            Text("follow back to see what they share", color = LocalChorusPalette.current.ink3, fontSize = 12.sp) } }
        for (r in back) item(key = "b:${r.id}") { FriendListRow(r, now, false, onOpen, onMessage, onFollowBack) }
    }
}

/** F7.4 Following: systems, then people. */
private fun LazyListScope.personFollowing(rows: List<FriendRow>, now: Long, onOpen: (String) -> Unit, onMessage: (String) -> Unit,
    onFollow: () -> Unit) {
    val followed = rows.filter { it.following != null }
    if (followed.isEmpty()) item(key = "empty") { EmptyFriends(onFollow) }
    val (systems, people) = followed.partition { it.account.isSystem }
    for ((title, group) in listOf("Systems" to systems, "People" to people)) {
        if (group.isEmpty()) continue
        item(key = "h:$title") { SectionLabel(title, Modifier.padding(horizontal = 12.dp)) {
            Text("${group.size}", color = LocalChorusPalette.current.ink3, fontSize = 12.sp) } }
        for (r in group) item(key = "$title:${r.id}") { FriendListRow(r, now, Friends.around(r, now) || !r.account.isSystem && r.follows, onOpen, onMessage) }
    }
}

/** F7.4 Followers: who follows us and the buckets they're in, then the buckets themselves. */
private fun LazyListScope.personFollowers(model: Model, rows: List<FriendRow>, onOpen: (String) -> Unit, onBucket: (String) -> Unit,
    onFollowBack: (String) -> Unit) {
    val followers = rows.filter { it.follower?.status == "active" }
    if (followers.isEmpty()) item(key = "empty") {
        EmptyState(R.drawable.ic_friends, "No followers yet")
    }
    for (r in followers) item(key = "f:${r.id}") {
        val p = LocalChorusPalette.current
        val buckets = model.privacyBuckets.filter { r.id in model.bucketAssignments[it.id].orEmpty() }
        Row(Modifier.fillMaxWidth().clickable { onOpen(r.id) }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AccountAvatar(r.account, 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(r.account.shownName, color = p.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    KindPill(r.account)
                }
                if (buckets.isEmpty()) Text("In no bucket", color = p.ink3, fontSize = 13.sp)
                else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { for (b in buckets.take(3)) BucketLabel(b.name, b.icon, b.color) }
                if (r.following == null) SmallButton("Follow back", icon = R.drawable.ic_user_plus) { onFollowBack(r.id) }
            }
            Glyph(R.drawable.ic_chevron_right, p.ink3, 18.dp)
        }
    }
    item(key = "buckets") { SectionLabel("Buckets", Modifier.padding(horizontal = 12.dp)) {
        Text("who sees which posts", color = LocalChorusPalette.current.ink3, fontSize = 12.sp) } }
    for (b in model.privacyBuckets) item(key = "b:${b.id}") { BucketRow(model, b, followers.map { it.account }, onClick = { onBucket(b.id) }) }
    item(key = "newbucket") { NewBucketButton { onBucket("") } }
}

@Composable
private fun EmptyFriends(onFollow: () -> Unit) = EmptyState(R.drawable.ic_friends, "No friends yet", action = "Follow", onAction = onFollow)

/** One friend on the list (F7.1, F7.4): who's here and since when, or what they did last. */
@Composable
private fun FriendListRow(r: FriendRow, now: Long, bright: Boolean, onOpen: (String) -> Unit, onMessage: (String) -> Unit,
    onFollowBack: ((String) -> Unit)? = null) {
    val p = LocalChorusPalette.current
    val view = r.view
    Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Open ${r.account.shownName}") { onOpen(r.id) }
        .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.alpha(if (bright) 1f else .55f)) { AccountAvatar(r.account, 44.dp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(r.account.shownName, color = if (bright) p.ink else p.ink2, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                KindPill(r.account)
            }
            val shown = view?.entries.orEmpty()
            when {
                r.waiting -> Text("Waiting for them to accept", color = p.ink3, fontSize = 14.sp, lineHeight = 19.sp)
                r.following == null -> Text("Follows you · you don’t follow them", color = p.ink3, fontSize = 14.sp, lineHeight = 19.sp)
                r.account.isSystem && shown.isNotEmpty() -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FaceStack(shown.map { it.subject() }, max = 3, size = 22.dp)
                    Text(Friends.nowLine(shown), color = p.ink, fontSize = 14.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                r.account.isSystem && view?.shared == false -> Text("Posts and chat only", color = p.ink3, fontSize = 14.sp, lineHeight = 19.sp)
                r.account.isSystem && view?.since == null -> Text("Shares who’s around after their next switch", color = p.ink3, fontSize = 14.sp, lineHeight = 19.sp)
                r.account.isSystem -> Text("Nobody’s around right now", color = p.ink3, fontSize = 14.sp, lineHeight = 19.sp)
                r.latest != null && bright -> Text(Friends.activity(r.latest, compactAgo(r.latest.occurredAt)), color = p.ink2, fontSize = 14.sp, lineHeight = 19.sp)
                r.latest != null -> Text("Last posted ${dayName(r.latest.occurredAt, now)}", color = p.ink3, fontSize = 14.sp, lineHeight = 19.sp)
                else -> Text("Nothing posted yet", color = p.ink3, fontSize = 14.sp, lineHeight = 19.sp)
            }
            val small: Pair<Int, String>? = when {
                r.waiting || r.following == null -> null
                r.account.isSystem && shown.isNotEmpty() -> Friends.sinceLine(view, now)?.let { R.drawable.ic_clock to it }
                r.account.isSystem && view?.shared == false -> R.drawable.ic_eye_off to "They haven’t shared fronting with you"
                r.account.isSystem -> Friends.friendsSince(r.since, now)?.let { R.drawable.ic_clock to it.replaceFirstChar { c -> c.uppercase() } }
                bright -> R.drawable.ic_user to "One person, so nothing to front"
                else -> null
            }
            small?.let { (icon, text) -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Glyph(icon, p.ink3, 13.dp); Text(text, color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp)
            } }
            if (onFollowBack != null && r.following == null) SmallButton("Follow back", icon = R.drawable.ic_user_plus) { onFollowBack(r.id) }
        }
        if (r.follows || r.follower?.status == "active") BarIcon(R.drawable.ic_message, "Message ${r.account.shownName}", p.ink3, 20.dp) { onMessage(r.id) }
    }
}

/** "Monday", "yesterday", "today", else a date. */
internal fun dayName(at: Long, now: Long): String {
    val days = ((now - at) / 86_400_000L).toInt()
    return when {
        days < 1 -> "today"
        days < 2 -> "yesterday"
        days < 7 -> java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault()).format(java.util.Date(at))
        else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(at))
    }
}

/** F7.7 Requests: who wants to follow us, who asked about posts, and our own requests. */
private fun LazyListScope.requestItems(model: Model, data: FriendsData, ownName: String, onOpen: (String) -> Unit,
    onAccept: (FollowInfo) -> Unit, onDecline: (FollowInfo) -> Unit, onCancel: (FollowInfo) -> Unit,
    onAlert: (JSONObject, Boolean) -> Unit) {
    val asked = data.postAlerts.filter { it.optString("status") == "requested" }
    val hearing = data.postAlerts.filter { it.optString("status") == "active" }
    val ours = data.follows.following.filter { it.status == "requested" }
    if (data.requests.isEmpty() && asked.isEmpty() && ours.isEmpty() && hearing.isEmpty()) item(key = "none") {
        EmptyState(R.drawable.ic_user_plus, "No requests")
    }
    if (data.requests.isNotEmpty()) {
        item(key = "h:in") { SectionLabel("Want to follow $ownName", Modifier.padding(horizontal = 12.dp)) }
        for (f in data.requests) item(key = "in:${f.id}") {
            RequestRow(f.account, if (model.isPerson) "Would like to follow you." else "Would like to know when you switch.", { onOpen(f.account.id) }) {
                SmallButton(if (model.isPerson) "Accept" else "Accept…", primary = true) { onAccept(f) }
                SmallButton("Decline") { onDecline(f) }
            }
        }
    }
    if (asked.isNotEmpty()) {
        item(key = "h:alerts") { SectionLabel("Want to hear about posts", Modifier.padding(horizontal = 12.dp)) }
        for (a in asked) item(key = "al:${a.optString("member_id")}:${a.optJSONObject("account")?.optString("id")}") {
            val who = alertAccount(a)
            val member = model.member(a.optString("member_id"))?.shownName ?: "a member"
            RequestRow(who, if (model.isPerson) "Would like to hear when you post." else "Would like to hear when $member posts.", null) {
                SmallButton("Allow", primary = true) { onAlert(a, true) }
                SmallButton("Decline") { onAlert(a, false) }
            }
        }
    }
    if (ours.isNotEmpty()) {
        item(key = "h:out") { SectionLabel("You asked to follow", Modifier.padding(horizontal = 12.dp)) }
        for (f in ours) item(key = "out:${f.id}") {
            RequestRow(f.account, "Waiting for them to accept.", { onOpen(f.account.id) }) { SmallButton("Cancel request") { onCancel(f) } }
        }
    }
    if (hearing.isNotEmpty()) {
        item(key = "h:hear") { SectionLabel("Hear about our posts", Modifier.padding(horizontal = 12.dp)) }
        for (a in hearing) item(key = "hear:${a.optString("member_id")}:${a.optJSONObject("account")?.optString("id")}") {
            val member = model.member(a.optString("member_id"))?.shownName ?: "a member"
            RequestRow(alertAccount(a), if (model.isPerson) "Hears when you post." else "Hears when $member posts.", null) {
                SmallButton("Stop") { onAlert(a, false) }
            }
        }
    }
}

private fun alertAccount(a: JSONObject): SpaceAccount {
    val acc = a.optJSONObject("account") ?: JSONObject()
    fun t(k: String) = acc.optString(k).takeIf { acc.has(k) && !acc.isNull(k) && it.isNotBlank() }
    return SpaceAccount(t("id").orEmpty(), t("handle"), t("display_name"), t("kind"), t("avatar_blob"))
}

@Composable
private fun RequestRow(account: SpaceAccount, line: String, onOpen: (() -> Unit)?, buttons: @Composable RowScope.() -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
        .padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        AccountAvatar(account, 44.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(account.shownName, color = p.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (account.kind != null) KindPill(account)
            }
            Text(line, color = p.ink2, fontSize = 14.sp, lineHeight = 19.sp)
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), content = buttons)
        }
    }
}

/** A compact pill button (F7.7 "Accept…", "Decline"). */
@Composable
internal fun SmallButton(text: String, primary: Boolean = false, icon: Int? = null, soft: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    val (fill, ink) = when { primary -> p.accent to p.onAccent; soft -> p.accentSoft to p.accent; else -> p.surface2 to p.ink }
    Row(Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(18.dp)).background(if (enabled) fill else p.surface2)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        icon?.let { Glyph(it, if (enabled) ink else p.ink3, 16.dp) }
        Text(text, color = if (enabled) ink else p.ink3, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Follow someone by their handle. */
@Composable
internal fun FollowDrawer(onDismiss: () -> Unit, onFollow: (String) -> Unit) {
    var handle by rememberSaveable { mutableStateOf("") }
    Drawer(onDismiss, "Follow someone", footer = {
        DrawerButton("Send request", { onFollow(handle.trim()) }, enabled = handle.isNotBlank())
    }) {
        ChorusField(handle, { handle = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Their handle") },
            placeholder = { Text("@handle") })
    }
}

/**
 * F7.7: let someone follow us, with how much they hear and the bucket they go in. Starts on the
 * bucket new friends start in (F7.8) and its preset.
 */
@Composable
internal fun AcceptDrawer(model: Model, f: FollowInfo, onDismiss: () -> Unit, onDecline: () -> Unit, onAccept: (String?, String?) -> Unit) {
    val p = LocalChorusPalette.current
    val startBucket = startBucket(model)
    var bucket by remember { mutableStateOf(startBucket?.id) }
    val inherited = (model.privacyBuckets.find { it.id == bucket }?.ceiling?.let { FollowPresets.choiceOf(it) }?.takeIf { it != "inherit" }
        ?: Friends.everyoneElse(model.accountPrefs.followCeiling)).takeIf { it in Friends.PRESETS }
    // a custom bucket starts with no preset picked, so accepting keeps it in charge
    var preset by remember(bucket) { mutableStateOf<String?>(inherited) }
    var choosingBucket by remember { mutableStateOf(false) }
    Drawer(onDismiss, "Let ${f.account.shownName} follow you?", "Choose how much they hear.", footer = {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DrawerButton("Decline", onDecline, ButtonKind.Neutral, modifier = Modifier.weight(.4f))
            DrawerButton(preset?.let { "Accept as ${Friends.presetName(it)}" } ?: "Accept",
                { onAccept(preset, bucket) }, modifier = Modifier.weight(.6f))
        }
    }) {
        FormCard {
            Friends.PRESETS.forEachIndexed { i, choice ->
                if (i > 0) FormDivider()
                Row(Modifier.fillMaxWidth().clickable(role = Role.RadioButton) { preset = choice }
                    .semantics { selected = preset == choice }.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Radio(preset == choice)
                    Column(Modifier.weight(1f)) {
                        Text(if (choice == "close") "Close — instant" else Friends.presetName(choice), color = p.ink, fontSize = 16.sp)
                        Text(Friends.presetAbout(choice), color = p.ink3, fontSize = 13.sp, lineHeight = 17.sp)
                    }
                }
            }
        }
        val b = model.privacyBuckets.find { it.id == bucket }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cardColor()).clickable(role = Role.DropdownList) { choosingBucket = true }
            .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BucketTile(b?.icon, b?.color, 36.dp)
            Column(Modifier.weight(1f)) {
                Text("Bucket", color = p.ink3, fontSize = 12.sp)
                Text(b?.name ?: "No bucket", color = p.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Glyph(R.drawable.ic_chevron_down, p.ink3, 18.dp)
        }
    }
    MenuDrawer(choosingBucket, { choosingBucket = false }, "Bucket") {
        MenuRow("No bucket", subtitle = "Everyone else · ${Friends.presetName(Friends.everyoneElse(model.accountPrefs.followCeiling))}",
            selected = bucket == null, leading = { BucketTile(null, null, 36.dp) }) { bucket = null; choosingBucket = false }
        for (option in model.privacyBuckets) MenuRow(option.name, subtitle = Friends.presetName(FollowPresets.choiceOf(option.ceiling)),
            selected = bucket == option.id, leading = { BucketTile(option.icon, option.color, 36.dp) }) { bucket = option.id; choosingBucket = false }
    }
}

/** The bucket new friends start in (F7.8), when it still exists. */
internal fun startBucket(model: Model) = model.accountPrefs.startBucket?.let { id -> model.privacyBuckets.find { it.id == id } }

@Composable
internal fun Radio(on: Boolean) {
    val p = LocalChorusPalette.current
    Box(Modifier.size(22.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color.Transparent)
        .layerOutline(if (on) p.accent else p.ink3, 11.dp, false, 2.dp), contentAlignment = Alignment.Center) {
        if (on) Box(Modifier.size(10.dp).background(p.accent, CircleShape))
    }
}

/** A bucket's icon on a rounded tile in its colour (F7.7, F7.8, F7.10). */
@Composable
internal fun BucketTile(icon: String?, color: String?, size: Dp) {
    val p = LocalChorusPalette.current
    val tones = BucketControls.color(color)?.let { tonesOf(it) }
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(tones?.tint ?: p.surface2), contentAlignment = Alignment.Center) {
        if (icon == null) Glyph(R.drawable.ic_user, p.ink3, size * .5f)
        else BucketGlyph(icon, color, size * .5f)
    }
}
