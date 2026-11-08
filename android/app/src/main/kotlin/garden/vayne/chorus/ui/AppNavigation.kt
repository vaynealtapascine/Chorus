package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.PeopleApi
import garden.vayne.chorus.data.Reply
import garden.vayne.chorus.data.Status
import garden.vayne.chorus.data.cap
import garden.vayne.chorus.designsystem.LocalChorusAppearance
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R

enum class AppIcon(val resource: Int) {
    Members(R.drawable.ic_members), Front(R.drawable.ic_front), Chat(R.drawable.ic_chat),
    Friends(R.drawable.ic_friends), More(R.drawable.ic_more), Journal(R.drawable.ic_journal),
    Search(R.drawable.ic_search), Settings(R.drawable.ic_settings), History(R.drawable.ic_history),
    Swap(R.drawable.ic_switch_arrows), Plus(R.drawable.ic_plus), Close(R.drawable.ic_close),
    Chevron(R.drawable.ic_chevron), Layers(R.drawable.ic_layers), Lock(R.drawable.ic_lock), Bell(R.drawable.ic_bell), Home(R.drawable.ic_home),
}

@Composable
fun ChorusIcon(icon: AppIcon, description: String? = null, modifier: Modifier = Modifier) {
    Icon(painterResource(icon.resource), description, modifier.size(22.dp))
}

/** Where More's rows lead: a destination by its saved key, Insights, a settings page, search, the bar editor. */
internal class MoreRoutes(val go: (String) -> Unit, val onInsights: () -> Unit, val onSettings: (SettingsPage) -> Unit,
    val onSearch: () -> Unit, val onEditBar: () -> Unit)

/**
 * F10.1 More: *Go to* what isn't on the bar (Home always, D-130), then *Settings*, then Edit bottom
 * bar. Each row's line under it is its state where there is one (counts, the current look).
 */
@Composable
internal fun MoreScreen(chorus: Chorus, model: Model, bar: List<String>, unread: Int, routes: MoreRoutes) {
    val p = LocalChorusPalette.current
    val person = model.isPerson
    val status by chorus.status.collectAsState()
    val issues by chorus.syncIssues.collectAsState()
    val appearance = LocalChorusAppearance.current
    var following by remember { mutableStateOf<Int?>(null) }
    var requests by remember { mutableIntStateOf(0) }
    LaunchedEffect(chorus.device?.session, status == Status.Live) {
        val dev = chorus.device ?: return@LaunchedEffect
        if (status != Status.Live) return@LaunchedEffect
        runCatching { PeopleApi.list(dev) }.getOrNull()?.let { f ->
            following = f.following.count { it.status == "active" }
            requests = f.followers.count { it.status == "requested" }
        }
    }
    val reader = if (person) model.members.firstOrNull { it.isSelf } else Reply.speaker(model)
    val owner = (if (person) reader?.shownName else model.systemName?.takeIf { it.isNotBlank() }) ?: model.accountHandle?.let { "@$it" }
    val front = model.current.filter { it.subjectType == "member" && it.level == "front" }.mapNotNull { model.member(it.subjectId)?.shownName }
    Column(Modifier.fillMaxSize().background(p.bg)) {
        Row(Modifier.fillMaxWidth().background(p.surface).heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            reader?.let { ChatAvatar(it, 32.dp); Spacer(Modifier.width(10.dp)) }
            Column(Modifier.weight(1f)) {
                Text("More", color = p.ink, fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold)
                listOfNotNull(owner, reader?.takeIf { !person }?.let { "reading as ${it.shownName}" }).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                    Text(it, color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            BarIcon(R.drawable.ic_search, "Search", p.ink, onClick = routes.onSearch)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item { SectionLabel("Go to", Modifier.padding(horizontal = 12.dp)) }
            fun shown(key: String) = key !in bar
            item { NavRow("Home", R.drawable.ic_home) { routes.go("Today") } }
            if (!person && shown("Home")) item { NavRow(model.words.fronting.cap(), AppIcon.Front.resource, front.joinToString(", ").takeIf { it.isNotEmpty() }) { routes.go("Home") } }
            if (!person && shown("Members")) item {
                NavRow("Members", R.drawable.ic_users, countLine(model.active.size, "member", model.groups.size, "group")) { routes.go("Members") }
            }
            if (shown("Notifications")) item { NavRow("Notifications", R.drawable.ic_bell, badge = unread) { routes.go("Notifications") } }
            if (shown("Chat")) item { NavRow("Chat", AppIcon.Chat.resource) { routes.go("Chat") } }
            if (shown("Journal")) item { NavRow("Journal", R.drawable.ic_book) { routes.go("Journal") } }
            if (shown("People")) item {
                NavRow("Friends", R.drawable.ic_user_plus, following?.let { "$it following" }, badge = requests) { routes.go("People") }
            }
            if (!person) item { NavRow("Fronting insights", AppIcon.History.resource, onClick = routes.onInsights) }
            item { SectionLabel("Settings", Modifier.padding(horizontal = 12.dp)) }
            if (issues.isNotEmpty()) item {
                NavRow("Sync issues · ${issues.size}", R.drawable.ic_alert, danger = true) { routes.onSettings(SettingsPage.Issues) }
            }
            item { NavRow("Appearance", R.drawable.ic_type, appearanceSummary(appearance.mode, appearance.accent, appearance.spacing)) {
                routes.onSettings(SettingsPage.Appearance) } }
            item { NavRow("Notification settings", R.drawable.ic_bell) { routes.onSettings(SettingsPage.Notifications) } }
            if (!person) item { NavRow("Privacy and sharing", R.drawable.ic_shield) { routes.onSettings(SettingsPage.Privacy) } }
            item { NavRow("Your data and devices", R.drawable.ic_down) { routes.onSettings(SettingsPage.Data) } }
            item {
                NavRow(if (person) "Account" else "System settings", R.drawable.ic_sliders,
                    listOfNotNull(model.systemName?.takeIf { !person && it.isNotBlank() }, model.accountHandle?.let { "@$it" }).joinToString(" · ").takeIf { it.isNotEmpty() }) {
                    routes.onSettings(SettingsPage.System)
                }
            }
            item { NavRow("Edit bottom bar", R.drawable.ic_grip, bar.joinToString(" · ", transform = ::destinationLabel), onClick = routes.onEditBar) }
        }
    }
}

/** "4 members · 2 groups"; a zero count is left out. */
internal fun countLine(a: Int, aWord: String, b: Int, bWord: String): String? =
    listOfNotNull(a.takeIf { it > 0 }?.let { "$it $aWord${if (it == 1) "" else "s"}" },
        b.takeIf { it > 0 }?.let { "$it $bWord${if (it == 1) "" else "s"}" }).joinToString(" · ").takeIf { it.isNotEmpty() }
