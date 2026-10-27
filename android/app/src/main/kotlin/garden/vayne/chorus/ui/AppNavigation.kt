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
    Swap(R.drawable.ic_swap), Plus(R.drawable.ic_plus), Close(R.drawable.ic_close),
    Chevron(R.drawable.ic_chevron), Layers(R.drawable.ic_layers), Lock(R.drawable.ic_lock),
}

@Composable
fun ChorusIcon(icon: AppIcon, description: String? = null, modifier: Modifier = Modifier) {
    Icon(painterResource(icon.resource), description, modifier.size(22.dp))
}

@Composable
fun MoreScreen(person: Boolean, onJournal: () -> Unit, onHistory: () -> Unit,
    onInsights: () -> Unit, onFronts: () -> Unit, onSettings: () -> Unit, onSearch: () -> Unit, onLink: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { DestinationRow("Journal", "Entries, notes and member journals", AppIcon.Journal, onJournal) }
        if (!person) {
            item { DestinationRow("Fronts and layers", "Separate fronts, following and who counts for what", AppIcon.Front, onFronts) }
            item { DestinationRow("Front history", "Look back, amend or log a switch", AppIcon.History, onHistory) }
            item { DestinationRow("Fronting insights", "History, patterns and exports", AppIcon.Layers, onInsights) }
        }
        item { DestinationRow("Appearance & preferences", "Theme, notifications and privacy", AppIcon.Settings, onSettings) }
        item { DestinationRow("Search", "Messages, posts and switches on this device", AppIcon.Search, onSearch) }
        item { DestinationRow("Link another device", "Use Chorus on another phone or browser", AppIcon.Plus, onLink) }
    }
}

@Composable
private fun DestinationRow(title: String, description: String, icon: AppIcon, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.surface)
        .clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(shape = RoundedCornerShape(50), color = p.accentSoft, contentColor = p.accent) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { ChorusIcon(icon) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = p.ink, fontWeight = FontWeight.SemiBold)
            Text(description, color = p.ink3, style = MaterialTheme.typography.bodySmall)
        }
        ChorusIcon(AppIcon.Chevron)
    }
}
