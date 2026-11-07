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
import androidx.compose.material3.TextButton
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
import garden.vayne.chorus.ui.DeviceLink
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
private data class OpenPostRequest(val id: Long, val postId: String?, val messageId: String? = null)

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
        if (post == null && i?.getBooleanExtra(EXTRA_OPEN_PEOPLE, false) != true) return null
        return OpenPostRequest(++nextOpenChatId, post)
    }

    private fun openChatFrom(i: Intent?): OpenChatRequest? {
        val channel = i?.getStringExtra(EXTRA_OPEN_CHANNEL)?.takeIf { it.isNotBlank() } ?: return null
        val space = i.getStringExtra(EXTRA_OPEN_SPACE)?.takeIf { it.isNotBlank() }
        return OpenChatRequest(++nextOpenChatId, space, channel)
    }
}

private enum class Tab(val label: String, val icon: AppIcon) { Home("Fronting", AppIcon.Front), Notifications("Notifications", AppIcon.Bell), Chat("Chat", AppIcon.Chat), Journal("Journal", AppIcon.Journal), People("Friends", AppIcon.Friends), Members("Members", AppIcon.Members), More("More", AppIcon.More), History("Front history", AppIcon.History) }

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
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    val navigationTabs = remember(navRevision, navAccount, model.isPerson) {
        DeviceNavigation.resolve(navPrefs.getString(navAccount, null), model.isPerson).map { Tab.valueOf(it) } + Tab.More
    }
    val routedTab = if (model.isPerson && tab in listOf(Tab.Home, Tab.Members, Tab.History)) Tab.Journal else tab
    var chatSpace by rememberSaveable { mutableStateOf<String?>(null) }
    var chatChannel by rememberSaveable { mutableStateOf<String?>(null) }
    var chatSearchHit by remember { mutableStateOf<SearchDocument?>(null) }
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
    var linking by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var quickSwitchOpen by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchSection by rememberSaveable { mutableStateOf("Messages") }
    var insightsOpen by rememberSaveable { mutableStateOf(false) }
    var frontsOpen by rememberSaveable { mutableStateOf(false) }
    // Layers & display for one front (F3.9); null when closed
    var layersFront by rememberSaveable { mutableStateOf<String?>(null) }
    // the front Front history opens on (the one Fronting was showing)
    var historyFront by rememberSaveable { mutableStateOf<String?>(null) }
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
        if (newEntryRequest == null || status == Status.Loading || status == Status.NoDevice || status == Status.StorageError) return@LaunchedEffect
        journalNewEntry = newEntryRequest
        journalReplyPost = null; journalOpenPost = null
        searchOpen = false; settingsOpen = false; insightsOpen = false; frontsOpen = false; layersFront = null
        tab = Tab.Journal
        onNewEntryConsumed()
    }
    LaunchedEffect(openChatRequest?.id, status) {
        val req = openChatRequest ?: return@LaunchedEffect
        if (status == Status.Loading || status == Status.NoDevice || status == Status.StorageError) return@LaunchedEffect
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
        if (status == Status.Loading || status == Status.NoDevice || status == Status.StorageError) return@LaunchedEffect
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
        } else tab = Tab.People
        onOpenPostConsumed()
    }
    BackHandler(sharedDraft != null || settingsOpen || searchOpen || insightsOpen || frontsOpen || layersFront != null) {
        if (sharedDraft != null) onShareDismissed()
        else if (searchOpen) searchOpen = false else if (insightsOpen) insightsOpen = false else if (layersFront != null) layersFront = null
        else if (frontsOpen) frontsOpen = false else settingsOpen = false
    }
    if (linking && status != Status.NoDevice && status != Status.Loading) DeviceLink(chorus) { linking = false }
    var handoverOpen by remember { mutableStateOf(false) }
    LaunchedEffect(switchRequest, status) {
        val handover = switchRequest ?: return@LaunchedEffect
        if (status == Status.Loading || status == Status.NoDevice || status == Status.StorageError) return@LaunchedEffect
        tab = Tab.Home; handoverOpen = handover; quickSwitchOpen = true
        onSwitchConsumed()
    }
    if (quickSwitchOpen && !model.isPerson) Switcher(chorus, model, handover = handoverOpen) { quickSwitchOpen = false; handoverOpen = false }
    BackHandler(sharedDraft == null && !linking && !quickSwitchOpen && !settingsOpen && !searchOpen && !insightsOpen && !frontsOpen && layersFront == null && routedTab !in navigationTabs) {
        // Front history is a Fronting page (F3.7): back returns there when Fronting is on the bar
        tab = if (routedTab == Tab.History && Tab.Home in navigationTabs) Tab.Home else Tab.More
    }

    when (status) {
        Status.Loading -> Box(Modifier.fillMaxSize().background(p.bg))
        Status.NoDevice -> Onboarding(chorus, invite)
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
            @Composable fun TabIcon(t: Tab) {
                if (t == Tab.Notifications && unread > 0) BadgedBox(badge = {
                    Badge(containerColor = p.sun, contentColor = p.onSun) { Text(if (unread > 99) "99+" else unread.toString()) }
                }) { ChorusIcon(t.icon, "${t.label}, $unread unread") } else ChorusIcon(t.icon)
            }
            fun select(t: Tab) { tab = t; settingsOpen = false; searchOpen = false; insightsOpen = false; frontsOpen = false; layersFront = null }
            if (expanded && !chatStageCapture) NavigationRail(containerColor = p.surface) {
                destinations.forEach { t -> NavigationRailItem(selected = t == activeTab || (t == Tab.More && activeTab !in destinations),
                    onClick = { select(t) }, icon = { TabIcon(t) }, label = { Text(t.label) }) }
            }
            Column(Modifier.weight(1f).navigationBarsPadding()) {
            // Fronting, its history and its subpages draw their own Figma bars (F3.1, F3.4, F3.7, F3.9)
            val frontingChrome = !person && !searchOpen && !settingsOpen && !insightsOpen && sharedDraft == null &&
                (frontsOpen || layersFront != null || activeTab == Tab.Home || activeTab == Tab.History)
            val chatOwnChrome = frontingChrome || (activeTab in listOf(Tab.Chat, Tab.Journal, Tab.Notifications, Tab.People) || (activeTab == Tab.Members && membersFocused)) && !searchOpen && !settingsOpen && !insightsOpen && !frontsOpen && sharedDraft == null
            if (chatOwnChrome && !chatStageCapture) Box(Modifier.fillMaxWidth().background(p.surface).statusBarsPadding())
            if (!chatStageCapture && !chatOwnChrome) Row(
                Modifier.fillMaxWidth().background(p.surface).statusBarsPadding().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(if (searchOpen) "Search" else if (settingsOpen) "Settings" else if (insightsOpen) "Insights" else if (frontsOpen) "Fronts" else activeTab.label,
                        fontSize = 20.sp, fontWeight = FontWeight.Bold, color = p.ink)
                    Text(if (status == Status.Live) "Chorus · synced" else if (status == Status.Connecting) "Connecting…" else "Offline · saved on this device",
                        fontSize = 12.sp, color = p.ink3)
                }
                IconButton(onClick = { searchSection=if(activeTab==Tab.Journal) "Posts" else "Messages"; searchOpen = !searchOpen; settingsOpen = false; insightsOpen = false; frontsOpen = false; layersFront = null }) { ChorusIcon(if (searchOpen) AppIcon.Close else AppIcon.Search, if (searchOpen) "Close search" else "Search") }
                if (!person) IconButton(onClick = { quickSwitchOpen = true }) { ChorusIcon(AppIcon.Swap, "Open switcher") }
                IconButton(onClick = { settingsOpen = !settingsOpen; searchOpen = false; insightsOpen = false; frontsOpen = false; layersFront = null }) { ChorusIcon(if (settingsOpen) AppIcon.Close else AppIcon.Settings, if (settingsOpen) "Close settings" else "Settings") }
            }
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
                    })
                else if (insightsOpen) InsightsScreen(chorus, model) { insightsOpen = false }
                else if (layersFront != null && !model.isPerson) LayersDisplayScreen(chorus, model, layersFront!!) { layersFront = null }
                else if (frontsOpen && !model.isPerson) FrontsScreen(chorus, model) { frontsOpen = false }
                else if (settingsOpen) SettingsScreen(chorus, model) { insightsOpen = true; settingsOpen = false }
                else androidx.compose.animation.Crossfade(targetState = activeTab,
                    animationSpec = androidx.compose.animation.core.tween(if (motion) 120 else 0), label = "Main destination") { shownTab ->
                destinationState.SaveableStateProvider(shownTab.name) {
                when (shownTab) {
                    Tab.Home -> Home(chorus, model, onFronts = { frontsOpen = true }, onHistory = { historyFront = it; tab = Tab.History },
                        onLayers = { layersFront = it })
                    Tab.Notifications -> Notifications(chorus, model, InboxRoutes(
                        onPost = { id -> journalReplyPost = null; journalOpenPost = id; tab = Tab.Journal },
                        onChat = { channel, message ->
                            chatSpace = model.channels.find { it.id == channel }?.spaceId; chatChannel = channel
                            chatMessage = message; chatSearchHit = null; tab = Tab.Chat
                        },
                        onFriends = { tab = Tab.People },
                        onHistory = { tab = if (person) Tab.Journal else Tab.History },
                        onSettings = { settingsOpen = true }))
                    Tab.Chat -> Chat(chorus, model, chatSpace, chatChannel, chatSearchHit,
                        onStageCapture = { chatStageCapture = it }, onDismissSearchHit = { chatSearchHit = null },
                        sharedDraft = chatShare, sharedChannel = sharedChannel,
                        onShareConsumed = { chatShare = null; sharedChannel = null }, onSearch = { searchSection="Messages"; searchOpen = true },
                        active = shownTab == activeTab, onFocusedRoute = { chatFocused = it },
                        onOpenConsumed = { chatSpace = null; chatChannel = null; chatMessage = null },
                        requestedMessage = chatMessage,
                        onOpenProfile = { journalOpenProfile = it; journalOpenPost = null; tab = Tab.Journal })
                    Tab.Journal -> Journal(chorus, model, journalReplyPost,
                        onExternalReplyConsumed = { journalReplyPost = null },
                        externalOpenPost = journalOpenPost,
                        onExternalOpenConsumed = { journalOpenPost = null },
                        externalOpenProfile = journalOpenProfile, onExternalProfileConsumed = { journalOpenProfile = null },
                        sharedDraft = journalShare, onShareConsumed = { journalShare = null },
                        externalNewEntry = journalNewEntry, onExternalNewEntryConsumed = { journalNewEntry = null },
                        active = shownTab == activeTab, onFocusedRoute = { journalFocused = it },
                        onSearch = { searchSection=it; searchOpen=true })
                    Tab.People -> FriendsScreen(chorus, model,
                        onOpenChat = { spaceId -> chatSpace = spaceId; chatChannel = null; chatSearchHit = null; tab = Tab.Chat },
                        onReplyPost = { postId -> journalReplyPost = postId; tab = Tab.Journal },
                        onFocusedRoute = { friendsFocused = it })
                    Tab.Members -> Members(chorus, model, onJournal = { member, post ->
                        journalOpenProfile = member; journalOpenPost = post; tab = Tab.Journal
                    }, active = shownTab == activeTab, onFocusedRoute = { membersFocused = it })
                    Tab.History -> History(chorus, model, historyFront, onBack = { tab = Tab.Home })
                    Tab.More -> MoreScreen(person, onMembers = { tab = Tab.Members }, onHome = { tab = Tab.Home }, onChat = { tab = Tab.Chat }, onPeople = { tab = Tab.People }, onJournal = { tab = Tab.Journal }, onHistory = { tab = Tab.History },
                        onInsights = { insightsOpen = true }, onFronts = { frontsOpen = true }, onSettings = { settingsOpen = true },
                        onSearch = { searchSection="Messages"; searchOpen = true }, onLink = { linking = true },
                        onNotifications = { tab = Tab.Notifications })
                }
                }
                }
            }
            // The bar and viewport resize together during push/pop; focused routes own their
            // chrome. The shell applies the system navigation inset exactly once.
            val focused = (frontsOpen || layersFront != null) && !searchOpen && !settingsOpen && !insightsOpen ||
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
                    selected = (t == activeTab || (t == Tab.Home && activeTab == Tab.History) ||
                        (t == Tab.More && activeTab !in destinations && !(activeTab == Tab.History && Tab.Home in destinations))) &&
                        !settingsOpen && !searchOpen && !insightsOpen && !frontsOpen,
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
    Column(Modifier.fillMaxSize().background(p.bg).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Share into Chorus", color = p.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
        Text(listOfNotNull("Text".takeIf { draft.text.isNotBlank() },
            "${draft.uris.size} file${if (draft.uris.size == 1) "" else "s"}".takeIf { draft.uris.isNotEmpty() })
            .joinToString(" · "), color = p.ink2, modifier = Modifier.padding(bottom = 12.dp))
        Text("Choose where to prepare this draft. You can review it before posting or sending.",
            color = p.ink2, modifier = Modifier.padding(bottom = 12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (model.active.any { it.createdByAccountId == null || it.createdByAccountId == accountId }) item {
                Text("Post as…", color = p.accent, fontSize = 16.sp,
                    modifier = Modifier.fillMaxWidth().background(p.surface, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .clickable(onClick = onPost).padding(16.dp))
            }
            if (model.channels.isNotEmpty()) item {
                Text("Send to channel…", color = p.ink, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
            }
            items(model.channels, key = { it.id }) { channel ->
                val space = model.spaces.find { it.id == channel.spaceId }
                Text("${space?.name ?: "Chat"} · #${channel.name}", color = p.accent,
                    modifier = Modifier.fillMaxWidth().background(p.surface, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .clickable { onChannel(channel) }.padding(16.dp))
            }
            if (model.active.isEmpty() && model.channels.isEmpty()) item {
                Text("No place to share yet. Finish setting up your account first.", color = p.ink2)
            }
        }
    }
}
