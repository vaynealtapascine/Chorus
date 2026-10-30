package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.JournalPost
import garden.vayne.chorus.data.JournalSocial
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R

/** Posts this account saved with the bookmark, newest first. Saves are private account prefs,
 * so they are read from the local replica and work offline. */
@Composable
internal fun JournalSaved(chorus: Chorus, model: Model, onBack: () -> Unit, onReply: (String) -> Unit,
    onThread: (String) -> Unit, onQuote: (JournalPost) -> Unit, onProfile: (String) -> Unit) {
    val p = LocalChorusPalette.current
    val saved = JournalSocial.saved(model.posts, model.accountPrefs.bookmarks)
    Column(Modifier.fillMaxSize().background(p.bg)) {
        Row(Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ChatIconButton(R.drawable.ic_back, "Back to journal", onBack)
            Text("Saved", color = p.ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 8.dp))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 80.dp)) {
            if (saved.isEmpty()) item {
                Text("Nothing saved yet. Tap the bookmark on a post to keep it here.", color = p.ink2, modifier = Modifier.padding(24.dp))
            }
            items(saved, key = { "saved:${it.id}" }) { post ->
                JournalPostCard(post, model, chorus, onReply = { onReply(it.id) }, onThread = { onThread(it.id) },
                    onQuote = onQuote, onOpenPost = onThread, onProfile = onProfile)
            }
        }
    }
}
