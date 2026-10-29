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
import garden.vayne.chorus.ui.People
import garden.vayne.chorus.ui.SettingsScreen
import garden.vayne.chorus.ui.ContentSearch
import garden.vayne.chorus.ui.Journal
import garden.vayne.chorus.ui.InsightsScreen

class MainActivity : ComponentActivity() {
    private var inviteLink = mutableStateOf<String?>(null)
    private var sharedDraft = mutableStateOf<SharedDraft?>(null)
    private var nextShareId = 0L
    private var newEntryRequest = mutableStateOf<Long?>(null)
    private var nextEntryId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        inviteLink.value = inviteFrom(intent)
        sharedDraft.value = SharedDraft.from(intent, ++nextShareId)
        if (intent.action == "garden.vayne.chorus.NEW_ENTRY") newEntryRequest.value = ++nextEntryId
        val chorus = Chorus.get(this)
        SyncWork.schedulePeriodic(this)
        // switch notifications (M8.1): ask once on Android 13+, then register with ntfy if present
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        garden.vayne.chorus.data.Push.ensure(this)
        garden.vayne.chorus.data.Updater.schedule(this)
        setContent { InspectionOverrides(intent) { ChorusTheme { App(chorus, inviteLink.value, sharedDraft.value,
            onShareDismissed = { sharedDraft.value = null }, newEntryRequest = newEntryRequest.value,
            onNewEntryConsumed = { newEntryRequest.value = null }) } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        inviteFrom(intent)?.let { inviteLink.value = it }
        SharedDraft.from(intent, ++nextShareId)?.let { sharedDraft.value = it }
        if (intent.action == "garden.vayne.chorus.NEW_ENTRY") {
            sharedDraft.value = null
            newEntryRequest.value = ++nextEntryId
        }
    }

    override fun onStart() {
        super.onStart()
        Chorus.get(this).setForeground(true)
    }

    override fun onStop() {
        Chorus.get(this).setForeground(false)
        super.onStop()
    }

    private fun inviteFrom(i: Intent?): String? =
        i?.takeIf { it.action == Intent.ACTION_VIEW }?.data?.toString()?.takeIf { "/i/" in it }
}

private enum class Tab(val label: String, val icon: AppIcon) { Home("Fronting", AppIcon.Front), Chat("Chat", AppIcon.Chat), Journal("Journal", AppIcon.Journal), People("Friends", AppIcon.Friends), Members("Members", AppIcon.Members), More("More", AppIcon.More), History("Front history", AppIcon.History) }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun App(chorus: Chorus, invite: String?, sharedDraft: SharedDraft?,
    onShareDismissed: () -> Unit, newEntryRequest: Long?, onNewEntryConsumed: () -> Unit) {
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
    var chatStageCapture by remember { mutableStateOf(false) }
    var chatFocused by rememberSaveable { mutableStateOf(false) }
    var journalFocused by rememberSaveable { mutableStateOf(false) }
    var membersFocused by rememberSaveable { mutableStateOf(false) }
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
    var insightsOpen by rememberSaveable { mutableStateOf(false) }
    var frontsOpen by rememberSaveable { mutableStateOf(false) }
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
            searchOpen = false; settingsOpen = false; insightsOpen = false; frontsOpen = false
        }
        lastAccount = account
    }
    LaunchedEffect(newEntryRequest, status) {
        if (newEntryRequest == null || status == Status.Loading || status == Status.NoDevice || status == Status.StorageError) return@LaunchedEffect
        journalNewEntry = newEntryRequest
        journalReplyPost = null; journalOpenPost = null
        searchOpen = false; settingsOpen = false; insightsOpen = false; frontsOpen = false
        tab = Tab.Journal
        onNewEntryConsumed()
    }
    BackHandler(sharedDraft != null || settingsOpen || searchOpen || insightsOpen || frontsOpen) {
        if (sharedDraft != null) onShareDismissed()
        else if (searchOpen) searchOpen = false else if (insightsOpen) insightsOpen = false else if (frontsOpen) frontsOpen = false else settingsOpen = false
    }
    if (linking && status != Status.NoDevice && status != Status.Loading) DeviceLink(chorus) { linking = false }
    if (quickSwitchOpen && !model.isPerson) Switcher(chorus, model) { quickSwitchOpen = false }
    BackHandler(sharedDraft == null && !linking && !quickSwitchOpen && !settingsOpen && !searchOpen && !insightsOpen && !frontsOpen && routedTab !in navigationTabs) {
        tab = Tab.More
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
            fun select(t: Tab) { tab = t; settingsOpen = false; searchOpen = false; insightsOpen = false; frontsOpen = false }
            if (expanded && !chatStageCapture) NavigationRail(containerColor = p.surface) {
                destinations.forEach { t -> NavigationRailItem(selected = t == activeTab || (t == Tab.More && activeTab !in destinations),
                    onClick = { select(t) }, icon = { ChorusIcon(t.icon) }, label = { Text(t.label) }) }
            }
            Column(Modifier.weight(1f).navigationBarsPadding()) {
            val chatOwnChrome = (activeTab in listOf(Tab.Chat, Tab.Journal) || (activeTab == Tab.Members && membersFocused)) && !searchOpen && !settingsOpen && !insightsOpen && !frontsOpen && sharedDraft == null
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
                IconButton(onClick = { searchOpen = !searchOpen; settingsOpen = false; insightsOpen = false; frontsOpen = false }) { ChorusIcon(if (searchOpen) AppIcon.Close else AppIcon.Search, if (searchOpen) "Close search" else "Search") }
                if (!person) IconButton(onClick = { quickSwitchOpen = true }) { ChorusIcon(AppIcon.Swap, "Open switcher") }
                IconButton(onClick = { settingsOpen = !settingsOpen; searchOpen = false; insightsOpen = false; frontsOpen = false }) { ChorusIcon(if (settingsOpen) AppIcon.Close else AppIcon.Settings, if (settingsOpen) "Close settings" else "Settings") }
            }
            Box(Modifier.weight(1f)) {
                if (sharedDraft != null) ShareChooser(sharedDraft, model, chorus.device?.accountId,
                    onCancel = onShareDismissed,
                    onPost = {
                        journalShare = sharedDraft
                        journalReplyPost = null; journalOpenPost = null
                        tab = Tab.Journal
                        searchOpen = false; settingsOpen = false; insightsOpen = false; frontsOpen = false
                        onShareDismissed()
                    },
                    onChannel = { selected ->
                        chatShare = sharedDraft
                        sharedChannel = selected.id
                        chatSpace = selected.spaceId; chatChannel = selected.id; chatSearchHit = null
                        tab = Tab.Chat
                        searchOpen = false; settingsOpen = false; insightsOpen = false; frontsOpen = false
                        onShareDismissed()
                    })
                else if (searchOpen) ContentSearch(chorus, model, status == Status.Live,
                    onClose = { searchOpen = false },
                    onOpenMessage = { hit ->
                        val channel = model.channels.find { it.id == hit.channelId }
                        if (channel != null) {
                            chatSpace = channel.spaceId; chatChannel = channel.id; chatSearchHit = hit
                            tab = Tab.Chat; searchOpen = false
                        }
                    },
                    onOpenPost = { id ->
                        journalReplyPost = null; journalOpenPost = id; tab = Tab.Journal; searchOpen = false
                    })
                else if (insightsOpen) InsightsScreen(chorus, model) { insightsOpen = false }
                else if (frontsOpen && !model.isPerson) FrontsScreen(chorus, model) { frontsOpen = false }
                else if (settingsOpen) SettingsScreen(chorus, model) { insightsOpen = true; settingsOpen = false }
                else androidx.compose.animation.Crossfade(targetState = activeTab,
                    animationSpec = androidx.compose.animation.core.tween(if (motion) 120 else 0), label = "Main destination") { shownTab ->
                destinationState.SaveableStateProvider(shownTab.name) {
                when (shownTab) {
                    Tab.Home -> Home(chorus, model, onFronts = { frontsOpen = true })
                    Tab.Chat -> Chat(chorus, model, chatSpace, chatChannel, chatSearchHit,
                        onStageCapture = { chatStageCapture = it }, onDismissSearchHit = { chatSearchHit = null },
                        sharedDraft = chatShare, sharedChannel = sharedChannel,
                        onShareConsumed = { chatShare = null; sharedChannel = null }, onSearch = { searchOpen = true },
                        active = shownTab == activeTab, onFocusedRoute = { chatFocused = it },
                        onOpenConsumed = { chatSpace = null; chatChannel = null })
                    Tab.Journal -> Journal(chorus, model, journalReplyPost,
                        onExternalReplyConsumed = { journalReplyPost = null },
                        externalOpenPost = journalOpenPost,
                        onExternalOpenConsumed = { journalOpenPost = null },
                        externalOpenProfile = journalOpenProfile, onExternalProfileConsumed = { journalOpenProfile = null },
                        sharedDraft = journalShare, onShareConsumed = { journalShare = null },
                        externalNewEntry = journalNewEntry, onExternalNewEntryConsumed = { journalNewEntry = null },
                        active = shownTab == activeTab, onFocusedRoute = { journalFocused = it })
                    Tab.People -> People(chorus, model,
                        onOpenChat = { spaceId -> chatSpace = spaceId; chatChannel = null; chatSearchHit = null; tab = Tab.Chat },
                        onReplyPost = { postId -> journalReplyPost = postId; tab = Tab.Journal })
                    Tab.Members -> Members(chorus, model, onJournal = { member, post ->
                        journalOpenProfile = member; journalOpenPost = post; tab = Tab.Journal
                    }, active = shownTab == activeTab, onFocusedRoute = { membersFocused = it })
                    Tab.History -> History(chorus, model)
                    Tab.More -> MoreScreen(person, onMembers = { tab = Tab.Members }, onHome = { tab = Tab.Home }, onChat = { tab = Tab.Chat }, onPeople = { tab = Tab.People }, onJournal = { tab = Tab.Journal }, onHistory = { tab = Tab.History },
                        onInsights = { insightsOpen = true }, onFronts = { frontsOpen = true }, onSettings = { settingsOpen = true },
                        onSearch = { searchOpen = true }, onLink = { linking = true })
                }
                }
                }
            }
            // The bar and viewport resize together during push/pop; focused routes own their
            // chrome. The shell applies the system navigation inset exactly once.
            val focused = !searchOpen && !settingsOpen && !insightsOpen && !frontsOpen &&
                ((activeTab == Tab.Chat && chatFocused) || (activeTab == Tab.Journal && journalFocused) || (activeTab == Tab.Members && membersFocused))
            androidx.compose.animation.AnimatedVisibility(
                visible = !expanded && sharedDraft == null && !chatStageCapture && !WindowInsets.isImeVisible && !focused,
                enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(if (motion) 220 else 0)) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(if (motion) 180 else 0)),
                exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(if (motion) 220 else 0)) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(if (motion) 100 else 0))) {
            NavigationBar(
                containerColor = p.surface, tonalElevation = 0.dp, windowInsets = WindowInsets(0, 0, 0, 0),
            ) {
                destinations.forEach { t -> NavigationBarItem(
                    selected = (t == activeTab || (t == Tab.More && activeTab !in destinations)) && !settingsOpen && !searchOpen && !insightsOpen && !frontsOpen,
                    onClick = { select(t) }, icon = { ChorusIcon(t.icon) }, label = {
                        Text(t.label, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
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
