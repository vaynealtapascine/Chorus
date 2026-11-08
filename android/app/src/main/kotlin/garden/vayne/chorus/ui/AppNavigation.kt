package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

@Composable
fun MoreScreen(person: Boolean, onDashboard: () -> Unit = {}, onMembers: () -> Unit, onHome: () -> Unit, onChat: () -> Unit, onPeople: () -> Unit, onJournal: () -> Unit,
    onInsights: () -> Unit, onSettings: () -> Unit, onSearch: () -> Unit, onLink: () -> Unit,
    onNotifications: () -> Unit = {}) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Chorus opens on Home (D-130); it is listed here whether or not it is on the bar
        item { DestinationRow("Home", AppIcon.Home, onDashboard) }
        if (!person) {
            item { DestinationRow("Members", AppIcon.Members, onMembers) }
            item { DestinationRow("Fronting", AppIcon.Front, onHome) }
        }
        item { DestinationRow("Notifications", AppIcon.Bell, onNotifications) }
        item { DestinationRow("Chat", AppIcon.Chat, onChat) }
        item { DestinationRow("Friends", AppIcon.Friends, onPeople) }
        item { DestinationRow("Journal", AppIcon.Journal, onJournal) }
        // fronts and front history open from the Fronting screen's bar (UI21)
        if (!person) item { DestinationRow("Fronting insights", AppIcon.Layers, onInsights) }
        item { DestinationRow("Appearance & preferences", AppIcon.Settings, onSettings) }
        item { DestinationRow("Search", AppIcon.Search, onSearch) }
        item { DestinationRow("Link another device", AppIcon.Plus, onLink) }
    }
}

@Composable
private fun DestinationRow(title: String, icon: AppIcon, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.surface)
        .clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(shape = RoundedCornerShape(50), color = p.accentSoft, contentColor = p.accent) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { ChorusIcon(icon) }
        }
        Text(title, color = p.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        ChorusIcon(AppIcon.Chevron)
    }
}
