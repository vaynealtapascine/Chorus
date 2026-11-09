package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.JournalPost
import garden.vayne.chorus.data.JournalPresentation
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R

/**
 * D-135 / F6.9: our system's own posts (private, top level) with Posts and Polls tabs. A client view
 * over posts every device already holds; nothing is fetched, so it reads offline. "+" starts a
 * private post.
 */
@Composable
internal fun SystemBoard(chorus: Chorus, model: Model, onBack: () -> Unit, onNew: () -> Unit, onReply: (JournalPost) -> Unit,
    onThread: (JournalPost) -> Unit, onQuote: (JournalPost) -> Unit, onProfile: (String) -> Unit) {
    val p = LocalChorusPalette.current
    var tab by rememberSaveable { mutableStateOf(0) }
    val posts = model.posts.filter(JournalPresentation::isBoardPost).sortedByDescending { it.occurredAt }
    val polls = posts.filter { it.id in model.polls.polls }
    val shown = if (tab == 1) polls else posts
    Column(Modifier.fillMaxSize().background(p.bg)) {
        Row(Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ChatIconButton(R.drawable.ic_back, "Back", onBack)
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text("System board", color = p.ink, style = MaterialTheme.typography.titleMedium)
                Text(listOfNotNull(model.systemName?.takeIf { it.isNotBlank() }, "internal").joinToString(" · "), color = p.ink3,
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ChatIconButton(R.drawable.ic_plus_circle, "New post", onClick = onNew)
        }
        UnderlineTabs(listOf("Posts" to 0, "Polls" to 0), tab) { tab = it }
        LazyColumn(Modifier.fillMaxSize()) {
            if (shown.isEmpty()) item { EmptyState(if (tab == 1) R.drawable.ic_poll else R.drawable.ic_journal, if (tab == 1) "No polls yet" else "Nothing yet",
                action = "New post", onAction = onNew) }
            items(shown, key = { it.id }) { post ->
                JournalPostCard(post, model, chorus, onReply = onReply, onThread = onThread, onQuote = onQuote,
                    onOpenPost = { id -> model.posts.find { it.id == id }?.let(onThread) }, onProfile = onProfile)
            }
        }
    }
}
