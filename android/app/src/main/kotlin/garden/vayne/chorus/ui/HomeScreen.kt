package garden.vayne.chorus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Entry
import garden.vayne.chorus.data.ForeignAuthor
import garden.vayne.chorus.data.Front
import garden.vayne.chorus.data.FriendCard
import garden.vayne.chorus.data.FriendRow
import garden.vayne.chorus.data.Friends
import garden.vayne.chorus.data.FriendsFeed
import garden.vayne.chorus.data.FrontScene
import garden.vayne.chorus.data.FrontingDisplay
import garden.vayne.chorus.data.HomeDashboard
import garden.vayne.chorus.data.HomeSection
import garden.vayne.chorus.data.Member
import garden.vayne.chorus.data.Mode
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.NamedHistory
import garden.vayne.chorus.data.PeopleApi
import garden.vayne.chorus.data.Reply
import garden.vayne.chorus.data.SceneLayer
import garden.vayne.chorus.data.SpaceInfo
import garden.vayne.chorus.data.Spaces
import garden.vayne.chorus.data.Status
import garden.vayne.chorus.data.Subject
import garden.vayne.chorus.data.TodayEvent
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import garden.vayne.chorus.widget.WidgetPins
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where Home's links go (MainActivity owns the destinations). */
internal class HomeRoutes(
    val onSearch: () -> Unit,
    /** A chat (space, channel), or the chat list when null. */
    val onChat: (String?, String?) -> Unit,
    /** Friends, or a page in it (`friend:<account>`). */
    val onFriends: (List<String>?) -> Unit,
    val onHistory: () -> Unit,
    val onJournal: () -> Unit,
    val onPost: (String) -> Unit,
    val onReply: (String) -> Unit,
    /** The Journal composer, new. */
    val onWrite: () -> Unit,
    /** A member's profile (a person's own, to fill in). */
    val onProfile: (String) -> Unit,
)

