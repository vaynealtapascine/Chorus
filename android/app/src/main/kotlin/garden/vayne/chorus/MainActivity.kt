package garden.vayne.chorus

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import garden.vayne.chorus.data.Inbox
import garden.vayne.chorus.data.InboxRead
import garden.vayne.chorus.ui.InboxRoutes
import garden.vayne.chorus.ui.Notifications
import garden.vayne.chorus.ui.inboxReader
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.DisposableEffect
import garden.vayne.chorus.data.DeviceNavigation
import androidx.compose.ui.platform.LocalConfiguration
import garden.vayne.chorus.ui.AppIcon
import garden.vayne.chorus.ui.ChorusIcon
import garden.vayne.chorus.ui.MoreScreen
import garden.vayne.chorus.ui.FrontsScreen
import garden.vayne.chorus.ui.LayersDisplayScreen
import garden.vayne.chorus.ui.Switcher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Status
import garden.vayne.chorus.data.SyncWork
import garden.vayne.chorus.data.SearchDocument
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.ChatChannel
import garden.vayne.chorus.designsystem.ChorusTheme
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.ui.History
import garden.vayne.chorus.ui.Home
import garden.vayne.chorus.ui.Chat
import garden.vayne.chorus.ui.Members
import garden.vayne.chorus.ui.Onboarding
import garden.vayne.chorus.ui.FriendsScreen
import garden.vayne.chorus.ui.SettingsScreen
import garden.vayne.chorus.ui.ContentSearch
import garden.vayne.chorus.ui.Journal
import garden.vayne.chorus.ui.InsightsScreen

private data class OpenChatRequest(val id: Long, val spaceId: String?, val channelId: String)
/** A tapped post alert (D-106): the post, or (no post) Friends for a request or its answer. */
/** Somewhere a link or a notification opens: a post, a message, a friend's page or a tab ("notifications", "history"). */
private data class OpenPostRequest(val id: Long, val postId: String?, val messageId: String? = null,
    val friend: String? = null, val tab: String? = null)

class MainActivity : ComponentActivity() {
    companion object {
        const val ACTION_OPEN_CHAT = "garden.vayne.chorus.OPEN_CHAT"
        /** Open the switch sheet (the front widget's Switch and Handover, F3.8). */
        const val ACTION_SWITCH = "garden.vayne.chorus.SWITCH"
        const val EXTRA_HANDOVER = "garden.vayne.chorus.HANDOVER"
        const val EXTRA_OPEN_CHANNEL = "garden.vayne.chorus.OPEN_CHANNEL"
        const val EXTRA_OPEN_SPACE = "garden.vayne.chorus.OPEN_SPACE"
        const val EXTRA_OPEN_POST = "garden.vayne.chorus.OPEN_POST"
        const val EXTRA_OPEN_PEOPLE = "garden.vayne.chorus.OPEN_PEOPLE"
        /** A friend's page (their account id): a friend's switch, an answered alert request. */
        const val EXTRA_OPEN_FRIEND = "garden.vayne.chorus.OPEN_FRIEND"
        /** A destination by name: "notifications" or "history". */
        const val EXTRA_OPEN_TAB = "garden.vayne.chorus.OPEN_TAB"
    }

