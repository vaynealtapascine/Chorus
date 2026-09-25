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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        inviteLink.value = inviteFrom(intent)
        sharedDraft.value = SharedDraft.from(intent, ++nextShareId)
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
        setContent { ChorusTheme { App(chorus, inviteLink.value, sharedDraft.value) { sharedDraft.value = null } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        inviteFrom(intent)?.let { inviteLink.value = it }
        SharedDraft.from(intent, ++nextShareId)?.let { sharedDraft.value = it }
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

private enum class Tab(val label: String) { Home("Home"), Chat("Chat"), Journal("Journal"), People("People"), Members("Members"), History("History") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun App(chorus: Chorus, invite: String?, sharedDraft: SharedDraft?, onShareDismissed: () -> Unit) {
    val p = LocalChorusPalette.current
    val status by chorus.status.collectAsState()
    val model by chorus.model.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var chatSpace by rememberSaveable { mutableStateOf<String?>(null) }
    var chatChannel by rememberSaveable { mutableStateOf<String?>(null) }
    var chatSearchHit by remember { mutableStateOf<SearchDocument?>(null) }
    var chatStageCapture by remember { mutableStateOf(false) }
    var journalReplyPost by rememberSaveable { mutableStateOf<String?>(null) }
    var journalOpenPost by rememberSaveable { mutableStateOf<String?>(null) }
    var chatShare by remember { mutableStateOf<SharedDraft?>(null) }
    var journalShare by remember { mutableStateOf<SharedDraft?>(null) }
    var sharedChannel by remember { mutableStateOf<String?>(null) }
    var linking by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var insightsOpen by rememberSaveable { mutableStateOf(false) }
    var lastAccount by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(chorus.device?.accountId, status) {
        if (status == Status.Loading) return@LaunchedEffect
        val account = chorus.device?.accountId
        if (lastAccount != null && lastAccount != account) {
            chatSearchHit = null
            chatStageCapture = false
            chatSpace = null; chatChannel = null
            journalReplyPost = null; journalOpenPost = null
            chatShare = null; journalShare = null; sharedChannel = null
            onShareDismissed()
            searchOpen = false; settingsOpen = false; insightsOpen = false
        }
        lastAccount = account
    }
    BackHandler(sharedDraft != null || settingsOpen || searchOpen || insightsOpen) {
        if (sharedDraft != null) onShareDismissed()
        else if (searchOpen) searchOpen = false else if (insightsOpen) insightsOpen = false else settingsOpen = false
    }
    if (linking && status != Status.NoDevice && status != Status.Loading) DeviceLink(chorus) { linking = false }

    when (status) {
        Status.Loading -> Box(Modifier.fillMaxSize().background(p.bg))
        Status.NoDevice -> Onboarding(chorus, invite)
        Status.StorageError -> Box(Modifier.fillMaxSize().background(p.bg).padding(24.dp), contentAlignment = Alignment.Center) {
            Text("The local replica could not be opened or saved. Keep this app's data intact and check the device storage.", color = p.danger)
        }
        else -> Column(Modifier.fillMaxSize().background(p.bg)) {
            val person = model.isPerson
            val activeTab = if (person && (tab == Tab.Home || tab == Tab.Members || tab == Tab.History))
                Tab.Journal else tab
            if (!chatStageCapture) Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Chorus", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = p.ink)
                Spacer(Modifier.weight(1f))
                Text("⌕", fontSize = 24.sp, color = p.accent,
                    modifier = Modifier.clickable { searchOpen = !searchOpen; settingsOpen = false; insightsOpen = false }
                        .padding(horizontal = 8.dp, vertical = 4.dp).semantics { contentDescription = "Search" })
                Text(if (settingsOpen) "Close settings" else if (insightsOpen) "Close insights" else "Settings", fontSize = 12.sp, color = p.accent,
                    modifier = Modifier.clickable { if (insightsOpen) insightsOpen = false else settingsOpen = !settingsOpen; searchOpen = false }
                        .padding(horizontal = 10.dp, vertical = 6.dp))
                Text("Link device", fontSize = 12.sp, color = p.accent,
                    modifier = Modifier.clickable { linking = true }.padding(horizontal = 10.dp, vertical = 6.dp))
                val (dot, label) = when (status) {
                    Status.Live -> p.ok to "live"
                    Status.Connecting -> p.warn to "connecting"
                    else -> p.ink3 to "offline"
                }
                Box(Modifier.size(8.dp).background(dot, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(label, fontSize = 12.sp, color = p.ink3)
            }
            Box(Modifier.weight(1f)) {
                if (sharedDraft != null) ShareChooser(sharedDraft, model, chorus.device?.accountId,
                    onCancel = onShareDismissed,
                    onPost = {
                        journalShare = sharedDraft
                        journalReplyPost = null; journalOpenPost = null
                        tab = Tab.Journal
                        searchOpen = false; settingsOpen = false; insightsOpen = false
                        onShareDismissed()
                    },
                    onChannel = { selected ->
                        chatShare = sharedDraft
                        sharedChannel = selected.id
                        chatSpace = selected.spaceId; chatChannel = selected.id; chatSearchHit = null
                        tab = Tab.Chat
                        searchOpen = false; settingsOpen = false; insightsOpen = false
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
                else if (insightsOpen) InsightsScreen(model) { insightsOpen = false }
                else if (settingsOpen) SettingsScreen(chorus, model) { insightsOpen = true; settingsOpen = false }
                else when (activeTab) {
                    Tab.Home -> Home(chorus, model)
                    Tab.Chat -> Chat(chorus, model, chatSpace, chatChannel, chatSearchHit,
                        onStageCapture = { chatStageCapture = it }, onDismissSearchHit = { chatSearchHit = null },
                        sharedDraft = chatShare, sharedChannel = sharedChannel,
                        onShareConsumed = { chatShare = null; sharedChannel = null })
                    Tab.Journal -> Journal(chorus, model, journalReplyPost,
                        onExternalReplyConsumed = { journalReplyPost = null },
                        externalOpenPost = journalOpenPost,
                        onExternalOpenConsumed = { journalOpenPost = null },
                        sharedDraft = journalShare, onShareConsumed = { journalShare = null })
                    Tab.People -> People(chorus, model,
                        onOpenChat = { spaceId -> chatSpace = spaceId; chatChannel = null; chatSearchHit = null; tab = Tab.Chat },
                        onReplyPost = { postId -> journalReplyPost = postId; tab = Tab.Journal })
                    Tab.Members -> Members(chorus, model)
                    Tab.History -> History(chorus, model)
                }
            }
            // the keyboard covers the tabs anyway; hiding them lets a screen's imePadding sit on it
            if (sharedDraft == null && !settingsOpen && !searchOpen && !insightsOpen && !chatStageCapture && !WindowInsets.isImeVisible) Row(
                Modifier.fillMaxWidth().background(p.surface).navigationBarsPadding().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Tab.entries.filter { !person || (it != Tab.Home && it != Tab.Members && it != Tab.History) }.forEach { t ->
                    Text(
                        t.label,
                        color = if (t == activeTab) p.accent else p.ink2,
                        fontWeight = if (t == activeTab) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 12.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).clickable { tab = t }.padding(horizontal = 1.dp, vertical = 12.dp)
                            .semantics { contentDescription = t.label },
                    )
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