/**
 * Chorus Home (D-130, Figma F0.1–F0.6): who's fronting with Switch and Log a past one, Quick
 * switch, chats waiting, friends right now, Today and new posts from friends; a person gets a
 * composer row, friends, chats and posts, and a first day of Welcome and Try. Each reader chooses
 * and orders the sections (F0.3, `home.sections`). Handover (D-132) takes its place once built.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomeScreen(chorus: Chorus, model: Model, routes: HomeRoutes) {
    val p = LocalChorusPalette.current
    val ctx = LocalContext.current
    val status by chorus.status.collectAsState()
    val live = status == Status.Live
    val accountId = chorus.device?.accountId.orEmpty()
    val person = model.isPerson
    val self = model.members.firstOrNull { it.isSelf }
    val own = model.active.filter { it.createdByAccountId == null || it.createdByAccountId == accountId }
    var chosenReader by rememberSaveable(accountId) { mutableStateOf<String?>(null) }
    val reader: Member? = if (person) self else own.find { it.id == chosenReader } ?: Reply.speaker(model) ?: own.firstOrNull()
    val prefs = model.accountPrefs
    val readerKey = reader?.id?.takeIf { !person }
    val sections = HomeDashboard.forReader(prefs.homeSections, prefs.homeSectionsAll, readerKey, person)
    val actions = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }

    // fronting: Body, as Fronting shows it
    val named = model.namedFronts
    val bodyId = named?.bodyId?.ifEmpty { null } ?: accountId
    val entries = if (named != null) named.entries(bodyId) else model.current
    val scene = FrontScene.build(model, bodyId, entries, accountId)
    val since = rememberSince(chorus, model, bodyId)
    val revision by chorus.frontHistoryRevision.collectAsState()
    val history by produceState<NamedHistory?>(null, revision, bodyId, named != null) {
        value = if (named == null) null else runCatching { chorus.frontHistory(bodyId) }.getOrNull()?.takeIf { it.frontId == bodyId }
    }
    val undo by Front.undo.collectAsState()
    LaunchedEffect(undo) { val u = undo ?: return@LaunchedEffect; delay(10_000); Front.dismiss(u.opId) }

    // friends, their posts and the chats' names for other accounts: the server's, read while live
    var friends by remember(accountId) { mutableStateOf(FriendsData()) }
    var directory by remember(accountId) { mutableStateOf<Map<String, SpaceInfo>>(emptyMap()) }
    var privateCards by remember(accountId) { mutableStateOf<Map<String, ForeignAuthor>>(emptyMap()) }
    val dmSpaces = model.spaces.filter { it.kind == "dm" }.map { it.id }.sorted()
    LaunchedEffect(chorus.device?.session, live) {
        val dev = chorus.device ?: return@LaunchedEffect
        if (!live) return@LaunchedEffect
        while (true) {
            try { friends = loadFriendsData(dev, friends.postAlerts) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
            delay(120_000)
        }
    }
    LaunchedEffect(chorus.device?.session, live, dmSpaces) {
        val dev = chorus.device ?: return@LaunchedEffect
        if (!live) return@LaunchedEffect
        try { directory = Spaces.list(dev) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
        for (id in dmSpaces) {
            try { privateCards = privateCards + Spaces.authorCards(dev, id) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
        }
    }
    val chatRows = remember(model, accountId, directory, privateCards) { chatListRows(model, null, accountId, directory, emptyMap(), privateCards) }
    val waiting = HomeDashboard.waiting(chatRows, { it.unread }, { it.muted }, { it.archived }, { it.latest?.occurredAt ?: 0L })
    val friendCards = remember(friends, now) { HomeDashboard.friends(friends.rows, now) }
    val feed = remember(friends) {
        FriendsFeed.shown(friends.pages.map { (id, posts) ->
            FriendsFeed.Source(id, friends.row(id)?.account?.shownName ?: "Someone", posts, false)
        }).take(3)
    }
    val dayStart = remember(now / 60_000) { Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis }
    val layerNames = FrontScene.layers(model).map { it.id to it.name }
    val today = remember(model.switches, model.posts, history, dayStart, layerNames) {
        HomeDashboard.today(if (named != null) history?.saved.orEmpty() else model.switches, model.posts, dayStart, layerNames,
            { e -> model.subject(e.subjectType, e.subjectId)?.name }, { ids -> ids.firstNotNullOfOrNull { model.member(it)?.shownName } })
    }
    val firstDay = HomeDashboard.firstDay(friends.follows.following.count { it.status != "declined" }, model.posts.size, self)
        .takeIf { person && friends.loaded }

    var switcher by remember { mutableStateOf(false) }
    var logging by rememberSaveable { mutableStateOf(false) }
    var pins by remember { mutableStateOf(false) }
    var placing by remember { mutableStateOf<Subject?>(null) }
    var stateFor by remember { mutableStateOf<Subject?>(null) }
    var readAs by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var following by remember { mutableStateOf(false) }
    // a new order shows from its top: the list would otherwise keep the old first section in view
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    var reordered by remember { mutableStateOf(false) }
    val shownIds = HomeDashboard.shown(sections)
    LaunchedEffect(shownIds) { if (reordered) { list.scrollToItem(0); reordered = false } }

    BackHandler(logging) { logging = false }
    if (logging && !person) { LogPastSwitch(chorus, model, bodyId, history) { logging = false }; return }
    if (switcher && !person) Switcher(chorus, model, if (named != null) bodyId else null) { switcher = false }
    if (pins) WidgetPinsSheet(model, accountId) { pins = false }
    placing?.let { s -> PlaceSheet(chorus, model, bodyId, s, since[s.id], onState = { stateFor = s; placing = null }) { placing = null } }
    stateFor?.let { s -> MemberStateSheet(chorus, model, s) { stateFor = null } }
    if (editing) HomeSectionsDrawer(sections, reader?.shownName?.takeIf { !person }, onDismiss = { editing = false }) { next ->
        actions.launch {
            try { reordered = true; chorus.create("pref.set", null, HomeDashboard.payload(readerKey, next)); error = null }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = e.message }
        }
    }
    if (following) FollowDrawer({ following = false }) { handle ->
        following = false
        actions.launch {
            try { PeopleApi.request(checkNotNull(chorus.device), handle); friends = loadFriendsData(checkNotNull(chorus.device), friends.postAlerts); error = null }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Couldn’t send the request." }
        }
    }
    MenuDrawer(readAs, { readAs = false }, "Reading as") {
        for (m in own) MenuRow(m.shownName, selected = m.id == reader?.id, leading = { ChatAvatar(m, 32.dp) }) { readAs = false; chosenReader = m.id }
    }

    fun quickTap(s: Subject) = actions.launch {
        try { Front.tap(chorus, s, Mode.Replace, if (named != null) bodyId else null); error = null }
        catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = e.message }
    }

    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(Modifier.fillMaxSize()) {
            HomeTitleRow(reader, listOfNotNull(if (person) self?.shownName else model.systemName?.takeIf { it.isNotBlank() },
                SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(now))).joinToString(" · "),
                onReader = if (person) null else ({ readAs = true }), onSearch = routes.onSearch, onSections = { editing = true })
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
                error?.let { item(key = "error") { Text(it, color = p.danger, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) } }
                if (firstDay?.welcome == true) item(key = "welcome") {
                    WelcomeCard(self?.shownName ?: "", model.accountHandle, chorus.device?.base, onFind = { following = true }) { text ->
                        ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
                            .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text), null))
                    }
                }
                for (id in shownIds) when (id) {
                    "write" -> if (firstDay?.welcome != true) item(key = "write") { WriteRow(self, routes.onWrite) }
                    "fronting" -> item(key = "fronting") {
                        FrontingNowCard(model, scene, since, now, onFace = { placing = it }, onSwitch = { switcher = true }, onLog = { logging = true })
                    }
                    "quick_switch" -> {
                        item(key = "quick_head") { HomeHeader("Quick switch", "Edit") { pins = true } }
                        item(key = "quick") {
                            QuickSwitchRow(model, own, WidgetPins.read(ctx, accountId), onTap = ::quickTap, onMore = { switcher = true })
                        }
                    }
                    "waiting" -> {
                        item(key = "waiting_head") { HomeHeader("Waiting for you", "Chat".takeIf { waiting.isNotEmpty() }) { routes.onChat(null, null) } }
                        if (waiting.isEmpty()) item(key = "waiting_none") { HomeQuiet(R.drawable.ic_message, "Nothing waiting") }
                        for (row in waiting) item(key = "w:${row.channel.id}") {
                            val context = row.space.name.takeIf { row.space.kind == "shared" && it != row.title }
                            Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Open ${row.title}") { routes.onChat(row.space.id, row.channel.id) }
                                .heightIn(min = 72.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                ChatRowBody(model, row, row.peer(model, reader?.id), false, row.title, context = context)
                            }
                            HorizontalDivider(Modifier.padding(start = 72.dp), color = p.line, thickness = .5.dp)
                        }
                    }
                    "friends" -> if (friends.loaded || friendCards.isNotEmpty()) {
                        item(key = "friends_head") { HomeHeader("Friends right now", "Friends".takeIf { friendCards.isNotEmpty() }) { routes.onFriends(null) } }
                        item(key = "friends") {
                            if (friendCards.isEmpty()) HomeEmptyCard(R.drawable.ic_users, "Nobody yet")
                            else LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(friendCards, key = { it.first.id }) { (row, card) ->
                                    FriendNowCard(row, card, now) { routes.onFriends(listOf("friend:${row.id}")) }
                                }
                            }
                        }
                    }
                    "today" -> {
                        item(key = "today_head") { HomeHeader("Today", "History") { routes.onHistory() } }
                        if (today.isEmpty()) item(key = "today_none") { HomeQuiet(R.drawable.ic_clock, "Nothing yet today") }
                        else item(key = "today") { TodayList(today) }
                    }
                    "new_from_friends" -> if (feed.isNotEmpty()) {
                        item(key = "feed_head") { HomeHeader("New from friends", "Journal") { routes.onJournal() } }
                        for ((name, post) in feed) item(key = "p:${post.id}") {
                            SharedPostPreview(post, name, chorus, model, onThread = { routes.onPost(it.id) }, onReply = { routes.onReply(it.id) },
                                onOpenPost = routes.onPost)
                            HorizontalDivider(color = p.line)
                        }
                        item(key = "caught_up") { CaughtUp() }
                    }
                }
                if (firstDay != null && (firstDay.post || firstDay.profile)) {
                    item(key = "try_head") { HomeHeader("Try", null) {} }
                    item(key = "try") {
                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (firstDay.post) TryRow(R.drawable.ic_journal, "Write your first post", "Friends can reply and react", routes.onWrite)
                            if (firstDay.profile && self != null) TryRow(R.drawable.ic_user, "Fill in your profile", "Pronouns, a picture, a few words") { routes.onProfile(self.id) }
                        }
                    }
                }
            }
        }
        undo?.let { u ->
            Row(Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth().background(p.surface2, RoundedCornerShape(12.dp))
                .padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(u.label, color = p.ink, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                Text("Undo", color = p.accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .clickable { actions.launch { try { Front.undo(chorus); error = null } catch (e: Exception) { error = e.message } } }.padding(12.dp))
            }
        }
    }
}

/** F0.1's title row: the reader's face (who's reading, for a system), Home, whose and which day. */
@Composable
private fun HomeTitleRow(reader: Member?, subtitle: String, onReader: (() -> Unit)?, onSearch: () -> Unit, onSections: () -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().background(p.surface).heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        reader?.let { r ->
            Box(Modifier.clip(CircleShape).then(if (onReader != null) Modifier.clickable(onClickLabel = "Read as another member", onClick = onReader) else Modifier)) {
                ChatAvatar(r, 32.dp, label = r.shownName)
            }
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text("Home", color = p.ink, fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        BarIcon(R.drawable.ic_sliders, "Home sections", p.ink, onClick = onSections)
        BarIcon(R.drawable.ic_search, "Search", p.ink, onClick = onSearch)
    }
}

/** A section's teal label, with a teal link at its right (F0.1 "Edit", "Chat", "Friends"). */
@Composable
private fun HomeHeader(title: String, link: String?, onLink: () -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = p.accent, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        link?.let { Text(it, color = p.accent, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onLink).padding(horizontal = 8.dp, vertical = 6.dp)) }
    }
}