    private var inviteLink = mutableStateOf<String?>(null)
    private var sharedDraft = mutableStateOf<SharedDraft?>(null)
    private var nextShareId = 0L
    private var newEntryRequest = mutableStateOf<Long?>(null)
    private var nextEntryId = 0L
    private var openChatRequest = mutableStateOf<OpenChatRequest?>(null)
    private var nextOpenChatId = 0L
    private var openPostRequest = mutableStateOf<OpenPostRequest?>(null)
    /** A switch sheet asked for from outside: null none, else whether it is a handover. */
    private var switchRequest = mutableStateOf<Boolean?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        inviteLink.value = inviteFrom(intent)
        sharedDraft.value = SharedDraft.from(intent, ++nextShareId)
        if (intent.action == "garden.vayne.chorus.NEW_ENTRY") newEntryRequest.value = ++nextEntryId
        if (intent.action == ACTION_SWITCH) switchRequest.value = intent.getBooleanExtra(EXTRA_HANDOVER, false)
        openChatFrom(intent)?.let { openChatRequest.value = it }
        openPostFrom(intent)?.let { openPostRequest.value = it }
        val chorus = Chorus.get(this)
        SyncWork.schedulePeriodic(this)
        garden.vayne.chorus.data.NotifyCheck.start(this)
        // switch notifications (M8.1): ask once on Android 13+, then register with ntfy if present
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        garden.vayne.chorus.data.Push.ensure(this)
        garden.vayne.chorus.data.Updater.schedule(this)
        setContent { androidx.compose.runtime.CompositionLocalProvider(garden.vayne.chorus.ui.LocalChorusLinks provides ::openOwnLink) {
            InspectionOverrides(intent) { ChorusTheme { App(chorus, inviteLink.value, sharedDraft.value,
            onShareDismissed = { sharedDraft.value = null }, newEntryRequest = newEntryRequest.value,
            onNewEntryConsumed = { newEntryRequest.value = null },
            openChatRequest = openChatRequest.value,
            onOpenChatConsumed = { openChatRequest.value = null },
            openPostRequest = openPostRequest.value,
            onOpenPostConsumed = { openPostRequest.value = null },
            switchRequest = switchRequest.value, onSwitchConsumed = { switchRequest.value = null }) } } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        inviteFrom(intent)?.let { inviteLink.value = it }
        SharedDraft.from(intent, ++nextShareId)?.let { sharedDraft.value = it }
        if (intent.action == ACTION_SWITCH) switchRequest.value = intent.getBooleanExtra(EXTRA_HANDOVER, false)
        if (intent.action == "garden.vayne.chorus.NEW_ENTRY") {
            sharedDraft.value = null
            newEntryRequest.value = ++nextEntryId
        }
        openChatFrom(intent)?.let {
            sharedDraft.value = null
            openChatRequest.value = it
        }
        openPostFrom(intent)?.let {
            sharedDraft.value = null
            openPostRequest.value = it
        }
    }

    override fun onStart() {
        super.onStart()
        Chorus.get(this).setForeground(true)
        garden.vayne.chorus.data.Push.ensure(this)
        // every open looks for a newer build (CLIENTS.md §5a); the banner offers it
        garden.vayne.chorus.data.Updater.onOpen(this)
    }

    override fun onStop() {
        Chorus.get(this).setForeground(false)
        garden.vayne.chorus.data.Updater.onClose(this)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        recordEditorSavedState(outState)
    }

    private fun inviteFrom(i: Intent?): String? =
        i?.takeIf { it.action == Intent.ACTION_VIEW }?.data?.toString()?.takeIf { "/i/" in it }

    /** A tapped link to one of our posts or messages (D-109) opens here; anything else is the browser's. */
    private fun openOwnLink(href: String): Boolean {
        val base = Chorus.get(this).device?.base ?: return false
        val t = garden.vayne.chorus.data.ChorusLinks.parse(href, base) ?: return false
        openPostRequest.value = OpenPostRequest(++nextOpenChatId, t.id.takeIf { t.kind == "post" }, t.id.takeIf { t.kind == "message" })
        return true
    }

    private fun openPostFrom(i: Intent?): OpenPostRequest? {
        // a shared link opened from outside (D-109); the intent filter already names the server
        i?.takeIf { it.action == Intent.ACTION_VIEW }?.data?.let { uri ->
            garden.vayne.chorus.data.ChorusLinks.parse(uri.toString(), "${uri.scheme}://${uri.encodedAuthority}")?.let { t ->
                return OpenPostRequest(++nextOpenChatId, t.id.takeIf { t.kind == "post" }, t.id.takeIf { t.kind == "message" })
            }
        }
        val post = i?.getStringExtra(EXTRA_OPEN_POST)?.takeIf { it.isNotBlank() }
        val friend = i?.getStringExtra(EXTRA_OPEN_FRIEND)?.takeIf { it.isNotBlank() }
        val tab = i?.getStringExtra(EXTRA_OPEN_TAB)?.takeIf { it.isNotBlank() }
        if (post == null && friend == null && tab == null && i?.getBooleanExtra(EXTRA_OPEN_PEOPLE, false) != true) return null
        return OpenPostRequest(++nextOpenChatId, post, friend = friend, tab = tab)
    }

    private fun openChatFrom(i: Intent?): OpenChatRequest? {
        val channel = i?.getStringExtra(EXTRA_OPEN_CHANNEL)?.takeIf { it.isNotBlank() } ?: return null
        val space = i.getStringExtra(EXTRA_OPEN_SPACE)?.takeIf { it.isNotBlank() }
        return OpenChatRequest(++nextOpenChatId, space, channel)
    }
}

/** Destinations; [key] is the name saved in `chorus.navigation` (Fronting keeps its old "Home"). */
private enum class Tab(val label: String, val icon: AppIcon, val key: String) {
    Home("Home", AppIcon.Home, "Today"), Fronting("Fronting", AppIcon.Front, "Home"), Notifications("Notifications", AppIcon.Bell, "Notifications"),
    Chat("Chat", AppIcon.Chat, "Chat"), Journal("Journal", AppIcon.Journal, "Journal"), People("Friends", AppIcon.Friends, "People"),
    Members("Members", AppIcon.Members, "Members"), More("More", AppIcon.More, "More"), History("Front history", AppIcon.History, "History");
    companion object { fun of(key: String): Tab? = entries.firstOrNull { it.key == key } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun App(chorus: Chorus, invite: String?, sharedDraft: SharedDraft?,
    onShareDismissed: () -> Unit, newEntryRequest: Long?, onNewEntryConsumed: () -> Unit,
    openChatRequest: OpenChatRequest? = null, onOpenChatConsumed: () -> Unit = {},
    openPostRequest: OpenPostRequest? = null, onOpenPostConsumed: () -> Unit = {},
    switchRequest: Boolean? = null, onSwitchConsumed: () -> Unit = {}) {
    val p = LocalChorusPalette.current
    val context = LocalContext.current
    val navPrefs = remember { context.getSharedPreferences("chorus.navigation", 0) }
    val navAccount = chorus.device?.accountId.orEmpty()
    var navRevision by remember { mutableStateOf(0) }
    DisposableEffect(navPrefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> navRevision++ }
        navPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { navPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val motion = garden.vayne.chorus.ui.LocalChorusMotionOverride.current ?: android.animation.ValueAnimator.areAnimatorsEnabled()
    val expanded = LocalConfiguration.current.screenWidthDp >= 840
    val status by chorus.status.collectAsState()
    val model by chorus.model.collectAsState()
    // Chorus opens on Home (D-130)
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    val navigationTabs = remember(navRevision, navAccount, model.isPerson) {
        DeviceNavigation.resolve(navPrefs.getString(navAccount, null), model.isPerson).mapNotNull { Tab.of(it) } + Tab.More
    }
    val routedTab = if (model.isPerson && tab in listOf(Tab.Fronting, Tab.Members, Tab.History)) Tab.Journal else tab
    var chatSpace by rememberSaveable { mutableStateOf<String?>(null) }
    var chatChannel by rememberSaveable { mutableStateOf<String?>(null) }
    var chatSearchHit by remember { mutableStateOf<SearchDocument?>(null) }
    // a Friends page a notification opens (UI21): its route stack
    var friendsRoute by remember { mutableStateOf<List<String>?>(null) }
    // the Fronting bar's "+" opens Fronts on the new-front drawer
    var frontsNaming by remember { mutableStateOf(false) }
    // a link to a message (D-109): the chat opens at it
    var chatMessage by remember { mutableStateOf<String?>(null) }
    var chatStageCapture by remember { mutableStateOf(false) }
    var chatFocused by rememberSaveable { mutableStateOf(false) }
    var journalFocused by rememberSaveable { mutableStateOf(false) }
    var membersFocused by rememberSaveable { mutableStateOf(false) }
    var friendsFocused by rememberSaveable { mutableStateOf(false) }
    val destinationState = androidx.compose.runtime.key(chorus.device?.accountId) {
        androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    }
    var journalReplyPost by rememberSaveable { mutableStateOf<String?>(null) }
    var journalOpenPost by rememberSaveable { mutableStateOf<String?>(null) }
    var journalOpenProfile by rememberSaveable { mutableStateOf<String?>(null) }
    var chatShare by remember { mutableStateOf<SharedDraft?>(null) }
    var journalShare by remember { mutableStateOf<SharedDraft?>(null) }
    var journalNewEntry by remember { mutableStateOf<Long?>(null) }
    var sharedChannel by remember { mutableStateOf<String?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    // the settings pages open, outermost first: "Appearance>Advanced" (F10.1, F10.2)
    var settingsPath by rememberSaveable { mutableStateOf("") }
    fun openSettings(page: garden.vayne.chorus.ui.SettingsPage) { settingsPath = page.name; settingsOpen = true }
    var editingBar by remember { mutableStateOf(false) }
    var quickSwitchOpen by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchSection by rememberSaveable { mutableStateOf("Messages") }
    var insightsOpen by rememberSaveable { mutableStateOf(false) }
    var frontsOpen by rememberSaveable { mutableStateOf(false) }
    // Layers & display for one front (F3.9); null when closed
    var layersFront by rememberSaveable { mutableStateOf<String?>(null) }
    // the front Front history opens on (the one Fronting was showing)
    var historyFront by rememberSaveable { mutableStateOf<String?>(null) }
    var historyReview by rememberSaveable { mutableStateOf(false) }
    var lastAccount by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(chorus.device?.accountId, status) {
        if (status == Status.Loading) return@LaunchedEffect
        val account = chorus.device?.accountId
        if (lastAccount != null && lastAccount != account) {
            chatSearchHit = null
            chatStageCapture = false
            chatSpace = null; chatChannel = null
            journalReplyPost = null; journalOpenPost = null; journalOpenProfile = null
            chatShare = null; journalShare = null; journalNewEntry = null; sharedChannel = null
            onShareDismissed()
            searchOpen = false; settingsOpen = false; insightsOpen = false; frontsOpen = false; layersFront = null
        }
        lastAccount = account
    }
    LaunchedEffect(newEntryRequest, status) {
        if (newEntryRequest == null || status == Status.Loading || status == Status.NoDevice || status == Status.SignedOut || status == Status.StorageError) return@LaunchedEffect
        journalNewEntry = newEntryRequest
        journalReplyPost = null; journalOpenPost = null
        searchOpen = false; settingsOpen = false; insightsOpen = false; frontsOpen = false; layersFront = null
        tab = Tab.Journal
        onNewEntryConsumed()
    }
    LaunchedEffect(openChatRequest?.id, status) {
        val req = openChatRequest ?: return@LaunchedEffect
        if (status == Status.Loading || status == Status.NoDevice || status == Status.SignedOut || status == Status.StorageError) return@LaunchedEffect
        chatSpace = req.spaceId ?: model.channels.find { it.id == req.channelId }?.spaceId
        chatChannel = req.channelId
        chatSearchHit = null
        searchOpen = false; settingsOpen = false; insightsOpen = false; frontsOpen = false; layersFront = null
        tab = Tab.Chat
        onOpenChatConsumed()
    }
    val linkContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(openPostRequest?.id, status) {
        val req = openPostRequest ?: return@LaunchedEffect
        if (status == Status.Loading || status == Status.NoDevice || status == Status.SignedOut || status == Status.StorageError) return@LaunchedEffect
        searchOpen = false; settingsOpen = false; insightsOpen = false; frontsOpen = false; layersFront = null
        if (req.messageId != null) {
            // only where this reader may see it now (its chat shown, its audience including who's here)
            val m = garden.vayne.chorus.data.ChatOrganize.actionMessage(model, req.messageId, null, chorus.device?.accountId.orEmpty())
            if (m == null) android.widget.Toast.makeText(linkContext, "That message isn't available here.", android.widget.Toast.LENGTH_SHORT).show()
            else {
                chatSpace = model.channels.find { it.id == m.channelId }?.spaceId; chatChannel = m.channelId
                chatMessage = m.id; chatSearchHit = null
                tab = Tab.Chat
            }
        } else if (req.postId != null) {
            journalReplyPost = null; journalOpenPost = req.postId
            tab = Tab.Journal
        } else if (req.friend != null) { friendsRoute = listOf("friend:${req.friend}"); tab = Tab.People }
        else if (req.tab == "notifications") tab = Tab.Notifications
        else if (req.tab == "history") tab = Tab.History
        else tab = Tab.People
        onOpenPostConsumed()
    }
    BackHandler(sharedDraft != null || settingsOpen || searchOpen || insightsOpen || frontsOpen || layersFront != null) {
        if (sharedDraft != null) onShareDismissed()
        else if (searchOpen) searchOpen = false else if (insightsOpen) insightsOpen = false else if (layersFront != null) layersFront = null
        else if (frontsOpen) frontsOpen = false
        else if (settingsPath.contains('>')) settingsPath = settingsPath.substringBeforeLast('>') else settingsOpen = false
    }
    var handoverOpen by remember { mutableStateOf(false) }
    LaunchedEffect(switchRequest, status) {
        val handover = switchRequest ?: return@LaunchedEffect
        if (status == Status.Loading || status == Status.NoDevice || status == Status.SignedOut || status == Status.StorageError) return@LaunchedEffect
        tab = Tab.Fronting; handoverOpen = handover; quickSwitchOpen = true
        onSwitchConsumed()
    }
    if (editingBar) garden.vayne.chorus.ui.BottomBarDrawer(
        DeviceNavigation.resolve(navPrefs.getString(navAccount, null), model.isPerson), model.isPerson, { editingBar = false }) { choices ->
        navPrefs.edit().putString(navAccount, choices.joinToString(",")).apply()
    }
    if (quickSwitchOpen && !model.isPerson) Switcher(chorus, model, handover = handoverOpen) { quickSwitchOpen = false; handoverOpen = false }
    BackHandler(sharedDraft == null && !quickSwitchOpen && !settingsOpen && !searchOpen && !insightsOpen && !frontsOpen && layersFront == null && routedTab != Tab.Home) {
        // Front history is a Fronting page (F3.7): back returns there when Fronting is on the bar; a
        // page opened from More returns to More; any destination on the bar returns to Home (D-130)
        tab = if (routedTab == Tab.History && Tab.Fronting in navigationTabs) Tab.Fronting
            else if (routedTab !in navigationTabs) Tab.More else Tab.Home
    }

    // a new system's setup (F1.2–F1.4) comes before everything else, once the replica is open
    var setupName by remember(navAccount) { mutableStateOf(garden.vayne.chorus.data.Setup.pending(context, navAccount)) }
    if (setupName != null && navAccount.isNotEmpty() && !model.isPerson && status != Status.Loading && status != Status.NoDevice &&
        status != Status.SignedOut && status != Status.StorageError) {
        garden.vayne.chorus.ui.SystemSetup(chorus, model, setupName.orEmpty()) {
            garden.vayne.chorus.data.Setup.done(context, navAccount); setupName = null
        }
        return
    }
    when (status) {
        Status.Loading -> Box(Modifier.fillMaxSize().background(p.bg))
        // signed out from another device: onboarding, as a phone with no device (D-153)
        Status.NoDevice, Status.SignedOut -> Onboarding(chorus, invite)
        Status.StorageError -> Box(Modifier.fillMaxSize().background(p.bg).padding(24.dp), contentAlignment = Alignment.Center) {
            Text("The local replica could not be opened or saved. Keep this app's data intact and check the device storage.", color = p.danger)
        }
        else -> Row(Modifier.fillMaxSize().background(p.bg)) {
            val person = model.isPerson
            val activeTab = routedTab
            val destinations = navigationTabs
            // the Notifications badge: unread for whoever is reading (D-127); checked every minute while live
            val inbox by Inbox.state.collectAsState()
            LaunchedEffect(chorus.device?.session, status == Status.Live) {
                val dev = chorus.device ?: return@LaunchedEffect
                if (status != Status.Live) return@LaunchedEffect
                while (true) {
                    try { Inbox.refresh(dev, chorus.model.value) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
                    kotlinx.coroutines.delay(60_000)
                }
            }
            val unread = inbox?.takeIf { it.first == chorus.device?.accountId }?.second?.let { items ->
                Inbox.unread(items, inboxReader(model, null)?.let { model.accountPrefs.inboxRead[it.id] } ?: InboxRead(), model.accountPrefs.inboxShows)
            } ?: 0
            // the unread widget (F3.8) shows the same number for the same reader
            val unreadReader = inboxReader(model, null)?.id
            LaunchedEffect(unread, unreadReader, inbox != null) { if (inbox != null) garden.vayne.chorus.widget.UnreadWidget.refreshAll(context, model) }
            @Composable fun TabIcon(t: Tab) {
                if (t == Tab.Notifications && unread > 0) BadgedBox(badge = {
                    Badge(containerColor = p.sun, contentColor = p.onSun) { Text(if (unread > 99) "99+" else unread.toString()) }
                }) { ChorusIcon(t.icon, "${t.label}, $unread unread") } else ChorusIcon(t.icon)
            }
            fun select(t: Tab) { tab = t; settingsOpen = false; searchOpen = false; insightsOpen = false; frontsOpen = false; layersFront = null }
            if (expanded && !chatStageCapture) NavigationRail(containerColor = p.surface) {
                destinations.forEach { t -> NavigationRailItem(selected = t == activeTab || (t == Tab.More && activeTab !in destinations && activeTab != Tab.Home),
                    onClick = { select(t) }, icon = { TabIcon(t) }, label = { Text(t.label) }) }
            }
            Column(Modifier.weight(1f).navigationBarsPadding()) {
            // Fronting, its history and its subpages draw their own Figma bars (F3.1, F3.4, F3.7, F3.9)
            val frontingChrome = !person && !searchOpen && !settingsOpen && !insightsOpen && sharedDraft == null &&
                (frontsOpen || layersFront != null || activeTab == Tab.Fronting || activeTab == Tab.History)
            // search draws its own bar too: the field is the top bar (F5.9)
            val chatOwnChrome = frontingChrome || searchOpen && !settingsOpen && !insightsOpen && sharedDraft == null || (settingsOpen || insightsOpen) && !searchOpen && sharedDraft == null ||
                activeTab in listOf(Tab.Home, Tab.Chat, Tab.Journal, Tab.Notifications, Tab.People, Tab.Members, Tab.More) && !searchOpen && !settingsOpen && !insightsOpen && !frontsOpen && sharedDraft == null
            // the share chooser draws its own bar too
            if ((chatOwnChrome || sharedDraft != null) && !chatStageCapture) Box(Modifier.fillMaxWidth().background(p.surface).statusBarsPadding())
            if (!chatStageCapture && !chatOwnChrome && sharedDraft == null) Row(
                Modifier.fillMaxWidth().background(p.surface).statusBarsPadding().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // the connection banner below says when changes are waiting; a live app says nothing
                Text(if (searchOpen) "Search" else if (settingsOpen) "Settings" else if (insightsOpen) "Insights" else if (frontsOpen) "Fronts" else activeTab.label,
                    fontSize = 20.sp, fontWeight = FontWeight.Bold, color = p.ink, modifier = Modifier.weight(1f))
                IconButton(onClick = { searchSection=if(activeTab==Tab.Journal) "Posts" else "Messages"; searchOpen = !searchOpen; settingsOpen = false; insightsOpen = false; frontsOpen = false; layersFront = null }) { ChorusIcon(if (searchOpen) AppIcon.Close else AppIcon.Search, if (searchOpen) "Close search" else "Search") }
                IconButton(onClick = { settingsOpen = !settingsOpen; searchOpen = false; insightsOpen = false; frontsOpen = false; layersFront = null }) { ChorusIcon(if (settingsOpen) AppIcon.Close else AppIcon.Settings, if (settingsOpen) "Close settings" else "Settings") }
            }
            // changes waiting on this phone, on every screen (F2.5, F10.3)
            if (!chatStageCapture) garden.vayne.chorus.ui.ConnectionBanner(chorus)
            if (!chatStageCapture) garden.vayne.chorus.ui.UpdateBanner()
            Box(Modifier.weight(1f)) {
                if (sharedDraft != null) ShareChooser(sharedDraft, model, chorus.device?.accountId,
                    onCancel = onShareDismissed,
                    onPost = {
                        journalShare = sharedDraft
                        journalReplyPost = null; journalOpenPost = null
                        tab = Tab.Journal
                        searchOpen = false; settingsOpen = false; insightsOpen = false; frontsOpen = false; layersFront = null
                        onShareDismissed()
                    },
                    onChannel = { selected ->
                        chatShare = sharedDraft
                        sharedChannel = selected.id
                        chatSpace = selected.spaceId; chatChannel = selected.id; chatSearchHit = null
                        tab = Tab.Chat
                        searchOpen = false; settingsOpen = false; insightsOpen = false; frontsOpen = false; layersFront = null
                        onShareDismissed()
                    })
                else if (searchOpen) ContentSearch(chorus, model, status == Status.Live,
                    onClose = { searchOpen = false }, initialSection = searchSection,
                    onOpenMessage = { hit ->
                        val channel = model.channels.find { it.id == hit.channelId }
                        if (channel != null) {
                            chatSpace = channel.spaceId; chatChannel = channel.id; chatSearchHit = null; chatMessage = hit.id
                            tab = Tab.Chat; searchOpen = false
                        }
                    },
                    onOpenPost = { id ->
                        journalReplyPost = null; journalOpenPost = id; tab = Tab.Journal; searchOpen = false
                    },
                    onOpenChat = { id ->
                        chatSpace = model.channels.find { it.id == id }?.spaceId; chatChannel = id; chatSearchHit = null; chatMessage = null
                        tab = Tab.Chat; searchOpen = false
                    })
                else if (insightsOpen) InsightsScreen(chorus, model) { insightsOpen = false }
                else if (layersFront != null && !model.isPerson) LayersDisplayScreen(chorus, model, layersFront!!) { layersFront = null }
                else if (frontsOpen && !model.isPerson) FrontsScreen(chorus, model, frontsNaming, { frontsNaming = false }) { frontsOpen = false }
                else if (settingsOpen) {
                    val page = runCatching { garden.vayne.chorus.ui.SettingsPage.valueOf(settingsPath.substringAfterLast('>')) }
                        .getOrDefault(garden.vayne.chorus.ui.SettingsPage.Appearance)
                    SettingsScreen(chorus, model, page, onOpen = { settingsPath = "$settingsPath>${it.name}" },
                        onBack = { if (settingsPath.contains('>')) settingsPath = settingsPath.substringBeforeLast('>') else settingsOpen = false },
                        routes = garden.vayne.chorus.ui.SettingsRoutes(onFriends = { route -> friendsRoute = route; settingsOpen = false; tab = Tab.People }))
                }
                else androidx.compose.animation.Crossfade(targetState = activeTab,
                    animationSpec = androidx.compose.animation.core.tween(if (motion) 120 else 0), label = "Main destination") { shownTab ->
                destinationState.SaveableStateProvider(shownTab.name) {
                when (shownTab) {
                    Tab.Home -> garden.vayne.chorus.ui.HomeScreen(chorus, model, garden.vayne.chorus.ui.HomeRoutes(
                        onSearch = { searchSection = "Messages"; searchOpen = true },
                        onChat = { space, channel ->
                            if (channel != null) { chatSpace = space; chatChannel = channel; chatMessage = null; chatSearchHit = null }
                            tab = Tab.Chat
                        },
                        onFriends = { route -> friendsRoute = route; tab = Tab.People },
                        onHistory = { historyFront = null; historyReview = false; tab = Tab.History },
                        onJournal = { tab = Tab.Journal },
                        onPost = { id -> journalReplyPost = null; journalOpenPost = id; tab = Tab.Journal },
                        onReply = { id -> journalReplyPost = id; tab = Tab.Journal },
                        onWrite = { journalReplyPost = null; journalOpenPost = null; journalNewEntry = System.nanoTime(); tab = Tab.Journal },
                        onProfile = { id -> journalOpenProfile = id; journalOpenPost = null; tab = Tab.Journal }))
                    Tab.Fronting -> Home(chorus, model, onFronts = { frontsOpen = true }, onHistory = { historyFront = it; historyReview = false; tab = Tab.History },
                        onReview = { historyFront = it; historyReview = true; tab = Tab.History },
                        onLayers = { layersFront = it }, onNewFront = { frontsNaming = true; frontsOpen = true })
                    Tab.Notifications -> Notifications(chorus, model, InboxRoutes(
                        onPost = { id -> journalReplyPost = null; journalOpenPost = id; tab = Tab.Journal },
                        onChat = { channel, message ->
                            chatSpace = model.channels.find { it.id == channel }?.spaceId; chatChannel = channel
                            chatMessage = message; chatSearchHit = null; tab = Tab.Chat
                        },
                        onFriends = { route -> friendsRoute = route; tab = Tab.People },
                        onHistory = { historyReview = false; tab = if (person) Tab.Journal else Tab.History },
                        onReview = { historyFront = null; historyReview = true; tab = if (person) Tab.Journal else Tab.History },
                        onSettings = { openSettings(garden.vayne.chorus.ui.SettingsPage.Notifications) }))
                    Tab.Chat -> Chat(chorus, model, chatSpace, chatChannel, chatSearchHit,
                        onStageCapture = { chatStageCapture = it }, onDismissSearchHit = { chatSearchHit = null },
                        sharedDraft = chatShare, sharedChannel = sharedChannel,
                        onShareConsumed = { chatShare = null; sharedChannel = null }, onSearch = { searchSection="Messages"; searchOpen = true },
                        active = shownTab == activeTab, onFocusedRoute = { chatFocused = it },
                        onOpenConsumed = { chatSpace = null; chatChannel = null; chatMessage = null },
                        requestedMessage = chatMessage,
                        onOpenProfile = { journalOpenProfile = it; journalOpenPost = null; tab = Tab.Journal },
                        onFriends = { tab = Tab.People })
                    Tab.Journal -> Journal(chorus, model, journalReplyPost,
                        onExternalReplyConsumed = { journalReplyPost = null },
                        externalOpenPost = journalOpenPost,
                        onExternalOpenConsumed = { journalOpenPost = null },
                        externalOpenProfile = journalOpenProfile, onExternalProfileConsumed = { journalOpenProfile = null },
                        sharedDraft = journalShare, onShareConsumed = { journalShare = null },
                        externalNewEntry = journalNewEntry, onExternalNewEntryConsumed = { journalNewEntry = null },
                        active = shownTab == activeTab, onFocusedRoute = { journalFocused = it },
                        onSearch = { searchSection=it; searchOpen=true },
                        onMessage = { space, channel -> chatSpace = space; chatChannel = channel; chatMessage = null; chatSearchHit = null; tab = Tab.Chat },
                        onStageCapture = { chatStageCapture = it })
                    Tab.People -> FriendsScreen(chorus, model,
                        onOpenChat = { spaceId -> chatSpace = spaceId; chatChannel = null; chatSearchHit = null; tab = Tab.Chat },
                        onReplyPost = { postId -> journalReplyPost = postId; tab = Tab.Journal },
                        onFocusedRoute = { friendsFocused = it },
                        openRoute = friendsRoute, onRouteConsumed = { friendsRoute = null })
                    Tab.Members -> Members(chorus, model, onJournal = { member, post ->
                        journalOpenProfile = member; journalOpenPost = post; tab = Tab.Journal
                    }, onMessage = { space, channel -> chatSpace = space; chatChannel = channel; chatMessage = null; chatSearchHit = null; tab = Tab.Chat }, active = shownTab == activeTab, onFocusedRoute = { membersFocused = it })
                    Tab.History -> History(chorus, model, historyFront, historyReview, onBack = { historyReview = false; tab = Tab.Fronting })
                    Tab.More -> MoreScreen(chorus, model, destinations.filter { it != Tab.More }.map { it.key }, unread, garden.vayne.chorus.ui.MoreRoutes(
                        go = { key -> Tab.of(key)?.let { tab = it } }, onInsights = { insightsOpen = true }, onSettings = { openSettings(it) },
                        onSearch = { searchSection = "Messages"; searchOpen = true }, onEditBar = { editingBar = true }))
                }
                }
                }
                // "Catching its breath": the server asked us to slow down (F10.7)
                if (!chatStageCapture) garden.vayne.chorus.ui.NoticeToast(Modifier.align(Alignment.BottomCenter).padding(16.dp))
            }
            // The bar and viewport resize together during push/pop; focused routes own their
            // chrome. The shell applies the system navigation inset exactly once.
            val focused = searchOpen && !settingsOpen && !insightsOpen ||
                (frontsOpen || layersFront != null) && !searchOpen && !settingsOpen && !insightsOpen ||
                !searchOpen && !settingsOpen && !insightsOpen && !frontsOpen &&
                ((activeTab == Tab.Chat && chatFocused) || (activeTab == Tab.Journal && journalFocused) || (activeTab == Tab.Members && membersFocused) || (activeTab == Tab.People && friendsFocused))
            androidx.compose.animation.AnimatedVisibility(
                visible = !expanded && sharedDraft == null && !chatStageCapture && !WindowInsets.isImeVisible && !focused,
                enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(if (motion) 220 else 0)) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(if (motion) 180 else 0)),
                exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(if (motion) 220 else 0)) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(if (motion) 100 else 0))) {
            NavigationBar(
                containerColor = p.surface, tonalElevation = 0.dp, windowInsets = WindowInsets(0, 0, 0, 0),
            ) {
                destinations.forEach { t -> NavigationBarItem(
                    // Front history belongs to Fronting (F3.7) when Fronting is on the bar
                    selected = (t == activeTab || (t == Tab.Fronting && activeTab == Tab.History) ||
                        (t == Tab.More && activeTab !in destinations && activeTab != Tab.Home && !(activeTab == Tab.History && Tab.Fronting in destinations))) &&
                        !settingsOpen && !searchOpen && !insightsOpen && !frontsOpen ||
                        // settings and insights are More's pages (F10.1)
                        t == Tab.More && (settingsOpen || insightsOpen) && !searchOpen,
                    onClick = { select(t) }, icon = { TabIcon(t) }, label = {
                        // "Notifications" is the longest label: let it run into the item's padding rather than cut it
                        Text(t.label, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1, softWrap = false,
                            overflow = TextOverflow.Visible, textAlign = TextAlign.Center)
                    },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = p.accent, selectedTextColor = p.accent,
                        indicatorColor = p.accentSoft, unselectedIconColor = p.ink3, unselectedTextColor = p.ink3),
                ) }
            }
            }
            }

        }
    }
}

@Composable
private fun ShareChooser(draft: SharedDraft, model: Model, accountId: String?, onCancel: () -> Unit,
    onPost: () -> Unit, onChannel: (ChatChannel) -> Unit) {
    val p = LocalChorusPalette.current
    @Composable fun Target(label: String, sub: String?, icon: Int, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(36.dp).background(p.accentSoft, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                garden.vayne.chorus.ui.Glyph(icon, p.accent, 18.dp)
            }
            Column(Modifier.weight(1f)) {
                Text(label, color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                sub?.let { Text(it, color = p.ink3, fontSize = 13.sp, maxLines = 1) }
            }
        }
    }
    Column(Modifier.fillMaxSize().background(p.bg)) {
        // F5.20's forward list: where this goes, no sentence
        garden.vayne.chorus.ui.SubpageBar("Share into Chorus", listOfNotNull("Text".takeIf { draft.text.isNotBlank() },
            "${draft.uris.size} file${if (draft.uris.size == 1) "" else "s"}".takeIf { draft.uris.isNotEmpty() }).joinToString(" · ").ifEmpty { null }, onCancel)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (model.active.any { it.createdByAccountId == null || it.createdByAccountId == accountId }) item {
                garden.vayne.chorus.ui.FormCard { Target("Journal", "Post or entry", garden.vayne.chorus.designsystem.R.drawable.ic_book, onPost) }
            }
            if (model.channels.isNotEmpty()) item {
                garden.vayne.chorus.ui.FormCard {
                    model.channels.forEachIndexed { i, channel ->
                        if (i > 0) garden.vayne.chorus.ui.FormDivider()
                        Target("#${channel.name}", model.spaces.find { it.id == channel.spaceId }?.name ?: "Chat", garden.vayne.chorus.designsystem.R.drawable.ic_hash) { onChannel(channel) }
                    }
                }
            }
            if (model.active.isEmpty() && model.channels.isEmpty()) item {
                garden.vayne.chorus.ui.EmptyState(garden.vayne.chorus.designsystem.R.drawable.ic_share, "Nothing to share into")
            }
        }
    }
}