@Composable
private fun homeCard(): Modifier {
    val p = LocalChorusPalette.current
    return Modifier.clip(RoundedCornerShape(16.dp)).background(p.surface).border(1.dp, p.line, RoundedCornerShape(16.dp))
}

/** One line in a quiet card (F0.6 "Nothing waiting"). */
@Composable
private fun HomeQuiet(icon: Int, text: String) {
    val p = LocalChorusPalette.current
    Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth().then(homeCard()).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Glyph(icon, p.ink3, 18.dp)
        Text(text, color = p.ink3, fontSize = 14.sp, lineHeight = 18.sp)
    }
}

/** An empty card with an icon over a line (F0.6 "Nobody yet"). */
@Composable
private fun HomeEmptyCard(icon: Int, text: String) {
    val p = LocalChorusPalette.current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth().then(homeCard()).padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Glyph(icon, p.ink3, 22.dp)
        Text(text, color = p.ink2, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** F0.1 (a): who's here in Body, ringed as their layer looks, since when, Switch and Log a past one. */
@Composable
private fun FrontingNowCard(model: Model, scene: FrontScene, since: Map<String, Long>, now: Long, onFace: (Subject) -> Unit,
    onSwitch: () -> Unit, onLog: () -> Unit) {
    val p = LocalChorusPalette.current
    val time = DateFormat.getTimeInstance(DateFormat.SHORT)
    val from = since.values.minOrNull() ?: model.since.takeIf { model.namedFronts == null }
    val here = scene.nested.flatMap { l -> l.entries.map { it to l } }
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(p.surface)
        .border(1.dp, p.line, RoundedCornerShape(22.dp)).padding(15.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${scene.innermost?.name?.takeIf { model.namedFronts != null } ?: "Fronting"} now", color = p.ink, fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            from?.let { Text("since ${time.format(Date(it))} · ${FrontingDisplay.duration(now - it)}", color = p.ink3, fontSize = 12.sp) }
        }
        if (here.isEmpty()) Text("No one fronting", color = p.ink3, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
        else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            for ((e, l) in here.take(12)) model.subject(e.subjectType, e.subjectId)?.let { s -> PresenceFace(s, l) { onFace(s) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HomeButton("Switch", R.drawable.ic_switch_arrows, p.accent, p.onAccent, Modifier.weight(1f), onSwitch)
            HomeButton("Log a past one", R.drawable.ic_history, p.surface2, p.ink, Modifier.weight(1f), onLog)
        }
    }
}

@Composable
private fun HomeButton(text: String, icon: Int, fill: Color, ink: Color, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(14.dp)).background(fill).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        Glyph(icon, ink, 16.dp)
        Text(text, color = ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** A 64 dp face ringed as its layer looks (solid, dashed, faint, plain) with the name under it. */
@Composable
private fun PresenceFace(s: Subject, layer: SceneLayer, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    val ink = layerInk(layer.style, parseHex(layer.color), p)
    Column(Modifier.width(64.dp).clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = s.name, onClick = onClick)
        .semantics { contentDescription = "${s.name}, ${layer.name}" }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
            Avatar(s.glyph, s.color, 54.dp, avatarBlob = s.avatarBlob, ring = false)
            val ring = if (layer.style == FrontingDisplay.Style.Plain) p.ink3.copy(alpha = .45f) else ink.ring
            Canvas(Modifier.size(64.dp)) {
                drawCircle(ring, radius = size.minDimension / 2 - 1.dp.toPx(), style = Stroke(2.dp.toPx(),
                    pathEffect = if (ink.dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null))
            }
        }
        Text(s.name, color = p.ink2, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp))
    }
}

/** F0.1 (b): one tap switches to someone; holding one, or Everyone, opens the whole switch sheet. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickSwitchRow(model: Model, own: List<Member>, pins: List<String>, onTap: (Subject) -> Unit, onMore: () -> Unit) {
    val p = LocalChorusPalette.current
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
        val fit = ((maxWidth + 6.dp) / 62.dp).toInt().coerceAtLeast(2) - 1
        val ids = HomeDashboard.quickSwitch(own.map { it.id }, pins, model.recents(40), fit)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (id in ids) model.subject("member", id)?.let { s ->
                QuickFace(s.name, { Avatar(s.glyph, s.color, 40.dp, avatarBlob = s.avatarBlob, ring = false) },
                    Modifier.combinedClickable(onClickLabel = "Switch to ${s.name}", onLongClickLabel = "Switch…", onLongClick = onMore) { onTap(s) })
            }
            QuickFace("Everyone", {
                Box(Modifier.size(40.dp).background(p.surface2, CircleShape), contentAlignment = Alignment.Center) { Glyph(R.drawable.ic_users, p.ink2, 20.dp) }
            }, Modifier.clickable(onClickLabel = "Switch…", onClick = onMore), muted = true)
        }
    }
}

@Composable
private fun QuickFace(name: String, face: @Composable () -> Unit, modifier: Modifier, muted: Boolean = false) {
    val p = LocalChorusPalette.current
    Column(Modifier.width(56.dp).clip(RoundedCornerShape(12.dp)).then(modifier).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        face()
        Text(name, color = if (muted) p.ink3 else p.ink2, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center)
    }
}

/** F0.1 (e), F0.2, F0.4: one followed account, as much as they share of who's here right now. */
@Composable
private fun FriendNowCard(row: FriendRow, card: FriendCard, now: Long, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    val quiet = card == FriendCard.NotSharing || card == FriendCard.Quiet
    Column(Modifier.width(164.dp).then(homeCard()).clickable(onClickLabel = "Open ${row.account.shownName}", onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccountAvatar(row.account, 24.dp)
            Text(row.account.shownName, color = if (quiet) p.ink2 else p.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when (card) {
            FriendCard.Around -> {
                val shown = row.view?.entries.orEmpty()
                FaceStack(shown.map { it.subject() }, max = 3, size = 32.dp)
                Text(Friends.nowLine(shown, 2), color = p.ink, fontSize = 13.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Friends.sinceLine(row.view, now)?.let { SmallLine(it) }
            }
            FriendCard.NotYet -> {
                Text("You’ll see who’s around after their next switch", color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2)
                row.following?.createdAt?.takeIf { it > 0 }?.let { SmallLine("Followed ${dayName(it, now)}") }
            }
            FriendCard.Posted -> row.latest?.let { Text(Friends.activity(it, compactAgo(it.occurredAt)), color = p.ink2, fontSize = 13.sp,
                lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            FriendCard.Quiet -> Text("Nobody’s around", color = p.ink3, fontSize = 13.sp, lineHeight = 16.sp)
            FriendCard.NotSharing -> Text("Doesn’t share fronting", color = p.ink3, fontSize = 13.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun SmallLine(text: String) {
    Text(text, color = LocalChorusPalette.current.ink3, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** F0.2 *Today*: time, an icon on the day's line, what happened. */
@Composable
private fun TodayList(events: List<TodayEvent>) {
    val p = LocalChorusPalette.current
    val time = DateFormat.getTimeInstance(DateFormat.SHORT)
    // F0.2's 38 dp column holds "15:12"; "10:33 AM" needs more
    val column = if (android.text.format.DateFormat.is24HourFormat(LocalContext.current)) 38.dp else 60.dp
    Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
        events.take(8).forEachIndexed { i, e ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(time.format(Date(e.at)), color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, modifier = Modifier.width(column).padding(top = 5.dp))
                Column(Modifier.width(26.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    val (icon, tint) = if (e.kind == "switch") R.drawable.ic_switch_arrows to p.accent else R.drawable.ic_journal to p.danger
                    Box(Modifier.size(26.dp).background(tint.copy(alpha = .16f), CircleShape), contentAlignment = Alignment.Center) { Glyph(icon, tint, 14.dp) }
                    if (i < events.take(8).lastIndex) Box(Modifier.width(2.dp).weight(1f).background(p.line))
                }
                Text(e.text, color = p.ink, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f).padding(top = 3.dp, bottom = 12.dp))
            }
        }
    }
}

/** F0.5: the end of the friends' posts. */
@Composable
private fun CaughtUp() {
    val p = LocalChorusPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(36.dp).background(p.accentSoft, CircleShape), contentAlignment = Alignment.Center) { Glyph(R.drawable.ic_check, p.accent, 18.dp) }
        Text("You’re all caught up", color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text("Older posts are in Journal.", color = p.ink3, fontSize = 13.sp)
    }
}

/** F0.4: "Write something, Ren…" opens the Journal composer. */
@Composable
private fun WriteRow(self: Member?, onWrite: () -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth().then(homeCard()).clickable(onClickLabel = "Write a post", onClick = onWrite)
        .heightIn(min = 52.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        self?.let { ChatAvatar(it, 32.dp) }
        Text("Write something${self?.shownName?.let { ", $it" } ?: ""}…", color = p.ink3, fontSize = 15.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Glyph(R.drawable.ic_image, p.ink3, 20.dp)
        Glyph(R.drawable.ic_poll, p.ink3, 20.dp)
    }
}

/** F0.6: a person's first day. */
@Composable
private fun WelcomeCard(name: String, handle: String?, base: String?, onFind: () -> Unit, onShare: (String) -> Unit) {
    val p = LocalChorusPalette.current
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(p.accentSoft)
        .padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (name.isBlank()) "Welcome" else "Welcome, $name", color = p.ink, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
        Text("Chorus is quieter with friends.", color = p.ink2, fontSize = 14.sp, lineHeight = 20.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton("Find someone", primary = true, icon = R.drawable.ic_user_plus, onClick = onFind)
            handle?.takeIf { it.isNotBlank() }?.let { h ->
                val host = base?.let { runCatching { java.net.URI(it).host }.getOrNull() }
                SmallButton("Share @$h", icon = R.drawable.ic_link) { onShare(listOfNotNull("@$h", host?.let { "on $it" }).joinToString(" ")) }
            }
        }
    }
}

@Composable
private fun TryRow(icon: Int, title: String, subtitle: String, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().then(homeCard()).clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Glyph(icon, p.accent, 20.dp)
        Column(Modifier.weight(1f)) {
            Text(title, color = p.ink, fontSize = 15.sp, lineHeight = 19.sp)
            Text(subtitle, color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Glyph(R.drawable.ic_chevron_right, p.ink3, 18.dp)
    }
}
